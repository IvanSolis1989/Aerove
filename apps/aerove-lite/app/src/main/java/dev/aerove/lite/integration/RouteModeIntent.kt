package dev.aerove.lite.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serial owner includes compensation for every potentially applied PATCH. */
internal class RouteModeIntent {
    private val gate = Any()
    private val serial = Mutex()
    private var generation = 0L
    private var pending: Long? = null
    fun reserve(changed: Boolean): Long? = synchronized(gate) {
        if (!changed && pending == null) null else (++generation).also { pending = it }
    }
    fun current(token: Long): Boolean = synchronized(gate) { pending == token }
    fun finish(token: Long) = synchronized(gate) { if (pending == token) pending = null }

    suspend fun commit(token: Long, persist: suspend () -> Unit): Boolean {
        try { return serial.withLock {
            if (!current(token)) return@withLock false
            persist()
            current(token)
        } } finally { finish(token) }
    }

    suspend fun apply(token: Long, available: () -> Boolean, patch: suspend () -> Unit,
        persist: suspend () -> Unit, recover: suspend (Exception) -> Unit = {}): Boolean {
        try { return serial.withLock {
            if (!current(token)) return@withLock false
            var attempted = false
            var accepted = false
            var failure: Exception? = null
            try {
                if (!available()) throw CancellationException("Mode runtime is unavailable")
                attempted = true // A failed HTTP answer can still have changed the remote mode.
                patch()
                if (!current(token)) return@withLock false
                if (!available()) throw CancellationException("Mode runtime changed")
                persist()
                if (!current(token)) return@withLock false
                accepted = true
                true
            } catch (error: Exception) { failure = error; throw error }
            finally {
                if (attempted && !accepted) withContext(NonCancellable) {
                    try { recover(failure ?: CancellationException("Mode intent was superseded")) }
                    catch (cleanup: Exception) {
                        if (failure != null && cleanup !== failure && cleanup.suppressed.none { it === failure }) cleanup.addSuppressed(failure!!)
                        throw cleanup
                    }
                }
            }
        } } finally { finish(token) }
    }
}
