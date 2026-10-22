package dev.aerove.lite.data

import java.io.File
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompatibilityValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private var nativeCalls = 0
    private val nativeSources = mutableListOf<String>()
    private val validator = CompatibilityValidator(NativeConfigValidator { nativeCalls++; nativeSources += it; null })

    private fun ssProfile(plugin: String, options: String): String =
        "proxies:\n - {name: SS, type: ss, server: ss.example, port: 8388, cipher: aes-128-gcm, password: data, plugin: $plugin, plugin-opts: $options}"

    private fun rejected(yaml: String, path: String) {
        val failure = assertThrows(ConfigValidationException::class.java) { validator.validate(yaml) }
        assertTrue(failure.message, failure.issues.any { it.path == path })
    }

    @Test fun supportedProfileAlsoRunsNativeParser() {
        validator.validate("mode: rule\nproxies:\n - {name: direct, type: direct}\nrules:\n - MATCH,direct")
        assertEquals(1, nativeCalls)
    }

    @Test fun userHttpSocksMixedLanAndAuthenticationReachNativeWithoutBeingStripped() {
        val source = """
            port: 7891
            socks-port: 7892
            mixed-port: 7890
            authentication: ['alice:password:with:colon']
            allow-lan: true
            bind-address: '::'
            rules: ['MATCH,DIRECT']
        """.trimIndent()
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
    }

    @Test fun androidNativeHandlerLimitsAreSpecificToEnabledRedirAndTproxyPorts() {
        rejected("redir-port: 7893", "$.redir-port")
        rejected("tproxy-port: 7894", "$.tproxy-port")
        assertEquals(0, nativeCalls)
        validator.validate("redir-port: 0\ntproxy-port: 0\nmixed-port: 7890")
        assertEquals(1, nativeCalls)
    }

    @Test fun nativeErrorsAreNotConvertedToSuccess() {
        val realFailure = CompatibilityValidator(NativeConfigValidator { "$.proxies[0].uuid: invalid UUID" })
        val failure = assertThrows(ConfigValidationException::class.java) {
            realFailure.validate("proxies:\n - {name: node, type: vless, server: example.com, port: 443, uuid: broken}")
        }
        assertTrue(failure.message!!.contains("uuid"))
    }

    @Test fun nestedUnknownTransportFieldHasExactPath() {
        rejected("proxies:\n - type: vless\n   name: Node\n   ws-opts: {path: /, unsupported: true}", "$.proxies[0].ws-opts.unsupported")
        assertEquals(0, nativeCalls)
    }

    @Test fun shadowTlsUsesOnlyThePinnedRustOptionsAndPreservesStrictChoice() {
        listOf(true, false).forEach { strict ->
            val yaml = ssProfile("shadow-tls", "{host: tls.example, password: plugin-secret, strict: $strict}")
            validator.validate(yaml)
            assertEquals(yaml, nativeSources.last())
            val proxy = (ConfigYaml.parse(nativeSources.last())["proxies"] as List<*>).single() as Map<*, *>
            val options = proxy["plugin-opts"] as Map<*, *>
            assertEquals("plugin-secret", options["password"])
            assertEquals(strict, options["strict"])
        }
        assertEquals(2, nativeCalls)
    }

    @Test fun v2rayPluginPortAndCustomHeadersReachNativeWithoutRewriting() {
        val yaml = ssProfile("v2ray-plugin", "{mode: websocket, host: front.example, port: 8443, path: /ws, tls: true, mux: false, skip-cert-verify: false, headers: {Host: alternate.example, X-Test: value}}")
        validator.validate(yaml)
        assertEquals(listOf(yaml), nativeSources)
        val proxy = (ConfigYaml.parse(nativeSources.single())["proxies"] as List<*>).single() as Map<*, *>
        val options = proxy["plugin-opts"] as Map<*, *>
        assertEquals(8443, options["port"])
        assertEquals(mapOf("Host" to "alternate.example", "X-Test" to "value"), options["headers"])
    }

    @Test fun existingSimpleObfsAndMinimalWebsocketOptionsRemainAdmitted() {
        listOf(
            ssProfile("obfs", "{mode: http, host: front.example}"),
            ssProfile("obfs", "{mode: tls, host: front.example}"),
            ssProfile("v2ray-plugin", "{mode: websocket, tls: false, mux: false}"),
        ).forEach(validator::validate)
        assertEquals(3, nativeCalls)
    }

    @Test fun pluginWhitelistsRejectUnknownAndOtherPluginsOptionsAtTheirExactPaths() {
        listOf(
            Triple("shadow-tls", "{password: auth, version: 3}", "version"),
            Triple("shadow-tls", "{password: auth, port: 443}", "port"),
            Triple("shadow-tls", "{password: auth, headers: {Host: front.example}}", "headers"),
            Triple("shadow-tls", "{password: auth, mode: websocket}", "mode"),
            Triple("v2ray-plugin", "{mode: websocket, strict: false}", "strict"),
            Triple("v2ray-plugin", "{mode: websocket, password: auth}", "password"),
            Triple("v2ray-plugin", "{mode: websocket, packet-encoding: xudp}", "packet-encoding"),
            Triple("obfs", "{mode: http, port: 443}", "port"),
            Triple("obfs", "{mode: http, headers: {Host: front.example}}", "headers"),
            Triple("unknown-plugin", "{port: 443}", "port"),
        ).forEach { (plugin, options, field) ->
            rejected(ssProfile(plugin, options), "$.proxies[0].plugin-opts.$field")
        }
        assertEquals(0, nativeCalls)
    }

    @Test fun malformedAndDuplicatePluginOptionsAreNotSilentlyIgnored() {
        listOf("true", "[host, password]", "text").forEach { raw ->
            rejected(ssProfile("shadow-tls", raw), "$.proxies[0].plugin-opts")
        }
        rejected(ssProfile("v2ray-plugin", "{mode: websocket, port: 443, port: 8443}"), "$")
        assertEquals(0, nativeCalls)
    }

    @Test fun admittingV2rayFieldsDoesNotSuppressNativeTransportErrors() {
        var attempts = 0
        val diagnostic = "v2ray plugin does not support mux"
        val check = CompatibilityValidator(NativeConfigValidator { attempts++; diagnostic })
        val failure = assertThrows(ConfigValidationException::class.java) {
            check.validate(ssProfile("v2ray-plugin", "{mode: websocket, port: 443, mux: true, headers: {X-Test: value}}"))
        }
        assertEquals(1, attempts)
        assertEquals(listOf(ConfigIssue("$", diagnostic)), failure.issues)
    }

    @Test fun f07ShadowTlsAndV2rayShareResultsPassHostAdmissionIntact() = runBlocking<Unit> {
        val credentials = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-128-gcm:data".toByteArray())
        fun link(name: String, plugin: String): String =
            "ss://$credentials@ss.example:8388?plugin=${URLEncoder.encode(plugin, Charsets.UTF_8.name())}#$name"
        val batch = ProfileLinkDecoder().decode(listOf(
            link("ST", "shadow-tls;host=tls.example;password=auth;strict=false"),
            link("WS", "v2ray-plugin;mode=websocket;host=front.example;port=8443;headers={\"X-Test\":\"value\"}"),
        ))
        assertTrue(batch.errors.toString(), batch.errors.isEmpty())
        val source = batch.entries.filterIsInstance<ProfileLinkEntry.Inline>().single().config
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
        val proxies = ConfigYaml.parse(nativeSources.single())["proxies"] as List<*>
        val shadow = (proxies[0] as Map<*, *>)["plugin-opts"] as Map<*, *>
        val websocket = (proxies[1] as Map<*, *>)["plugin-opts"] as Map<*, *>
        assertEquals(false, shadow["strict"])
        assertEquals("auth", shadow["password"])
        assertEquals(8443, websocket["port"])
        assertEquals(mapOf("X-Test" to "value"), websocket["headers"])
    }

    @Test fun parsedButIgnoredFingerprintAndIdleFieldsAreRejected() {
        rejected("proxies:\n - {type: vless, name: N, client-fingerprint: chrome}", "$.proxies[0].client-fingerprint")
        rejected("proxies:\n - {type: anytls, name: N, min-idle-session: 1}", "$.proxies[0].min-idle-session")
    }

    @Test fun unknownTopLevelAndGroupFilterAreRejected() {
        rejected("sniffer: {enable: true}", "$.sniffer")
        rejected("proxy-groups:\n - {name: N, type: select, filter: test}", "$.proxy-groups[0].filter")
    }

    @Test fun unsupportedDnsPoliciesAreNotSilentlyAccepted() {
        rejected("dns: {enhanced-mode: redir-host}", "$.dns.enhanced-mode")
        rejected("dns: {nameserver: [system]}", "$.dns.nameserver[0]")
        rejected("dns: {nameserver-policy: {example.com: [1.1.1.1]}}", "$.dns.nameserver-policy.example.com")
        rejected("dns: {fake-ip-filter: ['geosite:cn']}", "$.dns.fake-ip-filter[0]")
    }

    @Test fun androidProcessRuleIsRejectedInsideLogicalRules() {
        rejected("rules:\n - AND,((PROCESS-NAME,app),(NETWORK,TCP)),DIRECT", "$.rules[0]")
    }

    @Test fun geoRequirementAndUnmanagedProvidersAreExplicitErrors() {
        rejected("rules:\n - GEOIP,CN,DIRECT", "$.rules[0]")
        rejected("proxy-providers: {remote: {type: http, url: 'https://example.com/list'}}", "$.proxy-providers")
    }

    @Test fun additionalInboundsAndExternalUiAreRejected() {
        rejected("listeners: []", "$.listeners")
        rejected("external-ui: public", "$.external-ui")
    }

    @Test fun duplicateKeysAndRecursiveAliasesAreRejected() {
        rejected("mode: rule\nmode: global", "$")
        assertThrows(ConfigValidationException::class.java) { validator.validate("hosts: &self {localhost: *self}") }
    }

    @Test fun privateGeoFileMustExistAndCannotEscape() {
        val check = CompatibilityValidator(NativeConfigValidator { null }, temporary.root)
        assertThrows(ConfigValidationException::class.java) { check.validate("geosite: ../outside.dat") }
        assertThrows(ConfigValidationException::class.java) { check.validate("geosite: missing.dat") }
        File(temporary.root, "resources").mkdirs()
        File(temporary.root, "resources/geo.dat").writeBytes(byteArrayOf(1))
        check.validate("geosite: geo.dat\nrules:\n - GEOSITE,cn,DIRECT")
    }
}
