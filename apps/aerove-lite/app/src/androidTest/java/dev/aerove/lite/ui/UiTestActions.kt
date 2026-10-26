package dev.aerove.lite.ui

import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.observability.HistoryTableChange

internal class UiTestActions(private val onSettings: (LiteSettings) -> Unit,
    initialSettings: LiteSettings) : LiteActions {
    constructor(onSettings: (LiteSettings) -> Unit) : this(onSettings, LiteSettings())
    val events = mutableListOf<String>()
    val windowFocus = mutableListOf<Boolean>()
    val telemetryInteraction = mutableListOf<Boolean>()
    override fun setUiWindowFocused(focused: Boolean) { windowFocus += focused }
    override fun setTelemetryInteractionActive(active: Boolean) { telemetryInteraction += active }
    val dashboardTelemetry = mutableListOf<Boolean>()
    val settings = mutableListOf<LiteSettings>()
    val connectionTelemetry = mutableListOf<Boolean>()
    val memoryTelemetry = mutableListOf<Boolean>()
    val providerTelemetry = mutableListOf<Boolean>()
    val serviceTelemetry = mutableListOf<Set<String>>()
    private var currentSettings = initialSettings
    override fun connect() { events += "connect" }
    override fun disconnect() { events += "disconnect" }
    override fun importFile() { events += "importFile" }
    override fun addSubscription(name: String, url: String) { events += "addSubscription:$name:$url" }
    override fun updateProfile(id: String) { events += "updateProfile:$id" }
    override fun updateProfileMetadata(id: String, name: String, url: String?, autoUpdate: Boolean, intervalMillis: Long) {
        events += "updateProfileMetadata:$id:$name:$url:$autoUpdate:$intervalMillis"
    }
    override fun cancelProfileUpdate(id: String) { events += "cancelProfileUpdate:$id" }
    override fun selectProfile(id: String) { events += "selectProfile:$id" }
    override fun deleteProfile(id: String) { events += "deleteProfile:$id" }
    override fun selectProxy(group: String, proxy: String) { events += "selectProxy:$group:$proxy" }
    override fun testGroup(group: String) { events += "testGroup:$group" }
    override fun closeConnection(id: String) { events += "closeConnection:$id" }
    override fun closeAllConnections() { events += "closeAllConnections" }
    override fun changeSettings(settings: LiteSettings) {
        currentSettings = settings
        this.settings += settings
        events += "changeSettings"
        onSettings(settings)
    }
    override fun saveDnsPreferences(preferences: dev.aerove.lite.dnssettings.DnsPreferences): Long =
        error("Unused UI fixture DNS preferences save; provide an explicit receipt owner")
    override fun changeHistoryTable(change: HistoryTableChange) {
        val updated = currentSettings.copy(historyTables = currentSettings.historyTables.apply(change))
        currentSettings = updated
        settings += updated
        events += "changeHistoryTable"
        onSettings(updated)
    }
    override fun loadInstalledApps() { events += "loadInstalledApps" }
    override fun clearLogs() { events += "clearLogs" }
    override fun refreshOutbound() { events += "refreshOutbound" }
    override fun retryNetworkSettings() { events += "retryNetworkSettings" }
    override fun refreshServiceChecks() { events += "refreshServiceChecks" }
    override fun refreshServiceCheck(id: String) { events += "refreshServiceCheck:$id" }
    override fun setServiceTelemetryTargets(ids: Set<String>) { serviceTelemetry += ids.toSet() }
    override fun setDashboardTelemetryActive(active: Boolean) { dashboardTelemetry += active }
    override fun setProviderTelemetryActive(active: Boolean) { providerTelemetry += active }
    override fun setMemoryTelemetryActive(active: Boolean) { memoryTelemetry += active }
    override fun shuffleAurora() { events += "shuffleAurora" }
    override fun setConnectionTelemetryActive(active: Boolean) { connectionTelemetry += active }
    override fun dismissMessage() { events += "dismissMessage" }
    override fun requestOnDemandLocation(): Unit = error("Unused UI fixture on-demand location request")
    override fun requestBatteryExemption(): Unit = error("Unused UI fixture battery exemption request")
    override fun authorizeApplications(): Unit = error("Unused UI fixture application authorization")
    override fun acceptExternalProfile(): Unit = error("Unused UI fixture external profile acceptance")
    override fun dismissExternalProfile(): Unit = error("Unused UI fixture external profile dismissal")
    override fun declineDisclaimer(): Unit = error("Unused UI fixture disclaimer decline")
}
