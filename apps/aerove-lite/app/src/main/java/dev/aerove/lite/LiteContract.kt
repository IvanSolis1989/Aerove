package dev.aerove.lite

import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.localization.canonicalLocale
import dev.aerove.lite.navigation.TabAnimation
import dev.aerove.lite.requests.RequestPreferences
import dev.aerove.lite.proxies.ProxyPresentationPreferences

enum class MaterialTier { Solid, Frosted, Clear }
enum class ColorMode { System, Light, Dark }
enum class ProxyMode { Rule, Global, Direct }
enum class VpnPhase { Stopped, Starting, Running, Stopping, Failed }
enum class AppFilterMode { All, Include, Exclude }
enum class FindProcessMode(val wireName: String) { Strict("strict"), Always("always"), Off("off") }

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

private const val defaultThemeColor = 0xFF007AFFL
private val defaultThemeColors = listOf(
    defaultThemeColor, 0xFFCB30E0L, 0xFFFF2D55L, 0xFFFF383CL,
    0xFFFF8D28L, 0xFFFFCC00L, 0xFF34C759L, 0xFF8E8E93L,
)
private val defaultAuroraColors = listOf(0xFF65BDD2L, 0xFFC589CAL, 0xFF75CCA8L)
private val defaultDashboardWidgets = listOf(
    DashboardWidget.CurrentOutbound,
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
private val retiredDashboardWidgets = setOf("runTime", "overrideDnsButton", "overrideNtpButton")

data class LiteSettings(
    val material: MaterialTier = MaterialTier.Clear,
    val aurora: Boolean = true,
    val colorMode: ColorMode = ColorMode.System,
    val themeColor: Long = defaultThemeColor,
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
    val themeColors: List<Long> = defaultThemeColors.toList(),
    val followSystemColor: Boolean = false,
    val editorFontSize: Int = 16,
    val editorLineWrap: Boolean = false,
    val serviceOrder: List<String> = serviceTargetIds.toList(),
    val disabledServices: Set<String> = emptySet(),
    val autoCheckUpdate: Boolean = true,
    val network: NetworkSettings = NetworkSettings(),
    val fontFamily: String? = null,
    val textScaleEnabled: Boolean = false,
    val textScale: Float = 1f,
    val pureBlack: Boolean = false,
    val locale: String? = null,
    val floatingNavigationBar: Boolean = true,
    val tabAnimation: TabAnimation = TabAnimation.Fade,
    val requests: RequestPreferences = RequestPreferences(),
    val proxyPresentation: ProxyPresentationPreferences = ProxyPresentationPreferences(),
    val dnsPreferences: dev.aerove.lite.dnssettings.DnsPreferences = dev.aerove.lite.dnssettings.DnsPreferences(),
    val geoResources: dev.aerove.lite.resources.GeoResourcePreferences = dev.aerove.lite.resources.GeoResourcePreferences(),
    val historyTables: dev.aerove.lite.observability.HistoryTablePreferences = dev.aerove.lite.observability.HistoryTablePreferences(),
    val backgroundPolicy: dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences = dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences(autoRun = autoStart),
    val appAccess: dev.aerove.lite.appaccess.AppAccessPreferences = dev.aerove.lite.appaccess.AppAccessPreferences(
        enabled = appFilterMode != AppFilterMode.All,
        mode = if (appFilterMode == AppFilterMode.Include) dev.aerove.lite.appaccess.AppAccessMode.Include else dev.aerove.lite.appaccess.AppAccessMode.Exclude,
        includePackages = if (appFilterMode == AppFilterMode.Include) packages else emptySet(),
        excludePackages = if (appFilterMode == AppFilterMode.Exclude) packages else emptySet()),
    val developerMode: Boolean = false,
    val acceptedDisclaimerVersion: Int = 0,
    val unifiedDelay: Boolean = true,
    val tcpConcurrent: Boolean = true,
    val externalController: dev.aerove.lite.controllerprefs.ExternalControllerPreferences = dev.aerove.lite.controllerprefs.ExternalControllerPreferences(),
    val findProcessMode: FindProcessMode = FindProcessMode.Always,
)

val serviceTargetIds: List<String> = listOf("google", "github", "youtube", "chatgpt", "claude", "gemini",
    "netflix", "disney-plus", "prime-video", "spotify", "tiktok", "discord", "bilibili")

fun LiteSettings.enabledServiceIds(): List<String> {
    val ordered = (serviceOrder.filter { it in serviceTargetIds } + serviceTargetIds).distinct()
    return ordered.filterNot { it in disabledServices }.ifEmpty { ordered }
}

fun normalizeDashboardWidgets(names: List<String>?): List<DashboardWidget> = normalizeDashboardWidgetValues(names)

internal fun normalizeDashboardWidgetValues(names: List<*>?): List<DashboardWidget> {
    if (names == null) return defaultDashboardWidgets.toList()
    val kept = names.mapNotNull { value ->
        val name = value as? String ?: return@mapNotNull null
        legacyDashboardWidgets[name] ?: name.takeIf { it == "networkTakeover" || it in dashboardWidgetsByName }
    }
    val migrated = when {
        "currentOutbound" in names -> kept
        kept.isEmpty() && !names.all { it is String && it in retiredDashboardWidgets } ->
            return defaultDashboardWidgets.toList()
        else -> kept
    }
    // Desktop entries participate in Full's migration before Android filtering.
    return migrated.distinct().mapNotNull { dashboardWidgetsByName[it] }.filterNot { it == DashboardWidget.NetworkSpeed }
}

internal fun normalizeAuroraColors(colors: List<*>?): List<Long> {
    if (colors.isNullOrEmpty() || colors.any {
        (it !is Int && it !is Long) || (it as Number).toLong() !in 0..0xFFFFFFFFL
    }) return defaultAuroraColors.toList()
    return colors.take(3).map { (it as Number).toLong() or 0xFF000000L }
}

internal fun normalizeThemeColors(colors: List<*>?): List<Long> {
    if (colors == null || colors.any {
        (it !is Int && it !is Long) || (it as Number).toLong() !in 0..0xFFFFFFFFL
    }) return defaultThemeColors.toList()
    return colors.map { (it as Number).toLong() }.distinct()
}

fun LiteSettings.normalized(): LiteSettings {
    val colors = normalizeThemeColors(themeColors)
    val missingSelection = !followSystemColor && themeColor in 0..0xFFFFFFFFL && themeColor !in colors
    return copy(
        themeColors = colors,
        themeColor = if (missingSelection && defaultThemeColor in colors) defaultThemeColor else themeColor,
        followSystemColor = followSystemColor || (missingSelection && defaultThemeColor !in colors),
        editorFontSize = when (editorFontSize) { 16, 18, 20 -> editorFontSize; else -> 16 },
        auroraColors = normalizeAuroraColors(auroraColors),
        dashboardWidgets = normalizeDashboardWidgets(dashboardWidgets.map { it.wireName }),
        dnsServers = dnsServers.toList(),
        packages = packages.toSet(),
        serviceOrder = (serviceOrder.filter { it in serviceTargetIds } + serviceTargetIds).distinct(),
        disabledServices = disabledServices.filterTo(linkedSetOf()) { it in serviceTargetIds },
        network = network.snapshot(),
        requests = requests.copy(userAgents = requests.userAgents.toList()),
        dnsPreferences = dnsPreferences.snapshot(),
        geoResources = geoResources.copy(urls = geoResources.urls.toMap()),
        backgroundPolicy = backgroundPolicy.copy(excludeSSIDs = backgroundPolicy.excludeSSIDs.toList()),
        appAccess = appAccess.copy(includePackages = appAccess.includePackages.toSet(), excludePackages = appAccess.excludePackages.toSet()),
        fontFamily = fontFamily?.trim()?.takeIf(String::isNotEmpty),
        locale = canonicalLocale(locale),
        textScale = if (textScale.isFinite()) textScale.coerceIn(0.8f, 1.4f) else 1f,
    )
}

fun LiteSettings.resetDashboardWidgets(): LiteSettings = copy(dashboardWidgets = defaultDashboardWidgets.toList())

data class ProfileSummary(
    val id: String,
    val name: String,
    val url: String?,
    val updatedAt: Long,
    val autoUpdate: Boolean = true,
    val updateIntervalMillis: Long = 86_400_000L,
    val lastSuccessfulUpdateAt: Long? = null,
    val sourceIdentity: String = "",
    val subscriptionUserInfo: String? = null,
    val contentDisposition: String? = null,
    val custom: Boolean = false,
    val currentProxyGroup: String? = null,
    val unfoldedProxyGroups: Set<String> = emptySet(),
)

data class ProxyNode(val name: String, val type: String, val delayMillis: Int? = null)
data class ProxyGroup(val name: String, val type: String, val selected: String, val nodes: List<ProxyNode>)
data class ConnectionInfo(
    val id: String,
    val destination: String,
    val network: String,
    val rule: String,
    val chains: List<String>,
    val upload: Long?,
    val download: Long?,
    val sourceIP: String? = null,
    val sourcePort: Int? = null,
    val destinationIP: String? = null,
    val destinationPort: Int? = null,
    val host: String? = null,
    val startedAt: java.time.Instant? = null,
    val inboundType: String? = null,
    val process: String? = null,
    val processPath: String? = null,
    val uid: Long? = null,
    val rulePayload: String? = null,
    val country: String? = null,
    val asn: String? = null,
    val inboundUser: String? = null,
    // Host snapshot receipt time, not the core's creation time; preserves idle samples in StateFlow.
    val observedAtNanos: Long? = null,
)
data class Traffic(val uploadPerSecond: Long = 0, val downloadPerSecond: Long = 0, val upload: Long = 0, val download: Long = 0)

data class DashboardCounts(val connectionCount: Int? = null, val requestCount: Long? = null,
    val dnsCount: Long? = null, val historyTruncated: Boolean = false)
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
    val settingsError: Boolean = false,
    val message: String? = null,
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val updatingProfileIds: Set<String> = emptySet(),
    val updatePromptVisible: Boolean = false,
    val networkRestartRequired: Boolean = false,
    val networkError: String? = null,
    val listening: Boolean = false,
    val suspended: Boolean = false,
    val listenerError: String? = null,
    val initialized: Boolean = false,
    val locationGranted: Boolean = false,
    val batteryIgnored: Boolean = false,
    val externalProfile: String? = null,
    val profileImportVisible: Boolean = false,
    val profileImportKeyPrompt: Boolean = false,
    val trafficHistory: List<Traffic> = emptyList(),
    val dashboardCounts: DashboardCounts = DashboardCounts(),
    val trafficHistoryRevision: Long = 0,
)

interface LiteActions {
    fun connect()
    fun disconnect()
    fun importFile()
    fun addSubscription(name: String, url: String)
    fun updateProfile(id: String)
    fun updateProfileMetadata(id: String, name: String, url: String?, autoUpdate: Boolean, intervalMillis: Long)
    fun cancelProfileUpdate(id: String)
    fun selectProfile(id: String)
    fun deleteProfile(id: String)
    fun selectProxy(group: String, proxy: String)
    fun testGroup(group: String)
    fun refreshOutbound()
    fun retryNetworkSettings()
    fun refreshServiceChecks()
    fun refreshServiceCheck(id: String)
    fun setServiceTelemetryTargets(ids: Set<String>)
    fun setProviderTelemetryActive(active: Boolean)
    fun setMemoryTelemetryActive(active: Boolean)
    fun setConnectionTelemetryActive(active: Boolean)
    fun setDashboardTelemetryActive(active: Boolean)
    fun setStatusTrafficActive(active: Boolean) {}
    fun closeConnection(id: String)
    fun closeAllConnections()
    fun shuffleAurora()
    fun changeSettings(settings: LiteSettings)
    fun changeHistoryTable(change: dev.aerove.lite.observability.HistoryTableChange)
    fun loadInstalledApps()
    fun clearLogs()
    fun dismissMessage()
    fun requestOnDemandLocation()
    fun requestBatteryExemption()
    fun authorizeApplications()
    fun acceptExternalProfile()
    fun dismissExternalProfile()
    fun declineDisclaimer()
}
