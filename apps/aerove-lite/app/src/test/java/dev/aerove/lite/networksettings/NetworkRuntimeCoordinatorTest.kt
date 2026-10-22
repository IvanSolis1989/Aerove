package dev.aerove.lite.networksettings

import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.LiteSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkRuntimeCoordinatorTest {
    private val initial = NetworkSettings()
    private fun changed(port: Int) = initial.copy(ports = UserProxyPorts(port))

    @Test fun startupBaselineAndVpnOnlySettingsNeverApplyAgain() = runTest {
        var calls = 0
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ -> calls++ })
        val endpoint = CoreEndpoint(30000, "secret")
        coordinator.settings(initial, false)
        coordinator.bind(endpoint, initial, false)
        runCurrent()
        for (saved in listOf(initial.copy(systemProxy = false), initial.copy(allowBypass = false),
            initial.copy(bypassDomains = listOf("*.example")), initial.copy(authentication = ProxyAuthentication(false, "dormant", "saved")))) {
            coordinator.settings(saved, false)
            coordinator.bind(endpoint, initial, false)
            runCurrent()
            assertEquals(0, calls)
        }
        coordinator.settings(initial, true)
        runCurrent()
        assertEquals(0, calls)
        coordinator.close()
    }

    @Test fun fontScaleAndPureBlackSavesDoNotRepeatNativeRequests() = runTest {
        var calls = 0
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ -> calls++ })
        coordinator.bind(CoreEndpoint(30000, "secret"), initial, false)
        val base = LiteSettings(network = initial)
        for (saved in listOf(base, base.copy(fontFamily = "custom-font"), base.copy(textScaleEnabled = true, textScale = 1.2f),
            base.copy(pureBlack = true))) {
            coordinator.settings(saved.network, saved.ipv6)
            runCurrent()
        }
        assertEquals(0, calls)
        coordinator.close()
    }

    @Test fun reconnectBaselineUsesActualCoreProjectionRatherThanThePreservedBuilderPolicy() = runTest {
        var calls = 0
        val current = initial.copy(ports = UserProxyPorts(7900), allowLan = true,
            authentication = ProxyAuthentication(true, "latest", "password"))
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ -> calls++ })
        coordinator.settings(current, true)
        coordinator.bind(CoreEndpoint(30000, "new"), initial, false, appliedNetwork = current, appliedIpv6 = true)
        runCurrent()
        assertEquals(0, calls)
        coordinator.settings(current.copy(ports = UserProxyPorts(7901)), true)
        runCurrent()
        assertEquals(1, calls)
        coordinator.close()
    }

    @Test fun onlyEffectivePortsLanBindAndEnabledCredentialsTriggerApply() = runTest {
        val calls = mutableListOf<Pair<NetworkSettings, Boolean>>()
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, settings, ipv6 -> calls += settings to ipv6 })
        coordinator.bind(CoreEndpoint(30000, "secret"), initial, false)
        var saved = initial.copy(ports = UserProxyPorts(7890, http = 7910))
        coordinator.settings(saved, false); runCurrent()
        assertEquals(1, calls.size)
        saved = saved.copy(ports = saved.ports.copy(socks = 7920))
        coordinator.settings(saved, false); runCurrent()
        assertEquals(2, calls.size)
        saved = saved.copy(allowLan = true)
        coordinator.settings(saved, false); runCurrent()
        assertEquals(3, calls.size)
        coordinator.settings(saved, true); runCurrent()
        assertEquals(4, calls.size)
        saved = saved.copy(authentication = ProxyAuthentication(true, "account", "password"))
        coordinator.settings(saved, true); runCurrent()
        assertEquals(5, calls.size)
        assertEquals(saved to true, calls.last())
        coordinator.settings(saved.copy(systemProxy = false), true); runCurrent()
        assertEquals(5, calls.size)
        coordinator.close()
    }

    @Test fun rapidSavesFinishAcceptedApplyThenConvergeOnlyToLatest() = runTest {
        val held = CompletableDeferred<Unit>()
        val calls = mutableListOf<Int>()
        val states = mutableListOf<NetworkRuntimeStatus>()
        var active = 0
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, settings, _ ->
            assertEquals(0, active++)
            calls += settings.ports.mixed
            try { if (calls.size == 1) held.await() } finally { active-- }
        }, states::add)
        coordinator.bind(CoreEndpoint(30000, "secret"), initial, false)
        coordinator.settings(changed(7900), false)
        runCurrent()
        coordinator.settings(changed(7901), false)
        coordinator.settings(changed(7902), false)
        runCurrent()
        assertEquals(listOf(7900), calls)
        held.complete(Unit)
        runCurrent()
        assertEquals(listOf(7900, 7902), calls)
        assertEquals(changed(7902), states.last().appliedNetwork)
        assertFalse(states.last().applying)
        assertNull(states.last().error)
        coordinator.close()
    }

    @Test fun replacementCancelsAndJoinsOldCallBeforeNewEndpointApply() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val previous = CoreEndpoint(30000, "old")
        val next = CoreEndpoint(30000, "new")
        var oldCleaned = false
        val calls = mutableListOf<CoreEndpoint>()
        val states = mutableListOf<NetworkRuntimeStatus>()
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { endpoint, _, _ ->
            calls += endpoint
            if (endpoint === previous) try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanup.await(); oldCleaned = true }
            } else assertTrue(oldCleaned)
        }, states::add)
        coordinator.bind(previous, initial, false); coordinator.settings(changed(7900), false)
        runCurrent()
        coordinator.bind(next, initial, false)
        runCurrent()
        assertEquals(listOf(previous), calls)
        cleanup.complete(Unit); runCurrent()
        assertEquals(listOf(previous, next), calls)
        assertTrue(states.last().endpoint === next)
        assertFalse(states.dropWhile { it.endpoint !== next }.any { it.endpoint === previous })
        coordinator.close()
    }

    @Test fun failedApplyKeepsActualBaselineAndOnlyExplicitRetryReissuesIt() = runTest {
        var calls = 0
        val states = mutableListOf<NetworkRuntimeStatus>()
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ ->
            if (++calls == 1) error("listener bind failed")
        }, states::add)
        coordinator.bind(CoreEndpoint(30000, "secret"), initial, false)
        coordinator.settings(changed(7900), false); runCurrent()
        assertEquals(initial, states.last().appliedNetwork)
        assertEquals("listener bind failed", states.last().error)
        coordinator.settings(changed(7900), false); runCurrent()
        assertEquals(1, calls)
        coordinator.retry(); runCurrent()
        assertEquals(2, calls)
        assertEquals(changed(7900), states.last().appliedNetwork)
        assertNull(states.last().error)
        coordinator.close()
    }

    @Test fun detachWaitsForHostCleanupAndLateReceiptCannotPublish() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        val endpoint = CoreEndpoint(30000, "secret")
        val states = mutableListOf<NetworkRuntimeStatus>()
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ ->
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }, states::add)
        coordinator.bind(endpoint, initial, false); coordinator.settings(changed(7900), false)
        runCurrent()
        val count = states.size
        val stopped = launch { coordinator.detachAndJoin(endpoint) }
        runCurrent()
        assertFalse(stopped.isCompleted)
        cleanup.complete(Unit); runCurrent()
        assertTrue(stopped.isCompleted)
        assertEquals(count, states.size)
        coordinator.retry(); runCurrent()
        assertEquals(count, states.size)
        coordinator.close()
    }

    @Test fun closeJoinsCleanupAndNeverAcceptsNewWork() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = NetworkRuntimeCoordinator(backgroundScope, { _, _, _ ->
            calls++
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        })
        coordinator.bind(CoreEndpoint(30000, "secret"), initial, false); coordinator.settings(changed(7900), false)
        runCurrent()
        val closed = launch { coordinator.close() }; runCurrent()
        assertFalse(closed.isCompleted)
        cleanup.complete(Unit); runCurrent()
        assertTrue(closed.isCompleted)
        coordinator.bind(CoreEndpoint(30001, "new"), initial, false)
        coordinator.settings(changed(7901), false); coordinator.retry(); runCurrent()
        assertEquals(1, calls)
    }
}
