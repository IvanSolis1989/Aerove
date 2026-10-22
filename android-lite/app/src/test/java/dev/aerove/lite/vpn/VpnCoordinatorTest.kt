package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnCoordinatorTest {
    private class Session(private val ready: CompletableDeferred<Unit> = CompletableDeferred()) : TunnelSession {
        override val endpoint = CoreEndpoint(1234, "test-secret")
        var stopped = false
        override suspend fun awaitReady() { ready.await() }
        override suspend fun awaitExit(): Unit = awaitCancellation()
        override suspend fun stop() { stopped = true }
        fun ready() { ready.complete(Unit) }
    }

    @Test
    fun stoppingAnUnreadyStartCannotPublishRunning() = runTest {
        val session = Session()
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { session })
        coordinator.start(VpnPlan("profile", LiteSettings()))
        runCurrent()
        assertEquals(VpnPhase.Starting, coordinator.state.value.phase)
        coordinator.stop()
        session.ready()
        runCurrent()
        assertEquals(VpnPhase.Stopped, coordinator.state.value.phase)
        assertTrue(session.stopped)
        coordinator.close()
    }

    @Test
    fun replacingProfileJoinsPreviousOwnerBeforeOpeningAnother() = runTest {
        val previous = Session().apply { ready() }
        val next = Session().apply { ready() }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) previous else {
                assertTrue(previous.stopped)
                next
            }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("second", LiteSettings()))
        runCurrent()
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        assertEquals(2, opened)
        coordinator.close()
        assertTrue(next.stopped)
    }

    @Test
    fun failedTerminationDoesNotPermitAnotherInstance() = runTest {
        var opened = 0
        val broken = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop(): Unit = error("join failed")
        }
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { opened++; broken })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        runCurrent()
        assertEquals(1, opened)
        assertEquals(VpnPhase.Failed, coordinator.state.value.phase)
        coordinator.close()
    }
}
