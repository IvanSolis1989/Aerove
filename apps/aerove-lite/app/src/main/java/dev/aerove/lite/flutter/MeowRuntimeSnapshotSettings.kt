package dev.aerove.lite.flutter

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.FindProcessMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson
import dev.aerove.lite.controllerprefs.decodeExternalController
import dev.aerove.lite.controllerprefs.encodeExternalController
import dev.aerove.lite.data.validateSettings
import dev.aerove.lite.dnssettings.DnsPreferencesJson
import dev.aerove.lite.networksettings.NetworkSettingsJson
import dev.aerove.lite.requests.RequestPreferencesJson
import dev.aerove.lite.resources.GeoResourcePreferencesJson
import org.json.JSONArray
import org.json.JSONObject

/** Runtime projection only: this codec never reads or writes LiteSettingsStore. */
object MeowRuntimeSnapshotSettings {
    fun encode(settings: LiteSettings): JSONObject = JSONObject()
        .put("network", NetworkSettingsJson.encode(settings.network)).put("mode", settings.mode.name)
        .put("ipv6", settings.ipv6).put("appFilterMode", settings.appFilterMode.name).put("packages", JSONArray(settings.packages.toList()))
        .put("requests", RequestPreferencesJson.encode(settings.requests)).put("backgroundPolicy", BackgroundPolicyJson.encode(settings.backgroundPolicy))
        .put("unifiedDelay", settings.unifiedDelay).put("tcpConcurrent", settings.tcpConcurrent).put("findProcessMode", settings.findProcessMode.wireName)
        .put("externalController", encodeExternalController(settings.externalController)).put("dnsPreferences", DnsPreferencesJson.encode(settings.dnsPreferences))
        .put("geoResources", GeoResourcePreferencesJson.encode(settings.geoResources)).put("autoStart", settings.autoStart)

    fun validate(handoff: RuntimeHandoff) {
        val settings = decode(handoff.runtimeSettingsJson)
        val options = JSONObject(handoff.vpnOptionsJson)
        val notification = JSONObject(handoff.notificationJson)
        require(options.getBoolean("enable") == settings.network.vpnEnabled) { "runtime_projection_conflict: vpnOptions.enable" }
        require(options.get("ipv6") is Boolean) { "runtime_setting_invalid: vpnOptions.ipv6" }
        if (settings.network.vpnEnabled) {
            require(options.getBoolean("dnsHijacking") == settings.network.dnsHijacking &&
                options.getBoolean("allowBypass") == settings.network.allowBypass &&
                options.getBoolean("systemProxy") == settings.network.systemProxy) { "runtime_projection_conflict: vpnOptions" }
        }
        require(notification.getBoolean("onlyStatisticsProxy") == settings.backgroundPolicy.onlyStatisticsProxy &&
            notification.getBoolean("showStopAction") == settings.backgroundPolicy.showNotificationStopAction) { "runtime_projection_conflict: notification" }
    }

    fun decode(source: String): LiteSettings {
        val row = JSONObject(source)
        fun flag(key: String): Boolean = (row.get(key) as? Boolean) ?: error("runtime_setting_invalid: $key")
        val packages = row.getJSONArray("packages")
        return LiteSettings(
            network = NetworkSettingsJson.decode(row.getJSONObject("network")),
            mode = ProxyMode.valueOf(row.getString("mode")), ipv6 = flag("ipv6"),
            appFilterMode = AppFilterMode.valueOf(row.getString("appFilterMode")),
            packages = List(packages.length()) { packages.getString(it) }.toSet(),
            requests = RequestPreferencesJson.decode(row.getJSONObject("requests")),
            backgroundPolicy = BackgroundPolicyJson.decode(row.getJSONObject("backgroundPolicy")),
            unifiedDelay = flag("unifiedDelay"), tcpConcurrent = flag("tcpConcurrent"),
            findProcessMode = FindProcessMode.entries.single { it.wireName == row.getString("findProcessMode") },
            externalController = decodeExternalController(row.getJSONObject("externalController")),
            dnsPreferences = DnsPreferencesJson.decode(row.getJSONObject("dnsPreferences")),
            geoResources = GeoResourcePreferencesJson.decode(row.getJSONObject("geoResources")),
            autoStart = flag("autoStart"),
        ).also(::validateSettings)
    }
}
