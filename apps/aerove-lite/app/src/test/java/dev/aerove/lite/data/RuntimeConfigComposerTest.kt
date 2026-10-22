package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
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
            rules: ["MATCH,DIRECT"]
        """.trimIndent()
        val result = ConfigYaml.parse(compose(source))
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
        assertEquals(1500, tun["mtu"])
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

    @Test fun userDnsServerOverridePreservesOtherDnsPolicies() {
        val source = """
            dns:
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
        assertEquals(settings.dnsServers, dns["nameserver"])
        assertEquals("fake-ip", dns["enhanced-mode"])
        assertEquals(listOf("*.lan"), dns["fake-ip-filter"])
        assertEquals(mapOf("example.com" to "tls://1.0.0.1"), dns["nameserver-policy"])
        assertEquals(listOf("9.9.9.9"), dns["fallback"])
        assertEquals(listOf("1.1.1.1"), dns["default-nameserver"])
        assertFalse(dns.containsKey("listen"))
        assertEquals(true, dns["ipv6"])
        assertEquals("global", result["mode"])
        assertEquals("fdfe:dcba:9876::1/126", (result["tun"] as Map<*, *>)["gateway-v6"])
    }

    @Test fun defaultSettingsFollowSourceDnsAndSupplyOnlyMissingNameserver() {
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
        assertEquals(LiteSettings().dnsServers, (ConfigYaml.parse(compose("{}"))["dns"] as Map<*, *>)["nameserver"])
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
