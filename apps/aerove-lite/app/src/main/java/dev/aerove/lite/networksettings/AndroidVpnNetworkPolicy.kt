package dev.aerove.lite.networksettings

import android.net.ProxyInfo
import android.net.VpnService

internal interface VpnTunPolicyTarget {
    fun mtu(value: Int)
    fun address(host: String, prefix: Int)
    fun route(host: String, prefix: Int)
}

internal fun applyVpnTunPolicy(target: VpnTunPolicyTarget, plan: NetworkRuntimePlan) {
    if (!plan.vpnEnabled) return
    target.mtu(plan.mtu)
    fun cidr(value: String, write: (String, Int) -> Unit) {
        val split = value.lastIndexOf('/')
        write(value.substring(0, split), value.substring(split + 1).toInt())
    }
    cidr(plan.inet4Address, target::address)
    plan.inet6Address?.let { cidr(it, target::address) }
    plan.routes.forEach { cidr(it, target::route) }
}

/** The service calls this once on its fresh Builder, before establish(). */
fun applyVpnTunPolicy(builder: VpnService.Builder, plan: NetworkRuntimePlan) = applyVpnTunPolicy(object: VpnTunPolicyTarget {
    override fun mtu(value: Int) { builder.setMtu(value) }
    override fun address(host: String, prefix: Int) { builder.addAddress(host, prefix) }
    override fun route(host: String, prefix: Int) { builder.addRoute(host, prefix) }
}, plan)

/** Called once on the current service owner's new Builder, before establish(). */
fun applyVpnNetworkPolicy(builder: VpnService.Builder, plan: NetworkRuntimePlan) {
    if (!plan.vpnEnabled) return
    if (plan.allowBypass) builder.allowBypass()
    plan.systemProxy?.let { proxy ->
        val info = ProxyInfo.buildDirectProxy(proxy.host, proxy.port, proxy.exclusions)
        require(info.isValid) { "Invalid VPN system proxy exclusions" }
        builder.setHttpProxy(info)
    }
}
