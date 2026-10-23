package dev.aerove.lite.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.aerove.lite.navigation.*

internal val LocalLitePageActive = staticCompositionLocalOf { true }

private class TabSceneLifecycle(parent: Lifecycle.State, active: Boolean) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    init { update(parent, active) }
    fun update(parent: Lifecycle.State, active: Boolean) {
        registry.currentState = if (active || parent < Lifecycle.State.CREATED) parent else Lifecycle.State.CREATED
    }
    fun close() { registry.currentState = Lifecycle.State.DESTROYED }
}

/** Only the two transition scenes are placed; visited keyed compositions retain their view state. */
@Composable
internal fun <K : Any> LiteTabTransitionHost(
    items: List<K>, selected: K, animation: TabAnimation, active: Boolean,
    modifier: Modifier = Modifier, visible: Boolean = true, pageActive: Boolean = visible,
    content: @Composable (K, Boolean) -> Unit,
) {
    val target = selected.takeIf { it in items } ?: items.firstOrNull() ?: return
    val visited = remember { mutableStateListOf(target) }
    val keys = (visited + target).distinct().filter { it in items }
    SideEffect { if (target !in visited) visited.add(target); visited.removeAll { it !in items } }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val reduced = LocalLiteReducedMotion.current
    val parent = LocalLifecycleOwner.current.lifecycle
    val foreground = active && visible
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LaunchedEffect(target, items, animation, foreground, reduced) {
        val source = if (from in items && to in items) retargetTab(from, to, progress.value, items) else target
        if (!foreground || reduced || source == target) {
            from = target; to = target; progress.snapTo(1f)
        } else {
            from = source; to = target; progress.snapTo(0f)
            progress.animateTo(1f, tween(tabDurationMillis(animation), easing = if (animation == TabAnimation.Fade)
                CubicBezierEasing(0.42f, 0f, 0.58f, 1f) else CubicBezierEasing(0f, 0f, 0.58f, 1f)))
            from = target
        }
    }
    val direction = if (items.indexOf(to) >= items.indexOf(from)) 1 else -1
    Layout(modifier = modifier.clipToBounds(), content = {
        keys.forEach { item -> key(item) {
            val logicalActive = item == target && foreground
            val latestActive by rememberUpdatedState(logicalActive)
            val owner = remember(parent) { TabSceneLifecycle(parent.currentState, logicalActive) }
            SideEffect { owner.update(parent.currentState, logicalActive) }
            DisposableEffect(parent, owner) {
                val observer = LifecycleEventObserver { _, _ -> owner.update(parent.currentState, latestActive) }
                parent.addObserver(observer)
                onDispose { parent.removeObserver(observer); owner.close() }
            }
            val sceneModifier = Modifier.fillMaxSize().testTag("tab-scene-$item").graphicsLayer {
                val frame = if (from == to) TabSceneFrame(0f, 1f)
                    else tabSceneFrame(animation, item == to, direction, progress.value)
                translationX = frame.offset * size.width * if (rtl) -1 else 1
                // Backdrop panes keep sampling their explicit external source before this scene is composited.
                alpha = frame.opacity
            }.focusProperties { canFocus = logicalActive }.then(if (logicalActive) Modifier else
                Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                })
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalLitePageActive provides (item == target && pageActive),
                LocalLiteReducedMotion provides (reduced || !logicalActive),
                LocalDiagnosticsForeground provides (LocalDiagnosticsForeground.current && logicalActive)) {
                Box(sceneModifier) { content(item, logicalActive) }
            }
        } }
    }) { children, constraints ->
        val measured = children.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (visible) (listOf(from, to).distinct()).forEach { item ->
                keys.indexOf(item).takeIf { it >= 0 }?.let { measured[it].place(0, 0) }
            }
        }
    }
}
