package dev.aerove.lite.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class ResourceDigest(val sha256: String, val bytes: Long)

class ResourceDownloader(client: OkHttpClient = OkHttpClient()) {
    private val http = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    internal suspend fun download(url: String, destination: File, limit: Long): ResourceDigest = withTimeout(180_000) {
        withContext(Dispatchers.IO) { downloadBounded(url, destination, limit) }
    }

    private suspend fun downloadBounded(url: String, destination: File, limit: Long): ResourceDigest {
        var target = SubscriptionDownloader.parseUrl(url)
        repeat(6) { redirect ->
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(target).header("User-Agent", "AEROVE-Lite")
                .header("Accept", "application/octet-stream,application/yaml,text/plain").build()
            http.newCall(request).awaitResponse().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) throw IOException("Resource redirect limit exceeded")
                    val next = response.header("Location")?.let(target::resolve)
                        ?: throw IOException("Invalid resource redirect")
                    SubscriptionDownloader.parseUrl(next.toString())
                    if (target.isHttps && !next.isHttps) throw IOException("Resource HTTPS downgrade refused")
                    target = next
                } else {
                    if (!response.isSuccessful) throw IOException("Resource HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Resource response is empty")
                    if (body.contentLength() > limit) throw IOException("Resource exceeds $limit bytes")
                    return body.byteStream().use { input ->
                        copyResource(input, destination, limit)
                    }
                }
            }
        }
        throw IOException("Resource redirect limit exceeded")
    }
}

internal suspend fun copyResource(input: java.io.InputStream, destination: File, limit: Long): ResourceDigest {
    require(!destination.exists()) { "Immutable resource already exists" }
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    try {
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                if (count > limit) throw IOException("Resource exceeds $limit bytes")
                digest.update(buffer, 0, size)
                output.write(buffer, 0, size)
            }
            output.fd.sync()
        }
        if (count == 0L) throw IOException("Resource is empty")
        return ResourceDigest(digest.digest().hex(), count)
    } catch (failure: Throwable) {
        destination.delete()
        throw failure
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).hex()

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().hex()
}
