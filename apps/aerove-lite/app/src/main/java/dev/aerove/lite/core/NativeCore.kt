package dev.aerove.lite.core

import org.json.JSONObject

fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

object NativeCore {
    enum class State { Starting, Running, Failed, Exited }
    enum class RuntimeMode(val wireValue: String) { Tun("tun"), Proxy("proxy") }

    data class Status(
        val state: State,
        val error: String?,
        val apiAddress: String?,
        val runtimeMode: RuntimeMode? = null,
        val tunReady: Boolean = false,
        val userListenersReady: Boolean = false,
        val listening: Boolean = false,
    )

    init {
        System.loadLibrary("aerove_core")
    }

    // The caller owns tunFd throughout; native duplicates it and owns only its private copy.
    @JvmStatic
    external fun start(
        configPath: String,
        workingDir: String,
        tunFd: Int,
        protector: SocketProtector,
    ): Long

    @JvmStatic
    external fun startRuntime(
        configPath: String,
        workingDir: String,
        runtimeMode: String,
        tunFd: Int,
        protector: SocketProtector?,
    ): Long

    @JvmStatic
    fun status(handle: Long): Status {
        val value = JSONObject(snapshot(handle))
        return Status(
            state = State.valueOf(value.getString("state")),
            error = value.optString("error").takeUnless { value.isNull("error") },
            apiAddress = value.optString("apiAddress").takeUnless { value.isNull("apiAddress") },
            runtimeMode = if (value.isNull("runtimeMode")) null else when (val mode = value.getString("runtimeMode")) {
                "tun" -> RuntimeMode.Tun
                "proxy" -> RuntimeMode.Proxy
                else -> error("Unknown native runtime mode: $mode")
            },
            tunReady = value.getBoolean("tunReady"),
            userListenersReady = value.getBoolean("userListenersReady"),
            listening = value.getBoolean("listening"),
        )
    }

    data class ListenerStatus(
        val listening: Boolean,
        val tunReady: Boolean,
        val userListenersReady: Boolean,
        val activePorts: List<Int>,
        val coreEpoch: String,
        val onlyStatisticsProxy: Boolean,
    )

    private fun listenerStatus(value: String): ListenerStatus {
        val data = JSONObject(value)
        val epoch = data.getString("coreEpoch")
        require(epoch.toULongOrNull()?.toString() == epoch) { "Invalid native core epoch" }
        val ports = data.getJSONArray("activePorts")
        return ListenerStatus(data.getBoolean("listening"), data.getBoolean("tunReady"),
            data.getBoolean("userListenersReady"), List(ports.length()) { ports.getInt(it) },
            epoch, data.getBoolean("onlyStatisticsProxy"))
    }

    @JvmStatic fun park(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "park", -1, false))
    @JvmStatic fun resumeProxy(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "resumeProxy", -1, false))
    @JvmStatic fun attachTun(handle: Long, tunFd: Int): ListenerStatus = listenerStatus(listenerCommand(handle, "attachTun", tunFd, false))
    @JvmStatic fun listenerStatus(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "read", -1, false))
    @JvmStatic fun setOnlyStatisticsProxy(handle: Long, enabled: Boolean) {
        listenerCommand(handle, "statistics", -1, enabled)
    }

    @JvmStatic external fun updateNetworkDns(handle: Long, snapshotJson: String)

    @JvmStatic external fun configureHttp(handle: Long, globalUserAgent: String, checkCertificate: Boolean)

    @JvmStatic private external fun listenerCommand(handle: Long, action: String, tunFd: Int, onlyStatisticsProxy: Boolean): String

    @JvmStatic
    external fun awaitReady(handle: Long, timeoutMillis: Long)

    @JvmStatic
    external fun stop(handle: Long)

    @JvmStatic
    external fun validate(config: String): String?

    @JvmStatic
    external fun validateResource(kind: String, path: String, behavior: String, format: String): String?

    @JvmStatic
    private external fun snapshot(handle: Long): String
}
