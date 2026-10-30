package dev.aerove.lite.flutter

import dev.aerove.lite.vpn.OwnedStartCancellation
import dev.aerove.lite.vpn.OwnedStartRegistry
import dev.aerove.lite.vpn.OwnedStartRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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
class MeowFlutterCommandFacadeTest {
    private class Harness(capacity: Int = 64) {
        val registry = OwnedStartRegistry(capacity)
        var allowed = true
        var granted = true
        var epoch = 0L
        var dispatchFailure: Exception? = null
        var requiresRuntime = false
        var currentRuntime: MeowFlutterRuntimeBinding? = null
        val sent = mutableListOf<Pair<OwnedStartRequest, Long>>()
        val sentRuntime = mutableListOf<MeowFlutterRuntimeBinding?>()
        val stops = mutableListOf<String>()
        val stopSnapshots = MutableStateFlow(MeowFlutterStopSnapshot(7, 3, terminal = true))
        val facade = MeowFlutterCommandFacade(registry, { allowed }, { allowed && granted }, { epoch },
            { epoch++; stops += "invalidate" },
            { request, requestedAt, runtime -> dispatchFailure?.let { throw it }; sent += request to requestedAt; sentRuntime += runtime },
            { stops += "stop" }, { stops += "cancel-work" }, { 123L },
            { requiresRuntime }, { currentRuntime == null || currentRuntime == it },
            { stopSnapshots.value }, stopSnapshots)
    }

    @Test fun controllerEntrySendsAnAdmissibleOwnedRequestOnceWithOriginalTimestamp() {
        val h = Harness()
        val request = checkNotNull(h.facade.prepareOwnedConnect())
        assertTrue(h.facade.connectOwned(request))
        assertFalse(h.facade.connectOwned(request))
        assertEquals(listOf(request to 123L), h.sent)
        assertSame(request, h.registry.admit(request.id) { {} })
    }

    @Test fun permissionCancellationRejectsLateGrantWithoutDispatchingAStop() = runTest {
        val h = Harness(1)
        h.granted = false
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        assertEquals(OwnedStartCancellation.Prevented, h.facade.cancelPermissionConnect(attempt))
        h.granted = true
        assertEquals(MeowFlutterPermissionResult.Ignored, h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sent.isEmpty())
        assertTrue(h.stops.isEmpty())
        assertNotNull(h.facade.preparePermissionConnect())
    }

    @Test fun deniedPermissionRetiresOnlyItsRequestAndDuplicateCallbacksAreIgnored() = runTest {
        val h = Harness(1)
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Denied,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(attempt, false))
        assertEquals(MeowFlutterPermissionResult.Ignored, h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sent.isEmpty())
        assertTrue(h.stops.isEmpty())
        assertNotNull(h.facade.preparePermissionConnect())
    }

    @Test fun newPermissionIntentSupersedesOldGrantButDoesNotCancelTheNewStart() = runTest {
        val h = Harness()
        val old = checkNotNull(h.facade.preparePermissionConnect())
        val current = checkNotNull(h.facade.preparePermissionConnect())
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Superseded,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(old, true))
        assertEquals(MeowFlutterPermissionResult.Sent, h.facade.completePermissionConnect(current, true))
        assertEquals(1, h.sent.size)
        assertTrue(h.stops.isEmpty())
    }

    @Test fun disconnectInvalidatesPermissionBeforeSendingTheExistingStopCommand() = runTest {
        val h = Harness()
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        h.facade.disconnect()
        assertEquals(listOf("invalidate", "cancel-work", "stop"), h.stops)
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Superseded,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sent.isEmpty())
    }

    @Test fun externalEpochChangeRejectsALateGrantAndReclaimsTheOwnedSlot() = runTest {
        val h = Harness(1)
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        h.epoch++
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Superseded,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(attempt, true))
        assertNotNull(h.facade.prepareOwnedConnect())
        assertTrue(h.sent.isEmpty())
    }

    @Test fun grantRechecksAdmissionAndActualPermissionRatherThanTrustingTheCallback() = runTest {
        val h = Harness(1)
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        h.granted = false
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.AdmissionRejected,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sent.isEmpty())
        h.allowed = false
        assertNull(h.facade.prepareOwnedConnect())
        assertNull(h.facade.preparePermissionConnect())
    }

    @Test fun launchFailureRetiresTheRequestAndPreservesTheOriginalFailure() = runTest {
        val h = Harness(1)
        val request = checkNotNull(h.facade.prepareOwnedConnect())
        val failure = IllegalStateException("foreground service launch refused")
        h.dispatchFailure = failure
        try { h.facade.connectOwned(request); error("Launch failure was swallowed") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(OwnedStartCancellation.Prevented, h.facade.cancelOwnedConnect(request))
        assertFalse(h.facade.connectOwned(request))
        assertNotNull(h.facade.prepareOwnedConnect())
    }

    @Test fun cancellationWaitsForTheCapturedServiceAcknowledgementAndKeepsUnconfirmedDistinct() = runTest {
        val h = Harness()
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        assertEquals(MeowFlutterPermissionResult.Sent, h.facade.completePermissionConnect(attempt, true))
        val request = h.sent.single().first
        var withdrawals = 0
        h.registry.admit(request.id) { { withdrawals++ } }
        val cancellation = async { h.facade.cancelPermissionConnect(attempt, 10) }
        runCurrent()
        assertFalse(cancellation.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertEquals(OwnedStartCancellation.TerminationUnconfirmed, cancellation.await())
        assertEquals(1, withdrawals)
        assertTrue(h.stops.isEmpty())
        request.completeCancellation(OwnedStartCancellation.CancelledAndJoined)
        assertEquals(OwnedStartCancellation.CancelledAndJoined, h.facade.cancelPermissionConnect(attempt))
        assertEquals(MeowFlutterPermissionResult.Ignored, h.facade.completePermissionConnect(attempt, true))
        assertEquals(1, withdrawals)
    }

    @Test fun aDifferentFacadeCannotConsumeOrCancelThePermissionCapability() = runTest {
        val h = Harness()
        val other = Harness()
        val attempt = checkNotNull(h.facade.preparePermissionConnect())
        try { other.facade.cancelPermissionConnect(attempt); error("Foreign permission capability was accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(MeowFlutterPermissionResult.Sent, h.facade.completePermissionConnect(attempt, true))
        assertTrue(other.sent.isEmpty())
        assertTrue(other.stops.isEmpty())
    }

    @Test fun eachDirectRequestDispatchesItsImmutableRuntimeIdentityInsteadOfTheLatestPreparedOne() {
        val h = Harness()
        val firstRuntime = MeowFlutterRuntimeBinding("revision-a", 4)
        val nextRuntime = MeowFlutterRuntimeBinding("revision-b", 5)
        val first = checkNotNull(h.facade.prepareOwnedConnect(firstRuntime))
        assertTrue(h.facade.connectOwned(first))
        val next = checkNotNull(h.facade.prepareOwnedConnect(nextRuntime))
        assertTrue(h.facade.connectOwned(next))
        assertEquals(listOf(firstRuntime, nextRuntime), h.sentRuntime)
    }

    @Test fun aNewDirectIntentPreventsALatePermissionGrantFromReplacingIt() = runTest {
        val h = Harness()
        val runtime = MeowFlutterRuntimeBinding("revision", 4)
        val permission = checkNotNull(h.facade.preparePermissionConnect(runtime))
        val direct = checkNotNull(h.facade.prepareOwnedConnect(runtime))
        assertTrue(h.facade.connectOwned(direct))
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Superseded,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(permission, true))
        assertEquals(1, h.sent.size)
        assertSame(direct, h.sent.single().first)
    }

    @Test fun aNewPermissionIntentPreventsAnOlderDirectRequestFromBeingSent() = runTest {
        val h = Harness()
        val runtime = MeowFlutterRuntimeBinding("revision", 4)
        val direct = checkNotNull(h.facade.prepareOwnedConnect(runtime))
        val permission = checkNotNull(h.facade.preparePermissionConnect(runtime))
        assertFalse(h.facade.connectOwned(direct))
        assertEquals(OwnedStartCancellation.Prevented, h.facade.cancelOwnedConnect(direct))
        assertEquals(MeowFlutterPermissionResult.Sent, h.facade.completePermissionConnect(permission, true))
        assertEquals(1, h.sent.size)
    }

    @Test fun permissionGrantUsesItsCapturedRuntimeIdentity() = runTest {
        val h = Harness()
        val runtime = MeowFlutterRuntimeBinding("permission-revision", 6)
        val attempt = checkNotNull(h.facade.preparePermissionConnect(runtime))
        assertEquals(MeowFlutterPermissionResult.Sent, h.facade.completePermissionConnect(attempt, true))
        assertEquals(listOf(runtime), h.sentRuntime)
    }

    @Test fun aSupersededHandoffRejectsLatePermissionWithoutStartingTheNewHandoff() = runTest {
        val h = Harness(1)
        val runtime = MeowFlutterRuntimeBinding("old-revision", 4)
        h.currentRuntime = runtime
        val attempt = checkNotNull(h.facade.preparePermissionConnect(runtime))
        h.currentRuntime = MeowFlutterRuntimeBinding("new-revision", 5)
        assertEquals(MeowFlutterPermissionResult.Cancelled(MeowFlutterPermissionRejection.Superseded,
            OwnedStartCancellation.Prevented), h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sent.isEmpty())
        assertNotNull(h.facade.preparePermissionConnect(h.currentRuntime))
    }

    @Test fun cancellationOfABoundPermissionRequestCannotAdmitItsLateDelivery() = runTest {
        val h = Harness(1)
        val attempt = checkNotNull(h.facade.preparePermissionConnect(MeowFlutterRuntimeBinding("cancelled", 8)))
        assertEquals(OwnedStartCancellation.Prevented, h.facade.cancelPermissionConnect(attempt))
        assertEquals(MeowFlutterPermissionResult.Ignored, h.facade.completePermissionConnect(attempt, true))
        assertTrue(h.sentRuntime.isEmpty())
        assertTrue(h.stops.isEmpty())
    }

    @Test fun directRequestsCannotCrossAnEpochChangeOrLoseTheirRequiredRuntimeBinding() = runTest {
        val h = Harness(1)
        val request = checkNotNull(h.facade.prepareOwnedConnect(MeowFlutterRuntimeBinding("revision", 4)))
        h.epoch++
        assertFalse(h.facade.connectOwned(request))
        assertEquals(OwnedStartCancellation.Prevented, h.facade.cancelOwnedConnect(request))
        h.requiresRuntime = true
        assertNull(h.facade.prepareOwnedConnect())
        assertNull(h.facade.preparePermissionConnect())
        assertTrue(h.sent.isEmpty())
    }

    @Test fun disconnectDoesNotTreatTheOldStoppedSnapshotAsAnAcknowledgement() = runTest {
        val h = Harness()
        val result = async { h.facade.disconnectAndJoin(10) }
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(10); runCurrent()
        assertEquals(MeowFlutterStopResult.TerminationUnconfirmed, result.await())
        assertEquals(listOf("invalidate", "cancel-work", "stop"), h.stops)
    }

    @Test fun disconnectConfirmsOnlyAFreshTerminalSnapshotOfTheCapturedServiceOwner() = runTest {
        val h = Harness()
        h.stopSnapshots.value = MeowFlutterStopSnapshot(7, 3, terminal = false)
        val result = async { h.facade.disconnectAndJoin() }
        runCurrent()
        h.stopSnapshots.value = MeowFlutterStopSnapshot(7, 4, terminal = false)
        runCurrent()
        assertFalse(result.isCompleted)
        h.stopSnapshots.value = MeowFlutterStopSnapshot(7, 4, terminal = true)
        assertEquals(MeowFlutterStopResult.Confirmed(7, 4), result.await())
    }

    @Test fun replacedServiceOrNewerSessionCannotAcknowledgeTheCapturedStop() = runTest {
        for (replacement in listOf(MeowFlutterStopSnapshot(8, 4, terminal = true),
            MeowFlutterStopSnapshot(7, 5, terminal = true))) {
            val h = Harness()
            val result = async { h.facade.disconnectAndJoin() }
            runCurrent()
            h.stopSnapshots.value = replacement
            assertEquals(MeowFlutterStopResult.Superseded, result.await())
        }
    }

    @Test fun failedTerminationCannotBeReportedAsConfirmed() = runTest {
        val h = Harness()
        val result = async { h.facade.disconnectAndJoin() }
        runCurrent()
        h.stopSnapshots.value = MeowFlutterStopSnapshot(7, 4, terminal = false, failed = true)
        assertEquals(MeowFlutterStopResult.TerminationUnconfirmed, result.await())
    }
}
