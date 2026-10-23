package dev.aerove.lite.networksettings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class VpnRoutePlanTest {
    private fun fails(code: NetworkIssueCode, field: String, block: () -> Unit) {
        try { block(); fail("Expected $field: $code") } catch (error: NetworkSettingsException) {
            assertTrue(error.issues.any { it.field == field && it.code == code })
        }
    }
    private fun routed(routes: List<String>, ip: String): Boolean {
        val address = InetAddress.getByName(ip).address
        return routes.any { cidr ->
            val base = InetAddress.getByName(cidr.substringBefore('/')).address
            val prefix = cidr.substringAfter('/').toInt()
            base.size == address.size && (0 until prefix).all { bit ->
                ((base[bit / 8].toInt() xor address[bit / 8].toInt()) and (1 shl (7 - bit % 8))) == 0
            }
        }
    }

    @Test fun missingFieldsMigrateToActualStandardDefaults() {
        val settings = NetworkSettingsJson.decode(JSONObject("""{"ports":{"mixed":7891}}"""))
        assertEquals(9000, settings.mtu); assertFalse(settings.dnsHijacking); assertTrue(settings.bypassPrivateRoute)
        assertTrue(settings.routeAddress.isEmpty()); assertTrue(settings.routeExcludeAddress.isEmpty())
        assertEquals(7891, settings.ports.mixed)
    }

    @Test fun codecPreservesExactStoredRouteOrderDuplicatesAndEveryNewField() {
        val original = NetworkSettings(mtu = 65535, dnsHijacking = true, bypassPrivateRoute = false,
            routeAddress = listOf("192.0.2.35/24", "::/0", "192.0.2.35/24"), routeExcludeAddress = listOf("192.0.2.1/32"))
        assertEquals(original, NetworkSettingsJson.decode(JSONObject(NetworkSettingsJson.encode(original).toString())))
    }

    @Test fun presentWrongTypesAndNullAreNotSilentlyReset() {
        mapOf("dnsHijacking" to "true", "bypassPrivateRoute" to 1, "mtu" to "9000", "routeAddress" to "0.0.0.0/0",
            "routeExcludeAddress" to JSONObject.NULL).forEach { (field, value) ->
            fails(NetworkIssueCode.Type, "network.$field") { NetworkSettingsJson.decode(JSONObject().put(field, value)) }
        }
        fails(NetworkIssueCode.Type, "network.routeAddress[0]") { NetworkSettingsJson.decode(JSONObject().put("routeAddress", JSONArray(listOf(1)))) }
        fails(NetworkIssueCode.Type, "network.mtu") { NetworkSettingsJson.decode(JSONObject().put("mtu", 1500.5)) }
    }

    @Test fun mtuRangeUsesIntegersWithExact1280And65535Bounds() {
        listOf(1280, 9000, 65535).forEach { NetworkSettings(mtu = it).requireValid() }
        listOf(0, -1, 1279, 65536).forEach { value ->
            fails(NetworkIssueCode.MtuRange, "network.mtu") { NetworkSettings(mtu = value).requireValid() }
        }
        fails(NetworkIssueCode.MtuRange, "network.mtu") { NetworkSettingsJson.decode(JSONObject().put("mtu", Long.MAX_VALUE)) }
    }

    @Test fun malformedRoutesRejectExactListIndexWithoutLookingUpAHostname() {
        listOf("example.org/24", "192.0.2.1/33", "fd00::/129", "192.0.2.1", "1.2.3.4/-1", "", " 1.2.3.4/24", "001.2.3.4/24").forEach { cidr ->
            fails(NetworkIssueCode.InvalidCidr, "network.routeAddress[0]") { NetworkSettings(routeAddress = listOf(cidr)).requireValid() }
        }
        fails(NetworkIssueCode.InvalidCidr, "network.routeExcludeAddress[1]") {
            NetworkSettings(routeExcludeAddress = listOf("::/0", "bad")).requireValid()
        }
    }

    @Test fun emptyIncludesWithoutExclusionsAreRealDefaultRoutesForEnabledFamilies() {
        val settings = NetworkSettings(bypassPrivateRoute = false)
        assertEquals(listOf("0.0.0.0/0"), planVpnRoutes(settings, false))
        val both = planVpnRoutes(settings, true)
        assertEquals(2, both.size); assertTrue(routed(both, "8.8.8.8")); assertTrue(routed(both, "2001:4860::8888"))
    }

    @Test fun subnetSubtractionCanonicalizesHostBitsOverlapsAndOrder() {
        val settings = NetworkSettings(bypassPrivateRoute = false,
            routeAddress = listOf("192.0.2.200/25", "192.0.2.35/24", "192.0.2.0/25"),
            routeExcludeAddress = listOf("192.0.2.64/26"))
        assertEquals(listOf("192.0.2.0/26", "192.0.2.128/25"), planVpnRoutes(settings, false))
    }

    @Test fun completelyExcludedFamilyMustNotFallBackToDefaultRoute() {
        val settings = NetworkSettings(bypassPrivateRoute = false, routeAddress = listOf("192.0.2.0/24", "2001:db8::/32"),
            routeExcludeAddress = listOf("0.0.0.0/0"))
        assertTrue(planVpnRoutes(settings, false).isEmpty())
        val result = planVpnRoutes(settings, true)
        assertFalse(routed(result, "192.0.2.1")); assertTrue(routed(result, "2001:db8::1"))
    }

    @Test fun privateBypassMatchesStandardPrivateCidrsAndKeepsFakeIpV4Routable() {
        val result = planVpnRoutes(NetworkSettings(), true)
        listOf("10.0.0.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "192.168.1.1", "224.0.0.1", "fd00::1", "fe80::1").forEach { assertFalse(it, routed(result, it)) }
        listOf("1.1.1.1", "198.18.0.1", "192.0.2.1", "2001:4860::8888").forEach { assertTrue(it, routed(result, it)) }
    }

    @Test fun reservedFakeIpRangeIsRemovedOnlyFromAutomaticPrivateBypass() {
        val settings = NetworkSettings(routeAddress = listOf("fd00::/8"))
        assertTrue(planVpnRoutes(settings, true).isEmpty())
        val reserved = planVpnRoutes(settings, true, listOf("fd00::/64"))
        assertTrue(routed(reserved, "fd00::123")); assertFalse(routed(reserved, "fd00:1::123"))
        assertTrue(planVpnRoutes(settings.copy(routeExcludeAddress = listOf("fd00::/64")), true, listOf("fd00::/64")).isEmpty())
    }

    @Test fun ipv6BoundaryArithmeticDoesNotOverflowOrInventIpv4Routes() {
        val settings = NetworkSettings(bypassPrivateRoute = false, routeAddress = listOf("::/127", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff/128"),
            routeExcludeAddress = listOf("::1/128"))
        val result = planVpnRoutes(settings, true)
        assertEquals(2, result.size)
        assertTrue(routed(result, "::")); assertFalse(routed(result, "::1"))
        assertTrue(routed(result, "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
        assertTrue(planVpnRoutes(settings, false).isEmpty())
    }

    @Test fun effectiveDiffUsesSemanticRoutesAndDormantIpv6CannotDemandRestart() {
        val settings = NetworkSettings(bypassPrivateRoute = false, routeAddress = listOf("192.0.2.0/24"))
        val reordered = settings.copy(routeAddress = listOf("192.0.2.128/25", "192.0.2.0/25", "192.0.2.1/24"),
            routeExcludeAddress = listOf("fd00::/8"))
        assertEquals(effectiveVpnNetworkOptions(settings, false), effectiveVpnNetworkOptions(reordered, false))
        assertNotEquals(effectiveVpnNetworkOptions(settings, true), effectiveVpnNetworkOptions(settings.copy(mtu = 1280), true))
        assertNotEquals(effectiveVpnNetworkOptions(settings, true), effectiveVpnNetworkOptions(settings.copy(dnsHijacking = true), true))
    }

    @Test fun proxyModeMakesAllTunPreferencesDormantAndDoesNotPolluteInboundHotApply() {
        val settings = NetworkSettings(vpnEnabled = false)
        val changed = settings.copy(mtu = 1280, dnsHijacking = true, bypassPrivateRoute = false,
            routeAddress = listOf("2001:db8::/32"), routeExcludeAddress = listOf("2001:db8:1::/48"))
        assertEquals(effectiveVpnNetworkOptions(settings, true), effectiveVpnNetworkOptions(changed, false))
        val plan = planNetwork(changed, 59333, false)
        assertFalse(plan.vpnEnabled); assertTrue(plan.routes.isEmpty())
        assertEquals(planNetwork(settings, 59333, true).userConfigFields(), plan.userConfigFields())
        val tun = planNetwork(changed.copy(vpnEnabled = true), 59333, true)
        assertEquals(1280, tun.mtu); assertTrue(tun.dnsHijacking); assertTrue(tun.routes.isNotEmpty())
    }

    @Test fun storedAndCachedRouteSnapshotsCannotBeMutatedByCallers() {
        val includes = mutableListOf("192.0.2.0/24")
        val settings = NetworkSettings(routeAddress = includes, bypassPrivateRoute = false)
        val snapshot = settings.snapshot()
        val first = planVpnRoutes(snapshot, false)
        includes.clear()
        assertEquals(listOf("192.0.2.0/24"), snapshot.routeAddress)
        runCatching { (first as? MutableList<String>)?.clear() }
        assertEquals(listOf("192.0.2.0/24"), planVpnRoutes(snapshot, false))
    }

    @Test fun manyDisjointIncludesAndExcludesRetainEveryExpectedAddress() {
        val settings = NetworkSettings(bypassPrivateRoute = false,
            routeAddress = (0..255).map { "192.0.2.$it/32" },
            routeExcludeAddress = (1..255 step 2).map { "192.0.2.$it/32" })
        val result = planVpnRoutes(settings, false)
        assertEquals(128, result.size)
        (0..255).forEach { assertEquals(it % 2 == 0, routed(result, "192.0.2.$it")) }
    }

    @Test fun noMihomoStackNameMayBeFalselyMappedToTheSingleFixedRustStack() {
        listOf("gvisor", "system", "mixed", "mips").forEach { stack ->
            fails(NetworkIssueCode.UnsupportedCore, "network.tunStack") { NetworkSettingsJson.decode(JSONObject().put("tunStack", stack)) }
        }
    }

    @Test fun actualPreparedPrivateFakeIpPoolFlowsThroughBuilderPlanAndEffectiveComparison() {
        val settings = NetworkSettings(routeAddress = listOf("10.0.0.0/8"))
        assertTrue(planNetwork(settings, 59333, false).routes.isEmpty())
        val actual = planNetwork(settings, 59333, false, listOf("10.0.1.0/24"))
        assertEquals(listOf("10.0.1.0/24"), actual.routes)
        assertEquals(actual.routes, effectiveVpnNetworkOptions(settings, false, listOf("10.0.1.0/24")).routes)
        assertTrue(planNetwork(settings.copy(routeExcludeAddress = listOf("10.0.1.0/24")), 59333, false, listOf("10.0.1.0/24")).routes.isEmpty())
    }
}
