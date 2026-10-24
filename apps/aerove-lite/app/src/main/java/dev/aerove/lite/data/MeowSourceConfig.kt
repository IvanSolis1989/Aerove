package dev.aerove.lite.data

/** The source-side TUN/listener/resource ownership gate shared by profile admission and composition. */
internal object MeowSourceConfig {
    private fun invalid(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
    fun input(source: String): Pair<MutableMap<String, Any?>, List<Any?>> {
        val root = ConfigYaml.parse(source)
        if (root.containsKey("inbound-providers")) invalid("$.inbound-providers", "No Meow v0.26.1 schema; bridge support is required")
        listOf("firewall", "udp", "udp-timeout").forEach { if (root.containsKey(it)) invalid("$.$it", "Meow warns and ignores this root field") }
        val tun = root["tun"]
        if (tun != null && tun !is Map<*, *>) invalid("$.tun", "Expected an object")
        (tun as? Map<*, *>)?.forEach { (key, _) ->
            if (key !in setOf("enable", "device", "mtu", "inet4-address", "inet6-address", "auto-route", "dns-hijack"))
                invalid("$.tun.$key", "Not consumed by the Android typed TUN plan; warn-only fields cannot be ignored")
        }
        // Source TUN device/address/route choices cannot silently supersede the actual Android Builder.
        (tun as? Map<*, *>)?.forEach { (key, value) ->
            val valid = when (key) {
                "enable" -> value is Boolean
                "device" -> value is String && value.isNotBlank() && !value.startsWith("fd://")
                "mtu" -> value is Number && value.toLong() in 1280..65535 && value.toDouble() == value.toLong().toDouble()
                "auto-route" -> value is Boolean || value in setOf("global", "fake-ip")
                "dns-hijack" -> value is List<*> && value.all { it is String && it == "any:53" }
                "inet4-address", "inet6-address" -> value is String && value.isNotBlank()
                else -> false
            }
            if (!valid) invalid("$.tun.$key", "Invalid Meow RawTun value")
            if (key in setOf("device", "inet4-address", "inet6-address", "auto-route"))
                invalid("$.tun.$key", "Explicit source TUN policy requires Android Builder reconciliation before activation")
        }
        val raw = root.remove("listeners") ?: emptyList<Any?>()
        if (raw !is List<*>) invalid("$.listeners", "Expected a list")
        val names = mutableSetOf<String>()
        raw.forEachIndexed { index, item ->
            val path = "$.listeners[$index]"
            val row = item as? Map<*, *> ?: invalid(path, "Expected an object")
            val allowed = setOf("name", "type", "port", "listen", "tproxy-sni", "firewall", "udp-timeout", "max-connections", "cipher", "password", "udp", "simple-obfs")
            row.keys.forEach { if (it !in allowed) invalid("$path.$it", "Unknown or warn-only listener field") }
            val name = row["name"] as? String ?: invalid("$path.name", "Expected a name")
            if (name.isBlank() || !names.add(name)) invalid("$path.name", "Empty or duplicate listener name")
            val type = row["type"] as? String ?: invalid("$path.type", "Expected a protocol")
            if (type !in setOf("mixed", "http", "socks5", "tproxy", "shadowsocks", "ss")) invalid("$path.type", "Unknown Meow listener protocol")
            if (row["port"] !is Int || (row["port"] as Int) !in 1..65535) invalid("$path.port", "Explicit non-ephemeral port required by host listener ownership")
            if (row.containsKey("listen") && row["listen"] !is String) invalid("$path.listen", "Expected a bind address")
            if (type == "tproxy" && row["firewall"] != false) invalid("$path.firewall", "Android owns routing; native firewall must be false")
            if (type in setOf("ss", "shadowsocks") && listOf("cipher", "password").any { (row[it] as? String).isNullOrEmpty() })
                invalid(path, "Shadowsocks listener requires cipher and password")
        }
        (root["geodata"] as? Map<*, *>)?.keys?.forEach {
            if (it in setOf("geodata-mode", "geodata-loader", "geoip-matcher")) invalid("$.geodata.$it", "Meow warns and ignores this field")
        }
        @Suppress("UNCHECKED_CAST")
        (root["geodata"] as? MutableMap<String, Any?>)?.let { geo ->
            mapOf("mmdb-path" to "mmdb", "asn-path" to "asn-mmdb", "geosite-path" to "geosite").forEach { (key, old) ->
                if (geo.containsKey(key)) {
                    val path = geo.remove(key) as? String ?: invalid("$.geodata.$key", "Expected a resource path")
                    if (root.containsKey(old)) invalid("$.geodata.$key", "Duplicate resource path")
                    root[old] = path
                }
            }
        }
        return root to raw
    }

}
