package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import org.json.JSONObject

internal fun meowFlutterBuilderSettings(core: LiteSettings, vpnOptionsJson: String): LiteSettings {
    val options = JSONObject(vpnOptionsJson)
    require(options.get("enable") is Boolean && options.getBoolean("enable") == core.network.vpnEnabled) { "vpnOptions.enable: Runtime mode differs" }
    val ipv6 = options.get("ipv6") as? Boolean ?: error("vpnOptions.ipv6: Expected a boolean")
    val network = if (options.has("rawRouteAddress") && options.has("routeAuto")) {
        val rows = options.getJSONArray("routeAddress")
        core.network.copy(routeAddress = (0 until rows.length()).map { rows.getString(it) }, routeExcludeAddress = emptyList(), bypassPrivateRoute = false, routeAuto = false, routeDefaultsPerFamily = false)
    } else core.network
    return core.copy(network = network, ipv6 = ipv6)
}

internal class MeowFlutterTunProjection(val settings: LiteSettings, val shared: JSONObject,
    val reconciliation: dev.aerove.lite.data.MeowTunReconciliation)

internal fun meowReconcileFlutterTun(source: String, settings: LiteSettings, shared: JSONObject): MeowFlutterTunProjection {
    val row = JSONObject(shared.toString())
    val options = row.getJSONObject("vpnOptions")
    val actual = settings.copy(ipv6 = options.getBoolean("ipv6"))
    val receipt = dev.aerove.lite.data.MeowTunReconciliation.reconcile(source, actual)
    val network = receipt.settings.network
    options.put("mtu", network.mtu).put("dnsHijacking", network.dnsHijacking)
        .put("routeAddress", org.json.JSONArray(dev.aerove.lite.networksettings.planVpnRoutes(network, actual.ipv6, dev.aerove.lite.data.MeowTunReconciliation.reservedRanges(source, actual.ipv6))))
        .put("rawRouteAddress", org.json.JSONArray(network.routeAddress)).put("rawRouteExcludeAddress", org.json.JSONArray(network.routeExcludeAddress)).put("bypassPrivateRoute", network.bypassPrivateRoute)
        .put("routeExcludeAddress", org.json.JSONArray(network.routeExcludeAddress))
        .put("routeAuto", network.routeAuto).put("routeDefaultsPerFamily", network.routeDefaultsPerFamily).put("inet4Address", network.tunInet4Address).put("inet6Address", network.tunInet6Address)
        .put("dnsHijackTargets", network.tunDnsHijack?.let { org.json.JSONArray(it) } ?: JSONObject.NULL)
    return MeowFlutterTunProjection(receipt.settings.copy(ipv6 = settings.ipv6), row, receipt)
}
