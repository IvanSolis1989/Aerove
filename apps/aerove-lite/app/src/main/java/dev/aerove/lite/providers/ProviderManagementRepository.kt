package dev.aerove.lite.providers

import dev.aerove.lite.ProxyNode
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiException
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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class ProviderKind(val segment: String) { Proxy("proxies"), Rule("rules") }
data class RuntimeProvider(
    val name: String, val kind: ProviderKind, val vehicleType: String,
    val updatedAt: String?, val behavior: String?, val format: String?, val proxies: List<ProxyNode>,
    val ruleCount: Long? = null,
    val revision: String? = null,
)

data class RuleProviderPage(val rules: List<String>, val ruleCount: Long, val offset: Long,
    val nextOffset: Long?, val complete: Boolean, val coreEpoch: String)

class RuleProviderChangedException : IOException("Rule provider changed; reload its preview")

/** One endpoint/epoch per instance. No background polling; callers close this on core replacement. */
class ProviderManagementRepository(private val endpoint: ControlEndpoint, client: OkHttpClient = OkHttpClient()) : Closeable {
    private val http = client.newBuilder().cache(null).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).callTimeout(75, TimeUnit.SECONDS).build()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)

    suspend fun snapshot(): List<RuntimeProvider> = ProviderKind.entries.flatMap { kind ->
        val values = request(listOf("providers", kind.segment)).getJSONObject("providers")
        require(values.length() <= 1024) { "Core returned too many providers" }
        values.keys().asSequence().map { decode(it, kind, values.getJSONObject(it)) }
            .filterNot { it.vehicleType.equals("Compatible", true) }.toList()
    }

    suspend fun read(kind: ProviderKind, name: String): RuntimeProvider =
        decode(checked(name), kind, request(listOf("providers", kind.segment, name)))

    /** Refreshes the active core provider. Remote source sync is separately owned by the resource generation pipeline. */
    suspend fun update(kind: ProviderKind, name: String): RuntimeProvider {
        request(listOf("providers", kind.segment, checked(name)), "PUT")
        return read(kind, name)
    }

    suspend fun healthcheck(name: String): RuntimeProvider {
        request(listOf("providers", "proxies", checked(name), "healthcheck"))
        return read(ProviderKind.Proxy, name)
    }

    suspend fun rulePage(name: String, offset: Long = 0, limit: Int = 500, coreEpoch: String? = null): RuleProviderPage {
        require(offset >= 0 && limit in 1..10000) { "Invalid rule page range" }
        require(coreEpoch == null || validEpoch(coreEpoch)) { "Invalid rule page epoch" }
        val query = linkedMapOf("offset" to offset.toString(), "limit" to limit.toString())
        if (coreEpoch != null) query["coreEpoch"] = coreEpoch
        val row = try { request(listOf("providers", "rules", checked(name), "rules"), query = query) }
            catch (failure: CoreApiException) { if (failure.statusCode == 409) throw RuleProviderChangedException(); throw failure }
        fun integer(key: String): Long = (row.get(key) as? Number)?.toString()?.toLongOrNull()
            ?: throw IOException("Invalid rule page $key")
        val epoch = row.get("coreEpoch") as? String ?: throw IOException("Invalid rule page epoch")
        if (!validEpoch(epoch)) throw IOException("Invalid rule page epoch")
        if (coreEpoch != null && epoch != coreEpoch) throw RuleProviderChangedException()
        val count = integer("ruleCount")
        val actualOffset = integer("offset")
        val complete = row.get("complete") as? Boolean ?: throw IOException("Invalid rule page completion")
        require(row.has("nextOffset")) { "Missing rule page continuation" }
        val next = if (row.isNull("nextOffset")) null else integer("nextOffset")
        val rows = row.getJSONArray("rules")
        require(rows.length() <= limit && count in 0..Int.MAX_VALUE.toLong() && actualOffset == offset) { "Invalid rule page range" }
        val end = Math.addExact(offset, rows.length().toLong())
        require((if (offset >= count) rows.length() == 0 else end <= count) && complete == (end >= count) &&
            (if (complete) next == null else next == end && end > offset)) { "Invalid rule page continuation" }
        val rules = (0 until rows.length()).map { rows.get(it) as? String ?: throw IOException("Invalid rule entry") }
        return RuleProviderPage(rules, count, offset, next, complete, epoch)
    }

    /** Streams a consistent complete dump. A changed epoch/HTTP409 fails explicitly, without polling/retry. */
    suspend fun forEachRulePage(name: String, limit: Int = 500, onPage: suspend (RuleProviderPage) -> Unit) {
        var offset = 0L
        var epoch: String? = null
        var count: Long? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = rulePage(name, offset, limit, epoch)
            if (count != null && page.ruleCount != count) throw RuleProviderChangedException()
            epoch = page.coreEpoch; count = page.ruleCount
            onPage(page)
            currentCoroutineContext().ensureActive()
            offset = page.nextOffset ?: return
        } while (true)
    }

    suspend fun previewRules(name: String): List<String> = buildList {
        forEachRulePage(name) { addAll(it.rules) }
    }

    private fun validEpoch(value: String): Boolean = value.length in 1..20 &&
        value.all { it in '0'..'9' } && value.toULongOrNull()?.toString() == value

    private fun checked(name: String): String = name.also {
        require(it.isNotBlank() && it != "." && it != ".." && it.length <= 1024 && it.none(Char::isISOControl)) { "Invalid provider name" }
    }

    private fun decode(name: String, kind: ProviderKind, row: JSONObject): RuntimeProvider {
        val revision = if (!row.has("revision") || row.isNull("revision")) null else {
            val value = row.get("revision") as? String ?: throw IOException("Invalid provider revision")
            if (!validEpoch(value) || value == "0") throw IOException("Invalid provider revision")
            value
        }
        val nodes = row.optJSONArray("proxies")
        require(nodes == null || nodes.length() <= 100000) { "Core returned too many provider proxies" }
        return RuntimeProvider(name, kind, row.getString("vehicleType"),
            row.optString("updatedAt").takeIf { it.isNotEmpty() && it != "null" },
            row.optString("behavior").takeIf(String::isNotEmpty), row.optString("format").takeIf(String::isNotEmpty),
            if (nodes == null) emptyList() else (0 until nodes.length()).map { index ->
                val node = nodes.getJSONObject(index)
                val history = node.optJSONArray("history")
                val delay = history?.takeIf { it.length() > 0 }?.getJSONObject(history.length() - 1)?.optInt("delay")
                ProxyNode(node.getString("name"), node.getString("type"), delay?.takeIf { it > 0 })
            }, if (!row.has("ruleCount")) null else (row.get("ruleCount") as? Number)?.toString()?.toLongOrNull()
                ?.takeIf { it >= 0 } ?: throw IOException("Invalid provider rule count"), revision)
    }

    private suspend fun request(path: List<String>, method: String = "GET", query: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        check(!closed.get()) { "Provider client is closed" }
        val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(endpoint.port).apply {
            path.forEach(::addPathSegment); query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${endpoint.secret}")
            .method(method, if (method == "PUT") ByteArray(0).toRequestBody() else null).build()
        val call = http.newCall(request)
        val cancellation = Job(currentCoroutineContext()[Job])
        cancellation.invokeOnCompletion { if (it != null) call.cancel() }
        calls += call
        try {
            check(!closed.get()) { "Provider client is closed" }
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) throw CoreApiException(response.code, "providers")
                JSONObject(response.body?.byteStream()?.use { it.readBounded(8 * 1024 * 1024).strictUtf8() }.orEmpty().ifBlank { "{}" })
            }
        } catch (failure: IOException) { currentCoroutineContext().ensureActive(); throw failure }
        finally { calls -= call; cancellation.complete() }
    }

    override fun close() { if (closed.compareAndSet(false, true)) calls.forEach(Call::cancel) }
}
