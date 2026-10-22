package dev.aerove.lite.optics

import android.content.res.AssetManager
import android.graphics.Shader
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader

class AeroveOpticsPrograms private constructor(val glassSource: String, val inkSource: String) {
    companion object {
        fun load(assets: AssetManager): AeroveOpticsPrograms = AeroveOpticsPrograms(
            assets.open("optics/liquid_glass.agsl").bufferedReader().use { it.readText() },
            assets.open("optics/foreground_ink.agsl").bufferedReader().use { it.readText() },
        )
    }
}

fun RuntimeShader.configureAeroveGlass(
    geometry: AeroveSuperellipseGeometry,
    style: AeroveGlassStyle,
    capture: AeroveGlassCapture,
    hazeShader: Shader? = null,
    glow: AeroveGlassGlow = AeroveGlassGlow(),
    profile: FloatArray? = null,
    shine: AeroveGlassShine = AeroveGlassShine.none(),
) {
    require(geometry.width > 0 && geometry.height > 0)
    require(profile == null || (profile.size == 64 && profile.all { it.isFinite() && it > 0 }))
    require(style.haze == 0f || hazeShader != null) { "Haze requires a separately blurred backdrop shader" }
    val step = 1f / capture.density
    val origin = -capture.paddingPixels * step
    setFloatUniform("uSize", capture.widthPixels, capture.heightPixels)
    setFloatUniform("uFrame", origin, origin, step, step)
    setFloatUniform("uSample", origin, origin, step, step)
    setFloatUniform("uClamp", 0.5f, 0.5f, capture.widthPixels - 0.5f, capture.heightPixels - 0.5f)
    setFloatUniform("uTexels", step, capture.capturedBlurSigmaPixels)
    setFloatUniform("uHazeSample", origin, origin, step, step)
    setFloatUniform("uHazeClamp", 0.5f, 0.5f, capture.widthPixels - 0.5f, capture.heightPixels - 0.5f)
    setFloatUniform("uHaze", style.haze)
    setFloatUniform("uShape", geometry.width.toFloat(), geometry.height.toFloat(), style.bevel, style.blur)
    setFloatUniform("uCorners", geometry.radii.map { it.toFloat() }.toFloatArray())
    setFloatUniform("uCurves", geometry.curveUniforms())
    setFloatUniform("uQuadrants", geometry.quadrantUniforms())
    setFloatUniform("uOptics", style.refraction, style.saturation, style.brightness)
    setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
    setFloatUniform("uLight", style.rim, style.rimWidth, style.endShade)
    setFloatUniform("uGlow", glow.centerX, glow.centerY, glow.strength, style.contrast)
    setFloatUniform("uGlowSpread", glow.reach, glow.wash)
    setFloatUniform("uEdge", style.hairline, style.dispersion, style.rimBlur)
    setFloatUniform("uRim", style.rimTail, style.rimFocus, style.rimAdded, style.hairlineSide ?: style.hairline)
    setFloatUniform("uOutlined", if (profile == null) 0f else 1f)
    setFloatUniform("uProfile", profile ?: FloatArray(64))
    setFloatUniform("uShineTurns", shine.turns)
    setFloatUniform("uShineLit", shine.lights)
    setFloatUniform("uShineEnds", shine.ends)
    setFloatUniform("uShineShares", shine.shares)
    if (hazeShader != null) asAndroidRuntimeShader().setInputShader("hazeContent", hazeShader)
}

fun RuntimeShader.configureAeroveInk(style: AeroveInkStyle, mapping: AeroveInkMapping, field: Shader) {
    require(mapping.fieldWidthPixels >= 1 && mapping.fieldHeightPixels >= 1)
    setFloatUniform("uFieldSize", mapping.fieldWidthPixels, mapping.fieldHeightPixels)
    setFloatUniform("uOrigin", mapping.originX, mapping.originY)
    setFloatUniform("uAxisX", mapping.axisXX, mapping.axisXY)
    setFloatUniform("uAxisY", mapping.axisYX, mapping.axisYY)
    setFloatUniform("uStrength", style.role.strength)
    setFloatUniform("uDark", if (style.dark) 1f else 0f)
    setFloatUniform("uContrast", style.contrast)
    setFloatUniform("uAlpha", style.source.alpha)
    setFloatUniform("uSource", style.source.red, style.source.green, style.source.blue)
    setFloatUniform("uUltra", if (style.quality == AeroveGlassQuality.Ultra) 1f else 0f)
    setFloatUniform("uSubtle", if (style.subtle) 1f else 0f)
    val background = style.contrastBackground
    setFloatUniform("uContrastBackground", background.red, background.green, background.blue, background.alpha)
    asAndroidRuntimeShader().setInputShader("backdropField", field)
}
