package dev.aerove.lite.ui

import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.Traffic

internal val DashboardStatusMembers = setOf(DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed)
internal val DashboardControlMembers = setOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp)
internal val DashboardOverviewMembers = setOf(DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats)

internal fun dashboardPresentationWidgets(saved: List<DashboardWidget>): List<DashboardWidget> {
    val seen = mutableSetOf<Set<DashboardWidget>>()
    return saved.distinct().filter { widget ->
        val group = when (widget) {
            in DashboardStatusMembers -> DashboardStatusMembers
            in DashboardControlMembers -> DashboardControlMembers
            in DashboardOverviewMembers -> DashboardOverviewMembers
            else -> setOf(widget)
        }
        seen.add(group)
    }
}

internal fun dashboardLanAddress(ip: String?, port: Int, allowLan: Boolean): String? {
    val address = ip?.takeIf { it.isNotBlank() } ?: return null
    if (!allowLan) return address
    if (port !in 1..65535) return null
    return if (':' in address) "[${address.removeSurrounding("[", "]")}]:$port" else "$address:$port"
}

internal data class DashboardCurvePoint(val x: Float, val y: Float)
internal data class DashboardTrafficCurves(val upload: List<DashboardCurvePoint>, val download: List<DashboardCurvePoint>)

internal fun dashboardTrafficCurves(history: List<Traffic>): DashboardTrafficCurves {
    val samples = history.takeLast(30)
    val scale = maxOf(8192L, samples.maxOfOrNull { maxOf(it.uploadPerSecond, it.downloadPerSecond) } ?: 0L).toDouble()
    fun series(value: (Traffic) -> Long): List<DashboardCurvePoint> {
        if (samples.isEmpty()) return emptyList()
        if (samples.size == 1) {
            val y = (0.68 - value(samples.single()).coerceAtLeast(0).toDouble() / scale * 0.42).toFloat()
            return listOf(DashboardCurvePoint(0f, y), DashboardCurvePoint(1f, y))
        }
        return samples.mapIndexed { index, sample -> DashboardCurvePoint(index.toFloat() / samples.lastIndex,
            (0.68 - value(sample).coerceAtLeast(0).toDouble() / scale * 0.42).toFloat()) }
    }
    return DashboardTrafficCurves(series { it.uploadPerSecond }, series { it.downloadPerSecond })
}

/** A card-local view boundary, never an owner or mutator of shared traffic history. */
internal class DashboardTrafficEpisode {
    private var initialized = false
    private var wasActive = false
    private var lastRevision = 0L
    private var baseline = 0L
    private var result: List<Traffic> = emptyList()

    fun samples(history: List<Traffic>, revision: Long, active: Boolean): List<Traffic> {
        require(revision >= 0) { "Invalid traffic history revision" }
        if (initialized && revision == lastRevision && active == wasActive) return result
        if (!initialized) {
            initialized = true
            // Initial active mount may show the actual retained window. Reactivation may not.
            baseline = if (active) revision - history.size else revision
        } else if (!active || !wasActive || revision < lastRevision) baseline = revision
        wasActive = active
        lastRevision = revision
        if (!active) { result = emptyList(); return result }
        val available = if (baseline < 0 && revision > Long.MAX_VALUE + baseline) history.size.toLong()
            else (revision - baseline).coerceAtLeast(0)
        result = history.takeLast(minOf(available, history.size.toLong()).toInt())
        return result
    }
}
