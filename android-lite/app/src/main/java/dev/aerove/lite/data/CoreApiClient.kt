package dev.aerove.lite.data

import dev.aerove.lite.ConnectionInfo
import dev.aerove.lite.LogLine
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.Traffic
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class CoreApiClient(
    private val endpoint: ControlEndpoint,
    client: OkHttpClient = OkHttpClient(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private val http = client.newBuilder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun proxies(): List<ProxyNode> = withContext(Dispatchers.Default) {
        proxyMap().entries.map { (name, row) -> node(name, row) }
    }

    suspend fun groups(): List<ProxyGroup> = withContext(Dispatchers.Default) {
        val all = proxyMap()
        all.entries.mapNotNull { (name, row) ->
            val members = row.optJSONArray("all") ?: return@mapNotNull null
            ProxyGroup(
                name = name,
                type = row.getString("type"),
                selected = row.optString("now", ""),
                nodes = strings(members).map { member ->
                    node(member, all[member] ?: throw IOException("Core returned an unknown group member"))
                },
            )
        }
    }

    suspend fun connections(): List<ConnectionInfo> = withContext(Dispatchers.Default) {
        decodeConnections(json(listOf("connections")))
    }

    suspend fun mode(): ProxyMode = when (json(listOf("configs")).getString("mode").lowercase(Locale.ROOT)) {
        "rule" -> ProxyMode.Rule
        "global" -> ProxyMode.Global
        "direct" -> ProxyMode.Direct
        else -> throw IOException("Core returned an unsupported mode")
    }

    suspend fun setMode(mode: ProxyMode) {
        request(listOf("configs"), "PATCH", JSONObject().put("mode", mode.name.lowercase(Locale.ROOT)))
    }

    suspend fun select(group: String, proxy: String) {
        require(group.isNotEmpty() && proxy.isNotEmpty())
        request(listOf("proxies", group), "PUT", JSONObject().put("name", proxy))
    }

    suspend fun delay(
        proxy: String,
        url: String = "https://www.gstatic.com/generate_204",
        timeoutMillis: Int = 5000,
    ): Int {
        require(timeoutMillis in 1..60000)
        SubscriptionDownloader.parseUrl(url)
        val result = json(listOf("proxies", proxy, "delay"), mapOf("url" to url, "timeout" to "$timeoutMillis"))
        return result.getInt("delay").also { require(it >= 0) { "Core returned a negative delay" } }
    }

    suspend fun closeConnection(id: String) {
        require(id.isNotBlank())
        request(listOf("connections", id), "DELETE")
    }

    suspend fun closeAllConnections() { request(listOf("connections"), "DELETE") }

    fun logs(): Flow<LogLine> = messages(listOf("logs")).map { value ->
        LogLine(System.currentTimeMillis(), value.getString("type"), value.getString("payload"))
    }

    fun traffic(): Flow<Traffic> = combine(
        messages(listOf("traffic")),
        messages(listOf("connections")),
    ) { rate, snapshot ->
        Traffic(
            uploadPerSecond = unsigned(rate, "up"),
            downloadPerSecond = unsigned(rate, "down"),
            upload = unsigned(snapshot, "uploadTotal"),
            download = unsigned(snapshot, "downloadTotal"),
        )
    }

    fun connectionUpdates(): Flow<List<ConnectionInfo>> = messages(listOf("connections"))
        .map(::decodeConnections).flowOn(Dispatchers.Default)

    private suspend fun proxyMap(): Map<String, JSONObject> {
        val raw = json(listOf("proxies")).getJSONObject("proxies")
        return raw.keys().asSequence().associateWith { raw.getJSONObject(it) }
    }

    private fun node(name: String, value: JSONObject): ProxyNode {
        val history = value.optJSONArray("history")
        val delay = if (history == null || history.length() == 0) null else {
            history.getJSONObject(history.length() - 1).getInt("delay").takeIf { it > 0 }
        }
        return ProxyNode(name, value.getString("type"), delay)
    }

    private fun decodeConnections(value: JSONObject): List<ConnectionInfo> {
        val rows = value.getJSONArray("connections")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val metadata = row.getJSONObject("metadata")
            val host = metadata.optString("host", "").ifBlank { metadata.getString("destinationIP") }
            ConnectionInfo(
                id = row.getString("id"),
                destination = "$host:${metadata.get("destinationPort")}",
                network = metadata.getString("network"),
                rule = row.getString("rule"),
                chains = strings(row.getJSONArray("chains")),
                upload = unsigned(row, "upload"),
                download = unsigned(row, "download"),
            )
        }
    }

    private suspend fun json(path: List<String>, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.Default) { JSONObject(request(path, query = query)) }

    private suspend fun request(
        path: List<String>,
        method: String = "GET",
        data: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Core client is closed" }
        val builder = authenticated(url(path, query))
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = http.newCall(builder.method(method, body).build())
        calls.add(call)
        try {
            if (closed.get()) throw IOException("Core client is closed")
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) throw CoreApiException(response.code, path.first())
                response.body?.byteStream()?.readBounded(MAX_PROFILE_BYTES)?.strictUtf8().orEmpty()
            }
        } finally {
            calls.remove(call)
        }
    }

    private fun url(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(endpoint.port)
        path.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun authenticated(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer ${endpoint.secret}")

    private fun messages(path: List<String>): Flow<JSONObject> = callbackFlow {
        check(!closed.get()) { "Core client is closed" }
        val socket = http.newWebSocket(authenticated(url(path)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.length > MAX_PROFILE_BYTES) throw IOException("Core stream message is too large")
                    if (trySend(JSONObject(text)).isFailure) {
                        close(IOException("Core stream consumer is too slow"))
                    }
                } catch (failure: Exception) {
                    close(failure)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                close(IOException("Unexpected binary core stream message"))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                response?.close()
                close(IOException("Core stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, "")
                close(IOException("Core stream closed ($code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }
        })
        sockets.add(socket)
        if (closed.get()) socket.cancel()
        awaitClose {
            sockets.remove(socket)
            socket.cancel()
        }
    }.buffer(64)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            calls.forEach(Call::cancel)
            sockets.forEach(WebSocket::cancel)
        }
    }

    companion object {
        private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
        private fun unsigned(json: JSONObject, key: String): Long = json.getLong(key).also {
            if (it < 0) throw IOException("Core returned a negative counter")
        }
    }
}
