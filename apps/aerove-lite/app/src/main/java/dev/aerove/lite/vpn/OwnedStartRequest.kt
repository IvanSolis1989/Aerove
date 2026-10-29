package dev.aerove.lite.vpn

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class OwnedStartCancellation { Prevented, CancelledAndJoined, Superseded, TerminationUnconfirmed }

/** A single issued start; retaining this handle never grants ownership of a later intent. */
class OwnedStartRequest internal constructor(internal val registry: OwnedStartRegistry) {
    internal val id: String = UUID.randomUUID().toString()
    internal var sent = false
    internal var admitted = false
    @Volatile internal var cancelling = false
    internal var onCancel: (() -> Unit)? = null
    private val acknowledgement = CompletableDeferred<OwnedStartCancellation>()

    suspend fun cancelAndJoin(awaitMillis: Long = 45_000): OwnedStartCancellation {
        require(awaitMillis in 1..45_000L) { "Owned start acknowledgement budget must be within 1..45000 ms" }
        registry.cancel(this)
        return withContext(NonCancellable) {
            withTimeoutOrNull(awaitMillis) { acknowledgement.await() } ?: OwnedStartCancellation.TerminationUnconfirmed
        }
    }

    internal fun completeCancellation(result: OwnedStartCancellation) { acknowledgement.complete(result) }
}

/** Only live issued IDs are admissible: removing an ID also rejects every late delivery or replay. */
internal class OwnedStartRegistry(private val capacity: Int = 64) {
    private val issued = mutableMapOf<String, OwnedStartRequest>()

    init { require(capacity > 0) }

    @Synchronized fun prepare(): OwnedStartRequest? =
        if (issued.size >= capacity) null else OwnedStartRequest(this).also { issued[it.id] = it }

    @Synchronized fun send(request: OwnedStartRequest): Boolean {
        if (issued[request.id] !== request || request.sent) return false
        request.sent = true
        return true
    }

    @Synchronized fun admit(id: String, cancellation: (OwnedStartRequest) -> (() -> Unit)): OwnedStartRequest? {
        val request = issued[id]?.takeIf { it.sent && !it.admitted } ?: return null
        request.onCancel = cancellation(request)
        request.admitted = true
        return request
    }

    @Synchronized fun retire(request: OwnedStartRequest) {
        if (request.registry !== this) return
        if (issued[request.id] === request) issued.remove(request.id)
        request.onCancel = null
        if (!request.cancelling) request.completeCancellation(
            if (request.admitted) OwnedStartCancellation.Superseded else OwnedStartCancellation.Prevented)
    }

    @Synchronized fun reject(id: String) {
        issued[id]?.takeUnless { it.admitted }?.let(::retire)
    }

    fun cancel(request: OwnedStartRequest) {
        val callback = synchronized(this) {
            if (request.registry !== this || request.cancelling) return
            request.cancelling = true
            if (issued[request.id] !== request) {
                request.completeCancellation(OwnedStartCancellation.Superseded)
                return
            }
            issued.remove(request.id)
            request.onCancel.also {
                request.onCancel = null
                if (it == null) request.completeCancellation(OwnedStartCancellation.Prevented)
            }
        }
        try { callback?.invoke() }
        catch (_: Exception) { request.completeCancellation(OwnedStartCancellation.TerminationUnconfirmed) }
    }
}

internal data class OwnedStartServiceReceipt(val request: OwnedStartRequest, val serviceOwner: Long,
    val intentGeneration: Long, val startId: Int)

internal data class OwnedStartStop(val intentGeneration: Long, val preparation: Job?, val joined: Deferred<Unit>)

internal fun capturedOwnedStartIntent(expected: Long?, receipt: OwnedStartServiceReceipt?, serviceOwner: Long,
    currentGeneration: () -> Long, requested: () -> Boolean, serviceCurrent: () -> Boolean): () -> Boolean {
    val request = receipt?.takeIf { it.intentGeneration == expected && it.serviceOwner == serviceOwner }?.request
    return capturedOwnerIntent(expected, currentGeneration) { requested() && serviceCurrent() && request?.cancelling != true }
}

internal suspend fun cancelOwnedStartForService(expected: OwnedStartServiceReceipt,
    current: () -> OwnedStartServiceReceipt?, currentGeneration: () -> Long, latestStartId: () -> Int,
    onMain: suspend (() -> Unit) -> Unit, withdraw: () -> OwnedStartStop,
    finish: (Int, Long) -> Unit): OwnedStartCancellation = withContext(NonCancellable) {
    try {
        withTimeout(45_000) {
            var captured: OwnedStartStop? = null
            onMain { if (current() === expected) captured = withdraw() }
            val stop = captured ?: return@withTimeout OwnedStartCancellation.Superseded
            stop.preparation?.cancelAndJoin()
            stop.joined.await()
            onMain { if (currentGeneration() == stop.intentGeneration) finish(latestStartId(), stop.intentGeneration) }
            OwnedStartCancellation.CancelledAndJoined
        }
    } catch (_: Exception) { OwnedStartCancellation.TerminationUnconfirmed }
}
