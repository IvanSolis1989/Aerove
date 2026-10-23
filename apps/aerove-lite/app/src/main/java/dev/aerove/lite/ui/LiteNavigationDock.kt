package dev.aerove.lite.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusProperties
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.aerove.lite.optics.AeroveGlassStyle

@Composable
private fun dockMotion(target: Float, moving: Boolean, spec: FiniteAnimationSpec<Float>): State<Float> {
    val value = remember { Animatable(target) }
    LaunchedEffect(target, moving, spec) {
        if (moving && value.value != target) value.animateTo(target, spec) else value.snapTo(target)
    }
    return value.asState()
}

@Composable
internal fun LiteNavigationDock(
    items: List<MainTab>,
    selected: MainTab,
    collapsed: Boolean,
    bottomInset: Dp,
    onSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val moving = enabled && !LocalLiteReducedMotion.current
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selection by rememberUpdatedState(onSelected)
    val collapse by dockMotion(if (collapsed) 1f else 0f, moving, tween(300, easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)))
    var held by remember { mutableStateOf(false) }
    var glowAt by remember { mutableStateOf(Offset.Zero) }
    val glow by dockMotion(if (held && enabled) 1f else 0f, moving, spring(dampingRatio = 1f, stiffness = if (held) 3947.8418f else 157.91367f, visibilityThreshold = 0.001f))
    val glowSpread = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(held, moving) {
        if (!moving) glowSpread.snapTo(if (held && enabled) 0f else 1f)
        else if (held) glowSpread.snapTo(0f) else glowSpread.animateTo(1f, spring(dampingRatio = 1f, stiffness = 157.91367f, visibilityThreshold = 0.001f))
    }
    var finger by remember { mutableStateOf<Float?>(null) }
    var motionStretch by remember { mutableFloatStateOf(0f) }
    val lift by dockMotion(if (held && enabled) 1f else 0f, moving, spring(dampingRatio = 0.593f, stiffness = 311f, visibilityThreshold = 0.001f))
    val position by dockMotion(if (enabled) finger ?: items.indexOf(selected).coerceAtLeast(0).toFloat() else items.indexOf(selected).coerceAtLeast(0).toFloat(), moving,
        spring(dampingRatio = if (held) 0.9f else 0.85f, stiffness = if (held) 1024f else 246.74f, visibilityThreshold = 0.001f))
    val stretch by dockMotion(if (enabled) motionStretch else 0f, moving, spring(dampingRatio = 0.4f, stiffness = 100f, visibilityThreshold = 0.003f))
    LaunchedEffect(enabled) { if (!enabled) { held = false; finger = null; motionStretch = 0f } }
    BoxWithConstraints(modifier.focusProperties { canFocus = enabled }.then(if (enabled) Modifier else Modifier.clearAndSetSemantics {}).fillMaxWidth().padding(start = 21.dp, end = 21.dp, bottom = maxOf(21.dp, bottomInset)), contentAlignment = Alignment.BottomCenter) {
        val expanded = if (items.size >= 5) maxWidth else minOf(maxWidth, (items.size * 86 + 16).dp)
        val compact = minOf(expanded, (items.size * 56 + 16).dp)
        val width = expanded + (compact - expanded) * collapse
        val normalHeight = 62.dp + (10 * (density.fontScale - 1)).dp
        val height = normalHeight + (56.dp - normalHeight) * collapse
        val pitch = (width - 16.dp) / items.size
        val pitchPx = with(density) { pitch.toPx() }
        val widthPx = with(density) { width.toPx() }
        val insetPx = with(density) { 8.dp.toPx() }
        val minimumLens = 84.dp + (64.dp - 84.dp) * collapse
        val lensWidth = maxOf(pitch + 8.dp, minimumLens).coerceAtMost(width)
        val lensHeight = height - 8.dp
        fun logical(x: Float) = (((if (rtl) widthPx - x else x) - insetPx) / pitchPx - 0.5f)
        LitePane(
            Modifier.width(width).height(height).selectableGroup().pointerInput(items, rtl, widthPx, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var previous = down.position.x
                    var dragging = false
                    held = true
                    glowAt = down.position
                    finger = logical(down.position.x).coerceIn(0f, items.lastIndex.toFloat())
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) selection(items[requireNotNull(finger).roundToInt().coerceIn(items.indices)])
                                break
                            }
                            if (abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) dragging = true
                            if (dragging) {
                                change.consume()
                                val raw = logical(change.position.x)
                                val over = if (raw < 0) raw else if (raw > items.lastIndex) raw - items.lastIndex else 0f
                                val rubber = if (over == 0f) 0f else 0.21875f * (1 - 1 / (abs(over) * 0.55f / 0.21875f + 1)) * if (over < 0) -1 else 1
                                finger = raw.coerceIn(0f, items.lastIndex.toFloat()) + rubber
                                motionStretch = ((change.position.x - previous) / pitchPx * 0.2f).coerceIn(-0.15f, 0.2f)
                                previous = change.position.x
                            }
                        }
                    } finally { held = false; finger = null; motionStretch = 0f }
                }
            },
            corner = LiteCorner.Full,
            clipContent = false,
            glassStyle = AeroveGlassStyle.pill(visuals.dark),
            solidColor = if (visuals.dark) androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh else visuals.surface,
            shadows = when (visuals.material) {
                dev.aerove.lite.MaterialTier.Solid -> listOf(LiteShadow(0.08f * if (visuals.dark) 3f else 1f, 8f, 24f * 0.57735f + 0.5f), LiteShadow(0.04f * if (visuals.dark) 3f else 1f, 1f, 3f * 0.57735f + 0.5f))
                dev.aerove.lite.MaterialTier.Clear -> listOf(LiteShadow(0.013f, 6.5f, 3.8f * 0.57735f + 0.5f))
                else -> emptyList()
            },
            explicitGlow = dev.aerove.lite.optics.AeroveGlassGlow(glowAt.x / density.density, glowAt.y / density.density, 0.06f * glow * glow, 28f * (1 + 3 * glowSpread.value), 0.7f),
        ) {
            val grownWidth = (lensWidth + (16 * lift).dp) * (1 + stretch)
            val grownHeight = (lensHeight + (16 * lift).dp) * (1 - stretch * 1.4f)
            LitePane(
                Modifier.offset(x = 8.dp + pitch * (position + 0.5f) - grownWidth / 2, y = (height - grownHeight) / 2)
                    .size(grownWidth, grownHeight),
                corner = LiteCorner.Full, selected = true,
                glassTint = if (visuals.material == dev.aerove.lite.MaterialTier.Frosted) visuals.surface.copy(alpha = 0.24f) else androidx.compose.ui.graphics.Color.Transparent,
                glassStyle = AeroveGlassStyle.lens(visuals.dark),
                shadows = if (visuals.material == dev.aerove.lite.MaterialTier.Solid) emptyList() else listOf(LiteShadow(0.055f * (if (visuals.dark) 3f else 1f) * lift.coerceIn(0f, 1f), 8f, 5f * 0.57735f + 0.5f)),
            ) {}
            val lensStart = 8.dp + pitch * (position + 0.5f) - grownWidth / 2
            val contentLens = with(density) {
                val left = (if (rtl) width - lensStart - grownWidth else lensStart).toPx()
                val top = ((height - grownHeight) / 2).toPx()
                Rect(left, top, left + grownWidth.toPx(), top + grownHeight.toPx())
            }
            Box(Modifier.fillMaxSize().liteLensContent(contentLens, lift)) {
            items.forEachIndexed { index, item ->
                val active = item == selected
                val fill by dockMotion(if (active) 1f else 0f, moving, tween(200))
                val foreground = if (active) LocalLiteVisuals.current.primary else LocalLiteVisuals.current.secondaryInk
                Column(
                    Modifier.offset(x = 8.dp + pitch * index, y = 4.dp).width(pitch).height(lensHeight)
                        .selectable(active, enabled = enabled, role = Role.Tab, onClick = { selection(item) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LiteIcon(item.icon, Modifier.graphicsLayer {
                        val magnify = if (active && visuals.material != dev.aerove.lite.MaterialTier.Clear) 1 + lift * 0.16f else 1f
                        scaleX = magnify; scaleY = magnify
                    }, foreground, fill = fill, size = 28.dp)
                    if (collapse < 0.999f) Box(Modifier.height((16.5f * (1 - collapse)).dp).alpha(1 - collapse), contentAlignment = Alignment.BottomCenter) {
                        LiteText(stringResource(item.label), style = LiteType.Dock, color = foreground, maxLines = 1)
                    }
                }
            }
            }
        }
    }
}
