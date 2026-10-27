package dev.aerove.lite.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

internal data class SpatialFit(
    val shiftPx: Double, val rmse: Double, val zeroShiftRmse: Double,
    val gain: Double, val offset: Double, val adjacentGridRmseGap: Double,
)

internal data class ChannelSpatialDiagnostic(
    val full: SpatialFit, val rising: SpatialFit, val falling: SpatialFit,
    val referenceRisingEnergy: Double, val referenceFallingEnergy: Double,
    val flankShiftDisagreementPx: Double,
)

internal data class ChromaticSpatialResult(
    val red: ChannelSpatialDiagnostic, val blue: ChannelSpatialDiagnostic,
    val signedRedBlueDp: Double, val greenBandTouchesStart: Boolean, val greenBandTouchesEnd: Boolean,
)

/** Pixel arrays only. This estimator has no access to pose, lens parameters or metadata labels. */
internal fun chromaticSpatialDiagnostic(rgb: List<DoubleArray>, density: Double, bottom: Boolean): ChromaticSpatialResult {
    require(rgb.size == 3 && density.isFinite() && density > 0)
    val n = rgb[1].size
    require(n > 4 && rgb.all { it.size == n && it.all(Double::isFinite) })
    val green = rgb[1]
    fun channel(values: DoubleArray): ChannelSpatialDiagnostic {
        fun gradient(v: DoubleArray, sign: Double) = DoubleArray(v.size - 1) { max(0.0, sign * (v[it + 1] - v[it])) }
        val up = gradient(green, 1.0)
        val down = gradient(green, -1.0)
        val rising = spatialFit(up, gradient(values, 1.0), offsetAllowed = false)
        val falling = spatialFit(down, gradient(values, -1.0), offsetAllowed = false)
        return ChannelSpatialDiagnostic(spatialFit(green, values, offsetAllowed = true), rising, falling,
            up.sum(), down.sum(), abs(rising.shiftPx - falling.shiftPx))
    }
    val red = channel(rgb[0])
    val blue = channel(rgb[2])
    // The existing 20% marker support is reported only to expose truncation, never to remove pixels.
    val cutoff = green.min() + (green.max() - green.min()) * 0.20
    return ChromaticSpatialResult(red, blue, (red.full.shiftPx - blue.full.shiftPx) * (if (bottom) 1 else -1) / density,
        green.first() > cutoff, green.last() > cutoff)
}

private fun spatialFit(reference: DoubleArray, moving: DoubleArray, offsetAllowed: Boolean): SpatialFit {
    data class Score(val shift: Double, val rmse: Double, val gain: Double, val offset: Double)
    fun sample(position: Double): Double {
        val p = position.coerceIn(0.0, reference.lastIndex.toDouble())
        val left = floor(p).toInt()
        val right = (left + 1).coerceAtMost(reference.lastIndex)
        return reference[left] + (reference[right] - reference[left]) * (p - left)
    }
    val scores = (-128..128).map { grid ->
        val shift = grid / 16.0
        val x = DoubleArray(reference.size) { sample(it - shift) }
        val meanX = if (offsetAllowed) x.average() else 0.0
        val meanY = if (offsetAllowed) moving.average() else 0.0
        var xx = 0.0
        var xy = 0.0
        for (i in x.indices) { xx += (x[i] - meanX) * (x[i] - meanX); xy += (x[i] - meanX) * (moving[i] - meanY) }
        val gain = if (xx > 0) max(0.0, xy / xx) else 0.0
        val offset = meanY - gain * meanX
        var error = 0.0
        for (i in x.indices) { val residual = moving[i] - (gain * x[i] + offset); error += residual * residual }
        Score(shift, sqrt(error / x.size), gain, offset)
    }
    // Prefer the zero/smaller solution in a flat objective rather than inventing motion at the search boundary.
    val sorted = scores.sortedWith(compareBy<Score> { it.rmse }.thenBy { abs(it.shift) })
    val best = sorted[0]
    return SpatialFit(best.shift, best.rmse, scores[128].rmse, best.gain, best.offset, sorted[1].rmse - best.rmse)
}
