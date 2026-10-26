package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aerove.lite.LiteState
import dev.aerove.lite.integration.LiteDisplayStreams

// Null is solely the existing LiteState-based preview/androidTest injection path.
internal val LocalLiteDisplayStreams = staticCompositionLocalOf<LiteDisplayStreams?> { null }

@Composable
internal fun DiagnosticsLeaf(state: LiteState, content: @Composable (LiteState) -> Unit) {
    val display = LocalLiteDisplayStreams.current
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    Box(Modifier.onGloballyPositioned {
        val rect = it.boundsInWindow()
        visible = !rect.isEmpty && rect.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }) {
        val diagnostics = displayValue(display?.diagnostics, state.diagnostics, visible).value
        content(state.copy(diagnostics = diagnostics))
    }
}

@Composable
internal fun ConnectionsLeaf(state: LiteState, content: @Composable (LiteState) -> Unit) {
    val display = LocalLiteDisplayStreams.current
    val connections = displayValue(display?.connections, state.connections).value
    val traffic = displayValue(display?.traffic, state.traffic).value
    content(state.copy(connections = connections, traffic = traffic))
}

/** A retained but hidden scene never collects or publishes display changes. */
@Composable
internal fun <T> displayValue(flow: StateFlow<T>?, fallback: T, visible: Boolean = true, ownedOverlay: Boolean = false): State<T> {
    if (flow == null) return rememberUpdatedState(fallback)
    val active = LocalLiteWindowFocused.current && visible && (ownedOverlay || (LocalLitePageActive.current && LocalDiagnosticsForeground.current &&
        LocalLiteOverlay.current.content == null))
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(flow) { mutableStateOf(flow.value) }
    LaunchedEffect(flow, active, lifecycle) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { flow.collect { state.value = it } }
    }
    return state
}
