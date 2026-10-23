package dev.aerove.lite.data

/** Exact option consumers in fixed meow-config/proxy_parser.rs, not serde's permissive map. */
internal object MeowProxySchema {
    private enum class Kind { TEXT, BOOL, UINT, TEXTS, HEADERS }
    private val base = mapOf("name" to Kind.TEXT, "type" to Kind.TEXT, "server" to Kind.TEXT, "port" to Kind.UINT, "udp" to Kind.BOOL)
    private fun text(vararg keys: String) = keys.associateWith { Kind.TEXT }
    private fun flags(vararg keys: String) = keys.associateWith { Kind.BOOL }
    private fun numbers(vararg keys: String) = keys.associateWith { Kind.UINT }

    fun validate(p: MutableMap<String, Any?>, path: String, features: Set<String>) {
        val type = str(p["type"], "$path.type")
        when (type) {
            "ss" -> ss(p, path, features)
            "http", "socks5" -> credentials(p, path, type)
            "snell" -> snell(p, path)
            "trusttunnel" -> trust(p, path, features)
            "hysteria2" -> hy2(p, path)
            "vless" -> vless(p, path, features)
            else -> fail("$path.type", "Unknown proxy type")
        }
        for (key in listOf("name", "server")) nonempty(p[key], "$path.$key")
        if (type != "hysteria2" || p.containsKey("port")) uint(p["port"], "$path.port", if (type == "hysteria2" && p.containsKey("ports")) 0 else 1, 65535)
    }

    private fun ss(p: MutableMap<String, Any?>, path: String, features: Set<String>) {
        schema(p, path, base + text("cipher", "password", "plugin", "client-fingerprint"), setOf("plugin-opts"))
        nonempty(p["cipher"], "$path.cipher"); nonempty(p["password"], "$path.password")
        val plugin = p["plugin"] as? String
        if (p.containsKey("plugin-opts") && plugin == null) fail("$path.plugin-opts", "No selected plugin consumer")
        if (p.containsKey("client-fingerprint") && plugin != "shadow-tls") fail("$path.client-fingerprint", "Only shadow-tls consumes node fingerprint")
        if (plugin != null) {
            if (!MeowSsPluginOptions.isBuiltin(plugin)) fail("$path.plugin", "External SIP003 process policy requires host verification")
            if (plugin in setOf("kcptun", "ech-tls-tunnel")) feature(features, plugin, "$path.plugin")
            if (p["udp"] == true && plugin !in setOf("obfs", "simple-obfs", "kcptun")) fail("$path.udp", "Plugin does not support UDP relay")
            p["plugin-opts"]?.let { p["plugin-opts"] = MeowSsPluginOptions.normalize(plugin, it, "$path.plugin-opts") }
        }
    }

    private fun credentials(p: MutableMap<String, Any?>, path: String, type: String) {
        schema(p, path, base.filterKeys { it != "udp" || type == "socks5" } + text("username", "password") + flags("tls", "skip-cert-verify") + if (type == "http") mapOf("headers" to Kind.HEADERS) else emptyMap())
        if (p.containsKey("username") != p.containsKey("password")) fail("$path.username", "username and password must both be present or absent")
        if (p.containsKey("skip-cert-verify") && p["tls"] != true) fail("$path.skip-cert-verify", "TLS policy would be ignored without tls: true")
    }

    private fun snell(p: MutableMap<String, Any?>, path: String) {
        schema(p, path, base + text("psk", "mode") + flags("reuse"), setOf("version", "obfs-opts"))
        nonempty(p["psk"], "$path.psk")
        val version = p["version"]?.let { v ->
            if (v !is String) uint(v, "$path.version", 3, 6)
            v.toString().trim().lowercase().removePrefix("v").ifEmpty { "4" }
        } ?: "4"
        if (version !in setOf("3", "4", "5", "6")) fail("$path.version", "Only Snell 3/4/5/6 are implemented")
        p["mode"]?.let {
            if (version != "6") fail("$path.mode", "mode requires Snell v6")
            choice(str(it, "$path.mode").trim().lowercase(), "$path.mode", "default", "unshaped", "unsafe-raw")
        }
        p["obfs-opts"]?.let {
            val o = map(it, "$path.obfs-opts")
            schema(o, "$path.obfs-opts", text("mode", "host"))
            val mode = (o["mode"] as? String ?: "off").lowercase()
            choice(mode, "$path.obfs-opts.mode", "off", "none", "", "http", "tls")
            if (version == "6" && mode in setOf("http", "tls")) fail("$path.obfs-opts.mode", "Snell v6 does not implement obfs")
            if (o.containsKey("host") && mode !in setOf("http", "tls")) fail("$path.obfs-opts.host", "host would be ignored without obfs")
            o["host"]?.let { h -> val host = str(h, "$path.obfs-opts.host").trim(); if (host.isEmpty() || host.length > 253 || host.any { c -> c.isWhitespace() || c.isISOControl() }) fail("$path.obfs-opts.host", "Invalid obfs host") }
        }
    }

    private fun trust(p: MutableMap<String, Any?>, path: String, features: Set<String>) {
        schema(p, path, base + text("username", "password", "sni", "name-cert-verify", "client-fingerprint", "platform", "app-name") + flags("quic", "skip-cert-verify") + numbers("max-connections", "min-streams", "max-streams") + mapOf("alpn" to Kind.TEXTS, "headers" to Kind.HEADERS))
        for (key in listOf("username", "password")) nonempty(p[key], "$path.$key")
        val quic = p["quic"] == true
        if (quic) {
            feature(features, "trusttunnel-h3", "$path.quic")
            for (key in listOf("client-fingerprint", "name-cert-verify")) p[key]?.let { if (it != "") fail("$path.$key", "TrustTunnel HTTP/3 cannot honor this TLS policy") }
        }
        p["alpn"]?.let { val a = strings(it, "$path.alpn"); if (a.isNotEmpty() && a != listOf(if (quic) "h3" else "h2")) fail("$path.alpn", "Selected TrustTunnel transport requires ${if (quic) "h3" else "h2"}") }
        val max = (p["max-streams"] as? Number)?.toLong() ?: 0
        val connections = (p["max-connections"] as? Number)?.toLong() ?: 0
        val min = (p["min-streams"] as? Number)?.toLong() ?: 0
        if (max > 0 && connections == 0L && min == 0L) fail("$path.max-streams", "Legacy unbounded pool would be replaced with bounded policy; set modern pool fields explicitly")
        if (max > 0 && max < min) fail("$path.max-streams", "Core would increase max-streams to min-streams")
    }

    private fun hy2(p: MutableMap<String, Any?>, path: String) {
        schema(p, path, base + text("password", "sni", "ports", "obfs", "obfs-password", "fingerprint") + flags("skip-cert-verify", "fast-open"), setOf("up", "down", "hop-interval", "alpn"))
        nonempty(p["password"], "$path.password")
        p["ports"]?.let { ports ->
            val value = nonempty(ports, "$path.ports")
            val wildcard = value.trim() == "*" || value.trim().equals("all", true)
            if (wildcard && ((p["port"] as? Number)?.toLong() ?: 0) == 0L) fail("$path.ports", "Wildcard requires explicit dial port")
            var total = 0L
            if (!wildcard) value.split(',').forEach { part ->
                val pair = part.trim().split('-')
                if (pair.size !in 1..2) fail("$path.ports", "Invalid port range")
                val ns = pair.map { n -> n.trim().toLongOrNull() ?: fail("$path.ports", "Invalid port") }
                if (ns.any { it !in 1..65535 } || ns.first() > ns.last()) fail("$path.ports", "Invalid port range")
                total += ns.last() - ns.first() + 1
                if (total > 65535) fail("$path.ports", "Port expansion exceeds 65535 entries")
            }
        }
        if (!p.containsKey("port") && !p.containsKey("ports")) fail("$path.port", "port or concrete ports range required")
        p["obfs"]?.let { choice(it, "$path.obfs", "", "salamander") }
        if (p["obfs"] == "salamander") nonempty(p["obfs-password"], "$path.obfs-password")
        else if (p.containsKey("obfs-password")) fail("$path.obfs-password", "Password has no selected obfs consumer")
        for (key in listOf("up", "down")) p[key]?.let { v ->
            if (v is String) {
                if (!v.trim().matches(Regex("[0-9]+(?:\\s*[KMGTkmgt]?[Bb]ps)?"))) fail("$path.$key", "Invalid bandwidth")
            } else uint(v, "$path.$key")
        }
        p["hop-interval"]?.let { v ->
            if (v is String) {
                val ns = v.trim().split('-').map { it.trim().toLongOrNull() ?: fail("$path.hop-interval", "Invalid seconds range") }
                if (ns.size !in 1..2 || ns.any { it < 0 }) fail("$path.hop-interval", "Invalid seconds range")
                if (ns.size == 2 && ns[1] != 0L && ns[1] < (if (ns[0] == 0L) 30 else ns[0])) fail("$path.hop-interval", "Core would increase range end to start")
                if (ns.any { it in 1..4 }) fail("$path.hop-interval", "Core would clamp hop interval to 5 seconds")
            } else { uint(v, "$path.hop-interval"); if ((v as Number).toLong() in 1..4) fail("$path.hop-interval", "Core would clamp hop interval to 5 seconds") }
        }
        p["fingerprint"]?.let {
            val v = str(it, "$path.fingerprint").trim().substringAfterLast('=').filter { c -> !c.isWhitespace() && c != ':' }
            if (v.isNotEmpty() && !v.matches(Regex("[0-9a-fA-F]{64}"))) fail("$path.fingerprint", "Expected SHA256 fingerprint")
        }
        p["alpn"]?.let { v -> val a = if (v is String) listOf(v) else strings(v, "$path.alpn"); if (a.any { it != "h3" }) fail("$path.alpn", "Hysteria2 supports only h3 ALPN") }
    }

    private fun vless(p: MutableMap<String, Any?>, path: String, features: Set<String>) {
        alias(p, "server-name", "servername", path)
        alias(p, "splithttp-opts", "xhttp-opts", path)
        alias(p, "mux", "smux", path)
        schema(p, path, base + text("uuid", "network", "servername", "client-fingerprint", "encryption", "flow") + flags("tls", "skip-cert-verify") + mapOf("alpn" to Kind.TEXTS), setOf("ws-opts", "grpc-opts", "h2-opts", "http-upgrade-opts", "xhttp-opts", "reality-opts", "ech-opts", "smux"))
        if (str(p["server"], "$path.server").toByteArray(Charsets.UTF_8).size > 255) fail("$path.server", "Server exceeds 255 UTF8 bytes")
        val uuid = str(p["uuid"], "$path.uuid")
        if (!uuid.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) fail("$path.uuid", "Expected UUID")
        val network = p["network"] ?: "tcp"
        choice(network, "$path.network", "tcp", "ws", "grpc", "h2", "httpupgrade", "xhttp")
        val tls = p["tls"] == true
        for (key in listOf("skip-cert-verify", "alpn", "client-fingerprint", "reality-opts", "ech-opts")) if (p.containsKey(key) && !tls) fail("$path.$key", "TLS option would be ignored without tls: true")
        if (p.containsKey("servername") && !tls && network !in setOf("grpc", "xhttp")) fail("$path.servername", "No servername consumer for this transport without TLS")
        val encryption = p["encryption"] as? String ?: "none"
        if (encryption !in setOf("", "none")) { feature(features, "vless-encryption", "$path.encryption"); if (!encryption.startsWith("mlkem768x25519plus")) fail("$path.encryption", "Unsupported encryption") }
        val flow = p["flow"] ?: ""
        choice(flow, "$path.flow", "", "xtls-rprx-vision")
        if (flow == "xtls-rprx-vision") {
            feature(features, "vless-vision", "$path.flow")
            if (encryption in setOf("", "none") && (network != "tcp" || !tls)) fail("$path.flow", "Vision requires TCP + TLS")
            if (encryption !in setOf("", "none") && !tls && network !in setOf("grpc", "h2")) fail("$path.flow", "Encrypted Vision requires TLS transport")
        }
        p["reality-opts"]?.let { v ->
            val o = map(v, "$path.reality-opts")
            if (o.containsKey("short-id") && o["short-id"] == null) o["short-id"] = ""
            schema(o, "$path.reality-opts", text("public-key", "short-id") + flags("support-x25519mlkem768"))
            val key = nonempty(o["public-key"], "$path.reality-opts.public-key")
            val bytes = try { java.util.Base64.getUrlDecoder().decode(key) } catch (_: IllegalArgumentException) { fail("$path.reality-opts.public-key", "Expected base64url public key") }
            if (bytes.size != 32) fail("$path.reality-opts.public-key", "Expected 32-byte public key")
            o["short-id"]?.let { if (!str(it, "$path.reality-opts.short-id").matches(Regex("(?:[0-9a-fA-F]{2}){0,8}"))) fail("$path.reality-opts.short-id", "Expected up to 8 hex bytes; quote numeric IDs") }
            nonempty(p["client-fingerprint"], "$path.client-fingerprint")
        }
        p["ech-opts"]?.let { v ->
            val o = map(v, "$path.ech-opts"); schema(o, "$path.ech-opts", flags("enable") + text("config"))
            if (o["enable"] == true) {
                val config = nonempty(o["config"], "$path.ech-opts.config")
                try { if (java.util.Base64.getDecoder().decode(config).isEmpty()) fail("$path.ech-opts.config", "Empty ECH config") } catch (_: IllegalArgumentException) { fail("$path.ech-opts.config", "Invalid base64 ECH config") }
            } else if (o.containsKey("config")) fail("$path.ech-opts.config", "ECH config would be ignored without enable: true")
        }
        val blocks = mapOf("ws-opts" to "ws", "grpc-opts" to "grpc", "h2-opts" to "h2", "http-upgrade-opts" to "httpupgrade", "xhttp-opts" to "xhttp")
        for ((key, transport) in blocks) p[key]?.let { v ->
            if (network != transport) fail("$path.$key", "Options would be ignored for network $network")
            transport(map(v, "$path.$key"), "$path.$key", transport)
        }
        p["smux"]?.let { v ->
            val o = map(v, "$path.smux")
            schema(o, "$path.smux", text("protocol") + flags("enabled", "padding", "only-tcp") + numbers("max-connections", "min-streams", "max-streams"))
            if (o["enabled"] == true) {
                feature(features, "mux", "$path.smux")
                val protocol = o["protocol"] ?: "h2mux"
                choice(protocol, "$path.smux.protocol", "", "smux", "yamux", "h2mux", "muxcool")
                if (protocol == "muxcool" && o["padding"] == true) fail("$path.smux.padding", "MuxCool has no padding")
                if (flow == "xtls-rprx-vision" && protocol != "muxcool") fail("$path.smux.protocol", "Vision requires MuxCool")
            } else if (o.keys.any { it != "enabled" }) fail("$path.smux", "Options would be ignored without enabled: true")
        }
    }

    private fun transport(o: MutableMap<String, Any?>, path: String, type: String) {
        val fields = when (type) {
            "ws" -> text("path", "early-data-header-name") + numbers("max-early-data") + mapOf("headers" to Kind.HEADERS)
            "grpc" -> text("grpc-service-name")
            "h2" -> text("path") + mapOf("host" to Kind.TEXTS)
            "httpupgrade" -> text("path", "host") + mapOf("headers" to Kind.HEADERS)
            else -> text("path", "mode") + flags("no-grpc-header") + mapOf("headers" to Kind.HEADERS)
        }
        schema(o, path, fields, if (type == "xhttp") setOf("host", "x-padding-bytes") else emptySet())
        if (type == "ws") {
            o["max-early-data"]?.let { uint(it, "$path.max-early-data", 0, 2048) }
            o["headers"]?.let { v -> map(v, "$path.headers").keys.forEach { if (it != "Host") fail("$path.headers.$it", "WS consumes only Host") } }
        }
        if (type == "h2") o["host"]?.let { if (strings(it, "$path.host").isEmpty()) fail("$path.host", "Empty host list") }
        if (type == "xhttp") {
            o["host"]?.let { val hosts = if (it is String) listOf(it) else strings(it, "$path.host"); if (hosts.isEmpty()) fail("$path.host", "Empty host list") }
            o["mode"]?.let { if (!str(it, "$path.mode").equals("stream-one", true)) fail("$path.mode", "Only stream-one is implemented") }
            o["x-padding-bytes"]?.let { v ->
                val parts = if (v is String) v.split('-').map { it.trim().toLongOrNull() ?: fail("$path.x-padding-bytes", "Invalid range") } else (v as? List<*>)?.map { uint(it, "$path.x-padding-bytes") } ?: fail("$path.x-padding-bytes", "Expected range string or pair")
                if (parts.size != 2 || parts[0] > parts[1] || parts.any { it < 0 || it > 65536 }) fail("$path.x-padding-bytes", "Invalid padding range")
            }
        }
    }

    private fun schema(m: MutableMap<String, Any?>, path: String, fields: Map<String, Kind>, nested: Set<String> = emptySet()) {
        m.forEach { (key, value) ->
            val p = "$path.$key"
            if (value == null) fail(p, "Explicit null is not admitted")
            when (fields[key]) {
                Kind.TEXT -> str(value, p)
                Kind.BOOL -> if (value !is Boolean) fail(p, "Expected boolean")
                Kind.UINT -> uint(value, p)
                Kind.TEXTS -> strings(value, p)
                Kind.HEADERS -> { val headers = map(value, p); if (headers.size > 64) fail(p, "Maximum 64 headers"); headers.forEach { (k, v) -> str(v, "$p.$k") } }
                null -> if (key !in nested) fail(p, "No consumed field in fixed protocol factory; refusing semantic loss")
            }
        }
    }
    private fun alias(m: MutableMap<String, Any?>, old: String, key: String, path: String) {
        if (!m.containsKey(old)) return
        if (m.containsKey(key) && m[key] != m[old]) fail("$path.$old", "Conflicts with $key")
        m[key] = m.remove(old)
    }
    private fun feature(features: Set<String>, name: String, path: String) { if (name !in features) fail(path, "Native feature verification required: $name; implemented by core, not a core exception") }
    private fun str(value: Any?, path: String): String = value as? String ?: fail(path, "Expected string")
    private fun nonempty(value: Any?, path: String): String = str(value, path).also { if (it.isEmpty()) fail(path, "Expected nonempty string") }
    private fun uint(value: Any?, path: String, min: Long = 0, max: Long = Long.MAX_VALUE): Long {
        if (value !is Byte && value !is Short && value !is Int && value !is Long) fail(path, "Expected unsigned integer")
        return (value as Number).toLong().also { if (it !in min..max) fail(path, "Expected integer in $min..$max") }
    }
    private fun strings(value: Any?, path: String): List<String> = (value as? List<*> ?: fail(path, "Expected list")).mapIndexed { i, v -> str(v, "$path[$i]") }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?, path: String): MutableMap<String, Any?> = value as? MutableMap<String, Any?> ?: fail(path, "Expected mapping")
    private fun choice(value: Any?, path: String, vararg choices: String) { if (str(value, path) !in choices) fail(path, "Unsupported value: $value") }
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
}
