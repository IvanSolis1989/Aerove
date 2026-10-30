package dev.aerove.lite.flutter

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.utf8Size
import org.json.JSONArray
import org.json.JSONObject

fun interface MeowProtocolMethod {
    suspend fun invoke(arguments: Any?): Any?
}

class MeowProtocolFailure(val code: String, message: String,
    val details: Map<String, Any?> = emptyMap(), cause: Throwable? = null) : Exception(message, cause)

class MeowStringResponseBudget(val maxTextBytes: Int) {
    internal val encodedBytes = Math.addExact(Math.multiplyExact(maxTextBytes, 6), 2048)
    init { require(maxTextBytes > 0) }
}

class MeowProtocolDispatcher(
    homeDir: File,
    handlers: Map<String, MeowProtocolMethod>,
    private val discardResult: (method: String, result: Any?) -> Unit = { _, _ -> },
    private val translateFailure: (Throwable) -> MeowProtocolFailure? = { null },
    stringResponseBudgets: Map<String, MeowStringResponseBudget> = emptyMap(),
) {
    private val handlers = handlers.toMap()
    private val spills = MeowFlutterSpill(homeDir)
    private class StringResponse(home: File, val budget: MeowStringResponseBudget) {
        val gate = Mutex()
        val spills = MeowFlutterSpill(home, MeowSpillBudget(budget.encodedBytes, budget.encodedBytes.toLong()))
    }
    private val stringResponses = stringResponseBudgets.mapValues { (_, budget) -> StringResponse(homeDir, budget) }
    private class ResponseFiles {
        private val monitor = Any()
        private val owned = mutableListOf<Pair<MeowFlutterSpill, String>>()
        private var delivered = false
        private var cancelled = false
        fun created(owner: MeowFlutterSpill, path: String) = synchronized(monitor) { owned.add(owner to path); Unit }
        fun cancel() = synchronized(monitor) { if (!delivered) cancelled = true }
        fun deliver(): Boolean = synchronized(monitor) { if (cancelled) false else { delivered = true; true } }
        fun discardUndelivered() {
            synchronized(monitor) {
                if (delivered) return
                var failure: Throwable? = null
                owned.toList().forEach { file ->
                    try { file.first.discard(file.second); owned.remove(file) }
                    catch (cleanup: Throwable) { if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup) }
                }
                failure?.let { throw it }
            }
        }
    }
    private class Request(val task: Deferred<ByteArray>, val files: ResponseFiles)
    private val monitor = Any()
    private val active = mutableMapOf<String, Request>()
    @Volatile private var closed = false

    init {
        require(handlers.keys.all { it in METHODS && it != "message" }) { "Unknown Meow method binding" }
        require(stringResponses.keys.all { it in handlers }) { "String response budget requires a bound method" }
    }

    val implementedMethods: Set<String> get() = handlers.keys.toSet()
    val missingMethods: Set<String> get() = METHODS - handlers.keys - "message"

    suspend fun invoke(encoded: String): ByteArray {
        val call = try { decode(encoded) }
        catch (failure: InvalidCall) { return response(failure.id, null, failure.problem) }
        return coroutineScope {
            val files = ResponseFiles()
            val task = async(start = CoroutineStart.LAZY) { execute(call, files) }
            val request = Request(task, files)
            val rejection = synchronized(monitor) {
                when {
                    closed -> MeowProtocolFailure("closed", "Meow protocol attachment is closed")
                    active.containsKey(call.id) -> MeowProtocolFailure("duplicate_call", "Request id is already active")
                    else -> { active[call.id] = request; null }
                }
            }
            if (rejection != null) {
                task.cancel()
                return@coroutineScope response(call.id, null, rejection)
            }
            task.start()
            var handedOff = false
            try {
                val encoded = task.await()
                currentCoroutineContext().ensureActive()
                if (!files.deliver()) return@coroutineScope response(call.id, null,
                    MeowProtocolFailure("cancelled", "Meow request was cancelled"))
                handedOff = true
                encoded
            }
            catch (failure: CancellationException) {
                currentCoroutineContext().ensureActive()
                response(call.id, null, MeowProtocolFailure("cancelled", "Meow request was cancelled"))
            } finally {
                try {
                    if (!handedOff) withContext(NonCancellable + Dispatchers.IO) { task.join(); files.discardUndelivered() }
                } finally { synchronized(monitor) { if (active[call.id] === request) active.remove(call.id) } }
            }
        }
    }

    suspend fun cancel(id: String, budgetMillis: Long = 45_000): Boolean {
        require(budgetMillis in 1..45_000)
        val request = synchronized(monitor) { active[id] } ?: return true
        request.files.cancel()
        request.task.cancel(CancellationException("Meow request cancellation"))
        return withTimeoutOrNull(budgetMillis) {
            request.task.join()
            withContext(Dispatchers.IO) { request.files.discardUndelivered() }
            true
        } ?: false
    }

    suspend fun closeAndJoin(budgetMillis: Long = 45_000): Boolean {
        require(budgetMillis in 1..45_000)
        val tasks = synchronized(monitor) { closed = true; active.values.toList() }
        tasks.forEach { it.files.cancel(); it.task.cancel(CancellationException("Meow attachment detached")) }
        val joined = withTimeoutOrNull(budgetMillis) { tasks.map { it.task }.joinAll(); true } ?: false
        if (joined) {
            tasks.forEach { it.files.discardUndelivered() }
            spills.close(); stringResponses.values.forEach { it.spills.close() }
        }
        return joined
    }

    private suspend fun execute(call: Call, files: ResponseFiles): ByteArray = stringResponses[call.method]?.gate?.withLock {
        executeOwned(call, files)
    } ?: executeOwned(call, files)

    private suspend fun executeOwned(call: Call, files: ResponseFiles): ByteArray {
        var result: Any? = null
        var delivered = false
        var responseCompleted = false
        try {
            val handler = handlers[call.method] ?: throw MeowProtocolFailure(
                if (call.method == "message") "bad_method" else "implementation_missing",
                if (call.method == "message") "Events cannot be invoked as requests" else "Meow method is not connected: ${call.method}",
                mapOf("method" to call.method),
            )
            result = handler.invoke(call.arguments)
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("Meow attachment detached")
            val encoded = response(call.id, result, null, call.method, files)
            currentCoroutineContext().ensureActive()
            responseCompleted = true
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
            return response(call.id, null, error, files = files).also { responseCompleted = true }
        } finally {
            try {
                if (!responseCompleted) withContext(NonCancellable + Dispatchers.IO) { files.discardUndelivered() }
            } finally { if (!delivered && result != null) discardResult(call.method, result) }
        }
    }

    private suspend fun response(id: String?, result: Any?, error: MeowProtocolFailure?, method: String? = null,
        files: ResponseFiles? = null): ByteArray {
        val stringResponse = if (error == null) stringResponses[method] else null
        if (stringResponse != null) {
            if (result !is String || id == null) throw MeowProtocolFailure("invalid_result", "Expected a string response with its request id")
            if (result.utf8Size() > stringResponse.budget.maxTextBytes)
                throw MeowProtocolFailure("output_too_large", "Response exceeds the method's UTF-8 text budget")
            // Only small strings reach JSONObject; large escaping writes directly into the owned spill.
            if (result.length >= 51_200) return spilledStringResponse(stringResponse, id, result, checkNotNull(files))
        }
        val row = JSONObject().put("result", value(result))
        if (id != null) row.put("id", id)
        if (error != null) row.put("error", JSONObject().put("code", error.code)
            .put("message", error.message).put("details", value(error.details)))
        val bytes = row.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size < 51_200) return bytes
        if (stringResponse != null) return spilledStringResponse(stringResponse, checkNotNull(id), result as String, checkNotNull(files))
        val file = spills.write(bytes.toString(Charsets.UTF_8), "json")
        files?.created(spills, file)
        try {
            currentCoroutineContext().ensureActive()
            val envelope = JSONObject().put("result", JSONObject.NULL).put("file", file)
            if (id != null) envelope.put("id", id)
            return envelope.toString().toByteArray(Charsets.UTF_8)
        } catch (failure: Throwable) { spills.discard(file); throw failure }
    }

    private suspend fun spilledStringResponse(policy: StringResponse, id: String, result: String,
        files: ResponseFiles): ByteArray = withContext(Dispatchers.IO) {
        val file = policy.spills.writeJsonStringResult(id, result)
        files.created(policy.spills, file)
        try {
            currentCoroutineContext().ensureActive()
            JSONObject().put("result", JSONObject.NULL).put("file", file).put("id", id).toString().toByteArray(Charsets.UTF_8)
        } catch (failure: Throwable) { policy.spills.discard(file); throw failure }
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
