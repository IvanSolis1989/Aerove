package dev.aerove.lite.flutter

import dev.aerove.lite.VpnPhase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Qualification is read again at each boundary; a graph must join before taking its lifecycle lock. */
internal suspend fun <T : Any, R> sharedStartTransition(
    graphGate: Mutex,
    lifecycle: Mutex,
    qualify: suspend () -> T?,
    same: (T, T) -> Boolean,
    retire: suspend () -> Unit,
    commit: suspend (T) -> R,
): R? {
    val initial = qualify() ?: return null
    return graphGate.withLock graph@ {
        val candidate = qualify() ?: return@graph null
        check(same(initial, candidate)) { "shared_start_superseded" }
        retire()
        lifecycle.withLock owner@ {
            val current = qualify() ?: return@owner null
            check(same(candidate, current)) { "shared_start_superseded" }
            commit(current)
        }
    }
}

internal fun sharedStartOwnerAvailable(autoRun: Boolean, phase: VpnPhase): Boolean {
    if (phase == VpnPhase.Stopped || phase == VpnPhase.Failed) return true
    check(autoRun) { "shared_service_owner_active" }
    return false
}
