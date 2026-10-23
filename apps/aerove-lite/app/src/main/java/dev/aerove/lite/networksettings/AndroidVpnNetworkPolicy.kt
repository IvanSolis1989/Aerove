package dev.aerove.lite.networksettings

import android.net.ProxyInfo
import android.net.VpnService

/** The service calls this once on its fresh Builder, with no pre-added default routes. */
fun applyVpnTunPolicy(builder: VpnService.Builder, plan: NetworkRuntimePlan) {
    if (!plan.vpnEnabled) return
    builder.setMtu(plan.mtu)
    plan.routes.forEach { route ->
        val split = route.lastIndexOf('/')
        builder.addRoute(route.substring(0, split), route.substring(split + 1).toInt())
    }
}

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
