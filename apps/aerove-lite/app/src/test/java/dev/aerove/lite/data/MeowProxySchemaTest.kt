package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MeowProxySchemaTest {
    // Test-only capability injection exercises schema branches, not a claim about the built native artifact.
    private val features = setOf("vless", "vless-vision", "vless-encryption", "hysteria2", "snell", "trusttunnel", "trusttunnel-h3", "mux")
    private val uuid = "uuid: 12345678-1234-1234-1234-123456789012"
    private fun yaml(type: String, extra: String) = "proxies: [{name: t, type: $type, server: example.com, port: 443, $extra}]"
    private fun accept(type: String, extra: String) = MeowCompatibilityValidator.normalize(yaml(type, extra), features)
    private fun reject(field: String, type: String, extra: String) {
        try { accept(type, extra); fail("Expected rejection: $field") }
        catch (e: ConfigValidationException) { assertEquals("$.proxies[0].$field", e.issues.single().path) }
    }
    @Test fun actualFeatureProofRequiredByDefault() {
        try { MeowCompatibilityValidator.normalize(yaml("vless", uuid)); fail() }
        catch (e: ConfigValidationException) { assertTrue(e.message!!.contains("native feature verification")) }
    }
    @Test fun httpAndSocksCredentialsAndTls() {
        accept("http", "username: user, password: pass, tls: true, headers: {X-Test: value}")
        accept("socks5", "username: user, password: pass, tls: true, udp: true")
        reject("username", "http", "username: user")
        reject("headers.X-Test", "http", "headers: {X-Test: 1}")
        reject("udp", "http", "udp: true")
        reject("sni", "socks5", "sni: other")
        reject("skip-cert-verify", "socks5", "skip-cert-verify: true")
    }
    @Test fun snellVersionsAndVersionSpecificOptions() {
        accept("snell", "psk: key, version: v5, reuse: true, obfs-opts: {mode: tls, host: example.com}")
        accept("snell", "psk: key, version: 6, mode: unshaped")
        reject("version", "snell", "psk: key, version: 2")
        reject("mode", "snell", "psk: key, mode: unshaped")
        reject("obfs-opts.mode", "snell", "psk: key, version: 6, obfs-opts: {mode: tls}")
        reject("obfs-opts.host", "snell", "psk: key, obfs-opts: {host: example.com}")
    }
    @Test fun trustTlsAndQuicPolicies() {
        accept("trusttunnel", "username: user, password: pass, alpn: [h2], max-connections: 8, min-streams: 5, max-streams: 128, headers: {X-Test: value}")
        accept("trusttunnel", "username: user, password: pass, quic: true, alpn: [h3]")
        reject("client-fingerprint", "trusttunnel", "username: user, password: pass, quic: true, client-fingerprint: chrome")
        reject("max-streams", "trusttunnel", "username: user, password: pass, max-streams: 10")
        reject("alpn", "trusttunnel", "username: user, password: pass, alpn: [h3]")
        reject("certificate", "trusttunnel", "username: user, password: pass, certificate: cert")
        try { MeowCompatibilityValidator.normalize(yaml("trusttunnel", "username: user, password: pass, quic: true"), setOf("trusttunnel")); fail() }
        catch (e: ConfigValidationException) { assertEquals("$.proxies[0].quic", e.issues.single().path) }
    }
    @Test fun hy2RealFieldsAndRejections() {
        accept("hysteria2", "password: pass, up: 30 Mbps, down: 500, obfs: salamander, obfs-password: key, ports: 443-445, hop-interval: 10-30, alpn: h3")
        reject("alpn", "hysteria2", "password: pass, alpn: [h2]")
        reject("obfs", "hysteria2", "password: pass, obfs: gecko")
        reject("cwnd", "hysteria2", "password: pass, cwnd: 100")
        reject("up", "hysteria2", "password: pass, up: 1.5 Mbps")
        reject("hop-interval", "hysteria2", "password: pass, hop-interval: 2")
        reject("ports", "hysteria2", "password: pass, ports: 70000")
        reject("fingerprint", "hysteria2", "password: pass, fingerprint: not-sha256")
    }
    @Test fun hy2MportAliasPreservesCanonicalHoppingRange() {
        for (options in listOf("mport: 4443-4445", "mport: 4443-4445, ports: 4443-4445")) {
            val out = accept("hysteria2", "password: pass, $options, hop-interval: 10-30")
            assertFalse(out.contains("mport:"))
            assertTrue(out.contains("ports: 4443-4445"))
            assertTrue(out.contains("hop-interval: 10-30"))
        }
        val out = MeowCompatibilityValidator.normalize(
            "proxies: [{name: t, type: hysteria2, server: example.com, password: pass, mport: 4443-4445}]", features)
        assertTrue(out.contains("ports: 4443-4445")); assertFalse(out.contains("mport:"))
    }
    @Test fun hy2MportAliasRejectsConflictInvalidRangeAndOtherProtocols() {
        reject("mport", "hysteria2", "password: pass, mport: 4443-4445, ports: 5443-5445")
        for (value in listOf("70000", "'4445-4443'", "''", "[4443, 4444]", "true")) {
            reject("ports", "hysteria2", "password: pass, mport: $value")
        }
        reject("mport", "vless", "$uuid, mport: 4443-4445")
    }
    @Test fun hy2HoppingPortFallbackAndBounds() {
        MeowCompatibilityValidator.normalize("proxies: [{name: t, type: hysteria2, server: example.com, password: pass, port: 0, ports: 443-445}]", features)
        MeowCompatibilityValidator.normalize("proxies: [{name: t, type: hysteria2, server: example.com, password: pass, ports: 443-445}]", features)
        accept("hysteria2", "password: pass, ports: '*'")
        reject("ports", "hysteria2", "password: pass, ports: '1-65535,443'")
        reject("hop-interval", "hysteria2", "password: pass, hop-interval: 30-10")
    }
    @Test fun vlessAllTransportConsumersAndAliases() {
        accept("vless", "$uuid, network: tcp, tls: true, server-name: Case.EXAMPLE")
        accept("vless", "$uuid, network: ws, ws-opts: {path: /test, headers: {Host: Case.EXAMPLE}, max-early-data: 100}")
        accept("vless", "$uuid, network: grpc, grpc-opts: {grpc-service-name: Gun}")
        accept("vless", "$uuid, network: h2, h2-opts: {path: /test, host: [example.com]}")
        accept("vless", "$uuid, network: httpupgrade, http-upgrade-opts: {host: example.com, headers: {X-Test: value}}")
        val out = accept("vless", "$uuid, network: xhttp, splithttp-opts: {mode: stream-one, host: [example.com], x-padding-bytes: [100, 1000]}")
        assertTrue(out.contains("xhttp-opts")); assertFalse(out.contains("splithttp-opts"))
        reject("splithttp-opts", "vless", "$uuid, network: xhttp, splithttp-opts: {path: /a}, xhttp-opts: {path: /b}")
        reject("grpc-opts", "vless", "$uuid, grpc-opts: {grpc-service-name: Gun}")
        reject("ws-opts.headers.Other", "vless", "$uuid, network: ws, ws-opts: {headers: {Other: value}}")
        reject("xhttp-opts.mode", "vless", "$uuid, network: xhttp, xhttp-opts: {mode: packet-up}")
        reject("xhttp-opts.x-padding-bytes", "vless", "$uuid, network: xhttp, xhttp-opts: {x-padding-bytes: [0, 65537]}")
    }
    @Test fun vlessVisionRealityEchAndMux() {
        accept("vless", "$uuid, tls: true, flow: xtls-rprx-vision, smux: {enabled: true, protocol: muxcool}")
        accept("vless", "$uuid, tls: true, client-fingerprint: chrome, reality-opts: {public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA, short-id: 'abcd'}")
        accept("vless", "$uuid, tls: true, ech-opts: {enable: true, config: AQID}")
        reject("flow", "vless", "$uuid, flow: xtls-rprx-vision")
        reject("smux.padding", "vless", "$uuid, smux: {enabled: true, protocol: muxcool, padding: true}")
        reject("smux.statistic", "vless", "$uuid, smux: {enabled: true, statistic: true}")
        reject("ech-opts.config", "vless", "$uuid, tls: true, ech-opts: {enable: true, config: '%%%'}")
        reject("reality-opts.short-id", "vless", "$uuid, tls: true, client-fingerprint: chrome, reality-opts: {public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA, short-id: 1234}")
    }

    private fun ss(plugin: String, opts: String, extra: String = "") = MeowCompatibilityValidator.normalize(
        "proxies: [{name: node, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: secret, plugin: '$plugin', plugin-opts: $opts$extra}]",
        features + setOf("ss", "kcptun", "ech-tls-tunnel"))
    @Test fun ssPluginAliasesAndNativeConsumerFieldsSurviveNormalization() {
        val simple = ss("simple-obfs", "{obfs: tls, obfs-host: Case.Example}")
        assertTrue(simple.contains("host=Case.Example;mode=tls"))
        val gost = ss("gost-plugin", "{tls: true, ech-opts: {enable: true, config: AQID}, headers: {X-Test: value}}")
        assertTrue(gost.contains("ech-opts.enable=true"));assertTrue(gost.contains("header=X-Test:value"))
        val jls = ss("jls", "{host: example.com, username: user, password: pass, skip_cert_verify: false, alpn: [h2, http/1.1]}")
        assertTrue(jls.contains("skip-cert-verify=false")); assertTrue(jls.contains("alpn=h2,http/1.1"))
        assertTrue(ss("v2ray-plugin", "'mode=ws;tls;host=Case.Example'").contains("tls=true"))
    }
    @Test fun ssPluginUnknownAliasesCoercionsAndIgnoredPolicyFailAtExactPath() {
        listOf(
            Triple("obfs", "{obfs: tls, mode: http}", "mode"),
            Triple("restls", "{client-id: abc}", "client-id"),
            Triple("restls", "{version-hint: tls14}", "version-hint"),
            Triple("shadow-tls", "{version: 4}", "version"),
            Triple("kcptun", "{smuxver: 2}", "smuxver"),
            Triple("v2ray-plugin", "{tls: typo}", "tls"),
            Triple("v2ray-plugin", "{mode: quic}", "mode"),
            Triple("gost-plugin", "{skip-cert-verify: true}", "skip-cert-verify"),
            Triple("gost-plugin", "{tls: true, ech-enable: true, ech-opts: {enable: false}}", "ech-opts.enable"),
            Triple("kcptun", "{datashard: 255}", "parityshard"),
            Triple("kcptun", "{mode: fast, interval: 10}", "interval"),
            Triple("restls", "{force-tls12: true, version-hint: tls13}", "force-tls12")
        ).forEach { (plugin, opts, field) ->
            val e = assertThrows(ConfigValidationException::class.java) { ss(plugin, opts) }
            assertEquals("$.proxies[0].plugin-opts.$field", e.issues.single().path)
        }
    }
    @Test fun ssPluginUdpAndFingerprintAreConsumedOnlyByActualSupportingPlugins() {
        val udp = assertThrows(ConfigValidationException::class.java) { ss("shadow-tls", "{host: example.com}", ", udp: true") }
        assertEquals("$.proxies[0].udp", udp.issues.single().path)
        val fingerprint = assertThrows(ConfigValidationException::class.java) { ss("restls", "{host: example.com}", ", client-fingerprint: chrome") }
        assertEquals("$.proxies[0].client-fingerprint", fingerprint.issues.single().path)
    }
    private fun compat(type: String, extra: String) = MeowCompatibilityValidator.normalize(yaml(type, extra),
        features + setOf("ss", "trojan", "vmess", "anytls", "kcptun", "ech-tls-tunnel"))
    private fun compatReject(field: String, type: String, extra: String) {
        val error = assertThrows(ConfigValidationException::class.java) { compat(type, extra) }
        assertEquals("$.proxies[0].$field", error.issues.single().path)
    }
    @Test fun supportedTcpFactoriesKeepTheirExactDialerField() {
        for ((type, options) in listOf("http" to "", "socks5" to "", "ss" to "cipher: aes-128-gcm, password: secret,",
                "trojan" to "password: secret,", "vmess" to "$uuid,", "vless" to "$uuid,", "snell" to "psk: secret,",
                "trusttunnel" to "username: user, password: secret,")) {
            val result = ConfigYaml.parse(compat(type, "$options dialer-proxy: ' FRONT '"))
            assertEquals(" FRONT ", ((result["proxies"] as List<*>).single() as Map<*, *>)["dialer-proxy"])
        }
    }
    @Test fun unthreadedFactoriesAndExternalPluginsCannotClaimActiveChains() {
        for ((type, options) in listOf("direct" to "", "anytls" to "password: secret,", "hysteria2" to "password: secret,",
                "trusttunnel" to "username: user, password: secret, quic: true,",
                "ss" to "cipher: aes-128-gcm, password: secret, plugin: external-program,"))
            compatReject("dialer-proxy", type, "$options dialer-proxy: FRONT")
    }
    @Test fun dialerShapeAndSelfReferenceAreRejectedWithoutDroppingUnknownFields() {
        for (target in listOf("123", "true", "[]", "{}", "'   '", "t", "null"))
            compatReject("dialer-proxy", "http", "dialer-proxy: $target")
        compatReject("unknown-chain-option", "http", "dialer-proxy: FRONT, unknown-chain-option: true")
        compatReject("type", "tuic", "dialer-proxy: FRONT")
    }
    @Test fun emptyDialerPreservesTheCoreUnsetSemantics() {
        for ((type, options) in listOf("http" to "", "anytls" to "password: secret,", "hysteria2" to "password: secret,",
                "trusttunnel" to "username: user, password: secret, quic: true,"))
            assertTrue(compat(type, "$options dialer-proxy: ''").contains("dialer-proxy"))
        assertTrue(MeowCompatibilityValidator.normalize("proxies: [{name: t, type: direct, dialer-proxy: ''}]").contains("dialer-proxy"))
    }
    @Test fun vlessDnsEchPreservesAbsentInlineConfigAndQueryName() {
        for (opts in listOf("{enable: true}", "{enable: true, query-server-name: ech.example}",
                "{enable: true, config: '', query-server-name: ech.example}")) {
            val result = ConfigYaml.parse(compat("vless", "$uuid, tls: true, ech-opts: $opts"))
            val node = (result["proxies"] as List<*>).single() as Map<*, *>
            val ech = node["ech-opts"] as Map<*, *>
            assertEquals(true, ech["enable"])
            if (opts.contains("query-server-name")) assertEquals("ech.example", ech["query-server-name"])
            if (!opts.contains("config:")) assertFalse(ech.containsKey("config"))
        }
    }
    @Test fun dnsEchStillRejectsIgnoredWrongTypedAndIncompatiblePolicies() {
        compatReject("ech-opts.query-server-name", "vless", "$uuid, tls: true, ech-opts: {enable: true, query-server-name: 3}")
        compatReject("ech-opts.query-server-name", "vless", "$uuid, tls: true, ech-opts: {enable: false, query-server-name: ech.example}")
        compatReject("ech-opts.query-server-name", "vless", "$uuid, tls: true, ech-opts: {enable: true, config: AQID, query-server-name: ech.example}")
        compatReject("ech-opts.unknown", "vless", "$uuid, tls: true, ech-opts: {enable: true, unknown: true}")
        compatReject("ech-opts", "vless", "$uuid, ech-opts: {enable: true}")
        compatReject("ech-opts", "vless", "$uuid, tls: true, client-fingerprint: chrome, reality-opts: {public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA}, ech-opts: {enable: true}")
    }

}
