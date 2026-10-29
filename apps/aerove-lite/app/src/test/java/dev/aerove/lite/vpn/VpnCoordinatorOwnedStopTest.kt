package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnCoordinatorOwnedStopTest {
    private class Session(val ready: CompletableDeferred<Unit> = CompletableDeferred(Unit),
        val joined: CompletableDeferred<Unit> = CompletableDeferred(Unit)) : TunnelSession {
        override val endpoint = CoreEndpoint(1234, "owned-test-secret")
        var stopped = false
        override suspend fun awaitReady() { ready.await() }
        override suspend fun awaitExit(): Unit = awaitCancellation()
        override suspend fun stop() { joined.await(); stopped = true }
    }

    @Test fun cancellationBeforeCollectionPreventsNativeOpen() = runTest {
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opened++; Session() })
        coordinator.start(VpnPlan("old", LiteSettings()))
        val captured = coordinator.stopAndCapture()
        assertTrue(captured.isCompleted)
        captured.await(); runCurrent()
        assertEquals(0, opened)
        coordinator.close()
    }

    @Test fun nativeOpenMustFinishItsOwnJoinBeforeAcknowledgement() = runTest {
        val opening = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val session = Session(joined = joined)
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opening.await(); session })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        runCurrent()
        assertFalse(captured.isCompleted)
        opening.complete(Unit); runCurrent()
        assertFalse(captured.isCompleted)
        assertFalse(session.stopped)
        joined.complete(Unit); runCurrent()
        captured.await()
        assertTrue(session.stopped)
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test fun newerStartCanReplaceStopWithoutOwningItsAcknowledgement() = runTest {
        val joined = CompletableDeferred<Unit>()
        val old = Session(ready = CompletableDeferred(), joined = joined)
        val newer = Session()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { if (it.profileId == "old") old else newer })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        coordinator.start(VpnPlan("new", LiteSettings())); runCurrent()
        assertFalse(captured.isCompleted)
        joined.complete(Unit); runCurrent()
        captured.await()
        assertTrue(old.stopped)
        assertFalse(newer.stopped)
        assertSame(newer.endpoint, coordinator.state.value.endpoint)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test fun acknowledgementDoesNotWaitForTheNewerNativeOpen() = runTest {
        val oldJoin = CompletableDeferred<Unit>()
        val nextOpen = CompletableDeferred<Unit>()
        val old = Session(joined = oldJoin)
        val newer = Session()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (it.profileId == "old") old else { nextOpen.await(); newer }
        })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        coordinator.start(VpnPlan("new", LiteSettings())); runCurrent()
        oldJoin.complete(Unit); runCurrent()
        assertTrue(captured.isCompleted)
        captured.await()
        assertEquals(VpnPhase.Starting, coordinator.state.value.phase)
        assertFalse(newer.stopped)
        nextOpen.complete(Unit); runCurrent()
        assertSame(newer.endpoint, coordinator.state.value.endpoint)
        coordinator.close()
    }

    @Test fun unconfirmedStopIsReturnedAndStillBlocksANewerOpen() = runTest {
        var opened = 0
        val failed = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "owned-test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop(): Unit = error("native join failed")
        }
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opened++; failed })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        coordinator.start(VpnPlan("new", LiteSettings())); runCurrent()
        try { captured.await(); error("Failed join was acknowledged") }
        catch (expected: IllegalStateException) { assertEquals("native join failed", expected.message) }
        assertEquals(1, opened)
        assertEquals(VpnPhase.Failed, coordinator.state.value.phase)
        val repeated = coordinator.stopAndCapture()
        try { repeated.await(); error("Failed owner was forgotten") }
        catch (expected: IllegalStateException) { assertEquals("native join failed", expected.message) }
        coordinator.close()
    }

    @Test fun runningStopRetainsStrictEndpointIdentityAndTheSameExecutionBarrier() = runTest {
        val joined = CompletableDeferred<Unit>()
        val session = Session(joined = joined)
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { session })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        assertFalse(coordinator.stopIfCurrent(CoreEndpoint(session.endpoint.port, session.endpoint.secret), "unowned"))
        assertSame(session.endpoint, coordinator.state.value.endpoint)
        val stopped = async { coordinator.stopIfCurrent(session.endpoint, "owned") }
        runCurrent()
        val captured = coordinator.stopAndCapture()
        assertFalse(captured.isCompleted)
        joined.complete(Unit); runCurrent()
        assertTrue(stopped.await())
        captured.await()
        assertTrue(session.stopped)
        coordinator.close()
    }

    @Test fun openThatRetainsNativeResourcesAndThrowsCannotAcknowledgeTheirJoin() = runTest {
        val opening = CompletableDeferred<Unit>()
        val failure = IllegalStateException("owner superseded").apply {
            addSuppressed(IllegalStateException("native cleanup failed"))
        }
        var retained = false
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            retained = true
            opening.await()
            throw failure
        })
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        opening.complete(Unit); runCurrent()
        assertTrue(retained)
        try { captured.await(); error("Non-returning open falsely acknowledged join") }
        catch (expected: IllegalStateException) {
            assertSame(failure, expected)
            assertEquals("native cleanup failed", expected.suppressed.single().message)
        }
        val repeated = coordinator.stopAndCapture()
        try { repeated.await(); error("Completed failed open lost its resource evidence") }
        catch (expected: IllegalStateException) { assertSame(failure, expected) }
        coordinator.close()
    }

    @Test fun ordinaryOpenFailureRemainsUnconfirmedButDoesNotPermanentlyBlockANewStart() = runTest {
        var opened = 0
        val failure = IllegalArgumentException("configuration rejected")
        val session = Session()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) throw failure else session
        })
        coordinator.start(VpnPlan("invalid", LiteSettings())); runCurrent()
        val captured = coordinator.stopAndCapture()
        try { captured.await(); error("Failed open was reported as a successful resource join") }
        catch (expected: IllegalArgumentException) { assertSame(failure, expected) }
        coordinator.start(VpnPlan("valid", LiteSettings())); runCurrent()
        assertEquals(2, opened)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        assertSame(session.endpoint, coordinator.state.value.endpoint)
        val stopped = coordinator.stopAndCapture(); runCurrent()
        stopped.await()
        assertTrue(session.stopped)
        coordinator.close()
    }

    @Test fun successiveIdenticalFailuresKeepIdentityWhenStartingIsNotCollected() = runTest {
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { error("userListenerBindFailed") })
        coordinator.start(VpnPlan("first", LiteSettings())); runCurrent()
        val previous = coordinator.state.value
        assertEquals(VpnPhase.Failed, previous.phase)
        assertTrue(previous.sessionGeneration > 0)
        coordinator.start(VpnPlan("second", LiteSettings()))
        val queued = coordinator.state.value
        assertEquals(VpnPhase.Starting, queued.phase)
        assertTrue(queued.sessionGeneration > previous.sessionGeneration)
        runCurrent()
        // Subscribe only after the second terminal snapshot replaced Starting.
        val terminal = coordinator.state.first {
            it.sessionGeneration > previous.sessionGeneration && it.phase == VpnPhase.Failed
        }
        assertEquals(previous.error, terminal.error)
        assertEquals(queued.sessionGeneration, terminal.sessionGeneration)
        coordinator.close()
    }

    @Test fun stopAndCloseSnapshotsKeepTheirActualCoordinatorRevision() = runTest {
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { Session() })
        coordinator.start(VpnPlan("profile", LiteSettings())); runCurrent()
        val running = coordinator.state.value
        coordinator.stop()
        val stopping = coordinator.state.value
        assertEquals(VpnPhase.Stopping, stopping.phase)
        assertTrue(stopping.sessionGeneration > running.sessionGeneration)
        runCurrent()
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertEquals(stopping.sessionGeneration, coordinator.state.value.sessionGeneration)
        coordinator.close()
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertTrue(coordinator.state.value.sessionGeneration > stopping.sessionGeneration)
    }

    @Test fun oldCleanupFailureDoesNotBorrowTheNewerRequestedGeneration() = runTest {
        val joining = CompletableDeferred<Unit>()
        val failed = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "owned-test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop() { joining.await(); error("old cleanup failed") }
        }
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { failed })
        val snapshots = mutableListOf<VpnSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            coordinator.state.collect { snapshots += it }
        }
        coordinator.start(VpnPlan("old", LiteSettings())); runCurrent()
        val oldGeneration = coordinator.state.value.sessionGeneration
        coordinator.start(VpnPlan("new", LiteSettings()))
        val newerGeneration = coordinator.state.value.sessionGeneration
        assertTrue(newerGeneration > oldGeneration)
        joining.complete(Unit); runCurrent()
        val cleanup = snapshots.first { it.phase == VpnPhase.Failed && it.error == "old cleanup failed" }
        assertEquals(oldGeneration, cleanup.sessionGeneration)
        val newerFailure = coordinator.state.value
        assertEquals(VpnPhase.Failed, newerFailure.phase)
        assertEquals(newerGeneration, newerFailure.sessionGeneration)
        coordinator.close()
    }
}
