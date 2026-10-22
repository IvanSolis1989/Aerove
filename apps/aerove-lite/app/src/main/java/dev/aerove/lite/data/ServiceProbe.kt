package dev.aerove.lite.data

import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class ServiceTarget(val id: String, val label: String, val icon: String) {
    Google("google", "Google", "google"),
    GitHub("github", "GitHub", "github"),
    YouTube("youtube", "YouTube", "youtube"),
    ChatGpt("chatgpt", "ChatGPT", "openai"),
    Claude("claude", "Claude", "claude"),
    Gemini("gemini", "Gemini", "gemini"),
    Netflix("netflix", "Netflix", "netflix"),
    DisneyPlus("disney-plus", "Disney+", "disneyplus"),
    PrimeVideo("prime-video", "Prime Video", "primevideo"),
    Spotify("spotify", "Spotify", "spotify"),
    TikTok("tiktok", "TikTok", "tiktok"),
    Discord("discord", "Discord", "discord"),
    Bilibili("bilibili", "bilibili", "bilibili");

    companion object {
        fun byId(id: String): ServiceTarget? = entries.firstOrNull { it.id == id }
    }
}

enum class ServiceProbeStatus(val id: String) {
    Available("available"), Unavailable("unavailable"), Restricted("restricted"),
    DisallowedIsp("disallowed-isp"), Blocked("blocked"), UnsupportedRegion("unsupported-region"),
    OriginalsOnly("originals-only"), ComingSoon("coming-soon"), Timeout("timeout"), Failed("failed");

    companion object {
        fun byId(id: String): ServiceProbeStatus? = entries.firstOrNull { it.id == id }
    }
}

data class ServiceCheck(
    val target: ServiceTarget,
    val status: ServiceProbeStatus,
    val region: String?,
    val delayMillis: Long,
    val chains: List<String>,
    val checkedAtMillis: Long,
    val coreEpoch: ULong,
    val picksVersion: ULong,
) {
    val node: String? get() = chains.firstOrNull()
}

enum class ServiceCheckFailure(val code: String) {
    Superseded("service_check_superseded"), SessionStopped("session_stopped"),
    ClockUnavailable("clock_unavailable"), NoQueryParameters("no_query_parameters"),
    InvalidBody("invalid_request_body"), InvalidParameters("invalid_service_parameters"),
    InvalidTargetsOrBudget("invalid_service_targets_or_budget"),
}

class ServiceCheckException(val statusCode: Int, val reason: ServiceCheckFailure?) :
    IOException("Core service check returned HTTP $statusCode${reason?.let { ": ${it.code}" }.orEmpty()}")

internal fun serviceCheckFailure(statusCode: Int, body: String): ServiceCheckException {
    val code = try { JSONObject(body).opt("error") as? String } catch (_: JSONException) { null }
    return ServiceCheckException(statusCode, ServiceCheckFailure.entries.firstOrNull { it.code == code })
}

internal fun serviceCheckBody(targets: List<ServiceTarget>, proxyName: String, timeoutMillis: Int): JSONObject {
    require(targets.isNotEmpty() && targets.size <= ServiceTarget.entries.size && targets.distinct().size == targets.size) {
        "Service checks require explicit, unique targets"
    }
    require(timeoutMillis in 1..60000) { "Service timeout must be between 1 and 60000 milliseconds" }
    require(proxyName.toByteArray(Charsets.UTF_8).size <= 4096) { "Service proxy name is too long" }
    return JSONObject().put("names", JSONArray(targets.map { it.id }))
        .put("proxy-name", proxyName).put("timeout", timeoutMillis).also {
            require(it.toString().toByteArray(Charsets.UTF_8).size <= 8192) { "Service request is too large" }
        }
}

internal fun decodeServiceChecks(source: String, targets: List<ServiceTarget>): List<ServiceCheck> {
    val input = ProbeJson(source)
    val results = mutableListOf<ServiceCheck>()
    input.expect('[')
    if (!input.take(']')) {
        do {
            if (results.size >= targets.size) throw IOException("Core returned extra service checks")
            val row = input.fields()
            if (row.keys != serviceFields) throw IOException("Core returned an invalid service schema")
            val target = ServiceTarget.byId(row["name"] as? String ?: "")
            if (target != targets[results.size]) throw IOException("Core returned mismatched service targets")
            val status = ServiceProbeStatus.byId(row["status"] as? String ?: "")
                ?: throw IOException("Core returned an unknown service status")
            val region = (row["region"] as? String)?.takeIf { it.isEmpty() || it.matches(Regex("[A-Z]{2}")) }
                ?: throw IOException("Core returned an invalid service region")
            val chains = (row["chains"] as? List<*>)?.map {
                (it as? String)?.takeIf { name -> name.isNotEmpty() && name.none(Char::isISOControl) }
                    ?: throw IOException("Core returned an invalid service chain")
            } ?: throw IOException("Core returned an invalid service chain")
            if (chains.isEmpty() && status != ServiceProbeStatus.Failed && status != ServiceProbeStatus.Timeout) {
                throw IOException("Core service response is missing its actual route chain")
            }
            results += ServiceCheck(
                target, status, region.ifEmpty { null }, row.long("delay"), chains,
                row.long("checked-at").also { if (it == 0L) throw IOException("Core returned an unstamped service check") },
                row.unsigned("core-epoch").also { if (it == 0uL) throw IOException("Core returned an invalid route epoch") },
                row.unsigned("picks-version"),
            )
        } while (input.take(','))
        input.expect(']')
    }
    input.end()
    if (results.size != targets.size) throw IOException("Core returned missing service checks")
    if (results.any { it.coreEpoch != results[0].coreEpoch || it.picksVersion != results[0].picksVersion }) {
        throw IOException("Core returned inconsistent service route stamps")
    }
    return results
}

private val serviceFields = setOf("name", "status", "region", "delay", "chains", "checked-at", "core-epoch", "picks-version")

internal fun Map<String, Any>.unsigned(key: String): ULong =
    this[key] as? ULong ?: throw IOException("Core returned an invalid $key counter")

internal fun Map<String, Any>.long(key: String): Long = unsigned(key).let {
    if (it > Long.MAX_VALUE.toULong()) throw IOException("Core returned an overflowing $key counter")
    it.toLong()
}

// Android JSONObject rounds uint64 through Double; read flat wire counters from their integer tokens instead.
internal class ProbeJson(private val source: String) {
    private var offset = 0

    private fun peek(): Char {
        while (offset < source.length && source[offset] in " \t\r\n") offset++
        return source.getOrNull(offset) ?: '\u0000'
    }

    fun take(symbol: Char): Boolean = if (peek() == symbol) { offset++; true } else false
    fun expect(symbol: Char) { if (!take(symbol)) invalid() }
    fun end() { peek(); if (offset != source.length) invalid() }

    fun fields(depth: Int = 0): Map<String, Any> {
        if (depth > 1) invalid()
        expect('{')
        val fields = linkedMapOf<String, Any>()
        if (take('}')) return fields
        do {
            val name = string()
            if (fields.containsKey(name)) invalid()
            expect(':')
            fields[name] = when (peek()) {
                '"' -> string()
                '[' -> strings()
                '{' -> fields(depth + 1)
                else -> number()
            }
        } while (take(','))
        expect('}')
        return fields
    }

    private fun strings(): List<String> {
        expect('[')
        if (take(']')) return emptyList()
        val values = mutableListOf<String>()
        do { values += string() } while (take(','))
        expect(']')
        return values
    }

    private fun number(): ULong {
        peek()
        val start = offset
        while (source.getOrNull(offset) in '0'..'9') offset++
        val token = source.substring(start, offset)
        if (token.length > 1 && token[0] == '0') invalid()
        return token.toULongOrNull() ?: invalid()
    }

    private fun string(): String {
        peek()
        val start = offset
        expect('"')
        while (offset < source.length) {
            val value = source[offset++]
            if (value == '"') {
                return try { JSONTokener(source.substring(start, offset)).nextValue() as String }
                catch (_: JSONException) { invalid() }
            }
            if (value < ' ') invalid()
            if (value == '\\') {
                when (source.getOrNull(offset++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) { if (source.getOrNull(offset++)?.digitToIntOrNull(16) == null) invalid() }
                    else -> invalid()
                }
            }
        }
        invalid()
    }

    private fun invalid(): Nothing = throw IOException("Malformed core probe response")
}
