package dev.aerove.lite.profilebuilder

/** Field types are explicit: passwords, numeric ports and nested transports never pass through YAML text input. */
enum class BuilderValueKind { Text, Number, Toggle, List, Object }

object ProfileBuilderSchema {
    val proxyTypes = listOf("ss", "socks5", "trojan", "vmess", "vless", "anytls", "tuic", "hysteria2", "ssh", "direct", "reject")
    val groupTypes = listOf("select", "url-test", "fallback", "load-balance", "relay", "smart")
    val ruleTypes = listOf("DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX", "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR", "GEOIP", "GEOSITE", "RULE-SET", "DST-PORT", "SRC-PORT", "NETWORK", "AND", "OR", "NOT", "MATCH")
    val decimalFields = setOf("site-stickiness", "bandwidth-weight")
    private val numbers = setOf("port", "alter-id", "alterId", "interval", "timeout", "tolerance", "max-retries", "max-early-data", "hop-interval", "hop_interval", "udp-mtu", "cwnd", "max-open-stream", "gc-interval", "gc-lifetime", "send-window", "receive-window", "x-padding-bytes", "heartbeat-interval", "request-timeout", "max-udp-relay-packet-size", "digits", "step", "screw", "up", "down") + decimalFields
    private val toggles = setOf("udp", "tls", "skip-cert-verify", "lazy", "enable", "disable-sni", "reduce-rtt", "fast-open", "disable-mtu-discovery", "strict", "mux")
    private val lists = setOf("proxies", "use", "alpn", "host-key", "host-key-algorithms", "payload", "inline-rules")
    private val objects = setOf("ws-opts", "h2-opts", "grpc-opts", "xhttp-opts", "splithttp-opts", "reality-opts", "plugin-opts", "headers", "health-check", "totp-opt", "common")
    fun kind(key: String): BuilderValueKind = when (key) {
        in numbers -> BuilderValueKind.Number
        in toggles -> BuilderValueKind.Toggle
        in lists -> BuilderValueKind.List
        in objects -> BuilderValueKind.Object
        else -> BuilderValueKind.Text
    }
    fun defaultValue(key: String): Any = when (kind(key)) {
        BuilderValueKind.Number -> 0L
        BuilderValueKind.Toggle -> false
        BuilderValueKind.List -> emptyList<String>()
        BuilderValueKind.Object -> emptyMap<String, Any?>()
        BuilderValueKind.Text -> ""
    }
    fun fields(section: BuilderSection, type: String): List<String> = when (section) {
        BuilderSection.Proxies -> (listOf("type", "server", "port", "dialer-proxy", "connect-via") + when (type) {
            "ss" -> listOf("cipher", "password", "udp", "plugin", "plugin-opts")
            "socks5" -> listOf("username", "password", "tls", "sni", "skip-cert-verify", "udp")
            "trojan" -> listOf("password", "alpn", "sni", "skip-cert-verify", "udp", "network", "grpc-opts", "ws-opts", "tls-cert", "tls-key")
            "vmess", "vless" -> listOf("uuid", "udp", "tls", "skip-cert-verify", "servername", "network", "ws-opts", "h2-opts", "grpc-opts", "xhttp-opts", "splithttp-opts", "tls-cert", "tls-key") +
                if (type == "vmess") listOf("alter-id", "cipher") else listOf("reality-opts", "flow")
            "anytls" -> listOf("password", "alpn", "sni", "skip-cert-verify", "udp", "tls-cert", "tls-key")
            "tuic" -> listOf("uuid", "password", "ip", "heartbeat-interval", "alpn", "disable-sni", "reduce-rtt", "request-timeout", "udp-relay-mode", "congestion-controller", "max-udp-relay-packet-size", "fast-open", "skip-cert-verify", "max-open-stream", "sni", "gc-interval", "gc-lifetime", "send-window", "receive-window", "tls-cert", "tls-key")
            "hysteria2" -> listOf("ports", "hop-interval", "password", "obfs", "obfs-password", "alpn", "up", "down", "sni", "skip-cert-verify", "ca", "ca-str", "fingerprint", "udp-mtu", "disable-mtu-discovery", "cwnd", "tls-cert", "tls-key")
            "ssh" -> listOf("username", "password", "private-key", "private-key-passphrase", "host-key", "host-key-algorithms", "totp-opt")
            else -> emptyList()
        }).let { if (type == "direct" || type == "reject") listOf("type") else it }.distinct()
        BuilderSection.Groups -> listOf("type", "proxies", "use", "icon", "url") + when (type) {
            "select" -> listOf("udp")
            "url-test" -> listOf("interval", "lazy", "tolerance")
            "fallback" -> listOf("interval", "lazy")
            "load-balance" -> listOf("interval", "lazy", "strategy")
            "smart" -> listOf("udp", "lazy", "max-retries", "site-stickiness", "bandwidth-weight")
            else -> emptyList()
        }
        BuilderSection.ProxyProviders -> listOf("type", "path", "interval", "health-check") + if (type == "http") listOf("url") else emptyList()
        BuilderSection.RuleProviders -> listOf("type", "behavior", "path", "payload", "inline-rules") +
            if (type == "inline") emptyList() else listOf("format", "interval") + if (type == "http") listOf("url") else emptyList()
        BuilderSection.Rules -> emptyList()
    }
    fun nestedFields(key: String): List<String> = when (key) {
        "ws-opts" -> listOf("path", "headers", "max-early-data", "early-data-header-name")
        "h2-opts" -> listOf("host", "path")
        "grpc-opts" -> listOf("grpc-service-name")
        "xhttp-opts", "splithttp-opts" -> listOf("path", "host", "mode", "headers", "x-padding-bytes")
        "reality-opts" -> listOf("public-key", "short-id")
        "plugin-opts" -> listOf("mode", "host", "path", "password", "strict", "tls", "mux", "skip-cert-verify", "port", "headers")
        "health-check" -> listOf("enable", "url", "interval", "lazy")
        "totp-opt" -> listOf("otp-auth", "common")
        "common" -> listOf("secret", "screw", "step", "digits", "algorithm")
        else -> emptyList()
    }
}
