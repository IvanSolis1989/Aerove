package dev.aerove.lite.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aerove.lite.optics.AeroveGlassGlow
import dev.aerove.lite.optics.AeroveGlassShine
import dev.aerove.lite.optics.AeroveSuperellipseShape
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.flow.collect

internal data class LiteGlassRim(val side: Float, val corner: Float, val flat: Float, val shine: Float) {
    fun uniforms(size: Size, radius: Float): AeroveGlassShine = AeroveGlassShine.menu(size.width, size.height, radius, shine)
    companion object {
        fun alert(dark: Boolean) = if (dark) LiteGlassRim(0.75f, 0.1f, 0.03f, 0.22f) else LiteGlassRim(0.35f, 0.22f, 0.12f, 0.16f)
        fun menu(dark: Boolean) = if (dark) LiteGlassRim(0.27f, 0.1f, 0.02f, 0.22f) else LiteGlassRim(0.25f, 0.15f, 0.09f, 0.16f)
    }
}

internal class LitePressGlow(val amount: State<Float>, val touch: androidx.compose.runtime.MutableState<Offset?>, val pressed: Boolean) {
    fun resolve(size: Size, density: Float): AeroveGlassGlow {
        val t = amount.value
        val point = touch.value ?: Offset(size.width / 2, size.height / 2)
        return AeroveGlassGlow(point.x / density, point.y / density, 0.16f * t, size.height / density / sqrt(2f) * (1 + if (pressed) 0f else 3 * (1 - t)))
    }
}

@Composable
internal fun rememberLitePressGlow(source: MutableInteractionSource?): LitePressGlow {
    val pressed = if (source == null) false else source.collectIsPressedAsState().value
    val amount = animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 120 else 360, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)), label = "glassTouchGlow")
    val touch = remember { mutableStateOf<Offset?>(null) }
    androidx.compose.runtime.LaunchedEffect(pressed, amount) {
        if (!pressed) androidx.compose.runtime.snapshotFlow { amount.value }.collect { if (it == 0f) touch.value = null }
    }
    return remember(amount, touch, pressed) { LitePressGlow(amount, touch, pressed) }
}

internal fun Modifier.trackLiteGlow(glow: LitePressGlow, enabled: Boolean): Modifier = if (!enabled) this else pointerInput(glow.touch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.pressed }?.let { change ->
                glow.touch.value = change.position
            }
        }
    }
}

private fun Shape.path(size: Size, direction: LayoutDirection, density: Density): Path = when (val outline = createOutline(size, direction, density)) {
    is Outline.Generic -> outline.path
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    else -> error("Surface finish requires the actual superellipse outline")
}

@Composable
internal fun Modifier.liteRimLine(shape: AeroveSuperellipseShape, rim: LiteGlassRim): Modifier = this.then(remember(shape, rim) { Modifier.drawWithCache {
    if (size.minDimension <= 0f) return@drawWithCache onDrawWithContent { drawContent() }
    val width = 0.67.dp.toPx()
    val half = width / 2
    val inflated = shape.copy(corner = shape.corner + 0.335.dp, endCorner = shape.endCorner + 0.335.dp, bottomCorner = shape.bottomCorner + 0.335.dp, bottomEndCorner = shape.bottomEndCorner + 0.335.dp)
    val outline = Path().apply { addPath(inflated.path(Size(size.width + width, size.height + width), layoutDirection, this@drawWithCache), Offset(-half, -half)) }
    val across = min(shape.corner.toPx().coerceIn(0f, size.minDimension / 2) / size.width, 0.4f)
    val gradient = Brush.horizontalGradient(0f to Color.Black.copy(alpha = rim.side), across / 4 to Color.Black.copy(alpha = rim.corner), across to Color.Black.copy(alpha = rim.flat), 1 - across to Color.Black.copy(alpha = rim.flat), 1 - across / 4 to Color.Black.copy(alpha = rim.corner), 1f to Color.Black.copy(alpha = rim.side))
    onDrawWithContent {
        drawContent()
        drawPath(outline, gradient, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
    }
} })

internal data class LiteShadow(val alpha: Float, val y: Float, val sigma: Float, val spread: Float = 0f)

internal fun fullMenuShadows(dark: Boolean): List<LiteShadow> {
    val strength = if (dark) 1.5f else 1f
    return listOf(LiteShadow(0.09f * strength, 4f, 16.5f * 0.57735f + 0.5f), LiteShadow(0.045f * strength, 8f, 42f * 0.57735f + 0.5f))
}

internal fun fullDashboardShadows(dark: Boolean): List<LiteShadow> = if (dark) listOf(LiteShadow(128 / 255f, 1f, 1f), LiteShadow(140 / 255f, 10f, 14f, -6f))
else listOf(LiteShadow(13 / 255f, 0.5f, 0f), LiteShadow(10 / 255f, 2f, 3f), LiteShadow(23 / 255f, 12f, 16f, -6f))

internal fun Modifier.liteOutsideShadows(shape: Shape, shadows: List<LiteShadow>): Modifier =
    if (shadows.none { it.alpha > 0f }) this else this.then(OutsideShadowsElement(shape, shadows.filter { it.alpha > 0f }))

private data class OutsideShadowsElement(val shape: Shape, val shadows: List<LiteShadow>) :
    androidx.compose.ui.node.ModifierNodeElement<OutsideShadowsNode>() {
    override fun create() = OutsideShadowsNode(shape, shadows)
    override fun update(node: OutsideShadowsNode) { node.update(shape, shadows) }
    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() { name = "liteOutsideShadows" }
}

private class OutsideShadowsNode(var shape: Shape, var shadows: List<LiteShadow>) : androidx.compose.ui.node.DelegatingNode() {
    fun update(shape: Shape, shadows: List<LiteShadow>) {
        this.shape = shape; this.shadows = shadows; cache.invalidateDrawCache()
    }
    private val cache = delegate(androidx.compose.ui.draw.CacheDrawModifierNode {
        val layers = shadows.map { obtainGraphicsLayer() }
        val actual = shape.path(size, layoutDirection, this)
        val pad = ceil(shadows.maxOf { it.sigma * 3 + kotlin.math.abs(it.y) + kotlin.math.abs(it.spread) } * density)
        val buffer = androidx.compose.ui.unit.IntSize(ceil(size.width + 2 * pad).toInt().coerceAtLeast(1), ceil(size.height + 2 * pad).toInt().coerceAtLeast(1))
        val paths = shadows.map { shadow ->
            val spread = shadow.spread.dp.toPx()
            shape.path(Size((size.width + 2 * spread).coerceAtLeast(1f), (size.height + 2 * spread).coerceAtLeast(1f)), layoutDirection, this)
        }
        val offsets = shadows.map { Offset(pad - it.spread * density, pad + (it.y - it.spread) * density) }
        shadows.forEachIndexed { i, shadow -> layers[i].renderEffect = if (shadow.sigma == 0f) null else BlurEffect(shadow.sigma * density, shadow.sigma * density, TileMode.Decal) }
        shadows.forEachIndexed { index, shadow ->
            layers[index].record(this, layoutDirection, buffer) {
                withTransform({ translate(offsets[index].x, offsets[index].y) }) { drawPath(paths[index], Color.Black.copy(alpha = shadow.alpha)) }
            }
        }
        onDrawBehind {
            clipPath(actual, ClipOp.Difference) {
                layers.forEach { layer -> withTransform({ translate(-pad, -pad) }) { drawLayer(layer) } }
            }
        }
    })
}
