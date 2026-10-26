package dev.aerove.lite.ui

import androidx.compose.ui.geometry.Rect
import kotlin.math.floor

internal fun dashboardFooterPreferredX(contentWidth: Int, trafficWidth: Int, radius: Int, rightPadding: Int): Int =
    contentWidth + rightPadding - radius - trafficWidth

internal fun dashboardFooterOffsetX(preferredX: Int, maxX: Int, metrics: List<Rect>,
    occupied: List<DashboardClearance>): Int? {
    if (maxX < 0 || preferredX !in 0..maxX) return null
    val bands = metrics.flatMap { metric -> occupied.mapNotNull { mark ->
        mark.bandBounds(metric.top, metric.bottom)?.let { metric to it }
    } }
    var x = preferredX
    while (x in 0..maxX) {
        val collisions = bands.filter { (metric, band) -> metric.left + x < band.right && metric.right + x > band.left }
        if (collisions.isEmpty()) return x
        x = minOf(x - 1, collisions.minOf { (metric, band) -> floor(band.left - metric.right).toInt() })
    }
    return null
}

internal fun dashboardFooterAppends(slot: Int?, fontScale: Float): Boolean = slot == null && fontScale > 1.3f
