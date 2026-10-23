package dev.aerove.lite.telemetry

import dev.aerove.lite.Traffic
import dev.aerove.lite.observability.ObservationWindow

enum class ProviderLoadPhase { Initial, Update }
data class ProviderLoaded(val kind: dev.aerove.lite.providers.ProviderKind, val name: String,
    val revision: ULong, val phase: ProviderLoadPhase, val changed: Boolean)

sealed interface CoreTelemetry {
    data class TrafficSample(val value: Traffic) : CoreTelemetry
    data class GroupsChanged(val revision: ULong) : CoreTelemetry
    data class ProvidersLoaded(val providers: List<ProviderLoaded>) : CoreTelemetry
    data class Observations(val value: ObservationWindow) : CoreTelemetry
}
