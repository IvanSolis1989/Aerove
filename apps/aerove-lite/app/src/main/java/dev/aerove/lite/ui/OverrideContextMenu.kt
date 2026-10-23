package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Same anchored menu surface, scrim and one-scroll-owner policy as ProfileContextMenu. */
@Composable
internal fun OverrideContextMenu(anchor: Rect, title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = dismiss)
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.32f)).clickable(onClick = dismiss)) {
        val width = minOf(300.dp, maxWidth - 32.dp)
        val left = with(density) { anchor.left.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = with(density) { anchor.top.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxHeight - 480.dp))
        val availableHeight = maxHeight - top - 16.dp
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.offset(left, top).width(width).pointerInput(Unit) { detectTapGestures {} }, corner = LiteCorner.Control,
            solidColor = fill, glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).semantics { paneTitle = title }, content = content)
        }
    }
}
