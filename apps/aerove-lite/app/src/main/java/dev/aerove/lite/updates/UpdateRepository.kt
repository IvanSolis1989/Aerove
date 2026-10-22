package dev.aerove.lite.updates

import dev.aerove.lite.data.awaitResponse
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.strictUtf8
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

interface UpdateTransport : AutoCloseable {
    suspend fun releases(page: Int): String
    suspend fun metadata(url: String): ByteArray
    suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long, Long) -> Unit)
    override fun close() = Unit
}

class UpdateHttpClient(client: OkHttpClient? = null) : UpdateTransport {
    private val owned = client == null
    private val http = (client ?: OkHttpClient()).newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(5, TimeUnit.MINUTES).connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun releases(page: Int): String {
        require(page in 1..5)
        return read("https://api.github.com/repos/$LITE_REPOSITORY/releases?per_page=100&page=$page", 2 * 1024 * 1024, false).strictUtf8()
    }
    override suspend fun metadata(url: String): ByteArray = read(url, 128 * 1024, true)

    private suspend fun read(url: String, limit: Int, asset: Boolean): ByteArray = withContext(Dispatchers.IO) {
        response(url, asset) { reply ->
            check(reply.isSuccessful) { "Update server HTTP ${reply.code}" }
            checkNotNull(reply.body).byteStream().use { it.readBounded(limit) }
        }
    }

    private suspend fun <T> response(raw: String, asset: Boolean, consume: suspend (okhttp3.Response) -> T): T {
        var url = raw.toHttpUrl()
        repeat(6) { redirects ->
            require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Untrusted update endpoint" }
            val allowed = if (asset) setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") else setOf("api.github.com")
            require(url.host in allowed) { "Untrusted update host" }
            val call = http.newCall(Request.Builder().url(url).header("User-Agent", "AEROVE-Lite")
                .header("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json").build())
            // Keep cancellation attached after headers while synchronous body reads own the socket.
            val cancellation = Job(currentCoroutineContext()[Job])
            cancellation.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.awaitResponse().use { result ->
                    if (result.code !in setOf(301, 302, 303, 307, 308)) return consume(result)
                    val next = result.header("Location")?.let(url::resolve)
                    require(asset && redirects < 5 && next != null) { "Invalid update redirect" }
                    url = next
                }
            } catch (failure: IOException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } finally { cancellation.complete() }
        }
        error("Too many update redirects")
    }

    override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        require(expectedBytes in 1..MAX_APK_BYTES)
        response(url, true) { reply ->
            check(reply.isSuccessful) { "Update download HTTP ${reply.code}" }
            val body = checkNotNull(reply.body)
            require(body.contentLength() == -1L || body.contentLength() == expectedBytes) { "APK length does not match release" }
            body.byteStream().use { input -> FileOutputStream(destination).use { output ->
                val buffer = ByteArray(65536)
                var bytes = 0L; var reported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= expectedBytes) { "APK exceeds declared length" }
                    output.write(buffer, 0, count)
                    if (bytes - reported >= 1024 * 1024) { progress(bytes, expectedBytes); reported = bytes }
                }
                require(bytes == expectedBytes) { "Incomplete APK download" }
                output.fd.sync()
                progress(bytes, expectedBytes)
            } }
        }
    }
    override fun close() {
        if (owned) {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }
}

fun interface UpdateApkVerifier { suspend fun verify(file: File, release: LiteRelease) }
class VerifiedUpdate internal constructor(val file: File, val release: LiteRelease, val sha256: String)

class UpdateRepository(
    private val transport: UpdateTransport,
    cacheDirectory: File,
    private val verifier: UpdateApkVerifier,
) : AutoCloseable {
    private val directory = cacheDirectory.canonicalFile.apply { check(isDirectory || mkdirs()) }
    suspend fun check(identity: UpdateIdentity): LiteRelease? = withContext(Dispatchers.IO) {
        val all = mutableListOf<LiteRelease>()
        for (page in 1..5) {
            val array = JSONArray(transport.releases(page))
            all += parseLiteReleases(array)
            if (array.length() < 100) break
            require(page < 5) { "Release feed scan limit reached" }
        }
        selectLiteRelease(all, identity)
    }

    suspend fun download(release: LiteRelease, progress: (Long, Long) -> Unit): VerifiedUpdate = withContext(Dispatchers.IO) {
        require(release.tag == "lite-v${release.version}+${release.versionCode}" && release.versionCode in 1..2_100_000_000L && release.bytes in 1..MAX_APK_BYTES) { "Invalid Lite update identity" }
        ProductVersion.parse(release.version)
        trustedAssetUrl(release.apkUrl, release.tag, LITE_APK_NAME)
        trustedAssetUrl(release.sumsUrl, release.tag, "SHA256SUMS")
        trustedAssetUrl(release.buildInfoUrl, release.tag, "BUILD_INFO.json")
        val sums = transport.metadata(release.sumsUrl).strictUtf8().lineSequence().filter(String::isNotBlank).map { line ->
            val match = Regex("([0-9a-f]{64}) [ *]([^/\\\\]+)").matchEntire(line) ?: error("Invalid release checksum manifest")
            match.groupValues[2] to match.groupValues[1]
        }.toList()
        require(sums.map { it.first }.distinct().size == sums.size) { "Duplicate release checksum" }
        val expected = sums.toMap()[LITE_APK_NAME] ?: error("Missing APK checksum")
        val infoBytes = transport.metadata(release.buildInfoUrl)
        require(digest(infoBytes) == sums.toMap()["BUILD_INFO.json"]) { "Build provenance checksum mismatch" }
        val info = JSONObject(infoBytes.strictUtf8())
        require(info.getString("product") == "AEROVE Lite" && info.getString("application_id") == LITE_PACKAGE &&
            info.getString("version") == release.version && info.getLong("version_code") == release.versionCode &&
            info.getString("abi") == "arm64-v8a" && info.getString("signer_sha256") == LITE_SIGNER_SHA256 &&
            info.getString("apk_sha256") == expected && info.getInt("min_sdk") == 33 &&
            info.getString("channel") in setOf("preview", "stable") &&
            info.getString("source").matches(Regex("[0-9a-f]{40}")) && info.getString("tree").matches(Regex("[0-9a-f]{40}")) &&
            info.getString("workflow").matches(Regex("https://github\\.com/IvanSolis1989/Aerove/actions/runs/[0-9]+"))) { "Unexpected Lite build provenance" }
        val destination = File(directory, "$expected.apk")
        if (destination.isFile) {
            val ready = VerifiedUpdate(destination, release, expected)
            try { validate(ready); return@withContext ready }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        val temporary = File(directory, ".download-${UUID.randomUUID()}.part")
        try {
            transport.download(release.apkUrl, temporary, release.bytes, progress)
            val candidate = VerifiedUpdate(temporary, release, expected)
            validate(candidate)
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9a-f]{64}\\.apk")) && it != destination }
                ?.sortedByDescending(File::lastModified)?.drop(1)?.forEach { Files.deleteIfExists(it.toPath()) }
            VerifiedUpdate(destination, release, expected)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    suspend fun validate(update: VerifiedUpdate) = withContext(Dispatchers.IO) {
        require(update.file.canonicalFile.parentFile == directory && update.file.isFile && update.file.length() == update.release.bytes) { "Update cache is unavailable" }
        val hash = MessageDigest.getInstance("SHA-256")
        update.file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer); if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        require(hash.digest().hex() == update.sha256) { "APK checksum mismatch" }
        verifier.verify(update.file, update.release)
    }
    override fun close() = transport.close()
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
