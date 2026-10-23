package dev.aerove.lite.integration

/** Retained by the Controller, so Activity recreation cannot replay accepted platform requests. */
class PlatformEntryGate {
    private var startGeneration = 0L
    private var autoRunConsumed = false
    private val requests = linkedSetOf<String>()

    fun beginStart(): Long = ++startGeneration
    fun cancelStart() { startGeneration++ }
    fun currentStart(token: Long): Boolean = token == startGeneration
    fun suppressAutoRun() { autoRunConsumed = true }

    fun acceptRequest(id: String): Boolean {
        if (id.isBlank() || id.length > 80 || !requests.add(id)) return false
        while (requests.size > 128) requests.remove(requests.first())
        return true
    }

    fun consumeAutoRun(initialized: Boolean, consent: Boolean, enabled: Boolean, selected: Boolean, stopped: Boolean): Boolean {
        if (!initialized || !consent || autoRunConsumed) return false
        autoRunConsumed = true
        return enabled && selected && stopped
    }
}
