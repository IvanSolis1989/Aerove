package dev.aerove.lite

enum class MaterialTier { Solid, Frosted, Clear }
enum class ColorMode { System, Light, Dark }
enum class ProxyMode { Rule, Global, Direct }
enum class VpnPhase { Stopped, Starting, Running, Stopping, Failed }
enum class AppFilterMode { All, Include, Exclude }

data class LiteSettings(
    val material: MaterialTier = MaterialTier.Solid,
    val aurora: Boolean = false,
    val colorMode: ColorMode = ColorMode.System,
    val themeColor: Long = 0xff6b52ae,
    val mode: ProxyMode = ProxyMode.Rule,
    val ipv6: Boolean = false,
    val overrideDns: Boolean = false,
    val dnsServers: List<String> = listOf("https://1.1.1.1/dns-query"),
    val appFilterMode: AppFilterMode = AppFilterMode.All,
    val packages: Set<String> = emptySet(),
    val autoStart: Boolean = false,
    val openLogs: Boolean = false,
)

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
