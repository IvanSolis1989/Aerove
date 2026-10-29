package dev.aerove.lite.networksettings

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.VpnSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DashboardLanReceipt(val token: Long, val mixedPort: Int?)

/** A receipt belongs to an explicit dashboard enable, never a passive settings readback. */
internal class DashboardLanAttempts(private val publish: (DashboardLanReceipt) -> Unit) {
    private val sequence = MutableStateFlow(0L)
    val tokens = sequence.asStateFlow()
    private var completed = false

    @Synchronized fun begin(): Long {
        check(sequence.value < Long.MAX_VALUE)
        completed = false
        sequence.value++
        return sequence.value
    }

    @Synchronized fun current(token: Long) = token == sequence.value
    @Synchronized fun invalidate() { begin() }
    @Synchronized fun complete(token: Long, mixedPort: Int?) {
        if (!current(token) || completed) return
        completed = true
        publish(DashboardLanReceipt(token, mixedPort))
    }

    suspend fun persist(token: Long, save: suspend () -> Int) {
        try {
            val port = save()
            currentCoroutineContext().ensureActive()
            complete(token, port)
        }
        catch (failure: Exception) { complete(token, null); throw failure }
    }
}

internal enum class DashboardLanCommit { Wait, Applied, Aborted }

private fun sameLanInbound(left: NetworkSettings, leftIpv6: Boolean, right: NetworkSettings, rightIpv6: Boolean) =
    left.allowLan == right.allowLan && left.ports == right.ports &&
        left.authentication.credentials == right.authentication.credentials && leftIpv6 == rightIpv6

/** Matches the existing Service actor's inbound fields and its POST+GET applied snapshot. */
internal fun dashboardLanCommit(initial: VpnSnapshot, runtime: VpnSnapshot, saved: LiteSettings,
    current: LiteSettings?, currentIntent: Boolean): DashboardLanCommit {
    if (!currentIntent || !saved.network.allowLan || current == null ||
        !sameLanInbound(saved.network, saved.ipv6, current.network, current.ipv6))
        return DashboardLanCommit.Aborted
    if (initial.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed)) {
        return if (runtime.phase == initial.phase && runtime.endpoint == null) DashboardLanCommit.Applied
        else DashboardLanCommit.Aborted
    }
    if (initial.phase != VpnPhase.Running || initial.endpoint == null || runtime.phase != VpnPhase.Running ||
        runtime.endpoint !== initial.endpoint || runtime.sessionGeneration != initial.sessionGeneration ||
        runtime.suspended || runtime.listenerError != null)
        return DashboardLanCommit.Aborted
    val request = runtime.networkRequest ?: return DashboardLanCommit.Wait
    if (request.id <= (initial.networkRequest?.id ?: 0L) ||
        !sameLanInbound(saved.network, saved.ipv6, request.network, request.ipv6)) return DashboardLanCommit.Wait
    if (request.outcome == NetworkRuntimeOutcome.Failed || runtime.networkError != null) return DashboardLanCommit.Aborted
    if (request.outcome != NetworkRuntimeOutcome.Applied) return DashboardLanCommit.Wait
    if (runtime.networkApplying || !runtime.listening) return DashboardLanCommit.Wait
    val applied = runtime.appliedNetwork ?: return DashboardLanCommit.Wait
    return if (applied.allowLan && applied.ports == saved.network.ports &&
        applied.authentication.credentials == saved.network.authentication.credentials && runtime.appliedIpv6 == saved.ipv6)
        DashboardLanCommit.Applied else DashboardLanCommit.Wait
}
