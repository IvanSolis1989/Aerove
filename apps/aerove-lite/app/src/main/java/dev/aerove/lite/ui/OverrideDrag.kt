package dev.aerove.lite.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** DashboardEditor gesture/edge-scroll mechanism; only drop commits, cancel restores the source order. */
internal class OverrideDragState(private val list: LazyListState) {
    val keys = mutableStateListOf<String>()
    var dragging by mutableStateOf<String?>(null)
        private set
    private var authoritative = emptyList<String>()
    private var positions = mutableMapOf<String, Int>()
    var accepting by mutableStateOf(false)
    private var origin by mutableFloatStateOf(0f)
    private var distance by mutableFloatStateOf(0f)

    fun sync(input: List<String>) {
        if (input != authoritative) { dragging = null; distance = 0f; authoritative = input; restoreKeys() }
    }
    fun start(key: String) {
        if (!accepting) return
        val info = list.layoutInfo.visibleItemsInfo.find { it.key == key } ?: return
        origin = info.offset.toFloat(); distance = 0f; dragging = key
    }
    fun move(delta: Float) { distance += delta; reorder() }
    fun reorder() {
        val key = dragging ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val held = visible.find { it.key == key } ?: return
        val center = origin + distance + held.size / 2f
        val nearest = visible.filter { it.key in positions }.minByOrNull { abs(center - it.offset - it.size / 2f) } ?: return
        val from = positions[key] ?: return; val to = positions[nearest.key] ?: return
        if (from != to) {
            keys.add(to, keys.removeAt(from))
            for (index in minOf(from, to)..maxOf(from, to)) positions[keys[index]] = index
        }
    }
    fun drop(commit: (String, Int) -> Unit) {
        if (!accepting) { cancel(); return }
        val key = dragging
        val destination = positions[key] ?: -1
        val changed = key != null && destination >= 0 && authoritative.indexOf(key) != destination
        dragging = null; distance = 0f
        if (changed) commit(key, destination) else restoreKeys()
    }
    fun cancel() { dragging = null; distance = 0f; restoreKeys() }
    private fun restoreKeys() {
        if (keys != authoritative) { keys.clear(); keys.addAll(authoritative) }
        positions = authoritative.mapIndexed { index, key -> key to index }.toMap().toMutableMap()
    }
    fun translation(key: String): Float = if (dragging == key)
        origin + distance - (list.layoutInfo.visibleItemsInfo.find { it.key == key }?.offset ?: origin.toInt()) else 0f
    fun edgeDirection(edge: Float, speed: Float): Float {
        val held = list.layoutInfo.visibleItemsInfo.find { it.key == dragging } ?: return 0f
        return when {
            origin + distance < list.layoutInfo.viewportStartOffset + edge && list.canScrollBackward -> -speed
            origin + distance + held.size > list.layoutInfo.viewportEndOffset - edge && list.canScrollForward -> speed
            else -> 0f
        }
    }
}

@Composable
internal fun rememberOverrideDrag(keys: List<String>, list: LazyListState, enabled: Boolean): OverrideDragState {
    val state = remember(list) { OverrideDragState(list).also { it.sync(keys) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val active = enabled && resumed && LocalLitePageActive.current && LocalLiteOverlay.current.content == null
    val density = LocalDensity.current
    val edge = with(density) { 48.dp.toPx() }; val speed = with(density) { 12.dp.toPx() }
    SideEffect { state.accepting = active; state.sync(keys) }
    DisposableEffect(lifecycle, state) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); if (!resumed) state.cancel() }
        lifecycle.addObserver(observer)
        onDispose { state.cancel(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) { if (!active) state.cancel() }
    LaunchedEffect(state.dragging, active) {
        if (state.dragging != null && active) snapshotFlow { state.edgeDirection(edge, speed) }.collectLatest { amount ->
            if (amount != 0f) while (isActive) { list.scrollBy(amount); state.reorder(); delay(16) }
        }
    }
    return state
}

internal fun Modifier.overrideDragVisual(state: OverrideDragState, key: String): Modifier =
    zIndex(if (state.dragging == key) 1f else 0f).graphicsLayer { translationY = state.translation(key) }

internal fun Modifier.overrideDragGesture(state: OverrideDragState, key: String, enabled: Boolean,
    immediate: Boolean = false, commit: (String, Int) -> Unit): Modifier = pointerInput(state, key, enabled, immediate) {
    if (enabled) {
        if (immediate) detectDragGestures(onDragStart = { state.start(key) }, onDragEnd = { state.drop(commit) }, onDragCancel = state::cancel,
            onDrag = { change, delta -> change.consume(); state.move(delta.y) })
        else detectDragGesturesAfterLongPress(onDragStart = { state.start(key) }, onDragEnd = { state.drop(commit) }, onDragCancel = state::cancel,
            onDrag = { change, delta -> change.consume(); state.move(delta.y) })
    }
}

internal fun Modifier.overrideMoveAccessibility(index: Int, size: Int, up: String, down: String, move: (Int) -> Unit) = semantics {
    customActions = buildList {
        if (index > 0) add(CustomAccessibilityAction(up) { move(index - 1); true })
        if (index < size - 1) add(CustomAccessibilityAction(down) { move(index + 1); true })
    }
}

internal val OverrideDragGlyph = LiteGlyph(listOf(glyphPath("M4 7H20M4 12H20M4 17H20", role = LiteGlyphRole.Line)))
