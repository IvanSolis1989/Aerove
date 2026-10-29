package dev.aerove.lite.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OwnedStartRequestTest {
    @Test fun cancellationBeforeSendRejectsTheSameRequestForever() = runTest {
        val registry = OwnedStartRegistry(1)
        val request = checkNotNull(registry.prepare())
        assertEquals(OwnedStartCancellation.Prevented, request.cancelAndJoin())
        assertFalse(registry.send(request))
        assertNull(registry.admit(request.id) { error("Cancelled request was admitted") })
        assertEquals(OwnedStartCancellation.Prevented, request.cancelAndJoin())
        assertNotNull(registry.prepare())
    }

    @Test fun cancellationAfterSendRejectsLateDeliveryAndReclaimsCapacity() = runTest {
        val registry = OwnedStartRegistry(1)
        val request = checkNotNull(registry.prepare())
        assertTrue(registry.send(request))
        assertNull(registry.prepare())
        assertEquals(OwnedStartCancellation.Prevented, request.cancelAndJoin())
        assertNull(registry.admit(request.id) { error("Late delivery was admitted") })
        assertNotNull(registry.prepare())
        assertNull(OwnedStartRegistry().admit(request.id) { error("Replayed ID was admitted") })
    }

    @Test fun unknownForeignUnsentAndRepeatedRequestsCannotBeConsumed() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        assertNull(registry.admit("unknown") { error("Unknown request was admitted") })
        assertFalse(OwnedStartRegistry().send(request))
        assertNull(registry.admit(request.id) { error("Unsent request was admitted") })
        assertTrue(registry.send(request))
        assertFalse(registry.send(request))
        assertSame(request, registry.admit(request.id) { {} })
        assertNull(registry.admit(request.id) { error("Repeated request was admitted") })
        registry.retire(request)
        assertEquals(OwnedStartCancellation.Superseded, request.cancelAndJoin())
        assertNull(registry.admit(request.id) { error("Retired request was admitted") })
    }

    @Test fun boundCancellationIsLatchedAndSharesOneAcknowledgement() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        var cancellations = 0
        registry.send(request)
        registry.admit(request.id) { { cancellations++ } }
        val first = async { request.cancelAndJoin() }
        val second = async { request.cancelAndJoin() }
        runCurrent()
        assertEquals(1, cancellations)
        assertTrue(request.cancelling)
        assertNull(request.onCancel)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        registry.retire(request)
        assertFalse(first.isCompleted)
        request.completeCancellation(OwnedStartCancellation.CancelledAndJoined)
        assertEquals(OwnedStartCancellation.CancelledAndJoined, first.await())
        assertEquals(first.await(), second.await())
    }

    @Test fun callerBudgetCanExpireWithoutCompletingTheSharedResourceAcknowledgement() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        var cancellations = 0
        registry.send(request)
        registry.admit(request.id) { { cancellations++ } }
        val shortWait = async { request.cancelAndJoin(16_000) }
        val defaultWait = async { request.cancelAndJoin() }
        runCurrent(); advanceTimeBy(16_000); runCurrent()
        assertEquals(OwnedStartCancellation.TerminationUnconfirmed, shortWait.await())
        assertFalse(defaultWait.isCompleted)
        assertEquals(1, cancellations)
        request.completeCancellation(OwnedStartCancellation.CancelledAndJoined)
        assertEquals(OwnedStartCancellation.CancelledAndJoined, defaultWait.await())
        assertEquals(OwnedStartCancellation.CancelledAndJoined, request.cancelAndJoin(1))
        assertEquals(1, cancellations)
    }

    @Test fun defaultCallerWaitRemainsBoundedByTheExistingResourceLimit() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        registry.send(request)
        registry.admit(request.id) { {} }
        val cancellation = async { request.cancelAndJoin() }
        runCurrent(); advanceTimeBy(44_999); runCurrent()
        assertFalse(cancellation.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals(OwnedStartCancellation.TerminationUnconfirmed, cancellation.await())
    }

    @Test fun invalidCallerBudgetsAreRejectedBeforeLatchingCancellation() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        for (budget in listOf(-1L, 0L, 45_001L, Long.MAX_VALUE)) {
            try { request.cancelAndJoin(budget); error("Invalid budget was accepted") }
            catch (_: IllegalArgumentException) { }
            assertFalse(request.cancelling)
        }
        assertTrue(registry.send(request))
        assertEquals(OwnedStartCancellation.Prevented, request.cancelAndJoin(1))
    }

    @Test fun capturedCancellationLatchCannotReopenWhenTheServiceReceiptIsClearedOrReplaced() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        registry.send(request)
        registry.admit(request.id) { { request.completeCancellation(OwnedStartCancellation.CancelledAndJoined) } }
        var receipt: OwnedStartServiceReceipt? = OwnedStartServiceReceipt(request, 1, 7, 3)
        val admitted = capturedOwnedStartIntent(7, receipt, 1, { 7 }, { true }, { true })
        assertTrue(admitted())
        request.cancelAndJoin()
        assertFalse(admitted())
        receipt = null
        assertNull(receipt)
        assertFalse(admitted())
        receipt = OwnedStartServiceReceipt(checkNotNull(registry.prepare()), 1, 8, 4)
        assertEquals(8L, receipt.intentGeneration)
        assertFalse(admitted())
    }

    @Test fun capturedOwnedAdmissionStillRequiresTheOriginalGenerationAndServiceOwner() {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val receipt = OwnedStartServiceReceipt(request, 1, 7, 3)
        var generation = 7L
        var serviceCurrent = true
        val admitted = capturedOwnedStartIntent(7, receipt, 1, { generation }, { true }, { serviceCurrent })
        assertTrue(admitted())
        generation++
        assertFalse(admitted())
        val serviceOwned = capturedOwnedStartIntent(8, null, 1, { generation }, { true }, { serviceCurrent })
        assertTrue(serviceOwned())
        serviceCurrent = false
        assertFalse(serviceOwned())
    }

    @Test fun rejectingUnacceptedRequestsDoesNotRetireAnAdmittedRequest() = runTest {
        val registry = OwnedStartRegistry(2)
        val rejected = checkNotNull(registry.prepare())
        val admitted = checkNotNull(registry.prepare())
        registry.send(rejected); registry.send(admitted)
        var cancelled = false
        registry.admit(admitted.id) { { cancelled = true; admitted.completeCancellation(OwnedStartCancellation.Superseded) } }
        registry.reject(rejected.id); registry.reject(admitted.id)
        assertEquals(OwnedStartCancellation.Prevented, rejected.cancelAndJoin())
        assertEquals(OwnedStartCancellation.Superseded, admitted.cancelAndJoin())
        assertTrue(cancelled)
    }

    @Test fun retiringAServiceOwnerReleasesItsCallbackAndRejectsReplays() = runTest {
        val registry = OwnedStartRegistry(1)
        val request = checkNotNull(registry.prepare())
        registry.send(request)
        registry.admit(request.id) { { error("Destroyed service was called") } }
        registry.retire(request)
        assertNull(request.onCancel)
        assertEquals(OwnedStartCancellation.Superseded, request.cancelAndJoin())
        assertNull(registry.admit(request.id) { error("Destroyed request was restored") })
        assertNotNull(registry.prepare())
    }

    @Test fun aCancelledCallerStillWithdrawsItsUnacceptedStart() = runTest {
        val registry = OwnedStartRegistry()
        val request = checkNotNull(registry.prepare())
        registry.send(request)
        var result: OwnedStartCancellation? = null
        val caller = launch {
            try { awaitCancellation() }
            finally { result = request.cancelAndJoin() }
        }
        runCurrent(); caller.cancel(); caller.join()
        assertEquals(OwnedStartCancellation.Prevented, result)
        assertNull(registry.admit(request.id) { error("Cancelled caller left an admitted start") })
    }

    @Test fun supersededReceiptCannotWithdrawAYoungerIntent() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val expected = OwnedStartServiceReceipt(request, 1, 7, 3)
        val newer = expected.copy(intentGeneration = 8)
        var withdrawals = 0
        val result = cancelOwnedStartForService(expected, { newer }, { 8 }, { 4 }, { action -> action() },
            withdraw = { withdrawals++; error("Newer intent was stopped") }, finish = { _, _ -> error("Newer state was cleared") })
        assertEquals(OwnedStartCancellation.Superseded, result)
        assertEquals(0, withdrawals)
    }

    @Test fun changedServiceOwnerAndRepeatedCancellationCannotClearState() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val expected = OwnedStartServiceReceipt(request, 1, 7, 3)
        var current: OwnedStartServiceReceipt? = expected.copy(serviceOwner = 2)
        var generation = 7L
        var withdrawals = 0
        var finished = 0
        suspend fun cancel() = cancelOwnedStartForService(expected, { current }, { generation }, { 3 }, { action -> action() },
            withdraw = { withdrawals++; current = null; generation++; OwnedStartStop(generation, null, CompletableDeferred(Unit)) },
            finish = { startId, token -> assertEquals(3, startId); assertEquals(8L, token); finished++ })
        assertEquals(OwnedStartCancellation.Superseded, cancel())
        current = expected
        assertEquals(OwnedStartCancellation.CancelledAndJoined, cancel())
        assertEquals(OwnedStartCancellation.Superseded, cancel())
        assertEquals(1, withdrawals)
        assertEquals(1, finished)
    }

    @Test fun withdrawalJoinsPreparationAndCapturedWorkWithoutClearingNewIntent() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val receipt = OwnedStartServiceReceipt(request, 1, 7, 3)
        var current: OwnedStartServiceReceipt? = receipt
        var generation = 7L
        var startId = 3
        var finished = false
        var preparationFinished = false
        val preparation = launch { try { awaitCancellation() } finally { preparationFinished = true } }
        runCurrent()
        val joined = CompletableDeferred<Unit>()
        val cancellation = async {
            cancelOwnedStartForService(receipt, { current }, { generation }, { startId }, { action -> action() },
                withdraw = { current = null; generation++; OwnedStartStop(generation, preparation, joined) },
                finish = { _, _ -> finished = true })
        }
        runCurrent()
        assertTrue(preparationFinished)
        assertFalse(cancellation.isCompleted)
        generation++
        startId = 5
        joined.complete(Unit); runCurrent()
        assertEquals(OwnedStartCancellation.CancelledAndJoined, cancellation.await())
        assertFalse(finished)
    }

    @Test fun replayedStartIdCanAdvanceWithinTheSameIntentBeforeOwnedStopFinishes() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val receipt = OwnedStartServiceReceipt(request, 1, 7, 3)
        var generation = 7L
        var startId = 3
        var finishedStartId: Int? = null
        val joined = CompletableDeferred<Unit>()
        val cancellation = async {
            cancelOwnedStartForService(receipt, { receipt }, { generation }, { startId }, { action -> action() },
                withdraw = { generation++; OwnedStartStop(generation, null, joined) },
                finish = { latestId, token -> assertEquals(8L, token); finishedStartId = latestId })
        }
        runCurrent()
        startId = 4
        joined.complete(Unit); runCurrent()
        assertEquals(OwnedStartCancellation.CancelledAndJoined, cancellation.await())
        assertEquals(4, finishedStartId)
    }

    @Test fun failedOrUnconfirmedJoinNeverAcknowledgesTermination() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val receipt = OwnedStartServiceReceipt(request, 1, 7, 3)
        val failed = CompletableDeferred<Unit>().apply { completeExceptionally(IllegalStateException("join failed")) }
        assertEquals(OwnedStartCancellation.TerminationUnconfirmed,
            cancelOwnedStartForService(receipt, { receipt }, { 8 }, { 3 }, { action -> action() },
                withdraw = { OwnedStartStop(8, null, failed) }, finish = { _, _ -> error("Failed join cleared state") }))
        val cancellation = async {
            cancelOwnedStartForService(receipt, { receipt }, { 8 }, { 3 }, { action -> action() },
                withdraw = { OwnedStartStop(8, null, CompletableDeferred()) }, finish = { _, _ -> error("Timed out join cleared state") })
        }
        runCurrent(); advanceTimeBy(45_000); runCurrent()
        assertEquals(OwnedStartCancellation.TerminationUnconfirmed, cancellation.await())
    }

    @Test fun uncooperativePreparationKeepsAcknowledgementPendingAndCannotSubmitAfterCancel() = runTest {
        val request = checkNotNull(OwnedStartRegistry().prepare())
        val receipt = OwnedStartServiceReceipt(request, 1, 7, 3)
        val release = CompletableDeferred<Unit>()
        var generation = 7L
        var submitted = false
        val preparation = backgroundScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
            if (generation == receipt.intentGeneration) submitted = true
        }
        runCurrent()
        val cancellation = async {
            cancelOwnedStartForService(receipt, { receipt }, { generation }, { 3 }, { action -> action() },
                withdraw = { generation++; OwnedStartStop(generation, preparation, CompletableDeferred(Unit)) },
                finish = { _, _ -> })
        }
        runCurrent()
        assertFalse(cancellation.isCompleted)
        release.complete(Unit); runCurrent()
        assertEquals(OwnedStartCancellation.CancelledAndJoined, cancellation.await())
        assertFalse(submitted)
    }
}
