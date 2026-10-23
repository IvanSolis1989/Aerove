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

    @Test fun realSystemAndDhcpDnsReachNativeWithoutLegacyStoreRejection() {
        val source = "dns: {nameserver: [system, 'system://', 'dhcp://auto', 'dhcp://wlan0'], nameserver-policy: {example.com: 'dhcp://wlan0'}}"
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
        rejected("dns: {nameserver: ['dhcp://']}", "$.dns.nameserver[0]")
        rejected("dns: {nameserver: ['ftp://dns.example']}", "$.dns.nameserver[0]")
        rejected("dns: {nameserver-policy: {example.com: [system]}}", "$.dns.nameserver-policy.example.com")
        assertEquals(1, nativeCalls)
    }

    @Test fun probeDialOptionsAreStrictBooleansAndKeepExplicitFalse() {
        val source = "unified-delay: false\ntcp-concurrent: true"
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
        listOf("unified-delay", "tcp-concurrent").forEach { key ->
            listOf("null", "'false'", "1", "[]").forEach { invalid -> rejected("$key: $invalid", "$.$key") }
        }
        assertEquals(1, nativeCalls)
    }

    @Test fun fixedUnsupportedCapabilitiesReportActualConsumersRatherThanASchemaLabel() {
        listOf("ntp" to "{}", "sniffer" to "{enable: false}", "geodata-loader" to "standard").forEach { (key, value) ->
            val error = assertThrows(ConfigValidationException::class.java) { validator.validate("$key: $value") }
            val issue = error.issues.single { it.path == "$.$key" }
            assertTrue(issue.reason, issue.reason.contains("Fixed clash-rs"))
            assertFalse(issue.reason.contains("compatibility schema"))
        }
    }

    @Test fun fourNativeFeatureProtocolsKeepTheirPreciseCanonicalFieldsAndDefaultsUnmodified() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        listOf(
            "{type: ssh, name: SSH, server: ssh.example, port: 22, username: user, password: pass, host-key-algorithms: [ssh-ed25519]}",
            "{type: wireguard, name: WG, server: wg.example, port: 51820, private-key: '$key', public-key: '$key', ip: 10.0.0.2/32, ipv6: 'fd00::2/128', dns: [1.1.1.1], reserved-bits: [0, 1, 255]}",
            "{type: shadowquic, name: SQ, server: sq.example, port: 443, username: user, password: pass, server-name: sq.example}",
            "{type: tailscale, name: TS, ephemeral: false}",
        ).forEach { proxy ->
            val source = "proxies: [$proxy]"
            validator.validate(source)
            assertEquals(source, nativeSources.last())
        }
        assertEquals(4, nativeCalls)
    }

    @Test fun standardShadowQuicAliasesAndOmittedSniStayIntactForNativeNormalization() {
        val base = "type: shadowquic, name: Q, server: sq.example, port: 443, username: u, password: p"
        listOf(base, "$base, sni: front.example, udp-over-stream: false, congestion-controller: bbr, disable-mtu-discovery: false",
            base.replace(", username: u, password: p", ""), base.replace("username: u, password: p", "username: '', password: ''")).forEach { proxy ->
            val source = "proxies: [{$proxy}]"
            validator.validate(source)
            assertEquals(source, nativeSources.last())
        }
        listOf(
            "sni: name, server-name: name" to "sni",
            "udp-over-stream: true, over-stream: true" to "udp-over-stream",
            "mtu-discovery: true, disable-mtu-discovery: false" to "disable-mtu-discovery",
            "congestion-control: bbr, congestion-controller: bbr" to "congestion-controller",
            "disable-mtu-discovery: 'false'" to "disable-mtu-discovery",
            "udp-over-stream: 0" to "udp-over-stream",
            "max-open-streams: 1024" to "max-open-streams",
        ).forEach { (options, field) -> rejected("proxies: [{$base, $options}]", "$.proxies[0].$field") }
        assertEquals(4, nativeCalls)
    }

    @Test fun wireGuardRejectsInputsThatFixedRuntimeWouldTruncateIgnoreOrUnwrap() {
        val key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        val valid = "type: wireguard, name: W, server: wg.example, port: 51820, private-key: '$key', public-key: '$key', ip: 10.0.0.2/32"
        listOf(
            valid.replace("private-key: '$key'", "private-key: malformed") to "private-key",
            "$valid, pre-shared-key: malformed" to "pre-shared-key",
            "$valid, ipv6: malformed" to "ipv6",
            "$valid, ipv6: '192.0.2.1/24'" to "ipv6",
            "$valid, dns: [hostname.example]" to "dns[0]",
            "$valid, allowed-ips: [10.0.0.0/99]" to "allowed-ips[0]",
            "$valid, pre-shared-key: '$key', preshared-key: '$key'" to "preshared-key",
        ).forEach { (proxy, field) -> rejected("proxies: [{$proxy}]", "$.proxies[0].$field") }
        assertEquals(0, nativeCalls)
        val source = "proxies: [{$valid, preshared-key: '$key', allowed-ips: ['::/0', '0.0.0.0/0'], dns: ['::1'], reserved-bits: [0,0,0]}]"
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
    }

    @Test fun newProtocolTypeErrorsUnknownsAndIgnoredFieldsRejectBeforeNative() {
        listOf(
            "{type: ssh, name: S, server: ssh.example, port: 22, username: 1}" to "username",
            "{type: ssh, name: S, server: ssh.example, port: 22, username: u, host-key: text}" to "host-key",
            "{type: wireguard, name: W, server: wg.example, port: 51820, private-key: a, public-key: b, ip: 10.0.0.2/32, udp: 'true'}" to "udp",
            "{type: wireguard, name: W, server: wg.example, port: 51820, private-key: a, public-key: b, ip: 10.0.0.2/32, reserved-bits: [1, 2, 3, 4]}" to "reserved-bits",
            "{type: wireguard, name: W, server: wg.example, port: 51820, private-key: a, public-key: b, ip: 10.0.0.2/32, peers: []}" to "peers",
            "{type: shadowquic, name: Q, server: sq.example, port: 443, username: u, password: p, server-name: sq.example, initial-mtu: 1.5}" to "initial-mtu",
            "{type: tailscale, name: T, server: ts.example}" to "server",
            "{type: tailscale, name: T, ephemeral: 'false'}" to "ephemeral",
            "{type: tailscale, name: T, exit-node: remote}" to "exit-node",
        ).forEach { (proxy, field) -> rejected("proxies: [$proxy]", "$.proxies[0].$field") }
        assertEquals(0, nativeCalls)
        val error = assertThrows(ConfigValidationException::class.java) { validator.validate("proxies: [{type: openvpn, name: O}]") }
        assertTrue(error.issues.single().reason.contains("no openvpn outbound variant"))
    }

    @Test fun exactProcessNameAndPathRulesReachNativeAndModesStayStrict() {
        val source = "find-process-mode: always\nrules: ['PROCESS-NAME,com.example.app,DIRECT', 'PROCESS-PATH,/data/app/example,DIRECT', 'AND,((PROCESS-NAME,com.example.app),(NETWORK,TCP)),DIRECT']"
        validator.validate(source)
        assertEquals(listOf(source), nativeSources)
        listOf("strict", "Always", "off").forEach { validator.validate("find-process-mode: $it") }
        listOf("null", "true", "[]", "unknown").forEach { rejected("find-process-mode: $it", "$.find-process-mode") }
        assertEquals(4, nativeCalls)
    }

    @Test fun uidUserRegexAndWildcardGuardsRemainExactForInlineAndProviderRules() {
        listOf("UID", "IN-USER", "PROCESS-NAME-REGEX", "PROCESS-PATH-REGEX", "PROCESS-NAME-WILDCARD", "PROCESS-PATH-WILDCARD").forEach { rule ->
            rejected("rules: ['$rule,app,DIRECT']", "$.rules[0]")
            val failure = assertThrows(ConfigValidationException::class.java) {
                validator.validateRulePayload(listOf("$rule,app,DIRECT"), "$.payload")
            }
            assertEquals("$.payload[0]", failure.issues.single().path)
        }
        validator.validateRulePayload(listOf("PROCESS-NAME,com.example,DIRECT", "PROCESS-PATH,/data/app/example,DIRECT"), "$.payload")
        assertEquals(0, nativeCalls)
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
        rejected("dns: {nameserver-policy: {example.com: [1.1.1.1]}}", "$.dns.nameserver-policy.example.com")
        rejected("dns: {fake-ip-filter: ['geosite:cn']}", "$.dns.fake-ip-filter[0]")
    }

    @Test fun androidUnsupportedUidRuleIsRejectedInsideLogicalRules() {
        rejected("rules:\n - AND,((UID,1000),(NETWORK,TCP)),DIRECT", "$.rules[0]")
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
