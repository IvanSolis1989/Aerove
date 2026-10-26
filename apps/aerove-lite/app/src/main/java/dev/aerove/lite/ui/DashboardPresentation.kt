package dev.aerove.lite.ui

import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.Traffic

internal val DashboardStatusMembers = setOf(DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed)
internal val DashboardControlMembers = setOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp)
internal val DashboardOverviewMembers = setOf(DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats)

internal fun dashboardPresentationWidgets(saved: List<DashboardWidget>): List<DashboardWidget> {
    val seen = mutableSetOf<Set<DashboardWidget>>()
    return saved.distinct().filter { it != DashboardWidget.NetworkSpeed }.filter { widget ->
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
            val y = (1.0 - value(samples.single()).coerceAtLeast(0).toDouble() / scale * 0.62).toFloat()
            return listOf(DashboardCurvePoint(0f, y), DashboardCurvePoint(1f, y))
        }
        return samples.mapIndexed { index, sample -> DashboardCurvePoint(index.toFloat() / samples.lastIndex,
            (1.0 - value(sample).coerceAtLeast(0).toDouble() / scale * 0.62).toFloat()) }
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
        } else if (!active || revision < lastRevision) baseline = revision
        else if (!wasActive) baseline = lastRevision
        wasActive = active
        lastRevision = revision
        if (!active) { result = emptyList(); return result }
        val available = if (baseline < 0 && revision > Long.MAX_VALUE + baseline) history.size.toLong()
            else (revision - baseline).coerceAtLeast(0)
        result = history.takeLast(minOf(available, history.size.toLong()).toInt())
        return result
    }
}

// Explicit phrase boundaries avoid an orphan Chinese/Japanese character; Latin/Cyrillic words remain whole.
internal fun dashboardControlLabel(label: String): String = label
    .replace("局域网代理", "局域网\u200B代理")
    .replace("局域網代理", "局域網\u200B代理")
    .replace("LANプロキシ", "LAN\u200Bプロキシ")
    .replace("ローカルネットワーク", "ローカル\u200Bネットワーク")

internal fun dashboardLabelBreaks(text: String): List<Int> {
    val hinted = '\u200B' in text
    val boundaries = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(text) }
    fun kind(code: Int): Int = when {
        code == 0x200B -> 1
        Character.isWhitespace(code) -> 1
        code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0x20000..0x3134F -> 2
        code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> 3
        code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> 4
        code in 0x3001..0x303F || code in 0xFF01..0xFF65 -> 5
        else -> 0
    }
    val result = mutableListOf<Int>()
    var previous: Int? = null
    var at = boundaries.first()
    while (at < text.length) {
        val current = kind(text.codePointAt(at))
        if (previous != null && when {
            previous == 1 -> current != 1
            current == 1 || previous == 5 || current == 5 -> false
            previous == 2 && current == 2 -> !hinted
            else -> (previous in 2..4) != (current in 2..4)
        }) result += at
        previous = current
        at = boundaries.next()
        if (at == java.text.BreakIterator.DONE) break
    }
    return result
}

internal fun dashboardBalancedLabel(text: String, width: (String) -> Float): Pair<String, Float>? {
    var best: Pair<String, Float>? = null
    for (at in dashboardLabelBreaks(text)) {
        val first = text.substring(0, at).replace("\u200B", "").trim()
        val second = text.substring(at).replace("\u200B", "").trim()
        if (first.isEmpty() || second.isEmpty()) continue
        val widest = maxOf(width(first), width(second))
        if (best == null || widest <= best.second) best = "$first\n$second" to widest
    }
    return best
}

internal fun interpolateDashboardCurves(from: DashboardTrafficCurves, to: DashboardTrafficCurves, amount: Float): DashboardTrafficCurves {
    val mix = amount.coerceIn(0f, 1f)
    fun y(points: List<DashboardCurvePoint>, x: Float): Float {
        if (points.isEmpty()) return 1f
        val right = points.indexOfFirst { it.x >= x }.let { if (it < 0) points.lastIndex else it }
        if (right == 0) return points[0].y
        val a = points[right - 1]; val b = points[right]
        return a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x).coerceAtLeast(0.000001f))
    }
    fun channel(a: List<DashboardCurvePoint>, b: List<DashboardCurvePoint>): List<DashboardCurvePoint> {
        if (mix == 0f) return a
        if (mix == 1f) return b
        val xs = (a.map { it.x } + b.map { it.x } + listOf(0f, 1f)).distinct().sorted()
        return xs.map { x -> DashboardCurvePoint(x, y(a, x) + (y(b, x) - y(a, x)) * mix) }
    }
    return DashboardTrafficCurves(channel(from.upload, to.upload), channel(from.download, to.download))
}
