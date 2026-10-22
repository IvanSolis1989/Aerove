package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.networksettings.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AndroidTunnelNetworkPlanTest {
    private val baseline = NetworkSettings(allowLan = true, ports = UserProxyPorts(7890, 7891, 7892))
    private val latest = NetworkSettings(allowBypass = false, allowLan = false, ports = UserProxyPorts(9000, 9001, 9002),
        authentication = ProxyAuthentication(true, "new-account", "new-password"))
    private fun implicitPlan() = VpnPlan("profile", LiteSettings(network = latest, ipv6 = true), baseline, false)

    @Test fun implicitReconnectPreservesEstablishedBuilderPolicyWhileCoreSettingsRemainLatest() {
        val plan = implicitPlan()
        val policy = vpnBuilderNetworkPlan(plan, 59000)
        assertEquals(7890, policy.systemProxy!!.port)
        assertTrue(policy.allowBypass)
        assertFalse(vpnBuilderIpv6(plan))
        assertEquals("0.0.0.0", policy.inbounds.first().bindAddress)
        assertEquals(latest, plan.settings.network)
        assertEquals(9000, planNetwork(plan.settings.network, 59000, plan.settings.ipv6).userConfigFields()["mixed-port"])
        assertEquals(listOf("new-account:new-password"), planNetwork(plan.settings.network, 59000, true).userConfigFields()["authentication"])
        assertTrue(plan.settings.ipv6)
    }

    @Test fun explicitStartWithoutBaselinesUsesTheLatestUserIntent() {
        val plan = VpnPlan("profile", LiteSettings(network = latest, ipv6 = true))
        assertNull(vpnBuilderNetworkPlan(plan, 59000).systemProxy)
        assertFalse(vpnBuilderNetworkPlan(plan, 59000).allowBypass)
        assertTrue(vpnBuilderIpv6(plan))
    }

    @Test fun pendingAuthenticationDisableDoesNotEnableSystemProxyDuringImplicitReconnect() {
        val old = latest
        val saved = baseline.copy(authentication = ProxyAuthentication())
        val plan = VpnPlan("profile", LiteSettings(network = saved), old)
        assertNull(vpnBuilderNetworkPlan(plan, 59000).systemProxy)
        assertTrue(planNetwork(saved, 59000, false).systemProxy != null)
    }

    private class Reservation(override val port: Int, private val closeFails: Boolean = false) : BoundControlPort {
        var closed = false
        override fun close() { closed = true; if (closeFails) throw IOException("close failed") }
    }

    @Test fun privateApiSelectionExcludesLatestInboundsAndTheStillEstablishedProxyPolicy() {
        val reservations = listOf(7890, 9000, 7891, 9001, 7892, 9002, 59000).map(::Reservation)
        var index = 0
        val selected = selectTunnelControlPort(implicitPlan()) {
            assertTrue(reservations.take(index).none { it.closed })
            reservations[index++]
        }
        assertEquals(59000, selected)
        assertEquals(7, index)
        assertTrue(reservations.all { it.closed })
    }

    @Test fun failedSelectionReleasesBothBaselineAndLatestReservationsWithoutMaskingBindError() {
        val old = Reservation(7890, true); val current = Reservation(9000, true)
        var index = 0
        val error = assertThrows(IOException::class.java) {
            selectTunnelControlPort(implicitPlan()) {
                when (index++) { 0 -> old; 1 -> current; else -> throw IOException("original bind failed") }
            }
        }
        assertEquals("original bind failed", error.message)
        assertTrue(old.closed); assertTrue(current.closed)
        assertEquals(2, error.suppressed.size)
    }
}
