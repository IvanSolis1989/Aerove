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
    private fun composer() = MeowRuntimeConfigComposer(temporary.root, "AEROVE-test/1", File(temporary.root,"meow-cache/test-session").apply{mkdirs()})
    @Test fun finalEngineIsNormalizedOnlyWithExplicitVerifiedFeaturesAndSettingsSurvivePlan() {
        val source = """
            proxies: [{name: secure, type: ss, server: proxy.example, port: 443, cipher: aes-128-gcm, password: password}]
            proxy-groups: [{name: select, type: select, proxies: [secure]}]
            rules: ["MATCH,select"]
            dns: {enable: true, nameserver: ["https://dns.example/dns-query"]}
        """.trimIndent()
        val profile = PreparedProfile("actual-profile", source, source, "actual-revision", null, null, null)
        val settings = LiteSettings(ipv6 = true, requests = RequestPreferences(globalUserAgent = "verified-UA", checkCertificate = false))
        val failure = assertThrows(ConfigValidationException::class.java) { composer().compose(profile, settings, -1, 49152, secret, initiallyParked = true) }
        assertEquals("$.proxies[0].type", failure.issues.single().path)
        val value = composer().compose(profile, settings, -1, 49152, secret, initiallyParked = true, verifiedNativeFeatures = setOf("ss"))
        val plan = org.json.JSONObject(value.planJson())
        val engine = ConfigYaml.parse(plan.getString("engineYaml"))
        assertEquals(true, engine["strict"])
        assertEquals("ss", ((engine["proxies"] as List<*>).single() as Map<*, *>)["type"])
        assertEquals("actual-profile", plan.getString("profileId"));assertEquals("actual-revision", plan.getString("revision"))
        assertEquals("verified-UA", ConfigYaml.parse(plan.getString("hostPolicyYaml"))["global-ua"])
        assertFalse(plan.toString().contains("fd://"));assertNull(value.tun!!.descriptor)
        assertEquals(false, ConfigYaml.parse(plan.getString("hostPolicyYaml"))["check-certificate"])
    }
    @Test fun finalEngineRejectsAliasConflictsRatherThanSilentlyChoosingOne() {
        val source = """
            proxies:
              - {name: v, type: vmess, server: proxy.example, port: 443, uuid: '00000000-0000-0000-0000-000000000001', tls: true, server-name: one.example, servername: two.example}
        """.trimIndent()
        val error = assertThrows(ConfigValidationException::class.java) {
            composer().compose(source, LiteSettings(), 42, 49152, secret, verifiedNativeFeatures = setOf("vmess"))
        }
        assertEquals("$.proxies[0].server-name", error.issues.single().path)
    }
    @Test fun schemaOneSerializationHasExplicitPlanAndNeverSerializesDescriptor() {
        val value = composer().compose("dns: {enable: true}", LiteSettings(ipv6 = true), 91, 49152, secret,
            systemDns = SystemDnsSnapshot("wlan0", mapOf("wlan0" to listOf("192.0.2.1"))),
            tunOptions = TunRuntimeOptions(1480, true, true), physicalNetworkHandle = "18446744073709551615")
        val json = org.json.JSONObject(value.planJson())
        assertEquals(1, json.getInt("schemaVersion"));assertEquals("tun", json.getString("runtimeMode"))
        assertEquals("127.0.0.1:49152", json.getJSONObject("privateControl").getString("address"))
        assertEquals(secret, json.getJSONObject("privateControl").getString("secret"))
        assertEquals(1480, json.getJSONObject("tun").getInt("mtu"));assertFalse(json.getJSONObject("tun").getBoolean("autoRoute"))
        assertEquals("any:53", json.getJSONObject("tun").getJSONArray("dnsHijack").getString(0))
        assertFalse(json.getJSONObject("tun").has("descriptor"));assertFalse(value.planJson().contains("fd://"))
        assertEquals("18446744073709551615", json.getJSONObject("physicalNetwork").getString("handle"))
        assertEquals("wlan0", json.getJSONObject("physicalNetwork").getJSONObject("dns").getString("defaultInterface"))
        assertTrue(json.isNull("profileId"));assertTrue(json.getJSONObject("inbounds").has("configYaml"))
        assertThrows(IllegalArgumentException::class.java) { MeowPhysicalNetwork("00") }
        assertThrows(IllegalArgumentException::class.java) { MeowPhysicalNetwork("0") }
    }
    @Test fun candidateStatusWireIsStrictWithoutLoadingNativeLibrary() {
        val row = org.json.JSONObject("""{"state":"Running","error":null,"apiAddress":"127.0.0.1:49152","runtimeMode":"tun","tunReady":false,"userListenersReady":false,"listening":false,"coreReady":true}""")
        val status = dev.aerove.lite.core.MeowStatusCodec.status(row.toString())
        assertTrue(status.coreReady);assertFalse(status.listening)
        assertEquals(dev.aerove.lite.core.MeowState.Running, status.state)
        row.put("tunReady", "true")
        assertThrows(Exception::class.java) { dev.aerove.lite.core.MeowStatusCodec.status(row.toString()) }
        val listener = org.json.JSONObject("""{"listening":true,"tunReady":false,"userListenersReady":true,"activePorts":[7890],"coreEpoch":"18446744073709551615","onlyStatisticsProxy":true,"unifiedDelay":true,"tcpConcurrent":true,"findProcessMode":"always"}""")
        assertEquals(listOf(7890), dev.aerove.lite.core.MeowStatusCodec.listeners(listener.toString()).activePorts)
        listener.put("coreEpoch", "01")
        assertThrows(Exception::class.java) { dev.aerove.lite.core.MeowStatusCodec.listeners(listener.toString()) }
    }
    @Test fun actualTunIsTypedAndParserCannotCreateDeviceOrUserListeners() {
        val value = composer().compose("dns: {enable: true, listen: '127.0.0.1:1053'}\nsniffer: {enable: true, sniff: {TLS: {ports: [443]}}}",
            LiteSettings(ipv6 = true), 42, 49152, secret, tunOptions = TunRuntimeOptions(1420, true, true))
        val root = ConfigYaml.parse(value.engineYaml)
        assertEquals(mapOf("enable" to false, "auto-route" to false), root["tun"])
        listOf("mixed-port", "port", "listeners", "external-controller", "secret").forEach { assertFalse(root.containsKey(it)) }
        assertEquals(mapOf("enable" to true, "sniff" to mapOf("TLS" to mapOf("ports" to listOf(443)))), root["sniffer"])
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
        val plan = org.json.JSONObject(value.planJson())
        assertEquals(bundle.canonicalPath, plan.getString("resourceReadRoot"))
        assertEquals(File(temporary.root,"meow-cache/test-session").canonicalPath,plan.getString("cacheRoot"))
        assertEquals("payload: [example.com]",rules.readText())
        assertThrows(IllegalArgumentException::class.java) { composer().compose(prepared.copy(resourceDirectory = temporary.root), LiteSettings(), 4, 49152, secret) }
        assertThrows(Exception::class.java) { composer().compose("geodata: {mmdb-path: '../outside.mmdb'}", LiteSettings(), 4, 49152, secret) }
    }
    @Test fun cacheRootMustBeTrustedPrivateAndExplicitBeforeSerialization() {
        val value=MeowRuntimeConfigComposer(temporary.root).compose("{}",LiteSettings(),42,49152,secret)
        assertThrows(IllegalStateException::class.java){value.planJson()}
        assertThrows(IllegalArgumentException::class.java){MeowRuntimeConfigComposer(temporary.root,cacheDirectory=temporary.root)}
        assertThrows(IllegalArgumentException::class.java){MeowRuntimeConfigComposer(temporary.root,cacheDirectory=File(temporary.root,"meow-cache/missing"))}
    }
}
