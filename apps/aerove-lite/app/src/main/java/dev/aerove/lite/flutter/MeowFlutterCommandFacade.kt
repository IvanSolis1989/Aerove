package dev.aerove.lite.flutter

import dev.aerove.lite.integration.PlatformEntryGate
import dev.aerove.lite.vpn.OwnedStartCancellation
import dev.aerove.lite.vpn.OwnedStartRegistry
import dev.aerove.lite.vpn.OwnedStartRequest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.WeakHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class MeowFlutterRuntimeBinding(val revision: String, val intentEpoch: Long, internal val sourcePermit: ProviderSourcePermit? = null) {
    init {
        require(revision.isNotBlank()) { "Runtime revision is required" }
        require(intentEpoch >= 0) { "Runtime intent epoch must be nonnegative" }
    }
}

data class MeowFlutterStopSnapshot(val serviceOwner: Long, val sessionGeneration: Long,
    val terminal: Boolean, val failed: Boolean = false)

sealed interface MeowFlutterStopResult {
    data class Confirmed(val serviceOwner: Long, val sessionGeneration: Long) : MeowFlutterStopResult
    data object Superseded : MeowFlutterStopResult
    data object TerminationUnconfirmed : MeowFlutterStopResult

    fun requireConfirmed(): Boolean = when (this) {
        is Confirmed -> true
        Superseded -> throw MeowProtocolFailure("stop_superseded", "Stop acknowledgement belongs to a superseded runtime")
        TerminationUnconfirmed -> throw MeowProtocolFailure("termination_unconfirmed", "Service termination could not be confirmed")
    }
}

internal class MeowStoppedGraphCancellation(cause: kotlinx.coroutines.CancellationException) :
    kotlinx.coroutines.CancellationException("Service stopped; parked query graph restoration cancelled") {
    init { initCause(cause) }
}

internal suspend fun restoreQueryGraphAfterConfirmedStop(restore: suspend () -> Unit) {
    try { restore() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw MeowStoppedGraphCancellation(cancelled) }
    catch (failure: Exception) {
        throw MeowProtocolFailure("stop_graph_restore_failed", "Service stopped; parked query graph restoration failed",
            mapOf("stopRestoreLocation" to meowFailureLocation("service.stop", failure)), failure)
    }
}

class MeowFlutterPermissionStart internal constructor(
    internal val owner: MeowFlutterCommandFacade,
    internal val request: OwnedStartRequest,
    internal val token: Long,
    internal val epoch: Long,
) {
    internal val resolved = AtomicBoolean(false)
}

enum class MeowFlutterPermissionRejection { Denied, Superseded, AdmissionRejected }

sealed interface MeowFlutterPermissionResult {
    data object Sent : MeowFlutterPermissionResult
    data object Ignored : MeowFlutterPermissionResult
    data class Cancelled(val reason: MeowFlutterPermissionRejection,
        val cancellation: OwnedStartCancellation) : MeowFlutterPermissionResult
}

class MeowFlutterCommandFacade internal constructor(
    private val ownedStarts: OwnedStartRegistry,
    private val canPrepare: () -> Boolean,
    private val canConnect: () -> Boolean,
    private val startIntentEpoch: () -> Long,
    private val invalidatePendingStarts: () -> Unit,
    private val dispatchStart: (OwnedStartRequest, Long, MeowFlutterRuntimeBinding?) -> Unit,
    private val dispatchStop: () -> Unit,
    private val cancelPendingWork: () -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val requiresRuntimeBinding: () -> Boolean = { false },
    private val runtimeCurrent: (MeowFlutterRuntimeBinding) -> Boolean = { true },
    private val currentStopSnapshot: (() -> MeowFlutterStopSnapshot)? = null,
    private val stopSnapshots: Flow<MeowFlutterStopSnapshot>? = null,
    private val ownedStops: dev.aerove.lite.vpn.OwnedStopRegistry? = null,
    private val dispatchOwnedStop: ((dev.aerove.lite.vpn.OwnedStopRequest) -> Unit)? = null,
) {
    private val permissionIntents = PlatformEntryGate()
    private data class RequestBinding(val runtime: MeowFlutterRuntimeBinding?, val startEpoch: Long, val token: Long)
    private val bindings = WeakHashMap<OwnedStartRequest, RequestBinding>()

    @Synchronized fun prepareOwnedConnect(runtime: MeowFlutterRuntimeBinding? = null): OwnedStartRequest? =
        if (canConnect()) issue(runtime) else null

    private fun runtimeAllowed(runtime: MeowFlutterRuntimeBinding?): Boolean =
        if (runtime == null) !requiresRuntimeBinding() else runtimeCurrent(runtime)

    private fun issue(runtime: MeowFlutterRuntimeBinding?): OwnedStartRequest? {
        if (!runtimeAllowed(runtime)) return null
        ownedStops?.supersede()
        return ownedStarts.prepare()?.also {
            it.providerSourcePermit = runtime?.sourcePermit
            bindings[it] = RequestBinding(runtime, startIntentEpoch(), permissionIntents.beginStart())
        }
    }

    private fun requestCurrent(request: OwnedStartRequest): Boolean = bindings[request]?.let {
        it.startEpoch == startIntentEpoch() && permissionIntents.currentStart(it.token) && runtimeAllowed(it.runtime)
    } == true

    @Synchronized fun connectOwned(request: OwnedStartRequest): Boolean {
        val binding = bindings[request] ?: return false
        if (!ownedStarts.send(request)) return false
        try {
            if (!requestCurrent(request) || !canConnect()) { ownedStarts.retire(request); return false }
            dispatchStart(request, nanoTime(), binding.runtime)
        } catch (failure: Exception) {
            ownedStarts.retire(request)
            throw failure
        }
        return true
    }

    @Synchronized fun preparePermissionConnect(runtime: MeowFlutterRuntimeBinding? = null): MeowFlutterPermissionStart? {
        if (!canPrepare()) return null
        val request = issue(runtime) ?: return null
        val binding = checkNotNull(bindings[request])
        return MeowFlutterPermissionStart(this, request, binding.token, binding.startEpoch)
    }

    suspend fun completePermissionConnect(attempt: MeowFlutterPermissionStart,
        granted: Boolean): MeowFlutterPermissionResult {
        require(attempt.owner === this) { "Permission request belongs to another command facade" }
        val rejection = synchronized(this) {
            if (!attempt.resolved.compareAndSet(false, true)) return MeowFlutterPermissionResult.Ignored
            when {
                !granted -> MeowFlutterPermissionRejection.Denied
                !permissionIntents.currentStart(attempt.token) || attempt.epoch != startIntentEpoch() || !requestCurrent(attempt.request) ->
                    MeowFlutterPermissionRejection.Superseded
                connectOwned(attempt.request) -> return MeowFlutterPermissionResult.Sent
                else -> MeowFlutterPermissionRejection.AdmissionRejected
            }
        }
        return MeowFlutterPermissionResult.Cancelled(rejection, cancelOwnedConnect(attempt.request))
    }

    suspend fun cancelPermissionConnect(attempt: MeowFlutterPermissionStart,
        awaitMillis: Long = 45_000): OwnedStartCancellation {
        require(attempt.owner === this) { "Permission request belongs to another command facade" }
        require(awaitMillis in 1..45_000L) { "Owned start acknowledgement budget must be within 1..45000 ms" }
        attempt.resolved.set(true)
        return cancelOwnedConnect(attempt.request, awaitMillis)
    }

    suspend fun cancelOwnedConnect(request: OwnedStartRequest,
        awaitMillis: Long = 45_000): OwnedStartCancellation {
        require(request.registry === ownedStarts) { "Start request belongs to another registry" }
        return request.cancelAndJoin(awaitMillis)
    }

    @Synchronized fun disconnect() {
        ownedStops?.supersede()
        permissionIntents.cancelStart()
        invalidatePendingStarts()
        cancelPendingWork()
        dispatchStop()
    }

    suspend fun disconnectAndJoin(awaitMillis: Long = 45_000): MeowFlutterStopResult {
        require(awaitMillis in 1..45_000L) { "Stop acknowledgement budget must be within 1..45000 ms" }
        if (ownedStops != null && dispatchOwnedStop != null) {
            val request = synchronized(this) {
                permissionIntents.cancelStart()
                invalidatePendingStarts()
                cancelPendingWork()
                val captured = currentStopSnapshot?.invoke()
                    ?: return MeowFlutterStopResult.TerminationUnconfirmed
                ownedStops.prepare(captured).also {
                    try { dispatchOwnedStop.invoke(it) }
                    catch (failure: Exception) { ownedStops.retire(it); throw failure }
                }
            }
            return withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(awaitMillis) { request.result.await() }
                        ?: MeowFlutterStopResult.TerminationUnconfirmed
                } finally { ownedStops.retire(request) }
            }
        }
        val before = synchronized(this) {
            val captured = currentStopSnapshot?.invoke()
            disconnect()
            captured
        }
        val snapshots = stopSnapshots
        if (before == null || snapshots == null) return MeowFlutterStopResult.TerminationUnconfirmed
        return withContext(NonCancellable) {
            try {
                withTimeoutOrNull(awaitMillis) {
                    val observed = snapshots.first { stopResult(before, it) != null }
                    checkNotNull(stopResult(before, observed))
                } ?: MeowFlutterStopResult.TerminationUnconfirmed
            } catch (_: Exception) { MeowFlutterStopResult.TerminationUnconfirmed }
        }
    }

    private fun stopResult(before: MeowFlutterStopSnapshot, after: MeowFlutterStopSnapshot): MeowFlutterStopResult? = when {
        after.serviceOwner != before.serviceOwner -> MeowFlutterStopResult.Superseded
        after.sessionGeneration > before.sessionGeneration + 1 -> MeowFlutterStopResult.Superseded
        after.sessionGeneration <= before.sessionGeneration -> null
        after.failed -> MeowFlutterStopResult.TerminationUnconfirmed
        after.terminal -> MeowFlutterStopResult.Confirmed(after.serviceOwner, after.sessionGeneration)
        else -> null
    }
}
