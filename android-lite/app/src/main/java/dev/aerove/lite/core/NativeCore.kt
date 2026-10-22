package dev.aerove.lite.core

import org.json.JSONObject

fun interface SocketProtector {
    fun protect(fd: Int): Boolean
}

object NativeCore {
    enum class State { Starting, Running, Failed, Exited }

    data class Status(
        val state: State,
        val error: String?,
        val apiAddress: String?,
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
    fun status(handle: Long): Status {
        val value = JSONObject(snapshot(handle))
        return Status(
            state = State.valueOf(value.getString("state")),
            error = value.optString("error").takeUnless { value.isNull("error") },
            apiAddress = value.optString("apiAddress").takeUnless { value.isNull("apiAddress") },
        )
    }

    @JvmStatic
    external fun awaitReady(handle: Long, timeoutMillis: Long)

    @JvmStatic
    external fun stop(handle: Long)

    @JvmStatic
    external fun validate(config: String): String?

    @JvmStatic
    private external fun snapshot(handle: Long): String
}
