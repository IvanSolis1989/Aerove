package dev.aerove.lite.data

/** The source-side TUN/listener/resource ownership gate shared by profile admission and composition. */
internal object MeowSourceConfig {
    private fun invalid(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
    fun input(source: String, tunReconciliation: MeowTunReconciliation? = null, sourceAdmission: Boolean = false): Pair<MutableMap<String, Any?>, List<Any?>> {
        val root = ConfigYaml.parse(source)
        MeowGeoPolicy.read(root, dev.aerove.lite.resources.GeoResourcePreferences())
        MeowGeoOrigin.read(root)
        root.remove(MeowGeoOrigin.KEY)
        if (root.containsKey("inbound-providers")) invalid("$.inbound-providers", "No Meow v0.26.1 schema; bridge support is required")
        listOf("firewall", "udp", "udp-timeout").forEach { if (root.containsKey(it)) invalid("$.$it", "Meow warns and ignores this root field") }
        val policy = MeowTunReconciliation.sourcePolicy(source)
        val explicit = policy.keys.firstOrNull { it != "enable" || policy[it] == true }
        if (explicit != null && !sourceAdmission) {
            if (tunReconciliation == null) invalid("$.tun.$explicit", "Explicit source TUN policy requires Android Builder reconciliation before activation")
            tunReconciliation.verify(source)
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
