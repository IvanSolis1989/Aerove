package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore.RuntimeMode
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.requests.RequestPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MeowRuntimeConfigComposerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun composer() = MeowRuntimeConfigComposer(temporary.root, "AEROVE-test/1")
    @Test fun actualTunIsTypedAndParserCannotCreateDeviceOrUserListeners() {
        val value = composer().compose("dns: {enable: true, listen: '127.0.0.1:1053'}\nsniffer: {enable: true}",
            LiteSettings(ipv6 = true), 42, 49152, secret, tunOptions = TunRuntimeOptions(1420, true, true))
        val root = ConfigYaml.parse(value.engineYaml)
        assertEquals(mapOf("enable" to false, "auto-route" to false), root["tun"])
        listOf("mixed-port", "port", "listeners", "external-controller", "secret").forEach { assertFalse(root.containsKey(it)) }
        assertEquals(mapOf("enable" to true), root["sniffer"])
        assertFalse((root["dns"] as Map<*, *>).containsKey("listen"))
        val tun = ConfigYaml.parse(value.tun!!.rawYaml)
        assertEquals(42, value.tun!!.descriptor);assertEquals(1420, tun["mtu"])
        assertEquals(listOf("any:53"), tun["dns-hijack"]);assertEquals(false, tun["auto-route"])
        assertEquals("fdfe:dcba:9876::1/126", tun["inet6-address"])
        listOf("gateway", "gateway-v6", "device-id", "device").forEach { assertFalse(tun.containsKey(it)) }
        assertEquals(secret, value.privateControl.secret);assertEquals(49152, value.privateControl.port)
        assertFalse(value.toString().contains(secret))
    }
    @Test fun parkedAndProxyHaveNoFdAndSettingsPoliciesRemainExplicit() {
        val settings = LiteSettings(network = NetworkSettings(vpnEnabled = false), requests = RequestPreferences(globalUserAgent = "custom-UA", checkCertificate = false))
        val value = composer().compose("dns: {enable: false}", settings, -1, 49152, secret, RuntimeMode.Proxy,
            SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf("192.0.2.1"))), initiallyParked = true)
        assertNull(value.tun);assertTrue(value.initiallyParked)
        val policy = ConfigYaml.parse(value.hostPolicyYaml)
        assertEquals("custom-UA", policy["global-ua"]);assertEquals(false, policy["check-certificate"])
        assertEquals("wlan0", (policy["aerove-system-dns"] as Map<*, *>)["defaultInterface"])
        val parked = composer().compose("dns: {enable: false}", LiteSettings(), -1, 49152, secret, initiallyParked = true)
        assertNull(parked.tun!!.descriptor);assertEquals(emptyList<String>(), ConfigYaml.parse(parked.tun!!.rawYaml)["dns-hijack"])
        assertThrows(IllegalArgumentException::class.java) { composer().compose("{}", LiteSettings(), 4, 49152, secret, initiallyParked = true) }
    }
    @Test fun supportedNamedListenersAreKeptForHostRatherThanDiscarded() {
        val source = "listeners: [{name: office, type: socks5, port: 1081, listen: '127.0.0.1'}]"
        val value = composer().compose(source, LiteSettings(), 5, 49152, secret)
        assertEquals("office", ((ConfigYaml.parse(value.userInboundYaml)["listeners"] as List<*>).single() as Map<*, *>)["name"])
        assertFalse(ConfigYaml.parse(value.engineYaml).containsKey("listeners"))
        assertThrows(ConfigValidationException::class.java) { composer().compose(source.replace("1081", "49152"), LiteSettings(), 5, 49152, secret) }
    }
    @Test fun ignoredAndLegacyTunFieldsFailAtExactPath() {
        listOf("tun: {stack: system}" to "$.tun.stack", "tun: {device-id: 'fd://4'}" to "$.tun.device-id",
            "tun: {dns-hijack: true}" to "$.tun.dns-hijack", "tun: {auto-route: global}" to "$.tun.auto-route",
            "listeners: [{name: a, type: ss, port: 1080, shadow-tls: {}}]" to "$.listeners[0].shadow-tls").forEach { (source,path) ->
            val error = assertThrows(ConfigValidationException::class.java) { composer().compose(source, LiteSettings(), 4, 49152, secret) }
            assertEquals(path, error.issues.single().path)
        }
    }
    @Test fun preparedGenerationAndCanonicalResourceContainmentUseExistingComposer() {
        val bundle = File(temporary.root, "resources/bundles/profile/generation").apply { mkdirs() }
        val rules = File(bundle, "rules.yaml").apply { writeText("payload: [example.com]") }
        val yaml = "rule-providers: {test: {type: file, path: '${rules.path.replace('\\','/')}', behavior: domain}}"
        val prepared = PreparedProfile("profile", yaml, yaml, "revision", "generation", bundle, null)
        val value = composer().compose(prepared, LiteSettings(), 4, 49152, secret)
        assertEquals("generation", value.resourceGeneration);assertEquals("revision", value.revision)
        assertTrue(value.engineYaml.contains("rules.yaml"))
        assertThrows(IllegalArgumentException::class.java) { composer().compose(prepared.copy(resourceDirectory = temporary.root), LiteSettings(), 4, 49152, secret) }
        assertThrows(Exception::class.java) { composer().compose("geodata: {mmdb-path: '../outside.mmdb'}", LiteSettings(), 4, 49152, secret) }
    }
}
