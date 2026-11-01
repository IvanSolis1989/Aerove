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
        if (options.has("rawRouteAddress")) {
            fun rows(key: String) = options.getJSONArray(key).let { values -> (0 until values.length()).map { values.getString(it) } }
            require(rows("rawRouteAddress") == settings.network.routeAddress && rows("rawRouteExcludeAddress") == settings.network.routeExcludeAddress &&
                options.getBoolean("bypassPrivateRoute") == settings.network.bypassPrivateRoute && options.getBoolean("routeAuto") == settings.network.routeAuto &&
                options.getBoolean("routeDefaultsPerFamily") == settings.network.routeDefaultsPerFamily) { "runtime_projection_conflict: original route policy" }
        }
        val builder = meowFlutterBuilderSettings(settings, handoff.vpnOptionsJson)
        val requested = settings.copy(ipv6 = builder.ipv6)
        dev.aerove.lite.data.MeowTunReconciliation.requireActual(handoff.compiledYaml, requested,
            dev.aerove.lite.data.TunRuntimeOptions(builder.network.mtu, builder.network.dnsHijacking, builder.ipv6,
                builder.network.tunInet4Address, builder.network.tunInet6Address, builder.network.tunDnsHijack, builder.network))
        if (options.has("mtu")) require((options.getInt("mtu").takeIf { it > 0 } ?: 9000) == builder.network.mtu) { "runtime_projection_conflict: vpnOptions.mtu" }
        if (options.has("rawRouteAddress") && options.has("routeAuto")) require((0 until options.getJSONArray("routeAddress").length()).map { options.getJSONArray("routeAddress").getString(it) } == dev.aerove.lite.networksettings.planVpnRoutes(builder.network, builder.ipv6, dev.aerove.lite.data.MeowTunReconciliation.reservedRanges(handoff.compiledYaml, builder.ipv6))) { "runtime_projection_conflict: vpnOptions.routeAddress" }
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
