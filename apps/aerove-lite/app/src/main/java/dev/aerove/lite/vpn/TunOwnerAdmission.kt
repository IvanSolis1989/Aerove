package dev.aerove.lite.vpn

import java.util.concurrent.atomic.AtomicBoolean

/** Captured by exactly one Tun resolver; an old owner cannot be reopened by a later run. */
internal class TunOwnerAdmission(private val currentIntent: () -> Boolean) {
    private val established = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun current(): Boolean = !closed.get() && currentIntent()
    fun admitted(): Boolean = established.get() && current()

    fun established() {
        if (closed.get() || !currentIntent()) throw IllegalStateException("Tun owner intent was superseded")
        established.set(true)
        if (!admitted()) { established.set(false); throw IllegalStateException("Tun owner intent was superseded") }
    }

    fun park() { established.set(false) }
    fun close() { closed.set(true); established.set(false) }
}

internal fun capturedOwnerIntent(expected: Long?, current: () -> Long, requested: () -> Boolean): () -> Boolean {
    val captured = checkNotNull(expected) { "Runtime owner intent is unavailable" }
    return { requested() && captured == current() }
}
