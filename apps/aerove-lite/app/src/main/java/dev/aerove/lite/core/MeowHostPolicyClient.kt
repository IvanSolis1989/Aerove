package dev.aerove.lite.core

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.MeowPhysicalNetwork
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class MeowControllerTarget(val enabled: Boolean, val secret: String) {
    init {
        require(!enabled || (secret == secret.trim() && secret.isNotEmpty() && secret.length <= 512 &&
            secret.toByteArray(Charsets.UTF_8).all { (it.toInt() and 255).let { byte -> byte == 9 || byte >= 32 && byte != 127 } })) {
            "User controller requires a persisted canonical Bearer secret"
        }
    }
    override fun toString() = "MeowControllerTarget(enabled=$enabled, secret=<redacted>)"
}
data class MeowHostPolicyTarget(val http: MeowHttpPolicy, val probe: MeowProbeDialPolicy,
    val mode: String, val onlyProxy: Boolean, val controller: MeowControllerTarget) {
    init { require(mode in setOf("strict", "always", "off")) { "Invalid Meow process mode" } }
    companion object {
        fun from(settings: LiteSettings, defaultAgent: String) = MeowHostPolicyTarget(
            MeowHttpPolicy(settings.requests.globalUserAgent ?: defaultAgent, settings.requests.checkCertificate),
            MeowProbeDialPolicy(settings.unifiedDelay, settings.tcpConcurrent), settings.findProcessMode.wireName,
            settings.backgroundPolicy.onlyStatisticsProxy,
            MeowControllerTarget(settings.externalController.enabled, if (settings.externalController.enabled) settings.externalController.secret else ""))
    }
}
data class MeowPhysicalPolicy(val handle: String?, val dnsServers: List<String>, val intent: String)
data class MeowControllerPolicy(val enabled: Boolean, val address: String?, val authenticated: Boolean,
    val privateRoutesExcluded: Boolean, val coreEpoch: String)
data class MeowCompletePolicySnapshot(val coreEpoch: String, val http: MeowHttpPolicy, val probe: MeowProbeDialPolicy,
    val mode: String, val physical: MeowPhysicalPolicy, val controller: MeowControllerPolicy, val onlyProxy: Boolean)

/** Exact full-policy-contract-01 codec; no speculative operations or receipt success defaults. */
object MeowHostPolicyCodec {
    internal fun JSONObject.exact(vararg names: String) {
        require(keys().asSequence().toSet() == names.toSet()) { "Invalid Meow host policy fields" }
    }
    internal fun JSONObject.text(key: String) = get(key) as? String ?: error("Invalid Meow host policy string: $key")
    internal fun JSONObject.flag(key: String) = get(key) as? Boolean ?: error("Invalid Meow host policy flag: $key")
    internal fun mode(value: String) = value.also { require(it in setOf("strict", "always", "off")) { "Invalid process readback" } }
    internal fun intent(value: String) = value.also { require(it.toULongOrNull()?.toString() == it) { "Invalid network intent" } }
    internal fun servers(array: JSONArray): List<String> = List(array.length()) { index ->
        val value = array.get(index) as? String ?: error("Invalid physical DNS endpoint")
        val literal = if (value.startsWith("[")) {
            require(value.endsWith("]:53")) { "Invalid physical DNS port" }; value.substring(1, value.length - 4)
        } else {
            require(value.count { it == ':' } == 1 && value.endsWith(":53")) { "Invalid physical DNS port" }; value.dropLast(3)
        }
        val split = literal.split('%')
        require(split.size <= 2 && (split.size == 1 || (':' in split[0] && split[1].toUIntOrNull()?.let { it > 0u && it.toString() == split[1] } == true))) { "Invalid physical DNS scope" }
        require(split[0].isNotEmpty() && split[0].all { it in "0123456789abcdefABCDEF:." }) { "Physical DNS must be an IP literal" }
        if (':' !in split[0]) require(split[0].split('.').let { it.size == 4 && it.all { part -> part.toIntOrNull()?.let { byte -> byte in 0..255 } == true } })
        val ip = InetAddress.getByName(split[0])
        require(!ip.isAnyLocalAddress && !ip.isMulticastAddress) { "Invalid physical DNS address" }
        value
    }
    private fun serverIdentity(endpoint: String): Triple<Boolean, List<Byte>, UInt> {
        val literal = if (endpoint.startsWith("[")) endpoint.substring(1, endpoint.length - 4) else endpoint.dropLast(3)
        val parts = literal.split('%')
        return Triple(':' in parts[0], InetAddress.getByName(parts[0]).address.toList(), parts.getOrNull(1)?.toUInt() ?: 0u)
    }
    internal fun matchesSelectedDns(target: MeowPhysicalNetwork, actual: List<String>): Boolean {
        target.dns.config()
        val selected = checkNotNull(target.dns.defaultInterface) { "Physical DNS unavailable" }
        val addresses = checkNotNull(target.dns.interfaces[selected]) { "Physical DNS unavailable" }
        check(addresses.isNotEmpty()) { "Physical DNS unavailable" }
        // Match Native physical_dns::selected_servers: preserve order and resolve only a real interface identity.
        val expected = addresses.map { address ->
            val parts = address.split('%'); val literal = parts[0]; val ip = InetAddress.getByName(literal)
            if (':' !in literal) "$literal:53" else {
                val zone = parts.getOrNull(1)
                val scope = when {
                    zone != null && zone.all { it in '0'..'9' } -> zone.toUInt()
                    zone != null || ip.isLinkLocalAddress -> {
                        check(zone == null || zone == selected) { "Invalid physical DNS interface scope" }
                        val index = NetworkInterface.getByName(selected)?.index
                        check(index != null && index > 0) { "Physical DNS interface scope unavailable" }; index.toUInt()
                    }
                    else -> 0u
                }
                "[$literal${if (scope == 0u) "" else "%$scope"}]:53"
            }
        }
        return expected.map(::serverIdentity) == actual.map(::serverIdentity)
    }
    internal fun http(value: MeowHttpPolicy) = JSONObject().put("globalUserAgent", value.globalUserAgent).put("checkCertificate", value.checkCertificate)
    internal fun probe(value: MeowProbeDialPolicy) = JSONObject().put("unifiedDelay", value.unifiedDelay).put("tcpConcurrent", value.tcpConcurrent)
    internal fun probe(row: JSONObject): MeowProbeDialPolicy {
        row.exact("unifiedDelay", "tcpConcurrent"); return MeowProbeDialPolicy(row.flag("unifiedDelay"), row.flag("tcpConcurrent"))
    }
    internal fun controller(row: JSONObject, epoch: String): MeowControllerPolicy {
        row.exact("enabled", "address", "authenticated", "privateRoutesExcluded", "coreEpoch")
        val value = MeowControllerPolicy(row.flag("enabled"), if (row.isNull("address")) null else row.text("address"),
            row.flag("authenticated"), row.flag("privateRoutesExcluded"), MeowHttpPolicyCodec.epoch(row.text("coreEpoch")))
        check(value.coreEpoch == epoch && value.privateRoutesExcluded && value.authenticated == value.enabled &&
            value.address == if (value.enabled) "127.0.0.1:9090" else null) { "User controller readback mismatch" }
        return value
    }
    internal fun request(id: String, epoch: String, operation: String, value: JSONObject): String {
        require(operation in setOf("snapshot", "http", "probeDial", "process", "invalidateIdentity", "statistics", "userController", "network"))
        return JSONObject().put("schemaVersion", 1).put("requestId", MeowHttpPolicyCodec.requestId(id))
            .put("expectedCoreEpoch", MeowHttpPolicyCodec.epoch(epoch)).put("operation", operation).put("value", value).toString()
    }
    internal fun response(json: String, id: String, operation: String): Pair<String, JSONObject> {
        val row = JSONObject(json); row.exact("requestId", "coreEpoch", "operation", "value")
        require(MeowHttpPolicyCodec.requestId(row.text("requestId")) == id && row.text("operation") == operation) { "Host policy receipt mismatch" }
        return MeowHttpPolicyCodec.epoch(row.text("coreEpoch")) to row.getJSONObject("value")
    }
    fun snapshot(epoch: String, row: JSONObject): MeowCompletePolicySnapshot {
        MeowHttpPolicyCodec.epoch(epoch)
        row.exact("http", "probeDial", "findProcessMode", "physicalNetwork", "userController", "statistics", "privateCorsOrigins")
        require(row.getJSONArray("privateCorsOrigins").length() == 0) { "Private API CORS must be disabled" }
        val physical = row.getJSONObject("physicalNetwork"); physical.exact("handle", "dnsServers", "intent")
        val handle = if (physical.isNull("handle")) null else MeowHttpPolicyCodec.epoch(physical.text("handle"))
        val statistics = row.getJSONObject("statistics"); statistics.exact("onlyStatisticsProxy")
        val http = MeowHttpPolicyCodec.status(JSONObject().put("coreEpoch", epoch).put("http", row.getJSONObject("http")).toString()).http
        return MeowCompletePolicySnapshot(epoch, http, probe(row.getJSONObject("probeDial")), mode(row.text("findProcessMode")),
            MeowPhysicalPolicy(handle, servers(physical.getJSONArray("dnsServers")), intent(physical.text("intent"))),
            controller(row.getJSONObject("userController"), epoch), statistics.flag("onlyStatisticsProxy"))
    }
}

/** Composed by the existing Session, not a second policy owner or background task. */
internal class MeowHostPolicyClient(private val handle: Long, private val gate: Mutex,
    private val readEpoch: () -> String?, private val acceptEpoch: (String) -> Unit, private val commands: Commands) {
    private var confirmedController: MeowControllerTarget? = null
    interface Commands { fun epoch(): String; fun command(json: String): String }
    private fun accept(value: String) {
        val actual = MeowHttpPolicyCodec.epoch(value).toULong()
        check(readEpoch()?.let { actual >= MeowHttpPolicyCodec.epoch(it).toULong() } != false) { "Stale host policy epoch" }
        acceptEpoch(value)
    }
    private fun command(epoch: String, op: String, value: JSONObject): Pair<String, JSONObject> {
        val id = UUID.randomUUID().toString()
        return MeowHostPolicyCodec.response(commands.command(MeowHostPolicyCodec.request(id, epoch, op, value)), id, op)
            .also { check(it.first.toULong() >= epoch.toULong()) { "Host policy epoch moved backwards" } }
    }
    private fun snapshot(epoch: String): MeowCompletePolicySnapshot = command(epoch, "snapshot", JSONObject()).let {
        check(it.first == epoch) { "Incoherent host policy snapshot" }; MeowHostPolicyCodec.snapshot(it.first, it.second).also { value -> accept(value.coreEpoch) }
    }
    private suspend fun <T> transaction(currentIntent: () -> Boolean, work: suspend (() -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        fun current() { caller.ensureActive(); if (!currentIntent()) throw CancellationException("Meow host policy superseded") }
        current()
        return gate.withLock {
            current()
            val actual = withContext(NonCancellable + Dispatchers.IO) { current(); work(::current) }
            current(); actual
        }
    }
    private fun initial(): MeowCompletePolicySnapshot {
        val epoch = MeowHttpPolicyCodec.epoch(commands.epoch()); accept(epoch); return snapshot(epoch)
    }
    suspend fun read(current: () -> Boolean): MeowCompletePolicySnapshot = transaction(current) { check -> initial().also { check() } }
    suspend fun apply(target: MeowHostPolicyTarget, current: () -> Boolean): MeowCompletePolicySnapshot = transaction(current) { live ->
        var before = initial(); live()
        fun change(op: String, value: JSONObject, knownChanged: Boolean = true, verify: (JSONObject, String) -> Unit) {
            val receipt = command(before.coreEpoch, op, value); verify(receipt.second, receipt.first)
            check(!knownChanged || receipt.first.toULong() > before.coreEpoch.toULong()) { "Changed policy did not advance epoch" }
            accept(receipt.first); live()
            before = snapshot(receipt.first); live()
        }
        if (before.http != target.http) change("http", MeowHostPolicyCodec.http(target.http)) { row, epoch ->
            check(MeowHttpPolicyCodec.status(JSONObject().put("coreEpoch", epoch).put("http", row).toString()).http == target.http) }
        if (before.probe != target.probe) change("probeDial", MeowHostPolicyCodec.probe(target.probe)) { row, _ -> check(MeowHostPolicyCodec.probe(row) == target.probe) }
        if (before.mode != target.mode) change("process", JSONObject().put("findProcessMode", target.mode)) { row, _ ->
            with(MeowHostPolicyCodec) { row.exact("findProcessMode"); check(mode(row.text("findProcessMode")) == target.mode) } }
        if (before.onlyProxy != target.onlyProxy) change("statistics", JSONObject().put("onlyStatisticsProxy", target.onlyProxy)) { row, _ ->
            with(MeowHostPolicyCodec) { row.exact("onlyStatisticsProxy"); check(row.flag("onlyStatisticsProxy") == target.onlyProxy) } }
        // Cache only a fully confirmed secret target; native never returns secret material.
        if (confirmedController != target.controller || before.controller.enabled != target.controller.enabled) {
            change("userController", JSONObject().put("enabled", target.controller.enabled).put("secret", target.controller.secret), false) { row, epoch ->
                check(MeowHostPolicyCodec.controller(row, epoch).enabled == target.controller.enabled) }
            confirmedController = target.controller
        }
        check(before.http == target.http && before.probe == target.probe && before.mode == target.mode &&
            before.onlyProxy == target.onlyProxy && before.controller.enabled == target.controller.enabled) { "Host policies did not converge" }
        before
    }
    suspend fun invalidate(current: () -> Boolean): MeowCompletePolicySnapshot = transaction(current) { live ->
        val before = initial(); live(); val actual = command(before.coreEpoch, "invalidateIdentity", JSONObject())
        with(MeowHostPolicyCodec) { actual.second.exact("findProcessMode", "invalidated");
            check(mode(actual.second.text("findProcessMode")) == before.mode && actual.second.flag("invalidated") && actual.first.toULong() > before.coreEpoch.toULong()) }
        accept(actual.first); live(); snapshot(actual.first).also { live() }
    }
    suspend fun network(target: MeowPhysicalNetwork, current: () -> Boolean, commit: suspend () -> Unit): MeowCompletePolicySnapshot = transaction(current) { live ->
        val handle = MeowHttpPolicyCodec.epoch(checkNotNull(target.handle) { "No captured physical network" })
        val before = initial(); live(); val previous = before.physical.intent.toULong()
        check(previous < ULong.MAX_VALUE) { "Physical network intent exhausted" }; val intent = (previous + 1u).toString()
        val receipt = command(before.coreEpoch, "network", JSONObject().put("intent", intent).put("physicalNetwork", target.json()))
        val servers = with(MeowHostPolicyCodec) {
            receipt.second.exact("intent", "handle", "dnsServers", "resolverReadback", "networkReadback", "retiredDnsJoined")
            check(receipt.second.text("intent") == intent && receipt.second.text("handle") == handle &&
                receipt.second.flag("resolverReadback") && receipt.second.flag("networkReadback") && receipt.second.flag("retiredDnsJoined")) { "Physical network cleanup/readback unconfirmed" }
            servers(receipt.second.getJSONArray("dnsServers")).also {
                check(it.isNotEmpty() && matchesSelectedDns(target, it)) { "Physical DNS differs from captured target" }
            }
        }
        accept(receipt.first)
        val actual = snapshot(receipt.first)
        check(actual.physical.handle == handle && actual.physical.intent == intent && actual.physical.dnsServers == servers) { "Physical network did not converge" }
        // Keep Host and Native paired after an admitted transaction, even if its waiter was cancelled.
        commit()
        live()
        actual
    }
}
