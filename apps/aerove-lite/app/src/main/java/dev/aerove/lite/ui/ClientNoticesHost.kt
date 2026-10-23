package dev.aerove.lite.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.toArgb
import dev.aerove.lite.ui.colorutilities.Hct
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.notices.*
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal val LocalClientNotices = staticCompositionLocalOf<ClientNotices?> { null }
private val noticeEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val noticeEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val noticeEaseIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val noticeEaseInCubic = CubicBezierEasing(0.32f, 0f, 0.67f, 0f)
private val successGlyph = LiteGlyph(listOf(glyphCircle(12, 12, 8.8),
    glyphPolyline(listOf(vertex(7.59, 12.3), vertex(10.49, 15.2), vertex(16.41, 8.79)), role = LiteGlyphRole.Detail)))
private val warningGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(12, 1.6, 3), vertex(21.9, 20.3, 2.5), vertex(2.1, 20.3, 2.5)), closed = true),
    glyphPolyline(listOf(vertex(12, 9), vertex(12, 12.1)), role = LiteGlyphRole.Detail),
    glyphCircle(12, 16.2, 1.4, role = LiteGlyphRole.Detail, solid = true)))
private val errorGlyph = LiteGlyph(listOf(glyphCircle(12, 12, 8.8),
    glyphPolyline(listOf(vertex(12, 7.3), vertex(12, 12.2)), role = LiteGlyphRole.Detail),
    glyphCircle(12, 16.5, 1.4, role = LiteGlyphRole.Detail, solid = true)))

/** Mount once outside navigation. The existing owner remains responsible for disposal. */
@Composable
internal fun ClientNoticesHost(
    notices: ClientNotices, modifier: Modifier = Modifier, active: Boolean = true,
    covered: Boolean = false, topOffset: Dp = 0.dp,
) {
    val state by notices.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val presenting = active && resumed && !covered
    val reduced = LocalLiteReducedMotion.current
    val enterShift = with(LocalDensity.current) { -16.dp.roundToPx() }
    LaunchedEffect(notices, presenting, reduced) {
        notices.setReducedMotion(reduced)
        notices.setForeground(presenting)
    }
    DisposableEffect(notices) { onDispose { notices.setForeground(false) } }
    LaunchedEffect(notices, state.nextDeadlineMillis, presenting) {
        if (presenting) notices.delayUntilNextEvent()?.let { delay(it); notices.advance() }
    }
    if (!presenting) return
    Box(modifier.fillMaxSize().statusBarsPadding().padding(top = topOffset + 8.dp, start = 4.dp, end = 4.dp)) {
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            state.entries.forEach { entry -> key(entry.notice.id) {
                val transition = remember { MutableTransitionState(false) }
                transition.targetState = entry.visible
                AnimatedVisibility(transition,
                    enter = expandVertically(tween(if (reduced) 0 else 360, easing = noticeEase), expandFrom = Alignment.Top, clip = false) +
                        fadeIn(tween(if (reduced) 0 else 225, easing = noticeEaseOut)) +
                        slideInVertically(tween(if (reduced) 0 else 450, easing = noticeEase)) { enterShift } +
                        scaleIn(tween(if (reduced) 0 else 450, easing = noticeEase), initialScale = 0.94f),
                    exit = shrinkVertically(tween(if (reduced) 0 else if (entry.swiped) 200 else 180), shrinkTowards = Alignment.Top, clip = false) +
                        fadeOut(tween(if (reduced) 0 else 150, delayMillis = if (reduced) 0 else 150, easing = noticeEaseIn)) +
                        slideOutVertically(tween(if (reduced || entry.swiped) 0 else 180, easing = noticeEaseInCubic)) { if (entry.swiped) 0 else enterShift } +
                        scaleOut(tween(if (reduced || entry.swiped) 0 else 180, easing = noticeEaseInCubic), targetScale = if (entry.swiped) 1f else 0.94f)) {
                    ClientNoticeCard(entry, notices, reduced)
                }
            } }
        }
    }
}

@Composable
private fun ClientNoticeCard(entry: ClientNoticeEntry, notices: ClientNotices, reduced: Boolean) {
    val notice = entry.notice
    val visuals = LocalLiteVisuals.current
    val highContrast = LocalLiteHighContrast.current
    val colors = MaterialTheme.colorScheme
    val fill = if (notice.level == NoticeLevel.Error) colors.errorContainer
        else if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
    val menuTint = remember(colors.surfaceContainer, colors.surfaceBright, visuals.dark) {
        if (!visuals.dark) colors.surfaceBright else {
            val hct = Hct.fromInt(colors.surfaceContainer.toArgb())
            Color(Hct.from(hct.hue, hct.chroma, (hct.tone + 7).coerceAtMost(100.0)).toInt())
        }
    }
    val ink = if (notice.level == NoticeLevel.Error) colors.onErrorContainer else colors.onSurfaceVariant
    val glyph = when (notice.level) {
        NoticeLevel.Info -> null; NoticeLevel.Success -> successGlyph
        NoticeLevel.Warning -> warningGlyph; NoticeLevel.Error -> errorGlyph
    }
    val icon = remember(notice.level, visuals.dark, ink) {
        when (notice.level) {
            NoticeLevel.Success -> Color(TonalPalette.fromInt(0xff4caf50.toInt()).tone(if (visuals.dark) 80 else 40))
            NoticeLevel.Warning -> Color(TonalPalette.fromInt(0xffff9800.toInt()).tone(if (visuals.dark) 80 else 40))
            else -> ink
        }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    var offset by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    DisposableEffect(notices, notice.id) { onDispose { notices.setDragging(notice.id, false) } }
    fun release(speed: Float) {
        if (leaving) return
        val dismiss = width > 0 && (abs(offset) >= width * 0.35f ||
            (abs(speed) / density >= 700 && sign(speed) == sign(offset)))
        leaving = dismiss
        if (!dismiss) notices.setDragging(notice.id, false)
        scope.launch {
            val target = if (dismiss) sign(offset) * maxOf(width.toFloat(), abs(offset)) else 0f
            if (reduced) offset = target else animate(offset, target,
                animationSpec = tween(if (dismiss) 200 else 300, easing = if (dismiss) noticeEaseOut else noticeEase)) { value, _ -> offset = value }
            if (dismiss) notices.cancel(notice.id, swiped = true)
        }
    }
    val glass = visuals.material == MaterialTier.Clear && !highContrast
    val shape = rememberLiteShape(LiteCorner.Block)
    val card = Modifier.shadow(if (glass) 0.dp else 3.dp, shape, clip = false,
        ambientColor = Color.Black.copy(alpha = 0.6f), spotColor = Color.Black.copy(alpha = 0.6f)).widthIn(max = 500.dp).heightIn(min = 52.dp)
        .offset { IntOffset(offset.roundToInt(), 0) }
        .onSizeChanged { width = it.width }
        .alpha((1f - abs(offset) / width.coerceAtLeast(1)).coerceIn(0f, 1f))
        .testTag("client-notice-${notice.id}")
        .semantics(mergeDescendants = true) {
            liveRegion = if (notice.level == NoticeLevel.Error) LiveRegionMode.Assertive else LiveRegionMode.Polite
            dismiss { notices.cancel(notice.id); true }
        }
        .then(if (!entry.visible) Modifier.clearAndSetSemantics {} else Modifier)
        .pointerInput(notice.id, entry.visible) {
            val velocity = VelocityTracker()
            detectHorizontalDragGestures(onDragStart = {
                if (entry.visible && !leaving) { velocity.resetTracking(); notices.setDragging(notice.id, true) }
            }, onDragEnd = { release(velocity.calculateVelocity().x) }, onDragCancel = { release(0f) }) { change, delta ->
                if (entry.visible && !leaving) { velocity.addPosition(change.uptimeMillis, change.position); change.consume(); offset += delta }
            }
        }
    Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp)) {
        CompositionLocalProvider(LocalLiteVisuals provides if (highContrast) visuals.copy(material = MaterialTier.Solid) else visuals) {
            LitePane(card, corner = LiteCorner.Block, solidColor = fill,
                glassStyle = AeroveGlassStyle.menu(if (notice.level == NoticeLevel.Error) fill.copy(alpha = 0.16f).opticsColor() else menuTint.copy(alpha = 0.24f).opticsColor()),
                rim = LiteGlassRim.menu(visuals.dark), shadows = if (glass) fullMenuShadows(visuals.dark) else emptyList()) {
                CompositionLocalProvider(LocalLiteInkContrast provides if (!visuals.dark) 5f else LocalLiteInkContrast.current) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (glyph != null) { LiteIcon(glyph, Modifier.testTag("client-notice-icon-${notice.id}"), color = icon, size = 20.dp); Spacer(Modifier.width(12.dp)) }
                        LiteText(notice.text, Modifier.weight(1f, fill = false),
                            style = LiteType.Label.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 3)
                        notice.actionLabel?.let { label ->
                            Spacer(Modifier.width(16.dp))
                            LitePane(Modifier.heightIn(min = 32.dp).testTag("client-notice-action-${notice.id}")
                                .clickable(enabled = entry.visible && !leaving, role = Role.Button) { notices.performAction(notice.id) },
                                corner = LiteCorner.Control, selected = true) {
                                LiteText(label, Modifier.align(Alignment.Center).padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = LiteType.Label, color = colors.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
