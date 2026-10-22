package dev.aerove.lite.data

import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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

class SubscriptionDownloader(client: OkHttpClient = OkHttpClient()) {
    data class Download(val yaml: String, val subscriptionUserInfo: String?, val contentDisposition: String?)
    private val http = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String): String = downloadResult(url).yaml

    suspend fun downloadResult(url: String): Download = withContext(Dispatchers.IO) {
        var target = parseUrl(url)
        repeat(6) { redirect ->
            val request = Request.Builder().url(target)
                .header("User-Agent", "AEROVE-Lite")
                .header("Accept", "application/yaml,text/yaml,text/plain,application/octet-stream")
                .build()
            http.newCall(request).awaitResponse().use { response ->
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
                    if (body.contentLength() > MAX_PROFILE_BYTES) throw IOException("Subscription is too large")
                    return@withContext Download(body.byteStream().readBounded(MAX_PROFILE_BYTES).strictUtf8(),
                        response.header("subscription-userinfo")?.take(8192),
                        response.header("content-disposition")?.take(8192))
                }
            }
        }
        throw IOException("Too many subscription redirects")
    }

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
