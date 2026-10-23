package dev.aerove.lite.data

/** Closed option admission mirrors fixed native consumers; actual core conversion remains mandatory. */
internal object MeowSsPluginOptions {
    private val allowed = mapOf(
        "obfs" to setOf("mode","host"),
        "simple-obfs" to setOf("mode","host"),
        "v2ray-plugin" to setOf("mode","tls","host","path","mux","skip-cert-verify","header"),
        "gost-plugin" to setOf("mode","tls","host","path","mux","skip-cert-verify","name-cert-verify","fingerprint","certificate","private-key","ech-opts.enable","ech-opts.config","header"),
        "shadow-tls" to setOf("host","password","version","alpn","skip-cert-verify","name-cert-verify","fingerprint","strict-mode","certificate","private-key"),
        "restls" to setOf("host","password","version-hint","restls-script","skip-cert-verify","force-tls12","name-cert-verify","fingerprint"),
        "jls" to setOf("host","username","password","alpn","skip-cert-verify"),
        "kcptun" to setOf("key","crypt","mode","conn","autoexpire","scavengettl","mtu","ratelimit","sndwnd","rcvwnd","datashard","parityshard","dscp","nocomp","acknodelay","nodelay","interval","resend","nc","sockbuf","smuxver","smuxbuf","framesize","streambuf","keepalive"),
        "ech-tls-tunnel" to setOf("mode","sni","path","ech-config","client-fingerprint","fast-open")
    )
    fun normalize(plugin: String, options: Any?, path: String): String {
        val fields = allowed[plugin] ?: fail(path, "External SIP003 process policy requires host verification")
        val tokens = mutableListOf<Pair<String, String>>()
        when (options) {
            is String -> options.split(';').map(String::trim).filter(String::isNotEmpty).forEach { token ->
                val parts = token.split('=', limit = 2)
                tokens += parts[0].trim().lowercase() to if (parts.size == 2) parts[1].trim() else "true"
            }
            is Map<*, *> -> options.forEach { (rawKey, value) ->
                val key = (rawKey as? String ?: fail(path, "Expected string option names")).lowercase()
                if (key.any { it == ';' || it == '=' }) fail("$path.$key", "Invalid option separator")
                when {
                    key == "headers" -> (value as? Map<*, *> ?: fail("$path.headers", "Expected mapping")).forEach { (name, v) ->
                        if (name !is String || name.isEmpty() || name.any { it in ";=:" }) fail("$path.headers", "Invalid header name")
                        tokens += "header" to "$name:${scalar(v, "$path.headers.$name")}"
                    }
                    key == "ech-opts" -> (value as? Map<*, *> ?: fail("$path.ech-opts", "Expected mapping")).forEach { (name, v) ->
                        if (name !is String) fail("$path.ech-opts", "Expected string keys")
                        tokens += "ech-opts.$name" to scalar(v, "$path.ech-opts.$name")
                    }
                    value is List<*> -> {
                        if (key != "alpn") fail("$path.$key", "No list consumer")
                        tokens += key to value.mapIndexed { i, v ->
                            val s = v as? String ?: fail("$path.$key[$i]", "Expected string")
                            if (s.any { it in ",;" }) fail("$path.$key[$i]", "List separator changes semantics")
                            s
                        }.joinToString(",")
                    }
                    else -> tokens += key to scalar(value, "$path.$key")
                }
            }
            else -> fail(path, "Expected SIP003 string or mapping")
        }
        val accepted = sortedMapOf<String, Pair<String, String>>()
        tokens.forEach { (originalKey, originalValue) ->
            val key = when {
                plugin in setOf("obfs", "simple-obfs") && originalKey == "obfs" -> "mode"
                plugin in setOf("obfs", "simple-obfs") && originalKey == "obfs-host" -> "host"
                plugin == "gost-plugin" && originalKey == "ech-enable" -> "ech-opts.enable"
                plugin == "gost-plugin" && originalKey == "ech-config" -> "ech-opts.config"
                plugin == "jls" && originalKey == "skip_cert_verify" -> "skip-cert-verify"
                plugin == "ech-tls-tunnel" && originalKey == "ech_config" -> "ech-config"
                plugin == "ech-tls-tunnel" && originalKey in setOf("fingerprint", "client_fingerprint") -> "client-fingerprint"
                plugin == "ech-tls-tunnel" && originalKey == "fast_open" -> "fast-open"
                else -> originalKey
            }
            if (key !in fields) fail("$path.$key", "No consumed option for $plugin")
            var value = originalValue
            if (';' in value) fail("$path.$key", "Option separator changes semantics")
            if (key in setOf("tls", "mux", "skip-cert-verify", "strict-mode", "force-tls12", "ech-opts.enable", "fast-open", "nocomp", "acknodelay")) value = when (value.lowercase()) {
                "1", "true", "yes", "on" -> "true"
                "", "0", "false", "no", "off" -> "false"
                else -> fail("$path.$key", "Unrecognized boolean would be coerced")
            }
            if (key in setOf("mode", "crypt", "version-hint")) value = value.lowercase()
            val identity = if (key == "header") {
                if (':' !in value || value.substringBefore(':').isBlank()) fail("$path.header", "Expected name:value")
                "header:${value.substringBefore(':').trim().lowercase()}"
            } else key
            accepted[identity]?.let {
                val same = if (key == "header") it.second.substringAfter(':').trim() == value.substringAfter(':').trim() else it.second == value
                if (!same) fail("$path.$key", "Conflicting aliases or duplicate options")
            }
            accepted[identity] = key to value
        }
        fun get(key: String) = accepted[key]?.second
        if (plugin in setOf("obfs", "simple-obfs") && get("mode")?.let { it !in setOf("http", "tls") } == true) fail("$path.mode", "Only http/tls obfs supported")
        if (plugin in setOf("v2ray-plugin", "gost-plugin")) {
            if (get("mode")?.let { it !in setOf("ws", "websocket") } == true) fail("$path.mode", "Only websocket supported")
            if (get("tls") != "true") for (key in listOf("skip-cert-verify", "name-cert-verify", "fingerprint", "certificate", "private-key", "ech-opts.enable", "ech-opts.config")) if (get(key) != null) fail("$path.$key", "TLS policy would be ignored without tls true")
        }
        if (plugin == "restls" && get("version-hint")?.let { it !in setOf("tls12", "tls13") } == true) fail("$path.version-hint", "Only tls12/tls13 supported")
        if (plugin == "shadow-tls" && get("version")?.let { it !in setOf("1", "2", "3") } == true) fail("$path.version", "Only v1/v2/v3 supported")
        if (plugin == "kcptun" && get("smuxver")?.let { it !in setOf("0", "1") } == true) fail("$path.smuxver", "Only smux v1 supported")
        if (plugin == "restls" && get("force-tls12") == "true" && get("version-hint") == "tls13") fail("$path.force-tls12", "Overrides declared TLS version")
        if (plugin == "kcptun" && get("crypt") == "sm4") fail("$path.crypt", "Core explicitly does not implement SM4")
        if (plugin == "ech-tls-tunnel" && get("mode")?.let { it != "client" } == true) fail("$path.mode", "Only client mode supported")
        if (plugin == "kcptun") {
            if (get("mode")?.let { it !in setOf("normal", "fast", "fast2", "fast3", "manual") } == true) fail("$path.mode", "Unknown mode would change defaults")
            if (get("crypt")?.let { it !in setOf("aes", "aes-256", "aes-128", "aes-192", "aes-128-gcm", "salsa20", "none", "null", "xor", "tea", "xtea", "blowfish", "twofish", "cast5", "3des", "sm4") } == true) fail("$path.crypt", "Unknown cipher would default")
            if (get("mode") != "manual") for (key in listOf("nodelay", "interval", "resend", "nc")) if (get(key) != null) fail("$path.$key", "Preset would overwrite option")
            fun count(key: String) = get(key)?.toLongOrNull()?.takeIf { it >= 0 } ?: if (get(key) == null) 0L else fail("$path.$key", "Expected unsigned integer")
            val data = count("datashard").let { if (it == 0L) 10L else it }
            val parity = count("parityshard").let { if (it == 0L) 3L else it }
            if (get("mode") == "manual" && get("interval") != null && count("interval") != 0L && count("interval") !in 10L..5000L) fail("$path.interval", "Core would clamp manual interval")
            if (data > 256 - parity) fail("$path.parityshard", "FEC limit would disable parity")
        }
        return accepted.values.joinToString(";") { "${it.first}=${it.second}" }
    }
    fun isBuiltin(plugin: String) = plugin in allowed
    private fun scalar(value: Any?, path: String): String = when (value) {
        is String, is Number, is Boolean -> value.toString().also { if (';' in it) fail(path, "Separator would drop or inject option") }
        else -> fail(path, "Nonscalar option would be dropped")
    }
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
}
