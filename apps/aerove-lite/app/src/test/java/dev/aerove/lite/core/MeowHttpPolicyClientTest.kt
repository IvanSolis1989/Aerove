package dev.aerove.lite.core

import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowHttpPolicyClientTest {
    private val old = MeowHttpPolicy("AEROVE/old", true)
    private val target = MeowHttpPolicy("AEROVE/new", false)
    private val id = "37337b38-f2bf-4d64-b906-df47a122b625"
    private fun json(epoch: String, value: MeowHttpPolicy) = JSONObject().put("coreEpoch", epoch)
        .put("http", JSONObject().put("globalUserAgent", value.globalUserAgent).put("checkCertificate", value.checkCertificate))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected strict rejection") } catch (_: IllegalArgumentException) { } catch (_: org.json.JSONException) { }
    }

    @Test fun strictWireRoundTripPreservesU64Utf8AndNativeRestrictions() {
        val agent = MeowHttpPolicy("  猫/✓  ", false)
        val request = JSONObject(MeowHttpPolicyCodec.request(id, "18446744073709551615", agent))
        assertEquals(setOf("schemaVersion", "requestId", "expectedCoreEpoch", "http"), request.keys().asSequence().toSet())
        assertEquals(1, request.get("schemaVersion"))
        assertEquals(agent, MeowHttpPolicyCodec.status(json("18446744073709551615", agent).toString()).http)
        assertEquals(id, MeowHttpPolicyCodec.receipt(json("2", agent).put("requestId", id).toString()).requestId)
        for (epoch in listOf("0", "01", "-1", "1.0", "18446744073709551616")) rejected { MeowHttpPolicyCodec.request(id, epoch, agent) }
        for (bad in listOf("00000000-0000-0000-0000-000000000000", id.uppercase(), "1-1-1-1-1")) rejected { MeowHttpPolicyCodec.request(bad, "1", agent) }
        for (bad in listOf("  ", "a\u0080", "a\n", "猫".repeat(1366))) rejected { MeowHttpPolicy(bad, true) }
        assertEquals(4096, MeowHttpPolicy("a".repeat(4096), true).globalUserAgent.length)
        assertFalse(agent.toString().contains("猫"))
    }

    @Test fun responseUnknownFieldsTypesAndMissingFieldsFailClosed() {
        rejected { MeowHttpPolicyCodec.status(json("1", old).put("extra", true).toString()) }
        rejected { MeowHttpPolicyCodec.status(json("1", old).put("coreEpoch", 1).toString()) }
        rejected { MeowHttpPolicyCodec.status(json("1", old).put("http", JSONObject().put("globalUserAgent", "ok").put("checkCertificate", "false")).toString()) }
        rejected { MeowHttpPolicyCodec.status(json("1", old).put("http", JSONObject().put("globalUserAgent", "ok").put("checkCertificate", true).put("dns", false)).toString()) }
        rejected { MeowHttpPolicyCodec.receipt(json("1", old).toString()) }
        val facade = Class.forName("dev.aerove.lite.core.MeowNativeCore", false, javaClass.classLoader)
        for ((name, types) in listOf("configurePolicies" to arrayOf(Long::class.javaPrimitiveType!!, String::class.java), "policyStatus" to arrayOf(Long::class.javaPrimitiveType!!))) {
            val method = facade.getDeclaredMethod(name, *types)
            assertEquals(String::class.java, method.returnType)
            assertTrue(Modifier.isStatic(method.modifiers) && Modifier.isNative(method.modifiers))
        }
    }

    private inner class Native : MeowHttpPolicyClient.Commands {
        var epoch = "5"; var value = old; var calls = 0; var reads = 0
        var beforeCommit: () -> Unit = {}
        var rewrite: (JSONObject) -> JSONObject = { it }
        override fun status(handle: Long): String { reads++; return json(epoch, value).toString() }
        override fun configure(handle: Long, request: String): String {
            calls++
            val row = JSONObject(request)
            assertEquals(epoch, row.getString("expectedCoreEpoch"))
            beforeCommit()
            epoch = (epoch.toULong() + 1u).toString()
            value = MeowHttpPolicyCodec.status(json(epoch, old).put("http", row.getJSONObject("http")).toString()).http
            return rewrite(json(epoch, value).put("requestId", row.getString("requestId"))).toString()
        }
    }

    @Test fun actualStatusSuppliesInitialCasAndReceiptAdvancesSharedEpoch() = runBlocking {
        val native = Native(); var shared: String? = null; val epochs = mutableListOf<String>()
        val callerThread = Thread.currentThread()
        native.beforeCommit = { assertNotSame(callerThread, Thread.currentThread()) }
        val client = MeowHttpPolicyClient(7, Mutex(), { shared }, { shared = it; epochs += it }, native)
        assertEquals(MeowHttpPolicyStatus("6", target), client.apply(target))
        assertEquals(listOf("5", "6", "6"), epochs)
        assertEquals(2, native.reads)
        assertEquals("6", client.apply(target).coreEpoch)
        assertEquals(1, native.calls) // Actual unchanged policy needs no configure.
    }

    @Test fun casErrorsAndMalformedReceiptsDoNotEstablishTarget() = runBlocking {
        val native = Native(); var shared: String? = "4"
        val client = MeowHttpPolicyClient(7, Mutex(), { shared }, { shared = it }, native)
        val failure = IllegalStateException("staleCoreEpoch")
        native.beforeCommit = { throw failure }
        try { client.apply(target); fail() } catch (actual: IllegalStateException) {
            assertEquals(failure.message, actual.message)
            assertTrue(actual === failure || actual.cause === failure) // Coroutine stack recovery may copy the throwable.
        }
        assertEquals("5", shared)
        native.beforeCommit = {}; native.rewrite = { it.put("requestId", id) }
        try { client.apply(target); fail() } catch (_: IllegalStateException) { }
        assertEquals("5", shared) // Unknown receipt cannot authorize a fabricated shared epoch.
        assertEquals("6", client.apply(target).coreEpoch) // Real status reconciles the committed native value.
    }

    @Test fun cancelledAcceptedCommandDrainsReceiptBeforeReleasingSessionGate() = runBlocking {
        val native = Native(); var shared: String? = null; val entered = CountDownLatch(1); val release = CountDownLatch(1)
        native.beforeCommit = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val gate = Mutex(); val client = MeowHttpPolicyClient(7, gate, { shared }, { shared = it }, native)
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.apply(target); published = true }
        check(entered.await(5, TimeUnit.SECONDS)); job.cancel()
        assertTrue(gate.isLocked)
        release.countDown(); job.join()
        assertFalse(published); assertEquals("6", shared); assertFalse(gate.isLocked)
        assertEquals(1, native.reads) // No stale post-cancel readback.
    }

    @Test fun newerIntentRejectsOldResultAndUsesItsRealReceiptEpoch() = runBlocking {
        val native = Native(); var shared: String? = null; val entered = CountDownLatch(1); val release = CountDownLatch(1)
        native.beforeCommit = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val client = MeowHttpPolicyClient(7, Mutex(), { shared }, { shared = it }, native)
        var oldPublished = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) { client.apply(target); oldPublished = true }
        check(entered.await(5, TimeUnit.SECONDS))
        val newest = async(start = CoroutineStart.UNDISPATCHED) { client.apply(MeowHttpPolicy("AEROVE/latest", true)) }
        release.countDown(); first.join()
        assertEquals("7", newest.await().coreEpoch)
        assertEquals("7", shared); assertFalse(oldPublished); assertEquals(2, native.calls)
    }
}
