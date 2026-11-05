package dev.aerove.client.platform

import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Shared client-only channels; VPN and core ownership stay in each product. */
class AeroveClientPlatformPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {
    private val fonts = SystemFontPlugin()
    private val battery = BatteryPlugin()
    private var performanceChannel: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        fonts.onAttachedToEngine(binding)
        battery.onAttachedToEngine(binding)
        performanceChannel = MethodChannel(binding.binaryMessenger, "dev.aerove.client/performance").also {
            it.setMethodCallHandler(this)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        performanceChannel?.setMethodCallHandler(null)
        performanceChannel = null
        battery.onDetachedFromEngine(binding)
        fonts.onDetachedFromEngine(binding)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getMediaPerformanceClass" -> result.success(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0
            )
            else -> result.notImplemented()
        }
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) =
        battery.onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() =
        battery.onDetachedFromActivityForConfigChanges()

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        battery.onReattachedToActivityForConfigChanges(binding)

    override fun onDetachedFromActivity() = battery.onDetachedFromActivity()
}
