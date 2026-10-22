package dev.aerove.lite.ui

import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteSettings

internal class UiTestActions(private val onSettings: (LiteSettings) -> Unit) : LiteActions {
    val events = mutableListOf<String>()
    val settings = mutableListOf<LiteSettings>()
    val connectionTelemetry = mutableListOf<Boolean>()
    val memoryTelemetry = mutableListOf<Boolean>()
    val serviceTelemetry = mutableListOf<Set<String>>()
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
        this.settings += settings
        events += "changeSettings"
        onSettings(settings)
    }
    override fun loadInstalledApps() { events += "loadInstalledApps" }
    override fun clearLogs() { events += "clearLogs" }
    override fun refreshOutbound() { events += "refreshOutbound" }
    override fun retryNetworkSettings() { events += "retryNetworkSettings" }
    override fun refreshServiceChecks() { events += "refreshServiceChecks" }
    override fun refreshServiceCheck(id: String) { events += "refreshServiceCheck:$id" }
    override fun setServiceTelemetryTargets(ids: Set<String>) { serviceTelemetry += ids.toSet() }
    override fun setMemoryTelemetryActive(active: Boolean) { memoryTelemetry += active }
    override fun shuffleAurora() { events += "shuffleAurora" }
    override fun setConnectionTelemetryActive(active: Boolean) { connectionTelemetry += active }
    override fun dismissMessage() { events += "dismissMessage" }
}
