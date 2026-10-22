package dev.aerove.lite.optics

import kotlin.math.max
import kotlin.math.min
import kotlin.math.atan2
import kotlin.math.PI

data class OpticsColor(val red: Float, val green: Float, val blue: Float, val alpha: Float) {
    init { require(listOf(red, green, blue, alpha).all { it.isFinite() && it in 0f..1f }) }
    companion object { val Transparent = OpticsColor(0f, 0f, 0f, 0f) }
}

enum class AeroveGlassQuality { Standard, Ultra }

data class AeroveGlassStyle(
    val tint: OpticsColor = OpticsColor.Transparent,
    val contrast: Float = 0f,
    val blur: Float = 2.5f,
    val haze: Float = 0f,
    val hazeBlur: Float = 0f,
    val refraction: Float = 60f,
    val bevel: Float = 20f,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    val rim: Float = 0.6f,
    val rimWidth: Float = 1.3f,
    val rimTail: Float = 0f,
    val rimFocus: Float = 0f,
    val rimAdded: Float = 0f,
    val endShade: Float = 0f,
    val dispersion: Float = 0f,
    val rimBlur: Float = 0f,
    val hairline: Float = 0f,
    val hairlineSide: Float? = null,
) {
    init {
        require(listOf(contrast, blur, haze, hazeBlur, refraction, bevel, saturation, brightness, rim,
            rimWidth, rimTail, rimFocus, rimAdded, endShade, dispersion, rimBlur, hairline, hairlineSide).filterNotNull().all { it.isFinite() })
        require(blur >= 0 && hazeBlur >= 0 && haze in 0f..1f && bevel >= 0 && rimWidth >= 0 && rimBlur >= 0)
    }

    fun forQuality(quality: AeroveGlassQuality): AeroveGlassStyle = if (quality == AeroveGlassQuality.Ultra) this
    else AeroveGlassStyle(tint = tint, blur = max(2.5f, if (haze > 0) hazeBlur else blur), refraction = 0f, bevel = 0f, rim = 0f)

    companion object {
        fun regular(dark: Boolean) = AeroveGlassStyle(rim = 0.28f, rimWidth = 1.2f, rimTail = 0.17f,
            rimFocus = 1f, rimAdded = 0.38f, hairline = if (dark) 0f else 0.25f, hairlineSide = 0.75f)
        fun pill(dark: Boolean) = AeroveGlassStyle(blur = 0f, haze = 0.72f, hazeBlur = 15.6f,
            rim = if (dark) 0.35f else 0.6f, hairline = if (dark) 0f else 0.35f)
        fun lens(dark: Boolean) = AeroveGlassStyle(blur = 0f, refraction = -4.3f, bevel = 5.5f,
            rim = if (dark) 0.3f else 0.6f, rimWidth = 1.1f, endShade = 0.045f,
            dispersion = 6.5f, rimBlur = 1.5f, hairline = 0.42f)
        fun lensContent() = AeroveGlassStyle(blur = 0f, refraction = 14f, bevel = 11.2f,
            rim = 0f, dispersion = 3.6f, rimBlur = 2.5f)
        fun menu(tint: OpticsColor) = AeroveGlassStyle(tint = tint, blur = 12f, rim = 0f)
        fun alert(tint: OpticsColor) = menu(tint).copy(blur = 16f)
    }
}

data class AeroveGlassCapture(
    val widthPixels: Float,
    val heightPixels: Float,
    val density: Float,
    val paddingPixels: Float,
    val capturedBlurSigmaPixels: Float = 0f,
) {
    init {
        require(listOf(widthPixels, heightPixels, density, paddingPixels, capturedBlurSigmaPixels).all { it.isFinite() })
        require(widthPixels >= 1 && heightPixels >= 1 && density > 0 && paddingPixels >= 0 && capturedBlurSigmaPixels >= 0)
    }
}

data class AeroveGlassGlow(
    val centerX: Float = 0f,
    val centerY: Float = 0f,
    val strength: Float = 0f,
    val reach: Float = 1f,
    val wash: Float = 0f,
)

class AeroveGlassShine(turns: FloatArray, lights: FloatArray, ends: FloatArray, shares: FloatArray) {
    val turns = turns.copyOf()
    val lights = lights.copyOf()
    val ends = ends.copyOf()
    val shares = shares.copyOf()
    init {
        require(turns.size == 8 && lights.size == 8 && ends.size == 12 && shares.size == 12)
        require(listOf(turns, lights, ends, shares).all { array -> array.all { it.isFinite() } })
    }
    companion object {
        fun none() = AeroveGlassShine(FloatArray(8), FloatArray(8), FloatArray(12), FloatArray(12))

        fun menu(width: Float, height: Float, radius: Float, strength: Float): AeroveGlassShine {
            require(width > 0 && height > 0 && radius.isFinite() && strength.isFinite())
            val halfWidth = width / 2
            val halfHeight = height / 2
            val round = radius.coerceIn(0f, min(halfWidth, halfHeight))
            val around = arrayOf(floatArrayOf(1.53f, 0f, 0.68f), floatArrayOf(1f, 0f, 0.48f),
                floatArrayOf(0.3f, 0.3f, 0.38f), floatArrayOf(0f, 1f, 0.17f),
                floatArrayOf(0f, 1.5f, 0.07f), floatArrayOf(0f, 2f, 0.03f))
            val turns = FloatArray(8)
            val lights = FloatArray(8)
            lights[0] = strength
            for (i in around.indices) {
                val (along, down, lit) = around[i]
                turns[i + 1] = max(turns[i], atan2(halfWidth - min(along * round, halfWidth), halfHeight - min(down * round, halfHeight)))
                lights[i + 1] = strength * lit
            }
            turns[7] = (PI / 2).toFloat()
            val ends = floatArrayOf(1f / 3, 2f / 3, 1f, 5f / 3, 7f / 3, 3f, 11f / 3, 13f / 3, 5f)
            val shares = floatArrayOf(1f, 0.7f, 0.47f, 0.18f, 0.12f, 0.085f, 0.065f, 0.045f, 0.035f)
            return AeroveGlassShine(turns, lights, FloatArray(12) { ends[min(it, 8)] }, FloatArray(12) { if (it < 9) shares[it] else 0f })
        }
    }
}

enum class AeroveInkRole(val strength: Float) { Title(0.30f), Section(0.62f), Body(0.92f), Small(0.75f), Icon(1f) }

data class AeroveInkStyle(
    val source: OpticsColor,
    val dark: Boolean,
    val role: AeroveInkRole = AeroveInkRole.Body,
    val contrast: Float = if (role == AeroveInkRole.Icon) 3.2f else 4.7f,
    val quality: AeroveGlassQuality = AeroveGlassQuality.Ultra,
    val subtle: Boolean = false,
    val contrastBackground: OpticsColor = OpticsColor.Transparent,
)

data class AeroveInkMapping(
    val fieldWidthPixels: Float,
    val fieldHeightPixels: Float,
    val originX: Float,
    val originY: Float,
    val axisXX: Float,
    val axisXY: Float,
    val axisYX: Float,
    val axisYY: Float,
)
