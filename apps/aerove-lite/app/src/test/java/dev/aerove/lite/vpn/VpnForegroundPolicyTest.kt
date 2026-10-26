package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import org.junit.Assert.*
import org.junit.Test

class VpnForegroundPolicyTest {
    private fun vpn() = VpnSnapshot(phase = VpnPhase.Running, initialSettings = LiteSettings(),
        listening = true, initialDnsEnabled = true)
    @Test fun actualRunningVpnWithdrawsAppNotification() { assertFalse(vpnForegroundRequired(vpn())) }
    @Test fun parkedVpnKeepsSilentForegroundUntilActualTunResume() {
        assertTrue(vpnForegroundRequired(vpn().copy(suspended = true, listening = false)))
        assertTrue(vpnForegroundRequired(vpn().copy(initialDnsEnabled = null)))
        assertFalse(vpnForegroundRequired(vpn()))
    }
    @Test fun proxyAndBootstrapRetainForeground() {
        val base = LiteSettings()
        assertTrue(vpnForegroundRequired(vpn().copy(initialSettings = base.copy(network = base.network.copy(vpnEnabled = false)))))
        assertTrue(vpnForegroundRequired(VpnSnapshot(VpnPhase.Starting)))
    }
    @Test fun dnsPassthroughDoesNotRetainNotificationOnceTunIsReal() {
        assertFalse(vpnForegroundRequired(vpn().copy(initialDnsEnabled = false)))
    }
}
