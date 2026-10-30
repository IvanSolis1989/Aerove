package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import org.json.JSONObject

internal fun meowFlutterBuilderSettings(core: LiteSettings, vpnOptionsJson: String): LiteSettings {
    val options = JSONObject(vpnOptionsJson)
    require(options.get("enable") is Boolean && options.getBoolean("enable") == core.network.vpnEnabled) { "vpnOptions.enable: Runtime mode differs" }
    val ipv6 = options.get("ipv6") as? Boolean ?: error("vpnOptions.ipv6: Expected a boolean")
    return core.copy(ipv6 = ipv6)
}
