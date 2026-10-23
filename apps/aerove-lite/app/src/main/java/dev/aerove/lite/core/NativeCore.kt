package dev.aerove.lite.core

import org.json.JSONObject

fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

fun interface ConnectionOwnerLookup { fun resolveConnectionOwner(protocol: Int, source: String, target: String): String? }

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
        val coreReady: Boolean = false,
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
    external fun startRuntimeParked(configPath: String, workingDir: String, runtimeMode: String, protector: SocketProtector?): Long

    @JvmStatic external fun startRuntimeOwned(configPath: String, workingDir: String, runtimeMode: String, tunFd: Int,
        protector: SocketProtector?, owner: ConnectionOwnerLookup?, initiallyParked: Boolean): Long

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
            coreReady = value.getBoolean("coreReady"),
        )
    }

    data class ListenerStatus(
        val listening: Boolean,
        val tunReady: Boolean,
        val userListenersReady: Boolean,
        val activePorts: List<Int>,
        val coreEpoch: String,
        val onlyStatisticsProxy: Boolean,
        val unifiedDelay: Boolean = true,
        val tcpConcurrent: Boolean = true,
        val findProcessMode: String = "strict",
    )

    private fun listenerStatus(value: String): ListenerStatus {
        val data = JSONObject(value)
        val epoch = data.getString("coreEpoch")
        require(epoch.toULongOrNull()?.toString() == epoch) { "Invalid native core epoch" }
        val ports = data.getJSONArray("activePorts")
        return ListenerStatus(data.getBoolean("listening"), data.getBoolean("tunReady"),
            data.getBoolean("userListenersReady"), List(ports.length()) { ports.getInt(it) },
            epoch, data.getBoolean("onlyStatisticsProxy"), data.getBoolean("unifiedDelay"), data.getBoolean("tcpConcurrent"), data.getString("findProcessMode").also { require(it in setOf("strict", "always", "off")) { "Invalid native process mode" } })
    }

    @JvmStatic fun park(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "park", -1, false))
    @JvmStatic fun resumeProxy(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "resumeProxy", -1, false))
    @JvmStatic fun attachTun(handle: Long, tunFd: Int): ListenerStatus = listenerStatus(listenerCommand(handle, "attachTun", tunFd, false))
    @JvmStatic fun listenerStatus(handle: Long): ListenerStatus = listenerStatus(listenerCommand(handle, "read", -1, false))
    @JvmStatic fun setOnlyStatisticsProxy(handle: Long, enabled: Boolean) {
        listenerCommand(handle, "statistics", -1, enabled)
    }

    data class UserControllerStatus(val enabled: Boolean, val address: String?, val authenticated: Boolean, val privateRoutesExcluded: Boolean, val coreEpoch: String)
    private fun userControllerStatus(json: String): UserControllerStatus {
        val value = JSONObject(json)
        val epoch = value.getString("coreEpoch")
        require(epoch.toULongOrNull()?.toString() == epoch) { "Invalid user controller epoch" }
        return UserControllerStatus(value.getBoolean("enabled"), if (value.isNull("address")) null else value.getString("address"), value.getBoolean("authenticated"), value.getBoolean("privateRoutesExcluded"), epoch)
    }
    @JvmStatic fun configureUserController(handle: Long, enabled: Boolean, secret: String): UserControllerStatus = userControllerStatus(userControllerCommand(handle, false, enabled, secret))
    @JvmStatic fun userControllerStatus(handle: Long): UserControllerStatus = userControllerStatus(userControllerCommand(handle, true, false, ""))
    @JvmStatic private external fun userControllerCommand(handle: Long, read: Boolean, enabled: Boolean, secret: String): String
    data class ProbeDialStatus(val unifiedDelay: Boolean, val tcpConcurrent: Boolean, val coreEpoch: String)
    @JvmStatic fun probeDialStatus(handle: Long): ProbeDialStatus = listenerStatus(handle).let { ProbeDialStatus(it.unifiedDelay, it.tcpConcurrent, it.coreEpoch) }
    @JvmStatic external fun invalidateProcessIdentity(handle: Long)
    @JvmStatic external fun configureFindProcessMode(handle: Long, mode: String)
    @JvmStatic external fun configureProbeDial(handle: Long, unifiedDelay: Boolean, tcpConcurrent: Boolean)

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
    external fun inspectRuleSet(path: String): String

    @JvmStatic
    external fun dumpRuleSet(path: String, behavior: String, format: String): String

    @JvmStatic
    external fun validateResource(kind: String, path: String, behavior: String, format: String): String?

    @JvmStatic
    private external fun snapshot(handle: Long): String
}
