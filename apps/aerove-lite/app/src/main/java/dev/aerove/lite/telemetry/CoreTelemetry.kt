package dev.aerove.lite.telemetry

import dev.aerove.lite.Traffic

sealed interface CoreTelemetry {
    data class TrafficSample(val value: Traffic) : CoreTelemetry
    data class GroupsChanged(val revision: ULong) : CoreTelemetry
}
