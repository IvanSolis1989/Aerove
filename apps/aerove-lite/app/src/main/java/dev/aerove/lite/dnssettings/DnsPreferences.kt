package dev.aerove.lite.dnssettings

import java.net.InetAddress
import java.net.URI

sealed interface DnsValue {
    data class Bool(val value: Boolean) : DnsValue
    data class Text(val value: String) : DnsValue
    data class Entries(val value: List<String>) : DnsValue
    data class Policy(val value: Map<String, List<String>>) : DnsValue
}

enum class DnsField(val path: String, val default: DnsValue, val normalProfile: Boolean = false) {
    Enable("enable", DnsValue.Bool(true)),
    Listen("listen", DnsValue.Text(""), true),
    UseHosts("use-hosts", DnsValue.Bool(true)),
    Ipv6("ipv6", DnsValue.Bool(true), true),
    RespectRules("respect-rules", DnsValue.Bool(true)),
    EnhancedMode("enhanced-mode", DnsValue.Text("fake-ip"), true),
    FakeIpRange("fake-ip-range", DnsValue.Text("198.18.0.1/16")),
    FakeIpFilter("fake-ip-filter", DnsValue.Entries(defaultDnsFakeIpFilter), true),
    DefaultNameserver("default-nameserver", DnsValue.Entries(listOf("https://223.5.5.5/dns-query", "https://223.6.6.6/dns-query", "https://8.8.8.8/dns-query", "https://1.1.1.1/dns-query")), true),
    NameserverPolicy("nameserver-policy", DnsValue.Policy(emptyMap())),
    Nameserver("nameserver", DnsValue.Entries(listOf("https://dns.alidns.com/dns-query", "https://doh.pub/dns-query")), true),
    Fallback("fallback", DnsValue.Entries(listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query")), true),
    ProxyServerNameserver("proxy-server-nameserver", DnsValue.Entries(listOf("https://cloudflare-dns.com/dns-query", "https://dns.google/dns-query", "https://dns.alidns.com/dns-query", "https://doh.pub/dns-query")), true),
    FallbackGeoip("fallback-filter.geoip", DnsValue.Bool(true)),
    FallbackGeoipCode("fallback-filter.geoip-code", DnsValue.Text("CN")),
    FallbackIpcidr("fallback-filter.ipcidr", DnsValue.Entries(listOf("240.0.0.0/4", "0.0.0.0/32", "127.0.0.0/8", "10.0.0.0/8", "192.168.0.0/16"))),
    FallbackDomain("fallback-filter.domain", DnsValue.Entries(emptyList()));
}

enum class DnsIssueCode { Type, UnknownField, UnsupportedCore, UnknownReference, InvalidValue, Duplicate, Size, Version }
class DnsPreferencesException(val field: String, val code: DnsIssueCode) : IllegalArgumentException("$field: $code")
internal fun dnsIssue(field: String, code: DnsIssueCode): Nothing = throw DnsPreferencesException(field, code)

/** Values stay when inheritance is restored; overridden contains only explicitly selected fields. */
data class DnsPreferences(
    val values: Map<DnsField, DnsValue> = emptyMap(),
    val overridden: Set<DnsField> = emptySet(),
    val hosts: Map<String, List<String>> = emptyMap(),
    val legacyFallbackServers: List<String>? = null,
) {
    fun value(field: DnsField): DnsValue = values[field] ?: field.default
    fun withValue(field: DnsField, value: DnsValue): DnsPreferences =
        copy(values = values + (field to value), overridden = overridden + field).requireValid()
    fun inherit(field: DnsField): DnsPreferences = copy(overridden = overridden - field)
    fun snapshot(): DnsPreferences = copy(values = values.mapValues { (_, value) -> when (value) {
        is DnsValue.Entries -> value.copy(value = value.value.toList())
        is DnsValue.Policy -> value.copy(value = value.value.mapValues { it.value.toList() })
        else -> value
    } }, overridden = overridden.toSet(), hosts = hosts.mapValues { it.value.toList() }, legacyFallbackServers = legacyFallbackServers?.toList())

    fun requireValid(): DnsPreferences {
        if (values.size > DnsField.entries.size || hosts.size > 4096) dnsIssue("dnsPreferences", DnsIssueCode.Size)
        values.forEach { (field, value) -> validateDnsValue(field, value) }
        overridden.forEach { field -> validateDnsValue(field, value(field)) }
        legacyFallbackServers?.forEachIndexed { index, server -> validateDnsServer(server, "dnsPreferences.legacyFallbackServers[$index]") }
        hosts.forEach { (host, targets) ->
            validateDnsText(host, "hosts.$host")
            if (targets.isEmpty()) dnsIssue("hosts.$host", DnsIssueCode.InvalidValue)
            if (targets.size != 1) dnsIssue("hosts.$host", DnsIssueCode.UnsupportedCore)
            if (!isIpLiteral(targets.single())) dnsIssue("hosts.$host", DnsIssueCode.UnsupportedCore)
        }
        return this
    }
}

internal val unsupportedStandardDnsFields = setOf("listen-routing-mark", "use-system-hosts", "ipv6-timeout", "prefer-h3",
    "cache-algorithm", "cache-max-size", "fake-ip-range6", "fake-ip-filter-mode", "fake-ip-ttl", "fallback-lazy-query",
    "proxy-server-nameserver-policy", "direct-nameserver", "direct-nameserver-follow-policy", "fallback-filter.geosite")

internal fun dnsField(path: String): DnsField = DnsField.entries.find { it.path == path }
    ?: dnsIssue("dns.$path", if (path in unsupportedStandardDnsFields) DnsIssueCode.UnsupportedCore else DnsIssueCode.UnknownField)

internal fun validateDnsText(value: String, path: String) {
    if (value.isBlank() || value.length > 4096 || value.any(Char::isISOControl)) dnsIssue(path, DnsIssueCode.InvalidValue)
}

internal fun isIpLiteral(value: String): Boolean = when {
    ':' in value && value.all { it in "0123456789abcdefABCDEF:." } -> runCatching { InetAddress.getByName(value) }.isSuccess
    else -> value.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 } }
}

internal fun validateDnsServer(value: String, path: String) {
    validateDnsText(value, path)
    if (value.any(Char::isWhitespace)) dnsIssue(path, DnsIssueCode.InvalidValue)
    if (value == "system" || value == "system://") return
    if (!value.contains("://")) {
        if (!isIpLiteral(value)) dnsIssue(path, DnsIssueCode.InvalidValue)
        return
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: dnsIssue(path, DnsIssueCode.InvalidValue)
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    if (scheme !in setOf("udp", "tcp", "tls", "https", "dhcp", "system")) dnsIssue(path, DnsIssueCode.UnsupportedCore)
    if (uri.rawUserInfo != null || uri.rawQuery != null || (!uri.rawPath.isNullOrEmpty() &&
        !(scheme == "https" && uri.rawPath == "/dns-query"))) dnsIssue(path, DnsIssueCode.UnsupportedCore)
    uri.rawFragment?.split('&')?.forEach { option ->
        if ('=' in option && !option.startsWith("proxy=") && !option.startsWith("interface=")) dnsIssue(path, DnsIssueCode.UnsupportedCore)
        if (option == "proxy=" || option == "interface=" || option.isEmpty()) dnsIssue(path, DnsIssueCode.InvalidValue)
    }
    // DHCP/system are retained for the protected Android resolver adapter, never silently dropped.
    if (scheme != "system" && uri.host.isNullOrBlank()) dnsIssue(path, DnsIssueCode.InvalidValue)
    if (uri.port != -1 && uri.port !in 1..65535) dnsIssue(path, DnsIssueCode.InvalidValue)
}

internal fun validateDnsValue(field: DnsField, value: DnsValue) {
    val path = "dns.${field.path}"
    if (field.default::class != value::class) dnsIssue(path, DnsIssueCode.Type)
    when (value) {
        is DnsValue.Bool -> Unit
        is DnsValue.Text -> {
            if (value.value.isNotEmpty()) validateDnsText(value.value, path)
            if (field == DnsField.EnhancedMode && value.value !in setOf("normal", "fake-ip", "redir-host")) dnsIssue(path, DnsIssueCode.InvalidValue)
            if (field == DnsField.EnhancedMode && value.value == "redir-host") dnsIssue(path, DnsIssueCode.UnsupportedCore)
            if (field == DnsField.FakeIpRange) validateCidr(value.value, path)
            if (field == DnsField.FallbackGeoipCode && (value.value.length != 2 || !value.value.all(Char::isLetter))) dnsIssue(path, DnsIssueCode.InvalidValue)
            if (field == DnsField.Listen && value.value.isNotEmpty()) {
                val port = value.value.substringAfterLast(':').toIntOrNull()
                val host = value.value.substringBeforeLast(':', "").removeSurrounding("[", "]")
                if (port !in 1..65535 || (host.isNotEmpty() && !isIpLiteral(host))) dnsIssue(path, DnsIssueCode.InvalidValue)
            }
        }
        is DnsValue.Entries -> {
            if (value.value.size > 4096) dnsIssue(path, DnsIssueCode.Size)
            value.value.forEachIndexed { index, item ->
                val itemPath = "$path[$index]"
                when (field) {
                    DnsField.Nameserver, DnsField.DefaultNameserver, DnsField.Fallback, DnsField.ProxyServerNameserver -> validateDnsServer(item, itemPath)
                    DnsField.FallbackIpcidr -> validateCidr(item, itemPath)
                    else -> validateDnsText(item, itemPath)
                }
                if (field == DnsField.DefaultNameserver && !isIpLiteral(if ("://" in item)
                    runCatching { URI(item).host?.removeSurrounding("[", "]") }.getOrNull() ?: "" else item))
                    dnsIssue(itemPath, DnsIssueCode.InvalidValue)
            }
        }
        is DnsValue.Policy -> value.value.forEach { (domain, servers) ->
            validateDnsText(domain, "$path.$domain")
            if (':' in domain) dnsIssue("$path.$domain", DnsIssueCode.UnsupportedCore)
            if (servers.isEmpty()) dnsIssue("$path.$domain", DnsIssueCode.InvalidValue)
            if (servers.size != 1) dnsIssue("$path.$domain", DnsIssueCode.UnsupportedCore)
            validateDnsServer(servers.single(), "$path.$domain")
        }
    }
}

internal fun validateCidr(value: String, path: String) {
    val parts = value.split('/')
    if (parts.size != 2 || !isIpLiteral(parts[0]) || parts[1].toIntOrNull() !in 0..(if (':' in parts[0]) 128 else 32))
        dnsIssue(path, DnsIssueCode.InvalidValue)
}

fun legacyDnsPreferences(overrideDns: Boolean, dnsServers: List<String>, ipv6: Boolean): DnsPreferences = DnsPreferences(
    values = mapOf(DnsField.Nameserver to DnsValue.Entries(dnsServers.toList()), DnsField.Ipv6 to DnsValue.Bool(ipv6)),
    overridden = setOf(DnsField.Ipv6) + if (overrideDns) setOf(DnsField.Nameserver) else emptySet(),
    legacyFallbackServers = dnsServers.toList(),
).requireValid()
