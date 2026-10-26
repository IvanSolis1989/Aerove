package dev.aerove.lite.data

import dev.aerove.lite.*
import dev.aerove.lite.vpn.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ModeCleanupCoordinatorTest {
    @Test fun sameAddressReplacementIsNeverStoppedByOldEndpointAndOldJoinRemainsOwned() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val old = CoreEndpoint(1234, "same-secret"); val replacement = CoreEndpoint(1234, "same-secret")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var opens = 0; var stops = 0
        val coordinator = VpnCoordinator(scope, TunnelBackend {
            val sessionEndpoint = if (++opens == 1) old else replacement
            object : TunnelSession {
                override val endpoint = sessionEndpoint
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() { stops++; if (endpoint === old) { entered.complete(Unit); release.await() } }
            }
        })
        try {
            coordinator.start(VpnPlan("profile", LiteSettings()))
            withTimeout(3000) { while (coordinator.state.value.endpoint !== old) delay(5) }
            val stopping = async { coordinator.stopIfCurrent(old, "Compensation failed") }
            withTimeout(3000) { entered.await() }; assertFalse(stopping.isCompleted)
            coordinator.start(VpnPlan("profile", LiteSettings())); release.complete(Unit)
            assertTrue(withTimeout(3000) { stopping.await() })
            withTimeout(3000) { while (coordinator.state.value.endpoint !== replacement) delay(5) }
            assertFalse(coordinator.stopIfCurrent(old, "Obsolete cleanup"))
            assertEquals(VpnPhase.Running, coordinator.state.value.phase); assertSame(replacement, coordinator.state.value.endpoint)
            assertEquals(1, stops)
        } finally { release.complete(Unit); coordinator.close(); scope.cancel() }
    }
}
