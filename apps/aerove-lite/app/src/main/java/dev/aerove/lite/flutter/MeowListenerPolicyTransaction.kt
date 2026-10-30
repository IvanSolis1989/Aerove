package dev.aerove.lite.flutter

import dev.aerove.lite.backgroundpolicy.ListenerPolicyIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MeowListenerPolicySuperseded : Exception("runtime_listener_policy_superseded")

internal fun meowRequireListenerPolicyCurrent(current: Boolean) {
    if (!current) throw MeowListenerPolicySuperseded()
}

internal fun meowListenerFailureRequiresStop(failure: Throwable, current: Boolean): Boolean =
    current && failure !is CancellationException && failure !is MeowListenerPolicySuperseded

internal suspend fun <A, T> meowWithListenerPolicyDecision(gate: Mutex, request: suspend () -> Pair<A, Long>,
    decision: suspend () -> ListenerPolicyIntent, isCurrent: (ListenerPolicyIntent) -> Boolean,
    commit: suspend (A, ListenerPolicyIntent) -> T): T {
    val (admission, revision) = gate.withLock { request() }
    // An older Actor callback may itself need this gate before it can emit the next decision.
    val policy = decision()
    return gate.withLock {
        meowRequireListenerPolicyCurrent(policy.revision == revision && isCurrent(policy))
        commit(admission, policy)
    }
}
