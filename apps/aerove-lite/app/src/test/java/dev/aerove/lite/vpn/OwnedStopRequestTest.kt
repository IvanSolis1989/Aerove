package dev.aerove.lite.vpn

import dev.aerove.lite.flutter.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OwnedStopRequestTest {
    private fun stopped(owner: Long, generation: Long) = MeowFlutterStopSnapshot(owner, generation, true)

    @Test fun coldOwnerCanAcknowledgeOnlyTheActuallyAdmittedStop() = runTest {
        val registry = OwnedStopRegistry()
        val request = registry.prepare(stopped(7, 3))
        val receipt = checkNotNull(registry.admit(request.id, 8, 1))
        assertNull(ownedStopTerminalResult(receipt, 1, stopped(8, 0), true, 1))
        assertFalse(request.result.isCompleted)
        val outcome = checkNotNull(ownedStopTerminalResult(receipt, 1, stopped(8, 1), true, 1))
        registry.complete(receipt, outcome)
        assertEquals(MeowFlutterStopResult.Confirmed(8, 1), request.result.await())
        assertNull(registry.admit(request.id, 8, 2))
    }

    @Test fun completionReentrancyCannotEraseANewerIssuedRequest() = runTest {
        val registry = OwnedStopRegistry()
        val first = registry.prepare(stopped(7, 0))
        var newest: OwnedStopRequest? = null
        first.result.invokeOnCompletion { newest = registry.prepare(stopped(7, 0)) }
        val intermediate = registry.prepare(stopped(7, 0))
        assertEquals(MeowFlutterStopResult.Superseded, intermediate.result.await())
        assertNull(registry.admit(intermediate.id, 8, 1))
        assertNotNull(registry.admit(checkNotNull(newest).id, 8, 1))
    }

    @Test fun realRunningSessionCannotConfirmUntilItsResourcesAreJoined() = runTest {
        val joined = kotlinx.coroutines.CompletableDeferred<Unit>()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            object : TunnelSession {
                override val endpoint = CoreEndpoint(1234, "test-only")
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = kotlinx.coroutines.awaitCancellation()
                override suspend fun stop() { joined.await() }
            }
        })
        coordinator.start(VpnPlan("profile", dev.aerove.lite.LiteSettings())); runCurrent()
        val registry = OwnedStopRegistry()
        val request = registry.prepare(MeowFlutterStopSnapshot(7, coordinator.state.value.sessionGeneration, false))
        val receipt = checkNotNull(registry.admit(request.id, 7, 2))
        val target = checkNotNull(coordinator.stop())
        runCurrent()
        assertEquals(dev.aerove.lite.VpnPhase.Stopping, coordinator.state.value.phase)
        assertNull(ownedStopTerminalResult(receipt, target,
            MeowFlutterStopSnapshot(7, coordinator.state.value.sessionGeneration, false), true, 2))
        joined.complete(Unit); runCurrent()
        assertEquals(dev.aerove.lite.VpnPhase.Stopped, coordinator.state.value.phase)
        registry.complete(receipt, checkNotNull(ownedStopTerminalResult(receipt, target,
            stopped(7, coordinator.state.value.sessionGeneration), true, 2)))
        assertEquals(MeowFlutterStopResult.Confirmed(7, target), request.result.await())
        coordinator.close()
    }

    @Test fun realColdCoordinatorRequiresTheStopGenerationRatherThanItsInitialStoppedState() = runTest {
        val registry = OwnedStopRegistry()
        val request = registry.prepare(stopped(7, 3))
        val receipt = checkNotNull(registry.admit(request.id, 8, 1))
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { error("cold stop must not open a core") })
        val target = checkNotNull(coordinator.stop())
        assertNull(ownedStopTerminalResult(receipt, target, stopped(8, coordinator.state.value.sessionGeneration), true, 1))
        runCurrent()
        val actual = coordinator.state.value
        assertEquals(dev.aerove.lite.VpnPhase.Stopped, actual.phase)
        assertNull(actual.endpoint)
        assertNull(actual.error)
        registry.complete(receipt, checkNotNull(ownedStopTerminalResult(receipt, target,
            stopped(8, actual.sessionGeneration), true, 1)))
        assertEquals(MeowFlutterStopResult.Confirmed(8, target), request.result.await())
        coordinator.close()
    }

    @Test fun runningOwnerReplacementAndForgedOrReplayedIdsCannotAcquireOwnership() = runTest {
        val registry = OwnedStopRegistry()
        val active = registry.prepare(MeowFlutterStopSnapshot(7, 3, false))
        assertNull(registry.admit("unknown", 8, 1))
        assertNull(registry.admit(active.id, 8, 1))
        assertEquals(MeowFlutterStopResult.Superseded, active.result.await())
        val request = registry.prepare(stopped(7, 3))
        assertNotNull(registry.admit(request.id, 7, 4))
        assertNull(registry.admit(request.id, 7, 5))
    }

    @Test fun newerIntentCannotBeAcknowledgedByAnOldTerminalSnapshot() = runTest {
        val registry = OwnedStopRegistry()
        val old = registry.prepare(stopped(7, 3))
        val receipt = checkNotNull(registry.admit(old.id, 8, 1))
        val newer = registry.prepare(stopped(8, 0))
        registry.complete(receipt, MeowFlutterStopResult.Confirmed(8, 1))
        assertEquals(MeowFlutterStopResult.Superseded, old.result.await())
        assertFalse(newer.result.isCompleted)
        assertEquals(MeowFlutterStopResult.Superseded,
            ownedStopTerminalResult(receipt, 1, stopped(8, 1), true, 2))
        assertEquals(MeowFlutterStopResult.Superseded,
            ownedStopTerminalResult(receipt, 1, stopped(9, 1), false, 1))
        assertEquals(MeowFlutterStopResult.Superseded,
            ownedStopTerminalResult(receipt, 1, stopped(8, 2), true, 1))
    }

    @Test fun failureAndOwnerDestructionAreNotSuccess() = runTest {
        val registry = OwnedStopRegistry()
        val request = registry.prepare(stopped(7, 0))
        val receipt = checkNotNull(registry.admit(request.id, 8, 1))
        assertEquals(MeowFlutterStopResult.TerminationUnconfirmed,
            ownedStopTerminalResult(receipt, 1, MeowFlutterStopSnapshot(8, 1, true, true), true, 1))
        registry.retireOwner(7)
        assertFalse(request.result.isCompleted)
        registry.retireOwner(8)
        assertEquals(MeowFlutterStopResult.TerminationUnconfirmed, request.result.await())
    }

    @Test fun actualFacadeWaitsForColdServiceReceiptNotGlobalOwnerChanges() = runTest {
        val registry = OwnedStopRegistry()
        var sent: OwnedStopRequest? = null
        val facade = MeowFlutterCommandFacade(OwnedStartRegistry(), { true }, { true }, { 0 }, {},
            { _, _, _ -> }, { error("unowned stop must not be used") },
            currentStopSnapshot = { stopped(7, 3) }, ownedStops = registry, dispatchOwnedStop = { sent = it })
        val result = async { facade.disconnectAndJoin(100) }
        runCurrent()
        val request = checkNotNull(sent)
        val receipt = checkNotNull(registry.admit(request.id, 8, 1))
        assertFalse(result.isCompleted)
        registry.complete(receipt, checkNotNull(ownedStopTerminalResult(receipt, 1, stopped(8, 1), true, 1)))
        assertEquals(MeowFlutterStopResult.Confirmed(8, 1), result.await())
    }

    @Test fun cancellingTheCallerDoesNotDetachTheAdmittedStopJoin() = runTest {
        val registry = OwnedStopRegistry()
        var sent: OwnedStopRequest? = null
        val facade = MeowFlutterCommandFacade(OwnedStartRegistry(), { true }, { true }, { 0 }, {},
            { _, _, _ -> }, {}, currentStopSnapshot = { stopped(7, 0) },
            ownedStops = registry, dispatchOwnedStop = { sent = it })
        val result = async { facade.disconnectAndJoin(100) }
        runCurrent()
        val request = checkNotNull(sent)
        val receipt = checkNotNull(registry.admit(request.id, 8, 1))
        result.cancel(); runCurrent()
        assertFalse(result.isCompleted)
        registry.complete(receipt, MeowFlutterStopResult.Confirmed(8, 1))
        result.join()
        assertTrue(result.isCancelled)
        assertNull(registry.admit(request.id, 8, 2))
    }

    @Test fun facadeTimeoutRetiresLateDeliveryAndNewStartSupersedesPendingStop() = runTest {
        val registry = OwnedStopRegistry()
        var sent: OwnedStopRequest? = null
        val facade = MeowFlutterCommandFacade(OwnedStartRegistry(), { true }, { true }, { 0 }, {},
            { _, _, _ -> }, {}, currentStopSnapshot = { stopped(7, 0) },
            ownedStops = registry, dispatchOwnedStop = { sent = it })
        val timed = async { facade.disconnectAndJoin(10) }
        runCurrent(); val id = checkNotNull(sent).id
        advanceTimeBy(10); runCurrent()
        assertEquals(MeowFlutterStopResult.TerminationUnconfirmed, timed.await())
        assertNull(registry.admit(id, 8, 1))
        val superseded = async { facade.disconnectAndJoin() }
        runCurrent()
        val old = checkNotNull(sent)
        assertNotNull(facade.prepareOwnedConnect())
        assertEquals(MeowFlutterStopResult.Superseded, superseded.await())
        assertNull(registry.admit(old.id, 8, 1))
    }
}
