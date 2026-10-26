package dev.aerove.lite.core

import dev.aerove.lite.data.NativePreparedConfigValidator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The JNI edges one offline owner uses; JniResourceFetch binds the real exports and JVM tests bind fakes. */
internal interface OfflineResourceNative {
    fun create(plan: String): Long
    fun cancel(handle: Long)
    fun closeJoin(handle: Long)
    fun validatePrepared(handle: Long, candidate: String, readRoot: String, cacheRoot: String): String?
}

internal suspend fun <T> offlineResourceRead(native: OfflineResourceNative, work: (Long) -> T): T = ownedOfflineResourceRead(
    create = { native.create(JSONObject().put("schemaVersion", 1).put("offline", true).toString()) },
    cancel = native::cancel, closeJoin = native::closeJoin, work = work,
)

/** JniResourceFetch.preparedValidator: a sealed generation only, and the cache outlives the joined conversion. */
internal fun preparedResourceValidator(filesDir: File, cacheDir: File, native: OfflineResourceNative) =
    NativePreparedConfigValidator { yaml, resourceReadRoot ->
        val read = privateResourceDirectory(filesDir, resourceReadRoot)
        val bundles = File(filesDir, "runtime/resources/bundles").canonicalFile.toPath()
        require(read.toPath().startsWith(bundles) && read.toPath() != bundles) { "Prepared validation requires a sealed resource generation" }
        val cache = resourceCacheDirectory(cacheDir)
        try { offlineResourceRead(native) { handle -> native.validatePrepared(handle, yaml, read.path, cache.path) } }
        finally { withContext(NonCancellable + Dispatchers.IO) { cache.deleteRecursively() } }
    }

internal fun privateResourceDirectory(filesDir: File, file: File): File {
    val root = filesDir.canonicalFile.toPath()
    val canonical = file.canonicalFile
    require(file.absoluteFile == canonical && canonical.isDirectory && canonical.toPath().startsWith(root) && canonical.toPath() != root) {
        "Resource directory must be a canonical app-private child"
    }
    return canonical
}

internal fun resourceCacheDirectory(cacheDir: File): File = File(cacheDir, "meow-resource-${UUID.randomUUID()}").apply {
    check(mkdir()) { "Could not create resource validation cache" }
}.canonicalFile

/** Allocation and real worker join belong to the request, before its caller can release a file lease. */
internal suspend fun <T> ownedOfflineResourceRead(
    create: () -> Long,
    cancel: (Long) -> Unit,
    closeJoin: (Long) -> Unit,
    work: (Long) -> T,
): T {
    var handle: Long? = null
    var failure: Throwable? = null
    try {
        return withContext(Dispatchers.IO) {
            val created = create()
            handle = created
            nativeResourceCall({ cancel(created) }) { work(created) }
        }
    } catch (error: Throwable) { failure = error; throw error }
    finally {
        withContext(NonCancellable + Dispatchers.IO) {
            handle?.let { owned ->
                try { closeJoin(owned) }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }
}
