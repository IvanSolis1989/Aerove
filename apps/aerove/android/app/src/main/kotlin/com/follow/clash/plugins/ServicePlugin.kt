package com.follow.clash.plugins

import com.follow.clash.ServiceController
import com.follow.clash.ServiceState
import com.follow.clash.common.Components
import com.follow.clash.core.Core
import com.follow.clash.models.SharedState
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.StandardMethodCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ServicePlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var messenger: BinaryMessenger
    private lateinit var channel: MethodChannel
    private lateinit var coreChannel: MethodChannel
    private lateinit var scope: CoroutineScope
    private lateinit var coreLoaded: Deferred<Result<Unit>>
    private val gson = Gson()
    private val eventListener: (ByteArray) -> Unit = ::sendEvent
    private var commandRevision = 0

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coreLoaded = scope.async(Dispatchers.IO) { runCatching { Core.load() } }
        messenger = binding.binaryMessenger
        channel = MethodChannel(messenger, SERVICE_CHANNEL)
        channel.setMethodCallHandler(this)
        // Serial: watchRoute keeps the latest intent by the order calls reach the Core.
        coreChannel = MethodChannel(
            messenger,
            CORE_CHANNEL,
            StandardMethodCodec.INSTANCE,
            messenger.makeBackgroundTaskQueue(),
        )
        coreChannel.setMethodCallHandler(::onCoreMethodCall)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        coreChannel.setMethodCallHandler(null)
        scope.cancel()
        ServiceController.clearEventListener(eventListener)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> initialize(result)
            "shutdown" -> shutdown(result)
            "getRunTime" -> getRunTime(result)
            "syncState" -> syncState(call, result)
            "setUiTelemetryActive" -> setUiTelemetryActive(call, result)
            "start" -> start(call, result)
            "stop" -> stop(call, result)
            else -> result.notImplemented()
        }
    }

    private fun onCoreMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "invokeMethod" -> invokeMethod(call, result)
            else -> result.notImplemented()
        }
    }

    private fun initialize(result: MethodChannel.Result) {
        scope.launch(Dispatchers.Main.immediate) {
            coreLoaded.await()
                .mapCatching { ServiceController.setEventListener(eventListener).getOrThrow() }
                .onSuccess { result.success("") }
                .onFailure { error -> result.success(error.message.orEmpty()) }
        }
    }

    private fun shutdown(result: MethodChannel.Result) {
        ServiceController.clearEventListener(eventListener)
        result.success(true)
    }

    private fun setUiTelemetryActive(call: MethodCall, result: MethodChannel.Result) {
        val active = call.arguments as? Boolean
        if (active == null) {
            result.error("INVALID_ARGUMENT", "UI telemetry state must be a boolean", null)
            return
        }
        val data = gson.toJson(mapOf("method" to "setUiTelemetryActive", "arguments" to active))
        ServiceController.invokeMethod(data) { response ->
            val decoded = runCatching {
                requireNotNull(gson.fromJson(response?.decodeToString(), JsonObject::class.java))
            }
            val error = decoded.getOrNull()?.get("error")?.takeUnless { it.isJsonNull }
            if (decoded.isFailure || error != null) {
                result.error("CORE_ERROR", error?.toString() ?: "Invalid telemetry response", null)
            } else {
                result.success(null)
            }
        }.onFailure { error -> result.error("CORE_ERROR", error.message, null) }
    }

    private fun invokeMethod(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? String
        if (data == null) {
            result.error("INVALID_ARGUMENT", "Method call payload must be a string", null)
            return
        }
        ServiceController.invokeMethod(data) { response ->
            result.success(response)
        }.onFailure { error ->
            result.error("CORE_ERROR", error.message, null)
        }
    }

    private fun getRunTime(result: MethodChannel.Result) {
        scope.launch {
            result.success(ServiceState.awaitRunTime()?.let { gson.toJson(it) })
        }
    }

    private fun syncState(call: MethodCall, result: MethodChannel.Result) {
        val data = call.arguments as? String
        val state = runCatching {
            gson.fromJson(data, SharedState::class.java)
        }.getOrNull()
        if (state == null) {
            result.success("Invalid shared state")
            return
        }
        scope.launch {
            ServiceState.syncSharedState(state)
            result.success("")
        }
    }

    private fun start(call: MethodCall, result: MethodChannel.Result) {
        commandRevision = call.arguments as? Int ?: commandRevision
        ServiceState.requestStart()
        result.success(true)
    }

    private fun stop(call: MethodCall, result: MethodChannel.Result) {
        commandRevision = call.arguments as? Int ?: commandRevision
        ServiceState.requestStop()
        result.success(true)
    }

    // Read on the platform thread, where start and stop arrive: revision and intent are one instant.
    fun notifyStopped() {
        scope.launch(Dispatchers.Main) {
            if (!ServiceState.isRunRequested) {
                channel.invokeMethod("stopped", commandRevision)
            }
        }
    }

    private fun sendEvent(value: ByteArray) {
        val message = StandardMethodCodec.INSTANCE.encodeMethodCall(MethodCall("event", value))
        scope.launch(Dispatchers.Main) {
            messenger.send(SERVICE_CHANNEL, message)
        }
    }

    private companion object {
        val SERVICE_CHANNEL = "${Components.PACKAGE_NAME}/service"
        val CORE_CHANNEL = "${Components.PACKAGE_NAME}/core"
    }
}
