package dev.aerove.lite.data

/** Engine-root syntax admission only. Resource containment and native lifecycle admission remain separate gates. */
internal object MeowEngineSchema {
    fun validate(root: MutableMap<String, Any?>) {
        root["tun"]?.let { value ->
            val p = "$.tun"; val m = map(value, p)
            fields(m, p, setOf("enable", "auto-route"))
            for (key in listOf("enable", "auto-route")) if (m[key] != false) fail("$p.$key", "Engine TUN must be explicitly disabled; Android typed FD plan owns activation and routing")
        }
        root["hosts"]?.let { value -> map(value, "$.hosts").forEach { (host, v) ->
            if (host.isBlank()) fail("$.hosts", "Empty hosts pattern")
            if (v is String) text(v, "$.hosts.$host") else strings(v, "$.hosts.$host").also { if (it.isEmpty()) fail("$.hosts.$host", "Empty hosts address list") }
            // IP/domain/alias grammar and alias cycles belong to fixed native build_hosts_trie, without DNS I/O here.
        } }
        root["sniffer"]?.let { sniff(map(it, "$.sniffer")) }
        root["geodata"]?.let { geo(map(it, "$.geodata")) }
        for (kind in listOf("rule-providers", "proxy-providers")) root[kind]?.let { v ->
            map(v, "$.$kind").forEach { (name, entry) ->
                if (name.isBlank()) fail("$.$kind", "Empty provider name")
                provider(map(entry, "$.$kind.$name"), "$.$kind.$name", kind == "rule-providers")
            }
        }
    }

    private fun sniff(m: MutableMap<String, Any?>) {
        val p = "$.sniffer"
        fields(m, p, setOf("enable", "timeout", "parse-pure-ip", "override-destination", "sniff", "force-domain", "skip-domain"))
        for (key in listOf("enable", "parse-pure-ip", "override-destination")) m[key]?.let { bool(it, "$p.$key") }
        m["timeout"]?.let { uint(it, "$p.timeout", 1, 60000) }
        for (key in listOf("force-domain", "skip-domain")) m[key]?.let { strings(it, "$p.$key") }
        var count = 0L
        m["sniff"]?.let { v ->
            val protocols = map(v, "$p.sniff")
            val normalized = mutableSetOf<String>()
            protocols.forEach { (protocol, value) ->
                val path = "$p.sniff.$protocol"
                if (protocol.uppercase() !in setOf("TLS", "HTTP")) fail(path, "Fixed sniffer consumes TLS/HTTP only; QUIC and unknown protocols cannot be ignored")
                if (!normalized.add(protocol.uppercase())) fail(path, "Conflicting case aliases for sniff protocol")
                val row = map(value, path); fields(row, path, setOf("ports"))
                row["ports"]?.let { ports ->
                    val list = ports as? List<*> ?: fail("$path.ports", "Expected port list")
                    list.forEachIndexed { i, port ->
                        val q = "$path.ports[$i]"
                        val span = if (port is String) {
                            val ns = port.split('-').map { it.trim().toLongOrNull() ?: fail(q, "Expected port or port range") }
                            if (ns.size !in 1..2 || ns.any { it !in 0..65535 } || ns.first() > ns.last()) fail(q, "Invalid port range")
                            ns.last() - ns.first() + 1
                        } else { uint(port, q, 0, 65535); 1L }
                        count += span
                        if (count > 65536) fail(q, "Expanded sniff port count exceeds 65536")
                    }
                }
            }
        }
        if (m["enable"] == true && count == 0L) fail("$p.sniff", "Enabled fixed sniffer requires TLS or HTTP ports")
    }

    private fun geo(m: MutableMap<String, Any?>) {
        val p = "$.geodata"
        fields(m, p, setOf("mmdb-path", "asn-path", "geosite-path", "auto-update", "auto-update-interval", "url"))
        for (key in listOf("mmdb-path", "asn-path", "geosite-path")) m[key]?.let { text(it, "$p.$key") }
        m["auto-update"]?.let { bool(it, "$p.auto-update") }
        m["auto-update-interval"]?.let { uint(it, "$p.auto-update-interval", 1, 87600) }
        m["url"]?.let { v -> val urls = map(v, "$p.url"); fields(urls, "$p.url", setOf("mmdb", "asn", "geosite")); urls.forEach { (key, value) -> url(value, "$p.url.$key") } }
    }

    private fun provider(m: MutableMap<String, Any?>, p: String, rules: Boolean) {
        val common = setOf("type", "url", "path", "interval", "proxy", "header")
        fields(m, p, common + if (rules) setOf("behavior", "format", "payload") else setOf("filter", "exclude-filter", "exclude-type", "health-check", "allow-external-plugin", "dialer-proxy", "override"))
        val type = text(m["type"], "$p.type")
        if (type !in if (rules) setOf("http", "file", "inline") else setOf("http", "file")) fail("$p.type", "Unsupported provider type")
        if (type == "file") text(m["path"], "$p.path")
        if (type == "http") url(m["url"], "$p.url")
        m["path"]?.let { text(it, "$p.path") }
        m["interval"]?.let { uint(it, "$p.interval", 0, 315360000) }
        if (type != "http") for (key in listOf("url", "proxy", "header")) if (m.containsKey(key)) fail("$p.$key", "HTTP fetch field has no consumer for $type provider")
        m["proxy"]?.let { text(it, "$p.proxy", allowEmpty = true) }
        m["header"]?.let { v -> map(v, "$p.header").forEach { (key, value) ->
            if (key.isBlank()) fail("$p.header", "Empty header name")
            if (value is String) text(value, "$p.header.$key", allowEmpty = true) else strings(value, "$p.header.$key")
        } }
        if (rules) {
            if (text(m["behavior"], "$p.behavior") !in setOf("domain", "ipcidr", "classical")) fail("$p.behavior", "Unknown rule-provider behavior")
            m["format"]?.let { if (text(it, "$p.format") !in setOf("yaml", "text", "mrs")) fail("$p.format", "Unknown rule-provider format") }
            if (type != "http" && ((m["interval"] as? Number)?.toLong() ?: 0) > 0) fail("$p.interval", "Fixed file/inline provider does not consume refresh interval")
            if (type == "inline") {
                strings(m["payload"], "$p.payload")
                if (m.containsKey("path")) fail("$p.path", "Inline provider has no path consumer")
            } else if (m.containsKey("payload")) fail("$p.payload", "payload only applies to inline provider")
        } else {
            for (key in listOf("filter", "exclude-filter")) m[key]?.let { text(it, "$p.$key", allowEmpty = true) }
            m["exclude-type"]?.let { if (it is String) text(it, "$p.exclude-type", allowEmpty = true) else strings(it, "$p.exclude-type") }
            m["allow-external-plugin"]?.let { bool(it, "$p.allow-external-plugin"); if (it == true) fail("$p.allow-external-plugin", "External SIP003 process lifecycle/protection is not verified by the host; not an absent core capability") }
            m["dialer-proxy"]?.let { text(it, "$p.dialer-proxy", allowEmpty = true) }
            m["override"]?.let { v -> val over = map(v, "$p.override"); fields(over, "$p.override", setOf("dialer-proxy")); over["dialer-proxy"]?.let { text(it, "$p.override.dialer-proxy", allowEmpty = true) } }
            m["health-check"]?.let { v ->
                val h = map(v, "$p.health-check")
                fields(h, "$p.health-check", setOf("enable", "url", "interval", "timeout", "expected-status", "lazy"))
                for (key in listOf("enable", "lazy")) h[key]?.let { bool(it, "$p.health-check.$key") }
                h["url"]?.let { url(it, "$p.health-check.url") }
                for (key in listOf("interval", "timeout")) h[key]?.let { uint(it, "$p.health-check.$key") }
                h["expected-status"]?.let { if (it is String) text(it, "$p.health-check.expected-status") else uint(it, "$p.health-check.expected-status", 100, 599) }
                if (h["enable"] != false) fail("$p.health-check.enable", "Fixed provider stores manual health checks but has no periodic scheduler; host scheduling must be implemented before activation")
                if (h.keys.any { it != "enable" }) fail("$p.health-check", "Disabled health-check options would not be consumed")
            }
        }
    }

    private fun fields(m: Map<String, Any?>, p: String, keys: Set<String>) { m.forEach { (k, v) -> if (k !in keys) fail("$p.$k", "Unknown or warn-only field; refusing semantic loss") else if (v == null) fail("$p.$k", "Explicit null is not admitted") } }
    private fun text(v: Any?, p: String, allowEmpty: Boolean = false): String = (v as? String ?: fail(p, "Expected string")).also { if (it.isBlank() && (!allowEmpty || it.isNotEmpty())) fail(p, "Expected nonblank string") }
    private fun strings(v: Any?, p: String): List<String> = (v as? List<*> ?: fail(p, "Expected list")).mapIndexed { i, value -> value as? String ?: fail("$p[$i]", "Expected string") }
    private fun bool(v: Any?, p: String) { if (v !is Boolean) fail(p, "Expected boolean") }
    private fun uint(v: Any?, p: String, min: Long = 0, max: Long = Long.MAX_VALUE) { if (v !is Int && v !is Long && v !is Short && v !is Byte) fail(p, "Expected integer"); if ((v as Number).toLong() !in min..max) fail(p, "Integer outside $min..$max") }
    private fun url(v: Any?, p: String) { val s = text(v, p); val u = try { java.net.URI(s) } catch (_: Exception) { fail(p, "Invalid URL") }; if (u.scheme !in setOf("http", "https") || u.host.isNullOrEmpty()) fail(p, "Expected HTTP(S) URL") }
    @Suppress("UNCHECKED_CAST") private fun map(v: Any?, p: String): MutableMap<String, Any?> = v as? MutableMap<String, Any?> ?: fail(p, "Expected mapping")
    private fun fail(p: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(p, reason)))
}
