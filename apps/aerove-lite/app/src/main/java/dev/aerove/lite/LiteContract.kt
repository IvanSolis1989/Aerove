package dev.aerove.lite

enum class MaterialTier { Solid, Frosted, Clear }
enum class ColorMode { System, Light, Dark }
enum class ProxyMode { Rule, Global, Direct }
enum class VpnPhase { Stopped, Starting, Running, Stopping, Failed }
enum class AppFilterMode { All, Include, Exclude }

enum class ColorStyle(val wireName: String) {
    Vivid("vivid"), Tinted("tinted"), Soft("soft"), Monochrome("monochrome"),
}

enum class DashboardWidget(val wireName: String) {
    CurrentOutbound("currentOutbound"),
    NetworkSpeed("networkSpeed"),
    OutboundMode("outboundMode"),
    VpnButton("vpnButton"),
    ServiceStatus("serviceStatus"),
    Profiles("profiles"),
    ProxyGroups("proxyGroups"),
    ConnectionStats("connectionStats"),
    IntranetIp("intranetIp"),
    MemoryInfo("memoryInfo"),
}

private val defaultAuroraColors = listOf(0xFF65BDD2L, 0xFFC589CAL, 0xFF75CCA8L)
private val defaultDashboardWidgets = listOf(
    DashboardWidget.CurrentOutbound,
    DashboardWidget.NetworkSpeed,
    DashboardWidget.OutboundMode,
    DashboardWidget.VpnButton,
    DashboardWidget.IntranetIp,
    DashboardWidget.ServiceStatus,
    DashboardWidget.Profiles,
    DashboardWidget.ProxyGroups,
    DashboardWidget.ConnectionStats,
)
private val dashboardWidgetsByName = DashboardWidget.entries.associateBy { it.wireName }
private val legacyDashboardWidgets = mapOf(
    "networkDetection" to "intranetIp",
    "outboundModeV2" to "outboundMode",
    "systemProxyButton" to "networkTakeover",
    "tunButton" to "networkTakeover",
    "connections" to "connectionStats",
    "requests" to "connectionStats",
    "dnsQueries" to "connectionStats",
    "trafficUsage" to "networkSpeed",
)
private val legacyDefaultDashboardWidgets = setOf(
    "networkSpeed", "systemProxyButton", "tunButton", "outboundMode",
    "networkDetection", "trafficUsage", "intranetIp",
)
private val retiredDashboardWidgets = setOf("runTime", "overrideDnsButton", "overrideNtpButton")

data class LiteSettings(
    val material: MaterialTier = MaterialTier.Clear,
    val aurora: Boolean = true,
    val colorMode: ColorMode = ColorMode.System,
    val themeColor: Long = 0xFF007AFFL,
    val mode: ProxyMode = ProxyMode.Rule,
    val ipv6: Boolean = false,
    val overrideDns: Boolean = false,
    val dnsServers: List<String> = listOf("https://1.1.1.1/dns-query"),
    val appFilterMode: AppFilterMode = AppFilterMode.All,
    val packages: Set<String> = emptySet(),
    val autoStart: Boolean = false,
    val openLogs: Boolean = false,
    val colorStyle: ColorStyle = ColorStyle.Vivid,
    val auroraColors: List<Long> = defaultAuroraColors.toList(),
    val auroraSeed: Long = 271828,
    val auroraRotationDay: Long = 0,
    val dashboardWidgets: List<DashboardWidget> = defaultDashboardWidgets.toList(),
)

fun normalizeDashboardWidgets(names: List<String>?): List<DashboardWidget> = normalizeDashboardWidgetValues(names)

internal fun normalizeDashboardWidgetValues(names: List<*>?): List<DashboardWidget> {
    if (names == null) return defaultDashboardWidgets.toList()
    val kept = names.mapNotNull { value ->
        val name = value as? String ?: return@mapNotNull null
        legacyDashboardWidgets[name] ?: name.takeIf { it == "networkTakeover" || it in dashboardWidgetsByName }
    }
    val migrated = when {
        "currentOutbound" in names -> kept
        names.map { if (it == "outboundModeV2") "outboundMode" else it }.toSet() == legacyDefaultDashboardWidgets ->
            return defaultDashboardWidgets.toList()
        kept.isEmpty() && !names.all { it is String && it in retiredDashboardWidgets } ->
            return defaultDashboardWidgets.toList()
        else -> listOf("currentOutbound") + kept
    }
    // Desktop entries participate in Full's migration before Android filtering.
    return migrated.distinct().mapNotNull { dashboardWidgetsByName[it] }
}

internal fun normalizeAuroraColors(colors: List<*>?): List<Long> {
    if (colors.isNullOrEmpty() || colors.any {
        (it !is Int && it !is Long) || (it as Number).toLong() !in 0..0xFFFFFFFFL
    }) return defaultAuroraColors.toList()
    return colors.take(3).map { (it as Number).toLong() or 0xFF000000L }
}

fun LiteSettings.normalized(): LiteSettings = copy(
    auroraColors = normalizeAuroraColors(auroraColors),
    dashboardWidgets = normalizeDashboardWidgets(dashboardWidgets.map { it.wireName }),
    dnsServers = dnsServers.toList(),
    packages = packages.toSet(),
)

fun LiteSettings.resetDashboardWidgets(): LiteSettings = copy(dashboardWidgets = defaultDashboardWidgets.toList())

data class ProfileSummary(
    val id: String,
    val name: String,
    val url: String?,
    val updatedAt: Long,
)

data class ProxyNode(val name: String, val type: String, val delayMillis: Int? = null)
data class ProxyGroup(val name: String, val type: String, val selected: String, val nodes: List<ProxyNode>)
data class ConnectionInfo(
    val id: String,
    val destination: String,
    val network: String,
    val rule: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
)
data class Traffic(val uploadPerSecond: Long = 0, val downloadPerSecond: Long = 0, val upload: Long = 0, val download: Long = 0)
data class LogLine(val time: Long, val level: String, val message: String)
data class InstalledApp(val packageName: String, val label: String)

data class OutboundIdentity(
    val address: String,
    val countryCode: String? = null,
    val organization: String? = null,
    val kind: String? = null,
)

data class LiteState(
    val phase: VpnPhase = VpnPhase.Stopped,
    val settings: LiteSettings = LiteSettings(),
    val profiles: List<ProfileSummary> = emptyList(),
    val selectedProfile: String? = null,
    val groups: List<ProxyGroup> = emptyList(),
    val connections: List<ConnectionInfo> = emptyList(),
    val traffic: Traffic = Traffic(),
    val outboundIdentity: OutboundIdentity? = null,
    val outboundLoading: Boolean = false,
    val outboundError: String? = null,
    val localAddress: String? = null,
    val uptimeMillis: Long = 0,
    val logs: List<LogLine> = emptyList(),
    val installedApps: List<InstalledApp> = emptyList(),
    val busy: Set<String> = emptySet(),
    val message: String? = null,
)

interface LiteActions {
    fun connect()
    fun disconnect()
    fun importFile()
    fun addSubscription(name: String, url: String)
    fun updateProfile(id: String)
    fun selectProfile(id: String)
    fun deleteProfile(id: String)
    fun selectProxy(group: String, proxy: String)
    fun testGroup(group: String)
    fun refreshOutbound()
    fun setConnectionTelemetryActive(active: Boolean)
    fun closeConnection(id: String)
    fun closeAllConnections()
    fun changeSettings(settings: LiteSettings)
    fun loadInstalledApps()
    fun clearLogs()
    fun dismissMessage()
}
