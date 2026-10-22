package dev.aerove.lite.data

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ProxyMode
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LiteSettingsStore(privateDirectory: File) {
    private val storage = PrivateStorage(File(privateDirectory, "settings"))

    suspend fun read(): LiteSettings = storage.transaction { load() }

    suspend fun save(settings: LiteSettings, vpnAuthorized: Boolean = false) = storage.transaction {
        validateSettings(settings)
        require(!settings.autoStart || vpnAuthorized) { "Boot auto-start requires an existing VPN authorization" }
        val json = JSONObject().put("schema", 1)
            .put("material", settings.material.name).put("aurora", settings.aurora)
            .put("colorMode", settings.colorMode.name).put("themeColor", settings.themeColor)
            .put("mode", settings.mode.name).put("ipv6", settings.ipv6)
            .put("dnsServers", JSONArray(settings.dnsServers))
            .put("appFilterMode", settings.appFilterMode.name)
            .put("packages", JSONArray(settings.packages.sorted())).put("autoStart", settings.autoStart)
        val encoded = json.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Settings exceed storage limit" }
        storage.write("settings.json", encoded)
    }

    private fun load(): LiteSettings {
        if (!storage.file("settings.json").exists()) return LiteSettings()
        val json = JSONObject(storage.read("settings.json", 128 * 1024))
        require(json.getInt("schema") == 1) { "Unsupported settings version" }
        fun strings(key: String): List<String> {
            val values = json.getJSONArray(key)
            return (0 until values.length()).map { values.getString(it) }
        }
        return LiteSettings(
            material = MaterialTier.valueOf(json.getString("material")),
            aurora = json.getBoolean("aurora"),
            colorMode = ColorMode.valueOf(json.getString("colorMode")),
            themeColor = json.getLong("themeColor"),
            mode = ProxyMode.valueOf(json.getString("mode")),
            ipv6 = json.getBoolean("ipv6"),
            dnsServers = strings("dnsServers"),
            appFilterMode = AppFilterMode.valueOf(json.getString("appFilterMode")),
            packages = strings("packages").toSet(),
            autoStart = json.getBoolean("autoStart"),
        ).also(::validateSettings)
    }
}

internal fun validateSettings(settings: LiteSettings) {
    require(settings.themeColor in 0..0xffffffffL) { "Invalid ARGB theme color" }
    require(settings.dnsServers.isNotEmpty() && settings.dnsServers.size <= 32) { "Set at least one DNS server" }
    settings.dnsServers.forEach(::validateDnsServer)
    require(settings.packages.size <= 3000 && settings.packages.all {
        it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*"))
    }) { "Invalid application package" }
    require(settings.appFilterMode != AppFilterMode.Include || settings.packages.isNotEmpty()) {
        "An include-only VPN needs at least one application"
    }
}

internal fun validateDnsServer(value: String) {
    require(value.isNotBlank() && value.length <= 2048 && value.none { it.isWhitespace() || it.isISOControl() }) { "Invalid DNS server" }
    require(value != "system" && !value.startsWith("dhcp://")) { "System/DHCP DNS is not supported by this core" }
    if (value.contains("://")) {
        val uri = java.net.URI(value)
        require(uri.scheme in setOf("udp", "tcp", "tls", "https") && uri.host != null && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port in 1..65535)) {
            "Unsupported DNS server transport"
        }
    } else {
        require(value.matches(Regex("[0-9a-fA-F:.\\[\\]]+"))) { "DNS server must be an IP address or a supported URI" }
    }
}
