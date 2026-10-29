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

interface AeroveGlassUniformObserver {
    fun onUniformSet(name: String, values: FloatArray)
    fun onObserverFailure(error: Exception) {}
}

private class ObservedGlassUniforms(private val target: RuntimeShader, private val observer: AeroveGlassUniformObserver) : RuntimeShader by target {
    private fun record(name: String, values: FloatArray) {
        try { observer.onUniformSet(name, values) }
        catch (failure: Exception) { try { observer.onObserverFailure(failure) } catch (_: Exception) {} }
    }
    override fun setFloatUniform(name: String, value: Float) {
        target.setFloatUniform(name, value); record(name, floatArrayOf(value))
    }
    override fun setFloatUniform(name: String, value1: Float, value2: Float) {
        target.setFloatUniform(name, value1, value2); record(name, floatArrayOf(value1, value2))
    }
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float) {
        target.setFloatUniform(name, value1, value2, value3); record(name, floatArrayOf(value1, value2, value3))
    }
    override fun setFloatUniform(name: String, value1: Float, value2: Float, value3: Float, value4: Float) {
        target.setFloatUniform(name, value1, value2, value3, value4); record(name, floatArrayOf(value1, value2, value3, value4))
    }
    override fun setFloatUniform(name: String, values: FloatArray) {
        target.setFloatUniform(name, values); record(name, values.copyOf())
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
    uniformObserver: AeroveGlassUniformObserver? = null,
) {
    require(geometry.width > 0 && geometry.height > 0)
    require(profile == null || (profile.size == 64 && profile.all { it.isFinite() && it > 0 }))
    require(style.haze == 0f || hazeShader != null) { "Haze requires a separately blurred backdrop shader" }
    val step = 1f / capture.density
    val origin = -capture.paddingPixels * step
    val uniforms = if (uniformObserver == null) this else ObservedGlassUniforms(this, uniformObserver)
    uniforms.setFloatUniform("uSize", capture.widthPixels, capture.heightPixels)
    uniforms.setFloatUniform("uFrame", origin, origin, step, step)
    uniforms.setFloatUniform("uSample", origin, origin, step, step)
    uniforms.setFloatUniform("uClamp", 0.5f, 0.5f, capture.widthPixels - 0.5f, capture.heightPixels - 0.5f)
    uniforms.setFloatUniform("uTexels", step, capture.capturedBlurSigmaPixels)
    uniforms.setFloatUniform("uHazeSample", origin, origin, step, step)
    uniforms.setFloatUniform("uHazeClamp", 0.5f, 0.5f, capture.widthPixels - 0.5f, capture.heightPixels - 0.5f)
    uniforms.setFloatUniform("uHaze", style.haze)
    uniforms.setFloatUniform("uShape", geometry.width.toFloat(), geometry.height.toFloat(), style.bevel, style.blur)
    uniforms.setFloatUniform("uCorners", geometry.radii.map { it.toFloat() }.toFloatArray())
    uniforms.setFloatUniform("uCurves", geometry.curveUniforms())
    uniforms.setFloatUniform("uQuadrants", geometry.quadrantUniforms())
    uniforms.setFloatUniform("uOptics", style.refraction, style.saturation, style.brightness)
    uniforms.setFloatUniform("uTint", style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha)
    uniforms.setFloatUniform("uLight", style.rim, style.rimWidth, style.endShade)
    uniforms.setFloatUniform("uGlow", glow.centerX, glow.centerY, glow.strength, style.contrast)
    uniforms.setFloatUniform("uGlowSpread", glow.reach, glow.wash)
    uniforms.setFloatUniform("uEdge", style.hairline, style.dispersion, style.rimBlur)
    uniforms.setFloatUniform("uRim", style.rimTail, style.rimFocus, style.rimAdded, style.hairlineSide ?: style.hairline)
    uniforms.setFloatUniform("uOutlined", if (profile == null) 0f else 1f)
    uniforms.setFloatUniform("uProfile", profile ?: FloatArray(64))
    uniforms.setFloatUniform("uShineTurns", shine.turns)
    uniforms.setFloatUniform("uShineLit", shine.lights)
    uniforms.setFloatUniform("uShineEnds", shine.ends)
    uniforms.setFloatUniform("uShineShares", shine.shares)
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
