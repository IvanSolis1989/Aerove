package dev.aerove.lite.ui

import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal data class PixelDelta(val maximum: Int, val mean: Double, val count: Int) {
    fun json() = JSONObject().put("maximumChannelError", maximum).put("meanChannelError", mean).put("samples", count)
}

internal fun channels(color: Int) = intArrayOf(Color.red(color), Color.green(color), Color.blue(color))

internal fun channelError(first: Int, second: Int): Int = maxOf(
    abs(Color.red(first) - Color.red(second)),
    abs(Color.green(first) - Color.green(second)),
    abs(Color.blue(first) - Color.blue(second)),
    abs(Color.alpha(first) - Color.alpha(second)),
)

internal fun comparePixels(first: CapturedFrame, second: CapturedFrame, region: Rect, exclude: Rect? = null): PixelDelta {
    var maximum = 0
    var total = 0L
    var samples = 0
    for (y in ceil(region.top).toInt() until floor(region.bottom).toInt()) {
        for (x in ceil(region.left).toInt() until floor(region.right).toInt()) {
            if (exclude != null && x >= exclude.left && x < exclude.right && y >= exclude.top && y < exclude.bottom) continue
            val error = channelError(first.pixel(x.toFloat(), y.toFloat()), second.pixel(x.toFloat(), y.toFloat()))
            maximum = max(maximum, error)
            total += error
            samples++
        }
    }
    assertTrue("An empty pixel region is not evidence", samples > 0)
    return PixelDelta(maximum, total.toDouble() / samples, samples)
}

internal fun assertViewport(probe: RenderProbe) {
    val root = requireNotNull(probe.bounds["root"])
    assertTrue("Use a portrait viewport at least 300 x 510 dp; the entire optical fixture must be visible", root.width >= 300 * probe.density && root.height >= 510 * probe.density)
}

internal fun assertCorners(source: CapturedFrame, actual: CapturedFrame, probe: RenderProbe, outlineMask: CapturedFrame = actual) {
    val pane = requireNotNull(probe.bounds["pane"])
    val inset = 2 * probe.density
    for (x in listOf(pane.left + inset, pane.right - inset)) {
        for (y in listOf(pane.top + inset, pane.bottom - inset)) {
            assertTrue("The superellipse exterior must expose the original background", channelError(source.pixel(x, y), actual.pixel(x, y)) <= 2)
        }
    }
    if (channelError(source.pixel(pane.left + 6 * probe.density, pane.top + 6 * probe.density), probe.surface) > 20) {
        assertTrue("The actual RSuperellipse outline must fill its interior; transparent optical tint cannot serve as a shape mask", channelError(source.pixel(pane.left + 6 * probe.density, pane.top + 6 * probe.density), outlineMask.pixel(pane.left + 6 * probe.density, pane.top + 6 * probe.density)) > 3)
    }
}

internal data class LineProfile(val positions: DoubleArray, val values: DoubleArray) {
    val contrast: Double get() = values.max() - values.min()
    private val measuredCentroid: Double? get() {
        val baseline = values.min()
        val cutoff = contrast * 0.20
        var weight = 0.0
        var moment = 0.0
        values.indices.forEach { i ->
            val amount = (values[i] - baseline - cutoff).coerceAtLeast(0.0)
            weight += amount
            moment += positions[i] * amount
        }
        return if (weight > 0) moment / weight else null
    }
    val centroid: Double get() = requireNotNull(measuredCentroid) { "Marker profile has no measurable contrast" }
    fun json() = JSONObject().put("positionDp", JSONArray(positions.toList())).put("channelValue", JSONArray(values.toList()))
        .put("contrast", contrast).put("centroidDp", measuredCentroid ?: JSONObject.NULL)
}

internal fun lineProfile(
    frame: CapturedFrame,
    probe: RenderProbe,
    x: Float,
    yStart: Float,
    yEnd: Float,
    halfWidthDp: Float = 8f,
    channel: Int = 1,
    originY: Float = yStart,
): LineProfile {
    val from = ceil(yStart).toInt()
    val to = floor(yEnd).toInt()
    val left = ceil(x - halfWidthDp * probe.density).toInt()
    val right = floor(x + halfWidthDp * probe.density).toInt()
    assertTrue("Marker strip must contain physical pixels", to > from && right > left)
    return LineProfile(
        DoubleArray(to - from) { (from + it + 0.5 - originY) / probe.density },
        DoubleArray(to - from) { row ->
            (left until right).sumOf { px -> channels(frame.pixel(px.toFloat(), (from + row).toFloat()))[channel].toDouble() } / (right - left)
        },
    )
}

internal fun cardOptics(source: CapturedFrame, actual: CapturedFrame, control: CapturedFrame, probe: RenderProbe, case: RenderCase): JSONObject {
    val pane = requireNotNull(probe.bounds["pane"])
    val density = probe.density
    val strip = Rect(pane.center.x - 16 * density, pane.top + 0.5f * density, pane.center.x + 16 * density, pane.bottom - 0.5f * density)
    val comparison = comparePixels(actual, control, strip)
    val report = JSONObject().put("productionVsNoLens", comparison.json())
    when (case.tier) {
        MaterialTier.Solid -> {
            assertTrue("Solid must match the opaque, filter-free hardware control", comparison.maximum <= 2)
            val center = actual.pixel(pane.center.x, pane.center.y)
            assertTrue("Solid surface is not opaque", channelError(center, probe.surface) <= 2)
        }
        MaterialTier.Frosted -> {
            assertTrue("Frosted must equal blur-only rendering including its edge; no lens, rim or gloss", comparison.maximum <= 2)
            val original = lineProfile(source, probe, pane.center.x, pane.top, pane.top + 30 * density)
            val blurred = lineProfile(actual, probe, pane.center.x, pane.top, pane.top + 30 * density)
            report.put("sourceTop", original.json()).put("frostedTop", blurred.json())
            assertTrue("The fixture marker must be present in the original GPU image", original.contrast > 80)
            assertTrue("Frosted must actually suppress high-frequency background detail", blurred.contrast < original.contrast * 0.25)
        }
        MaterialTier.Clear -> {
            assertTrue("Clear must differ from the non-refracting transparency control", comparison.maximum > 30)
            for (bottom in listOf(false, true)) {
                val start = if (bottom) pane.bottom - 30 * density else pane.top
                val end = if (bottom) pane.bottom else pane.top + 30 * density
                val original = lineProfile(source, probe, pane.center.x, start, end, halfWidthDp = 1f)
                val refracted = lineProfile(actual, probe, pane.center.x, start, end, halfWidthDp = 1f)
                val flat = lineProfile(control, probe, pane.center.x, start, end, halfWidthDp = 1f)
                val outward = (refracted.centroid - original.centroid) * if (bottom) 1 else -1
                report.put(if (bottom) "bottom" else "top", JSONObject()
                    .put("source", original.json()).put("clear", refracted.json()).put("noLens", flat.json()).put("outwardDisplacementDp", outward))
                assertTrue("Missing original marker", original.contrast > 80)
                assertTrue("Clear must move the background marker outward by at least 1 dp; blur/transparency cannot satisfy this", outward >= 1.0)
                assertTrue("Clear must retain sharp marker contrast, rather than substitute blur", refracted.contrast >= original.contrast * 0.50)
                assertTrue("The no-lens control must not move the marker", abs(flat.centroid - original.centroid) <= 0.25)
                val red = lineProfile(actual, probe, pane.center.x, start, end, halfWidthDp = 1f, channel = 0)
                val blue = lineProfile(actual, probe, pane.center.x, start, end, halfWidthDp = 1f, channel = 2)
                val dispersion = (red.centroid - blue.centroid) * if (bottom) 1 else -1
                report.getJSONObject(if (bottom) "bottom" else "top").put("red", red.json()).put("blue", blue.json()).put("dispersionDp", dispersion)
                assertTrue("Clear must provide spatial color dispersion, not a uniform colored outline", dispersion >= 0.15)
            }
        }
    }
    return report
}

internal fun blend8(background: Int, foreground: Int, alpha: Float): Int {
    val bg = channels(background)
    val fg = channels(foreground)
    return Color.rgb(
        (bg[0] + (fg[0] - bg[0]) * alpha).roundToInt(),
        (bg[1] + (fg[1] - bg[1]) * alpha).roundToInt(),
        (bg[2] + (fg[2] - bg[2]) * alpha).roundToInt(),
    )
}

internal fun switchCompositing(source: CapturedFrame, actual: CapturedFrame, control: CapturedFrame, probe: RenderProbe, case: RenderCase): JSONObject {
    val bounds = requireNotNull(probe.bounds["switch"])
    val d = probe.density
    val controlAlpha = if (case.enabled || case.tier != MaterialTier.Solid) 1f else 0.48f
    val report = JSONObject()
    for (thumb in listOf(false, true)) {
        val x = bounds.left + (if (thumb) { if (case.checked) 40 else 20 } else { if (case.checked) 22 else 38 }) * d
        // Keep the checked glyph out of the plane compositing measurement.
        val y = bounds.top + (if (thumb) 16 else 24) * d
        val background = source.pixel(x, y)
        val track = probe.switchTrack
        val plane = if (thumb) probe.switchThumb else track
        val expected = if (case.tier != MaterialTier.Solid) control.pixel(x, y) else blend8(background, plane, controlAlpha)
        val observed = actual.pixel(x, y)
        val error = channelError(expected, observed)
        report.put(if (thumb) "thumb" else "track", JSONObject().put("expectedRgba8", expected).put("actualRgba8", observed).put("maximumChannelError", error)
            .put("reference", when (case.tier) {
                MaterialTier.Frosted -> "Hardware Gaussian blur-only control; includes finite track boundary"
                MaterialTier.Clear -> "Hardware Full-style reference with refraction disabled, retaining the same tint and rim"
                MaterialTier.Solid -> "RGBA8 compositing of measured hardware background"
            }))
        assertTrue("${case.tier} ${if (thumb) "thumb" else "track"} must composite independently at rest (error=$error)", error <= 2)
    }
    return report
}
