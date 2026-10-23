package dev.aerove.lite.core

import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowProbePolicyClientTest {
    private val id = "37337b38-f2bf-4d64-b906-df47a122b625"
    private val disabled = MeowProbeDialPolicy(false, false)
    private val enabled = MeowProbeDialPolicy(true, true)
    private fun probe(value: MeowProbeDialPolicy) = JSONObject().put("unifiedDelay", value.unifiedDelay).put("tcpConcurrent", value.tcpConcurrent)
    private fun http() = JSONObject().put("globalUserAgent", "AEROVE/test").put("checkCertificate", false)
    private fun row(operation: String, value: JSONObject, epoch: String = "41", requestId: String = id) = JSONObject()
        .put("requestId", requestId).put("coreEpoch", epoch).put("operation", operation).put("value", value)
    private fun snapshotValue(value: MeowProbeDialPolicy) = JSONObject().put("http", http()).put("probeDial", probe(value))
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } catch (_: org.json.JSONException) { }
    }

    @Test fun exactFrozenRequestsAndResponsesAndJniDescriptor() {
        val request = JSONObject(MeowProbePolicyCodec.setRequest(id, "18446744073709551615", disabled))
        assertEquals(setOf("schemaVersion", "requestId", "expectedCoreEpoch", "operation", "value"), request.keys().asSequence().toSet())
        assertEquals(1, request.get("schemaVersion")); assertEquals("probeDial", request.getString("operation"))
        assertFalse(request.getJSONObject("value").getBoolean("unifiedDelay"))
        assertEquals(0, JSONObject(MeowProbePolicyCodec.snapshotRequest(id, "41")).getJSONObject("value").length())
        val actual = MeowProbePolicyCodec.snapshot(row("snapshot", snapshotValue(disabled)).toString(), id)
        assertEquals(disabled, actual.probeDial); assertFalse(actual.http.checkCertificate)
        assertEquals(enabled, MeowProbePolicyCodec.receipt(row("probeDial", probe(enabled)).toString(), id).probeDial)
        val facade = Class.forName("dev.aerove.lite.core.MeowNativeCore", false, javaClass.classLoader)
        val method = facade.getDeclaredMethod("hostPolicyCommand", Long::class.javaPrimitiveType, String::class.java)
        assertEquals(String::class.java, method.returnType)
        assertTrue(Modifier.isNative(method.modifiers) && Modifier.isStatic(method.modifiers))
    }

    @Test fun futureFieldsOperationsCoercedFlagsWrongIdsAndEpochsAreRejected() {
        rejected { MeowProbePolicyCodec.snapshot(row("snapshot", snapshotValue(enabled).put("process", "always")).toString(), id) }
        rejected { MeowProbePolicyCodec.snapshot(row("snapshot", snapshotValue(enabled)).put("extra", 1).toString(), id) }
        rejected { MeowProbePolicyCodec.receipt(row("probeDial", probe(enabled).put("unifiedDelay", "true")).toString(), id) }
        rejected { MeowProbePolicyCodec.receipt(row("probeDial", probe(enabled).put("future", false)).toString(), id) }
        rejected { MeowProbePolicyCodec.receipt(row("statistics", probe(enabled)).toString(), id) }
        rejected { MeowProbePolicyCodec.receipt(row("probeDial", probe(enabled), requestId = "a687578a-10f5-4a78-a924-44357aa95ae7").toString(), id) }
        rejected { MeowProbePolicyCodec.receipt(row("probeDial", probe(enabled), epoch = "0").toString(), id) }
        rejected { MeowProbePolicyCodec.setRequest(id, "01", enabled) }
    }

    private inner class Native : MeowProbePolicyClient.Commands {
        var epoch = "41"; var value = enabled; var changes = 0; val operations = mutableListOf<String>()
        var beforeSet: () -> Unit = {}
        var rewriteSnapshot: (JSONObject) -> JSONObject = { it }
        override fun currentEpoch(handle: Long) = epoch
        override fun command(handle: Long, request: String): String {
            val input = JSONObject(request); val operation = input.getString("operation"); operations += operation
            check(input.getString("expectedCoreEpoch") == epoch) { "staleCoreEpoch" }
            val requestId = input.getString("requestId")
            return when (operation) {
                "snapshot" -> rewriteSnapshot(row(operation, snapshotValue(value), epoch, requestId)).toString()
                "probeDial" -> {
                    beforeSet(); changes++; epoch = (epoch.toULong() + 1u).toString()
                    val policy = input.getJSONObject("value")
                    value = MeowProbeDialPolicy(policy.getBoolean("unifiedDelay"), policy.getBoolean("tcpConcurrent"))
                    row(operation, probe(value), epoch, requestId).toString()
                }
                else -> error("unknownHostPolicyOperation")
            }
        }
    }

    @Test fun nativeListenerEpochAndActualSnapshotDriveCasSharedRevisionAndNoop() = runBlocking {
        val native = Native(); var shared: String? = null; val epochs = mutableListOf<String>(); val thread = Thread.currentThread()
        native.beforeSet = { assertNotSame(thread, Thread.currentThread()) }
        val client = MeowProbePolicyClient(7, Mutex(), { shared }, { shared = it; epochs += it }, native)
        assertEquals("41", client.read { true }.coreEpoch)
        assertEquals(disabled, client.set(disabled) { true }.probeDial)
        assertEquals("42", shared)
        assertEquals(listOf("snapshot", "snapshot", "probeDial", "snapshot"), native.operations)
        assertEquals(disabled, client.set(disabled) { true }.probeDial)
        assertEquals(1, native.changes); assertEquals("42", epochs.last())
    }

    @Test fun staleSnapshotAndNativeFailurePropagateWithoutRetryOrFabricatedEpoch() = runBlocking {
        val native = Native(); var shared: String? = "40"
        val client = MeowProbePolicyClient(7, Mutex(), { shared }, { shared = it }, native)
        val failure = IllegalStateException("staleCoreEpoch")
        native.beforeSet = { throw failure }
        try { client.set(disabled) { true }; fail() } catch (actual: IllegalStateException) {
            assertEquals(failure.message, actual.message); assertTrue(actual === failure || actual.cause === failure)
        }
        assertEquals(listOf("snapshot", "probeDial"), native.operations)
        assertEquals("41", shared); assertEquals(0, native.changes)
        native.rewriteSnapshot = { it.put("coreEpoch", "42") }
        try { client.read { true }; fail() } catch (_: IllegalStateException) { }
        assertEquals("41", shared) // Snapshot must be exactly the requested stamp, not any newer epoch.
    }

    @Test fun acceptedCancellationDrainsRealReceiptAndReleasesSharedGateWithoutLateSnapshot() = runBlocking {
        val native = Native(); var shared: String? = null; val gate = Mutex()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        native.beforeSet = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val client = MeowProbePolicyClient(7, gate, { shared }, { shared = it }, native)
        var published = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.set(disabled) { true }; published = true }
        check(entered.await(5, TimeUnit.SECONDS)); job.cancel(); assertTrue(gate.isLocked)
        release.countDown(); job.join()
        assertFalse(published); assertEquals("42", shared); assertFalse(gate.isLocked)
        assertEquals(listOf("snapshot", "probeDial"), native.operations)
    }

    @Test fun existingOwnerIntentSupersedesOldReceiptWithoutNewClientCounter() = runBlocking {
        val native = Native(); var shared: String? = null; val ownerIntent = AtomicInteger(1)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        native.beforeSet = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val client = MeowProbePolicyClient(7, Mutex(), { shared }, { shared = it }, native)
        var oldPublished = false
        val first = launch(start = CoroutineStart.UNDISPATCHED) { client.set(disabled) { ownerIntent.get() == 1 }; oldPublished = true }
        check(entered.await(5, TimeUnit.SECONDS)); ownerIntent.set(2)
        val next = async(start = CoroutineStart.UNDISPATCHED) { client.set(enabled) { ownerIntent.get() == 2 } }
        release.countDown(); first.join()
        assertEquals("43", next.await().coreEpoch); assertEquals("43", shared)
        assertFalse(oldPublished); assertEquals(2, native.changes)
    }
}
