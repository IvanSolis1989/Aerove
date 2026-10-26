package dev.aerove.lite.dnssettings

import kotlinx.coroutines.CancellationException

data class DnsSaveReceipt(val token: Long, val failed: Boolean)
internal data class PendingDnsSave(val preferences: DnsPreferences, val token: Long)
internal enum class DnsSaveDecision { Wait, Saved, Failed }

internal class DnsSaveAttempts(private val publish: (DnsSaveReceipt) -> Unit) {
    private var latest = 0L
    private var completed = false

    @Synchronized fun begin(): Long {
        check(latest < Long.MAX_VALUE)
        completed = false
        return ++latest
    }

    @Synchronized fun complete(token: Long, failed: Boolean) {
        if (token != latest || completed) return
        completed = true
        publish(DnsSaveReceipt(token, failed))
    }

    suspend fun persist(token: Long, save: suspend () -> Unit) {
        try {
            save()
            complete(token, failed = false)
        } catch (cancelled: CancellationException) {
            complete(token, failed = true)
            throw cancelled
        } catch (failure: Exception) {
            complete(token, failed = true)
            throw failure
        }
    }
}

internal fun dnsSaveDecision(current: DnsPreferences, busy: Boolean, pending: PendingDnsSave?,
    receipt: DnsSaveReceipt?): DnsSaveDecision {
    if (pending == null || busy) return DnsSaveDecision.Wait
    val result = receipt?.takeIf { it.token == pending.token }
    return when {
        result == null -> DnsSaveDecision.Wait
        result.failed -> DnsSaveDecision.Failed
        current == pending.preferences -> DnsSaveDecision.Saved
        else -> DnsSaveDecision.Wait
    }
}
