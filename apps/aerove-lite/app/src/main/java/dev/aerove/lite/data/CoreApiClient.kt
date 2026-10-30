package dev.aerove.lite.data

import dev.aerove.lite.ConnectionInfo
import dev.aerove.lite.LogLine
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.Traffic
import dev.aerove.lite.telemetry.CoreTelemetry
import dev.aerove.lite.telemetry.OutboundAnswer
import dev.aerove.lite.observability.decodeObservationWindow
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.planNetwork
import dev.aerove.lite.networksettings.networkApplyBody
import dev.aerove.lite.networksettings.decodeNetworkReceipt
import dev.aerove.lite.networksettings.verifyNetworkReadback
import java.io.Closeable
import java.io.IOException
import java.net.Proxy
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
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
import org.json.JSONException
import org.json.JSONObject

private fun defaultCoreHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 32 })
    .build()

private class ProtocolFailurePolicy(val create: (Int, String) -> IOException) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProtocolFailurePolicy>
}

internal class CoreApiProtocolReceiptException(val field: String) : IOException("Core returned an invalid delay receipt: $field")

class CoreApiClient(
    private val endpoint: ControlEndpoint,
    client: OkHttpClient = defaultCoreHttpClient(),
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val sockets = ConcurrentHashMap.newKeySet<WebSocket>()
    private val streamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closedSignal = MutableStateFlow(false)
    private val http = client.newBuilder()
        .cache(null)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private data class TelemetryDemand(val observations: Boolean = false, val revision: Long = 0)
    private class TelemetryFrame(val source: String) {
        val decoded: Result<CoreTelemetry> by lazy { runCatching { decodeTelemetry(source) } }
    }
    private data class TelemetryPacket(val revision: Long, val value: Result<TelemetryFrame>)
    private val telemetryGate = Any()
    private val telemetryDemand = MutableStateFlow(TelemetryDemand())

    fun setTelemetryObservations(enabled: Boolean) = synchronized(telemetryGate) {
        val current = telemetryDemand.value
        if (!closed.get() && current.observations != enabled)
            telemetryDemand.value = TelemetryDemand(enabled, current.revision + 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val telemetryPackets by lazy {
        telemetryDemand.flatMapLatest { demand ->
            frames(listOf("aerove", "telemetry"), 65536,
                query = if (demand.observations) mapOf("observations" to "true") else emptyMap(), serializedClose = true,
                gracefulClose = { telemetryDemand.value.revision != demand.revision })
            .map { TelemetryPacket(demand.revision, Result.success(TelemetryFrame(it))) }
            .onCompletion { cause ->
                if (cause == null) emit(TelemetryPacket(demand.revision, Result.failure(IOException("Core telemetry stream closed"))))
            }
            .catch { emit(TelemetryPacket(demand.revision, Result.failure(it))) }
        }
            .shareIn(streamScope, SharingStarted.WhileSubscribed(0, 0), replay = 1)
    }

    suspend fun connectionCount(): Int {
        val row = JSONObject(request(listOf("aerove", "connections", "count"), maxBytes = 1024))
        if (row.keys().asSequence().toSet() != setOf("count")) throw IOException("Invalid connection count response")
        val count = row.get("count")
        return when (count) {
            is Int -> count.takeIf { it >= 0 }
            is Long -> count.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        } ?: throw IOException("Invalid connection count")
    }

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

    suspend fun serviceChecks(
        targets: List<ServiceTarget>,
        proxyName: String = "",
        timeoutMillis: Int = 10_000,
    ): List<ServiceCheck> = withContext(Dispatchers.Default) {
        val wanted = targets.toList()
        val body = serviceCheckBody(wanted, proxyName, timeoutMillis)
        decodeServiceChecks(request(
            listOf("aerove", "service-check"), "POST", body,
            budgetMillis = timeoutMillis * 6L,
            failure = ::serviceCheckFailure,
            joinResponse = true,
        ), wanted)
    }

    suspend fun memorySnapshot(): MemorySnapshot = withContext(Dispatchers.Default) {
        decodeMemorySnapshot(request(listOf("memory"), maxBytes = 1024))
    }

    suspend fun routeSnapshot(): RouteSnapshot = withContext(Dispatchers.Default) {
        decodeRouteSnapshot(request(listOf("aerove", "route")))
    }

    suspend fun applyNetwork(settings: NetworkSettings, ipv6: Boolean): Unit = withContext(Dispatchers.Default) {
        val plan = planNetwork(settings, endpoint.port, ipv6)
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val before = routeSnapshot().coreEpoch
            currentCoroutineContext().ensureActive()
            val requestId = UUID.randomUUID().toString()
            val receipt = try {
                request(listOf("aerove", "network"), "POST", networkApplyBody(requestId, before, plan),
                    budgetMillis = 10_000, maxBytes = 4096)
            } catch (failure: CoreApiException) {
                currentCoroutineContext().ensureActive()
                if (failure.statusCode == 409 && attempt == 0) return@repeat
                throw failure
            }
            currentCoroutineContext().ensureActive()
            val appliedEpoch = decodeNetworkReceipt(receipt, requestId, before)
            val actual = request(listOf("aerove", "network"), budgetMillis = 10_000, maxBytes = 4096)
            verifyNetworkReadback(actual, appliedEpoch, plan)
            return@withContext
        }
    }

    suspend fun outboundIdentity(): OutboundIdentity = decodeOutbound(json(listOf("aerove", "outbound")))

    suspend fun outboundProbe(forceDirect: Boolean = false): OutboundAnswer {
        val value = json(listOf("aerove", "outbound"), if (forceDirect) mapOf("forceDirect" to "true") else emptyMap())
        val direct = if (value.has("direct")) value.get("direct") as? Boolean
            ?: throw IOException("Core returned an invalid outbound direct receipt") else null
        val epoch = if (value.has("coreEpoch")) (value.get("coreEpoch") as? String)?.takeIf {
            it.matches(Regex("0|[1-9][0-9]*")) && it.toULongOrNull() != null
        } ?: throw IOException("Core returned an invalid outbound epoch") else null
        if (forceDirect && (direct != true || epoch == null)) throw IOException("Core did not confirm the requested direct outbound route")
        val identity = decodeOutbound(value)
        val chains = try {
            val raw = value.getJSONArray("chains")
            if (raw.length() == 0) throw IOException("Core returned an invalid outbound route chain")
            (0 until raw.length()).map { index ->
                (raw.get(index) as? String)?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("Core returned an invalid outbound route chain")
            }
        } catch (error: JSONException) {
            throw IOException("Core outbound response is missing its actual route chain", error)
        }
        val parsed = ProbeJson(value.toString()).fields()
        val hits = decodeRouteHits(parsed["hits"])
        if (hits.isEmpty()) throw IOException("Core returned no outbound route hits")
        fun counter(key: String): ULong = (parsed[key] as? String)?.let { text ->
            text.toULongOrNull()?.takeIf { it.toString() == text }
        } ?: throw IOException("Core returned invalid outbound $key")
        return OutboundAnswer(identity, chains, hits, counter("coreEpoch"), counter("routeVersion"))
    }

    suspend fun reloadRuleProvider(name: String) {
        require(name.isNotBlank())
        request(listOf("providers", "rules", name), "PUT", emptyBody = true, budgetMillis = 10_000, maxBytes = 4096)
    }

    suspend fun checkRoutes(hits: List<List<RouteHit>>): RouteCheck {
        val body = JSONObject().put("hits", JSONArray(hits.map { rows -> JSONArray(rows.map { hit ->
            JSONObject().put("host", hit.host).put("proxy", hit.proxy).put("exit", hit.exit)
        }) }))
        return decodeRouteCheck(request(listOf("aerove", "check-routes"), "POST", body, budgetMillis = 5000, maxBytes = 4096), hits.size)
    }

    suspend fun ipQualityBody(source: String, ip: String, direct: Boolean): JSONObject {
        val body = JSONObject().put("source", source).put("ip", ip).put("direct", direct)
        val raw = request(listOf("aerove", "ip-quality"), "POST", body, budgetMillis = 10_000, maxBytes = 2 * 1024 * 1024)
        val row = JSONObject(raw)
        val parsed = ProbeJson(raw).fields()
        decodeRouteHits(parsed["hits"])
        return row
    }

    private fun decodeOutbound(value: JSONObject): OutboundIdentity {
        val address = value.getString("ip")
        val country = value.getString("countryCode")
        if (address.length > 45 || !address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F]*:[0-9a-fA-F:.]+")) ||
            !country.matches(Regex("[A-Z]{2}"))) throw IOException("Core returned invalid outbound identity")
        val organization = if (value.isNull("organization")) null else value.getString("organization").also {
            if (it.length > 256 || it.any(Char::isISOControl)) throw IOException("Core returned invalid organization")
        }
        return OutboundIdentity(address, country, organization, value.getString("kind"))
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

    internal suspend fun protocolDelay(proxy: String, url: String, timeoutMillis: Int, expectedEpoch: ULong,
        receipt: (ULong, ULong) -> Unit): Int {
        require(proxy.isNotBlank() && timeoutMillis in 1..60000 && expectedEpoch > 0uL)
        SubscriptionDownloader.parseUrl(url)
        val body = request(listOf("proxies", proxy, "delay"),
            query = mapOf("url" to url, "timeout" to timeoutMillis.toString(), "coreEpoch" to expectedEpoch.toString()),
            inspectResponse = { response ->
                // Only a committed measurement has these headers. Admission failures keep their native errors.
                if (response.isSuccessful || response.code == 504) {
                    fun stamp(field: String): ULong {
                        val values = response.headers.values(field)
                        val text = values.singleOrNull() ?: throw CoreApiProtocolReceiptException(field)
                        return text.toULongOrNull()?.takeIf { it.toString() == text }
                            ?: throw CoreApiProtocolReceiptException(field)
                    }
                    val epoch = stamp("x-aerove-core-epoch")
                    if (epoch == 0uL) throw CoreApiProtocolReceiptException("x-aerove-core-epoch")
                    receipt(epoch, stamp("x-aerove-route-version"))
                }
            })
        val value = JSONObject(body).get("delay")
        return when (value) {
            is Int -> value.takeIf { it in 0..65535 }
            is Long -> value.takeIf { it in 0..65535 }?.toInt()
            else -> null
        } ?: throw CoreApiProtocolReceiptException("delay")
    }

    suspend fun closeConnection(id: String) {
        require(id.isNotBlank())
        request(listOf("connections", id), "DELETE")
    }

    suspend fun closeAllConnections() { request(listOf("connections"), "DELETE") }
    suspend fun resetConnections() { request(listOf("aerove", "reset-connections"), "POST", emptyBody = true) }

    suspend fun clearDnsCache() { request(listOf("cache", "dns", "flush"), "POST", JSONObject()) }

    fun logs(): Flow<LogLine> = messages(listOf("logs")).map { value ->
        LogLine(System.currentTimeMillis(), value.getString("type"), value.getString("payload"))
    }

    fun telemetry(): Flow<CoreTelemetry> = telemetryFrames().map { it.decoded.getOrThrow() }

    // The Flutter projection must retain fields that the native UI's smaller DTOs do not use.
    // Both readers share the same demand owner and websocket, never a second sampler.
    internal fun protocolTelemetry(): Flow<String> = telemetryFrames().map { it.source }

    private fun telemetryFrames(): Flow<TelemetryFrame> = flow {
        check(!closed.get()) { "Core client is closed" }
        emitAll(merge(
            flow<TelemetryFrame> {
                telemetryPackets.collect { packet ->
                    if (packet.revision == telemetryDemand.value.revision) emit(packet.value.getOrThrow())
                }
            },
            flow<TelemetryFrame> {
                closedSignal.first { it }
                throw IOException("Core client is closed")
            },
        ))
    }

    internal suspend fun protocolSnapshot(path: List<String>): String {
        require(path == listOf("proxies") || path == listOf("connections") ||
            path == listOf("providers", "proxies") || path == listOf("providers", "rules"))
        return request(path)
    }

    suspend fun protocolDelayHistory(coreEpoch: ULong): String {
        require(coreEpoch > 0uL) { "Invalid expected core epoch" }
        return request(listOf("aerove", "delay-history"), query = mapOf("coreEpoch" to coreEpoch.toString()))
    }

    internal suspend fun protocolProbe(url: String, proxy: String, headers: Map<String, String>,
        timeoutMillis: Long, maxBody: Long, coreEpoch: ULong): String {
        require(coreEpoch > 0uL && timeoutMillis <= 60000 && maxBody <= 8 * 1024 * 1024)
        val data = JSONObject().put("url", url).put("proxy-name", proxy).put("headers", JSONObject(headers))
            .put("timeout", timeoutMillis).put("max-body", maxBody)
        return request(listOf("aerove", "probe"), "POST", data,
            query = mapOf("coreEpoch" to coreEpoch.toString()), budgetMillis = timeoutMillis.takeIf { it > 0 } ?: 10000,
            maxBytes = 4 * 1024 * 1024 + maxBody.coerceAtLeast(0).toInt() * 6, joinResponse = true)
    }

    internal suspend fun protocolOutboundIp(proxy: String, urls: List<String>, timeoutMillis: Long, coreEpoch: ULong): String {
        require(coreEpoch > 0uL && timeoutMillis <= 60000)
        val data = JSONObject().put("proxy-name", proxy).put("urls", JSONArray(urls)).put("timeout", timeoutMillis)
        return request(listOf("aerove", "outbound-ip"), "POST", data,
            query = mapOf("coreEpoch" to coreEpoch.toString()), budgetMillis = timeoutMillis.takeIf { it > 0 } ?: 10000,
            maxBytes = 32 * 1024 * 1024, joinResponse = true)
    }

    internal suspend fun protocolUnpin(group: String) {
        require(group.isNotBlank())
        request(listOf("proxies", group), "DELETE")
    }

    internal suspend fun protocolProvider(kind: String, name: String): String {
        require(kind in setOf("proxies", "rules") && name.isNotBlank())
        return request(listOf("providers", kind, name))
    }

    // Opt-in only: the existing native callers keep their established exception types.
    internal suspend fun <T> protocolErrors(create: (Int, String) -> IOException, work: suspend () -> T): T =
        withContext(ProtocolFailurePolicy(create)) { work() }

    fun traffic(): Flow<Traffic> = telemetry().filterIsInstance<CoreTelemetry.TrafficSample>().map { it.value }

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
        return dev.aerove.lite.connections.decodeConnectionSnapshot(value)
    }

    private suspend fun json(path: List<String>, query: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.Default) { JSONObject(request(path, query = query)) }

    private suspend fun request(
        path: List<String>,
        method: String = "GET",
        data: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        budgetMillis: Long? = null,
        failure: ((Int, String) -> IOException)? = null,
        maxBytes: Int = MAX_PROFILE_BYTES,
        emptyBody: Boolean = false,
        inspectResponse: ((Response) -> Unit)? = null,
        joinResponse: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Core client is closed" }
        val builder = authenticated(url(path, query))
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (emptyBody) ByteArray(0).toRequestBody(null) else null
        val transport = if (budgetMillis == null) http else http.newBuilder()
            .readTimeout(budgetMillis + 5000, TimeUnit.MILLISECONDS)
            .callTimeout(budgetMillis + 10000, TimeUnit.MILLISECONDS)
            .build()
        val call = transport.newCall(builder.method(method, body).build())
        val cancellation = Job(currentCoroutineContext()[Job])
        cancellation.invokeOnCompletion { if (it != null) call.cancel() }
        calls.add(call)
        val callbackEnded = CompletableDeferred<Unit>()
        var joined = false
        try {
            if (closed.get()) throw IOException("Core client is closed")
            val received = if (joinResponse) { joined = true; awaitProtocolResponse(call, callbackEnded) } else call.awaitResponse()
            received.use { response ->
                inspectResponse?.invoke(response)
                if (!response.isSuccessful) {
                    val describe = failure ?: currentCoroutineContext()[ProtocolFailurePolicy]?.create
                    throw if (describe == null) CoreApiException(response.code, path.first()) else {
                        describe(response.code, response.body?.byteStream()?.readBounded(8192)?.strictUtf8().orEmpty())
                    }
                }
                response.body?.byteStream()?.readBounded(maxBytes)?.strictUtf8().orEmpty()
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            if (joined) withContext(NonCancellable) { callbackEnded.await() }
            calls.remove(call)
            cancellation.complete()
        }
    }

    private suspend fun awaitProtocolResponse(call: Call, ended: CompletableDeferred<Unit>): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try { if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e)) }
                        finally { ended.complete(Unit) }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try { continuation.resume(response) { _, value, _ -> value.close() } }
                        finally { ended.complete(Unit) }
                    }
                })
            } catch (failure: Throwable) { ended.complete(Unit); throw failure }
        }

    private fun url(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(endpoint.port)
        path.forEach(builder::addPathSegment)
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun authenticated(url: HttpUrl): Request.Builder = Request.Builder().url(url)
        .header("Authorization", "Bearer ${endpoint.secret}")

    private fun messages(path: List<String>): Flow<JSONObject> = frames(path).map(::JSONObject)

    private fun frames(path: List<String>, limit: Int = MAX_PROFILE_BYTES,
        query: Map<String, String> = emptyMap(), serializedClose: Boolean = false,
        gracefulClose: () -> Boolean = { false }): Flow<String> = flow {
        val ended = CompletableDeferred<Unit>()
        var owned: WebSocket? = null
        try { emitAll(callbackFlow {
        check(!closed.get()) { "Core client is closed" }
        val socket = http.newWebSocket(authenticated(url(path, query)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.toByteArray(Charsets.UTF_8).size > limit) throw IOException("Core stream message is too large")
                    if (trySend(text).isFailure) {
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
                ended.complete(Unit)
                response?.close()
                close(IOException("Core stream failed", t))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, "")
                close(IOException("Core stream closed ($code)"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(Unit)
                close()
            }
        })
        owned = socket
        sockets.add(socket)
        if (closed.get()) socket.cancel()
        awaitClose {
            if (serializedClose && !closed.get() && gracefulClose()) socket.close(1000, "") else socket.cancel()
        }
        }.buffer(64)) } finally {
            if (serializedClose && owned != null) withContext(NonCancellable) {
                if (withTimeoutOrNull(1000) { ended.await() } == null) owned.cancel()
            }
            owned?.let(sockets::remove)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closedSignal.value = true
            streamScope.cancel()
            calls.forEach(Call::cancel)
            sockets.forEach(WebSocket::cancel)
        }
    }

    companion object {
        private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
        private fun unsigned(json: JSONObject, key: String): Long {
            val value = json.get(key)
            return (value as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IOException("Core returned an invalid $key counter")
        }

        private val revisionField = Regex("\"revision\"\\s*:\\s*(0|[1-9][0-9]*)(?=\\s*[,}])")

        private fun decodeTelemetry(frame: String): CoreTelemetry = try {
            val json = JSONObject(frame)
            when (json.getString("type")) {
                "traffic" -> CoreTelemetry.TrafficSample(Traffic(
                    uploadPerSecond = unsigned(json, "up"),
                    downloadPerSecond = unsigned(json, "down"),
                    upload = unsigned(json, "uploadTotal"),
                    download = unsigned(json, "downloadTotal"),
                ))
                "groupsChanged" -> {
                    val parsed = ProbeJson(frame).fields()
                    if (parsed.keys != setOf("type", "revision", "routeVersion")) throw IOException("Core returned an invalid groups schema")
                    CoreTelemetry.GroupsChanged(parsed.unsigned("revision"), parsed.unsigned("routeVersion"))
                }
                "providersLoaded" -> {
                    if (json.keys().asSequence().toSet() != setOf("type", "providers")) throw IOException("Invalid provider loaded frame")
                    val rows = json.getJSONArray("providers")
                    if (rows.length() > 2048) throw IOException("Too many loaded providers")
                    val keys = mutableSetOf<Pair<dev.aerove.lite.providers.ProviderKind, String>>()
                    CoreTelemetry.ProvidersLoaded((0 until rows.length()).map { index ->
                        val row = rows.getJSONObject(index)
                        if (row.keys().asSequence().toSet() != setOf("kind", "name", "revision", "phase", "changed")) throw IOException("Invalid provider loaded event")
                        val kind = when (row.get("kind")) { "proxy" -> dev.aerove.lite.providers.ProviderKind.Proxy; "rule" -> dev.aerove.lite.providers.ProviderKind.Rule; else -> throw IOException("Invalid provider kind") }
                        val name = row.get("name") as? String ?: throw IOException("Invalid provider name")
                        if (name.isBlank() || name.length > 1024 || name.any(Char::isISOControl) || !keys.add(kind to name)) throw IOException("Invalid provider name")
                        val raw = row.get("revision") as? String ?: throw IOException("Invalid provider revision")
                        val revision = raw.toULongOrNull()?.takeIf { it > 0uL && it.toString() == raw } ?: throw IOException("Invalid provider revision")
                        val phase = when (row.get("phase")) { "initial" -> dev.aerove.lite.telemetry.ProviderLoadPhase.Initial; "update" -> dev.aerove.lite.telemetry.ProviderLoadPhase.Update; else -> throw IOException("Invalid provider phase") }
                        val changed = row.get("changed") as? Boolean ?: throw IOException("Invalid provider change flag")
                        dev.aerove.lite.telemetry.ProviderLoaded(kind, name, revision, phase, changed)
                    })
                }
                "observations" -> CoreTelemetry.Observations(decodeObservationWindow(frame))
                else -> throw IOException("Core returned an unknown telemetry frame")
            }
        } catch (error: JSONException) {
            throw IOException("Malformed core telemetry frame", error)
        }
    }
}
