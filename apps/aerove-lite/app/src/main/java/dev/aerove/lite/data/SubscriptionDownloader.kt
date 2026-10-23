package dev.aerove.lite.data

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import dev.aerove.lite.requests.RequestPolicySource
import dev.aerove.lite.requests.forDownload
import dev.aerove.lite.requests.DownloadCancellation

internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

class SubscriptionDownloader(client: OkHttpClient, private val source: RequestPolicySource) {
    constructor(client: OkHttpClient = OkHttpClient()) : this(client, RequestPolicySource.secureDefaults)
    constructor(source: RequestPolicySource) : this(OkHttpClient(), source)
    data class Download(val yaml: String, val subscriptionUserInfo: String?, val contentDisposition: String?)
    data class RawDownload(val bytes: ByteArray, val subscriptionUserInfo: String?, val contentDisposition: String?) {
        override fun toString(): String = "RawDownload(bytes=<redacted>, subscriptionUserInfo=<redacted>, contentDisposition=<redacted>)"
    }
    private val http = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String): String = downloadResult(url).yaml

    suspend fun downloadResult(url: String): Download {
        val result = downloadRaw(url)
        return withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            if (result.bytes.size > MAX_PROFILE_BYTES) throw IOException("Subscription is too large")
            Download(result.bytes.strictUtf8(), result.subscriptionUserInfo, result.contentDisposition)
        }
    }

    suspend fun downloadRaw(url: String): RawDownload = withTimeout(20_000) { withContext(Dispatchers.IO) {
        val preferences = source.snapshot()
        val scoped = http.forDownload(preferences)
        var target = parseUrl(url)
        val cancellation = DownloadCancellation(currentCoroutineContext())
        try {
        repeat(6) { redirect ->
            val request = Request.Builder().url(target)
                .header("User-Agent", preferences.globalUserAgent ?: source.defaultUserAgent)
                .header("Accept", "application/yaml,text/yaml,text/plain,application/octet-stream")
                .build()
            cancellation.track(scoped.newCall(request)).awaitResponse().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    if (redirect == 5) throw IOException("Too many subscription redirects")
                    val next = response.header("Location")?.let(target::resolve)
                        ?: throw IOException("Subscription redirect has no valid Location")
                    parseUrl(next.toString())
                    if (target.isHttps && !next.isHttps) throw IOException("HTTPS downgrade refused")
                    target = next
                } else {
                    if (!response.isSuccessful) throw IOException("Subscription HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Subscription response is empty")
                    if (body.contentLength() > dev.aerove.lite.profilesource.MAX_CIPHERTEXT_BYTES) throw IOException("Subscription is too large")
                    return@withContext RawDownload(body.byteStream().readBounded(dev.aerove.lite.profilesource.MAX_CIPHERTEXT_BYTES),
                        response.header("subscription-userinfo")?.take(8192),
                        response.header("content-disposition")?.take(8192))
                }
            }
        }
        throw IOException("Too many subscription redirects")
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            cancellation.close()
            if (scoped !== http) scoped.connectionPool.evictAll()
        }
    } }

    companion object {
        fun parseUrl(value: String): HttpUrl {
            val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid subscription URL")
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
                "Subscription URL must not contain user-info or a fragment"
            }
            return url
        }
    }
}
