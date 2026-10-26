package dev.aerove.lite.vpn

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.SystemDnsSnapshot

internal enum class NetworkChangeAction { None, HotOnly, Reconnect }

internal fun physicalBuilderDns(dns: SystemDnsSnapshot, ipv6: Boolean): List<String> =
    dns.interfaces[dns.defaultInterface].orEmpty().filter { ipv6 || ':' !in it }

internal fun networkChangeAction(snapshot: VpnSnapshot, builderDns: List<String>, oldHandle: String?,
    newHandle: String?, dns: SystemDnsSnapshot): NetworkChangeAction {
    if (newHandle == null || snapshot.phase != VpnPhase.Running || snapshot.suspended) return NetworkChangeAction.None
    if (snapshot.initialSettings?.network?.vpnEnabled != true || snapshot.initialDnsEnabled == true) return NetworkChangeAction.HotOnly
    val next = physicalBuilderDns(dns, snapshot.initialIpv6 ?: false)
    if (next.isEmpty()) return NetworkChangeAction.None
    return if (builderDns != next) NetworkChangeAction.Reconnect else NetworkChangeAction.HotOnly
}

internal fun networkHotFailureReconnect(snapshot: VpnSnapshot, handle: String?): Boolean =
    handle != null && snapshot.phase == VpnPhase.Running && !snapshot.suspended

internal suspend fun applyPhysicalNetworkChange(action: NetworkChangeAction, current: () -> Boolean,
    hot: suspend () -> Unit, reconnect: () -> Unit, failed: (Exception) -> Unit) {
    if (!current()) return
    when (action) {
        NetworkChangeAction.None -> Unit
        NetworkChangeAction.Reconnect -> reconnect()
        NetworkChangeAction.HotOnly -> try { hot() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (current()) { failed(failure); reconnect() }
        }
    }
}
