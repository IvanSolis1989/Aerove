package dev.aerove.lite.flutter

import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.CoreApiException
import dev.aerove.lite.data.CoreApiProtocolReceiptException
import dev.aerove.lite.data.RouteHit
import dev.aerove.lite.data.ProbeJson
import dev.aerove.lite.data.decodeRouteHits
import dev.aerove.lite.data.SubscriptionDownloader
import java.io.IOException
import java.math.BigInteger
import java.net.URI
import java.time.Instant
import java.util.Collections
import org.json.JSONArray
import org.json.JSONObject

/** Reads only the applied runtime receipt, never the currently edited Dart profile. */
interface MeowAppliedQueries {
    suspend fun proxyGroupOrder(): List<String>
    suspend fun proxyDefinition(name: String): Map<String, Any?>?
    /** Must include actual native health-check results and their test URL, not an assumed global URL. */
    suspend fun latestDelays(): MeowDelayHistorySnapshot = missing("getLatestDelays", "native ProxyHealth.history has no URL keys; a complete committed URL-aware producer is required")
}

/** Complete native history from one committed graph; owner/generation are attached by that endpoint's owner. */
data class MeowDelayHistorySnapshot(val owner: String, val generation: Long, val coreEpoch: ULong,
    val routeVersion: ULong, val historyRevision: ULong, val delays: List<Map<String, Any?>>)

class MeowQueryException(val code: String, message: String,
    val details: Map<String, Any?> = emptyMap(), cause: Throwable? = null) : IOException(message, cause)

internal class MeowQueryProtocol(private val client: CoreApiClient,
    private val telemetry: MeowTelemetryProtocol, private val applied: MeowAppliedQueries?,
    private val coreEpoch: ULong, private val owner: String, private val generation: Long) {
    private val selection = kotlinx.coroutines.sync.Mutex()
    private val historyGate = Any()
    private var lastHistory: Pair<ULong, List<Map<String, Any?>>>? = null

    suspend fun invoke(method: String, arguments: Any?): Any? = when (method) {
        "getProxies" -> {
            noArguments(arguments)
            val order = applied?.proxyGroupOrder() ?: missing(method, "applied proxy-group order")
            val raw = protocolObject(client.protocolSnapshot(listOf("proxies")))
            val proxies = protocolMap(raw["proxies"], "proxies")
            if (order.distinct().size != order.size) invalidCore("Duplicate applied proxy-group name")
            order.forEach { name ->
                if (protocolMap(proxies[name], "proxies.$name")["all"] !is List<*>)
                    invalidCore("Applied proxy group is absent from the running core: $name")
            }
            val groups = if ("GLOBAL" !in order && (proxies["GLOBAL"] as? Map<*, *>)?.get("all") is List<*>)
                listOf("GLOBAL") + order else order
            protocolCopy(raw + mapOf("proxies" to proxies, "all" to groups))
        }
        "getProxyDefinition" -> {
            val name = protocolName(arguments, "name")
            val source = applied ?: missing(method, "applied proxy/provider definition reader")
            source.proxyDefinition(name)?.let(::protocolCopy)
        }
        "getConnections" -> {
            noArguments(arguments)
            val raw = protocolObject(client.protocolSnapshot(listOf("connections")))
            val connections = protocolList(raw["connections"], "connections").map { protocolTracker(protocolMap(it, "connection")) }
            protocolCopy(raw + ("connections" to connections))
        }
        "getConnectionCount" -> { noArguments(arguments); client.connectionCount() }
        "closeConnection" -> {
            val id = protocolName(arguments, "id")
            val snapshot = protocolObject(client.protocolSnapshot(listOf("connections")))
            val exists = protocolList(snapshot["connections"], "connections").any { protocolMap(it, "connection")["id"] == id }
            if (exists) { client.closeConnection(id); true } else false
        }
        "closeConnections" -> { noArguments(arguments); client.closeAllConnections(); true }
        "resetConnections" -> { noArguments(arguments); client.resetConnections(); true }
        "clearDnsCache" -> { noArguments(arguments); client.clearDnsCache(); true }
        "getTraffic", "getTotalTraffic" -> telemetry.traffic(protocolBoolean(arguments, "onlyStatisticsProxy"), method == "getTotalTraffic")
        "getMemoryStats" -> {
            noArguments(arguments)
            missing(method, "Meow RSS has no producers for heapInuse, heapIdle, stackInuse and runtimeOther")
        }
        "getLatestDelays" -> {
            noArguments(arguments)
            latestDelays()
        }
        "asyncTestDelay" -> {
            val args = protocolMap(arguments, "arguments")
            protocolKeys(args, setOf("proxy-name", "test-url", "timeout"))
            val name = protocolName(args["proxy-name"], "proxy-name")
            val url = protocolName(args["test-url"], "test-url")
            val timeout = protocolInteger(args["timeout"], "timeout").also {
                if (it !in 1..60000) badQueryArgument("timeout", "Expected 1..60000 milliseconds")
            }.toInt()
            var stamp: Pair<ULong, ULong>? = null
            val measured = try { client.protocolDelay(name, url, timeout, coreEpoch) { epoch, version ->
                checkEpoch(epoch)
                stamp = epoch to version
            } } catch (failure: CoreApiException) {
                // The owned native route uses 504 only after its actual probe times out.
                if (failure.statusCode == 504) -1 else throw failure
            } catch (failure: MeowQueryException) {
                if (failure.details["status"] == 504) -1 else throw failure
            } catch (failure: CoreApiProtocolReceiptException) {
                invalidCore("Missing or invalid committed delay receipt: ${failure.field}", failure)
            }
            val committed = stamp ?: invalidCore("Core did not attest the delay result's epoch and route")
            telemetry.route(committed.first, committed.second)
            telemetry.delayResult(name, url, if (measured == 0) -2 else measured)
        }
        "changeProxy" -> {
            val args = protocolMap(arguments, "arguments")
            protocolKeys(args, setOf("group-name", "proxy-name"))
            val group = protocolName(args["group-name"], "group-name")
            val proxy = args["proxy-name"] as? String ?: badQueryArgument("proxy-name", "Expected a string")
            selection.lock()
            try {
                val before = client.routeSnapshot()
                checkEpoch(before.coreEpoch)
                if (before.picks[group] == null) badQueryArgument("group-name", "Running selector does not exist")
                if (proxy.isEmpty()) client.protocolUnpin(group) else client.select(group, proxy)
                val after = client.routeSnapshot()
                checkEpoch(after.coreEpoch)
                if (after.picks[group] == null || (proxy.isNotEmpty() && after.picks[group] != proxy)) invalidCore("Core did not confirm the requested selection")
                telemetry.route(after.coreEpoch, after.routeVersion)
                mapOf("message" to "", "changed" to (before.picks[group] != after.picks[group]))
            } finally { selection.unlock() }
        }
        "watchRoute" -> telemetry.watchRoute(protocolBoolean(arguments, "watch"))
        "checkRoutes" -> {
            val args = protocolMap(arguments, "arguments")
            protocolKeys(args, setOf("routes"))
            val routes = protocolList(args["routes"], "routes").map { values ->
                protocolList(values, "route").map { value ->
                    val row = protocolMap(value, "hit")
                    if (row.keys.any { it !in setOf("host", "proxy", "exit") }) badQueryArgument("hit", "Unknown field")
                    RouteHit(protocolText(row["host"], "host"), protocolText(row["proxy"] ?: "", "proxy"),
                        protocolText(row["exit"] ?: "", "exit"))
                }
            }
            val value = client.checkRoutes(routes)
            checkEpoch(value.coreEpoch)
            protocolStamp(value.coreEpoch, value.routeVersion) + ("held" to value.held)
        }
        "getExternalProviders" -> { noArguments(arguments); providers() }
        "getExternalProvider" -> {
            val name = protocolName(arguments, "name")
            var answer: Map<String, Any?>? = null
            for (kind in listOf("rules", "proxies")) {
                val raw = try { protocolObject(client.protocolProvider(kind, name)) } catch (failure: CoreApiException) {
                    if (failure.statusCode == 404) continue else throw failure
                } catch (failure: MeowQueryException) {
                    if (failure.details["status"] == 404) continue else throw failure
                }
                answer = provider(name, kind, raw)
                if (answer != null) break
            }
            answer
        }
        "startLog" -> { noArguments(arguments); telemetry.setLogEnabled(true); null }
        "stopLog" -> { noArguments(arguments); telemetry.setLogEnabled(false); null }
        "probe", "outboundIp" -> routedProbe(arguments, method == "outboundIp")
        "resetTraffic" -> missing(method, "no matching native API with shared argument/result semantics")
        else -> throw MeowQueryException("unsupported_method", "Unknown query method: $method")
    }

    private fun checkEpoch(actual: ULong) {
        if (actual != coreEpoch) throw MeowQueryException("stale_session", "Core epoch changed", mapOf("expected" to coreEpoch.toString(), "actual" to actual.toString()))
    }

    private suspend fun routedProbe(arguments: Any?, outbound: Boolean): Map<String, Any?> {
        val args = protocolMap(arguments, "arguments")
        val allowed = if (outbound) setOf("proxy-name", "urls", "timeout") else setOf("url", "proxy-name", "headers", "timeout", "max-body")
        val required = if (outbound) setOf("timeout") else setOf("url", "timeout")
        if (!args.keys.containsAll(required) || args.keys.any { it !in allowed }) badQueryArgument("arguments", "Invalid routed probe fields")
        val proxy = if ("proxy-name" in args) protocolText(args["proxy-name"], "proxy-name") else ""
        if (proxy.toByteArray(Charsets.UTF_8).size > 4096 || proxy.any(Char::isISOControl)) badQueryArgument("proxy-name", "Invalid proxy name")
        val timeout = protocolInteger(args["timeout"], "timeout")
        if (timeout > 60000) badQueryArgument("timeout", "Expected at most 60000 milliseconds; non-positive uses 10000")
        val maxBody = if (!outbound && "max-body" in args) protocolInteger(args["max-body"], "max-body") else 0L
        if (maxBody > 8 * 1024 * 1024) badQueryArgument("max-body", "Body limit exceeds 8 MiB")
        fun url(value: Any?, field: String): String = protocolText(value, field).also {
            if (it.toByteArray(Charsets.UTF_8).size > 16384 || it.any(Char::isISOControl)) badQueryArgument(field, "Invalid URL")
            try { SubscriptionDownloader.parseUrl(it) } catch (_: IllegalArgumentException) { badQueryArgument(field, "Expected HTTP(S) URL without user-info or fragment") }
        }
        val urls = if (outbound) {
            val values = if ("urls" in args) protocolList(args["urls"], "urls") else emptyList<Any?>()
            if (values.size > 64) badQueryArgument("urls", "At most 64 caller URLs")
            values.map { url(it, "urls") }
        } else listOf(url(args["url"], "url"))
        val headers = if (!outbound && "headers" in args) protocolMap(args["headers"], "headers").mapValues { (name, value) ->
            if (name.isEmpty() || name.any { it !in "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" })
                badQueryArgument("headers", "Invalid header name")
            protocolText(value, "headers.$name").also { text ->
                if (text.any { (it < ' ' && it != '\t') || it == '\u007f' }) badQueryArgument("headers", "Invalid header value")
            }
        } else emptyMap()
        if (headers.size > 128) badQueryArgument("headers", "At most 128 headers")
        val payload = if (outbound) JSONObject().put("urls", JSONArray(urls)).put("proxy-name", proxy).put("timeout", timeout)
            else JSONObject().put("url", urls.single()).put("proxy-name", proxy).put("timeout", timeout).put("max-body", maxBody).put("headers", JSONObject(headers))
        if (payload.toString().toByteArray(Charsets.UTF_8).size > if (outbound) 1024 * 1024 else 65536)
            badQueryArgument("arguments", "Native request body limit exceeded")
        val source = if (outbound) client.protocolOutboundIp(proxy, urls, timeout, coreEpoch)
            else client.protocolProbe(urls.single(), proxy, headers, timeout, maxBody, coreEpoch)
        val result = decodeRoutedProbe(source, outbound, proxy, urls, maxBody)
        val epoch = protocolU64(result["core-epoch"], "core-epoch")
        val version = protocolU64(result["route-version"], "route-version")
        val current = client.routeSnapshot()
        checkEpoch(current.coreEpoch)
        if (current.routeVersion != version) throw MeowQueryException("route_superseded", "Probe receipt no longer describes the current route",
            mapOf("probe-route" to version.toString(), "current-route" to current.routeVersion.toString()))
        telemetry.route(epoch, version)
        @Suppress("UNCHECKED_CAST")
        return protocolCopy(result) as Map<String, Any?>
    }

    private fun decodeRoutedProbe(source: String, outbound: Boolean, proxy: String, urls: List<String>, maxBody: Long): Map<String, Any?> {
        val raw = try { val input = ProbeJson(source); input.fields().also { input.end() } }
            catch (failure: IOException) { invalidCore("Malformed routed probe JSON", failure) }
        val required = if (outbound) setOf("url", "body", "delay", "chains", "core-epoch", "route-version", "hits", "node-hits")
            else setOf("status-code", "delay", "body", "url", "chains", "rule", "rule-payload", "core-epoch", "route-version", "hits")
        val optional = if (outbound) setOf("error") else setOf("error", "message")
        if (!raw.keys.containsAll(required) || raw.keys.any { it !in required && it !in optional }) invalidCore("Invalid routed probe result schema")
        fun text(field: String): String = raw[field] as? String ?: invalidCore("Invalid probe $field")
        fun counter(field: String): ULong = (raw[field] as? ULong) ?: invalidCore("Invalid probe $field integer")
        fun stamp(field: String): ULong = protocolU64(text(field), field)
        val epoch = stamp("core-epoch"); checkEpoch(epoch)
        val version = stamp("route-version")
        val error = if ("error" in raw) text("error").also { if (it !in setOf("failed", "timeout")) invalidCore("Unknown native probe failure") } else null
        if ("message" in raw) text("message")
        val body = text("body"); val url = text("url"); val delay = counter("delay")
        val chains = (raw["chains"] as? List<*>)?.map {
            (it as? String)?.takeIf { name -> name.isNotEmpty() && name.length <= 16384 && name.none(Char::isISOControl) }
                ?: invalidCore("Invalid probe chain")
        } ?: invalidCore("Missing probe chains")
        fun decodeHits(field: String): List<RouteHit> {
            val hits = try { decodeRouteHits(raw[field]) } catch (failure: IOException) { invalidCore("Invalid probe $field", failure) }
            if (hits.map { it.host to it.proxy }.distinct().size != hits.size) invalidCore("Duplicate probe $field key")
            hits.forEach { hit ->
                val valid = try { URI("http://${hit.host}/").let { it.host != null && it.port in 1..65535 && it.rawUserInfo == null && it.rawPath == "/" && it.rawQuery == null && it.rawFragment == null } }
                    catch (_: Exception) { false }
                if (!valid) invalidCore("Invalid probe hit address")
            }
            return hits
        }
        val hits = decodeHits("hits")
        if (hits.any { it.proxy != proxy }) invalidCore("Probe hit does not attest the requested proxy")
        if (error == null) {
            if (hits.isEmpty()) invalidCore("Successful probe omitted its route hits")
            try { SubscriptionDownloader.parseUrl(url) } catch (failure: IllegalArgumentException) { invalidCore("Invalid successful probe URL", failure) }
        } else if (delay != 0uL || body.isNotEmpty() || chains.isNotEmpty()) invalidCore("Native failed probe has inconsistent response fields")
        val result = linkedMapOf<String, Any?>("url" to url, "body" to body, "delay" to protocolNumber(delay), "chains" to chains)
        if (outbound) {
            val nodes = decodeHits("node-hits")
            val node = chains.firstOrNull()
            if (nodes.any { it.proxy != node || hits.none { hit -> hit.host == it.host } }) invalidCore("Invalid winner node hit ownership")
            if (error == null) {
                if (url !in urls || body.length > 4096) invalidCore("Outbound result does not match caller URLs or body limit")
                if (node != null && node != proxy && nodes.isEmpty()) invalidCore("Outbound result omitted winner node hits")
                if ((node == null || node == proxy) && nodes.isNotEmpty()) invalidCore("Unexpected outbound node hits")
            } else if (url.isNotEmpty() || nodes.isNotEmpty()) invalidCore("Invalid failed outbound result")
            result["node-hits"] = nodes.map { mapOf("host" to it.host, "proxy" to it.proxy, "exit" to it.exit) }
        } else {
            val status = counter("status-code")
            val rule = text("rule"); val payload = text("rule-payload")
            if (error == null && status !in 100uL..999uL || error != null && (status != 0uL || url != urls.single() || rule.isNotEmpty() || payload.isNotEmpty()))
                invalidCore("Invalid probe response status or failure fields")
            if (body.length.toLong() > maxBody.coerceAtLeast(0)) invalidCore("Probe ignored the requested body cap")
            result.putAll(mapOf("status-code" to status.toInt(), "rule" to rule, "rule-payload" to payload))
        }
        if (error != null) result["error"] = error
        if ("message" in raw) result["message"] = text("message")
        result.putAll(protocolStamp(epoch, version))
        result["hits"] = hits.map { mapOf("host" to it.host, "proxy" to it.proxy, "exit" to it.exit) }
        return result
    }

    private suspend fun latestDelays(): List<Map<String, Any?>> {
        val snapshot = applied?.latestDelays() ?: missing("getLatestDelays", "complete native delay-history reader")
        if (snapshot.owner != owner || snapshot.generation != generation) throw MeowQueryException("stale_session",
            "Delay history belongs to another runtime owner or generation")
        checkEpoch(snapshot.coreEpoch)
        val keys = mutableSetOf<Pair<String, String>>()
        val rows = snapshot.delays.map { row ->
            try {
                val name = protocolName(row["name"], "delay.name")
                val url = protocolName(row["url"], "delay.url")
                SubscriptionDownloader.parseUrl(url)
                val value = protocolInteger(row["value"], "delay.value")
                // Standard getLatestDelays uses delayValue(..., false): failure is -2, never timeout -1 or 0.
                if (value != -2L && value !in 1..65535) invalidCore("Invalid latest delay value")
                if (!keys.add(name to url)) invalidCore("Duplicate proxy/URL history key")
                protocolMap(protocolCopy(row + ("value" to value.toInt())), "delay")
            } catch (failure: Exception) { invalidCore("Invalid URL-aware delay history row", failure) }
        }.sortedWith(compareBy({ it["name"] as String }, { it["url"] as String }))
        val current = client.routeSnapshot()
        checkEpoch(current.coreEpoch)
        if (current.routeVersion != snapshot.routeVersion) throw MeowQueryException("route_superseded",
            "Delay history does not describe the current route graph", mapOf("history-route" to snapshot.routeVersion.toString(), "current-route" to current.routeVersion.toString()))
        synchronized(historyGate) {
            lastHistory?.let { (revision, previous) ->
                if (snapshot.historyRevision < revision) throw MeowQueryException("history_superseded", "Delay history revision moved backwards")
                if (snapshot.historyRevision == revision && rows != previous) invalidCore("History changed without advancing its revision")
            }
            lastHistory = snapshot.historyRevision to rows
        }
        telemetry.route(current.coreEpoch, current.routeVersion)
        @Suppress("UNCHECKED_CAST")
        return protocolCopy(rows) as List<Map<String, Any?>>
    }

    private suspend fun providers(): List<Map<String, Any?>> {
        val result = linkedMapOf<String, Map<String, Any?>>()
        // Shared lookup gives rule providers precedence when names collide.
        for (kind in listOf("proxies", "rules")) {
            val root = protocolObject(client.protocolSnapshot(listOf("providers", kind)))
            for ((name, value) in protocolMap(root["providers"], "providers")) {
                provider(name, kind, protocolMap(value, "provider"))?.let { result[name] = it }
            }
        }
        return result.toSortedMap().values.toList()
    }

    private fun provider(name: String, kind: String, raw: Map<String, Any?>): Map<String, Any?>? {
        val vehicle = protocolText(raw["vehicleType"], "vehicleType")
        if (vehicle.equals("Compatible", true) || vehicle.equals("Inline", true)) return null
        if (raw["name"] != null && raw["name"] != name) invalidCore("Provider name does not match its lookup identity")
        val type = protocolText(raw["type"], "type")
        val updated = protocolText(raw["updatedAt"], "updatedAt")
        try { Instant.parse(updated) } catch (failure: Exception) { invalidCore("Provider has no valid update timestamp", failure) }
        val count = when (kind) {
            "proxies" -> protocolList(raw["proxies"], "proxies").size.toLong()
            else -> protocolInteger(raw["ruleCount"], "ruleCount")
        }
        if (count < 0) invalidCore("Negative provider count")
        // Keep every native field alongside the shared aliases (including subscriptionInfo/path/revision).
        return protocolMap(protocolCopy(raw + mapOf("name" to name, "type" to type, "count" to count,
            "vehicle-type" to vehicle, "update-at" to updated,
            "subscription-info" to raw["subscriptionInfo"])), "provider")
    }
}

internal fun noArguments(value: Any?) { if (value != null) badQueryArgument("arguments", "Expected null") }
internal fun protocolHttpFailure(status: Int, body: String): MeowQueryException {
    val data = try { protocolObject(body) } catch (_: Exception) { emptyMap() }
    val code = (data["error"] as? String)?.takeIf(String::isNotBlank) ?: "core_http_error"
    val message = data["message"] as? String ?: "Core returned HTTP $status ($code)"
    return MeowQueryException(code, message, mapOf("status" to status, "native" to data, "body" to body))
}
internal fun missing(method: String, reason: String): Nothing = throw MeowQueryException("implementation_missing", "$method: $reason", mapOf("method" to method, "missing" to reason))
internal fun badQueryArgument(field: String, reason: String): Nothing = throw MeowQueryException("bad_argument", "$field: $reason", mapOf("field" to field))
internal fun invalidCore(reason: String, cause: Throwable? = null): Nothing = throw MeowQueryException("invalid_core_response", reason, cause = cause)
internal fun protocolName(value: Any?, field: String): String = protocolText(value, field).also {
    if (it.isBlank() || it.length > 16384 || it.any(Char::isISOControl)) badQueryArgument(field, "Invalid name")
}
internal fun protocolText(value: Any?, field: String): String = value as? String ?: badQueryArgument(field, "Expected a string")
internal fun protocolBoolean(value: Any?, field: String): Boolean = value as? Boolean ?: badQueryArgument(field, "Expected a boolean")
internal fun protocolInteger(value: Any?, field: String): Long = when (value) {
    is Int -> value.toLong(); is Long -> value
    is BigInteger -> try { value.longValueExact() } catch (_: ArithmeticException) { badQueryArgument(field, "Integer out of range") }
    else -> badQueryArgument(field, "Expected an integer")
}
internal fun protocolKeys(value: Map<String, Any?>, keys: Set<String>) {
    if (value.keys != keys) badQueryArgument("arguments", "Expected exactly ${keys.joinToString()}")
}
internal fun protocolMap(value: Any?, field: String): Map<String, Any?> {
    val raw = value as? Map<*, *> ?: badQueryArgument(field, "Expected an object")
    if (raw.keys.any { it !is String }) badQueryArgument(field, "Expected string keys")
    @Suppress("UNCHECKED_CAST") return raw as Map<String, Any?>
}
internal fun protocolList(value: Any?, field: String): List<*> = value as? List<*> ?: badQueryArgument(field, "Expected an array")
internal fun protocolObject(source: String): Map<String, Any?> = try {
    protocolMap(protocolCopy(JSONObject(source)), "response")
} catch (failure: Exception) { invalidCore("Invalid core JSON object", failure) }

/** BigInteger is emitted as an exact JSON integer; u64 identities must never pass through Double. */
internal fun protocolU64(value: Any?, field: String): ULong {
    val text = when (value) {
        is String -> value
        is Int, is Long, is BigInteger -> value.toString()
        else -> invalidCore("Invalid $field unsigned integer")
    }
    return text.toULongOrNull()?.takeIf { it.toString() == text } ?: invalidCore("Invalid $field unsigned integer")
}
internal fun protocolNumber(value: ULong): Number = if (value <= Long.MAX_VALUE.toULong()) value.toLong() else BigInteger(value.toString())
internal fun protocolStamp(epoch: ULong, version: ULong): Map<String, Any?> = mapOf("core-epoch" to protocolNumber(epoch), "route-version" to protocolNumber(version))
internal fun protocolCopy(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> protocolCopy(value.keys().asSequence().associateWith { value.get(it) })
    is JSONArray -> protocolCopy((0 until value.length()).map(value::get))
    is Map<*, *> -> Collections.unmodifiableMap(value.entries.associate { it.key.toString() to protocolCopy(it.value) })
    is List<*> -> Collections.unmodifiableList(value.map(::protocolCopy))
    is String, is Boolean, is Number -> value
    else -> invalidCore("Unsupported wire value ${value.javaClass.name}")
}

internal fun protocolTracker(raw: Map<String, Any?>): Map<String, Any?> {
    val meta = protocolMap(raw["metadata"], "metadata").toMutableMap()
    for (field in listOf("sourcePort", "destinationPort")) {
        meta[field]?.let { value ->
            val text = if (value is String) value else protocolInteger(value, field).toString()
            if (text.toIntOrNull() !in 0..65535) invalidCore("Invalid tracker $field")
            meta[field] = text
        }
    }
    meta["uid"]?.let { value ->
        meta["uid"] = when (value) {
            is String -> value.toLongOrNull() ?: invalidCore("Invalid tracker UID")
            else -> protocolInteger(value, "uid")
        }
    }
    return protocolMap(protocolCopy(raw + mapOf("metadata" to meta,
        "upload" to protocolNumber(protocolU64(raw["upload"], "upload")),
        "download" to protocolNumber(protocolU64(raw["download"], "download")))), "tracker")
}
