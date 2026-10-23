package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import dev.aerove.lite.MaterialTier

internal object LiteMetrics {
    val PageToolbar = 72.dp
    val RowMin = 56.dp
    val RowVertical = 10.dp
    val PageSide = 16.dp
}

internal val LocalLiteListState = staticCompositionLocalOf<LazyListState> { error("A production page must have a scroll owner") }
internal val LocalLiteHeaderProgress = staticCompositionLocalOf<Float?> { null }

@Composable
internal fun LitePageHeader(
    title: String,
    topInset: Dp,
    scroll: LazyListState,
    modifier: Modifier = Modifier,
    back: (() -> Unit)? = null,
    backLabel: String,
    toolbarContent: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val progress by remember(scroll, density) { derivedStateOf {
        if (scroll.layoutInfo.totalItemsCount == 0) 0f
        else if (scroll.firstVisibleItemIndex > 0) 1f
        else (scroll.firstVisibleItemScrollOffset / (24 * density)).coerceIn(0f, 1f)
    } }
    Box(modifier.fillMaxWidth()) {
        LiteHeaderBackdrop(topInset, Modifier.fillMaxWidth().height(topInset + LiteMetrics.PageToolbar + 16.dp))
        CompositionLocalProvider(LocalLiteHeaderProgress provides progress) {
        Row(
            Modifier.fillMaxWidth().padding(top = topInset).height(LiteMetrics.PageToolbar).padding(start = if (back == null) 16.dp else 6.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (back == null) 8.dp else 6.dp),
        ) {
            if (toolbarContent != null) toolbarContent() else {
            if (back != null) LiteIconButton(backLabel, FullGlyphs.arrowBack, back)
            LiteText(title, Modifier.weight(1f), style = if (back == null) LiteType.Title else LiteType.Headline, maxLines = 1)
            actions()
            }
        }
        }
    }
}

@Composable
private fun LiteHeaderBackdrop(topInset: Dp, modifier: Modifier) {
    val visuals = LocalLiteVisuals.current
    val source = LocalLiteBackdrop.current
    val shape = rememberLiteShape(LiteCorner.None)
    val layer = remember { Paint() }
    if (visuals.material == MaterialTier.Solid) {
        Box(modifier.drawWithCache {
            val start = topInset.toPx() / size.height
            val curve = androidx.compose.animation.core.CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
            val stops = Array(9) { i ->
                val t = i / 8f
                (start + (1 - start) * t) to visuals.page.copy(alpha = if (visuals.aurora) 0f else 0.9f * (1 - curve.transform(t)))
            }
            val scrim = Brush.verticalGradient(*stops)
            onDrawBehind { drawRect(scrim) }
        })
        return
    }
    Box(modifier.drawWithCache {
        val full = (topInset + LiteMetrics.PageToolbar).toPx() * 0.35f / size.height
        val stops = Array(17) { i ->
            val t = i / 16f
            (full + (1 - full) * t) to Color.White.copy(alpha = 1 - t * t * (3 - 2 * t))
        }
        val mask = Brush.verticalGradient(*stops)
        onDrawWithContent {
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), layer)
        drawContent()
        drawRect(mask, blendMode = BlendMode.DstIn)
        drawContext.canvas.restore()
        }
    }.drawPlainBackdrop(
        backdrop = source,
        shape = { shape },
        effects = { blur(12.dp.toPx()) },
    ))
}
