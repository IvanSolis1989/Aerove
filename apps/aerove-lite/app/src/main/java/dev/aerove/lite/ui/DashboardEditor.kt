package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private fun editorWidgets(items: List<DashboardWidget>): List<DashboardWidget> =
    if (DashboardWidget.CurrentOutbound in items) items.distinct().filter { it != DashboardWidget.NetworkSpeed } else listOf(DashboardWidget.CurrentOutbound) + items.distinct().filter { it != DashboardWidget.NetworkSpeed }

private fun List<DashboardWidget>.move(from: Int, to: Int): List<DashboardWidget> = toMutableList().apply {
    if (from in indices && to in indices && from != to) add(to, removeAt(from))
}

private val DashboardEditorBadgeCorner = 14.dp

@Composable
fun DashboardEditor(
    settings: LiteSettings,
    onChanged: (LiteSettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    cardContent: (@Composable (DashboardWidget) -> Unit)? = null,
) {
    val current by rememberUpdatedState(settings)
    var names by rememberSaveable { mutableStateOf(editorWidgets(settings.dashboardWidgets).map { it.wireName }.toTypedArray()) }
    val shown = names.mapNotNull { name -> DashboardWidget.entries.find { it.wireName == name } }
    val addable = DashboardWidget.entries.filter { it !in shown && it != DashboardWidget.NetworkSpeed }
    val list = rememberLazyListState()
    var dragging by remember { mutableStateOf<DashboardWidget?>(null) }
    var origin by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val reduced = LocalLiteReducedMotion.current
    val animate = resumed && !reduced
    val phase: State<Float> = if (animate) rememberInfiniteTransition(label = "dashboardEdit").animateFloat(
        0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "dashboardSway",
    ) else remember { mutableStateOf(0f) }

    fun update(items: List<DashboardWidget>) { names = editorWidgets(items).map { it.wireName }.toTypedArray() }
    fun stopDrag() { dragging = null; distance = 0f }
    fun reorderHeld() {
        val held = dragging ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val info = visible.find { it.key == held.wireName } ?: return
        val center = origin + distance + info.size / 2f
        val target = visible.filter { candidate -> names.any { it == candidate.key } }
            .minByOrNull { abs(center - it.offset - it.size / 2f) } ?: return
        val from = names.indexOf(held.wireName)
        val to = names.indexOf(target.key)
        if (from >= 0 && to >= 0 && from != to) update(names.mapNotNull { name -> DashboardWidget.entries.find { it.wireName == name } }.move(from, to))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) stopDrag()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(dragging) {
        if (dragging != null) snapshotFlow {
            list.layoutInfo.visibleItemsInfo.find { it.key == dragging?.wireName }?.let { held ->
                val top = origin + distance
                val bottom = top + held.size
                when {
                    top < list.layoutInfo.viewportStartOffset + edge && list.canScrollBackward -> -speed
                    bottom > list.layoutInfo.viewportEndOffset - edge && list.canScrollForward -> speed
                    else -> 0f
                }
            } ?: 0f
        }.collectLatest { amount ->
            if (amount != 0f) while (isActive) {
                list.scrollBy(amount)
                reorderHeld()
                delay(16)
            }
        }
    }
    BackHandler(onBack = onClose)
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LiteIconButton(stringResource(R.string.lite_cancel), FullGlyphs.close, onClose)
            LiteText(stringResource(R.string.lite_dashboard_edit), Modifier.weight(1f), style = LiteType.Title)
            LiteIconButton(stringResource(R.string.lite_confirm), FullGlyphs.check, {
                val selected = editorWidgets(names.mapNotNull { name -> DashboardWidget.entries.find { it.wireName == name } })
                if (selected != current.dashboardWidgets) onChanged(current.copy(dashboardWidgets = selected))
                onClose()
            })
        }
        LazyColumn(Modifier.weight(1f).testTag("dashboard-editor"), state = list,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = dragging == null) {
            itemsIndexed(shown, key = { _, item -> item.wireName }) { index, item ->
                val title = dashboardEditorLabel(item)
                val position = stringResource(R.string.lite_dashboard_position, index + 1, shown.size)
                val required = stringResource(R.string.lite_dashboard_required)
                val up = stringResource(R.string.lite_dashboard_move_up)
                val down = stringResource(R.string.lite_dashboard_move_down)
                val held = dragging == item
                val turn = item.ordinal * 1.7
                val swings = when (item.ordinal % 3) { 0 -> 6; 1 -> 7; else -> 5 }
                val motion = Modifier.graphicsLayer {
                    val angle = if (animate && !held) phase.value * 2 * PI * swings + turn else 0.0
                    rotationZ = if (animate && !held) (sin(angle) * 0.7).toFloat() else 0f
                    translationY = if (held) origin + distance - (list.layoutInfo.visibleItemsInfo.find { it.key == item.wireName }?.offset?.toFloat() ?: origin)
                        else if (animate) (cos(angle) * 1.25 * density.density).toFloat() else 0f
                    scaleX = if (held) 1.03f else 1f
                    scaleY = scaleX
                }
                Box(Modifier.fillMaxWidth().animateItem(
                    fadeInSpec = tween(if (reduced) 0 else 200), fadeOutSpec = tween(if (reduced) 0 else 200),
                    placementSpec = if (held) null else tween(if (reduced) 0 else 260),
                ).zIndex(if (held) 1f else 0f).then(motion).testTag("dashboard-row-${item.wireName}")
                    .semantics {
                        contentDescription = title
                        stateDescription = if (item == DashboardWidget.CurrentOutbound) "$required; $position" else position
                        customActions = buildList {
                            if (index > 0) add(CustomAccessibilityAction(up) { update(shown.move(index, index - 1)); true })
                            if (index < shown.lastIndex) add(CustomAccessibilityAction(down) { update(shown.move(index, index + 1)); true })
                        }
                    }) {
                    if (cardContent != null) Box(Modifier.fillMaxWidth().clearAndSetSemantics {}) { CompositionLocalProvider(LocalDashboardFusion provides false) { cardContent(item) } }
                    else LitePane(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LiteIcon(dashboardEditorGlyph(item))
                            LiteText(title, Modifier.weight(1f))
                            LiteIcon(DashboardEditorGlyphs.drag, color = LocalLiteVisuals.current.secondaryInk)
                        }
                    }
                    Box(Modifier.matchParentSize().pointerInput(item) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                val info = list.layoutInfo.visibleItemsInfo.find { it.key == item.wireName }
                                if (info != null) { origin = info.offset.toFloat(); distance = 0f; dragging = item }
                            }, onDragEnd = ::stopDrag, onDragCancel = ::stopDrag,
                            onDrag = { change, delta -> change.consume(); distance += delta.y; reorderHeld() },
                        )
                    })
                    if (item != DashboardWidget.CurrentOutbound) {
                        val remove = stringResource(R.string.lite_dashboard_remove, title)
                        LitePane(Modifier.align(Alignment.TopEnd).size(28.dp)
                            .semantics { contentDescription = remove }.clickable(role = Role.Button) { update(shown - item) },
                            corner = LiteCorner.Full, solidColor = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer) {
                            LiteIcon(DashboardEditorGlyphs.remove, Modifier.align(Alignment.Center), androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer, size = 16.dp)
                        }
                    }
                }
            }
            if (addable.isNotEmpty()) {
                item(key = "addable-section") {
                    LiteText(stringResource(R.string.lite_dashboard_addable), Modifier.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 10.dp),
                        style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = LocalLiteVisuals.current.secondaryInk)
                }
                itemsIndexed(addable, key = { _, item -> "add-${item.wireName}" }) { _, item ->
                    val title = dashboardEditorLabel(item)
                    val description = when (item) {
                        DashboardWidget.ConnectionStats -> stringResource(R.string.lite_dashboard_connection_stats_description)
                        DashboardWidget.MemoryInfo -> stringResource(R.string.lite_dashboard_memory_description)
                        else -> null
                    }
                    val add = stringResource(R.string.lite_dashboard_add, title)
                    val colors = androidx.compose.material3.MaterialTheme.colorScheme
                    LitePane(Modifier.fillMaxWidth().testTag("dashboard-add-${item.wireName}"), corner = LiteCorner.DashboardBlock,
                        solidColor = LocalLiteVisuals.current.surface.copy(alpha = 0.55f)) {
                        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            LitePane(Modifier.size(28.dp).semantics { contentDescription = add }
                                .clickable(role = Role.Button) { update(shown + item) }, corner = LiteCorner.Full, solidColor = colors.primary) {
                                LiteIcon(FullGlyphs.add, Modifier.align(Alignment.Center), colors.onPrimary, size = 16.dp)
                            }
                            LitePane(Modifier.size(40.dp), corner = DashboardEditorBadgeCorner) {
                                LiteIcon(dashboardEditorGlyph(item), Modifier.align(Alignment.Center), LocalLiteVisuals.current.secondaryInk, size = 20.dp)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                LiteText(title, style = LiteType.Label, maxLines = 1)
                                if (description != null) LiteText(description, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dashboardEditorLabel(item: DashboardWidget) = stringResource(when (item) {
    DashboardWidget.CurrentOutbound -> R.string.lite_status
    DashboardWidget.NetworkSpeed -> R.string.lite_network_speed
    DashboardWidget.OutboundMode -> R.string.lite_proxy_mode
    DashboardWidget.VpnButton -> R.string.lite_vpn
    DashboardWidget.IntranetIp -> R.string.lite_intranet_ip
    DashboardWidget.ServiceStatus -> R.string.lite_dashboard_service_status
    DashboardWidget.Profiles -> R.string.lite_dashboard_profiles
    DashboardWidget.ProxyGroups -> R.string.lite_dashboard_proxy_groups
    DashboardWidget.ConnectionStats -> R.string.lite_dashboard_connection_stats
    DashboardWidget.MemoryInfo -> R.string.lite_dashboard_memory
})

private fun dashboardEditorGlyph(item: DashboardWidget): LiteGlyph = when (item) {
    DashboardWidget.CurrentOutbound -> FullGlyphs.status
    DashboardWidget.NetworkSpeed -> FullGlyphs.speed
    DashboardWidget.OutboundMode -> FullGlyphs.split
    DashboardWidget.VpnButton -> FullGlyphs.vpn
    DashboardWidget.IntranetIp -> FullGlyphs.devices
    DashboardWidget.ServiceStatus -> FullGlyphs.verified
    DashboardWidget.Profiles -> FullGlyphs.profiles
    DashboardWidget.ProxyGroups -> FullGlyphs.layers
    DashboardWidget.ConnectionStats -> FullGlyphs.connections
    DashboardWidget.MemoryInfo -> DashboardEditorGlyphs.memory
}

private object DashboardEditorGlyphs {
    val remove = LiteGlyph(listOf(glyphPath("M5 12H19", role = LiteGlyphRole.Line)))
    val drag = LiteGlyph(listOf(glyphPath("M4 7H20M4 12H20M4 17H20", role = LiteGlyphRole.Line)))
    val memory = LiteGlyph(listOf(
        glyphBox(3.5, 7.2, 20.5, 16.8, 2.5),
        glyphPath("M8 7.2V3.8M12 7.2V3.8M16 7.2V3.8M8 16.8V20.2M12 16.8V20.2M16 16.8V20.2", role = LiteGlyphRole.Line),
    ))
}
