package dev.aerove.lite.networksettings

enum class UserProxyProtocol { Mixed, Http, Socks }
data class UserInboundPlan(val protocol: UserProxyProtocol, val port: Int, val bindAddress: String, val allowLan: Boolean)
data class VpnProxyPlan(val host: String, val port: Int, val exclusions: List<String>)
data class EffectiveVpnNetworkOptions(val allowBypass: Boolean, val systemProxy: VpnProxyPlan?, val enabled: Boolean = true,
    val mtu: Int? = null, val dnsHijacking: Boolean? = null, val routes: List<String> = emptyList())

/** Compare this against the running Builder policy; dormant options do not demand a restart. */
fun effectiveVpnNetworkOptions(settings: NetworkSettings): EffectiveVpnNetworkOptions = effectiveVpnNetworkOptions(settings, true)

fun effectiveVpnNetworkOptions(settings: NetworkSettings, ipv6: Boolean): EffectiveVpnNetworkOptions =
    effectiveVpnNetworkOptions(settings, ipv6, emptyList())

fun effectiveVpnNetworkOptions(settings: NetworkSettings, ipv6: Boolean, reservedFakeIpRanges: List<String>): EffectiveVpnNetworkOptions {
    val snapshot = settings.snapshot().requireValid()
    if (!snapshot.vpnEnabled) return EffectiveVpnNetworkOptions(false, null, enabled = false)
    val proxy = if (snapshot.systemProxy && snapshot.authentication.credentials.isEmpty())
        VpnProxyPlan("127.0.0.1", snapshot.ports.mixed, snapshot.bypassDomains.toList()) else null
    return EffectiveVpnNetworkOptions(snapshot.allowBypass, proxy, mtu = snapshot.mtu,
        dnsHijacking = snapshot.dnsHijacking, routes = planVpnRoutes(snapshot, ipv6, reservedFakeIpRanges))
}

class NetworkRuntimePlan internal constructor(
    val inbounds: List<UserInboundPlan>,
    val allowBypass: Boolean,
    val systemProxy: VpnProxyPlan?,
    private val fields: Map<String, Any>,
    val vpnEnabled: Boolean = true,
    val mtu: Int = 9000,
    val dnsHijacking: Boolean = false,
    val routes: List<String> = emptyList(),
) {
    fun userConfigFields(): Map<String, Any> = fields.toMap()
    override fun toString(): String = "NetworkRuntimePlan(inbounds=$inbounds, allowBypass=$allowBypass, systemProxy=$systemProxy, credentials=<redacted>)"
}

fun planNetwork(settings: NetworkSettings, controlPort: Int, ipv6: Boolean): NetworkRuntimePlan =
    planNetwork(settings, controlPort, ipv6, emptyList())

fun planNetwork(settings: NetworkSettings, controlPort: Int, ipv6: Boolean, reservedFakeIpRanges: List<String>): NetworkRuntimePlan {
    val snapshot = settings.snapshot().requireValid()
    require(controlPort in 1..65535) { "Invalid private control port" }
    val ports = snapshot.ports
    val selected = listOf(UserProxyProtocol.Mixed to ports.mixed, UserProxyProtocol.Http to ports.http, UserProxyProtocol.Socks to ports.socks)
        .filter { it.second != 0 }
    if (selected.any { it.second == controlPort }) throw NetworkSettingsException(listOf(NetworkIssue("network.ports", NetworkIssueCode.PortConflict)))
    val bind = if (!snapshot.allowLan) "127.0.0.1" else if (ipv6) "::" else "0.0.0.0"
    val fields = linkedMapOf<String, Any>("mixed-port" to ports.mixed, "allow-lan" to snapshot.allowLan,
        "bind-address" to bind, "authentication" to snapshot.authentication.credentials)
    if (ports.http != 0) fields["port"] = ports.http
    if (ports.socks != 0) fields["socks-port"] = ports.socks
    val vpn = effectiveVpnNetworkOptions(snapshot, ipv6, reservedFakeIpRanges)
    return NetworkRuntimePlan(selected.map { UserInboundPlan(it.first, it.second, bind, snapshot.allowLan) },
        vpn.allowBypass, vpn.systemProxy, fields, vpnEnabled = vpn.enabled, mtu = vpn.mtu ?: 9000,
        dnsHijacking = vpn.dnsHijacking ?: false, routes = vpn.routes)
}
