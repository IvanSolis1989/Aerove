package dev.aerove.lite.flutter

import kotlinx.coroutines.CancellationException

/** Fixed code phases only. Never retain configuration, paths, endpoints or exception text. */
internal enum class MeowSetupPhase {
    REQUEST, READ_SOURCE, COMPILE, PROJECT, CAPTURE_RESOURCES, VALIDATE_NATIVE,
    BUILD_HANDOFF, COMMIT_HANDOFF, APPLY_STAGED, RELEASE_RESOURCES,
    GRAPH_ADMIT, GRAPH_RELEASE_PREVIOUS, GRAPH_CLAIM_OWNER, GRAPH_DECODE, GRAPH_OPEN,
    GRAPH_READY, GRAPH_POLICY, GRAPH_MODE, GRAPH_SELECTION, GRAPH_VERIFY, GRAPH_COMMIT, GRAPH_PUBLISH,
}

internal class MeowSetupFailureEvidence(val phase: MeowSetupPhase) :
    Exception(null, null, false, false)

internal class MeowSetupTrace {
    var phase = MeowSetupPhase.REQUEST
}

internal suspend fun <T> traceMeowSetup(block: suspend (MeowSetupTrace) -> T): T {
    val trace = MeowSetupTrace()
    try {
        return block(trace)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Coroutine stack recovery may discard a recovered copy at the next hop.
        // Preserve the same safe evidence on each original cause, without replacing it.
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = failure
        repeat(16) {
            val cause = current ?: return@repeat
            if (!seen.add(cause)) { current = null; return@repeat }
            if (cause.suppressed.none { it is MeowSetupFailureEvidence })
                cause.addSuppressed(MeowSetupFailureEvidence(trace.phase))
            current = cause.cause
        }
        throw failure
    }
}
