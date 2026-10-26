package dev.aerove.lite.data

import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileLinkDecoderTest {
    private val decoder = ProfileLinkDecoder()
    private val uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private fun b64(text: String): String = Base64.getEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun escaped(text: String): String = URLEncoder.encode(text, "UTF-8")
    private fun decode(vararg text: String): ProfileLinkBatch = runBlocking { decoder.decode(text.toList()) }
    private fun inline(result: ProfileLinkBatch): ProfileLinkEntry.Inline {
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        return result.entries.single() as ProfileLinkEntry.Inline
    }
    private fun nodes(result: ProfileLinkBatch): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return ConfigYaml.parse(inline(result).config)["proxies"] as List<Map<String, Any?>>
    }
    private fun rejected(text: String, field: String? = null, code: ProfileLinkErrorCode? = null): ProfileLinkBatch {
        val result = decode(text)
        assertTrue(result.entries.toString(), result.entries.isEmpty())
        assertTrue(result.errors.isNotEmpty())
        field?.let { assertEquals(it, result.errors.first().field) }
        code?.let { assertEquals(it, result.errors.first().code) }
        return result
    }

    @Test fun rawSpacesAreAllowedOnlyInShareDisplayFragment() {
        val link = "ss://${b64("aes-128-gcm:pw")}@node.example:443"
        assertEquals("Node One", nodes(decode("$link#Node One")).single()["name"])
        assertEquals("Node One", nodes(decode("$link#Node%20One")).single()["name"])
        rejected(link.replace("node.example", "node example") + "#Node One")
        rejected("$link?plugin=v2ray plugin#Node One")
        rejected("$link#Node\tOne")
        rejected("https://node.example:443#Node One", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("unknown://node.example:443#Node One", code = ProfileLinkErrorCode.UNSUPPORTED_SCHEME)
    }

    @Test fun fullBatchOrderCombinesAllSharesAtTheirFirstPosition() {
        val result = decode("https://one.example/a", "ss://${b64("aes-128-gcm:p")}@a.example:443#A",
            "https://two.example/b", "trojan://pass@b.example:443#B")
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertEquals(3, result.entries.size)
        assertEquals("https://one.example/a", (result.entries[0] as ProfileLinkEntry.Subscription).url)
        val group = result.entries[1] as ProfileLinkEntry.Inline
        assertEquals(ProfileLinkEntry.Inline.Kind.SHARE_LINKS, group.kind)
        assertEquals(listOf(ProfileLinkSource(1), ProfileLinkSource(3)), group.sources)
        assertEquals(2, group.nodeCount)
        assertEquals("A", group.firstNodeName)
        assertEquals(setOf("proxies"), ConfigYaml.parse(group.config).keys)
        assertEquals("https://two.example/b", (result.entries[2] as ProfileLinkEntry.Subscription).url)
    }

    @Test fun failedShareRejectsItsWholeGroupButKeepsIndependentUrls() {
        val result = decode("trojan://good@a.example:443#A", "https://ok.example/sub", "ss://not-base64")
        assertEquals(1, result.entries.size)
        assertTrue(result.entries.single() is ProfileLinkEntry.Subscription)
        assertEquals(ProfileLinkSource(2), result.errors.single().source)
        assertEquals(listOf(ProfileLinkSource(0), ProfileLinkSource(2)), result.rejectedShareSources)
    }

    @Test fun supportsEveryFullSchemeWithSourceProvenExceptions() {
        assertEquals(16, ProfileLinkDecoder.SHARE_SCHEMES.size)
        val supported = listOf("hysteria2://p@a.example#H", "hy2://p@a.example#H",
            "tuic://$uuid:p@a.example:443#T", "trojan://p@a.example:443#T",
            "vless://$uuid@a.example:443#V", "vmess://${b64("""{"ps":"V","add":"a.example","port":443,"id":"$uuid"}""")}",
            "ss://${b64("aes-128-gcm:p")}@a.example:443#S", "socks://a.example:1080#S",
            "socks5://a.example:1080#S", "socks5h://a.example:1080#S", "anytls://p@a.example:443#A")
        supported.forEach { link -> assertEquals(link.substringBefore("://"), 1, nodes(decode(link)).size) }
        listOf("hysteria", "ssr", "hysteria2+realm", "hy2+realm", "mierus").forEach { scheme ->
            rejected("$scheme://p@a.example:443/path", code = ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        }
    }

    @Test fun supportedMappedFieldsPassTheExistingClientAdmissionGate() {
        var calls = 0
        val validator = CompatibilityValidator(NativeConfigValidator { calls++; null })
        val links = listOf("ss://${b64("aes-128-gcm:p")}@a.example:443", "socks5://a.example:1080",
            "anytls://p@a.example:443", "trojan://p@a.example:443?type=ws&path=%2Fws",
            "vmess://$uuid@a.example:443", "vless://$uuid@a.example:443?type=xhttp&path=%2Fx",
            "tuic://$uuid:p@a.example:443", "hy2://p@a.example")
        runBlocking { validator.validate(inline(decode(*links.toTypedArray())).config) }
        assertEquals(1, calls)
    }

    @Test fun installConfigDecodesExactlyOnceWithoutFetching() {
        listOf("clash", "clashmeta", "flclash").forEach { scheme ->
            val url = "https://sub.example/config?token=a%2Bb&next=%252F"
            val entry = decode("$scheme://install-config?url=${escaped(url)}&name=${escaped("中文 + name")}").entries.single() as ProfileLinkEntry.Subscription
            assertEquals(url, entry.url)
            assertEquals("中文 + name", entry.name)
        }
    }

    @Test fun installConfigCannotSendShareCodesOrLocalFilesToDownloader() {
        rejected("clash://install-config?url=${escaped("ss://a")}", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("flclash://install-config?url=${escaped("file:///secret")}", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("flclash://other?url=https%3A%2F%2Fa.example", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("clash://install-config?url=https%3A%2F%2Fa.example&%75rl=x", code = ProfileLinkErrorCode.DUPLICATE_PARAMETER)
    }

    @Test fun rawJsonAndYamlKeepAllFieldsAndOriginalBytesForRepositoryValidation() {
        val yaml = "proxies:\n - {name: A, type: direct}\nproxy-groups:\n - {name: G, type: select, proxies: [A]}\nrules: [MATCH,G]\nx-owner-option: preserve\n"
        assertEquals(yaml, inline(decode(yaml)).config)
        val json = "{\"mode\":\"direct\",\"hosts\":{\"例.example\":\"127.0.0.1\"}}\n"
        assertEquals(json, inline(decode(json)).config)
        assertEquals(ProfileLinkEntry.Inline.Kind.CONFIGURATION, inline(decode(json)).kind)
    }

    @Test fun duplicateYamlJsonAndUnsafeTagsAreRejected() {
        rejected("proxies: []\nproxies: []", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
        rejected("{\"proxies\":[],\"proxies\":[]}", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
        rejected("a: &a {loop: *a}", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
        rejected("!!java.net.URL ['https://example.com']", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
        rejected("mode: direct\n---\nmode: rule", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
    }

    @Test fun rawKnownIgnoredFieldsAreRejectedAtTheirOriginalPath() {
        val source = "proxies: [{name: V, type: vless, server: a.example, port: 443, uuid: $uuid, client-fingerprint: chrome}]"
        rejected(source, "$.proxies[0].client-fingerprint", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        rejected("proxies: [{name: H, type: hysteria2, server: a.example, port: 443, password: p, up: 30}]",
            "$.proxies[0].up", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        rejected("proxies: [{name: S, type: ssr}]", "$.proxies[0].type", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
    }

    @Test fun standardAndUrlSafeBase64ListsRetainLineProvenance() {
        val source = "ss://${b64("aes-128-gcm:p")}@a.example:443#一\nsocks://b.example:1080#二"
        val standard = inline(decode(b64(source)))
        assertEquals(listOf(ProfileLinkSource(0, 1), ProfileLinkSource(0, 2)), standard.sources)
        val safe = Base64.getUrlEncoder().encodeToString(source.toByteArray())
        assertEquals(standard.config, inline(decode(safe)).config)
    }

    @Test fun shadowsocksSupportsSip002LegacyAndUnicodeWithoutPlusCorruption() {
        val user = b64("aes-128-gcm:p+密码:tail")
        val first = nodes(decode("ss://$user@[2001:db8::1]:443#%E4%B8%AD%E6%96%87+plus")).single()
        assertEquals("p+密码:tail", first["password"])
        assertEquals("中文+plus", first["name"])
        assertEquals("2001:db8::1", first["server"])
        assertEquals(443, first["port"])
        val legacy = nodes(decode("ss://${b64("aes-256-gcm:secret@a.example:8388")}#legacy")).single()
        assertEquals("secret", legacy["password"])
        assertEquals(8388, legacy["port"])
    }

    @Test fun sip002PluginParametersUseNativeFieldsAndRejectDuplicates() {
        val plugin = "v2ray-plugin;mode=websocket;host=front.example;path=/ws;tls;mux=false"
        val node = nodes(decode("ss://${b64("aes-128-gcm:p")}@a.example:443?plugin=${escaped(plugin)}#N")).single()
        assertEquals("v2ray-plugin", node["plugin"])
        val options = node["plugin-opts"] as Map<*, *>
        assertEquals(true, options["tls"])
        assertEquals(false, options["mux"])
        assertEquals("/ws", options["path"])
        rejected("ss://${b64("aes-128-gcm:p")}@a.example:443?plugin=${escaped("obfs-local;obfs=http;obfs=tls")}", code = ProfileLinkErrorCode.DUPLICATE_PARAMETER)
    }

    @Test fun socksAcceptsPlainAndBase64CredentialsAndKeepsColons() {
        val plain = nodes(decode("socks5h://user:p%3Aa%2Bss@a.example:1080")).single()
        assertEquals("socks5", plain["type"])
        assertEquals("p:a+ss", plain["password"])
        val encoded = nodes(decode("socks://${b64("user:p:a+ss")}@a.example:1080")).single()
        assertEquals(plain, encoded)
        assertEquals("user", nodes(decode("socks5://user@a.example:1080")).single()["username"])
    }

    @Test fun anytlsPasswordAndTrojanPercentCredentialsUseNativeTlsOptions() {
        val anytls = nodes(decode("anytls://p%3Aa%2Bss@a.example:443?sni=t.example&insecure=1#N")).single()
        assertEquals("p:a+ss", anytls["password"])
        assertEquals(true, anytls["skip-cert-verify"])
        val trojan = nodes(decode("trojan://p%3A%40ss@a.example:443?type=ws&path=%2Fws&sni=t.example&alpn=h2%2Chttp%2F1.1#T")).single()
        assertEquals("p:@ss", trojan["password"])
        assertEquals(listOf("h2", "http/1.1"), trojan["alpn"])
        assertEquals("/ws", (trojan["ws-opts"] as Map<*, *>)["path"])
    }

    @Test fun hysteria2PreservesHoppingObfuscationAndCertificatePin() {
        val node = nodes(decode("hy2://p%40ss@a.example:443,20000-30000?obfs=salamander&obfs-password=op&sni=s.example&pinSHA256=pin&alpn=h3#H")).single()
        assertEquals("hysteria2", node["type"])
        assertEquals("p@ss", node["password"])
        assertEquals("443,20000-30000", node["ports"])
        assertEquals("pin", node["fingerprint"])
        assertEquals(443, node["port"])
        rejected("hy2://p@a.example:30000-20000", "$.ports")
        rejected("hy2://p@a.example?obfs=salamander", "$.query.obfs-password")
    }

    @Test fun tuicV5DoesNotEmitAnUnsupportedUdpField() {
        val node = nodes(decode("tuic://$uuid:p%3Aa@a.example:443?congestion_control=bbr&udp_relay_mode=quic&disable_sni=1&alpn=h3#T")).single()
        assertEquals(uuid, node["uuid"])
        assertEquals("p:a", node["password"])
        assertEquals("bbr", node["congestion-controller"])
        assertEquals(true, node["disable-sni"])
        assertFalse(node.containsKey("udp"))
        rejected("tuic://token@a.example:443", "$.token", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
    }

    @Test fun vmessJsonUsesIndependentFullFieldFixtures() {
        val json = """{"v":"2","ps":"中文","add":"a.example","port":"443","id":"$uuid","aid":"0","scy":"auto","net":"ws","type":"none","host":"front.example","path":"/ws?ed=2048","tls":"tls","sni":"s.example"}"""
        val node = nodes(decode("vmess://${b64(json)}")).single()
        assertEquals(0, node["alterId"])
        assertEquals(true, node["tls"])
        val ws = node["ws-opts"] as Map<*, *>
        assertEquals("/ws", ws["path"])
        assertEquals(2048, ws["max-early-data"])
        assertEquals("front.example", (ws["headers"] as Map<*, *>)["Host"])
        assertFalse(node.containsKey("xudp"))
    }

    @Test fun vmessTcpUsesTheNativeDefaultRatherThanAnUnsupportedExplicitTransport() {
        val json = """{"ps":"V","add":"a.example","port":443,"id":"$uuid","net":"tcp"}"""
        assertFalse(nodes(decode("vmess://${b64(json)}")).single().containsKey("network"))
        assertFalse(nodes(decode("vmess://$uuid@a.example:443?type=tcp")).single().containsKey("network"))
        assertFalse(nodes(decode("trojan://p@a.example:443?type=tcp")).single().containsKey("network"))
        assertEquals("tcp", nodes(decode("vless://$uuid@a.example:443?type=tcp")).single()["network"])
    }

    @Test fun shareJsonRequiresStrictSyntaxAndRejectsEscapedHeaderControls() {
        listOf("{ps:'A'}", "{\"ps\":\"A\",}", "{\"ps\":01}", "{\"ps\":NaN}", "{\"ps\":\"A\"} trailing").forEach {
            rejected("vmess://${b64(it)}", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
        }
        val source = "{\"ps\":\"V\",\"add\":\"a.example\",\"port\":443,\"id\":\"$uuid\",\"net\":\"ws\",\"host\":\"a\\r\\nInjected: value\"}"
        rejected("vmess://${b64(source)}", "$.json")
        rejected("vmess://${b64("{\"add\":123,\"port\":443,\"id\":\"$uuid\"}")}", "$.vmess.add")
    }

    @Test fun actualRustPluginSupportIsNotDisabledByAMissingClientAdmissionWrapper() {
        val shadow = nodes(decode("ss://${b64("aes-128-gcm:p")}@a.example:443?plugin=${escaped("shadow-tls;host=t.example;password=p;strict=true")}")).single()
        assertEquals("shadow-tls", shadow["plugin"])
        val v2ray = nodes(decode("ss://${b64("aes-128-gcm:p")}@a.example:443?plugin=${escaped("v2ray-plugin;mode=websocket;host=t.example;port=443;headers={\"X-Test\":\"value\"}")}")).single()
        val options = v2ray["plugin-opts"] as Map<*, *>
        assertEquals(443, options["port"])
        assertEquals("value", (options["headers"] as Map<*, *>)["X-Test"])
    }

    @Test fun vmessUriAndVlessWsH2GrpcRealityXhttpKeepSupportedFields() {
        val vmess = nodes(decode("vmess://$uuid@a.example:443?type=h2&path=%2Fh2&host=h.example&encryption=auto#V")).single()
        assertEquals(listOf("h.example"), (vmess["h2-opts"] as Map<*, *>)["host"])
        val ws = nodes(decode("vless://$uuid@a.example:443?type=ws&path=%2Fws&host=f.example&ed=1024&eh=X-Early&security=tls&encryption=none#V")).single()
        assertEquals("X-Early", (ws["ws-opts"] as Map<*, *>)["early-data-header-name"])
        assertFalse(ws.containsKey("client-fingerprint"))
        assertFalse(ws.containsKey("encryption"))
        val grpc = nodes(decode("vless://$uuid@a.example:443?type=grpc&serviceName=service#V")).single()
        assertEquals("service", (grpc["grpc-opts"] as Map<*, *>)["grpc-service-name"])
        val reality = nodes(decode("vless://$uuid@a.example:443?security=reality&pbk=key&sid=ab&flow=xtls-rprx-vision#V")).single()
        assertEquals("xtls-rprx-vision", reality["flow"])
        assertEquals("key", (reality["reality-opts"] as Map<*, *>)["public-key"])
        val extra = "{\"headers\":{\"X-Test\":\"value\"},\"xPaddingBytes\":\"100-200\"}"
        val xhttp = nodes(decode("vless://$uuid@a.example:443?type=xhttp&path=%2Fx&mode=auto&extra=${escaped(extra)}#V")).single()
        assertEquals("100-200", (xhttp["xhttp-opts"] as Map<*, *>)["x-padding-bytes"])
    }

    @Test fun namesFollowFullReservedAndNumberedDeduplication() {
        val result = nodes(decode("trojan://p@a.example:443#DIRECT", "trojan://p@a.example:443#DIRECT", "trojan://p@a.example:443#-01", "trojan://p@a.example:443"))
        assertEquals(listOf("DIRECT-01", "DIRECT-02", "a.example", "a.example-01"), result.map { it["name"] })
        val urls = runBlocking { decoder.decode(listOf("https://a.example/a", "https://a.example/b"), setOf("a.example")) }
        assertEquals(listOf("a.example-01", "a.example-02"), urls.entries.map { it.name })
    }

    @Test fun explicitUnsupportedCapabilitiesReturnExactFieldErrors() {
        listOf("fp=chrome" to "fp", "alpn=h2" to "alpn", "packetEncoding=xudp" to "packetEncoding",
            "encryption=mlkem" to "encryption", "type=httpupgrade" to "type", "headerType=http" to "headerType",
            "support-x25519mlkem768=true" to "support-x25519mlkem768").forEach { (query, field) ->
            rejected("vless://$uuid@a.example:443?$query", "$.query.$field", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        }
        rejected("hy2://p@a.example?up=30", "$.query.up", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        rejected("anytls://p@a.example:443?hpkp=pin", "$.query.hpkp", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
        rejected("ss://${b64("aes-128-gcm:p")}@a.example:443?uot=1", "$.query.udp-over-tcp", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)
    }

    @Test fun unknownDuplicateAndConflictingParametersAreNeverSilentlyDropped() {
        rejected("trojan://p@a.example:443?mystery=value", "$.query.mystery")
        rejected("trojan://p@a.example:443?sni=a&%73ni=b", "$.query.sni", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
        rejected("trojan://p@a.example:443?insecure=1&allowInsecure=0", code = ProfileLinkErrorCode.DUPLICATE_PARAMETER)
        rejected("trojan://p@a.example:443?insecure=maybe")
        rejected("vmess://${b64("{\"ps\":\"A\",\"ps\":\"B\"}")}", code = ProfileLinkErrorCode.INVALID_CONFIGURATION)
    }

    @Test fun unsafeUrlsAndMalformedEncodingsAreRejectedWithoutEchoingSecrets() {
        listOf("https://user:secret@a.example/a", "https://a.example/a#fragment", "https://a.example/a%0d%0aheader",
            "https://a.example/%C3%28", "https://a.example/a b").forEach { rejected(it) }
        rejected("file:///private", code = ProfileLinkErrorCode.UNSUPPORTED_SCHEME)
        rejected("trojan://secret@a.example:99999", "$.port")
        rejected("trojan://p@a.example:443#%GG", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("vmess://@@@@", code = ProfileLinkErrorCode.INVALID_URL)
        rejected("trojan://p@a.example:443#%C3%28", code = ProfileLinkErrorCode.INVALID_ENCODING)
        rejected("\uD800", code = ProfileLinkErrorCode.INVALID_ENCODING)
        assertFalse(decode("trojan://secret@a.example:99999").errors.toString().contains("secret"))
        assertFalse(inline(decode("trojan://secret@a.example:443#N")).toString().contains("secret"))
        assertTrue(decode("http://127.0.0.1:8080/profile").entries.single() is ProfileLinkEntry.Subscription)
    }

    @Test fun configurableLimitsBoundInputsNodesParametersAndExpandedOutput() {
        val limited = ProfileLinkDecoder(ProfileLinkLimits(maxInputs = 2, maxLinks = 2, maxNodes = 1, maxParameters = 1))
        val inputOverflow = runBlocking { limited.decode(listOf("https://a.example", "https://b.example", "https://c.example")) }
        assertEquals(ProfileLinkErrorCode.LIMIT_EXCEEDED, inputOverflow.errors.single().code)
        val nodeOverflow = runBlocking { limited.decode(listOf("trojan://p@a.example:443\ntrojan://p@b.example:443")) }
        assertEquals(2, nodeOverflow.rejectedShareSources.size)
        assertEquals(ProfileLinkErrorCode.LIMIT_EXCEEDED, nodeOverflow.errors.single().code)
        val parameters = runBlocking { limited.decode("trojan://p@a.example:443?sni=a&insecure=1") }
        assertEquals(ProfileLinkErrorCode.LIMIT_EXCEEDED, parameters.errors.single().code)
        val lines = runBlocking { limited.decode((1..10000).joinToString("\n") { "https://a.example/$it" }) }
        assertEquals(2, lines.entries.size)
        assertEquals(1, lines.errors.size)
    }

    @Test fun limitsAlsoCoverRawProviderPayloadsAndOpaqueHttpParameters() {
        val limited = ProfileLinkDecoder(ProfileLinkLimits(maxNodes = 1, maxParameters = 1))
        val payload = "proxy-providers: {P: {type: inline, payload: [{name: A, type: direct}, {name: B, type: direct}]}}"
        val raw = runBlocking { limited.decode(payload) }
        assertEquals(ProfileLinkErrorCode.LIMIT_EXCEEDED, raw.errors.single().code)
        val query = runBlocking { limited.decode("https://a.example/sub?one=1&two=2") }
        assertEquals(ProfileLinkErrorCode.LIMIT_EXCEEDED, query.errors.single().code)
        rejected("https://a.example/sub?token=a&%74oken=b", "$.url.query", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
        assertEquals("https://a.example/sub?filters%5Bname%5D=a%2Bb", (decode("https://a.example/sub?filters%5Bname%5D=a%2Bb").entries.single() as ProfileLinkEntry.Subscription).url)
    }
}
