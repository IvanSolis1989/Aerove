package dev.aerove.lite.ipquality

import dev.aerove.lite.data.awaitResponse
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.strictUtf8
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class IpQualityHttp(client: OkHttpClient = OkHttpClient()) : Closeable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val http = client.newBuilder().cache(null).proxy(Proxy.NO_PROXY).retryOnConnectionFailure(false)
        .followRedirects(true).followSslRedirects(true).connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()

    internal suspend fun query(source: IpQualitySource, ip: String): IpQualityAnswer =
        fetch(source, ip, source.url(ip))

    internal suspend fun fetch(source: IpQualitySource, ip: String, url: String): IpQualityAnswer = withContext(Dispatchers.IO) {
        check(!closed.get()) { "IP quality client is closed" }
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", BROWSER_UA).build())
        val cancellation = Job(currentCoroutineContext()[Job])
        cancellation.invokeOnCompletion { if (it != null) call.cancel() }
        calls.add(call)
        try {
            if (closed.get()) throw IOException("IP quality client is closed")
            call.awaitResponse().use { response ->
                if (response.code == 429) throw IpQualitySourceException(IpQualitySourceStatus.RateLimited)
                if (response.code != 200) throw IpQualitySourceException(IpQualitySourceStatus.Failed)
                val body = response.body?.byteStream()?.readBounded(1024 * 1024)?.strictUtf8().orEmpty()
                parseIpQuality(source, ip, JSONObject(body))
            }
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            calls.remove(call)
            cancellation.complete()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) calls.forEach(Call::cancel)
    }

    private companion object {
        const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
