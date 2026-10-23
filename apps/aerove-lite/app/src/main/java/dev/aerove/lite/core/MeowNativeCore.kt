package dev.aerove.lite.core

import org.json.JSONObject

object MeowNativeCore {
    init { System.loadLibrary("aerove_meow_core") }

    // Caller retains tunFd; native duplicates it. -1 is required for Proxy and initially parked Tun.
    @JvmStatic external fun startOwned(planJson: String, tunFd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?): Long
    @JvmStatic external fun status(handle: Long): String
    @JvmStatic external fun listenerStatus(handle: Long): String

    // Blocking: native returns only after actual readiness/error, and stop after cancellation + join.
    // The lifecycle owner must stop a successfully allocated handle even if its coroutine is cancelled.
    @JvmStatic external fun awaitReady(handle: Long)
    @JvmStatic external fun awaitExit(handle: Long)
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun listenerCommand(handle: Long, action: String, tunFd: Int): String
    @JvmStatic external fun capabilities(): String
    @JvmStatic external fun configurePolicies(handle: Long, requestJson: String): String
    @JvmStatic external fun policyStatus(handle: Long): String

    // Offline conversion only: null means valid; diagnostics preserve native parser errors.
    @JvmStatic external fun validate(config: String): String?
    @JvmStatic external fun validateResource(kind: String, path: String, behavior: String, format: String): String?
    @JvmStatic external fun inspectRuleSet(path: String): String
    @JvmStatic external fun dumpRuleSet(path: String, behavior: String, format: String): String

    fun readCapabilities() = MeowCapabilitiesCodec.decode(capabilities())
    fun readVerifiedFeatures(): Set<String> = readCapabilities().verifiedFeatures

    fun park(handle: Long) = MeowStatusCodec.listeners(listenerCommand(handle, "park", -1))
    fun resumeProxy(handle: Long) = MeowStatusCodec.listeners(listenerCommand(handle, "resumeProxy", -1))
    fun attachTun(handle: Long, fd: Int): MeowListenerStatus {
        require(fd >= 0) { "Invalid Meow TUN descriptor" }
        return MeowStatusCodec.listeners(listenerCommand(handle, "attachTun", fd))
    }

    fun readStatus(handle: Long) = MeowStatusCodec.status(status(handle))
    fun readListenerStatus(handle: Long) = MeowStatusCodec.listeners(listenerStatus(handle))
}

class MeowCapabilitySource internal constructor(val repository: String, val commit: String, val tag: String,
    val rawFileManifestSha256: String, val adaptedFileManifestSha256: String, val overlaySha256: String)

/** Fixed offline factory proof only; never a protocol handshake or Android transport assertion. */
class MeowCapabilities internal constructor(val source: MeowCapabilitySource, compiled: List<String>, verified: List<String>) {
    val compiledFeatures: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(compiled))
    val verifiedFeatures: Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(verified))
    val wireVerified = false
    val verification = "fixedOfflineFactory"
}

object MeowCapabilitiesCodec {
    private val features = setOf("ss", "trojan", "vless", "vless-vision", "vless-encryption", "ech-tls-tunnel",
        "vmess", "snell", "hysteria2", "mux", "kcptun", "anytls", "trusttunnel", "trusttunnel-h3")
    private fun JSONObject.exact(fields: Set<String>) {
        require(keys().asSequence().toSet() == fields) { "Invalid Meow capability fields" }
    }
    private fun JSONObject.text(key: String) = (get(key) as? String) ?: error("Invalid Meow capability string: $key")
    private fun JSONObject.identifiers(key: String): List<String> {
        val array = getJSONArray(key)
        val values = List(array.length()) { index -> (array.get(index) as? String) ?: error("Invalid Meow capability identifier") }
        require(values.distinct().size == values.size) { "Duplicate Meow capability identifier" }
        return values
    }
    fun decode(json: String): MeowCapabilities {
        val row = JSONObject(json)
        row.exact(setOf("schemaVersion", "source", "compiledFeatures", "verifiedFeatures", "builtInProtocols", "verification", "wireVerified"))
        require(row.get("schemaVersion") is Int && row.get("schemaVersion") == 1) { "Invalid Meow capability schema" }
        require(row.text("verification") == "fixedOfflineFactory" && row.get("wireVerified") == false) { "Invalid Meow capability verification scope" }
        val raw = row.getJSONObject("source")
        raw.exact(setOf("repository", "commit", "tag", "rawFileManifestSha256", "adaptedFileManifestSha256", "overlaySha256"))
        fun hash(key: String) = raw.text(key).also { require(it.matches(Regex("[0-9a-f]{64}"))) { "Invalid Meow source hash: $key" } }
        val source = MeowCapabilitySource(raw.text("repository"), raw.text("commit"), raw.text("tag"),
            hash("rawFileManifestSha256"), hash("adaptedFileManifestSha256"), hash("overlaySha256"))
        require(source.repository == "https://github.com/meow-rs/meow-rs" && source.commit == "727ddcd06f592e2f12c5222c4de6cce328df808c" &&
            source.tag == "v0.26.1" && source.rawFileManifestSha256 == "a1948157edb77700f6f9152f3467907fb6a050c66cd153edfe98295bbf56e550") { "Meow capability source differs from fixed core" }
        val compiled = row.identifiers("compiledFeatures")
        val verified = row.identifiers("verifiedFeatures")
        require(compiled.all { it in features } && compiled == verified) { "Meow compiled/factory-verified feature mismatch" }
        require(row.identifiers("builtInProtocols") == listOf("direct", "http", "socks5")) { "Invalid Meow builtin factory proof" }
        mapOf("vless-vision" to setOf("vless"), "vless-encryption" to setOf("vless"),
            "ech-tls-tunnel" to setOf("ss"), "mux" to setOf("ss"), "kcptun" to setOf("ss", "mux"),
            "trusttunnel-h3" to setOf("trusttunnel")).forEach { (feature, required) ->
            require(feature !in compiled || compiled.containsAll(required)) { "Meow feature dependency mismatch: $feature" }
        }
        return MeowCapabilities(source, compiled, verified)
    }
}

enum class MeowState { Starting, Running, Failed, Exited }
data class MeowStatus(val state: MeowState, val error: String?, val apiAddress: String?,
    val runtimeMode: NativeCore.RuntimeMode, val tunReady: Boolean, val userListenersReady: Boolean,
    val listening: Boolean, val coreReady: Boolean)
data class MeowListenerStatus(val listening: Boolean, val tunReady: Boolean, val userListenersReady: Boolean,
    val activePorts: List<Int>, val coreEpoch: String, val onlyStatisticsProxy: Boolean,
    val unifiedDelay: Boolean, val tcpConcurrent: Boolean, val findProcessMode: String)

object MeowStatusCodec {
    private fun JSONObject.exact(keys: Set<String>) { require(keys().asSequence().toSet() == keys) { "Invalid Meow status fields" } }
    private fun JSONObject.bool(key: String) = (get(key) as? Boolean) ?: error("Invalid Meow boolean: $key")
    private fun JSONObject.text(key: String) = (get(key) as? String) ?: error("Invalid Meow string: $key")
    private fun JSONObject.nullable(key: String): String? = if (get(key) === JSONObject.NULL) null else text(key)
    fun status(json: String): MeowStatus {
        val row = JSONObject(json)
        row.exact(setOf("state", "error", "apiAddress", "runtimeMode", "tunReady", "userListenersReady", "listening", "coreReady"))
        val mode = when (row.text("runtimeMode")) { "tun" -> NativeCore.RuntimeMode.Tun; "proxy" -> NativeCore.RuntimeMode.Proxy; else -> error("Invalid Meow runtime mode") }
        return MeowStatus(MeowState.valueOf(row.text("state")), row.nullable("error"), row.nullable("apiAddress"), mode,
            row.bool("tunReady"), row.bool("userListenersReady"), row.bool("listening"), row.bool("coreReady"))
    }
    fun listeners(json: String): MeowListenerStatus {
        val row = JSONObject(json)
        row.exact(setOf("listening", "tunReady", "userListenersReady", "activePorts", "coreEpoch", "onlyStatisticsProxy", "unifiedDelay", "tcpConcurrent", "findProcessMode"))
        val epoch = row.text("coreEpoch")
        require(epoch.toULongOrNull()?.toString() == epoch) { "Invalid Meow epoch" }
        val array = row.getJSONArray("activePorts")
        val ports = List(array.length()) { index ->
            val value = array.get(index)
            require(value is Int && value in 1..65535) { "Invalid Meow listener port" }
            value
        }
        require(ports.distinct().size == ports.size) { "Duplicate Meow listener ports" }
        val mode = row.text("findProcessMode").also { require(it in setOf("strict", "always", "off")) { "Invalid Meow process mode" } }
        return MeowListenerStatus(row.bool("listening"), row.bool("tunReady"), row.bool("userListenersReady"), ports,
            epoch, row.bool("onlyStatisticsProxy"), row.bool("unifiedDelay"), row.bool("tcpConcurrent"), mode)
    }
}
