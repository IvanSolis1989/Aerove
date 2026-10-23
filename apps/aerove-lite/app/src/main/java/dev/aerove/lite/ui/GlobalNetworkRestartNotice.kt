package dev.aerove.lite.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** One presentation per required episode, independent of route and recomposition. */
internal data class NetworkRestartNoticeEpisode(val required: Boolean = false, val dismissed: Boolean = false) {
    val visible: Boolean get() = required && !dismissed
    fun observe(required: Boolean): NetworkRestartNoticeEpisode =
        if (required == this.required) this else NetworkRestartNoticeEpisode(required)
    fun dismiss(): NetworkRestartNoticeEpisode = copy(dismissed = true)
}

private val restartWarningGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(12, 1.6, 3), vertex(21.9, 20.3, 2.5), vertex(2.1, 20.3, 2.5)), closed = true),
    glyphPolyline(listOf(vertex(12, 9), vertex(12, 12.1)), role = LiteGlyphRole.Detail),
    glyphCircle(12, 16.2, 1.4, role = LiteGlyphRole.Detail, solid = true),
))

/** Standard VpnManager -> StatusManager warning; mount once outside navigation scenes. */
@Composable
internal fun GlobalNetworkRestartNotice(required: Boolean, onRestart: () -> Unit, covered: Boolean = false) {
    var episode by rememberSaveable(stateSaver = listSaver(
        save = { listOf(it.required, it.dismissed) },
        restore = { NetworkRestartNoticeEpisode(it[0], it[1]) },
    )) { mutableStateOf(NetworkRestartNoticeEpisode()) }
    val currentRestart by rememberUpdatedState(onRestart)
    LaunchedEffect(required) { episode = episode.observe(required) }
    val visible = required && episode.visible && !covered
    var dragging by remember { mutableStateOf(false) }
    val accessibility = LocalAccessibilityManager.current
    val duration = accessibility?.calculateRecommendedTimeoutMillis(6000L, containsIcons = true,
        containsText = true, containsControls = true) ?: 6000L
    LaunchedEffect(visible, dragging, duration) {
        if (visible && !dragging) { delay(duration); episode = episode.dismiss() }
    }
    val visuals = LocalLiteVisuals.current
    val warning = remember(visuals.dark) { Color(TonalPalette.fromInt(0xffff9800.toInt()).tone(if (visuals.dark) 80 else 40)) }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp)) {
        if (!covered) AnimatedVisibility(visible, Modifier.align(Alignment.TopEnd),
            enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { -16 } + scaleIn(tween(450), initialScale = 0.94f),
            exit = fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.94f)) {
            var offset by remember { mutableFloatStateOf(0f) }
            var cardWidth by remember { mutableIntStateOf(0) }
            val dismissNotice = { episode = episode.dismiss(); Unit }
            LitePane(Modifier.widthIn(max = 500.dp).heightIn(min = 52.dp)
                .offset { IntOffset(offset.roundToInt(), 0) }
                .onSizeChanged { cardWidth = it.width }
                .alpha((1f - abs(offset) / cardWidth.coerceAtLeast(1)).coerceIn(0f, 1f))
                .testTag("global-network-restart")
                .semantics { liveRegion = LiveRegionMode.Polite; dismiss { dismissNotice(); true } }
                .pointerInput(Unit) {
                    val velocity = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; velocity.resetTracking() },
                        onDragCancel = { dragging = false; offset = 0f },
                        onDragEnd = {
                            val speed = velocity.calculateVelocity().x
                            if (abs(offset) >= size.width * 0.35f ||
                                (abs(speed) >= 700f && sign(speed) == sign(offset))) dismissNotice()
                            dragging = false; offset = 0f
                        },
                    ) { change, delta ->
                        velocity.addPosition(change.uptimeMillis, change.position)
                        change.consume(); offset += delta
                    }
                }, corner = LiteCorner.Block) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteIcon(restartWarningGlyph, color = warning, size = 20.dp)
                    LiteText(stringResource(R.string.network_restart_detail), Modifier.weight(1f),
                        style = LiteType.Callout.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                    LiteButton(stringResource(R.string.network_restart), {
                        if (required && !covered && episode.visible) { episode = episode.dismiss(); currentRestart() }
                    }, Modifier.testTag("global-network-restart-action"), selected = true)
                }
            }
        }
    }
}
