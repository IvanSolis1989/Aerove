package dev.aerove.lite.networksettings

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardLanSaveTest {
    private val saved = LiteSettings(network = NetworkSettings(allowLan = true, ports = UserProxyPorts(mixed = 8899)))
    private val initial = VpnSnapshot(phase = VpnPhase.Running, endpoint = CoreEndpoint(30000, "fixture"),
        appliedNetwork = saved.network.copy(allowLan = false), appliedIpv6 = false, sessionGeneration = 3, listening = true,
        networkRequest = NetworkRuntimeRequest(1, saved.network.copy(allowLan = false), false, NetworkRuntimeOutcome.Applied))
    private fun applied() = initial.copy(appliedNetwork = saved.network,
        networkRequest = NetworkRuntimeRequest(2, saved.network, saved.ipv6, NetworkRuntimeOutcome.Applied))
    private fun decide(runtime: VpnSnapshot, settings: LiteSettings? = saved, current: Boolean = true) =
        dashboardLanCommit(initial, runtime, saved, settings, current)

    @Test fun settingsAndBusyReadbackCannotStandInForTheRealCoreApply() {
        assertEquals(DashboardLanCommit.Wait, decide(initial))
        val applied = applied()
        assertEquals(DashboardLanCommit.Wait, decide(applied.copy(networkApplying = true)))
        assertEquals(DashboardLanCommit.Wait, decide(applied.copy(listening = false)))
        assertEquals(DashboardLanCommit.Applied, decide(applied))
        assertEquals(DashboardLanCommit.Wait, decide(applied.copy(appliedNetwork = saved.network.copy(ports = UserProxyPorts()))))
        assertEquals(DashboardLanCommit.Wait, decide(applied.copy(appliedIpv6 = true)))
    }

    @Test fun failedParkedStoppedChangedSessionAndSupersededIntentsCannotSucceed() {
        val applied = applied()
        for (runtime in listOf(applied.copy(networkError = "apply failed"), applied.copy(listenerError = "listener failed"),
            applied.copy(suspended = true), applied.copy(phase = VpnPhase.Stopped),
            applied.copy(endpoint = CoreEndpoint(30000, "replacement")), applied.copy(sessionGeneration = 4))) {
            assertEquals(DashboardLanCommit.Aborted, decide(runtime))
        }
        assertEquals(DashboardLanCommit.Aborted, decide(applied, current = false))
        assertEquals(DashboardLanCommit.Aborted, decide(applied, saved.copy(network = saved.network.copy(allowLan = false))))
        assertEquals(DashboardLanCommit.Aborted, decide(applied, saved.copy(network = saved.network.copy(ports = UserProxyPorts()))))
    }

    @Test fun stoppedSaveDoesNotRequireOrInventAListenerAndAConcurrentStartAbortsFeedback() {
        val stopped = VpnSnapshot()
        assertEquals(DashboardLanCommit.Applied, dashboardLanCommit(stopped, stopped, saved, saved, true))
        assertEquals(DashboardLanCommit.Aborted,
            dashboardLanCommit(stopped, stopped.copy(phase = VpnPhase.Starting), saved, saved, true))
    }

    @Test fun savedSettingsMayPrecedeTheActorsClearingOfAnOlderNetworkError() {
        val oldFailure = initial.copy(networkError = "previous bind failed")
        assertEquals(DashboardLanCommit.Wait, dashboardLanCommit(oldFailure, oldFailure, saved, saved, true))
        val applying = oldFailure.copy(networkApplying = true, networkError = null,
            networkRequest = NetworkRuntimeRequest(2, saved.network, saved.ipv6, NetworkRuntimeOutcome.Applying))
        assertEquals(DashboardLanCommit.Wait, dashboardLanCommit(oldFailure, applying, saved, saved, true))
        val applied = applying.copy(networkApplying = false, appliedNetwork = saved.network,
            networkRequest = applying.networkRequest!!.copy(outcome = NetworkRuntimeOutcome.Applied))
        assertEquals(DashboardLanCommit.Applied, dashboardLanCommit(oldFailure, applied, saved, saved, true))
    }

    @Test fun realActorOldErrorWaitsForNewAdoptionAndIdenticalFastFailureStillTerminates() = runTest {
        for (failNew in listOf(false, true)) {
            var runtime = initial.copy(networkRequest = null)
            var calls = 0
            val actor = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ ->
                if (++calls == 1 || failNew) error("same bind failed")
            }, { status -> runtime = runtime.copy(appliedNetwork = status.appliedNetwork, appliedIpv6 = status.appliedIpv6,
                networkApplying = status.applying, networkError = status.error, networkRequest = status.request) })
            actor.bind(initial.endpoint, initial.appliedNetwork, false)
            actor.settings(initial.appliedNetwork!!.copy(ports = UserProxyPorts(mixed = 7900)), false)
            runCurrent()
            val beforeEnable = runtime
            assertEquals("same bind failed", beforeEnable.networkError)
            actor.settings(saved.network, saved.ipv6)
            // Settings have been saved, but the actor's next() has not run yet.
            assertEquals(DashboardLanCommit.Wait, dashboardLanCommit(beforeEnable, runtime, saved, saved, true))
            runCurrent()
            // Inspect only the final snapshot: no applying frame needs to have been observed.
            assertTrue(runtime.networkRequest!!.id > beforeEnable.networkRequest!!.id)
            assertEquals(if (failNew) DashboardLanCommit.Aborted else DashboardLanCommit.Applied,
                dashboardLanCommit(beforeEnable, runtime, saved, saved, true))
            assertEquals(2, calls)
            actor.close()
        }
    }

    @Test fun matchingCachedFailureIsANewAdoptedTerminalResultWithoutRetryingTheCore() = runTest {
        var runtime = initial.copy(networkRequest = null)
        var calls = 0
        val actor = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ -> calls++; error("cached bind failed") },
            { status -> runtime = runtime.copy(appliedNetwork = status.appliedNetwork, appliedIpv6 = status.appliedIpv6,
                networkApplying = status.applying, networkError = status.error, networkRequest = status.request) })
        actor.bind(initial.endpoint, initial.appliedNetwork, false)
        actor.settings(saved.network, saved.ipv6); runCurrent()
        val beforeEnable = runtime
        actor.settings(saved.network, saved.ipv6)
        assertEquals(DashboardLanCommit.Wait, dashboardLanCommit(beforeEnable, runtime, saved, saved, true))
        runCurrent()
        assertEquals(1, calls)
        assertTrue(runtime.networkRequest!!.id > beforeEnable.networkRequest!!.id)
        assertEquals(DashboardLanCommit.Aborted, dashboardLanCommit(beforeEnable, runtime, saved, saved, true))
        actor.close()
    }

    @Test fun successIsPublishedOnlyAfterTheRealSuspendCommitReturns() = runBlocking {
        val emitted = mutableListOf<DashboardLanReceipt>(); val attempts = DashboardLanAttempts(emitted::add)
        val token = attempts.begin(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val task = launch { attempts.persist(token) { entered.complete(Unit); release.await(); 8899 } }
        entered.await(); assertTrue(emitted.isEmpty())
        release.complete(Unit); task.join()
        assertEquals(listOf(DashboardLanReceipt(token, 8899)), emitted)
    }

    @Test fun failuresCancellationAndSupersededCompletionNeverPublishSuccess() = runBlocking {
        for (failure in listOf(IOException("disk failed"), CancellationException("cancelled"))) {
            val emitted = mutableListOf<DashboardLanReceipt>(); val attempts = DashboardLanAttempts(emitted::add)
            val token = attempts.begin()
            val caught = try { attempts.persist(token) { throw failure }; null } catch (actual: Exception) { actual }
            assertSame(failure, caught); assertEquals(listOf(DashboardLanReceipt(token, null)), emitted)
        }
        val emitted = mutableListOf<DashboardLanReceipt>(); val attempts = DashboardLanAttempts(emitted::add)
        val old = attempts.begin(); val latest = attempts.begin()
        attempts.complete(old, 7890); attempts.complete(latest, 8899); attempts.complete(latest, 8899)
        assertEquals(listOf(DashboardLanReceipt(latest, 8899)), emitted)
        attempts.invalidate(); attempts.complete(latest, 8899)
        assertEquals(1, emitted.size)
    }
}
