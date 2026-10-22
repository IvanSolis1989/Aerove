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
import org.junit.Assert.assertNull
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
    fun replacingSessionWithdrawsItsEndpointBeforeWaitingForJoin() = runTest {
        val joined = CompletableDeferred<Unit>()
        val previous = object : TunnelSession {
            override val endpoint = CoreEndpoint(1234, "test-secret")
            override suspend fun awaitReady() = Unit
            override suspend fun awaitExit(): Unit = awaitCancellation()
            override suspend fun stop() { joined.await() }
        }
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            if (opened++ == 0) previous else Session().apply { ready() }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        assertEquals(VpnPhase.Starting, coordinator.state.value.phase)
        assertNull(coordinator.state.value.endpoint)
        runCurrent()
        assertEquals(1, opened)
        joined.complete(Unit)
        runCurrent()
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test
    fun cleanupFailureAfterJoinDoesNotBlockTheNextInstance() = runTest {
        val events = mutableListOf<String>()
        var opened = 0
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend {
            opened++
            object : TunnelSession {
                override val endpoint = CoreEndpoint(1234, "test-secret")
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() = stopNativeSession(
                    join = { events += "joined" },
                    cleanup = { error("disk cleanup failed") },
                    warn = { events += it.message.orEmpty() },
                )
            }
        })
        coordinator.start(VpnPlan("first", LiteSettings()))
        runCurrent()
        coordinator.start(VpnPlan("next", LiteSettings()))
        runCurrent()
        assertEquals(listOf("joined", "disk cleanup failed"), events)
        assertEquals(2, opened)
        assertEquals(VpnPhase.Running, coordinator.state.value.phase)
        coordinator.close()
    }

    @Test
    fun unconfirmedJoinPreservesResourcesAndReportsFailure() = runTest {
        var released = false
        var reported = false
        try {
            stopNativeSession(
                join = { error("join failed") },
                cleanup = { released = true },
                warn = { reported = true },
            )
            error("An unconfirmed join must fail")
        } catch (failure: IllegalStateException) {
            assertEquals("join failed", failure.message)
        }
        assertEquals(false, released)
        assertEquals(false, reported)
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
