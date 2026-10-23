package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings

/** A process-local stop receipt; archive preferences cannot create a new running intent. */
data class MaintenanceRuntimeContext(
    val userRequested: Boolean,
    val previousPlan: VpnPlan?,
    val builderSettings: LiteSettings?,
    val ownerToken: Long = 0,
    val intentGeneration: Long = 0,
)

interface ListenerPolicySession : TunnelSession {
    val initialListenerReadback: ListenerReadback? get() = null
    val establishedBuilderSettings: LiteSettings? get() = null
    suspend fun setListening(enabled: Boolean): ListenerReadback
    suspend fun configurePolicies(settings: LiteSettings)
    suspend fun configureNetworkDns(snapshot: dev.aerove.lite.data.SystemDnsSnapshot)
}

data class ListenerReadback(val listening: Boolean, val tunReady: Boolean, val userListenersReady: Boolean,
    val coreEpoch: String, val activePorts: List<Int> = emptyList())
