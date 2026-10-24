package dev.aerove.lite.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.aerove.lite.data.MeowPhysicalNetwork
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.ProviderDownloadFactory
import dev.aerove.lite.data.ProviderDownloadRequest
import dev.aerove.lite.data.ProviderDownloadResult
import dev.aerove.lite.data.ProviderDownloadScope
import dev.aerove.lite.vpn.captureMeowPhysical
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Candidate-owned resource transport; it never connects to a running core's listener. */
object JniResourceFetch {
    init { System.loadLibrary("aerove_meow_core") }
    @JvmStatic private external fun nativeCreate(plan: String): Long
    @JvmStatic private external fun nativeLoad(handle: Long, candidate: String)
    @JvmStatic private external fun nativeDownload(handle: Long, request: String): String
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeCloseJoin(handle: Long)
    @JvmStatic private external fun nativeValidatePrepared(candidate: String, readRoot: String, cacheRoot: String): String?

    @JvmStatic private external fun nativePreflightRuleResource(path: String, behavior: String, format: String): String

    fun preflightRuleResource(path: File, behavior: String, format: String): String =
        nativePreflightRuleResource(path.canonicalPath, behavior, format)

    fun factory(context: Context): ProviderDownloadFactory {
        val app = context.applicationContext
        return ProviderDownloadFactory { input ->
            var allocated: NativeScope? = null
            try {
                withContext(Dispatchers.IO) {
                    val staging = privateDirectory(app, input.stagingRoot)
                    val read = input.resourceReadRoot?.let { privateDirectory(app, it) }
                    val cache = cacheDirectory(app)
                    try {
                        val manager = app.getSystemService(ConnectivityManager::class.java)
                        val available = manager.allNetworks.filter { network ->
                            manager.getNetworkCapabilities(network)?.let { caps ->
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            } == true
                        }
                        val selected = available.firstOrNull { it == manager.activeNetwork }
                            ?: available.firstOrNull { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                            ?: available.firstOrNull()
                        val captured = captureMeowPhysical(manager) { selected }
                        checkNotNull(captured.networkHandle) { "Resource download has no physical network" }
                        val plan = JSONObject().put("schemaVersion", 1)
                            .put("physicalNetwork", MeowPhysicalNetwork(captured.networkHandle, captured.dns).json())
                            .put("stagingRoot", staging.path).put("resourceReadRoot", read?.path ?: JSONObject.NULL)
                            .put("cacheRoot", cache.path)
                        val scope = NativeScope(nativeCreate(plan.toString()), cache)
                        allocated = scope
                        scope.call { nativeLoad(scope.handle, input.candidateYaml) }
                        scope
                    } catch (failure: Throwable) {
                        if (allocated == null) cache.deleteRecursively()
                        throw failure
                    }
                }
            } catch (failure: Throwable) {
                try { allocated?.closeAndJoin() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    fun preparedValidator(context: Context): NativePreparedConfigValidator {
        val app = context.applicationContext
        return NativePreparedConfigValidator { yaml, resourceReadRoot ->
            val read = privateDirectory(app, resourceReadRoot)
            val bundles = File(app.filesDir, "runtime/resources/bundles").canonicalFile.toPath()
            require(read.toPath().startsWith(bundles) && read.toPath() != bundles) { "Prepared validation requires a sealed resource generation" }
            val cache = cacheDirectory(app)
            try { nativeValidatePrepared(yaml, read.path, cache.path) }
            finally { cache.deleteRecursively() }
        }
    }

    private class NativeScope(val handle: Long, private val cache: File) : ProviderDownloadScope {
        private val close = Mutex()
        @Volatile private var closed = false
        suspend fun <T> call(work: () -> T): T = coroutineScope {
            check(!closed) { "Resource scope is closed" }
            val running = async(Dispatchers.IO) { work() }
            try { running.await() }
            catch (cancel: CancellationException) {
                try { nativeCancel(handle) } catch (failure: Throwable) { cancel.addSuppressed(failure) }
                withContext(NonCancellable) { running.join() }
                throw cancel
            }
        }
        override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult = call {
            val headers = JSONObject()
            request.headers.forEach { (name, values) -> headers.put(name, JSONArray(values)) }
            val response = JSONObject(nativeDownload(handle, JSONObject().put("url", request.url)
                .put("proxy", request.proxy ?: JSONObject.NULL).put("headers", headers)
                .put("destination", request.destination.path).put("maxBytes", request.maxBytes).toString()))
            val count = response.getLong("bytes")
            val digest = MessageDigest.getInstance("SHA-256")
            var actual = 0L
            request.destination.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = stream.read(buffer)
                    if (size < 0) break
                    actual += size
                    check(actual <= request.maxBytes) { "Native staged resource exceeds limit" }
                    digest.update(buffer, 0, size)
                }
            }
            check(actual == count) { "Native resource byte receipt mismatch" }
            ProviderDownloadResult(actual, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) },
                response.optString("subscriptionUserInfo").takeUnless { response.isNull("subscriptionUserInfo") })
        }
        override suspend fun closeAndJoin() = withContext(NonCancellable + Dispatchers.IO) {
            close.withLock {
                if (!closed) {
                    closed = true
                    try { nativeCloseJoin(handle) }
                    finally { cache.deleteRecursively() }
                }
            }
        }
    }
    private fun privateDirectory(context: Context, file: File): File {
        val root = context.filesDir.canonicalFile.toPath()
        val canonical = file.canonicalFile
        require(file.absoluteFile == canonical && canonical.isDirectory && canonical.toPath().startsWith(root) && canonical.toPath() != root) {
            "Resource directory must be a canonical app-private child"
        }
        return canonical
    }
    private fun cacheDirectory(context: Context): File = File(context.cacheDir, "meow-resource-${UUID.randomUUID()}").apply {
        check(mkdir()) { "Could not create resource validation cache" }
    }.canonicalFile
}
