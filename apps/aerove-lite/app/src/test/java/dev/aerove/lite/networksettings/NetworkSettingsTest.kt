package dev.aerove.lite.networksettings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkSettingsTest {
    private fun fails(code: NetworkIssueCode, field: String? = null, operation: () -> Unit) {
        try { operation(); fail("Expected $code") } catch (error: NetworkSettingsException) {
            assertTrue(error.issues.any { it.code == code && (field == null || it.field == field) })
        }
    }

    @Test fun absentNetworkUsesExactStandardDefaults() {
        val settings = NetworkSettingsJson.decode(null)
        assertTrue(settings.systemProxy); assertTrue(settings.allowBypass); assertFalse(settings.allowLan)
        assertEquals(UserProxyPorts(7890, 0, 0), settings.ports)
        assertEquals(listOf("*zhihu.com", "*zhimg.com", "*jd.com", "100ime-iat-api.xfyun.cn", "*360buyimg.com",
            "localhost", "*.local", "127.*", "10.*", "172.16.*", "172.17.*", "172.18.*", "172.19.*", "172.2*", "172.30.*", "172.31.*", "192.168.*"), settings.bypassDomains)
    }

    @Test fun jsonRoundTripPreservesDormantCredentialsOrderAndWildcardDomains() {
        val original = NetworkSettings(systemProxy = false, allowBypass = false, allowLan = true,
            ports = UserProxyPorts(7891, 7892, 7893), bypassDomains = listOf("*.example.org", "127.*", "*.example.org"),
            authentication = ProxyAuthentication(false, "alice", "pass:with:colons"))
        assertEquals(original, NetworkSettingsJson.decode(JSONObject(NetworkSettingsJson.encode(original).toString())))
    }

    @Test fun strictJsonRejectsWrongTypesRatherThanCoercingOrResetting() {
        listOf(JSONObject.NULL, "{}", JSONArray(), 1).forEach { member -> fails(NetworkIssueCode.Type, "network") { NetworkSettingsJson.decode(member) } }
        listOf("""{"systemProxy":"true"}""", """{"allowLan":1}""", """{"ports":null}""",
            """{"ports":{"mixed":"7890"}}""", """{"ports":{"mixed":7890.5}}""", """{"authentication":{"enabled":null}}""",
            """{"bypassDomains":[1]}""", """{"bypassDomains":null}""").forEach { value -> fails(NetworkIssueCode.Type) { NetworkSettingsJson.decode(JSONObject(value)) } }
    }

    @Test fun unknownVersionAndFieldsAreExplicitErrors() {
        fails(NetworkIssueCode.Version, "network.version") { NetworkSettingsJson.decode(JSONObject("""{"version":2}""")) }
        fails(NetworkIssueCode.UnknownField, "network.secret") { NetworkSettingsJson.decode(JSONObject("""{"secret":"never-part-of-user-settings"}""")) }
        fails(NetworkIssueCode.UnknownField, "network.ports.controller") { NetworkSettingsJson.decode(JSONObject("""{"ports":{"controller":8080}}""")) }
    }

    @Test fun standardPortRangeDisabledOptionalPortsAndConflictsAreEnforced() {
        listOf(0, -1, 1023, 49152, 65535).forEach { port ->
            fails(NetworkIssueCode.PortRange, "network.ports.mixed") { NetworkSettings(ports = UserProxyPorts(port)).requireValid() }
        }
        NetworkSettings(ports = UserProxyPorts(1024, 0, 49151)).requireValid()
        fails(NetworkIssueCode.PortConflict) { NetworkSettings(ports = UserProxyPorts(7890, 7890)).requireValid() }
        fails(NetworkIssueCode.PortConflict) { NetworkSettings(ports = UserProxyPorts(7890, 8080, 8080)).requireValid() }
    }

    @Test fun enablingEmptyAuthenticationGeneratesStandardLengthsAndRetainsThemOnRetoggle() {
        val enabled = NetworkSettings().withAuthenticationEnabled(true)
        assertEquals(8, enabled.authentication.username.length); assertEquals(16, enabled.authentication.password.length)
        assertTrue(enabled.authentication.username.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })
        assertEquals(enabled.authentication, enabled.withAuthenticationEnabled(false).withAuthenticationEnabled(true).authentication)
        assertFalse(enabled.authentication.toString().contains(enabled.authentication.password))
        assertFalse(enabled.toString().contains(enabled.authentication.username))
    }

    @Test fun invalidAccountsAndControlCharactersNeverReachRuntimeOrLeakInErrors() {
        fails(NetworkIssueCode.EmptyAccount) { NetworkSettings(authentication = ProxyAuthentication(true)).requireValid() }
        fails(NetworkIssueCode.InvalidAccount) { NetworkSettings(authentication = ProxyAuthentication(true, "a:b", "secret")).requireValid() }
        val broken = NetworkSettings(authentication = ProxyAuthentication(true, "alice", "private\npassword"))
        try { broken.requireValid(); fail("Expected control rejection") } catch (error: NetworkSettingsException) {
            assertFalse(error.message!!.contains("private")); assertFalse(error.message!!.contains("password\n"))
        }
    }

    @Test fun allNativeControlRangesAreRejectedWithPreciseFieldsAndNoCredentialLeak() {
        listOf('\u0001', '\t', '\u001f', '\u007f', '\u0085', '\u009f').forEach { control ->
            listOf("username", "password").forEach { field ->
                val auth = if (field == "username") ProxyAuthentication(true, "private${control}account", "private-password")
                    else ProxyAuthentication(true, "private-account", "private${control}password")
                val original = NetworkSettings(authentication = auth)
                fails(NetworkIssueCode.CredentialControl, "network.authentication.$field") { original.requireValid() }
                try { NetworkSettingsJson.encode(original); fail("Control credential was saved") } catch (error: NetworkSettingsException) {
                    assertFalse(error.message!!.contains("private"))
                    assertFalse(error.message!!.contains(control))
                }
                val encoded = JSONObject().put("authentication", JSONObject().put("enabled", true)
                    .put("username", auth.username).put("password", auth.password))
                fails(NetworkIssueCode.CredentialControl, "network.authentication.$field") { NetworkSettingsJson.decode(encoded) }
            }
        }
    }

    @Test fun protocolByteLimitsAreReportedInsteadOfSilentlyTruncatingCredentials() {
        val longPassword = "中".repeat(86)
        fails(NetworkIssueCode.SocksCredentialLength, "network.authentication.password") {
            NetworkSettings(authentication = ProxyAuthentication(true, "alice", longPassword)).requireValid()
        }
        val valid = NetworkSettings(authentication = ProxyAuthentication(true, "alice", "x".repeat(255))).requireValid()
        assertEquals(255, valid.authentication.password.length)
    }

    @Test fun oversizedJsonAndUnsafeDomainEntriesAreBoundedAndNamed() {
        fails(NetworkIssueCode.EmptyDomain, "network.bypassDomains[0]") { NetworkSettings(bypassDomains = listOf(" ")).requireValid() }
        fails(NetworkIssueCode.DomainLength) { NetworkSettings(bypassDomains = listOf("a".repeat(513))).requireValid() }
        fails(NetworkIssueCode.DomainControl) { NetworkSettings(bypassDomains = listOf("a\nb")).requireValid() }
        fails(NetworkIssueCode.Size) { NetworkSettingsJson.decode(JSONObject().put("bypassDomains", JSONArray(List(400) { "a".repeat(512) }))) }
    }

    @Test fun effectiveSystemProxyIsSuppressedByActualAuthenticationWithoutChangingStoredToggle() {
        val settings = NetworkSettings(authentication = ProxyAuthentication(true, "alice", "pass:word"))
        val plan = planNetwork(settings, 59333, false)
        assertNull(plan.systemProxy); assertTrue(settings.systemProxy)
        assertEquals(listOf("alice:pass:word"), plan.userConfigFields()["authentication"])
        assertFalse(plan.toString().contains("pass:word"))
        assertNotNull(planNetwork(settings.copy(authentication = settings.authentication.copy(enabled = false)), 59333, false).systemProxy)
    }

    @Test fun defaultPlanUsesIndependentUserMixedListenerAndNoEphemeralDisabledPorts() {
        val plan = planNetwork(NetworkSettings(), 59333, false)
        assertEquals(listOf(UserInboundPlan(UserProxyProtocol.Mixed, 7890, "127.0.0.1", false)), plan.inbounds)
        assertEquals(VpnProxyPlan("127.0.0.1", 7890, defaultProxyBypassDomains), plan.systemProxy)
        assertFalse(plan.userConfigFields().containsKey("port")); assertFalse(plan.userConfigFields().containsKey("socks-port"))
        assertEquals(setOf("mixed-port", "allow-lan", "bind-address", "authentication"), plan.userConfigFields().keys)
        assertFalse(plan.userConfigFields().keys.any { it.startsWith("external-") || it == "secret" || it.startsWith("cors") })
    }

    @Test fun lanAndIpv6AffectOnlyUserBindAndPrivatePortCollisionFailsBeforeStartup() {
        val settings = NetworkSettings(allowLan = true, ports = UserProxyPorts(7890, 7891, 7892), allowBypass = false)
        assertEquals("0.0.0.0", planNetwork(settings, 59333, false).userConfigFields()["bind-address"])
        val v6 = planNetwork(settings, 59333, true)
        assertEquals("::", v6.userConfigFields()["bind-address"])
        assertEquals("127.0.0.1", v6.systemProxy!!.host)
        assertEquals(3, v6.inbounds.size); assertFalse(v6.allowBypass)
        fails(NetworkIssueCode.PortConflict, "network.ports") { planNetwork(settings, 7891, true) }
    }

    @Test fun runtimePlanOwnsASnapshotOfTheMutableInputDomainList() {
        val domains = mutableListOf("localhost")
        val plan = planNetwork(NetworkSettings(bypassDomains = domains), 59333, false)
        domains += "example.org"
        assertEquals(listOf("localhost"), plan.systemProxy!!.exclusions)
    }

    @Test fun effectiveVpnOptionsIgnoreDormantProxyFieldsAndChangeBackConverges() {
        val disabled = NetworkSettings(systemProxy = false)
        assertEquals(effectiveVpnNetworkOptions(disabled), effectiveVpnNetworkOptions(disabled.copy(
            ports = UserProxyPorts(8000, 8001, 8002), bypassDomains = listOf("example.org"), allowLan = true)))
        val running = NetworkSettings()
        val initial = effectiveVpnNetworkOptions(running)
        assertNotEquals(initial, effectiveVpnNetworkOptions(running.copy(allowBypass = false)))
        assertEquals(initial, effectiveVpnNetworkOptions(running.copy(allowBypass = false).copy(allowBypass = true)))
        assertEquals(initial, effectiveVpnNetworkOptions(running.copy(authentication = ProxyAuthentication(false, "dormant", "secret"))))
    }
}
