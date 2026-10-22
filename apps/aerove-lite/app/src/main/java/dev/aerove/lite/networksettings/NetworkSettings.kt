package dev.aerove.lite.networksettings

import java.security.SecureRandom

val defaultProxyBypassDomains: List<String> = listOf(
    "*zhihu.com", "*zhimg.com", "*jd.com", "100ime-iat-api.xfyun.cn", "*360buyimg.com",
    "localhost", "*.local", "127.*", "10.*", "172.16.*", "172.17.*", "172.18.*", "172.19.*",
    "172.2*", "172.30.*", "172.31.*", "192.168.*",
)

data class UserProxyPorts(val mixed: Int = 7890, val http: Int = 0, val socks: Int = 0)

data class ProxyAuthentication(val enabled: Boolean = false, val username: String = "", val password: String = "") {
    val credentials: List<String> get() = if (enabled && username.isNotEmpty()) listOf("$username:$password") else emptyList()
    override fun toString(): String = "ProxyAuthentication(enabled=$enabled, username=<redacted>, password=<redacted>)"
}

data class NetworkSettings(
    val systemProxy: Boolean = true,
    val allowBypass: Boolean = true,
    val bypassDomains: List<String> = defaultProxyBypassDomains.toList(),
    val ports: UserProxyPorts = UserProxyPorts(),
    val allowLan: Boolean = false,
    val authentication: ProxyAuthentication = ProxyAuthentication(),
) {
    /** Matches Standard's enable action; existing credentials are preserved across off/on. */
    fun withAuthenticationEnabled(enabled: Boolean, random: SecureRandom = SecureRandom()): NetworkSettings {
        val value = if (enabled && authentication.username.isEmpty()) {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            fun secret(length: Int): String = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            ProxyAuthentication(true, secret(8), secret(16))
        } else authentication.copy(enabled = enabled)
        return copy(authentication = value)
    }

    fun snapshot(): NetworkSettings = copy(bypassDomains = bypassDomains.toList())
}

enum class NetworkIssueCode { Type, UnknownField, Version, PortRange, PortConflict, EmptyAccount, InvalidAccount,
    CredentialControl, CredentialLength, SocksCredentialLength, EmptyDomain, DomainLength, DomainControl, Size }

data class NetworkIssue(val field: String, val code: NetworkIssueCode)

class NetworkSettingsException(val issues: List<NetworkIssue>) : IllegalArgumentException(
    issues.joinToString("; ") { "${it.field}: ${it.code}" },
)

fun validateNetworkSettings(settings: NetworkSettings): List<NetworkIssue> = buildList {
    val ports = linkedMapOf("mixed" to settings.ports.mixed, "http" to settings.ports.http, "socks" to settings.ports.socks)
    ports.forEach { (field, port) ->
        if (port !in 1024..49151 && !(field != "mixed" && port == 0))
            add(NetworkIssue("network.ports.$field", NetworkIssueCode.PortRange))
        else if (port != 0 && ports.any { (other, value) -> other != field && value == port })
            add(NetworkIssue("network.ports.$field", NetworkIssueCode.PortConflict))
    }
    val auth = settings.authentication
    if (auth.enabled && auth.username.isEmpty()) add(NetworkIssue("network.authentication.username", NetworkIssueCode.EmptyAccount))
    if (':' in auth.username) add(NetworkIssue("network.authentication.username", NetworkIssueCode.InvalidAccount))
    listOf("username" to auth.username, "password" to auth.password).forEach { (field, text) ->
        if (text.any(Char::isISOControl))
            add(NetworkIssue("network.authentication.$field", NetworkIssueCode.CredentialControl))
        val limit = if (field == "username") 128 else 512
        if (text.length > limit) add(NetworkIssue("network.authentication.$field", NetworkIssueCode.CredentialLength))
        // Both the mixed and standalone SOCKS entry use RFC1929 one-byte UTF-8 lengths.
        if (auth.enabled && text.toByteArray(Charsets.UTF_8).size > 255)
            add(NetworkIssue("network.authentication.$field", NetworkIssueCode.SocksCredentialLength))
    }
    settings.bypassDomains.forEachIndexed { index, domain ->
        val field = "network.bypassDomains[$index]"
        if (domain.isBlank()) add(NetworkIssue(field, NetworkIssueCode.EmptyDomain))
        if (domain.length > 512) add(NetworkIssue(field, NetworkIssueCode.DomainLength))
        if (domain.any { it == '\r' || it == '\n' || it == '\u0000' }) add(NetworkIssue(field, NetworkIssueCode.DomainControl))
    }
}

fun NetworkSettings.requireValid(): NetworkSettings {
    val issues = validateNetworkSettings(this)
    if (issues.isNotEmpty()) throw NetworkSettingsException(issues)
    return this
}
