package dev.aerove.lite.data

import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.regex.Pattern
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver

internal object ConfigYaml {
    fun parse(source: String): MutableMap<String, Any?> {
        if (source.toByteArray(Charsets.UTF_8).size > MAX_PROFILE_BYTES) {
            throw ConfigValidationException(listOf(ConfigIssue("$", "Configuration is too large")))
        }
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = false
            allowRecursiveKeys = false
            maxAliasesForCollections = 50
            nestingDepthLimit = 50
            codePointLimit = MAX_PROFILE_BYTES
        }
        val value = try {
            val dumper = DumperOptions()
            Yaml(SafeConstructor(options), Representer(dumper), dumper, options, CoreResolver()).load<Any?>(source)
        } catch (failure: Exception) {
            val line = (failure as? MarkedYAMLException)?.problemMark?.line?.plus(1)
            throw ConfigValidationException(listOf(ConfigIssue("$", "Invalid YAML${line?.let { " at line $it" } ?: ""}")))
        }
        val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var values = 0
        fun copy(item: Any?, path: String): Any? {
            if (++values > 100000) throw ConfigValidationException(listOf(ConfigIssue(path, "Expanded configuration exceeds 100000 values")))
            if (item !is Map<*, *> && item !is List<*>) return item
            if (!ancestors.add(item)) throw ConfigValidationException(listOf(ConfigIssue(path, "Recursive YAML alias")))
            try {
                return when (item) {
                    is Map<*, *> -> item.entries.associateTo(linkedMapOf<String, Any?>()) { (key, v) ->
                        if (key !is String) throw ConfigValidationException(listOf(ConfigIssue(path, "Keys must be strings")))
                        key to copy(v, "$path.$key")
                    }
                    is List<*> -> item.mapIndexed { index, v -> copy(v, "$path[$index]") }
                    else -> error("Unreachable")
                }
            } finally {
                ancestors.remove(item)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return copy(value, "$") as? MutableMap<String, Any?>
            ?: throw ConfigValidationException(listOf(ConfigIssue("$", "Expected one configuration mapping")))
    }

    fun dump(value: Map<String, Any?>): String = Yaml().dump(value)

    // SnakeYAML's YAML 1.1 resolver otherwise turns proxy names/passwords such as "on" into booleans.
    private class CoreResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF")
            addImplicitResolver(Tag.INT, Pattern.compile("^[-+]?(?:0|[1-9][0-9_]*|0x[0-9a-fA-F_]+|0b[01_]+)$"), "-+0123456789")
            addImplicitResolver(Tag.FLOAT, Pattern.compile("^[-+]?(?:(?:[0-9][0-9_]*\\.[0-9_]*|\\.[0-9_]+)(?:[eE][-+]?[0-9]+)?|[0-9][0-9_]*[eE][-+]?[0-9]+|\\.(?:inf|Inf|INF|nan|NaN|NAN))$"), "-+0123456789.")
            addImplicitResolver(Tag.MERGE, MERGE, "<")
            addImplicitResolver(Tag.NULL, NULL, "~nN\u0000")
            addImplicitResolver(Tag.NULL, EMPTY, null)
        }
    }
}

class CompatibilityValidator(
    private val native: NativeConfigValidator,
    private val resourceDirectory: File? = null,
) : ProfileValidator {
    override fun validate(yaml: String) {
        val root = ConfigYaml.parse(yaml)
        val issues = mutableListOf<ConfigIssue>()
        fun issue(path: String, reason: String) { issues += ConfigIssue(path, reason) }
        fun fields(value: Any?, path: String, supported: Set<String>): Map<String, Any?> {
            if (value == null) return emptyMap()
            if (value !is Map<*, *>) {
                issue(path, "Expected a mapping")
                return emptyMap()
            }
            value.keys.filterIsInstance<String>().filterNot { it in supported }.forEach {
                issue("$path.$it", "Field is not admitted by the pinned Lite compatibility schema ($CORE_VERSION)")
            }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        fields(root, "$", TOP_LEVEL)
        UNSUPPORTED_TOP.forEach { key ->
            if (root[key] != null) issue("$.$key", "This capability is not available in Android Lite")
        }
        listOf("mmdb-download-url", "asn-mmdb-download-url", "geosite-download-url").forEach { key ->
            if (root[key] != null) issue("$.$key", "clash-rs supports this resource; Lite has not integrated bounded download, validation and rollback")
        }
        listOf("proxy-providers", "rule-providers").forEach { key ->
            if ((root[key] as? Map<*, *>)?.isNotEmpty() == true) {
                issue("$.$key", "Managed provider download/update validation is not implemented; import a self-contained profile")
            }
        }
        fields(root["profile"], "$.profile", keys("store-selected store-fake-ip store-smart-stats"))
        fields(root["experimental"], "$.experimental", keys(
            "tcp-buffer-size anytls-duplex-buffer-size anytls-relay-buffer-size closed-flows-cap ignore-resolve-fail",
        ))
        val dns = fields(root["dns"], "$.dns", DNS_FIELDS)
        if (dns["enhanced-mode"] == "redir-host") issue("$.dns.enhanced-mode", "redir-host is parsed but not implemented in $CORE_VERSION")
        listOf("nameserver", "fallback", "default-nameserver", "proxy-server-nameserver").forEach { key ->
            (dns[key] as? List<*>)?.forEachIndexed { i, server ->
                if (server is String) runCatching { validateDnsServer(server) }.onFailure {
                    issue("$.dns.$key[$i]", it.message ?: "Unsupported DNS server")
                }
            }
        }
        (dns["fake-ip-filter"] as? List<*>)?.forEachIndexed { i, value ->
            if (value is String && (value.contains(":") || value.contains(","))) {
                issue("$.dns.fake-ip-filter[$i]", "Only domain trie patterns are supported; no GEOSITE/RULE-SET filters")
            }
        }
        (dns["nameserver-policy"] as? Map<*, *>)?.forEach { (key, value) ->
            if (value !is String) issue("$.dns.nameserver-policy.$key", "Expected one DNS server string, not a list")
            else runCatching { validateDnsServer(value) }.onFailure {
                issue("$.dns.nameserver-policy.$key", it.message ?: "Unsupported DNS server")
            }
        }
        fields(dns["fallback-filter"], "$.dns.fallback-filter", keys("geoip geoip-code ipcidr domain"))
        fields(dns["edns-client-subnet"], "$.dns.edns-client-subnet", keys("ipv4 ipv6"))
        val listen = dns["listen"]
        if (listen is Map<*, *>) {
            fields(listen, "$.dns.listen", keys("udp tcp doh dot doh3"))
            listOf("doh", "dot", "doh3").forEach {
                fields(listen[it], "$.dns.listen.$it", keys("addr ca-cert ca-key hostname"))
            }
        }
        val tun = fields(root["tun"], "$.tun", keys("enable device-id device-url device gateway gateway-v6 gatewayv6 mtu dns-hijack routes route-all so-mark route-table"))
        listOf("routes", "route-all", "so-mark", "route-table").forEach {
            if (tun[it] != null) issue("$.tun.$it", "Android VPN routes and socket ownership belong to the service")
        }
        (root["proxies"] as? List<*>)?.forEachIndexed { index, value ->
            val path = "$.proxies[$index]"
            val type = (value as? Map<*, *>)?.get("type") as? String
            val schema = PROXY_FIELDS[type]
            if (schema == null) issue("$path.type", "Protocol is not supported by the Lite adapter")
            else {
                val common = when (type) {
                    "direct", "reject" -> setOf("name")
                    "hysteria2" -> keys("name server port")
                    else -> COMMON_PROXY
                }
                val proxy = fields(value, path, schema + common + "type")
                val ignored = when (type) {
                    "anytls" -> keys("fingerprint client-fingerprint idle-session-check-interval idle-session-timeout min-idle-session")
                    "vless" -> setOf("client-fingerprint")
                    else -> emptySet()
                }
                ignored.filter { proxy[it] != null }.forEach { issue("$path.$it", "Parsed but ignored by clash-rs $CORE_VERSION") }
                fields(proxy["ws-opts"], "$path.ws-opts", keys("path headers max-early-data early-data-header-name"))
                fields(proxy["h2-opts"], "$path.h2-opts", keys("host path"))
                fields(proxy["grpc-opts"], "$path.grpc-opts", keys("grpc-service-name"))
                listOf("xhttp-opts", "splithttp-opts").forEach {
                    fields(proxy[it], "$path.$it", keys("path host mode headers x-padding-bytes"))
                }
                fields(proxy["reality-opts"], "$path.reality-opts", keys("public-key short-id"))
                if (proxy["plugin-opts"] != null) {
                    val pluginFields = when (proxy["plugin"]) {
                        "obfs", "obfs-local" -> keys("mode host")
                        "v2ray-plugin" -> keys("mode host path tls mux skip-cert-verify")
                        else -> emptySet()
                    }
                    fields(proxy["plugin-opts"], "$path.plugin-opts", pluginFields)
                }
                listOf("tls-cert", "tls-key", "ca").forEach { key ->
                    (proxy[key] as? String)?.let { checkResource(it, "$path.$key", issues) }
                }
            }
        }
        (root["proxy-groups"] as? List<*>)?.forEachIndexed { index, value ->
            val path = "$.proxy-groups[$index]"
            val type = (value as? Map<*, *>)?.get("type") as? String
            val schema = GROUP_FIELDS[type]
            if (schema == null) issue("$path.type", "Unsupported proxy group")
            else fields(value, path, schema + keys("type name proxies use icon url"))
        }
        val rules = root["rules"] as? List<*> ?: emptyList<Any?>()
        rules.forEachIndexed { index, rule ->
            if (rule is String && ANDROID_RULE.containsMatchIn(rule)) {
                issue("$.rules[$index]", "UID and process matching are not supported on Android")
            }
            if (rule is String && GEO_RULE.containsMatchIn(rule)) {
                val key = if (rule.contains("GEOSITE", ignoreCase = true)) "geosite" else "mmdb"
                if (root[key] !is String) issue("$.rules[$index]", "$key database is required; automatic geo downloads are not provided")
            }
        }
        listOf("mmdb", "asn-mmdb", "geosite").forEach { key ->
            (root[key] as? String)?.let { checkResource(it, "$.$key", issues) }
        }
        if (issues.isNotEmpty()) throw ConfigValidationException(issues)
        native.validate(yaml)?.let { diagnostic ->
            throw ConfigValidationException(listOf(ConfigIssue("$", diagnostic.take(2000))))
        }
    }

    private fun checkResource(value: String, path: String, issues: MutableList<ConfigIssue>) {
        val directory = resourceDirectory
        if (directory == null) {
            issues += ConfigIssue(path, "An app-private resource file is required; automatic resource download is not provided")
            return
        }
        try {
            val file = privateResource(File(directory, "resources"), value)
            if (!file.isFile || !file.canRead()) issues += ConfigIssue(path, "App-private resource file is missing")
        } catch (_: IllegalArgumentException) {
            issues += ConfigIssue(path, "Resource path must stay inside the app-private working directory")
        }
    }

    companion object {
        const val CORE_VERSION = "0.10.10"
        private fun keys(value: String): Set<String> = value.split(' ').filter { it.isNotEmpty() }.toSet()
        private val TOP_LEVEL = keys("port http_port socks-port redir-port tproxy-port mixed-port authentication allow-lan bind-address mode log-level dns profile proxies proxy-groups rules hosts mmdb mmdb-download-url asn-mmdb asn-mmdb-download-url geosite geosite-download-url ipv6 external-controller external-controller-ipc external-controller-unix external-ui external-ui-url secret cors-allow-origins interface routing-mark proxy-providers rule-providers experimental tun listeners inbound-providers")
        private val UNSUPPORTED_TOP = keys("listeners inbound-providers external-ui external-ui-url external-controller-ipc external-controller-unix interface routing-mark")
        private val DNS_FIELDS = keys("enable ipv6 use-hosts nameserver fallback fallback-filter listen enhanced-mode fake-ip-range fake-ip-filter default-nameserver proxy-server-nameserver nameserver-policy edns-client-subnet respect-rules")
        private val COMMON_PROXY = keys("name server port connect-via dialer-proxy")
        private val PROXY_FIELDS = mapOf(
            "direct" to emptySet(), "reject" to emptySet(),
            "ss" to keys("cipher password udp plugin plugin-opts"),
            "socks5" to keys("username password tls sni skip-cert-verify udp"),
            "anytls" to keys("password alpn sni skip-cert-verify fingerprint client-fingerprint udp idle-session-check-interval idle-session-timeout min-idle-session tls-cert tls-key"),
            "trojan" to keys("password alpn sni skip-cert-verify udp network grpc-opts ws-opts tls-cert tls-key"),
            "vmess" to keys("uuid alter-id alterId cipher udp tls skip-cert-verify server-name servername network ws-opts h2-opts grpc-opts xhttp-opts splithttp-opts tls-cert tls-key"),
            "vless" to keys("uuid udp tls skip-cert-verify server-name servername network ws-opts h2-opts grpc-opts xhttp-opts splithttp-opts reality-opts flow client-fingerprint tls-cert tls-key"),
            "tuic" to keys("uuid password ip heartbeat-interval alpn disable-sni reduce-rtt request-timeout udp-relay-mode congestion-controller max-udp-relay-packet-size fast-open skip-cert-verify max-open-stream sni gc-interval gc-lifetime send-window receive-window tls-cert tls-key"),
            "hysteria2" to keys("ports hop-interval hop_interval password obfs obfs-password alpn up down sni skip-cert-verify ca ca-str fingerprint udp-mtu disable-mtu-discovery cwnd tls-cert tls-key"),
            "ssh" to keys("username password private-key private-key-passphrase host-key host-key-algorithms totp-opt"),
        )
        private val GROUP_FIELDS = mapOf(
            "select" to keys("udp"), "relay" to emptySet(),
            "url-test" to keys("interval lazy tolerance"), "fallback" to keys("interval lazy"),
            "load-balance" to keys("interval lazy strategy"),
            "smart" to keys("udp lazy max-retries site-stickiness bandwidth-weight"),
        )
        private val ANDROID_RULE = Regex("(?:^|[,(])\\s*(?:UID|PROCESS-NAME|PROCESS-PATH|PROCESS-NAME-REGEX|PROCESS-PATH-REGEX)\\s*,", RegexOption.IGNORE_CASE)
        private val GEO_RULE = Regex("(?:^|[,(])\\s*(?:GEOIP|GEOSITE)\\s*,", RegexOption.IGNORE_CASE)
    }
}

internal fun privateResource(directory: File, path: String): File {
    require(path.isNotBlank() && !File(path).isAbsolute && !path.contains('\\')) { "Relative private resource path required" }
    val root = directory.canonicalFile.toPath()
    val file = File(directory, path).canonicalFile
    require(file.toPath().startsWith(root) && file.toPath() != root) { "Resource path escapes private directory" }
    return file
}
