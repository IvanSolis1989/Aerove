package dev.aerove.lite.dnssettings

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DnsPreferencesTest {
    private fun fails(code: DnsIssueCode, field: String? = null, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (error: DnsPreferencesException) {
            assertEquals(code, error.code)
            if (field != null) assertEquals(field, error.field)
        }
    }
    private fun dns(result: Map<String, Any?>) = result["dns"] as Map<*, *>

    @Test fun absentPreferencesAreInheritedAndExplicitNullIsCorruption() {
        assertEquals(DnsPreferences(), DnsPreferencesJson.decode(null))
        fails(DnsIssueCode.Type, "dnsPreferences") { DnsPreferencesJson.decode(JSONObject.NULL) }
        fails(DnsIssueCode.Type, "dnsPreferences.hosts") { DnsPreferencesJson.decode(JSONObject("""{"hosts":null}""")) }
    }

    @Test fun strictCodecRoundTripsSelectedDormantValuesOrderAndLegacyFallback() {
        val prefs = legacyDnsPreferences(false, listOf("tls://1.1.1.1", "dhcp://wlan0"), true)
            .withValue(DnsField.Fallback, DnsValue.Entries(listOf("tls://8.8.8.8", "tls://1.1.1.1", "tls://8.8.8.8")))
            .inherit(DnsField.Fallback).copy(hosts = linkedMapOf("+.example.org" to listOf("192.0.2.1")))
        assertEquals(prefs, DnsPreferencesJson.decode(JSONObject(DnsPreferencesJson.encode(prefs).toString())))
        assertFalse(DnsField.Fallback in prefs.overridden)
        assertEquals(3, (prefs.value(DnsField.Fallback) as DnsValue.Entries).value.size)
    }

    @Test fun wrongTypesUnknownFieldsAndDuplicateSelectionsNeverBecomeDefaults() {
        listOf("""{"values":null}""", """{"values":{"ipv6":"false"}}""", """{"overridden":[1]}""",
            """{"values":{"nameserver":"1.1.1.1"}}""", """{"hosts":{"x":"1.1.1.1"}}""").forEach {
            fails(DnsIssueCode.Type) { DnsPreferencesJson.decode(JSONObject(it)) }
        }
        fails(DnsIssueCode.UnknownField) { DnsPreferencesJson.decode(JSONObject("""{"future":true}""")) }
        fails(DnsIssueCode.UnknownField) { DnsPreferencesJson.decode(JSONObject("""{"values":{"future":true}}""")) }
        fails(DnsIssueCode.Duplicate) { DnsPreferencesJson.decode(JSONObject("""{"overridden":["ipv6","ipv6"]}""")) }
        fails(DnsIssueCode.Version) { DnsPreferencesJson.decode(JSONObject("""{"version":2}""")) }
    }

    @Test fun exactUnsupportedFieldsReportTheSourcePathInsteadOfBeingDiscarded() {
        listOf("prefer-h3", "fake-ip-range6", "fallback-filter.geosite", "direct-nameserver").forEach { field ->
            fails(DnsIssueCode.UnsupportedCore, "dns.$field") {
                DnsPreferencesJson.decode(JSONObject().put("values", JSONObject().put(field, false)))
            }
        }
    }

    @Test fun approvedBaselineIsOnlyAppliedToOriginalDisabledOrMissingDns() {
        val expected = DnsField.Nameserver.default as DnsValue.Entries
        assertEquals(expected.value, dns(transformDnsPreferences(emptyMap(), DnsPreferences()))["nameserver"])
        val raw = mapOf("dns" to mapOf("enable" to true, "nameserver" to listOf("1.1.1.1"), "enhanced-mode" to "normal"))
        assertEquals(raw, transformDnsPreferences(raw, DnsPreferences()))
        assertEquals("fake-ip", dns(transformDnsPreferences(mapOf("dns" to mapOf("enable" to false)), DnsPreferences()))["enhanced-mode"])
    }

    @Test fun globalNormalProfileUsesOnlyNormalKeysAndCustomUsesItsOwnSelectedKeys() {
        val global = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("1.1.1.1")))
            .withValue(DnsField.Enable, DnsValue.Bool(false)).withValue(DnsField.UseHosts, DnsValue.Bool(false))
        val profile = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("8.8.8.8")))
            .withValue(DnsField.Enable, DnsValue.Bool(false))
        val normal = dns(transformDnsPreferences(emptyMap(), global, profile))
        assertEquals(true, normal["enable"]); assertFalse(normal.containsKey("use-hosts"))
        assertEquals(listOf("1.1.1.1"), normal["nameserver"])
        val custom = dns(transformDnsPreferences(emptyMap(), global, profile, customProfile = true))
        assertEquals(false, custom["enable"]); assertEquals(listOf("8.8.8.8"), custom["nameserver"])
        fails(DnsIssueCode.InvalidValue, "profile.dnsPreferences.hosts") {
            transformDnsPreferences(emptyMap(), global, profile.copy(hosts = mapOf("a" to listOf("1.1.1.1"))), customProfile = true)
        }
    }

    @Test fun fallbackFilterMergesSubfieldsWhilePolicyAndListsReplaceAsWholeValues() {
        val raw = mapOf("dns" to mapOf("enable" to true, "fallback-filter" to mapOf("geoip" to true, "geoip-code" to "CN", "domain" to listOf("old")),
            "nameserver-policy" to mapOf("old.example" to "1.1.1.1")))
        val prefs = DnsPreferences().withValue(DnsField.FallbackGeoipCode, DnsValue.Text("US"))
            .withValue(DnsField.NameserverPolicy, DnsValue.Policy(linkedMapOf("+.example.org" to listOf("tls://8.8.8.8"))))
        val dns = dns(transformDnsPreferences(raw, DnsPreferences(), prefs, true))
        assertEquals(mapOf("geoip" to true, "geoip-code" to "US", "domain" to listOf("old")), dns["fallback-filter"])
        assertEquals(mapOf("+.example.org" to "tls://8.8.8.8"), dns["nameserver-policy"])
    }

    @Test fun explicitEmptyNormalListClearsButCustomEmptyAndNameserverEmptyAreUnset() {
        val raw = mapOf("dns" to mapOf("enable" to true, "fallback" to listOf("8.8.8.8"), "nameserver" to listOf("1.1.1.1")))
        val prefs = DnsPreferences().withValue(DnsField.Fallback, DnsValue.Entries(emptyList()))
            .withValue(DnsField.Nameserver, DnsValue.Entries(emptyList()))
        assertEquals(emptyList<String>(), dns(transformDnsPreferences(raw, prefs))["fallback"])
        assertEquals(listOf("1.1.1.1"), dns(transformDnsPreferences(raw, prefs))["nameserver"])
        assertEquals(raw, transformDnsPreferences(raw, DnsPreferences(), prefs, true))
    }

    @Test fun legacyOverrideAndMissingSourceFallbackRemainEquivalentToOldComposer() {
        val inherited = legacyDnsPreferences(false, listOf("9.9.9.9"), false)
        assertEquals(listOf("9.9.9.9"), dns(transformDnsPreferences(emptyMap(), inherited))["nameserver"])
        val source = mapOf("dns" to mapOf("enable" to true, "nameserver" to listOf("1.1.1.1")))
        assertEquals(listOf("1.1.1.1"), dns(transformDnsPreferences(source, inherited))["nameserver"])
        assertEquals(false, dns(transformDnsPreferences(source, inherited))["ipv6"])
        val forced = legacyDnsPreferences(true, listOf("9.9.9.9"), false)
        assertEquals(listOf("9.9.9.9"), dns(transformDnsPreferences(source, forced))["nameserver"])
    }

    @Test fun hostsMergePreservesUnrelatedSourceAndUsesActualSingleIpCoreRepresentation() {
        val source = mapOf("hosts" to mapOf("source.example" to "192.0.2.1", "same.example" to "192.0.2.2"))
        val prefs = DnsPreferences(hosts = linkedMapOf("same.example" to listOf("192.0.2.3"), "*.local" to listOf("::1")))
        assertEquals(mapOf("source.example" to "192.0.2.1", "same.example" to "192.0.2.3", "*.local" to "::1"),
            transformDnsPreferences(source, prefs)["hosts"])
        fails(DnsIssueCode.UnsupportedCore, "hosts.a") { DnsPreferences(hosts = mapOf("a" to listOf("1.1.1.1", "2.2.2.2"))).requireValid() }
        fails(DnsIssueCode.UnsupportedCore, "hosts.a") { DnsPreferences(hosts = mapOf("a" to listOf("alias.example"))).requireValid() }
    }

    @Test fun transformOwnsDeepSnapshotAndNeverDropsUnknownSourceForCompatibilityGate() {
        val servers = mutableListOf("1.1.1.1")
        val dns = linkedMapOf<String, Any?>("enable" to true, "nameserver" to servers, "unknown-field" to true)
        val source = linkedMapOf<String, Any?>("dns" to dns, "rules" to mutableListOf("MATCH,DIRECT"))
        val result = transformDnsPreferences(source, DnsPreferences())
        servers += "8.8.8.8"
        assertEquals(listOf("1.1.1.1"), dns(result)["nameserver"])
        assertEquals(true, dns(result)["unknown-field"])
        assertFalse(source.containsKey("hosts"))
        fails(DnsIssueCode.Type, "dns") { transformDnsPreferences(mapOf("dns" to null), DnsPreferences()) }
        fails(DnsIssueCode.Type, "hosts") { transformDnsPreferences(mapOf("hosts" to null), DnsPreferences()) }
    }

    @Test fun policyOneServerIsRealCoreMappingAndUnsupportedExpansionIsAddressed() {
        fails(DnsIssueCode.UnsupportedCore, "dns.nameserver-policy.a") {
            DnsPreferences().withValue(DnsField.NameserverPolicy, DnsValue.Policy(mapOf("a" to listOf("1.1.1.1", "8.8.8.8"))))
        }
        fails(DnsIssueCode.UnsupportedCore, "dns.nameserver-policy.geosite:cn") {
            DnsPreferences().withValue(DnsField.NameserverPolicy, DnsValue.Policy(mapOf("geosite:cn" to listOf("1.1.1.1"))))
        }
    }

    @Test fun resolverEntriesPreserveDhcpSystemProxyReferencesAndExactOrder() {
        val entries = listOf("dhcp://wlan0", "system://", "system", "https://dns.example/dns-query#proxy=Group", "tls://1.1.1.1")
        val prefs = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(entries))
        assertEquals(entries, dns(transformDnsPreferences(mapOf("proxy-groups" to listOf(mapOf("name" to "Group"))), prefs))["nameserver"])
        fails(DnsIssueCode.InvalidValue, "dns.default-nameserver[0]") {
            DnsPreferences().withValue(DnsField.DefaultNameserver, DnsValue.Entries(listOf("https://dns.example/dns-query")))
        }
    }

    @Test fun typedValidatorsRejectMalformedCidrPortsControlsAndOversizedStorage() {
        fails(DnsIssueCode.InvalidValue) { DnsPreferences().withValue(DnsField.FakeIpRange, DnsValue.Text("198.18.0.1/33")) }
        fails(DnsIssueCode.InvalidValue) { DnsPreferences().withValue(DnsField.Listen, DnsValue.Text("127.0.0.1:70000")) }
        fails(DnsIssueCode.InvalidValue) { DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("tls://1.1.1.1\n"))) }
        fails(DnsIssueCode.Type) { DnsPreferences().withValue(DnsField.Ipv6, DnsValue.Text("true")) }
        fails(DnsIssueCode.Size) { DnsPreferencesJson.decode(JSONObject().put("hosts", JSONObject().put("x", JSONArray(listOf("x".repeat(600000)))))) }
    }

    @Test fun fieldInheritanceRetainsDormantDraftWithoutApplyingIt() {
        val prefs = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("9.9.9.9"))).inherit(DnsField.Nameserver)
        assertEquals(listOf("9.9.9.9"), (prefs.value(DnsField.Nameserver) as DnsValue.Entries).value)
        assertEquals((DnsField.Nameserver.default as DnsValue.Entries).value, dns(transformDnsPreferences(emptyMap(), prefs))["nameserver"])
    }

    @Test fun redirHostHasAnEnumButNoActualFixedCoreBehaviorAndCannotBeSilentlyAccepted() {
        fails(DnsIssueCode.UnsupportedCore, "dns.enhanced-mode") {
            DnsPreferences().withValue(DnsField.EnhancedMode, DnsValue.Text("redir-host"))
        }
        val source = mapOf("dns" to mapOf("enable" to true, "enhanced-mode" to "redir-host"))
        fails(DnsIssueCode.UnsupportedCore, "dns.enhanced-mode") { transformDnsPreferences(source, DnsPreferences()) }
        val overridden = DnsPreferences().withValue(DnsField.EnhancedMode, DnsValue.Text("fake-ip"))
        assertEquals("fake-ip", dns(transformDnsPreferences(source, overridden))["enhanced-mode"])
    }

    @Test fun missingDnsProxyReferenceCannotSilentlyBecomeDirectAndRealProviderCatalogIsAccepted() {
        val prefs = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("https://1.1.1.1/dns-query#proxy=ProviderNode")))
        val compiled = transformDnsPreferences(emptyMap(), prefs)
        fails(DnsIssueCode.UnknownReference, "dns.nameserver[0]") { validateDnsOutboundReferences(compiled, emptySet()) }
        validateDnsOutboundReferences(compiled, setOf("ProviderNode"))
        assertEquals(listOf("https://1.1.1.1/dns-query#proxy=ProviderNode"),
            dns(compiled)["nameserver"])
        val overridden = mapOf("dns" to mapOf("enable" to true, "nameserver" to listOf("https://1.1.1.1/dns-query#Missing")))
        fails(DnsIssueCode.UnknownReference, "dns.nameserver[0]") { validateDnsOutboundReferences(transformDnsPreferences(overridden, DnsPreferences()), emptySet()) }
    }

    @Test fun singletonStandardHostsAndPolicyArraysNormalizeWithoutLosingSemantics() {
        val source = mapOf("hosts" to mapOf("example.org" to listOf("192.0.2.1")),
            "dns" to mapOf("enable" to true, "nameserver-policy" to mapOf("+.example.org" to listOf("1.1.1.1"))))
        val result = transformDnsPreferences(source, DnsPreferences())
        assertEquals(mapOf("example.org" to "192.0.2.1"), result["hosts"])
        assertEquals(mapOf("+.example.org" to "1.1.1.1"), dns(result)["nameserver-policy"])
        fails(DnsIssueCode.UnsupportedCore, "hosts.example.org") {
            transformDnsPreferences(mapOf("hosts" to mapOf("example.org" to listOf("1.1.1.1", "8.8.8.8"))), DnsPreferences())
        }
    }

    @Test fun fixedCoreCannotSilentlyDiscardCustomDohPathQueryUserInfoOrFragmentOptions() {
        listOf("https://dns.example/custom-query", "https://dns.example/dns-query?token=secret", "https://user:pass@dns.example/dns-query",
            "https://dns.example/dns-query#h3=true").forEach { server ->
            fails(DnsIssueCode.UnsupportedCore, "dns.nameserver[0]") {
                transformDnsPreferences(mapOf("dns" to mapOf("enable" to true, "nameserver" to listOf(server))), DnsPreferences())
            }
        }
        val actual = listOf("HTTPS://1.1.1.1/dns-query#proxy=DIRECT&interface=auto")
        assertEquals(actual, dns(transformDnsPreferences(mapOf("dns" to mapOf("enable" to true, "nameserver" to actual)), DnsPreferences()))["nameserver"])
    }
}
