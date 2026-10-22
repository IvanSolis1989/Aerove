package dev.aerove.lite.data

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.ColorMode
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.normalizeAuroraColors
import dev.aerove.lite.normalizeDashboardWidgetValues
import dev.aerove.lite.normalizeThemeColors
import dev.aerove.lite.normalized
import dev.aerove.lite.networksettings.NetworkSettingsJson
import dev.aerove.lite.networksettings.requireValid
import dev.aerove.lite.rotateAuroraForDay as rotateAuroraSettingsForDay
import dev.aerove.lite.shuffleAuroraWallpaper as shuffleAuroraSettings
import java.io.File
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class LiteSettingsStore(privateDirectory: File) {
    private val storage = PrivateStorage(File(privateDirectory, "settings"))
    private val savedSettings = MutableStateFlow<LiteSettings?>(null)
    val changes = savedSettings.asStateFlow()

    suspend fun read(): LiteSettings = storage.transaction { load().also { savedSettings.value = it } }

    suspend fun save(settings: LiteSettings, vpnAuthorized: Boolean = false): LiteSettings = storage.transaction {
        val normalized = settings.normalized()
        validateSettings(normalized)
        require(!normalized.autoStart || vpnAuthorized) { "Boot auto-start requires an existing VPN authorization" }
        persist(normalized)
    }

    suspend fun rotateAuroraForDay(
        now: ZonedDateTime = ZonedDateTime.now(),
        random: Random = Random.Default,
    ): LiteSettings = updateAurora { rotateAuroraSettingsForDay(it, now, random) }

    suspend fun shuffleAuroraWallpaper(
        now: ZonedDateTime = ZonedDateTime.now(),
        random: Random = Random.Default,
    ): LiteSettings = updateAurora { shuffleAuroraSettings(it, now, random) }

    private suspend fun updateAurora(update: (LiteSettings) -> LiteSettings): LiteSettings = storage.transaction {
        val current = load()
        val changed = update(current)
        if (changed === current) current else persist(current.copy(
            auroraSeed = changed.auroraSeed,
            auroraRotationDay = changed.auroraRotationDay,
        ))
    }

    private fun persist(normalized: LiteSettings): LiteSettings {
        val json = JSONObject().put("schema", 1)
            .put("material", normalized.material.name).put("aurora", normalized.aurora)
            .put("colorMode", normalized.colorMode.name).put("themeColor", normalized.themeColor)
            .put("themeColors", JSONArray(normalized.themeColors)).put("followSystemColor", normalized.followSystemColor)
            .put("editorFontSize", normalized.editorFontSize)
            .put("editorLineWrap", normalized.editorLineWrap)
            .put("serviceOrder", JSONArray(normalized.serviceOrder))
            .put("disabledServices", JSONArray(normalized.disabledServices.sorted()))
            .put("autoCheckUpdate", normalized.autoCheckUpdate)
            .put("network", NetworkSettingsJson.encode(normalized.network))
            .put("fontFamily", normalized.fontFamily ?: JSONObject.NULL)
            .put("textScaleEnabled", normalized.textScaleEnabled)
            .put("textScale", normalized.textScale.toDouble())
            .put("pureBlack", normalized.pureBlack)
            .put("locale", normalized.locale ?: JSONObject.NULL)
            .put("mode", normalized.mode.name).put("ipv6", normalized.ipv6)
            .put("overrideDns", normalized.overrideDns).put("dnsServers", JSONArray(normalized.dnsServers))
            .put("appFilterMode", normalized.appFilterMode.name)
            .put("packages", JSONArray(normalized.packages.sorted())).put("autoStart", normalized.autoStart)
            .put("openLogs", normalized.openLogs).put("colorStyle", normalized.colorStyle.wireName)
            .put("auroraColors", JSONArray(normalized.auroraColors)).put("auroraSeed", normalized.auroraSeed)
            .put("auroraRotationDay", normalized.auroraRotationDay)
            .put("dashboardWidgets", JSONArray(normalized.dashboardWidgets.map { it.wireName }))
        val encoded = json.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= 128 * 1024) { "Settings exceed storage limit" }
        storage.write("settings.json", encoded)
        savedSettings.value = normalized
        return normalized
    }

    private fun load(): LiteSettings {
        val defaults = LiteSettings()
        if (!storage.file("settings.json").exists()) return defaults
        val json = JSONObject(storage.read("settings.json", 128 * 1024))
        require(json.getInt("schema") == 1) { "Unsupported settings version" }
        fun strings(key: String): List<String> {
            val values = json.getJSONArray(key)
            return (0 until values.length()).map { values.getString(it) }
        }
        fun values(key: String): List<*>? = (json.opt(key) as? JSONArray)?.let { array ->
            (0 until array.length()).map { array.opt(it) }
        }
        val themeColor = json.opt("themeColor")
        require((themeColor is Int || themeColor is Long) && (themeColor as Number).toLong() in 0..0xFFFFFFFFL) {
            "Invalid ARGB theme color"
        }
        val selectedColor = themeColor.toLong()
        val themeColors = normalizeThemeColors(values("themeColors")).let { colors ->
            if (!json.has("themeColors") && selectedColor !in colors) colors + selectedColor else colors
        }
        return LiteSettings(
            material = MaterialTier.valueOf(json.getString("material")),
            aurora = json.getBoolean("aurora"),
            colorMode = ColorMode.valueOf(json.getString("colorMode")),
            themeColor = selectedColor,
            mode = ProxyMode.valueOf(json.getString("mode")),
            ipv6 = json.getBoolean("ipv6"),
            overrideDns = json.optBoolean("overrideDns", false),
            dnsServers = strings("dnsServers"),
            appFilterMode = AppFilterMode.valueOf(json.getString("appFilterMode")),
            packages = strings("packages").toSet(),
            autoStart = json.getBoolean("autoStart"),
            openLogs = json.optBoolean("openLogs", false),
            colorStyle = json.readColorStyle(defaults.colorStyle),
            auroraColors = normalizeAuroraColors(values("auroraColors")),
            auroraSeed = (json.opt("auroraSeed") as? Number)?.toLong() ?: defaults.auroraSeed,
            auroraRotationDay = (json.opt("auroraRotationDay") as? Number)?.toLong() ?: defaults.auroraRotationDay,
            dashboardWidgets = normalizeDashboardWidgetValues(values("dashboardWidgets")),
            themeColors = themeColors,
            followSystemColor = json.opt("followSystemColor") as? Boolean ?: defaults.followSystemColor,
            editorFontSize = when (json.opt("editorFontSize")) {
                18, 18L, "large" -> 18
                20, 20L, "extraLarge" -> 20
                else -> defaults.editorFontSize
            },
            editorLineWrap = json.opt("editorLineWrap") as? Boolean ?: defaults.editorLineWrap,
            serviceOrder = values("serviceOrder")?.filterIsInstance<String>() ?: defaults.serviceOrder,
            disabledServices = values("disabledServices")?.filterIsInstance<String>()?.toSet() ?: defaults.disabledServices,
            autoCheckUpdate = json.opt("autoCheckUpdate") as? Boolean ?: defaults.autoCheckUpdate,
            network = NetworkSettingsJson.decode(if (json.has("network")) json.get("network") else null),
            fontFamily = if (!json.has("fontFamily") || json.isNull("fontFamily")) null else {
                require(json.get("fontFamily") is String) { "Invalid font family" }
                json.getString("fontFamily")
            },
            textScaleEnabled = json.readOptionalBoolean("textScaleEnabled", defaults.textScaleEnabled),
            textScale = if (json.has("textScale")) {
                val scale = json.get("textScale")
                require(scale is Number && scale.toFloat().isFinite()) { "Invalid text scale" }
                scale.toFloat()
            } else defaults.textScale,
            pureBlack = json.readOptionalBoolean("pureBlack", defaults.pureBlack),
            locale = if (!json.has("locale") || json.isNull("locale")) null else {
                require(json.get("locale") is String) { "Invalid application locale" }
                json.getString("locale")
            },
        ).normalized().also(::validateSettings)
    }

}

private fun JSONObject.readOptionalBoolean(key: String, default: Boolean): Boolean {
    if (!has(key)) return default
    val value = get(key)
    require(value is Boolean) { "Invalid $key setting" }
    return value
}

private fun JSONObject.readColorStyle(default: ColorStyle): ColorStyle {
    val value = opt("colorStyle")
    if (has("colorStyle") && isNull("colorStyle")) return default
    ColorStyle.entries.firstOrNull { it.wireName == value }?.let { return it }
    return when (opt("schemeVariant")) {
        "monochrome" -> ColorStyle.Monochrome
        "tonalSpot", "neutral" -> ColorStyle.Soft
        else -> default
    }
}

internal fun validateSettings(settings: LiteSettings) {
    settings.network.requireValid()
    require(settings.fontFamily == null || (settings.fontFamily.length <= 256 && settings.fontFamily.none { it.isISOControl() })) {
        "Invalid font family"
    }
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
