package dev.aerove.lite.vpn

import dev.aerove.lite.flutter.MeowFlutterStopResult
import dev.aerove.lite.flutter.MeowFlutterStopSnapshot
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

internal class OwnedStopRequest(val before: MeowFlutterStopSnapshot) {
    val id: String = UUID.randomUUID().toString()
    val result = CompletableDeferred<MeowFlutterStopResult>()
    var receipt: OwnedStopServiceReceipt? = null
}

internal data class OwnedStopServiceReceipt(val request: OwnedStopRequest,
    val serviceOwner: Long, val intentGeneration: Long)

internal class OwnedStopRegistry {
    private var current: OwnedStopRequest? = null

    fun prepare(before: MeowFlutterStopSnapshot): OwnedStopRequest {
        val request = OwnedStopRequest(before)
        val previous = synchronized(this) { current.also { current = request } }
        previous?.result?.complete(MeowFlutterStopResult.Superseded)
        return request
    }

    fun supersede() {
        val previous = synchronized(this) { current.also { current = null } }
        previous?.result?.complete(MeowFlutterStopResult.Superseded)
    }

    fun admit(id: String, owner: Long, intentGeneration: Long): OwnedStopServiceReceipt? {
        var rejected: OwnedStopRequest? = null
        val receipt = synchronized(this) {
            val request = current?.takeIf { it.id == id && it.receipt == null } ?: return null
            if (request.before.serviceOwner != owner && (!request.before.terminal || owner < request.before.serviceOwner)) {
                current = null
                rejected = request
                null
            } else OwnedStopServiceReceipt(request, owner, intentGeneration).also { request.receipt = it }
        }
        rejected?.result?.complete(MeowFlutterStopResult.Superseded)
        return receipt
    }

    fun complete(receipt: OwnedStopServiceReceipt, result: MeowFlutterStopResult) {
        synchronized(this) {
            if (current !== receipt.request || receipt.request.receipt !== receipt) return
            current = null
        }
        receipt.request.result.complete(result)
    }

    fun retire(request: OwnedStopRequest) {
        synchronized(this) {
            if (current !== request) return
            current = null
        }
        request.result.complete(MeowFlutterStopResult.TerminationUnconfirmed)
    }

    fun retireOwner(owner: Long) {
        val request = synchronized(this) { current?.takeIf { it.receipt?.serviceOwner == owner } }
        request?.let(::retire)
    }
}

internal fun ownedStopTerminalResult(receipt: OwnedStopServiceReceipt, target: Long,
    snapshot: MeowFlutterStopSnapshot, currentOwner: Boolean, currentIntent: Long): MeowFlutterStopResult? = when {
    !currentOwner || currentIntent != receipt.intentGeneration || snapshot.serviceOwner != receipt.serviceOwner ||
        snapshot.sessionGeneration > target -> MeowFlutterStopResult.Superseded
    snapshot.sessionGeneration < target -> null
    snapshot.failed -> MeowFlutterStopResult.TerminationUnconfirmed
    snapshot.terminal -> MeowFlutterStopResult.Confirmed(receipt.serviceOwner, target)
    else -> null
}
