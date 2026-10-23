package dev.aerove.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.navigation.TabAnimation
import dev.aerove.lite.navigation.tabSceneFrame
import dev.aerove.lite.optics.AeroveGlassStyle
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun NavigationPreferencesSection(settings: LiteSettings, enabled: Boolean, save: (LiteSettings) -> Unit) {
    val current by rememberUpdatedState(settings)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.navigation_bar_style))
        LitePane(Modifier.fillMaxWidth()) {
            Column { listOf(true, false).forEach { floating ->
                NavigationChoice(stringResource(if (floating) R.string.navigation_floating else R.string.navigation_docked),
                    settings.floatingNavigationBar == floating, enabled, "navigation-style-$floating",
                    { save(current.copy(floatingNavigationBar = floating)) }) { NavigationThumb(floating, settings.tabAnimation, false) }
            } }
        }
        SectionLabel(stringResource(R.string.navigation_tab_animation))
        LitePane(Modifier.fillMaxWidth()) {
            Column { TabAnimation.entries.forEach { animation ->
                NavigationChoice(stringResource(if (animation == TabAnimation.Slide) R.string.navigation_slide else R.string.navigation_fade),
                    settings.tabAnimation == animation, enabled, "navigation-animation-${animation.wireName}",
                    { save(current.copy(tabAnimation = animation)) }) { NavigationThumb(settings.floatingNavigationBar, animation, true) }
            } }
        }
    }
}

@Composable
private fun NavigationChoice(label: String, chosen: Boolean, enabled: Boolean, tag: String, select: () -> Unit, preview: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).semantics { selected = chosen }
        .clickable(enabled = enabled, role = Role.Button, onClick = { if (!chosen) select() }).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        preview()
        LiteText(label, Modifier.weight(1f))
        if (chosen) LiteIcon(FullGlyphs.check)
    }
}

/** Standard MiniScreenThumb:96x128 screen, bottom66 shown at30dp height. */
@Composable
private fun NavigationThumb(floating: Boolean, animation: TabAnimation, switching: Boolean) {
    val scale = 30f / 66f
    Layout(modifier = Modifier.size((96 * scale).dp, 30.dp).clip(rememberLiteShape(LiteCorner.Small)), content = {
        NavigationMiniScreen(floating, animation, switching)
    }) { nodes, _ ->
        val child = nodes.single().measure(Constraints.fixed(96.dp.roundToPx(), 128.dp.roundToPx()))
        layout((96.dp.toPx() * scale).roundToInt(), 30.dp.roundToPx()) {
            child.placeWithLayer(0, (30.dp.toPx() - child.height * scale).roundToInt()) {
                transformOrigin = TransformOrigin(0f, 0f); scaleX = scale; scaleY = scale
            }
        }
    }
}

@Composable
private fun NavigationMiniScreen(floating: Boolean, animation: TabAnimation, switching: Boolean) {
    val colors = MaterialTheme.colorScheme
    val visuals = LocalLiteVisuals.current
    val backdrop = rememberLayerBackdrop()
    val unit = 96f / 20
    val barHeight = unit * if (floating) 3.2f else 3.6f
    val margin = unit * 1.5f
    val left = if (floating) margin else 0f
    val top = 128 - barHeight - if (floating) margin else 0f
    val width = 96 - 2 * left
    val selected = if (switching) 1 else 0
    Box(Modifier.size(96.dp, 128.dp)) {
        Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            drawRect(colors.fullPage(visuals.dark))
            fun page(index: Int, offset: Float, opacity: Float) {
                val u = size.width / 20
                val content = Rect(offset + u * 1.5f, u * 1.5f, offset + size.width - u * 1.5f, size.height)
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = opacity })
                clipRect(content.left, content.top, content.right, content.bottom) { miniPage(index, content, u, colors, visuals.dark) }
                drawContext.canvas.restore()
            }
            if (switching) {
                val out = tabSceneFrame(animation, false, 1, 0.55f)
                val into = tabSceneFrame(animation, true, 1, 0.55f)
                page(0, out.offset * size.width, out.opacity); page(1, into.offset * size.width, into.opacity)
            } else page(0, 0f, 1f)
        }
        CompositionLocalProvider(LocalLiteBackdrop provides backdrop) {
            val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
            val barModifier = Modifier.offset(left.dp, top.dp).size(width.dp, barHeight.dp)
            if (floating) {
                val style = AeroveGlassStyle.pill(visuals.dark)
                LitePane(barModifier, corner = LiteCorner.Full, solidColor = fill,
                    glassStyle = style.copy(blur = style.blur * 0.24f, hazeBlur = style.hazeBlur * 0.24f,
                        refraction = 0f, bevel = 0f, rimWidth = style.rimWidth * 0.24f)) {
                    MiniDestinations(selected, floating)
                }
            } else Box(barModifier) {
                Canvas(Modifier.matchParentSize()) { drawRect(fill) }
                MiniDestinations(selected, floating)
            }
        }
    }
}

@Composable
private fun MiniDestinations(selected: Int, floating: Boolean) {
    val colors = MaterialTheme.colorScheme
    val visuals = LocalLiteVisuals.current
    Canvas(Modifier.fillMaxSize()) {
        val u = 4.8.dp.toPx(); val cell = size.width / 4
        for (index in 0..3) {
            val center = Offset(cell * (index + 0.5f), size.height / 2)
            val icon = center - Offset(0f, u * 0.4f)
            val ink = if (index == selected) colors.primary else colors.onSurfaceVariant
            if (index == selected) {
                val indicator = if (floating && visuals.material != MaterialTier.Solid) colors.onSurface.copy(alpha = if (visuals.dark) 0.14f else 0.08f)
                    else if (visuals.dark) colors.surfaceContainerHighest else colors.surfaceContainer
                miniBlock(if (floating) Rect(center.x - (cell - u * 0.5f) / 2, u * 0.25f,
                    center.x + (cell - u * 0.5f) / 2, size.height - u * 0.25f) else Rect(icon.x - 1.4f * u, icon.y - 0.75f * u, icon.x + 1.4f * u, icon.y + 0.75f * u), indicator, 1000f)
            }
            drawCircle(ink, u * 0.45f, icon)
            miniBlock(Rect(center.x - u * 0.85f, center.y + u * 0.625f, center.x + u * 0.85f, center.y + u * 0.975f),
                if (index == selected) ink else ink.copy(alpha = 0.6f), 1000f)
        }
    }
}

private fun DrawScope.miniBlock(rect: Rect, color: Color, corner: Float) {
    val outline = LiteSuperellipse((corner / density).dp).createOutline(rect.size, layoutDirection, this) as Outline.Generic
    withTransform({ translate(rect.left, rect.top) }) { drawPath(outline.path, color) }
}

private fun DrawScope.miniPage(index: Int, content: Rect, unit: Float, colors: androidx.compose.material3.ColorScheme, dark: Boolean) {
    val width = content.width / unit
    fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(content.left + x * unit, content.top + y * unit,
        content.left + (x + w) * unit, content.top + (y + h) * unit)
    fun block(x: Float, y: Float, w: Float, h: Float, color: Color, pill: Boolean = false) {
        val extent = 5 * unit / density / 3
        val corner = if (pill) 1000f else when { extent >= 28 -> 28f; extent >= 24 -> 24f; extent >= 20 -> 20f; extent >= 16 -> 16f; extent >= 8 -> 8f; extent >= 4 -> 4f; else -> 0f }
        miniBlock(rect(x, y, w, h), color, corner * density)
    }
    val card = colors.fullCard(dark); val inset = colors.surfaceContainerHighest
    val text = colors.onSurface.copy(alpha = 0.72f); val line = colors.onSurfaceVariant.copy(alpha = 0.4f)
    block(0f, 0.45f, 7f, 1.1f, text, true)
    if (index == 1) {
        repeat(9) { i -> val y = 2.8f + i * 3.2f
            block(0f, y, width, 2.6f, card); block(0.7f, y + 0.5f, 1.6f, 1.6f, inset, true)
            block(3f, y + 0.6f, 3f + i % 3, 0.7f, line, true)
            block(3f, y + 1.55f, 4f, 0.5f, colors.onSurfaceVariant.copy(alpha = 0.16f), true)
        }
        return
    }
    var y = 2.8f
    block(0f, y, width, 6.2f, card); block(width - 4.8f, y + 1.3f, 3.6f, 3.6f, colors.primary, true)
    block(1f, y + 0.9f, 3f, 0.7f, line, true); block(3.5f, y + 2.3f, 5f, 0.9f, text, true)
    y += 6.8f
    block(0f, y, width, 5.8f, card); block(1f, y + 0.9f, 4.5f, 0.7f, line, true)
    val tile = (width - 2.6f) / 2
    repeat(2) { i ->
        val x = 1 + i * (tile + 0.6f); block(x, y + 2.1f, tile, 2.9f, inset)
        val graph = Path()
        for (step in 0..16) {
            val px = content.left + (x + tile * step / 16) * unit
            val py = content.top + (y + 2.1f + 2.9f * 0.68f + sin(step * 0.9f + i * 1.7f) * 0.35f) * unit
            if (step == 0) graph.moveTo(px, py) else graph.lineTo(px, py)
        }
        drawPath(graph, colors.primary.copy(alpha = if (i == 0) 0.5f else 1f), style = Stroke(unit * 0.15f))
    }
    y += 6.4f
    block(0f, y, width, 4.4f, card); block(1f, y + 0.9f, 4f, 0.7f, line, true)
    repeat(3) { i -> block(1 + i * ((width - 2.6f) / 3 + 0.3f), y + 2.1f, (width - 2.6f) / 3, 1.6f, if (i == 0) colors.primary else inset, true) }
    y += 5f
    block(0f, y, width, 3f, card); block(1f, y + 1.1f, 2.4f, 0.8f, line, true)
    y += 3.6f
    repeat(3) { i -> block(0f, y, width, 4.5f, card); block(1f, y + 0.9f, 3.5f + i % 2, 0.7f, line, true); y += 5.1f }
}
