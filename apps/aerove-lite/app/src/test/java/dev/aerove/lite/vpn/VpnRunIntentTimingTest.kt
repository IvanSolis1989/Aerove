package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRunIntentTimingTest {
    private class Session : TunnelSession {
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        override val endpoint = CoreEndpoint(1234, "test")
        override suspend fun awaitReady() { ready.await() }
        override suspend fun awaitExit(): Unit = awaitCancellation()
        override suspend fun stop() { stopped.complete(Unit) }
    }

    @Test fun pendingStartAndReadyKeepTheClickTimeUntilTheIntentStops() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val sessions = mutableListOf<Session>()
        var now = 8_000_000_000L
        val coordinator = VpnCoordinator(scope, TunnelBackend { Session().also(sessions::add) }, nanoTime = { now })
        try {
            coordinator.start(VpnPlan("profile", LiteSettings()), requestedAtNanos = 5_000_000_000L)
            val starting = coordinator.state.value
            assertEquals(VpnPhase.Starting, starting.phase)
            assertEquals(5_000_000_000L, starting.uptimeStartNanos(true))
            assertEquals(0L, starting.uptimeStartNanos(false))
            now = 11_000_000_000L
            sessions.single().ready.complete(Unit)
            val ready = withTimeout(5_000) { coordinator.state.first { it.phase == VpnPhase.Running } }
            assertEquals(starting.runningSinceNanos, ready.runningSinceNanos)
            assertEquals(6_000L, (now - ready.uptimeStartNanos(true)) / 1_000_000)
            coordinator.reconnect()
            val reconnecting = coordinator.state.value
            assertEquals(VpnPhase.Starting, reconnecting.phase)
            assertEquals(ready.runningSinceNanos, reconnecting.runningSinceNanos)
            coordinator.stopAndJoin()
            assertTrue(sessions.all { it.stopped.isCompleted })
            assertEquals(0L, coordinator.state.value.uptimeStartNanos(true))
            now = 20_000_000_000L
            coordinator.start(VpnPlan("next", LiteSettings()))
            assertEquals(now, coordinator.state.value.uptimeStartNanos(true))
            coordinator.stopAndJoin()
            assertEquals(0L, coordinator.state.value.runningSinceNanos)
        } finally { coordinator.close(); scope.cancel() }
    }

    @Test fun failedPendingStartClearsTheClockAndTheNextStartGetsANewOrigin() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val session = Session()
        var fail = true
        var now = 4_000_000_000L
        val coordinator = VpnCoordinator(scope, TunnelBackend { if (fail) error("open failed") else session }, nanoTime = { now })
        try {
            coordinator.start(VpnPlan("profile", LiteSettings()))
            val failed = withTimeout(5_000) { coordinator.state.first { it.phase == VpnPhase.Failed } }
            assertEquals(0L, failed.runningSinceNanos)
            assertEquals(0L, failed.uptimeStartNanos(true))
            fail = false
            now = 9_000_000_000L
            coordinator.start(VpnPlan("profile", LiteSettings()))
            assertEquals(now, coordinator.state.value.uptimeStartNanos(true))
            coordinator.stopAndJoin()
            assertTrue(session.stopped.isCompleted)
        } finally { coordinator.close(); scope.cancel() }
    }
}
