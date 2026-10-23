package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MeowCompatibilityValidatorTest {
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
