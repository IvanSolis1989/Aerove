package dev.aerove.lite.ui

import android.graphics.Color
import dev.aerove.lite.MaterialTier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Independent scalar oracle for shaders/foreground_ink.frag, evaluated only against committed hardware PNG pixels.
internal fun expectedInk(source: Int, field: Int, case: RenderCase, icon: Boolean): Int {
    if (!case.aurora) return source
    fun rgb(color: Int) = doubleArrayOf(Color.red(color) / 255.0, Color.green(color) / 255.0, Color.blue(color) / 255.0)
    fun mix(a: DoubleArray, b: DoubleArray, amount: Double) = DoubleArray(3) { a[it] + (b[it] - a[it]) * amount }
    fun luminance(color: DoubleArray): Double {
        val linear = DoubleArray(3) { if (color[it] < 0.04045) color[it] / 12.92 else ((color[it] + 0.055) / 1.055).pow(2.4) }
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
    }
    val background = rgb(field)
    val backgroundLuma = luminance(background)
    val target = (if (icon) 3.2 else 4.7) + if (!case.dark && !icon) 0.1 else 0.0
    fun readable(ink: DoubleArray, alpha: Double): DoubleArray {
        fun contrast(candidate: DoubleArray): Double {
            val actual = luminance(mix(background, candidate, alpha))
            return (max(actual, backgroundLuma) + 0.05) / (min(actual, backgroundLuma) + 0.05)
        }
        if (contrast(ink) >= target) return ink
        val black = (backgroundLuma + 0.05) / 0.05
        val white = 1.05 / (backgroundLuma + 0.05)
        val pole = DoubleArray(3) { if (black > white) 0.0 else 1.0 }
        var low = 0.0
        var high = 1.0
        repeat(8) {
            val mid = (low + high) / 2
            if (contrast(mix(ink, pole, mid)) >= target) high = mid else low = mid
        }
        return mix(ink, pole, high)
    }
    val ultra = case.tier == MaterialTier.Clear
    val peak = background.max()
    val span = peak - background.min()
    var colored = background.copyOf()
    if (span >= 0.015) {
        val hue = DoubleArray(3) { (background[it] - background.min()) / span }
        val cap: Double
        val saturation: Double
        if (case.dark) {
            cap = if (ultra) 0.95 else 0.94
            saturation = min(if (ultra) 0.92 else 0.91, span / max(peak, 0.001) * (if (ultra) 2.70 else 2.65) + if (ultra) 0.175 else 0.17)
        } else {
            for (i in hue.indices) hue[i] = hue[i].pow(if (ultra) 1.22 else 1.20)
            cap = if (ultra) 1.0 else 0.985
            saturation = min(if (ultra) 0.99 else 0.98, span / max(peak, 0.001) * (if (ultra) 3.45 else 3.3) + if (ultra) 0.26 else 0.24)
        }
        colored = mix(DoubleArray(3) { cap }, DoubleArray(3) { hue[it] * cap }, saturation)
    }
    var strength = if (icon) 1.0 else 0.62
    if (case.dark) {
        colored = readable(colored, 1.0)
        strength *= if (ultra) 1.0 else 0.99
    } else strength += (1 - strength) * if (ultra) 0.18 else 0.14
    var ink = mix(rgb(source), colored, strength)
    if (case.tier == MaterialTier.Solid) {
        val neutral = ink[0] * 0.2126 + ink[1] * 0.7152 + ink[2] * 0.0722
        ink = mix(DoubleArray(3) { neutral }, ink, 0.52).map { it * if (case.dark) 0.84 else 1.0 }.toDoubleArray()
    }
    ink = readable(ink, Color.alpha(source) / 255.0)
    return Color.rgb((ink[0] * 255).roundToInt().coerceIn(0, 255), (ink[1] * 255).roundToInt().coerceIn(0, 255), (ink[2] * 255).roundToInt().coerceIn(0, 255))
}
