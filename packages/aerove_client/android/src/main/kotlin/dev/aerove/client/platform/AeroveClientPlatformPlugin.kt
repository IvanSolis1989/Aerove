package dev.aerove.client.platform

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding

/** Shared client-only channels; VPN and core ownership stay in each product. */
class AeroveClientPlatformPlugin : FlutterPlugin, ActivityAware {
    private val fonts = SystemFontPlugin()
    private val battery = BatteryPlugin()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        fonts.onAttachedToEngine(binding)
        battery.onAttachedToEngine(binding)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        battery.onDetachedFromEngine(binding)
        fonts.onDetachedFromEngine(binding)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) =
        battery.onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() =
        battery.onDetachedFromActivityForConfigChanges()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        battery.onReattachedToActivityForConfigChanges(binding)

    override fun onDetachedFromActivity() = battery.onDetachedFromActivity()
}
