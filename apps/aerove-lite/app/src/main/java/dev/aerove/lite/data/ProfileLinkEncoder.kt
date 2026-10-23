package dev.aerove.lite.data

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Client counterpart of Standard core/share_link.go; unrepresentable nodes retain an empty slot. */
class ProfileLinkEncoder(private val limits: ProfileLinkLimits = ProfileLinkLimits()) {
    suspend fun encode(mappings: List<Map<String, Any?>>): List<String> = withContext(Dispatchers.Default) {
        require(mappings.size <= minOf(limits.maxLinks, limits.maxNodes)) { "Too many nodes" }
        var total = 0L
        mappings.mapIndexed { index, mapping ->
            ensureActive()
            var values = 0
            var bytes = 0L
            fun inspect(value: Any?, depth: Int) {
                ensureActive()
                require(depth <= 32 && ++values <= 10000) { "Node is too complex" }
                when (value) {
                    is Map<*, *> -> {
                        require(value.size <= limits.maxParameters) { "Too many node fields" }
                        value.forEach { (key, item) -> require(key is String); inspect(key, depth + 1); inspect(item, depth + 1) }
                    }
                    is List<*> -> { require(value.size <= limits.maxParameters); value.forEach { inspect(it, depth + 1) } }
                    null -> Unit
                    is String, is Number, is Boolean -> {
                        val text = value.toString()
                        checkedLinkText(text, limits.maxParameterBytes, "$.proxies[$index]")
                        bytes += text.toByteArray(Charsets.UTF_8).size
                    }
                    else -> throw IllegalArgumentException("Unsupported node value")
                }
                require(bytes <= limits.maxInputBytes) { "Node exceeds the byte limit" }
            }
            inspect(mapping, 0)
            total += bytes
            require(total <= limits.maxTotalBytes) { "Batch exceeds the byte limit" }
            val link = encodeOne(Fields(mapping))
            checkedLinkText(link, limits.maxLinkBytes, "$.proxies[$index]")
            total += link.toByteArray(Charsets.UTF_8).size
            require(total <= limits.maxTotalBytes) { "Batch exceeds the byte limit" }
            link
        }
    }

    private class Fields(val values: Map<*, *> = emptyMap<Any, Any?>()) {
        fun str(key: String): String = values[key]?.toString().orEmpty()
        fun flag(key: String): Boolean = values[key] == true || values[key] == "true"
        fun sub(key: String) = Fields(values[key] as? Map<*, *> ?: emptyMap<Any, Any?>())
        fun list(key: String): List<String> = when (val value = values[key]) {
            is List<*> -> value.map { it?.toString() ?: "<nil>" }
            is String -> if (value.isEmpty()) emptyList() else listOf(value)
            else -> emptyList()
        }
        fun first(key: String) = list(key).firstOrNull().orEmpty()
    }

    private fun encodeOne(f: Fields): String {
        if (f.str("server").isEmpty()) return ""
        val q = sortedMapOf<String, String>()
        fun put(key: String, field: String = key) { q.setIf(key, f.str(field)) }
        fun flag(field: String, key: String) { if (f.flag(field)) q[key] = "1" }
        fun alpn() { q.setIf("alpn", f.list("alpn").joinToString(",")) }
        fun url(scheme: String, user: String? = null, host: String = hostPort(f)) =
            shareUrl(scheme, user, host, q, f.str("name"))
        return when (f.str("type")) {
            "ss" -> {
                flag("udp-over-tcp", "uot")
                val opts = f.sub("plugin-opts")
                when (f.str("plugin")) {
                    "" -> Unit
                    "obfs" -> q["plugin"] = "obfs-local;obfs=${opts.str("mode")};obfs-host=${opts.str("host")}"
                    "v2ray-plugin" -> q["plugin"] = "v2ray-plugin;mode=${opts.str("mode")};host=${opts.str("host")};path=${opts.str("path")}" + if (opts.flag("tls")) ";tls" else ""
                    else -> return ""
                }
                url("ss", userPassword(f.str("cipher"), f.str("password")))
            }
            "ssr" -> {
                if (':' in f.str("server")) return ""
                val body = listOf("server", "port", "protocol", "cipher", "obfs").joinToString(":") { f.str(it) } +
                    ":${base64(f.str("password"), true)}/?obfsparam=${base64(f.str("obfs-param"), true)}" +
                    "&protoparam=${base64(f.str("protocol-param"), true)}&remarks=${base64(f.str("name"), true)}"
                "ssr://${base64(body, true)}"
            }
            "vmess" -> if (f.str("alterId") !in listOf("", "0")) legacyVmess(f) else {
                q.putAll(vShareQuery(f))
                if (f.str("cipher") != "auto") q.setIf("encryption", f.str("cipher"))
                url("vmess", escape(f.str("uuid"), USER))
            }
            "vless" -> {
                q.putAll(vShareQuery(f)); put("flow"); put("encryption")
                url("vless", escape(f.str("uuid"), USER))
            }
            "trojan" -> {
                flag("skip-cert-verify", "allowInsecure"); put("sni"); alpn()
                when (f.str("network")) {
                    "ws" -> {
                        val opts = f.sub("ws-opts")
                        if (opts.sub("headers").str("Host").isNotEmpty()) return ""
                        q["type"] = "ws"; q.setIf("path", opts.str("path"))
                    }
                    "grpc" -> { q["type"] = "grpc"; q.setIf("serviceName", f.sub("grpc-opts").str("grpc-service-name")) }
                }
                put("fp", "client-fingerprint"); put("pcs", "fingerprint")
                url("trojan", escape(f.str("password"), USER))
            }
            "hysteria" -> {
                put("peer", "sni"); put("obfs"); alpn()
                q.setIf("auth", f.str("auth-str").ifEmpty { f.str("auth_str") })
                put("protocol"); put("up"); put("down"); flag("skip-cert-verify", "insecure")
                url("hysteria")
            }
            "hysteria2" -> {
                if (f.sub("realm-opts").flag("enable")) return ""
                put("sni"); put("obfs"); put("obfs-password"); flag("skip-cert-verify", "insecure"); alpn()
                put("pinSHA256", "fingerprint"); put("up"); put("down")
                val host = if (f.str("ports").isNotEmpty() && ':' !in f.str("server")) "${f.str("server")}:${f.str("ports")}" else hostPort(f)
                url("hysteria2", f.str("password").takeIf { it.isNotEmpty() }?.let { escape(it, USER) }, host)
            }
            "tuic" -> {
                put("congestion_control", "congestion-controller"); alpn(); put("sni")
                flag("disable-sni", "disable_sni"); put("udp_relay_mode", "udp-relay-mode")
                url("tuic", if (f.str("uuid").isEmpty()) escape(f.str("token"), USER) else userPassword(f.str("uuid"), f.str("password")))
            }
            "http", "socks5" -> {
                val user = if (f.str("username").isNotEmpty() || f.str("password").isNotEmpty()) userPassword(f.str("username"), f.str("password")) else null
                url(if (f.str("type") == "http" && f.flag("tls")) "https" else f.str("type"), user)
            }
            "anytls" -> {
                put("sni"); flag("skip-cert-verify", "insecure"); put("hpkp", "fingerprint")
                url("anytls", escape(f.str("password"), USER))
            }
            "mieru" -> {
                q["port"] = f.str("port-range").ifEmpty { f.str("port") }
                q["protocol"] = f.str("transport").ifEmpty { "TCP" }
                put("multiplexing"); put("handshake-mode"); put("traffic-pattern")
                val host = f.str("server").let { if (':' in it) "[$it]" else it }
                url("mierus", userPassword(f.str("username"), f.str("password")), host)
            }
            else -> ""
        }
    }

    private fun vShareQuery(f: Fields): MutableMap<String, String> {
        val q = sortedMapOf<String, String>()
        if (f.flag("tls")) {
            val reality = f.sub("reality-opts")
            if (reality.str("public-key").isNotEmpty()) {
                q["security"] = "reality"; q["pbk"] = reality.str("public-key")
                q.setIf("sid", reality.str("short-id")); q.setIf("support-x25519mlkem768", reality.str("support-x25519mlkem768"))
            } else q["security"] = "tls"
            q.setIf("fp", f.str("client-fingerprint")); q.setIf("alpn", f.list("alpn").joinToString(",")); q.setIf("pcs", f.str("fingerprint"))
        }
        q.setIf("sni", f.str("servername"))
        if (f.flag("packet-addr")) q["packetEncoding"] = "packet" else if (!f.flag("xudp")) q["packetEncoding"] = "none"
        when (val network = f.str("network")) {
            "", "tcp" -> Unit
            "http" -> {
                val opts = f.sub("http-opts")
                q["type"] = "tcp"; q["headerType"] = "http"; q.setIf("path", opts.first("path"))
                q.setIf("host", opts.sub("headers").first("Host")); q.setIf("method", opts.str("method"))
            }
            "h2" -> {
                val opts = f.sub("h2-opts")
                q["type"] = "http"; q.setIf("path", opts.str("path")); q.setIf("host", opts.first("host"))
            }
            "ws", "httpupgrade" -> {
                val opts = f.sub("ws-opts")
                q["type"] = network; q.setIf("path", opts.str("path")); q.setIf("host", opts.sub("headers").str("Host"))
                if (network == "ws") {
                    if (opts.str("max-early-data") !in listOf("", "0")) q["ed"] = opts.str("max-early-data")
                    if (opts.str("early-data-header-name") != "Sec-WebSocket-Protocol") q.setIf("eh", opts.str("early-data-header-name"))
                } else if (opts.flag("v2ray-http-upgrade-fast-open")) q["ed"] = "0"
            }
            "grpc" -> { q["type"] = "grpc"; q.setIf("serviceName", f.sub("grpc-opts").str("grpc-service-name")) }
            "xhttp" -> {
                val opts = f.sub("xhttp-opts")
                q["type"] = "xhttp"; listOf("path", "host", "mode").forEach { q.setIf(it, opts.str(it)) }
            }
            else -> q["type"] = network
        }
        return q
    }

    private fun legacyVmess(f: Fields): String {
        val values = sortedMapOf("v" to "2", "ps" to f.str("name"), "add" to f.str("server"), "port" to f.str("port"),
            "id" to f.str("uuid"), "aid" to f.str("alterId"), "scy" to f.str("cipher"), "net" to "tcp", "type" to "none", "host" to "", "path" to "", "tls" to "")
        when (val network = f.str("network")) {
            "ws", "httpupgrade" -> {
                val opts = f.sub("ws-opts")
                values["net"] = network; values["host"] = opts.sub("headers").str("Host")
                values["path"] = if (network == "ws") earlyDataPath(opts) else opts.str("path")
            }
            "http" -> { val opts = f.sub("http-opts"); values["type"] = "http"; values["host"] = opts.sub("headers").first("Host"); values["path"] = opts.first("path") }
            "h2" -> { val opts = f.sub("h2-opts"); values["net"] = "h2"; values["host"] = opts.first("host"); values["path"] = opts.str("path") }
            "grpc" -> { values["net"] = "grpc"; values["path"] = f.sub("grpc-opts").str("grpc-service-name") }
        }
        if (f.flag("tls")) {
            values["tls"] = "tls"; values["sni"] = f.str("servername"); values["alpn"] = f.list("alpn").joinToString(","); values["fp"] = f.str("client-fingerprint")
        }
        return "vmess://" + base64(values.entries.joinToString(",", "{", "}") { "${json(it.key)}:${json(it.value)}" })
    }

    private fun earlyDataPath(opts: Fields): String {
        val path = opts.str("path")
        if (opts.str("max-early-data") in listOf("", "0")) return path
        return try {
            require(path.none { it.isISOControl() })
            val beforeFragment = path.substringBefore('#')
            val rawBase = beforeFragment.substringBefore('?')
            val rawFragment = path.substringAfter('#', "")
            val authorityEnd = AUTHORITY.find(rawBase)?.range?.last?.plus(1) ?: 0
            val authority = escapePreservingPercent(rawBase.substring(0, authorityEnd), HOST + "/@")
            val rawPath = rawBase.substring(authorityEnd)
            // Go retains valid RawPath brackets, while java.net.URI requires them escaped in a path.
            URI(authority + escapePreservingPercent(rawPath, PATH))
            val base = authority + escapePreservingPercent(rawPath, PATH + "[]")
            val query = sortedMapOf<String, MutableList<String>>()
            beforeFragment.substringAfter('?', "").split('&').forEach { part ->
                if (part.isNotEmpty() && ';' !in part) runCatching {
                    val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                    val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                    query.getOrPut(key) { mutableListOf() }.add(value)
                }
            }
            query["ed"] = mutableListOf(opts.str("max-early-data"))
            val encoded = query.entries.flatMap { (key, items) -> items.map { "${escape(key, QUERY)}=${escape(it, QUERY)}" } }.joinToString("&")
            base + "?" + encoded + (if (rawFragment.isEmpty()) "" else "#" + escapePreservingPercent(rawFragment, FRAGMENT))
        } catch (_: Exception) { path }
    }

    private fun escapePreservingPercent(text: String, allowed: String): String = buildString {
        var at = 0
        while (at < text.length) {
            val next = text.indexOf('%', at).let { if (it < 0) text.length else it }
            append(escape(text.substring(at, next), allowed))
            if (next < text.length) {
                require(next + 2 < text.length && text[next + 1].isDigitOrHex() && text[next + 2].isDigitOrHex())
                append(text.substring(next, next + 3))
            }
            at = next + 3
        }
    }

    private fun Char.isDigitOrHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun hostPort(f: Fields) = (if (':' in f.str("server")) "[${f.str("server")}]" else f.str("server")) + ":" + f.str("port")
    private fun userPassword(user: String, password: String) = escape(user, USER) + ":" + escape(password, USER)
    private fun shareUrl(scheme: String, user: String?, host: String, q: Map<String, String>, name: String): String =
        "$scheme://" + (user?.let { "$it@" } ?: "") + escape(host, HOST) +
            (if (q.isEmpty()) "" else "?" + q.toSortedMap().entries.joinToString("&") { "${escape(it.key, QUERY)}=${escape(it.value, QUERY)}" }) +
            (if (name.isEmpty()) "" else "#" + escape(name, FRAGMENT))
    private fun MutableMap<String, String>.setIf(key: String, value: String) { if (value.isNotEmpty()) this[key] = value }
    private fun base64(text: String, rawUrl: Boolean = false): String =
        (if (rawUrl) Base64.getUrlEncoder().withoutPadding() else Base64.getEncoder()).encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun json(text: String): String = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b"); '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ' || c in "<>&\u2028\u2029") append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        } }
        append('"')
    }

    private fun escape(text: String, allowed: String): String = buildString {
        text.toByteArray(Charsets.UTF_8).forEach { byte ->
            val n = byte.toInt() and 255
            val c = n.toChar()
            when {
                n < 128 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~" || c in allowed) -> append(c)
                c == ' ' && allowed == QUERY -> append('+')
                else -> { append('%'); append(HEX[n shr 4]); append(HEX[n and 15]) }
            }
        }
    }

    private companion object {
        const val HEX = "0123456789ABCDEF"
        const val QUERY = ""
        const val USER = "$&+,;="
        const val HOST = "!$&'()*+,;=:[]<>\""
        const val FRAGMENT = "$&+,/:;=?@!()*"
        const val PATH = "$&+,/:;=@"
        val AUTHORITY = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*:)?//[^/]*")
    }
}
