package dev.aerove.lite.networksettings

enum class UserProxyProtocol { Mixed, Http, Socks }
data class UserInboundPlan(val protocol: UserProxyProtocol, val port: Int, val bindAddress: String, val allowLan: Boolean)
data class VpnProxyPlan(val host: String, val port: Int, val exclusions: List<String>)
data class EffectiveVpnNetworkOptions(val allowBypass: Boolean, val systemProxy: VpnProxyPlan?)

/** Compare this against the running Builder policy; dormant options do not demand a restart. */
fun effectiveVpnNetworkOptions(settings: NetworkSettings): EffectiveVpnNetworkOptions {
    val snapshot = settings.snapshot().requireValid()
    val proxy = if (snapshot.systemProxy && snapshot.authentication.credentials.isEmpty())
        VpnProxyPlan("127.0.0.1", snapshot.ports.mixed, snapshot.bypassDomains.toList()) else null
    return EffectiveVpnNetworkOptions(snapshot.allowBypass, proxy)
}

class NetworkRuntimePlan internal constructor(
    val inbounds: List<UserInboundPlan>,
    val allowBypass: Boolean,
    val systemProxy: VpnProxyPlan?,
    private val fields: Map<String, Any>,
) {
    fun userConfigFields(): Map<String, Any> = fields.toMap()
    override fun toString(): String = "NetworkRuntimePlan(inbounds=$inbounds, allowBypass=$allowBypass, systemProxy=$systemProxy, credentials=<redacted>)"
}

fun planNetwork(settings: NetworkSettings, controlPort: Int, ipv6: Boolean): NetworkRuntimePlan {
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
    val vpn = effectiveVpnNetworkOptions(snapshot)
    return NetworkRuntimePlan(selected.map { UserInboundPlan(it.first, it.second, bind, snapshot.allowLan) },
        vpn.allowBypass, vpn.systemProxy, fields)
}
