package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.SystemDnsSnapshot
import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkChangeTest {
    private val dns = SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf("1.1.1.1", "2001:4860:4860::8888")))
    private fun running(enabled: Boolean, ipv6: Boolean = false) = VpnSnapshot(phase = VpnPhase.Running,
        initialSettings = LiteSettings(), initialDnsEnabled = enabled, initialIpv6 = ipv6)
    @Test fun disconnectedPhysicalNetworkKeepsSession() {
        assertEquals(NetworkChangeAction.None, networkChangeAction(running(true), emptyList(), "1", null, dns))
        assertFalse(networkHotFailureReconnect(running(true), null))
    }
    @Test fun customDnsCanSwitchHandleAndDnsInPlace() {
        assertEquals(NetworkChangeAction.HotOnly, networkChangeAction(running(true), listOf("198.18.0.2"), "1", "2", dns))
    }
    @Test fun builderDecisionUsesOnlyDnsActuallyInstalledAfterIpv6Filtering() {
        assertEquals(NetworkChangeAction.HotOnly, networkChangeAction(running(false), listOf("1.1.1.1"), "1", "2", dns))
        assertEquals(NetworkChangeAction.Reconnect, networkChangeAction(running(false), listOf("8.8.8.8"), "1", "2", dns))
        assertEquals(NetworkChangeAction.Reconnect, networkChangeAction(running(false, true), listOf("1.1.1.1"), "1", "2", dns))
        assertEquals(listOf("1.1.1.1"), physicalBuilderDns(dns, false))
    }
    @Test fun missingPhysicalDnsWaitsForPropertiesAndParkedSessionDoesNotReconnect() {
        assertEquals(NetworkChangeAction.None, networkChangeAction(running(false), listOf("1.1.1.1"), "1", "2", SystemDnsSnapshot()))
        assertEquals(NetworkChangeAction.None, networkChangeAction(running(true).copy(suspended = true), emptyList(), "1", "2", dns))
        assertFalse(networkHotFailureReconnect(running(true).copy(suspended = true), "2"))
    }
    @Test fun fallbackRequiresLiveRunningSession() {
        assertTrue(networkHotFailureReconnect(running(true), "2"))
        assertFalse(networkHotFailureReconnect(running(true).copy(phase = VpnPhase.Stopping), "2"))
    }
    @Test fun proxyModeNeverRebuildsTun() {
        val base = LiteSettings()
        val proxy = running(false).copy(initialSettings = base.copy(network = base.network.copy(vpnEnabled = false)))
        assertEquals(NetworkChangeAction.HotOnly, networkChangeAction(proxy, emptyList(), "1", "2", dns))
    }

    @Test fun onlyDnsRebuildAndLiveHotFailureReconnectOnce() = kotlinx.coroutines.runBlocking {
        var configured = 0; var reconnected = 0; var errors = 0
        suspend fun run(action: NetworkChangeAction, fail: Boolean = false, current: Boolean = true) =
            applyPhysicalNetworkChange(action, { current }, { configured++; if (fail) error("network") }, { reconnected++ }, { errors++ })
        run(NetworkChangeAction.None)
        assertEquals(0, configured); assertEquals(0, reconnected)
        run(NetworkChangeAction.HotOnly)
        assertEquals(1, configured); assertEquals(0, reconnected)
        run(NetworkChangeAction.Reconnect)
        assertEquals(1, configured); assertEquals(1, reconnected)
        run(NetworkChangeAction.HotOnly, fail = true)
        assertEquals(2, configured); assertEquals(2, reconnected); assertEquals(1, errors)
        run(NetworkChangeAction.HotOnly, fail = true, current = false)
        assertEquals(2, configured); assertEquals(2, reconnected)
    }
    @Test fun cancellationNeverTriggersFallbackReconnect() = kotlinx.coroutines.runBlocking {
        var reconnects = 0
        try {
            applyPhysicalNetworkChange(NetworkChangeAction.HotOnly, { true }, { throw kotlinx.coroutines.CancellationException("superseded") }, { reconnects++ }, { fail() })
            fail()
        } catch (_: kotlinx.coroutines.CancellationException) { assertEquals(0, reconnects) }
    }
}
