package dev.aerove.lite.networksettings

import android.net.ProxyInfo
import android.net.VpnService

/** Called once on the current service owner's new Builder, before establish(). */
fun applyVpnNetworkPolicy(builder: VpnService.Builder, plan: NetworkRuntimePlan) {
    if (plan.allowBypass) builder.allowBypass()
    plan.systemProxy?.let { proxy ->
        val info = ProxyInfo.buildDirectProxy(proxy.host, proxy.port, proxy.exclusions)
        require(info.isValid) { "Invalid VPN system proxy exclusions" }
        builder.setHttpProxy(info)
    }
}
