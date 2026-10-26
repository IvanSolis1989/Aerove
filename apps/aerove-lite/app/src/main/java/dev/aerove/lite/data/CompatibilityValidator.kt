package dev.aerove.lite.data

import java.io.File
import java.io.Reader
import java.io.StringWriter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.regex.Pattern
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.composer.Composer
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.emitter.Emitter
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.parser.Parser
import org.yaml.snakeyaml.parser.ParserImpl
import org.yaml.snakeyaml.reader.StreamReader
import org.yaml.snakeyaml.representer.LiteSafeRepresenter
import org.yaml.snakeyaml.resolver.Resolver
import org.yaml.snakeyaml.serializer.Serializer

internal object ConfigYaml {
    fun parse(source: String, maxBytes: Int = MAX_PROFILE_BYTES): MutableMap<String, Any?> {
        if (source.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw ConfigValidationException(listOf(ConfigIssue("$", "Configuration is too large")))
        }
        return load(StreamReader(source), maxBytes, MAX_EXPANDED_VALUES)
    }

    fun parse(source: Reader, maxBytes: Int, maxValues: Int): MutableMap<String, Any?> = load(StreamReader(source), maxBytes, maxValues)

    /** The same loader with every event counted before the Composer sees it, so no input builds more nodes than the budget. */
    internal fun parseRule(stream: BoundedYamlStream, maxBytes: Int, maxValues: Int): MutableMap<String, Any?> {
        val options = options(maxBytes)
        return load(options, YamlValueBudget(maxValues, options, stopAtEarlierError = false).guard(ParserImpl(stream, options), stream), maxValues)
    }

    internal fun options(maxBytes: Int) = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        allowRecursiveKeys = false
        maxAliasesForCollections = 50
        nestingDepthLimit = 50
        codePointLimit = maxBytes
    }

    private fun load(stream: StreamReader, maxBytes: Int, maxValues: Int): MutableMap<String, Any?> =
        options(maxBytes).let { load(it, ParserImpl(stream, it), maxValues) }

    private fun load(options: LoaderOptions, parser: Parser, maxValues: Int): MutableMap<String, Any?> {
        val value = try {
            val constructor = SafeConstructor(options)
            constructor.setAllowDuplicateKeys(options.isAllowDuplicateKeys)
            constructor.setWarnOnDuplicateKeys(options.isWarnOnDuplicateKeys)
            constructor.setWrappedToRootException(options.isWrappedToRootException)
            constructor.setComposer(Composer(parser, CoreResolver(), options))
            constructor.getSingleData(Any::class.java)
        } catch (bound: ConfigValidationException) {
            throw bound
        } catch (failure: Exception) {
            val line = (failure as? MarkedYAMLException)?.problemMark?.line?.plus(1)
            throw ConfigValidationException(listOf(ConfigIssue("$", "Invalid YAML${line?.let { " at line $it" } ?: ""}")))
        }
        val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        var values = 0
        fun copy(item: Any?, path: String): Any? {
            if (++values > maxValues) throw ConfigValidationException(listOf(ConfigIssue(path, "Expanded configuration exceeds $maxValues values")))
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

    private const val MAX_EXPANDED_VALUES = 100000

    fun dump(value: Map<String, Any?>): String {
        val writer = StringWriter()
        val options = DumperOptions()
        val serializer = Serializer(Emitter(writer, options), Resolver(), options, null)
        serializer.open()
        try {
            serializer.serialize(LiteSafeRepresenter(options).represent(value))
        } finally {
            serializer.close()
        }
        return writer.toString()
    }

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

/** The two-argument constructor retains the historical schema; production explicitly selects forMeow. */
class CompatibilityValidator private constructor(
    private val native: NativeConfigValidator,
    private val resourceDirectory: File?,
    private val coreSchema: ProfileCompatibilitySchema?,
) : ProfileValidator {
    constructor(native: NativeConfigValidator, resourceDirectory: File? = null) : this(native, resourceDirectory, null)
    val coreVersion: String get() = coreSchema?.coreVersion ?: CORE_VERSION
    internal fun resourceInput(yaml: String): MutableMap<String, Any?> = coreSchema?.resourceInput(yaml) ?: ConfigYaml.parse(yaml)
    internal fun providerDownloadInput(yaml: String): String = coreSchema?.providerDownloadInput(yaml) ?: yaml
    internal suspend fun preflightPreparedRule(path: File, behavior: String, format: String): RuleResourcePreflight? =
        coreSchema?.preflightPreparedRule(path, behavior, format)

    override suspend fun validate(yaml: String) = validate(yaml, false, null)

    internal suspend fun validateManagedSource(yaml: String) = validate(yaml, true, null)

    internal suspend fun validatePrepared(yaml: String, bundleDirectory: File) = validate(yaml, true, bundleDirectory)

    private suspend fun validate(yaml: String, managed: Boolean, bundleDirectory: File?) {
        coreSchema?.let { it.validate(yaml, managed, bundleDirectory); return }
        val root = ConfigYaml.parse(yaml)
        val issues = mutableListOf<ConfigIssue>()
        fun issue(path: String, reason: String) { issues += ConfigIssue(path, reason) }
        fun fields(value: Any?, path: String, supported: Set<String>, reasons: Map<String, String> = emptyMap()): Map<String, Any?> {
            if (value == null) return emptyMap()
            if (value !is Map<*, *>) {
                issue(path, "Expected a mapping")
                return emptyMap()
            }
            value.keys.filterIsInstance<String>().filterNot { it in supported }.forEach {
                issue("$path.$it", reasons[it] ?: "Field is not admitted by the pinned Lite compatibility schema ($CORE_VERSION)")
            }
            @Suppress("UNCHECKED_CAST")
            return value as Map<String, Any?>
        }
        fields(root, "$", TOP_LEVEL, mapOf(
            "ntp" to "Fixed clash-rs has no NTP service or adjusted-clock consumer (config/internal/config.rs; lib.rs startup)",
            "sniffer" to "Fixed clash-rs has no payload HTTP/TLS/QUIC sniffing and destination-override path (app/dispatcher/dispatcher_impl.rs)",
            "geodata-loader" to "Fixed clash-rs GeoData directly decodes the full GeoSiteList; no selectable standard/memconservative loader (common/geodata/mod.rs)",
        ))
        listOf("unified-delay", "tcp-concurrent").forEach { key ->
            if (root.containsKey(key) && root[key] !is Boolean) issue("$.$key", "Expected a Boolean")
        }
        if (root.containsKey("find-process-mode") && (root["find-process-mode"] !is String ||
            (root["find-process-mode"] as String).lowercase(java.util.Locale.ROOT) !in setOf("strict", "always", "off")))
            issue("$.find-process-mode", "Expected strict, always or off")
        UNSUPPORTED_TOP.forEach { key ->
            if (root[key] != null) issue("$.$key", "This capability is not available in Android Lite")
        }
        listOf("redir-port", "tproxy-port").forEach { key ->
            if (root[key] != null && root[key] != 0) issue("$.$key",
                "clash-rs $CORE_VERSION has no Android native handler for $key (fixed network_listener target_os=linux only)")
        }
        listOf("mmdb-download-url", "asn-mmdb-download-url", "geosite-download-url").forEach { key ->
            if (root[key] != null && (!managed || bundleDirectory != null)) {
                issue("$.$key", "Resource downloads require the complete bundle preparation path")
            }
        }
        listOf("proxy-providers", "rule-providers").forEach { key ->
            if (!managed && (root[key] as? Map<*, *>)?.isNotEmpty() == true) {
                issue("$.$key", "Providers require the complete ResourceBundleManager preparation path")
            }
            (root[key] as? Map<*, *>)?.forEach { (name, raw) ->
                val path = "$.$key.$name"
                val type = (raw as? Map<*, *>)?.get("type")
                val allowed = when {
                    key == "proxy-providers" && type == "http" -> keys("type url interval path health-check")
                    key == "proxy-providers" && type == "file" -> keys("type path interval health-check")
                    key == "rule-providers" && type == "http" -> keys("type url interval path behavior format inline-rules payload")
                    key == "rule-providers" && type == "file" -> keys("type interval path behavior format inline-rules payload")
                    key == "rule-providers" && type == "inline" -> keys("type path behavior inline-rules payload")
                    else -> emptySet()
                }
                val provider = fields(raw, path, allowed)
                fields(provider["health-check"], "$path.health-check", keys("enable url interval lazy"))
                if (managed && bundleDirectory != null && type == "http") issue(path, "Unsealed remote provider in prepared bundle")
                if (type == "file" && bundleDirectory != null) {
                    (provider["path"] as? String)?.let { checkResource(it, "$path.path", issues, bundleDirectory) }
                }
                if (provider["format"] == "mrs" && listOf("inline-rules", "payload").any {
                    (provider[it] as? List<*>)?.isNotEmpty() == true
                }) {
                    issue(path, "clash-rs MRS parsing does not apply inline payload; refusing to discard it")
                }
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
                if (server is String) runCatching { dev.aerove.lite.dnssettings.validateDnsServer(server, "$.dns.$key[$i]") }.onFailure {
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
            else runCatching { dev.aerove.lite.dnssettings.validateDnsServer(value, "$.dns.nameserver-policy.$key") }.onFailure {
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
            if (schema == null) issue("$path.type", if (type in FIXED_UNSUPPORTED_PROTOCOLS)
                "Fixed clash-rs has no $type outbound variant, converter or handler (config/internal/proxy.rs; app/outbound/manager.rs)"
                else "Unknown outbound protocol")
            else {
                val common = when (type) {
                    "direct", "reject" -> setOf("name")
                    "hysteria2" -> keys("name server port")
                    "tailscale" -> keys("name")
                    else -> COMMON_PROXY
                }
                val unsupportedFields = when (type) {
                    "wireguard" -> keys("peers workers persistent-keepalive amnezia-wg-option ip-stack").associateWith {
                        "Fixed WireGuard has one peer DTO; this field has no converter consumer (config/internal/proxy.rs; proxy/wg/mod.rs)"
                    }
                    "shadowquic" -> keys("quic-versions up down cwnd bbr-profile recv-window-conn recv-window max-datagram-frame-size max-open-streams").associateWith {
                        "Fixed ShadowQUIC DTO/converter has no consumer for this option (config/internal/proxy.rs; proxy/converters/shadowquic.rs)"
                    }
                    "tailscale" -> keys("accept-routes exit-node exit-node-allow-lan-access udp").associateWith {
                        "Fixed Tailscale DTO/constructor has no consumer for this option (config/internal/proxy.rs; proxy/converters/tailscale.rs)"
                    }
                    else -> emptyMap()
                }
                val proxy = fields(value, path, schema + common + "type", unsupportedFields)
                type?.takeIf { it in setOf("ssh", "wireguard", "shadowquic", "tailscale") }?.let { validateExtendedProxy(proxy, path, it, ::issue) }
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
                        "v2ray-plugin" -> keys("mode host path tls mux skip-cert-verify port headers")
                        "shadow-tls" -> keys("host password strict")
                        else -> emptySet()
                    }
                    fields(proxy["plugin-opts"], "$path.plugin-opts", pluginFields)
                }
                listOf("tls-cert", "tls-key", "ca").forEach { key ->
                    (proxy[key] as? String)?.let {
                        if (!managed || bundleDirectory != null) checkResource(it, "$path.$key", issues, bundleDirectory)
                    }
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
                issue("$.rules[$index]", "UID, IN-USER and process regex/wildcard rules have no Android matcher in fixed clash-rs")
            }
            if (rule is String && GEO_RULE.containsMatchIn(rule)) {
                val key = if (rule.contains("GEOSITE", ignoreCase = true)) "geosite" else "mmdb"
                if (!managed && root[key] !is String) issue("$.rules[$index]", "$key database requires managed bundle preparation")
            }
        }
        listOf("mmdb", "asn-mmdb", "geosite").forEach { key ->
            (root[key] as? String)?.let {
                if (!managed || bundleDirectory != null) checkResource(it, "$.$key", issues, bundleDirectory)
            }
        }
        if (issues.isNotEmpty()) throw ConfigValidationException(issues)
        native.validate(yaml)?.let { diagnostic ->
            throw ConfigValidationException(listOf(ConfigIssue("$", diagnostic.take(2000))))
        }
    }

    private fun validateExtendedProxy(proxy: Map<String, Any?>, path: String, type: String, issue: (String, String) -> Unit) {
        fun text(key: String, required: Boolean = false) {
            val value = proxy[key]
            if ((required && (value !is String || value.isBlank())) || (value != null && value !is String))
                issue("$path.$key", "Expected ${if (required) "a nonempty" else "a"} string")
        }
        fun bool(key: String) { if (proxy[key] != null && proxy[key] !is Boolean) issue("$path.$key", "Expected a Boolean") }
        fun integer(key: String, maximum: ULong, required: Boolean = false, minimum: ULong = 0u) {
            val value = proxy[key]
            if (value == null && !required) return
            val number = if (value is Number && value !is Float && value !is Double) value.toString().toULongOrNull() else null
            if (number == null || number !in minimum..maximum) issue("$path.$key", "Expected an integer in $minimum..$maximum")
        }
        fun strings(key: String) {
            val value = proxy[key]
            if (value != null && (value !is List<*> || value.any { it !is String })) issue("$path.$key", "Expected a list of strings")
        }
        fun alias(first: String, second: String) {
            if (proxy.containsKey(first) && proxy.containsKey(second)) issue("$path.$second", "Conflicting aliases: $first and $second")
        }
        fun address(value: String, key: String, family: Int? = null, requirePrefix: Boolean = false) {
            val parts = value.split('/')
            val literal = parts.first()
            val ipv6 = ':' in literal
            val allowed = if (ipv6) literal.isNotEmpty() && literal.all { it in "0123456789abcdefABCDEF:." }
                else literal.split('.').size == 4 && literal.split('.').all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
            val prefix = parts.getOrNull(1)?.toIntOrNull()
            if (!allowed || parts.size > 2 || (requirePrefix && parts.size != 2) ||
                (parts.size == 2 && (prefix == null || prefix !in 0..(if (ipv6) 128 else 32))) ||
                (family == 4 && ipv6) || (family == 6 && !ipv6) ||
                runCatching { java.net.InetAddress.getByName(literal) }.isFailure)
                issue("$path.$key", "Expected a valid ${family?.let { "IPv$it " } ?: "IP "}${if (requirePrefix) "CIDR" else "literal or CIDR"}")
        }
        text("name", true)
        if (type != "tailscale") {
            text("server", true); integer("port", 65535u, true, 1u)
            text("connect-via"); text("dialer-proxy"); alias("connect-via", "dialer-proxy")
        }
        when (type) {
            "ssh" -> {
                text("username", true)
                listOf("password", "private-key", "private-key-passphrase").forEach { text(it) }
                strings("host-key"); strings("host-key-algorithms")
                val totp = proxy["totp-opt"]
                if (totp != null) {
                    if (totp !is Map<*, *> || totp.size != 1 || totp.keys.single() !in setOf("otp-auth", "common"))
                        issue("$path.totp-opt", "Expected one tagged otp-auth or common TOTP mapping")
                    else if (totp.containsKey("otp-auth")) {
                        if (totp["otp-auth"] !is String) issue("$path.totp-opt.otp-auth", "Expected a string")
                    } else {
                        val common = totp["common"] as? Map<*, *>
                        if (common == null) issue("$path.totp-opt.common", "Expected a TOTP mapping")
                        else {
                            val allowed = keys("secret screw step digits algorithm")
                            common.keys.forEach { if (it !in allowed) issue("$path.totp-opt.common.$it", "Unknown TOTP field") }
                            listOf("secret", "algorithm").forEach { if (common[it] !is String) issue("$path.totp-opt.common.$it", "Expected a string") }
                            listOf("screw", "step", "digits").forEach { key ->
                                val value = common[key]
                                val number = if (value is Number && value !is Float && value !is Double) value.toString().toULongOrNull() else null
                                if (number == null || (key == "screw" && number > 255u)) issue("$path.totp-opt.common.$key", "Expected an unsigned integer")
                            }
                        }
                    }
                }
            }
            "wireguard" -> {
                listOf("private-key", "public-key", "ip").forEach { text(it, true) }
                listOf("pre-shared-key", "preshared-key", "ipv6").forEach { text(it) }
                alias("pre-shared-key", "preshared-key")
                listOf("private-key", "public-key", "pre-shared-key", "preshared-key").forEach { key ->
                    (proxy[key] as? String)?.let { value ->
                        if (runCatching { val decoded = java.util.Base64.getDecoder().decode(value); try { decoded.size == 32 } finally { decoded.fill(0) } }.getOrDefault(false).not())
                            issue("$path.$key", "Expected a Base64-encoded 32-byte WireGuard key")
                    }
                }
                (proxy["ip"] as? String)?.let { address(it, "ip", 4) }
                (proxy["ipv6"] as? String)?.let { address(it, "ipv6", 6) }
                bool("udp"); bool("remote-dns-resolve"); integer("mtu", 65535u, minimum = 1u)
                strings("dns"); strings("allowed-ips")
                (proxy["dns"] as? List<*>)?.forEachIndexed { i, value -> if (value is String) {
                    if ('/' in value) issue("$path.dns[$i]", "Expected an IP literal without a prefix") else address(value, "dns[$i]")
                } }
                (proxy["allowed-ips"] as? List<*>)?.forEachIndexed { i, value -> if (value is String) address(value, "allowed-ips[$i]", requirePrefix = true) }
                val bits = proxy["reserved-bits"]
                if (bits != null && (bits !is List<*> || bits.size != 3 || bits.any { it !is Int || it !in 0..255 }))
                    issue("$path.reserved-bits", "Expected exactly three bytes; fixed WireGuard otherwise truncates or substitutes them")
            }
            "shadowquic" -> {
                listOf("password", "username").forEach { text(it) }
                // Standard falls back to the server if SNI is omitted; Native performs the typed mapping.
                text("server-name"); text("sni")
                strings("alpn"); text("congestion-control"); text("congestion-controller")
                listOf("zero-rtt", "over-stream", "udp-over-stream", "gso", "mtu-discovery", "disable-mtu-discovery").forEach(::bool)
                alias("server-name", "sni"); alias("congestion-control", "congestion-controller")
                alias("over-stream", "udp-over-stream"); alias("mtu-discovery", "disable-mtu-discovery")
                listOf("initial-mtu", "min-mtu").forEach { integer(it, 65535u, minimum = 1200u) }
                integer("keep-alive-interval", UInt.MAX_VALUE.toULong())
            }
            "tailscale" -> {
                listOf("state-dir", "auth-key", "hostname", "control-url", "client-name").forEach { text(it) }
                bool("ephemeral")
            }
        }
    }

    private fun checkResource(value: String, path: String, issues: MutableList<ConfigIssue>, bundleDirectory: File? = null) =
        checkProfileResource(value, path, issues, resourceDirectory, bundleDirectory)

    internal suspend fun validateProxyPayload(source: String) {
        coreSchema?.let { it.validateProxyPayload(source); return }
        val parsed = ConfigYaml.parse(source)
        if (parsed.keys != setOf("proxies") || parsed["proxies"] !is List<*>) {
            throw ConfigValidationException(listOf(ConfigIssue("$.proxies", "Proxy provider must contain only a proxies list")))
        }
        validateManagedSource(source)
    }

    internal fun validateRulePayload(lines: List<String>, path: String) = ruleLineCheck(path).run { lines.forEachIndexed(::line); finish() }

    internal fun ruleLineCheck(path: String): RuleLineCheck = RuleLineCheck(path, coreSchema?.let { it::ruleLineIssue } ?: { line ->
        when {
            line.isBlank() -> "Empty rule payload"
            ANDROID_RULE.containsMatchIn(line) -> "UID, IN-USER and process regex/wildcard rules have no Android matcher in fixed clash-rs"
            else -> null
        }
    })

    companion object {
        fun forMeow(native: NativeConfigValidator, resourceDirectory: File,
            preparedNative: NativePreparedConfigValidator, verifiedFeatures: () -> Set<String>): CompatibilityValidator =
            CompatibilityValidator(native, resourceDirectory, MeowProfileValidator(native, preparedNative, resourceDirectory, verifiedFeatures))
        fun forMeow(native: NativeConfigValidator, resourceDirectory: File,
            preparedNative: NativePreparedConfigValidator, preparedRules: NativePreparedRuleValidator,
            verifiedFeatures: () -> Set<String>): CompatibilityValidator =
            CompatibilityValidator(native, resourceDirectory, MeowProfileValidator(native, preparedNative, resourceDirectory, verifiedFeatures, preparedRules))
        const val CORE_VERSION = "0.10.10"
        private fun keys(value: String): Set<String> = value.split(' ').filter { it.isNotEmpty() }.toSet()
        private val TOP_LEVEL = keys("port http_port socks-port redir-port tproxy-port mixed-port authentication allow-lan bind-address mode log-level dns profile proxies proxy-groups rules hosts mmdb mmdb-download-url asn-mmdb asn-mmdb-download-url geosite geosite-download-url ipv6 external-controller external-controller-ipc external-controller-unix external-ui external-ui-url secret cors-allow-origins interface routing-mark proxy-providers rule-providers experimental tun listeners inbound-providers unified-delay tcp-concurrent find-process-mode")
        private val UNSUPPORTED_TOP = keys("listeners inbound-providers external-ui external-ui-url external-controller-ipc external-controller-unix interface routing-mark")
        private val DNS_FIELDS = keys("enable ipv6 use-hosts nameserver fallback fallback-filter listen enhanced-mode fake-ip-range fake-ip-filter default-nameserver proxy-server-nameserver nameserver-policy edns-client-subnet respect-rules")
        private val FIXED_UNSUPPORTED_PROTOCOLS = keys("ssr http snell hysteria gost-relay dns rematch mieru sudoku masque trusttunnel openvpn zerotier easytier")
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
            "wireguard" to keys("private-key public-key pre-shared-key preshared-key mtu udp ip ipv6 remote-dns-resolve dns allowed-ips reserved-bits"),
            "shadowquic" to keys("password username server-name sni alpn initial-mtu congestion-control congestion-controller zero-rtt over-stream udp-over-stream min-mtu keep-alive-interval gso mtu-discovery disable-mtu-discovery"),
            "tailscale" to keys("state-dir auth-key hostname control-url client-name ephemeral"),
        )
        private val GROUP_FIELDS = mapOf(
            "select" to keys("udp"), "relay" to emptySet(),
            "url-test" to keys("interval lazy tolerance"), "fallback" to keys("interval lazy"),
            "load-balance" to keys("interval lazy strategy"),
            "smart" to keys("udp lazy max-retries site-stickiness bandwidth-weight"),
        )
        private val ANDROID_RULE = Regex("(?:^|[,(])\\s*(?:UID|IN-USER|PROCESS-NAME-REGEX|PROCESS-PATH-REGEX|PROCESS-NAME-WILDCARD|PROCESS-PATH-WILDCARD)\\s*,", RegexOption.IGNORE_CASE)
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
