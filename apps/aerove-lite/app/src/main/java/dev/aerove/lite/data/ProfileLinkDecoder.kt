package dev.aerove.lite.data

import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProfileLinkDecoder(private val limits: ProfileLinkLimits = ProfileLinkLimits()) {
    suspend fun decode(text: String, existingNames: Set<String> = emptySet()): ProfileLinkBatch =
        decode(listOf(text), existingNames)

    suspend fun decode(inputs: List<String>, existingNames: Set<String> = emptySet()): ProfileLinkBatch = withContext(Dispatchers.Default) {
        val errors = mutableListOf<ProfileLinkError>()
        val pending = mutableListOf<Pair<Int, ProfileLinkEntry>>()
        val shares = mutableListOf<Pair<ProfileLinkSource, MutableMap<String, Any?>>>()
        val shareSources = mutableListOf<ProfileLinkSource>()
        var shareFailed = false
        var firstShare = -1
        var order = 0
        var totalBytes = 0L
        var nodes = 0
        val names = existingNames.toMutableSet()
        fun record(source: ProfileLinkSource, problem: ProfileLinkProblem) {
            errors += ProfileLinkError(source, problem.code, problem.field, problem.message)
        }
        if (inputs.size > limits.maxInputs || existingNames.size > 10000) {
            return@withContext ProfileLinkBatch(emptyList(), listOf(ProfileLinkError(ProfileLinkSource(0),
                ProfileLinkErrorCode.LIMIT_EXCEEDED, "$", "Too many inputs or existing names")), emptyList())
        }
        inputs.forEachIndexed { inputIndex, input ->
            ensureActive()
            val initial = ProfileLinkSource(inputIndex)
            try {
                if (totalBytes >= limits.maxTotalBytes) linkProblem("$", "Batch exceeds the total byte limit", ProfileLinkErrorCode.LIMIT_EXCEEDED)
                checkedLinkText(input, limits.maxInputBytes, "$")
                totalBytes += input.toByteArray(Charsets.UTF_8).size
                if (totalBytes > limits.maxTotalBytes) linkProblem("$", "Batch exceeds the total byte limit", ProfileLinkErrorCode.LIMIT_EXCEEDED)
                var text = input.trim().removePrefix("\uFEFF")
                if (text.isEmpty()) linkProblem("$", "Input is empty")
                if (!LINK_START.containsMatchIn(text) && text.matches(BASE64_TEXT)) {
                    val decoded = base64LinkText(text.replace("\r", "").replace("\n", ""))
                    if (!LINK_START.containsMatchIn(decoded.trim())) linkProblem("$.base64", "Expected encoded share links")
                    text = decoded.trim()
                }
                if (!LINK_START.containsMatchIn(text)) {
                    val config = ConfigYaml.parse(input, limits.maxInputBytes)
                    checkKnownCoreLimits(config)
                    val count = configurationNodes(config)
                    if (nodes + shares.size + count > limits.maxNodes) linkProblem("$.proxies", "Too many nodes", ProfileLinkErrorCode.LIMIT_EXCEEDED)
                    nodes += count
                    pending += order++ to ProfileLinkEntry.Inline(freeName("Imported profile", names), input,
                        ProfileLinkEntry.Inline.Kind.CONFIGURATION, count, listOf(initial))
                } else {
                    for ((lineIndex, raw) in text.lineSequence().withIndex()) {
                        ensureActive()
                        val line = raw.trim()
                        if (line.isNotEmpty()) {
                            val source = ProfileLinkSource(inputIndex, lineIndex + 1)
                            if (order >= limits.maxLinks) {
                                record(source, ProfileLinkProblem(ProfileLinkErrorCode.LIMIT_EXCEEDED, "$", "Too many links"))
                                shareFailed = true
                                break
                            }
                            val at = order++
                            val scheme = line.substringBefore("://", "").lowercase(Locale.ROOT)
                            val share = scheme in SHARE_SCHEMES
                            if (share) {
                                if (firstShare < 0) firstShare = at
                                shareSources += source
                            }
                            try {
                                checkedLinkText(line, limits.maxLinkBytes, "$")
                                if (line.any { it.isISOControl() }) linkProblem("$.url", "Control characters are not allowed", ProfileLinkErrorCode.INVALID_URL)
                                when {
                                    share -> {
                                        if (nodes + shares.size >= limits.maxNodes) linkProblem("$.proxies", "Too many nodes", ProfileLinkErrorCode.LIMIT_EXCEEDED)
                                        val proxy = decodeShare(line, scheme)
                                        val name = proxy["name"] as? String ?: ""
                                        if (name.any { it.isISOControl() }) linkProblem("$.name", "Control characters are not allowed in names")
                                        shares += source to proxy
                                    }
                                    scheme == "http" || scheme == "https" -> {
                                        val url = subscription(line)
                                        pending += at to ProfileLinkEntry.Subscription(freeName(linkUri(url).host ?: "Subscription", names), url, listOf(source))
                                    }
                                    scheme in INSTALL_SCHEMES -> {
                                        val uri = linkUri(line)
                                        if (uri.rawAuthority != "install-config" || uri.rawPath !in listOf("", "/") || uri.rawFragment != null) {
                                            linkProblem("$.url", "Expected an install-config URL", ProfileLinkErrorCode.INVALID_URL)
                                        }
                                        val query = ProfileLinkQuery(uri.rawQuery, limits)
                                        val target = query.take("url") ?: linkProblem("$.query.url", "Missing subscription URL", ProfileLinkErrorCode.INVALID_URL)
                                        val name = query.take("name")?.takeIf { it.isNotBlank() }
                                        query.finish()
                                        val url = subscription(target.trim())
                                        pending += at to ProfileLinkEntry.Subscription(freeName(name ?: linkUri(url).host ?: "Subscription", names), url, listOf(source))
                                    }
                                    else -> linkProblem("$.scheme", "Unknown link scheme", ProfileLinkErrorCode.UNSUPPORTED_SCHEME)
                                }
                            } catch (problem: ProfileLinkProblem) {
                                if (share) shareFailed = true
                                record(source, problem)
                            }
                        }
                    }
                }
            } catch (problem: ProfileLinkProblem) {
                val scheme = input.trimStart().substringBefore("://", "").lowercase(Locale.ROOT)
                if (scheme in SHARE_SCHEMES && initial !in shareSources) {
                    if (firstShare < 0) firstShare = order++
                    shareSources += initial
                    shareFailed = true
                }
                record(initial, problem)
            } catch (problem: ConfigValidationException) {
                problem.issues.forEach { errors += ProfileLinkError(initial, ProfileLinkErrorCode.INVALID_CONFIGURATION, it.path, it.reason) }
            }
        }
        if (shares.isNotEmpty() && !shareFailed) {
            val taken = RESERVED_NAMES.toMutableSet()
            shares.forEach { (_, proxy) ->
                val raw = (proxy["name"] as? String).orEmpty().trim()
                val base = if (raw.matches(Regex("^(-\\d+)?$"))) proxy["server"].toString() else raw
                proxy["name"] = freeName(base, taken)
            }
            val first = shares.first().second["name"].toString()
            val label = if (shares.size == 1) first else "$first (+${shares.size - 1})"
            val config = ConfigYaml.dump(mapOf("proxies" to shares.map { it.second }))
            if (config.toByteArray(Charsets.UTF_8).size > limits.maxInputBytes) {
                shareFailed = true
                record(shareSources.first(), ProfileLinkProblem(ProfileLinkErrorCode.LIMIT_EXCEEDED, "$.proxies", "Decoded configuration is too large"))
            } else {
                pending += firstShare to ProfileLinkEntry.Inline(freeName(label, names), config,
                    ProfileLinkEntry.Inline.Kind.SHARE_LINKS, shares.size, shareSources.toList(), first)
            }
        }
        ProfileLinkBatch(pending.sortedBy { it.first }.map { it.second }, errors.toList(),
            if (shareFailed) shareSources.toList() else emptyList())
    }

    private fun subscription(value: String): String {
        if (value.any { it.isWhitespace() || it.isISOControl() }) linkProblem("$.url", "Whitespace is not allowed in a subscription URL", ProfileLinkErrorCode.INVALID_URL)
        percentLinkText(value)
        val url = try { SubscriptionDownloader.parseUrl(value) } catch (_: IllegalArgumentException) {
            linkProblem("$.url", "Expected HTTP(S) without credentials or a fragment", ProfileLinkErrorCode.INVALID_URL)
        }
        url.encodedQuery?.takeIf { it.isNotEmpty() }?.let { raw ->
            val parts = raw.split('&')
            if (parts.size > limits.maxParameters) linkProblem("$.url.query", "Too many URL parameters", ProfileLinkErrorCode.LIMIT_EXCEEDED)
            val keys = mutableSetOf<String>()
            parts.forEach { part ->
                val key = percentLinkText(part.substringBefore('='), true)
                val param = percentLinkText(part.substringAfter('=', ""), true)
                checkedLinkText(key, limits.maxParameterBytes, "$.url.query")
                checkedLinkText(param, limits.maxParameterBytes, "$.url.query")
                if (!keys.add(key)) linkProblem("$.url.query", "Duplicate URL parameter", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
            }
        }
        return url.toString()
    }

    private fun configurationNodes(config: Map<String, Any?>): Int {
        fun listSize(value: Any?, path: String): Int = value?.let {
            (it as? List<*>)?.size ?: linkProblem(path, "Expected a list", ProfileLinkErrorCode.INVALID_CONFIGURATION)
        } ?: 0
        var count = listSize(config["proxies"], "$.proxies")
        val groups = listSize(config["proxy-groups"], "$.proxy-groups")
        if (groups > limits.maxNodes) linkProblem("$.proxy-groups", "Too many proxy groups", ProfileLinkErrorCode.LIMIT_EXCEEDED)
        (config["proxy-providers"] as? Map<*, *>)?.forEach { (key, provider) ->
            if (provider is Map<*, *> && provider["payload"] is List<*>) {
                count += listSize(provider["payload"], "$.proxy-providers.$key.payload")
            }
        }
        return count
    }

    private fun checkKnownCoreLimits(config: Map<String, Any?>) {
        fun inspect(value: Any?, path: String) {
            val proxy = value as? Map<*, *> ?: return
            val type = proxy["type"] as? String ?: return
            if (type !in CORE_PROTOCOLS) unsupported("$path.type", "No $type variant in config/internal/proxy.rs::OutboundProxyProtocol")
            val ignored = when (type) {
                "anytls" -> setOf("fingerprint", "client-fingerprint", "idle-session-check-interval", "idle-session-timeout", "min-idle-session")
                "hysteria2" -> setOf("up", "down")
                "vless", "vmess" -> setOf("client-fingerprint", "fingerprint", "alpn", "packet-addr", "xudp")
                "trojan" -> setOf("client-fingerprint", "fingerprint")
                else -> emptySet()
            }
            ignored.firstOrNull { proxy[it] != null }?.let {
                unsupported("$path.$it", "The fixed $type converter/runtime does not apply this option")
            }
            if (type == "hysteria2" && proxy["realm-opts"] != null) unsupported("$path.realm-opts", "OutboundHysteria2 has no realm rendezvous")
        }
        (config["proxies"] as? List<*>)?.forEachIndexed { index, proxy -> inspect(proxy, "$.proxies[$index]") }
        (config["proxy-providers"] as? Map<*, *>)?.forEach { (key, provider) ->
            ((provider as? Map<*, *>)?.get("payload") as? List<*>)?.forEachIndexed { index, proxy ->
                inspect(proxy, "$.proxy-providers.$key.payload[$index]")
            }
        }
    }

    private data class Address(
        val uri: URI, val query: ProfileLinkQuery, val host: String, val port: Int,
        val user: String?, val name: String, val ports: String?,
    ) {
        fun proxy(type: String): MutableMap<String, Any?> = linkedMapOf("name" to name, "type" to type, "server" to host, "port" to port)
    }

    private fun address(value: String, scheme: String): Address {
        val uri = linkUri(value)
        if (uri.rawPath !in listOf("", "/")) linkProblem("$.path", "Unexpected share-link path")
        val authority = uri.rawAuthority ?: linkProblem("$.server", "Missing server", ProfileLinkErrorCode.INVALID_URL)
        if (authority.count { it == '@' } > 1) linkProblem("$.userinfo", "Escape @ inside credentials", ProfileLinkErrorCode.INVALID_URL)
        val user = if ('@' in authority) authority.substringBeforeLast('@') else null
        var endpoint = authority.substringAfterLast('@')
        if (scheme == "vless" && ':' !in endpoint) endpoint = base64LinkText(endpoint)
        val host: String
        val portText: String
        if (endpoint.startsWith('[')) {
            val close = endpoint.indexOf(']')
            if (close < 0) linkProblem("$.server", "Invalid IPv6 server", ProfileLinkErrorCode.INVALID_URL)
            host = endpoint.substring(0, close + 1)
            val tail = endpoint.substring(close + 1)
            if (tail.isNotEmpty() && !tail.startsWith(':')) linkProblem("$.port", "Invalid server port")
            portText = tail.removePrefix(":")
        } else {
            if (endpoint.count { it == ':' } > 1) linkProblem("$.server", "IPv6 servers require brackets", ProfileLinkErrorCode.INVALID_URL)
            host = endpoint.substringBefore(':')
            portText = endpoint.substringAfter(':', "")
        }
        if (host.isBlank() || '%' in host || host.any { it.isWhitespace() }) linkProblem("$.server", "Invalid server", ProfileLinkErrorCode.INVALID_URL)
        val hopping = if (scheme in HYSTERIA2 && (',' in portText || '-' in portText)) portText else null
        if (hopping != null) {
            if (hopping.split(',').size > 64) linkProblem("$.ports", "Too many port ranges", ProfileLinkErrorCode.LIMIT_EXCEEDED)
            hopping.split(',').forEach { part ->
                val range = part.split('-')
                if (range.size !in 1..2) linkProblem("$.ports", "Invalid port range")
                val start = integer(range[0], "$.ports", 1, 65535)
                if (range.size == 2 && integer(range[1], "$.ports", 1, 65535) < start) linkProblem("$.ports", "Reversed port range")
            }
        }
        val rawPort = if (hopping != null) hopping.substringBefore(',').substringBefore('-') else portText
        val port = if (rawPort.isEmpty() && scheme in HYSTERIA2) 443 else integer(rawPort, "$.port", 1, 65535)
        val canonicalHost = try { SubscriptionDownloader.parseUrl("http://$host:$port/").host } catch (_: IllegalArgumentException) {
            linkProblem("$.server", "Invalid server", ProfileLinkErrorCode.INVALID_URL)
        }
        val name = uri.rawFragment?.let(::percentLinkText).orEmpty().ifEmpty {
            if (scheme in setOf("socks", "socks5", "socks5h", "anytls")) "$canonicalHost:$port" else ""
        }
        checkedLinkText(name, limits.maxParameterBytes, "$.name")
        return Address(uri, ProfileLinkQuery(uri.rawQuery, limits), canonicalHost, port, user, name, hopping)
    }

    private fun decodeShare(original: String, scheme: String): MutableMap<String, Any?> {
        // Share fragments are display labels; URI's host and query validation must remain strict.
        val fragment = original.indexOf('#')
        val value = if (fragment < 0) original else original.substring(0, fragment + 1) + original.substring(fragment + 1).replace(" ", "%20")
        when (scheme) {
            "hysteria", "ssr", "mierus" -> unsupported("$.scheme", "No ${if (scheme == "mierus") "mieru" else scheme} variant in config/internal/proxy.rs::OutboundProxyProtocol")
            "hysteria2+realm", "hy2+realm" -> unsupported("$.realm-opts", "No realm rendezvous in OutboundHysteria2 or its runtime")
        }
        if (scheme == "ss") return shadowsocks(value)
        if (scheme == "vmess" && '@' !in value.substringBefore('?').substringBefore('#')) return vmessJson(value)
        val address = address(value, scheme)
        val q = address.query
        val type = when (scheme) { "hy2" -> "hysteria2"; "socks", "socks5h" -> "socks5"; else -> scheme }
        val proxy = address.proxy(type)
        when (type) {
            "trojan", "anytls", "hysteria2" -> {
                val credentials = address.user ?: linkProblem("$.password", "Missing password")
                proxy["password"] = when (type) {
                    "anytls" -> percentLinkText(credentials.substringAfter(':', credentials))
                    "trojan" -> {
                        if (':' in credentials) linkProblem("$.password", "Escape : inside the Trojan password")
                        percentLinkText(credentials)
                    }
                    else -> percentLinkText(credentials)
                }
                if ((proxy["password"] as String).isEmpty()) linkProblem("$.password", "Password is empty")
                q.take("sni")?.let { proxy["sni"] = it }
                q.take("alpn")?.let { proxy["alpn"] = alpn(it) }
                proxy["skip-cert-verify"] = boolean(q.alias("insecure", "allowInsecure"), "$.query.insecure")
                if (type != "hysteria2") proxy["udp"] = true
                if (type == "hysteria2") {
                    address.ports?.let { proxy["ports"] = it }
                    q.take("obfs")?.let { if (it != "salamander") unsupported("$.query.obfs", "OutboundHysteria2 supports only salamander"); proxy["obfs"] = it }
                    q.take("obfs-password")?.let { proxy["obfs-password"] = it }
                    if (proxy["obfs"] != null && (proxy["obfs-password"] as? String).isNullOrEmpty()) linkProblem("$.query.obfs-password", "Obfuscation password is required")
                    if (proxy["obfs-password"] != null && proxy["obfs"] == null) linkProblem("$.query.obfs", "Obfuscation mode is required")
                    q.take("pinSHA256")?.let { proxy["fingerprint"] = it }
                    listOf("up", "down").forEach { key -> q.take(key)?.takeIf { it.isNotEmpty() }?.let {
                        unsupported("$.query.$key", "HystOption.up_down is stored but unused by proxy/hysteria2/outbound/mod.rs")
                    } }
                } else {
                    q.take("fp")?.takeIf { it.isNotEmpty() }?.let { unsupported("$.query.fp", "The TLS runtime does not apply client-fingerprint") }
                    q.take(if (type == "anytls") "hpkp" else "pcs")?.takeIf { it.isNotEmpty() }?.let {
                        unsupported("$.query.${if (type == "anytls") "hpkp" else "pcs"}", "The $type runtime does not apply certificate fingerprint pinning")
                    }
                    if (type == "trojan") transport(proxy, q, type)
                }
            }
            "socks5" -> {
                address.user?.let { raw ->
                    val plain = percentLinkText(raw)
                    val auth = if (':' in raw) plain else try {
                        base64LinkText(plain).takeIf { ':' in it } ?: plain
                    } catch (_: ProfileLinkProblem) { plain }
                    proxy["username"] = auth.substringBefore(':')
                    proxy["password"] = auth.substringAfter(':', "")
                }
                proxy["skip-cert-verify"] = true
            }
            "vmess", "vless" -> {
                val uuid = address.user?.let(::percentLinkText) ?: linkProblem("$.uuid", "Missing UUID")
                checkUuid(uuid)
                proxy["uuid"] = uuid
                proxy["udp"] = true
                val security = q.take("security").orEmpty().lowercase(Locale.ROOT)
                if (security !in setOf("", "none", "tls", "reality")) unsupported("$.query.security", "TLS mode is not supported by the fixed runtime")
                proxy["tls"] = security == "tls" || security == "reality"
                proxy["skip-cert-verify"] = boolean(q.alias("allowInsecure", "insecure"), "$.query.allowInsecure")
                q.take("sni")?.let { proxy["servername"] = it }
                q.take("fp")?.takeIf { it.isNotEmpty() }?.let { unsupported("$.query.fp", "uTLS client-fingerprint is not applied by the fixed runtime") }
                q.take("pcs")?.takeIf { it.isNotEmpty() }?.let { unsupported("$.query.pcs", "Certificate pinning is absent from OutboundVmess/OutboundVless") }
                q.take("alpn")?.takeIf { it.isNotEmpty() }?.let { unsupported("$.query.alpn", "V transport converters derive ALPN from network and do not accept an ALPN override") }
                q.take("packetEncoding")?.takeIf { it != "none" }?.let { unsupported("$.query.packetEncoding", "The fixed V datagram writers use ordinary UDP, without XUDP or packet-addr encoding") }
                if (type == "vless") {
                    q.take("encryption")?.takeIf { it !in listOf("", "none") }?.let { unsupported("$.query.encryption", "OutboundVless has no VLESS encryption layer") }
                    q.take("flow")?.takeIf { it.isNotEmpty() }?.let { proxy["flow"] = it.lowercase(Locale.ROOT) }
                } else {
                    proxy["alterId"] = 0
                    proxy["cipher"] = q.take("encryption")?.ifEmpty { "auto" } ?: "auto"
                }
                val publicKey = q.take("pbk")
                val shortId = q.take("sid")
                if (security == "reality") {
                    if (type != "vless") unsupported("$.query.security", "Reality is implemented only by the VLESS converter")
                    if (publicKey.isNullOrBlank()) linkProblem("$.query.pbk", "Reality requires a public key")
                    proxy["reality-opts"] = mapOf("public-key" to publicKey, "short-id" to shortId.orEmpty())
                } else if (publicKey != null || shortId != null) linkProblem("$.query.security", "Reality keys require security=reality")
                q.take("support-x25519mlkem768")?.let { unsupported("$.query.support-x25519mlkem768", "RealityOpt contains only public-key and short-id") }
                transport(proxy, q, type)
            }
            "tuic" -> {
                val credentials = address.user ?: linkProblem("$.uuid", "Missing TUIC credentials")
                if (':' !in credentials) unsupported("$.token", "OutboundTuic requires TUIC v5 UUID/password; no v4 token protocol")
                val uuid = percentLinkText(credentials.substringBefore(':'))
                checkUuid(uuid)
                proxy["uuid"] = uuid
                proxy["password"] = percentLinkText(credentials.substringAfter(':'))
                q.take("congestion_control")?.let { proxy["congestion-controller"] = it }
                q.take("alpn")?.let { proxy["alpn"] = alpn(it) }
                q.take("sni")?.let { proxy["sni"] = it }
                q.take("disable_sni")?.let { proxy["disable-sni"] = boolean(it, "$.query.disable_sni") }
                q.take("udp_relay_mode")?.let { proxy["udp-relay-mode"] = it }
                q.alias("allow_insecure", "insecure")?.let { proxy["skip-cert-verify"] = boolean(it, "$.query.insecure") }
            }
        }
        q.finish()
        return proxy
    }

    private fun shadowsocks(value: String): MutableMap<String, Any?> {
        val body = value.substringAfter("://")
        val main = body.substringBefore('#').substringBefore('?').removeSuffix("/")
        val suffix = body.substring(main.length)
        val expanded = if ('@' in main) value else "ss://${base64LinkText(percentLinkText(main))}$suffix"
        val a = address(expanded, "ss")
        val raw = a.user ?: linkProblem("$.userinfo", "Missing Shadowsocks credentials")
        val auth = if (':' in raw) percentLinkText(raw) else base64LinkText(percentLinkText(raw))
        if (':' !in auth) linkProblem("$.userinfo", "Expected cipher and password")
        val proxy = a.proxy("ss")
        proxy["cipher"] = auth.substringBefore(':').takeIf { it.isNotEmpty() } ?: linkProblem("$.cipher", "Missing cipher")
        proxy["password"] = auth.substringAfter(':')
        proxy["udp"] = true
        a.query.alias("udp-over-tcp", "uot")?.let { if (boolean(it, "$.query.udp-over-tcp")) unsupported("$.query.udp-over-tcp", "Shadowsocks runtime has no UDP-over-TCP framing") }
        a.query.take("plugin")?.let { plugin ->
            val parts = plugin.split(';')
            if (parts.size > limits.maxParameters) linkProblem("$.query.plugin", "Too many plugin options", ProfileLinkErrorCode.LIMIT_EXCEEDED)
            val options = linkedMapOf<String, Any?>()
            val type = when (parts.first()) {
                "obfs", "obfs-local", "simple-obfs" -> "obfs"
                "v2ray-plugin" -> "v2ray-plugin"
                "shadow-tls" -> "shadow-tls"
                else -> unsupported("$.query.plugin", "Plugin is not implemented by proxy/converters/shadowsocks.rs")
            }
            parts.drop(1).forEach { part ->
                val key = part.substringBefore('=')
                val target = when (key) { "obfs" -> "mode"; "obfs-host" -> "host"; else -> key }
                val rawValue = part.substringAfter('=', "true")
                val allowed = when (type) {
                    "obfs" -> setOf("mode", "host")
                    "shadow-tls" -> setOf("host", "password", "strict")
                    else -> setOf("mode", "host", "path", "tls", "mux", "skip-cert-verify", "port", "headers")
                }
                if (target !in allowed) linkProblem("$.query.plugin.$target", "Unknown plugin option")
                val mapped: Any? = when (target) {
                    "tls", "mux", "strict", "skip-cert-verify" -> boolean(rawValue, "$.query.plugin.$target")
                    "port" -> integer(rawValue, "$.query.plugin.port", 1, 65535)
                    "headers" -> jsonMapping(rawValue).also { headers -> if (headers.values.any { it !is String }) linkProblem("$.query.plugin.headers", "Expected string headers") }
                    else -> rawValue
                }
                if (options.put(target, mapped) != null) linkProblem("$.query.plugin.$target", "Duplicate plugin option", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
            }
            proxy["plugin"] = type
            proxy["plugin-opts"] = options
        }
        a.query.finish()
        return proxy
    }

    private fun vmessJson(value: String): MutableMap<String, Any?> {
        val body = value.substringAfter("://")
        val decoded = base64LinkText(percentLinkText(body.substringBefore('#')))
        val fields = jsonMapping(decoded)
        val supported = setOf("v", "ps", "add", "port", "id", "aid", "scy", "net", "type", "host", "path", "tls", "sni", "alpn", "fp", "allowInsecure")
        fields.keys.firstOrNull { it !in supported }?.let { linkProblem("$.vmess.$it", "Unknown VMess JSON field") }
        fun text(key: String, fallback: String = ""): String {
            val field = fields[key] ?: return fallback
            if (field !is String && !(field is Number && key in setOf("port", "aid", "v"))) linkProblem("$.vmess.$key", "Expected a string")
            return field.toString()
        }
        val host = text("add")
        val port = integer(text("port"), "$.vmess.port", 1, 65535)
        val authority = if (':' in host && !host.startsWith('[')) "[$host]" else host
        val server = try { SubscriptionDownloader.parseUrl("http://$authority:$port/").host } catch (_: IllegalArgumentException) { linkProblem("$.vmess.add", "Invalid server") }
        val uuid = text("id")
        checkUuid(uuid)
        val proxy = linkedMapOf<String, Any?>("type" to "vmess", "name" to text("ps"), "server" to server, "port" to port,
            "uuid" to uuid, "alterId" to integer(text("aid", "0"), "$.vmess.aid", 0, 65535), "cipher" to text("scy", "auto"), "udp" to true)
        val tls = text("tls").lowercase(Locale.ROOT)
        if (tls !in listOf("", "none", "tls")) unsupported("$.vmess.tls", "TLS mode is not supported by the fixed runtime")
        proxy["tls"] = tls == "tls"
        proxy["skip-cert-verify"] = boolean(fields["allowInsecure"]?.toString(), "$.vmess.allowInsecure")
        if (text("sni").isNotEmpty()) proxy["servername"] = text("sni")
        if (text("fp").isNotEmpty()) unsupported("$.vmess.fp", "VMess runtime does not support uTLS client-fingerprint")
        if (text("alpn").isNotEmpty()) unsupported("$.vmess.alpn", "VMess converter derives ALPN from the transport")
        val params = linkedMapOf("type" to text("net", "tcp"), "headerType" to text("type"), "host" to text("host"), "path" to text("path"))
        if (params["type"] == "grpc") params["serviceName"] = params.remove("path").orEmpty()
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${encodeQuery(v)}" }
        transport(proxy, ProfileLinkQuery(query, limits), "vmess", json = true)
        return proxy
    }

    private fun transport(proxy: MutableMap<String, Any?>, q: ProfileLinkQuery, type: String, json: Boolean = false) {
        var network = q.take("type").orEmpty().ifEmpty { "tcp" }.lowercase(Locale.ROOT)
        val header = q.take("headerType").orEmpty().lowercase(Locale.ROOT)
        if (header !in setOf("", "none")) {
            if (header == "http") unsupported("$.query.headerType", "Fixed V transports have no HTTP camouflage writer")
            linkProblem("$.query.headerType", "Unknown transport header")
        }
        if (network == "http") network = "h2"
        if (type == "trojan" && network !in setOf("tcp", "ws", "grpc")) unsupported("$.query.type", "Trojan converter implements TCP, WS and gRPC")
        if (network !in setOf("tcp", "ws", "h2", "grpc", "xhttp", "splithttp")) unsupported("$.query.type", "Transport is not implemented by the fixed V converter")
        val host = q.take("host")
        var path = q.take("path")
        val method = q.take("method")
        val service = q.take("serviceName")
        var early = q.take("ed")
        var earlyHeader = q.take("eh")
        val mode = q.take("mode")
        val extra = q.take("extra")
        if (!method.isNullOrEmpty()) unsupported("$.query.method", "Custom HTTP camouflage methods are not implemented")
        if (network == "tcp") {
            if (listOf(host, path, service, early, earlyHeader, mode, extra).any { !it.isNullOrEmpty() }) linkProblem("$.query.type", "Transport options require an explicit transport")
            if (type == "vless") proxy["network"] = "tcp"
            return
        }
        proxy["network"] = network
        if (network == "ws" && json && !path.isNullOrEmpty()) {
            val uri = linkUri(path)
            val embedded = ProfileLinkQuery(uri.rawQuery, limits)
            val embeddedEarly = embedded.take("ed")
            val embeddedHeader = embedded.take("eh")
            if (embeddedEarly != null) {
                if (early != null) linkProblem("$.query.ed", "Duplicate WebSocket early data", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
                early = embeddedEarly
                val remaining = uri.rawQuery.orEmpty().split('&').filter { percentLinkText(it.substringBefore('='), true) != "ed" }
                path = uri.rawPath + if (remaining.isEmpty()) "" else "?${remaining.joinToString("&")}"
            }
            if (embeddedHeader != null) earlyHeader = embeddedHeader
        }
        if (network != "ws" && (early != null || earlyHeader != null)) linkProblem("$.query.ed", "WebSocket options require WS")
        if (network != "grpc" && !service.isNullOrEmpty()) linkProblem("$.query.serviceName", "gRPC service requires gRPC")
        if (network !in setOf("xhttp", "splithttp") && (!mode.isNullOrEmpty() || extra != null)) linkProblem("$.query.extra", "XHTTP options require XHTTP")
        when (network) {
            "ws" -> {
                val options = linkedMapOf<String, Any?>("path" to (path?.ifEmpty { "/" } ?: "/"))
                if (!host.isNullOrEmpty()) options["headers"] = mapOf("Host" to host)
                early?.let { options["max-early-data"] = integer(it, "$.query.ed", 0, 1 shl 20); options["early-data-header-name"] = "Sec-WebSocket-Protocol" }
                earlyHeader?.let { options["early-data-header-name"] = it }
                proxy["ws-opts"] = options
            }
            "h2" -> {
                val options = linkedMapOf<String, Any?>("path" to (path?.ifEmpty { "/" } ?: "/"))
                if (!host.isNullOrEmpty()) options["host"] = listOf(host)
                proxy["h2-opts"] = options
            }
            "grpc" -> {
                if (!host.isNullOrEmpty() || !path.isNullOrEmpty()) linkProblem("$.query.serviceName", "Use serviceName for gRPC")
                proxy["grpc-opts"] = mapOf("grpc-service-name" to service.orEmpty())
            }
            else -> {
                val options = linkedMapOf<String, Any?>()
                path?.takeIf { it.isNotEmpty() }?.let { options["path"] = it }
                host?.takeIf { it.isNotEmpty() }?.let { options["host"] = it }
                mode?.takeIf { it.isNotEmpty() }?.let { options["mode"] = it }
                extra?.let {
                    val mapping = jsonMapping(it)
                    mapping.keys.firstOrNull { key -> key !in setOf("headers", "xPaddingBytes") }?.let { key ->
                        unsupported("$.query.extra.$key", "XhttpOpt supports headers and x-padding-bytes, without this extra option")
                    }
                    mapping["headers"]?.let { headers ->
                        if (headers !is Map<*, *> || headers.values.any { v -> v !is String }) linkProblem("$.query.extra.headers", "Expected string headers")
                        options["headers"] = headers
                    }
                    mapping["xPaddingBytes"]?.let { padding ->
                        if (padding !is String) linkProblem("$.query.extra.xPaddingBytes", "Expected a string")
                        options["x-padding-bytes"] = padding
                    }
                }
                proxy["xhttp-opts"] = options
            }
        }
    }

    private fun jsonMapping(value: String): MutableMap<String, Any?> {
        if (!value.trimStart().startsWith('{')) linkProblem("$.json", "Expected a JSON object", ProfileLinkErrorCode.INVALID_CONFIGURATION)
        checkedLinkText(value, limits.maxParameterBytes, "$.json")
        requireLinkJson(value)
        val mapping = try { ConfigYaml.parse(value, limits.maxParameterBytes) } catch (_: ConfigValidationException) {
            linkProblem("$.json", "Invalid JSON mapping or duplicate keys", ProfileLinkErrorCode.INVALID_CONFIGURATION)
        }
        fun check(item: Any?) {
            when (item) {
                is String -> {
                    checkedLinkText(item, limits.maxParameterBytes, "$.json")
                    if (item.any { it.isISOControl() }) linkProblem("$.json", "Control characters are not allowed in share JSON")
                }
                is Map<*, *> -> item.forEach { (key, v) -> check(key); check(v) }
                is List<*> -> item.forEach(::check)
            }
        }
        check(mapping)
        return mapping
    }

    private fun integer(value: String, field: String, min: Int, max: Int): Int {
        val number = value.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
        if (number == null || number !in min..max) linkProblem(field, "Expected an integer in $min..$max")
        return number
    }

    private fun boolean(value: String?, field: String): Boolean = when (value?.lowercase(Locale.ROOT)) {
        null, "0", "false" -> false
        "1", "true" -> true
        else -> linkProblem(field, "Expected 0, 1, false or true")
    }

    private fun alpn(value: String): List<String> = value.split(',').also {
        if (it.size > 16 || it.any { part -> part.isEmpty() || part.length > 255 }) linkProblem("$.query.alpn", "Invalid ALPN list")
    }

    private fun checkUuid(value: String) {
        if (!value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) linkProblem("$.uuid", "Invalid UUID")
        try { UUID.fromString(value) } catch (_: IllegalArgumentException) { linkProblem("$.uuid", "Invalid UUID") }
    }

    private fun freeName(base: String, taken: MutableSet<String>): String {
        if (base.any { it.isISOControl() }) linkProblem("$.name", "Control characters are not allowed in names")
        fun prefix(value: String, size: Int): String = value.take(size).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        val stem = prefix(base.trim().ifEmpty { "Imported profile" }, 200)
        var name = stem
        var index = 1
        while (!taken.add(name)) {
            val suffix = "-${(index++).toString().padStart(2, '0')}"
            name = prefix(stem, 200 - suffix.length) + suffix
        }
        return name
    }

    private fun unsupported(field: String, reason: String): Nothing =
        linkProblem(field, "clash-rs 0.10.10: $reason", ProfileLinkErrorCode.UNSUPPORTED_CAPABILITY)

    private fun encodeQuery(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        val SHARE_SCHEMES: Set<String> = setOf("hysteria", "hysteria2", "hy2", "hysteria2+realm", "hy2+realm", "tuic", "trojan", "vless", "vmess", "ss", "ssr", "socks", "socks5", "socks5h", "anytls", "mierus")
        private val HYSTERIA2 = setOf("hysteria2", "hy2")
        private val INSTALL_SCHEMES = setOf("clash", "clashmeta", "flclash")
        private val RESERVED_NAMES = setOf("DIRECT", "REJECT", "REJECT-DROP", "COMPATIBLE", "PASS", "PASS-RULE", "GLOBAL")
        private val CORE_PROTOCOLS = setOf("direct", "reject", "ss", "socks5", "anytls", "trojan", "vmess", "vless", "wireguard", "tor", "tuic", "hysteria2", "ssh", "shadowquic", "tailscale")
        private val LINK_START = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
        private val BASE64_TEXT = Regex("[A-Za-z0-9+/_=\\-\\r\\n]+")
    }
}
