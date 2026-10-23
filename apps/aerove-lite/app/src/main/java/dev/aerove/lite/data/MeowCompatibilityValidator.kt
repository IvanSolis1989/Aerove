package dev.aerove.lite.data

/** Candidate admission boundary for fixed meow-rs 727ddcd0; native strict parsing is still mandatory. */
internal object MeowCompatibilityValidator {
    fun normalize(yaml: String, verifiedNativeFeatures: Set<String> = emptySet()): String =
        ConfigYaml.dump(normalize(ConfigYaml.parse(yaml), verifiedNativeFeatures))

    fun normalize(source: Map<String, Any?>, verifiedNativeFeatures: Set<String> = emptySet()): MutableMap<String, Any?> {
        // Reuse the bounded YAML copier: caller-owned maps and aliases are never mutated.
        val root = ConfigYaml.parse(ConfigYaml.dump(source))
        checkFields(root, "$", setOf("strict", "port", "socks-port", "mixed-port", "allow-lan", "bind-address", "mode", "log-level", "ipv6", "external-controller", "secret", "dns", "proxies", "proxy-groups", "rules", "tun", "hosts", "sniffer", "rule-providers", "proxy-providers", "geodata"))
        root["strict"]?.let { if (it != true) fail("$.strict", "Strict parsing must remain enabled") }
        root["strict"] = true
        MeowEngineSchema.validate(root)
        for (key in listOf("port", "socks-port", "mixed-port")) root[key]?.let { integer(it, "$.${key}", 0, 65535) }
        for (key in listOf("allow-lan", "ipv6")) root[key]?.let { bool(it, "$.${key}") }
        for (key in listOf("bind-address", "external-controller", "secret", "log-level")) root[key]?.let { string(it, "$.${key}") }
        root["mode"]?.let { choice(it, "$.mode", setOf("rule", "global", "direct")) }
        root["dns"]?.let { dns(mapping(it, "$.dns")) }
        root["proxies"]?.let { list(it, "$.proxies").forEachIndexed { i, v -> proxy(mapping(v, "$.proxies[$i]"), "$.proxies[$i]", verifiedNativeFeatures) } }
        root["proxy-groups"]?.let { list(it, "$.proxy-groups").forEachIndexed { i, v -> group(mapping(v, "$.proxy-groups[$i]"), "$.proxy-groups[$i]") } }
        root["rules"]?.let { list(it, "$.rules").forEachIndexed { i, v -> rule(string(v, "$.rules[$i]"), "$.rules[$i]") } }
        return root
    }

    private fun proxy(p: MutableMap<String, Any?>, path: String, features: Set<String>) {
        val type = string(p["type"], "$path.type")
        if (type in setOf("tuic", "ssh", "wireguard", "tailscale", "shadowquic")) fail("$path.type", "No outbound implementation in fixed Meow v0.26.1")
        if (type in setOf("ss", "trojan", "anytls", "vmess", "vless", "hysteria2", "snell", "trusttunnel") && type !in features)
            fail("$path.type", "Core supports $type; native feature verification required: $type")
        if (type in setOf("ss", "vless", "http", "socks5", "hysteria2", "snell", "trusttunnel")) {
            MeowProxySchema.validate(p, path, features)
            return
        }
        val common = setOf("name", "type", "server", "port", "udp")
        val fields = when (type) {
            "ss" -> setOf("password", "cipher")
            "trojan", "anytls" -> setOf("password", "sni", "skip-cert-verify")
            "vmess" -> setOf("uuid", "alterId", "alter-id", "cipher", "network", "tls", "servername", "server-name", "alpn", "client-fingerprint", "skip-cert-verify", "ws-opts")
            "direct" -> emptySet()
            // A pending admission audit is a delivery gate, never a claim of missing core capability.
            else -> fail("$path.type", "Unknown proxy type or spelling")
        }
        checkFields(p, path, if (type == "direct") setOf("name", "type") else common + fields)
        string(p["name"], "$path.name")
        if (type == "direct") return
        string(p["server"], "$path.server")
        integer(p["port"], "$path.port", 1, 65535)
        p["udp"]?.let { bool(it, "$path.udp") }
        if (type in setOf("ss", "trojan", "anytls")) string(p["password"], "$path.password")
        if (type == "ss") string(p["cipher"], "$path.cipher")
        p["sni"]?.let { string(it, "$path.sni") }
        p["skip-cert-verify"]?.let { bool(it, "$path.skip-cert-verify") }
        if (type != "vmess") return
        alias(p, "server-name", "servername", path)
        alias(p, "alter-id", "alterId", path)
        val uuid = string(p["uuid"], "$path.uuid")
        if (!uuid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) fail("$path.uuid", "Expected UUID")
        p["alterId"]?.let { integer(it, "$path.alterId", 0, 0) }
        p["cipher"]?.let { choice(it, "$path.cipher", setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none")) }
        val network = p["network"] ?: "tcp"
        choice(network, "$path.network", setOf("tcp", "ws"))
        p["tls"]?.let { bool(it, "$path.tls") }
        for (key in listOf("servername", "alpn", "client-fingerprint", "skip-cert-verify")) if (p.containsKey(key) && p["tls"] != true) fail("$path.$key", "TLS option would be ignored without tls: true")
        for (key in listOf("servername", "client-fingerprint")) p[key]?.let { string(it, "$path.$key") }
        p["alpn"]?.let { strings(it, "$path.alpn") }
        p["ws-opts"]?.let {
            if (network != "ws") fail("$path.ws-opts", "WS options would be ignored without network: ws")
            val ws = mapping(it, "$path.ws-opts")
            checkFields(ws, "$path.ws-opts", setOf("path", "headers", "max-early-data", "early-data-header-name"))
            for (key in listOf("path", "early-data-header-name")) ws[key]?.let { v -> string(v, "$path.ws-opts.$key") }
            ws["max-early-data"]?.let { v -> integer(v, "$path.ws-opts.max-early-data", 0, 2048) }
            ws["headers"]?.let { v ->
                val headers = mapping(v, "$path.ws-opts.headers")
                checkFields(headers, "$path.ws-opts.headers", setOf("Host"))
                headers["Host"]?.let { h -> string(h, "$path.ws-opts.headers.Host") }
            }
        }
    }

    private fun dns(d: MutableMap<String, Any?>) {
        val path = "$.dns"
        checkFields(d, path, setOf("enable", "listen", "enhanced-mode", "fake-ip-range", "fake-ip-filter-mode", "store-fake-ip", "default-nameserver", "nameserver", "fallback", "proxy-server-nameserver", "fake-ip-filter", "use-hosts", "use-system-hosts", "nameserver-policy", "fallback-filter"))
        for (key in listOf("enable", "store-fake-ip", "use-hosts", "use-system-hosts")) d[key]?.let { bool(it, "$path.$key") }
        for (key in listOf("listen", "fake-ip-range")) d[key]?.let { string(it, "$path.$key") }
        d["enhanced-mode"]?.let { choice(it, "$path.enhanced-mode", setOf("normal", "redir-host", "fake-ip")) }
        d["fake-ip-filter-mode"]?.let { choice(it, "$path.fake-ip-filter-mode", setOf("whitelist", "white-list", "blacklist", "black-list")) }
        d["fake-ip-filter"]?.let { strings(it, "$path.fake-ip-filter") }
        for (key in listOf("default-nameserver", "nameserver", "fallback", "proxy-server-nameserver")) d[key]?.let { resolvers(it, "$path.$key") }
        d["nameserver-policy"]?.let { mapping(it, "$path.nameserver-policy").forEach { (key, value) ->
            if (key.contains(':') && !key.startsWith("geosite:") && !key.startsWith("rule-set:")) fail("$path.nameserver-policy.$key", "Unsupported policy prefix")
            if (value is String) resolver(value, "$path.nameserver-policy.$key") else resolvers(value, "$path.nameserver-policy.$key")
        } }
        d["fallback-filter"]?.let {
            val f = mapping(it, "$path.fallback-filter")
            checkFields(f, "$path.fallback-filter", setOf("geoip", "geoip-code", "ipcidr", "domain"))
            f["geoip"]?.let { v -> bool(v, "$path.fallback-filter.geoip") }
            f["geoip-code"]?.let { v -> string(v, "$path.fallback-filter.geoip-code") }
            for (key in listOf("ipcidr", "domain")) f[key]?.let { v -> strings(v, "$path.fallback-filter.$key") }
        }
    }

    private fun group(g: MutableMap<String, Any?>, path: String) {
        checkFields(g, path, setOf("name", "type", "proxies", "use", "url", "interval", "tolerance", "expected-status", "strategy", "lazy", "filter", "exclude-filter", "exclude-type", "include-all", "include-all-proxies", "include-all-providers", "hidden", "icon"))
        string(g["name"], "$path.name")
        choice(g["type"], "$path.type", setOf("select", "url-test", "fallback", "load-balance", "relay"))
        g["strategy"]?.let { if (g["type"] != "load-balance") fail("$path.strategy", "Strategy only applies to load-balance"); choice(it, "$path.strategy", setOf("round-robin", "consistent-hashing", "")) }
        for (key in listOf("proxies", "use", "exclude-type")) g[key]?.let { strings(it, "$path.$key") }
        for (key in listOf("url", "expected-status", "filter", "exclude-filter", "icon")) g[key]?.let { string(it, "$path.$key") }
        for (key in listOf("lazy", "include-all", "include-all-proxies", "include-all-providers", "hidden")) g[key]?.let { bool(it, "$path.$key") }
        for (key in listOf("interval", "tolerance")) g[key]?.let { integer(it, "$path.$key", 0, if (key == "tolerance") 65535 else Long.MAX_VALUE) }
    }

    private fun rule(value: String, path: String) {
        val type = value.substringBefore(',')
        val supported = setOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "DOMAIN-WILDCARD", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "IP-SUFFIX", "SRC-IP-SUFFIX", "SRC-PORT", "DST-PORT", "NETWORK", "PROCESS-NAME", "PROCESS-PATH", "GEOIP", "SRC-GEOIP", "GEOSITE", "IP-ASN", "SRC-IP-ASN", "IN-PORT", "IN-NAME", "IN-TYPE", "IN-USER", "DSCP", "UID", "MATCH", "RULE-SET")
        if (type !in supported) fail(path, "Rule grammar admission pending or unsupported: $type")
    }

    private fun alias(m: MutableMap<String, Any?>, old: String, key: String, path: String) {
        if (!m.containsKey(old)) return
        if (m.containsKey(key) && m[key] != m[old]) fail("$path.$old", "Conflicts with $key")
        m[key] = m.remove(old)
    }
    private fun resolver(value: String, path: String) {
        val scheme = value.substringBefore("://", "")
        if (scheme.isNotEmpty() && scheme !in setOf("udp", "tcp", "tls", "https", "rcode")) fail(path, "Unsupported DNS scheme: $scheme")
    }
    private fun resolvers(value: Any?, path: String) = strings(value, path).forEachIndexed { i, v -> resolver(v, "$path[$i]") }
    private fun strings(value: Any?, path: String): List<String> = list(value, path).mapIndexed { i, v -> string(v, "$path[$i]") }
    private fun list(value: Any?, path: String): List<*> = value as? List<*> ?: fail(path, "Expected list")
    private fun string(value: Any?, path: String): String = value as? String ?: fail(path, "Expected string")
    private fun bool(value: Any?, path: String) { if (value !is Boolean) fail(path, "Expected boolean") }
    private fun integer(value: Any?, path: String, min: Long, max: Long) {
        if (value !is Byte && value !is Short && value !is Int && value !is Long) fail(path, "Expected integer")
        if ((value as Number).toLong() !in min..max) fail(path, "Expected integer in $min..$max")
    }
    private fun choice(value: Any?, path: String, choices: Set<String>) { if (string(value, path) !in choices) fail(path, "Unsupported value: $value") }
    @Suppress("UNCHECKED_CAST")
    private fun mapping(value: Any?, path: String): MutableMap<String, Any?> = value as? MutableMap<String, Any?> ?: fail(path, "Expected mapping")
    private fun checkFields(map: Map<String, Any?>, path: String, allowed: Set<String>) {
        map.forEach { (key, value) -> if (key !in allowed) fail("$path.$key", "Unknown or unverified field; refusing semantic loss") else if (value == null) fail("$path.$key", "Explicit null is not admitted") }
    }
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
}
