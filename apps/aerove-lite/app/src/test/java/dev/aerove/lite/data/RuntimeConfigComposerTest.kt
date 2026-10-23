package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.requests.RequestPreferences
import dev.aerove.lite.requests.productUserAgent
import org.json.JSONObject
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.NetworkSettingsException
import dev.aerove.lite.networksettings.ProxyAuthentication
import dev.aerove.lite.networksettings.UserProxyPorts
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeConfigComposerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun compose(source: String, settings: LiteSettings = LiteSettings()) =
        RuntimeConfigComposer(temporary.root).compose(source, settings, 42, 49152, secret)

    @Test fun savedProcessModeOwnsStartupIncludingInitiallyParkedTun() {
        val composer = RuntimeConfigComposer(temporary.root)
        listOf(dev.aerove.lite.FindProcessMode.Always, dev.aerove.lite.FindProcessMode.Off, dev.aerove.lite.FindProcessMode.Strict).forEach { mode ->
            listOf(false, true).forEach { parked ->
                val root = ConfigYaml.parse(composer.compose("find-process-mode: strict", LiteSettings(findProcessMode = mode),
                    if (parked) -1 else 42, 49152, secret, initiallyParked = parked))
                assertEquals(mode.wireName, root["find-process-mode"])
                assertEquals("127.0.0.1:49152", root["external-controller"])
            }
        }
        val defaults = ConfigYaml.parse(compose("find-process-mode: off"))
        assertEquals("always", defaults["find-process-mode"])
    }

    @Test fun savedGlobalProbeDialPolicyOverridesImportedValuesIncludingFalse() {
        val source = "unified-delay: true\ntcp-concurrent: true\nexternal-controller: 0.0.0.0:9090\nsecret: imported"
        val root = ConfigYaml.parse(compose(source, LiteSettings(unifiedDelay = false, tcpConcurrent = false)))
        assertEquals(false, root["unified-delay"])
        assertEquals(false, root["tcp-concurrent"])
        assertEquals("127.0.0.1:49152", root["external-controller"])
        assertEquals(secret, root["secret"])
        val defaults = ConfigYaml.parse(compose("unified-delay: false\ntcp-concurrent: false"))
        assertEquals(true, defaults["unified-delay"])
        assertEquals(true, defaults["tcp-concurrent"])
    }

    @Test fun initiallyParkedTunHasNoDescriptorAndCannotAcceptAnAlreadyEstablishedTunnel() {
        val composer = RuntimeConfigComposer(temporary.root)
        val result = ConfigYaml.parse(composer.compose("tun: {device-id: fd://99}", LiteSettings(), -1, 49152, secret,
            initiallyParked = true))
        val tun = result["tun"] as Map<*, *>
        assertEquals(true, tun["enable"])
        assertFalse(tun.containsKey("device-id"))
        assertThrows(IllegalArgumentException::class.java) {
            composer.compose("{}", LiteSettings(), 42, 49152, secret, initiallyParked = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            composer.compose("{}", LiteSettings(), -1, 49152, secret)
        }
    }

    @Test fun serviceOwnsControllerTunAndAllInboundShortcuts() {
        val source = """
            external-controller: 0.0.0.0:9090
            secret: imported-secret
            external-ui: public
            external-controller-unix: /tmp/untrusted.sock
            http_port: 8080
            mixed-port: 7890
            allow-lan: true
            tun: {enable: false, device: fd://99, mtu: 9000}
            dns: {enable: true}
            rules: ["MATCH,DIRECT"]
        """.trimIndent()
        val result = ConfigYaml.parse(compose(source, LiteSettings(network = NetworkSettings(dnsHijacking = true, mtu = 1400))))
        assertEquals("127.0.0.1:49152", result["external-controller"])
        assertEquals(secret, result["secret"])
        assertEquals(false, result["allow-lan"])
        assertEquals(7890, result["mixed-port"])
        listOf("external-ui", "external-controller-unix", "http_port", "port", "socks-port", "redir-port", "tproxy-port").forEach {
            assertFalse("$it must be absent (port zero still creates a listener)", result.containsKey(it))
        }
        val tun = result["tun"] as Map<*, *>
        assertEquals(true, tun["enable"])
        assertEquals("fd://42", tun["device-id"])
        assertEquals("198.18.0.1/30", tun["gateway"])
        assertEquals(1400, tun["mtu"])
        assertEquals(true, tun["dns-hijack"])
        assertFalse(tun.containsKey("device"))
        assertFalse(tun.containsKey("gateway-v6"))
    }

    @Test fun governedUserInboundsAndAuthenticationNeverReplacePrivateController() {
        val network = NetworkSettings(allowLan = true, ports = UserProxyPorts(7890, 7891, 7892),
            authentication = ProxyAuthentication(true, "alice", "password:with:colons"))
        val result = ConfigYaml.parse(compose("""
            port: 9091
            socks-port: 9092
            mixed-port: 9093
            authentication: [imported:password]
            bind-address: 192.0.2.1
            external-controller: 0.0.0.0:9090
            secret: imported-secret
            cors-allow-origins: ['*']
        """.trimIndent(), LiteSettings(network = network, ipv6 = true)))
        assertEquals(7890, result["mixed-port"]); assertEquals(7891, result["port"]); assertEquals(7892, result["socks-port"])
        assertEquals(true, result["allow-lan"]); assertEquals("::", result["bind-address"])
        assertEquals(listOf("alice:password:with:colons"), result["authentication"])
        assertEquals("127.0.0.1:49152", result["external-controller"]); assertEquals(secret, result["secret"])
        assertEquals(emptyList<String>(), result["cors-allow-origins"])
    }

    @Test fun privatePortCollisionAndInvalidUserPortsFailBeforeRuntimeOutput() {
        assertThrows(NetworkSettingsException::class.java) {
            RuntimeConfigComposer(temporary.root).compose("{}", LiteSettings(), 42, 7890, secret)
        }
        assertThrows(NetworkSettingsException::class.java) {
            compose("{}", LiteSettings(network = NetworkSettings(ports = UserProxyPorts(7890, 7890))))
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun effectivePipelineDnsSurvivesLegacySettingsAndControlsTunHijack() {
        val source = """
            dns:
              enable: false
              ipv6: false
              enhanced-mode: fake-ip
              fake-ip-filter: ["*.lan"]
              nameserver: [8.8.8.8]
              nameserver-policy: {example.com: 'tls://1.0.0.1'}
              fallback: [9.9.9.9]
              default-nameserver: [1.1.1.1]
              listen: 0.0.0.0:53
        """.trimIndent()
        val settings = LiteSettings(mode = ProxyMode.Global, ipv6 = true, overrideDns = true, dnsServers = listOf("https://1.1.1.1/dns-query"))
        val result = ConfigYaml.parse(compose(source, settings))
        val dns = result["dns"] as Map<*, *>
        assertEquals(listOf("8.8.8.8"), dns["nameserver"])
        assertEquals("fake-ip", dns["enhanced-mode"])
        assertEquals(listOf("*.lan"), dns["fake-ip-filter"])
        assertEquals(mapOf("example.com" to "tls://1.0.0.1"), dns["nameserver-policy"])
        assertEquals(listOf("9.9.9.9"), dns["fallback"])
        assertEquals(listOf("1.1.1.1"), dns["default-nameserver"])
        assertEquals("0.0.0.0:53", dns["listen"])
        assertEquals(false, dns["enable"])
        assertEquals(false, (result["tun"] as Map<*, *>)["dns-hijack"])
        assertEquals(false, dns["ipv6"])
        assertEquals("global", result["mode"])
        assertEquals("fdfe:dcba:9876::1/126", (result["tun"] as Map<*, *>)["gateway-v6"])
    }

    @Test fun composerPreservesPipelineDnsWithoutInventingASecondDefault() {
        val source = """
            dns:
              nameserver: [8.8.8.8]
              nameserver-policy: {example.com: 'tls://1.0.0.1'}
              fallback: [9.9.9.9]
        """.trimIndent()
        val dns = ConfigYaml.parse(compose(source))["dns"] as Map<*, *>
        assertEquals(listOf("8.8.8.8"), dns["nameserver"])
        assertEquals(mapOf("example.com" to "tls://1.0.0.1"), dns["nameserver-policy"])
        assertEquals(listOf("9.9.9.9"), dns["fallback"])
        assertFalse(ConfigYaml.parse(compose("{}")).containsKey("dns"))
        assertFalse(effectiveProfileDnsEnabled("{}"))
        assertFalse(effectiveProfileDnsEnabled(source))
        assertTrue(effectiveProfileDnsEnabled("dns: {enable: true}"))
    }

    @Test fun implicitCoreReconfigurationUsesActualBuilderTunOptionsWithoutApplyingPendingOptions() {
        val saved = LiteSettings(ipv6 = true, network = NetworkSettings(mtu = 9000, dnsHijacking = true, allowLan = true))
        val result = ConfigYaml.parse(RuntimeConfigComposer(temporary.root).compose("dns: {enable: true}", saved, 42, 49152, secret,
            tunOptions = TunRuntimeOptions(1400, false, false)))
        assertEquals(true, result["ipv6"])
        assertEquals(true, result["allow-lan"])
        assertEquals("::", result["bind-address"])
        val tun = result["tun"] as Map<*, *>
        assertEquals(1400, tun["mtu"])
        assertEquals(false, tun["dns-hijack"])
        assertFalse(tun.containsKey("gateway-v6"))
    }

    @Test fun tunPolicyUsesUserMtuAndSyntheticOnlyUnlessDnsHijackingIsEnabled() {
        fun tun(source: String, network: NetworkSettings) = ConfigYaml.parse(compose(source, LiteSettings(network = network)))["tun"] as Map<*, *>
        val enabled = "dns: {enable: true}"
        assertEquals(9000, tun(enabled, NetworkSettings())["mtu"])
        assertEquals(false, tun(enabled, NetworkSettings())["dns-hijack"])
        assertEquals(true, tun(enabled, NetworkSettings(dnsHijacking = true))["dns-hijack"])
        assertEquals(false, tun("dns: {enable: false}", NetworkSettings(dnsHijacking = true))["dns-hijack"])
        assertEquals(1280, tun(enabled, NetworkSettings(mtu = 1280))["mtu"])
    }

    @Test fun realRequestPolicyOverridesImportedUaAndDownloadTlsWithoutTouchingNodeTls() {
        val defaultUa = productUserAgent("AEROVE Lite", "1.2.3")
        val composer = RuntimeConfigComposer(temporary.root, defaultUa)
        val source = """
            global-ua: imported
            check-certificate: false
            proxies: [{name: tls, type: socks5, server: example.com, port: 443, tls: true, skip-cert-verify: false}]
        """.trimIndent()
        val normal = ConfigYaml.parse(composer.compose(source, LiteSettings(), 42, 49152, secret))
        assertEquals(defaultUa, normal["global-ua"])
        assertEquals(true, normal["check-certificate"])
        val selected = ConfigYaml.parse(composer.compose(source,
            LiteSettings(requests = RequestPreferences(globalUserAgent = "Custom-UA/2", checkCertificate = false)), 42, 49152, secret))
        assertEquals("Custom-UA/2", selected["global-ua"])
        assertEquals(false, selected["check-certificate"])
        assertFalse(selected.containsKey("skip-cert-verify"))
        assertEquals(false, ((selected["proxies"] as List<*>).single() as Map<*, *>)["skip-cert-verify"])
    }

    @Test fun systemDnsUsesOnlyValidatedPhysicalSnapshotAndReplacesImportedPrivateData() {
        val snapshot = SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf("192.168.1.1", "fe80::1%wlan0", "fe80::2%12"), "rmnet0" to listOf("10.0.0.1")))
        val result = ConfigYaml.parse(RuntimeConfigComposer(temporary.root).compose(
            "aerove-system-dns: {defaultInterface: evil, interfaces: {evil: [8.8.8.8]}}", LiteSettings(), 42, 49152, secret,
            systemDnsSnapshot = snapshot))
        assertEquals(snapshot.config(), result["aerove-system-dns"])
        assertEquals("wlan0", JSONObject(snapshot.json()).getString("defaultInterface"))
        assertEquals("192.168.1.1", JSONObject(snapshot.json()).getJSONObject("interfaces").getJSONArray("wlan0").getString(0))
        assertEquals(SystemDnsSnapshot().config(), ConfigYaml.parse(compose("{}"))["aerove-system-dns"])
        listOf("resolver.example", "0.0.0.0", "::", "224.0.0.1", "ff02::1", "192.168.1.1%wlan0", "fe80::1%", "fe80::1%a%b", "fe80::1%other", "fe80::1%0", "fe80::1%4294967296").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf(invalid))).config() }
        }
        assertThrows(IllegalArgumentException::class.java) { SystemDnsSnapshot("absent", emptyMap()).config() }
    }

    @Test fun yaml12NamesPasswordsAndDatesDoNotBecomeYaml11Booleans() {
        val source = """
            proxies:
             - {type: ss, name: on, password: off, cipher: aes-128-gcm, server: example.com, port: 443}
            proxy-groups:
             - {type: select, name: YES, proxies: [on]}
            hosts: {date.example: 2026-10-22}
        """.trimIndent()
        val result = ConfigYaml.parse(compose(source))
        val proxy = (result["proxies"] as List<*>)[0] as Map<*, *>
        assertEquals("on", proxy["name"])
        assertEquals("off", proxy["password"])
        assertEquals("YES", ((result["proxy-groups"] as List<*>)[0] as Map<*, *>)["name"])
        assertEquals("2026-10-22", (result["hosts"] as Map<*, *>)["date.example"])
    }

    @Test fun unsupportedImportedListenersCannotBypassValidator() {
        assertThrows(ConfigValidationException::class.java) { compose("listeners: []") }
        assertThrows(ConfigValidationException::class.java) { compose("inbound-providers: {}") }
    }

    @Test fun providerAndGeoPathsCannotEscapePrivateDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            compose("rule-providers: {remote: {type: http, path: ../outside.mrs}}")
        }
        val outside = File(temporary.root, "outside.dat").canonicalPath.replace('\\', '/')
        assertThrows(IllegalArgumentException::class.java) { compose("geosite: '$outside'") }
        val result = ConfigYaml.parse(compose("geosite: geo.dat"))
        assertEquals(File(temporary.root, "resources/geo.dat").canonicalPath, result["geosite"])
    }

    @Test fun invalidServiceParametersFailWithoutAnyOutputWrite() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigComposer(temporary.root).compose("{}", LiteSettings(), -1, 49152, secret)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigComposer(temporary.root).compose("{}", LiteSettings(), 42, 0, secret)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigComposer(temporary.root).compose("{}", LiteSettings(), 42, 49152, "")
        }
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }
}
