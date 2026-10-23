package dev.aerove.lite.core

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowPhysicalNetwork
import dev.aerove.lite.data.SystemDnsSnapshot
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Stateful actor protocol fixture, not a native/runtime or Android-network implementation. */
internal class MeowPolicyFixture : MeowHostPolicyClient.Commands {
    var revision = 1uL
    var agent = "AEROVE-Lite"; var certificate = true; var probe = MeowProbeDialPolicy(true, true)
    var mode = "always"; var onlyProxy = true; var enabled = false; private var secret = ""
    var networkHandle: String? = "42"; var networkIntent = "0"; var servers = emptyList<String>()
    var onOperation: (String) -> Unit = {}; var joinConfirmed = true; var failNetworkOnce = false
    var returnedServers: List<String>? = null
    val operations = mutableListOf<String>()
    override fun epoch() = revision.toString()
    fun startup(yaml: String) {
        val row = ConfigYaml.parse(yaml)
        agent = row["global-ua"] as String; certificate = row["check-certificate"] as Boolean
        probe = MeowProbeDialPolicy(row["unified-delay"] as Boolean, row["tcp-concurrent"] as Boolean)
        mode = row["find-process-mode"] as String; onlyProxy = row["only-statistics-proxy"] as Boolean
        val controller = row["user-controller"] as Map<*, *>; enabled = controller["enabled"] as Boolean; secret = controller["secret"] as String
    }
    private fun controller() = JSONObject().put("enabled", enabled).put("address", if(enabled) "127.0.0.1:9090" else JSONObject.NULL)
        .put("authenticated", enabled).put("privateRoutesExcluded", true).put("coreEpoch", epoch())
    fun snapshot() = JSONObject().put("http", MeowHostPolicyCodec.http(MeowHttpPolicy(agent, certificate)))
        .put("probeDial", MeowHostPolicyCodec.probe(probe)).put("findProcessMode", mode)
        .put("physicalNetwork", JSONObject().put("handle", networkHandle ?: JSONObject.NULL).put("intent", networkIntent).put("dnsServers", JSONArray(servers)))
        .put("userController", controller()).put("statistics", JSONObject().put("onlyStatisticsProxy", onlyProxy)).put("privateCorsOrigins", JSONArray())
    override fun command(json: String): String {
        val row = JSONObject(json); val op = row.getString("operation"); val value = row.getJSONObject("value")
        check(row.getString("expectedCoreEpoch") == epoch()) { "staleCoreEpoch" }; operations += op; onOperation(op)
        val actual = when(op) {
            "snapshot" -> snapshot()
            "http" -> { val nextAgent = value.getString("globalUserAgent"); val nextCertificate = value.getBoolean("checkCertificate")
                if (agent != nextAgent || certificate != nextCertificate) revision++; agent = nextAgent; certificate = nextCertificate; value }
            "probeDial" -> { val next = MeowProbeDialPolicy(value.getBoolean("unifiedDelay"), value.getBoolean("tcpConcurrent")); if(probe != next)revision++; probe = next; value }
            "process" -> { if(mode != value.getString("findProcessMode"))revision++; mode = value.getString("findProcessMode"); value }
            "statistics" -> { if(onlyProxy != value.getBoolean("onlyStatisticsProxy"))revision++; onlyProxy = value.getBoolean("onlyStatisticsProxy"); value }
            "userController" -> { val nextEnabled = value.getBoolean("enabled"); val nextSecret = value.getString("secret")
                if(enabled != nextEnabled || secret != nextSecret)revision++; enabled = nextEnabled; secret = nextSecret; controller() }
            "invalidateIdentity" -> { revision++; JSONObject().put("findProcessMode", mode).put("invalidated", true) }
            "network" -> {
                networkIntent = value.getString("intent")
                if(failNetworkOnce) { failNetworkOnce = false; error("resolverJoinUnconfirmed") }
                val network = value.getJSONObject("physicalNetwork"); networkHandle = network.getString("handle")
                val dns = network.getJSONObject("dns"); val addresses = dns.getJSONObject("interfaces").getJSONArray(dns.getString("defaultInterface"))
                servers = returnedServers ?: List(addresses.length()) { addresses.getString(it) + ":53" }; revision++
                JSONObject().put("intent", networkIntent).put("handle", networkHandle).put("dnsServers", JSONArray(servers))
                    .put("resolverReadback", true).put("networkReadback", true).put("retiredDnsJoined", joinConfirmed)
            }
            else -> error("unknownHostPolicyOperation")
        }
        return JSONObject().put("requestId", row.getString("requestId")).put("coreEpoch", epoch()).put("operation", op).put("value", actual).toString()
    }
}

class MeowHostPolicyClientTest {
    private fun network() = MeowPhysicalNetwork("43", SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf("8.8.8.8"))))
    private fun target() = MeowHostPolicyTarget(MeowHttpPolicy("AEROVE/new", false), MeowProbeDialPolicy(false, false),
        "strict", false, MeowControllerTarget(true, "persisted-fixture-secret"))
    @Test fun completeContractStrictlyReadsSevenFieldsAndRejectsUnsafeReceipts() {
        val native = MeowPolicyFixture(); val row = native.snapshot()
        assertEquals("0", MeowHostPolicyCodec.snapshot("1", row).physical.intent)
        for (key in listOf("http", "probeDial", "findProcessMode", "physicalNetwork", "userController", "statistics", "privateCorsOrigins")) {
            val missing = JSONObject(row.toString()); missing.remove(key)
            assertTrue(runCatching { MeowHostPolicyCodec.snapshot("1", missing) }.isFailure)
        }
        assertTrue(runCatching { MeowHostPolicyCodec.snapshot("1", JSONObject(row.toString()).put("future", false)) }.isFailure)
        assertTrue(runCatching { MeowHostPolicyCodec.snapshot("1", JSONObject(row.toString()).put("privateCorsOrigins", JSONArray().put("*"))) }.isFailure)
        assertTrue(runCatching { MeowControllerTarget(true, " not-canonical ") }.isFailure)
        assertTrue(runCatching { MeowControllerTarget(true, "bad\r\nsecret") }.isFailure)
        assertFalse(target().toString().contains("persisted-fixture-secret"))
    }
    @Test fun eightOperationsUseSharedActualEpochAndChangedTargetCache() = runBlocking {
        val native = MeowPolicyFixture(); var epoch: String? = null
        val client = MeowHostPolicyClient(7, Mutex(), { epoch }, { epoch = it }, native)
        assertEquals("1", client.read { true }.coreEpoch)
        val actual = client.apply(target()) { true }
        assertEquals("6", actual.coreEpoch); assertTrue(actual.controller.enabled)
        assertEquals(listOf("http", "probeDial", "process", "statistics", "userController"), native.operations.filter { it != "snapshot" })
        val count = native.operations.count { it != "snapshot" }; client.apply(target()) { true }
        assertEquals(count, native.operations.count { it != "snapshot" })
        assertEquals("7", client.invalidate { true }.coreEpoch)
        var committed = false
        assertEquals("8", client.network(network(), { true }) { committed = true }.coreEpoch)
        assertTrue(committed); assertEquals("8", epoch); assertEquals("1", native.networkIntent)
    }
    @Test fun failedNetworkIntentIsReadBackAndNoJoinAckNeverCommitsHost() = runBlocking {
        val native = MeowPolicyFixture(); var epoch: String? = null; var commits = 0
        val client = MeowHostPolicyClient(7, Mutex(), { epoch }, { epoch = it }, native)
        native.failNetworkOnce = true
        try { client.network(network(), { true }) { commits++ }; fail() } catch (error: IllegalStateException) { assertEquals("resolverJoinUnconfirmed", error.message) }
        assertEquals("1", native.networkIntent); assertEquals(0, commits)
        client.network(network(), { true }) { commits++ }
        assertEquals("2", native.networkIntent); assertEquals(1, commits)
        native.joinConfirmed = false
        assertTrue(runCatching { client.network(network(), { true }) { commits++ } }.isFailure)
        assertEquals(1, commits)
    }
    @Test fun cancelledNetworkDrainsNativeSnapshotAndCommitsHostInsideSameGate() = runBlocking {
        val native = MeowPolicyFixture(); var epoch: String? = null; val gate = Mutex()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var committed = false; var published = false
        native.onOperation = { if(it == "network") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val client = MeowHostPolicyClient(7, gate, { epoch }, { epoch = it }, native)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.network(network(), { true }) { assertTrue(gate.isLocked); committed = true }; published = true }
        check(entered.await(5, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertTrue(committed); assertFalse(published); assertFalse(gate.isLocked); assertEquals("2", epoch)
    }
    @Test fun networkRejectsCoherentWrongDnsBeforeEpochOrHostPublicationAndAcceptsEquivalentIpv6() = runBlocking {
        val native = MeowPolicyFixture(); var epoch: String? = null; var commits = 0
        val client = MeowHostPolicyClient(7, Mutex(), { epoch }, { epoch = it }, native)
        native.returnedServers = listOf("1.1.1.1:53")
        assertTrue(runCatching { client.network(network(), { true }) { commits++ } }.isFailure)
        assertEquals(0, commits); assertEquals("1", epoch)
        // A non-selected interface must not affect the target; textual IPv6 compression is immaterial.
        val target = MeowPhysicalNetwork("43", SystemDnsSnapshot("wlan0", mapOf(
            "wlan0" to listOf("2001:4860:4860:0:0:0:0:8888%12"), "other0" to listOf("1.1.1.1"))))
        native.returnedServers = listOf("[2001:4860:4860::8888%12]:53")
        assertEquals("3", client.network(target, { true }) { commits++ }.coreEpoch)
        assertEquals(1, commits); assertEquals("3", epoch)
        native.returnedServers = listOf("[2001:4860:4860::8888%13]:53")
        assertTrue(runCatching { client.network(target, { true }) { commits++ } }.isFailure)
        assertEquals(1, commits); assertEquals("3", epoch)
    }
}
