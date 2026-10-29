package dev.aerove.lite.ui

import android.content.res.AssetManager
import android.graphics.PathMeasure
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.RuntimeShader
import com.kyant.backdrop.asAndroidRuntimeShader
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import dev.aerove.lite.optics.AeroveGlassCapture
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveInkStyle
import dev.aerove.lite.optics.AeroveOpticsPrograms
import dev.aerove.lite.optics.AeroveSuperellipseGeometry
import dev.aerove.lite.optics.AeroveSuperellipseShape
import dev.aerove.lite.optics.OpticsColor
import dev.aerove.lite.optics.configureAeroveGlass
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

internal val LocalLiteInkContrast = staticCompositionLocalOf<Float?> { null }
private val BackdropBufferShape = AeroveSuperellipseShape(0.dp)

internal fun Color.opticsColor() = OpticsColor(red, green, blue, alpha)
internal fun OpticsColor.composeColor() = Color(red, green, blue, alpha)

private data class LiteOpticsSources(val glass: String, val ink: String)

private object LiteOpticsSourceCache {
    private val sources = WeakHashMap<AssetManager, LiteOpticsSources>()

    @Synchronized fun get(assets: AssetManager): LiteOpticsSources = sources.getOrPut(assets) {
        val programs = AeroveOpticsPrograms.load(assets)
        // RenderEffect binds one RenderNode child; two aligned GPU tiles preserve the original two-input math.
        LiteOpticsSources(
            programs.glassSource.replaceRequired("uniform shader hazeContent;", "uniform float uHazeOffset;")
                .replaceRequired(
                    "hazeContent.eval(clamp(pixel, uHazeClamp.xy, min(uHazeClamp.zw, uSize - 0.5)))",
                    "content.eval(clamp(pixel, uHazeClamp.xy, min(uHazeClamp.zw, uSize - 0.5)) + float2(0.0, uHazeOffset))",
                ),
            programs.inkSource.replaceRequired("uniform shader backdropField;", "uniform shader content;")
                .replaceRequired("uniform shader glyphs;", "")
                .replaceRequired("backdropField.eval(", "content.eval(")
                .replaceRequired("float coverage = glyphs.eval(coord).a;", "float coverage = 1.0;"),
        )
    }
}

private fun String.replaceRequired(from: String, to: String): String {
    require(indexOf(from) >= 0 && indexOf(from) == lastIndexOf(from)) { "Optics sampler contract changed: $from" }
    return replace(from, to)
}

private class ViewportClamp(private val view: View) {
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float4 viewport;
        half4 main(float2 p) { return content.eval(clamp(p, viewport.xy, viewport.zw)); }
    """.trimIndent())
    private val location = IntArray(2)
    private val previous = FloatArray(4) { Float.NaN }
    private var cached: androidx.compose.ui.graphics.RenderEffect? = null

    fun diagnosticViewport(): FloatArray = previous.copyOf()

    fun effect(width: Int, height: Int, pad: Float, coordinates: LayoutCoordinates): androidx.compose.ui.graphics.RenderEffect {
        view.getLocationInWindow(location)
        val position = coordinates.positionInWindow()
        val left = (pad + location[0] - position.x + 0.5f).coerceIn(0.5f, width - 0.5f)
        val top = (pad + location[1] - position.y + 0.5f).coerceIn(0.5f, height - 0.5f)
        val right = (pad + location[0] + view.width - position.x - 0.5f).coerceIn(left, width - 0.5f)
        val bottom = (pad + location[1] + view.height - position.y - 0.5f).coerceIn(top, height - 0.5f)
        if (previous[0] != left || previous[1] != top || previous[2] != right || previous[3] != bottom) {
            previous[0] = left; previous[1] = top; previous[2] = right; previous[3] = bottom
            shader.setFloatUniform("viewport", left, top, right, bottom)
            cached = android.graphics.RenderEffect.createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content").asComposeRenderEffect()
        }
        return requireNotNull(cached)
    }
}

@Composable
private fun rememberLiteOpticsSources(): LiteOpticsSources {
    val assets = LocalContext.current.applicationContext.assets
    return remember(assets) { LiteOpticsSourceCache.get(assets) }
}

internal class PaneGeometry(private val shape: Shape) {
    private var key: Triple<Size, LayoutDirection, Float>? = null
    lateinit var geometry: AeroveSuperellipseGeometry
    var profile: FloatArray? = null

    fun resolve(size: Size, direction: LayoutDirection, density: Density) {
        val next = Triple(size, direction, density.density)
        if (key == next) return
        geometry = if (shape is AeroveSuperellipseShape) shape.geometry(size, direction, density)
        else AeroveSuperellipseGeometry.resolve((size.width / density.density).toDouble(), (size.height / density.density).toDouble(), 0.0)
        profile = if (shape is AeroveSuperellipseShape) null else outlineProfile(shape.createOutline(size, direction, density), size, density.density)
        key = next
    }
}

// liquidGlassOutlineProfile: intersect the actual contour with 64 rays, rather than fitting a substitute SDF.
private fun outlineProfile(outline: Outline, size: Size, density: Float): FloatArray {
    require(outline is Outline.Generic) { "Custom glass outlines require an explicit Path" }
    val measure = PathMeasure(outline.path.asAndroidPath(), true)
    val profile = FloatArray(64)
    val point = FloatArray(2)
    val step = 2 * PI / profile.size
    do {
        val length = measure.length
        val samples = max(256, ceil(length / density).toInt())
        var previous = Offset.Zero
        var first = Offset.Zero
        for (i in 0..samples) {
            val current = if (i == samples) first else {
                check(measure.getPosTan(length * i / samples, point, null))
                Offset((point[0] - size.width / 2) / density, (point[1] - size.height / 2) / density)
            }
            if (i == 0) { first = current; previous = current; continue }
            val angle = atan2(previous.y.toDouble(), previous.x.toDouble())
            var span = atan2(current.y.toDouble(), current.x.toDouble()) - angle
            while (span <= -PI) span += 2 * PI
            while (span > PI) span -= 2 * PI
            val end = angle + span
            val edge = current - previous
            for (ray in ceil(minOf(angle, end) / step).toInt()..floor(maxOf(angle, end) / step).toInt()) {
                val x = cos(ray * step)
                val y = sin(ray * step)
                val facing = x * edge.y - y * edge.x
                val radius = if (abs(facing) < 1e-9) max(hypot(previous.x, previous.y), hypot(current.x, current.y)).toDouble()
                else (previous.x * edge.y - previous.y * edge.x) / facing
                val index = (ray % profile.size + profile.size) % profile.size
                profile[index] = max(profile[index], radius.toFloat())
            }
            previous = current
        }
    } while (measure.nextContour())
    require(profile.all { it.isFinite() && it > 0f }) { "Glass contour must surround its center" }
    return profile
}

@Composable
internal fun Modifier.liteGlassBackdrop(source: Backdrop, shape: Shape, style: AeroveGlassStyle, glow: LitePressGlow? = null, rim: LiteGlassRim? = null, explicitGlow: dev.aerove.lite.optics.AeroveGlassGlow? = null): Modifier {
    val sources = rememberLiteOpticsSources()
    val geometry = remember(shape) { PaneGeometry(shape) }
    val view = LocalView.current
    val viewport = remember(view) { ViewportClamp(view) }
    val coordinates = remember { CoordinateRef() }
    val composition = LocalDensity.current
    val plainPad = composition.glassBackdropPadding(style)
    if (style.haze == 0f) {
        val role = LocalLiteGlassConsumerRole.current
        val subscriptionId = LiteGlassConsumerDiagnostics.subscriptionId
        val diagnostic = remember(source, view, role, subscriptionId) {
            if (role == null || subscriptionId == null) null else LiteGlassConsumerDiagnostics.attach(view.rootView, view, role, source)
                ?.let { GlassConsumerDrawObservation(it, viewport::diagnosticViewport) }
        }
        if (diagnostic != null) DisposableEffect(diagnostic) {
            onDispose { LiteGlassConsumerDiagnostics.detach(diagnostic.handle) }
        }
        val cached = remember(source, shape, style, glow, rim, explicitGlow, sources, geometry, viewport, plainPad, diagnostic) {
            val tracked = Modifier.onGloballyPositioned {
                coordinates.value = it
                if (diagnostic != null && diagnostic.handle.session.owns(diagnostic.handle)) {
                    val bounds = it.boundsInWindow(clipBounds = false)
                    LiteGlassConsumerDiagnostics.recordLayout(diagnostic.handle, GlassConsumerLayout(
                        GlassConsumerRect(bounds.left, bounds.top, bounds.right, bounds.bottom), it.size.width, it.size.height,
                        composition.density, System.identityHashCode(view.rootView), it.isAttached))
                }
            }
            val observed = if (diagnostic == null) tracked else tracked.drawWithContent {
                drawContent()
                LiteGlassConsumerDiagnostics.recordDraw(diagnostic.handle)
            }
            observed.drawPlainBackdrop(
                backdrop = source, shape = { BackdropBufferShape },
                effects = glassBackdropEffects(composition, style, geometry, sources.glass, glow, rim, explicitGlow, viewport.tracking { coordinates.value }, diagnostic))
        }
        return this.then(cached)
    }
    val tracked = onGloballyPositioned { coordinates.value = it }
    val sharp = rememberGraphicsLayer()
    val hazy = rememberGraphicsLayer()
    val atlas = rememberGraphicsLayer()
    val shader = remember(sources) { RuntimeShader(sources.glass) }
    return tracked.drawWithCache {
        geometry.resolve(size, layoutDirection, this)
        val pad = ceil(max(20.dp.toPx(), 3f * style.hazeBlur * density))
        val tile = IntSize(max(1, size.width.toInt() + 2 * pad.toInt()), max(1, size.height.toInt() + 2 * pad.toInt()))
        val capture = AeroveGlassCapture(tile.width.toFloat(), tile.height.toFloat(), density, pad)
        shader.configureAeroveGlass(geometry.geometry, style.copy(haze = 0f), capture, profile = geometry.profile,
            glow = explicitGlow ?: glow?.resolve(size, density) ?: dev.aerove.lite.optics.AeroveGlassGlow(),
            shine = rim?.uniforms(Size(geometry.geometry.width.toFloat(), geometry.geometry.height.toFloat()), geometry.geometry.radii[0].toFloat()) ?: dev.aerove.lite.optics.AeroveGlassShine.none())
        shader.setFloatUniform("uHaze", style.haze)
        shader.setFloatUniform("uHazeOffset", tile.height.toFloat())
        hazy.renderEffect = BlurEffect(style.hazeBlur * density, style.hazeBlur * density, TileMode.Clamp)
        atlas.renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content").asComposeRenderEffect()
        onDrawBehind {
            val position = coordinates.value?.takeIf { it.isAttached } ?: return@onDrawBehind
            sharp.renderEffect = viewport.effect(tile.width, tile.height, pad, position)
            recordTile(sharp, tile) {
                withTransform({ translate(pad, pad) }) { with(source) { drawBackdrop(this@onDrawBehind, position) } }
            }
            recordTile(hazy, tile) { drawLayer(sharp) }
            recordTile(atlas, IntSize(tile.width, tile.height * 2)) {
                drawLayer(sharp)
                withTransform({ translate(0f, tile.height.toFloat()) }) {
                    clipRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat()) { drawLayer(hazy) }
                }
            }
            withTransform({ translate(-pad, -pad) }) { drawLayer(atlas) }
        }
    }
}

@Composable
internal fun Modifier.liteInkLayer(backdrop: Backdrop, style: AeroveInkStyle): Modifier {
    val sources = rememberLiteOpticsSources()
    val field = rememberGraphicsLayer()
    val glyphs = rememberGraphicsLayer()
    val atlas = rememberGraphicsLayer()
    val shader = remember(sources) { RuntimeShader(sources.ink) }
    val view = LocalView.current
    val viewport = remember(view) { ViewportClamp(view) }
    val coordinates = remember { CoordinateRef() }
    return onGloballyPositioned { coordinates.value = it }.drawWithCache {
        val pad = ceil(2.dp.toPx())
        val tile = IntSize(max(1, ceil(size.width).toInt() + 2 * pad.toInt()), max(1, ceil(size.height).toInt() + 2 * pad.toInt()))
        shader.apply {
            setFloatUniform("uFieldSize", tile.width.toFloat(), tile.height.toFloat())
            setFloatUniform("uOrigin", 0f, 0f)
            setFloatUniform("uAxisX", 1f, 0f)
            setFloatUniform("uAxisY", 0f, 1f)
            setFloatUniform("uStrength", style.role.strength)
            setFloatUniform("uDark", if (style.dark) 1f else 0f)
            setFloatUniform("uContrast", style.contrast)
            setFloatUniform("uAlpha", style.source.alpha)
            setFloatUniform("uSource", style.source.red, style.source.green, style.source.blue)
            setFloatUniform("uUltra", if (style.quality == dev.aerove.lite.optics.AeroveGlassQuality.Ultra) 1f else 0f)
            setFloatUniform("uSubtle", if (style.subtle) 1f else 0f)
            val background = style.contrastBackground
            setFloatUniform("uContrastBackground", background.red, background.green, background.blue, background.alpha)
        }
        val inkEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content")
        var previousViewport: androidx.compose.ui.graphics.RenderEffect? = null
        atlas.compositingStrategy = CompositingStrategy.Offscreen
        glyphs.blendMode = BlendMode.DstIn
        onDrawWithContent {
            val position = coordinates.value?.takeIf { it.isAttached }
            if (position == null) { drawContent(); return@onDrawWithContent }
            val viewportEffect = viewport.effect(tile.width, tile.height, pad, position)
            if (previousViewport !== viewportEffect) {
                field.renderEffect = android.graphics.RenderEffect.createChainEffect(
                    inkEffect, viewportEffect.asAndroidRenderEffect(),
                ).asComposeRenderEffect()
                previousViewport = viewportEffect
            }
            recordTile(field, tile) {
                withTransform({ translate(pad, pad) }) { with(backdrop) { drawBackdrop(this@onDrawWithContent, position) } }
            }
            recordTile(glyphs, tile) {
                withTransform({ translate(pad, pad) }) { this@onDrawWithContent.drawContent() }
            }
            recordTile(atlas, tile) {
                drawLayer(field)
                drawLayer(glyphs)
            }
            withTransform({ translate(-pad, -pad); clipRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat()) }) { drawLayer(atlas) }
        }
    }
}

@Composable
internal fun Modifier.liteFrostBackdrop(source: Backdrop, style: AeroveGlassStyle): Modifier {
    val view = LocalView.current
    val viewport = remember(view) { ViewportClamp(view) }
    val coordinates = remember { CoordinateRef() }
    val composition = LocalDensity.current
    val plainPad = composition.frostBackdropPadding(style)
    val cached = remember(source, style, viewport, plainPad) {
        Modifier.onGloballyPositioned { coordinates.value = it }.drawPlainBackdrop(
            backdrop = source, shape = { BackdropBufferShape },
            effects = frostBackdropEffects(composition, style, viewport.tracking { coordinates.value }),
            onDrawSurface = { drawRect(style.tint.composeColor()) })
    }
    return this.then(cached)
}

private class CoordinateRef {
    private var current: LayoutCoordinates? = null
    private var bounds by mutableStateOf<Rect?>(null)
    var value: LayoutCoordinates?
        get() { bounds; return current }
        set(value) { current = value; bounds = value?.takeIf { it.isAttached }?.boundsInWindow() }
}

internal typealias BackdropViewport = (width: Int, height: Int, pad: Float) -> androidx.compose.ui.graphics.RenderEffect?

private fun ViewportClamp.tracking(coordinates: () -> LayoutCoordinates?): BackdropViewport =
    { width, height, pad -> coordinates()?.takeIf { it.isAttached }?.let { effect(width, height, pad, it) } }

internal fun frostBackdropEffects(composition: Density, style: AeroveGlassStyle, viewport: BackdropViewport) =
    plainBackdropEffects(composition, style, Density::frostBackdropPadding, viewport) { _, _ -> }

internal class GlassConsumerDrawObservation(val handle: LiteGlassConsumerDiagnostics.Handle, val viewport: () -> FloatArray)

internal fun glassBackdropEffects(
    composition: Density, style: AeroveGlassStyle, geometry: PaneGeometry, glassSource: String, glow: LitePressGlow?, rim: LiteGlassRim?,
    explicitGlow: dev.aerove.lite.optics.AeroveGlassGlow?, viewport: BackdropViewport,
    diagnostic: GlassConsumerDrawObservation? = null,
) = plainBackdropEffects(composition, style, Density::glassBackdropPadding, viewport) { pad, sigma ->
    geometry.resolve(size, layoutDirection, this)
    val capture = AeroveGlassCapture(size.width.toInt() + 2 * pad, size.height.toInt() + 2 * pad, density, pad, sigma)
    runtimeShaderEffect("AeroveGlassSource-v1", glassSource, "content") {
        val receipt = diagnostic?.takeIf { it.handle.session.owns(it.handle) }?.let {
            LiteGlassConsumerDiagnostics.beginConfiguration(it.handle, asAndroidRuntimeShader(), glassSource,
                geometry.geometry.width.toFloat(), geometry.geometry.height.toFloat(), capture, it.viewport())
        }
        configureAeroveGlass(geometry.geometry, style, capture, profile = geometry.profile,
            glow = explicitGlow ?: glow?.resolve(size, density) ?: dev.aerove.lite.optics.AeroveGlassGlow(),
            shine = rim?.uniforms(Size(geometry.geometry.width.toFloat(), geometry.geometry.height.toFloat()), geometry.geometry.radii[0].toFloat()) ?: dev.aerove.lite.optics.AeroveGlassShine.none(),
            uniformObserver = receipt)
        val hazeOffset = 0f
        setFloatUniform("uHazeOffset", hazeOffset)
        receipt?.onUniformSet("uHazeOffset", floatArrayOf(hazeOffset))
        receipt?.complete()
    }
}

internal fun Density.glassBackdropPadding(style: AeroveGlassStyle) = ceil(max(20.dp.toPx(), 3f * (style.blur + style.rimBlur) * density))
internal fun Density.frostBackdropPadding(style: AeroveGlassStyle) = ceil(3f * style.blur * density)
internal fun Density.backdropBlurRadius(style: AeroveGlassStyle) = style.blur * density

// Kyant 2.0.1 keeps `padding` in snapshot state and rereads it in the draw that reruns these effects; see LiteBackdropPaddingTest.
private fun plainBackdropEffects(
    composition: Density, style: AeroveGlassStyle, padFor: Density.(AeroveGlassStyle) -> Float, viewport: BackdropViewport,
    shade: BackdropEffectScope.(pad: Float, sigma: Float) -> Unit,
): BackdropEffectScope.() -> Unit {
    val pad = composition.padFor(style)
    return {
        padding = pad
        if (size.minDimension > 0f) {
            viewport(size.width.toInt() + 2 * pad.toInt(), size.height.toInt() + 2 * pad.toInt(), pad)?.let { effect(it) }
            val sigma = backdropBlurRadius(style)
            require(sigma <= pad) { "Backdrop blur radius $sigma exceeds its padding $pad" }
            if (sigma > 0f) blur(sigma)
            shade(pad, sigma)
        }
    }
}

private fun DrawScope.recordTile(layer: GraphicsLayer, size: IntSize, draw: DrawScope.() -> Unit) {
    val recordingDensity = drawContext.density
    layer.record(size) {
        val previous = drawContext.density
        drawContext.density = recordingDensity
        try { clipRect(0f, 0f, size.width.toFloat(), size.height.toFloat()) { draw() } }
        finally { drawContext.density = previous }
    }
}

@Composable
internal fun Modifier.liteLensContent(rect: Rect, lift: Float): Modifier {
    if (LocalLiteVisuals.current.material != dev.aerove.lite.MaterialTier.Clear || lift <= 0f) return this
    val sources = rememberLiteOpticsSources()
    val raw = rememberGraphicsLayer()
    val warped = rememberGraphicsLayer()
    val shader = remember(sources) { RuntimeShader(sources.glass) }
    val shape = rememberLiteShape(LiteCorner.Full)
    return drawWithCache {
        val amount = lift.coerceIn(0f, 1f)
        val style = AeroveGlassStyle.lensContent().let { it.copy(refraction = it.refraction * amount, dispersion = it.dispersion * amount, rimBlur = it.rimBlur * amount) }
        val geometry = shape.geometry(rect.size, layoutDirection, this)
        val outline = (shape.createOutline(rect.size, layoutDirection, this) as Outline.Generic).path.apply { translate(rect.topLeft) }
        val pad = ceil(20.dp.toPx())
        val tile = IntSize(max(1, size.width.toInt() + 2 * pad.toInt()), max(1, size.height.toInt() + 2 * pad.toInt()))
        val originX = (-pad - rect.left) / density
        val originY = (-pad - rect.top) / density
        shader.configureAeroveGlass(geometry, style, AeroveGlassCapture(tile.width.toFloat(), tile.height.toFloat(), density, pad))
        shader.setFloatUniform("uFrame", originX, originY, 1f / density, 1f / density)
        shader.setFloatUniform("uSample", originX, originY, 1f / density, 1f / density)
        shader.setFloatUniform("uHazeOffset", 0f)
        warped.renderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader.asAndroidRuntimeShader(), "content").asComposeRenderEffect()
        onDrawWithContent {
            recordTile(raw, tile) { withTransform({ translate(pad, pad) }) { this@onDrawWithContent.drawContent() } }
            recordTile(warped, tile) { drawLayer(raw) }
            clipPath(outline, ClipOp.Difference) { withTransform({ translate(-pad, -pad) }) { drawLayer(raw) } }
            clipPath(outline) { withTransform({ translate(-pad, -pad) }) { drawLayer(warped) } }
        }
    }
}
