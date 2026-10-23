package dev.aerove.lite.vpn

import dev.aerove.lite.*
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.RuntimeConfigComposer
import dev.aerove.lite.networksettings.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeModeHostTest {
    private val proxy = LiteSettings(network = NetworkSettings(vpnEnabled = false))
    private val tun = LiteSettings(network = NetworkSettings(vpnEnabled = true))

    @Test fun proxyComposerDropsImportedTunFdRoutesAndPreservesEffectiveDnsListener() {
        val directory = Files.createTempDirectory("proxy-mode").toFile()
        try {
            val yaml = RuntimeConfigComposer(directory).compose(
                "tun: {enable: true, device-id: 'fd://42', gateway: 198.18.0.1/30}\ndns: {listen: '0.0.0.0:53'}",
                proxy.copy(network = proxy.network.copy(allowLan = true)), -1, 59000, "0123456789abcdef0123456789abcdef")
            val root = ConfigYaml.parse(yaml)
            assertEquals(mapOf("enable" to false), root["tun"])
            assertEquals(7890, root["mixed-port"])
            assertEquals(true, root["allow-lan"])
            assertFalse((root["dns"] as Map<*, *>).containsKey("enable"))
            assertEquals("0.0.0.0:53", (root["dns"] as Map<*, *>)["listen"])
        } finally { directory.deleteRecursively() }
    }

    @Test fun composerRequiresExactModeDescriptorAndSupportsPendingOppositeDesiredMode() {
        val directory = Files.createTempDirectory("explicit-mode").toFile()
        val composer = RuntimeConfigComposer(directory)
        val secret = "0123456789abcdef0123456789abcdef"
        try {
            assertThrows(IllegalArgumentException::class.java) { composer.compose("{}", proxy, 0, 59000, secret) }
            assertThrows(IllegalArgumentException::class.java) { composer.compose("{}", tun, -1, 59000, secret) }
            val result = ConfigYaml.parse(composer.compose("{}", proxy, 42, 59000, secret, NativeCore.RuntimeMode.Tun))
            assertEquals("fd://42", (result["tun"] as Map<*, *>)["device-id"])
            assertEquals(mapOf("enable" to false), ConfigYaml.parse(composer.compose("{}", tun, -1, 59000, secret,
                NativeCore.RuntimeMode.Proxy))["tun"])
        } finally { directory.deleteRecursively() }
    }

    @Test fun proxyStartNeverRequestsVpnPermissionAndSystemAlwaysOnNeverFallsBackToProxy() {
        assertTrue(canStartRuntime(false) { error("Proxy must not prepare VPN") })
        assertFalse(canStartRuntime(false, true) { error("Always-on cannot launch Proxy") })
        assertFalse(canStartRuntime(true) { false })
        assertTrue(canStartRuntime(true, true) { true })
    }

    @Test fun readinessRejectsEchoedModeWithoutActualOwnedApiListenersAndTun() {
        val endpoint = CoreEndpoint(59000, "secret")
        val ready = NativeCore.Status(NativeCore.State.Running, null, "127.0.0.1:59000", NativeCore.RuntimeMode.Proxy,
            tunReady = false, userListenersReady = true)
        requireRuntimeReady(ready, NativeCore.RuntimeMode.Proxy, endpoint)
        listOf(ready.copy(apiAddress = "127.0.0.1:59001"), ready.copy(runtimeMode = null),
            ready.copy(userListenersReady = false), ready.copy(tunReady = true), ready.copy(state = NativeCore.State.Starting)).forEach {
            assertThrows(IllegalStateException::class.java) { requireRuntimeReady(it, NativeCore.RuntimeMode.Proxy, endpoint) }
        }
        assertThrows(IllegalStateException::class.java) { requireRuntimeReady(ready.copy(runtimeMode = NativeCore.RuntimeMode.Tun), NativeCore.RuntimeMode.Tun, endpoint) }
        requireRuntimeReady(ready.copy(runtimeMode = NativeCore.RuntimeMode.Tun, tunReady = true), NativeCore.RuntimeMode.Tun, endpoint)
    }

    @Test fun proxyBuilderPreferencesAreDormantButModeTransitionRequiresExplicitRestart() {
        val state = VpnSnapshot(VpnPhase.Running, initialSettings = proxy)
        val dormant = proxy.copy(ipv6 = true, appFilterMode = AppFilterMode.Include, packages = setOf("other"),
            network = proxy.network.copy(systemProxy = false, allowBypass = false, bypassDomains = listOf("other")))
        assertFalse(requiresVpnRestart(dormant, state))
        assertTrue(requiresVpnRestart(tun, state))
        val runningTun = VpnSnapshot(VpnPhase.Running, initialSettings = tun)
        assertTrue(requiresVpnRestart(proxy, runningTun))
        assertTrue(requiresVpnRestart(tun.copy(ipv6 = true), runningTun))
        assertFalse(requiresVpnRestart(tun.copy(mode = ProxyMode.Global, overrideDns = true,
            dnsServers = listOf("1.1.1.1")), runningTun))
    }

    @Test fun implicitAndAutomaticReconfigurationPreserveCompleteActualModeAndBuilderBaseline() = runTest {
        val opened = mutableListOf<VpnPlan>()
        var saved = proxy
        val coordinator = VpnCoordinator(backgroundScope, TunnelBackend { plan ->
            opened += plan
            object : TunnelSession {
                override val endpoint = CoreEndpoint(59000, "secret")
                override suspend fun awaitReady() = Unit
                override suspend fun awaitExit(): Unit = awaitCancellation()
                override suspend fun stop() = Unit
            }
        }, latestSavedSettings = { saved })
        coordinator.start(VpnPlan("profile", proxy)); runCurrent()
        saved = tun.copy(ipv6 = true, appFilterMode = AppFilterMode.Include, packages = setOf("new.package"),
            network = tun.network.copy(ports = UserProxyPorts(9000), allowLan = true))
        coordinator.reconnect(); runCurrent()
        assertEquals(saved.network, opened.last().settings.network)
        assertEquals(proxy, opened.last().builderSettings())
        assertEquals(NativeCore.RuntimeMode.Proxy, runtimeMode(opened.last()))
        coordinator.reconfigure(VpnPlan("profile", saved.copy(mode = ProxyMode.Global))); runCurrent()
        assertEquals(ProxyMode.Global, opened.last().settings.mode)
        assertEquals(proxy, coordinator.state.value.initialSettings)
        assertTrue(requiresVpnRestart(saved, coordinator.state.value))
        coordinator.start(VpnPlan("profile", saved)); runCurrent()
        assertEquals(saved, coordinator.state.value.initialSettings)
        assertEquals(NativeCore.RuntimeMode.Tun, runtimeMode(opened.last()))
        assertFalse(requiresVpnRestart(saved, coordinator.state.value))
        coordinator.close()
    }
}
