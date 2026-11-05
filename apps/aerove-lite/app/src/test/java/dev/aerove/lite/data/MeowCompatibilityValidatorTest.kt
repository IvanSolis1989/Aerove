package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MeowCompatibilityValidatorTest {
    @Test fun sourceSelectionPolicyIsValidatedOnlyAtSourceAdmissionAndNeverInRuntime() {
        for (requested in listOf(true, false, null)) {
            val source = if (requested == null) mapOf<String, Any?>() else mapOf("profile" to mapOf("store-selected" to requested))
            val projected = MeowCompatibilityValidator.normalize(source, sourceAdmission = true)
            assertFalse(projected.containsKey("profile"))
            assertEquals(requested, (source["profile"] as? Map<*, *>)?.get("store-selected"))
            if (requested != null) {
                try { MeowCompatibilityValidator.normalize(source); fail("Unconsumed runtime policy accepted") }
                catch (error: ConfigValidationException) { assertEquals("$.profile", error.issues.first().path) }
            }
        }
        for ((value, path) in listOf(
            mapOf("unexpected" to true) to "$.profile.unexpected",
            mapOf("store-selected" to "true") to "$.profile.store-selected",
            mapOf("store-selected" to null) to "$.profile.store-selected",
            null to "$.profile")) {
            try { MeowCompatibilityValidator.normalize(mapOf("profile" to value), sourceAdmission = true); fail("Invalid source policy accepted") }
            catch (error: ConfigValidationException) { assertEquals(path, error.issues.first().path) }
        }
    }

    @Test fun normalizesAliasesWithoutMutatingSource() {
        val yaml = """
            proxies:
              - {name: test, type: vmess, server: example.com, port: 443, uuid: 12345678-1234-1234-1234-123456789012, tls: true, server-name: Example.COM, alter-id: 0}
        """.trimIndent()
        val source = ConfigYaml.parse(yaml)
        val result = normalize(source)
        val proxy = (result["proxies"] as List<*>)[0] as Map<*, *>
        assertEquals("Example.COM", proxy["servername"])
        assertFalse(proxy.containsKey("server-name"))
        assertTrue(((source["proxies"] as List<*>)[0] as Map<*, *>).containsKey("server-name"))
        assertEquals(true, ConfigYaml.parse(normalize(yaml))["strict"])
    }
    @Test fun rejectsAliasConflict() = rejects("$.proxies[0].server-name", vmess("tls: true, server-name: a, servername: b"))
    @Test fun rejectsLegacyAlterId() = rejects("$.proxies[0].alterId", vmess("alter-id: 1"))
    @Test fun rejectsZeroCipher() = rejects("$.proxies[0].cipher", vmess("cipher: zero"))
    @Test fun rejectsIgnoredWsHeader() = rejects("$.proxies[0].ws-opts.headers.Other", vmess("network: ws, ws-opts: {headers: {Other: value}}"))
    @Test fun rejectsIgnoredTlsOption() = rejects("$.proxies[0].servername", vmess("servername: example.com"))
    @Test fun rejectsUnknownNestedField() = rejects("$.dns.respect-rules", "dns: {respect-rules: true}")
    @Test fun rejectsNonMappingDnsListen() = rejects("$.dns.listen", "dns: {listen: {udp: '127.0.0.1:53'}}")
    @Test fun rejectsUnsupportedDnsScheme() = rejects("$.dns.nameserver[0]", "dns: {nameserver: ['quic://dns.example']}")
    @Test fun acceptsRedirHostAndPolicyLists() {
        val output = normalize("dns: {enhanced-mode: redir-host, nameserver-policy: {'example.com': ['tls://dns.example', 'https://dns.example/dns-query']}}")
        assertTrue(output.contains("redir-host"))
        assertTrue(output.contains("https://dns.example/dns-query"))
    }
    @Test fun acceptsUidAndInboundUserWithoutChangingCase() {
        val result = normalize("rules: ['UID,1234,DIRECT', 'IN-USER,AppCase,DIRECT']")
        assertTrue(result.contains("AppCase"))
    }
    @Test fun rejectsUnknownRootAndWarnings() {
        rejects("$.firewall", "firewall: true")
        rejects("$.tun.stack", "tun: {stack: system}")
        rejects("$.sniffer.force-dns-mapping", "sniffer: {force-dns-mapping: false}")
    }
    @Test fun trusttunnelIsPendingFeatureProofNotMissingCore() {
        try { MeowCompatibilityValidator.normalize("proxies: [{name: tt, type: trusttunnel}]"); fail() }
        catch (e: ConfigValidationException) { assertTrue(e.issues.single().reason.contains("Core supports trusttunnel")) }
    }
    @Test fun rejectsAbsentCoreProtocolAtExactPath() = rejects("$.proxies[0].type", "proxies: [{name: t, type: tuic}]")
    @Test fun rejectsMieruWithItsCoreLimitationWithoutDroppingOrMutatingNodes() {
        val source = ConfigYaml.parse("""
            proxies:
              - {name: direct, type: direct}
              - {name: synthetic-mieru, type: mieru, server: example.invalid, port: 443, username: synthetic, password: synthetic, transport: TCP, udp: true}
        """.trimIndent())
        val before = ConfigYaml.dump(source)
        for (sourceAdmission in listOf(true, false)) {
            try {
                MeowCompatibilityValidator.normalize(source, features, sourceAdmission)
                fail("Mieru must remain rejected by the fixed core")
            } catch (error: ConfigValidationException) {
                assertEquals("$.proxies[1].type", error.issues.single().path)
                assertEquals("No outbound implementation in fixed Meow v0.26.1: mieru", error.issues.single().reason)
            }
            assertEquals(before, ConfigYaml.dump(source))
        }
    }
    @Test fun unrecognizedProtocolsKeepTheirUnknownTypeDiagnostic() {
        try { normalize("proxies: [{name: synthetic, type: invented}]"); fail("Unknown protocol accepted") }
        catch (error: ConfigValidationException) {
            assertEquals("$.proxies[0].type", error.issues.single().path)
            assertEquals("Unknown proxy type or spelling", error.issues.single().reason)
        }
    }
    @Test fun rejectsWrongScalarTypes() {
        rejects("$.ipv6", "ipv6: yes")
        rejects("$.proxies[0].udp", "proxies: [{name: t, type: anytls, server: example.com, port: 443, password: secret, udp: 1}]")
    }
    private val features = setOf("ss", "vmess", "trojan", "anytls", "vless", "hysteria2", "snell", "trusttunnel", "trusttunnel-h3", "vless-vision", "vless-encryption", "mux")
    private fun normalize(yaml: String) = MeowCompatibilityValidator.normalize(yaml, features)
    private fun normalize(source: Map<String, Any?>) = MeowCompatibilityValidator.normalize(source, features)
    private fun vmess(extra: String) = "proxies: [{name: test, type: vmess, server: example.com, port: 443, uuid: 12345678-1234-1234-1234-123456789012, $extra}]"
    private fun rejects(path: String, yaml: String) {
        try { normalize(yaml); fail("Expected rejection at $path") }
        catch (e: ConfigValidationException) { assertEquals(path, e.issues.single().path) }
    }
}
