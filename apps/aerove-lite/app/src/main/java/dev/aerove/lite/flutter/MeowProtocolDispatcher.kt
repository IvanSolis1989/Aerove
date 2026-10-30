package dev.aerove.lite.flutter

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

fun interface MeowProtocolMethod {
    suspend fun invoke(arguments: Any?): Any?
}

class MeowProtocolFailure(val code: String, message: String,
    val details: Map<String, Any?> = emptyMap(), cause: Throwable? = null) : Exception(message, cause)

class MeowProtocolDispatcher(
    homeDir: File,
    handlers: Map<String, MeowProtocolMethod>,
    private val discardResult: (method: String, result: Any?) -> Unit = { _, _ -> },
    private val translateFailure: (Throwable) -> MeowProtocolFailure? = { null },
) {
    private val handlers = handlers.toMap()
    private val spills = MeowFlutterSpill(homeDir)
    private val monitor = Any()
    private val active = mutableMapOf<String, Deferred<ByteArray>>()
    @Volatile private var closed = false

    init {
        require(handlers.keys.all { it in METHODS && it != "message" }) { "Unknown Meow method binding" }
    }

    val implementedMethods: Set<String> get() = handlers.keys.toSet()
    val missingMethods: Set<String> get() = METHODS - handlers.keys - "message"

    suspend fun invoke(encoded: String): ByteArray {
        val call = try { decode(encoded) }
        catch (failure: InvalidCall) { return response(failure.id, null, failure.problem) }
        return coroutineScope {
            val task = async(start = CoroutineStart.LAZY) { execute(call) }
            val rejection = synchronized(monitor) {
                when {
                    closed -> MeowProtocolFailure("closed", "Meow protocol attachment is closed")
                    active.containsKey(call.id) -> MeowProtocolFailure("duplicate_call", "Request id is already active")
                    else -> { active[call.id] = task; null }
                }
            }
            if (rejection != null) {
                task.cancel()
                return@coroutineScope response(call.id, null, rejection)
            }
            task.start()
            try { task.await() }
            catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                response(call.id, null, MeowProtocolFailure("cancelled", "Meow request was cancelled"))
            } finally {
                synchronized(monitor) { if (active[call.id] === task) active.remove(call.id) }
            }
        }
    }

    suspend fun cancel(id: String, budgetMillis: Long = 45_000): Boolean {
        require(budgetMillis in 1..45_000)
        val task = synchronized(monitor) { active[id] } ?: return true
        task.cancel(CancellationException("Meow request cancellation"))
        return withTimeoutOrNull(budgetMillis) { task.join(); true } ?: false
    }

    suspend fun closeAndJoin(budgetMillis: Long = 45_000): Boolean {
        require(budgetMillis in 1..45_000)
        val tasks = synchronized(monitor) { closed = true; active.values.toList() }
        tasks.forEach { it.cancel(CancellationException("Meow attachment detached")) }
        val joined = withTimeoutOrNull(budgetMillis) { tasks.joinAll(); true } ?: false
        if (joined) spills.close()
        return joined
    }

    private suspend fun execute(call: Call): ByteArray {
        var result: Any? = null
        var delivered = false
        try {
            val handler = handlers[call.method] ?: throw MeowProtocolFailure(
                if (call.method == "message") "bad_method" else "implementation_missing",
                if (call.method == "message") "Events cannot be invoked as requests" else "Meow method is not connected: ${call.method}",
                mapOf("method" to call.method),
            )
            result = handler.invoke(call.arguments)
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("Meow attachment detached")
            val encoded = response(call.id, result, null)
            currentCoroutineContext().ensureActive()
            delivered = true
            return encoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val error = when (failure) {
                is MeowProtocolFailure -> failure
                is MeowConfigProtocolException -> MeowProtocolFailure(failure.code,
                    failure.message ?: "Configuration request failed", failure.details, failure)
                else -> translateFailure(failure) ?: MeowProtocolFailure("method_failed",
                    "Meow method failed", mapOf("method" to call.method, "cause" to failure.javaClass.simpleName), failure)
            }
            return response(call.id, null, error)
        } finally {
            if (!delivered && result != null) discardResult(call.method, result)
        }
    }

    private suspend fun response(id: String?, result: Any?, error: MeowProtocolFailure?): ByteArray {
        val row = JSONObject().put("result", value(result))
        if (id != null) row.put("id", id)
        if (error != null) row.put("error", JSONObject().put("code", error.code)
            .put("message", error.message).put("details", value(error.details)))
        val bytes = row.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size < 51_200) return bytes
        val file = spills.write(bytes.toString(Charsets.UTF_8), "json")
        try {
            currentCoroutineContext().ensureActive()
            val envelope = JSONObject().put("result", JSONObject.NULL).put("file", file)
            if (id != null) envelope.put("id", id)
            return envelope.toString().toByteArray(Charsets.UTF_8)
        } catch (failure: Throwable) { spills.discard(file); throw failure }
    }

    private data class Call(val id: String, val method: String, val arguments: Any?)
    private class InvalidCall(val id: String?, val problem: MeowProtocolFailure) : Exception()

    private fun decode(encoded: String): Call {
        if (encoded.toByteArray(Charsets.UTF_8).size > 4 * 1024 * 1024)
            throw InvalidCall(null, MeowProtocolFailure("bad_request", "Request exceeds the byte budget"))
        val row = try { JSONObject(encoded) }
        catch (failure: Exception) { throw InvalidCall(null, MeowProtocolFailure("bad_request", "Request must be a JSON object")) }
        val id = row.opt("id")
        val method = row.opt("method")
        if (id !is String || id.isBlank() || id.length > 256)
            throw InvalidCall(null, MeowProtocolFailure("bad_request", "Request id is invalid"))
        if (method !is String || method !in METHODS)
            throw InvalidCall(id, MeowProtocolFailure("bad_method", "Request method is invalid"))
        return Call(id, method, unwrap(row.opt("arguments")))
    }

    private fun value(input: Any?): Any = when (input) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean -> input
        is Number -> {
            require(input !is Double || input.isFinite()) { "Non-finite response number" }
            require(input !is Float || input.isFinite()) { "Non-finite response number" }
            input
        }
        is Map<*, *> -> JSONObject().also { row -> input.forEach { (key, item) ->
            require(key is String) { "Response object keys must be strings" }; row.put(key, value(item))
        } }
        is Iterable<*> -> JSONArray().also { array -> input.forEach { array.put(value(it)) } }
        else -> throw MeowProtocolFailure("invalid_result", "Method returned an unsupported value")
    }

    private fun unwrap(input: Any?): Any? = when (input) {
        null, JSONObject.NULL -> null
        is JSONObject -> input.keys().asSequence().associateWith { unwrap(input.get(it)) }
        is JSONArray -> List(input.length()) { unwrap(input.get(it)) }
        else -> input
    }

    companion object {
        val METHODS: Set<String> = setOf(
            "message", "initClash", "getIsInit", "forceGc", "shutdown", "validateConfig", "decryptConfig",
            "validateProxies", "validateFilters", "convertProxies", "encodeShareLinks", "decodeShareLinks",
            "updateConfig", "getConfig", "getProfileBase", "getProfileOutbounds", "getProxyDefinition",
            "buildProfile", "dumpRuleSet", "compileRuleSet", "getProxies", "changeProxy", "getTraffic",
            "getTotalTraffic", "resetTraffic", "asyncTestDelay", "getLatestDelays", "probe", "outboundIp",
            "serviceCheck", "getMemoryStats", "getConnections", "getConnectionCount", "closeConnection",
            "closeConnections", "resetConnections", "clearDnsCache", "startLog", "stopLog", "setUiTelemetryActive",
            "getExternalProviders", "getExternalProvider", "updateGeoData", "sideLoadExternalProvider",
            "updateExternalProvider", "setupConfig", "getAppliedSetup", "refreshAppliedSetup",
            "startListener", "stopListener", "crash", "updateDns", "watchRoute", "checkRoutes", "clearEffect",
        )
    }
}
