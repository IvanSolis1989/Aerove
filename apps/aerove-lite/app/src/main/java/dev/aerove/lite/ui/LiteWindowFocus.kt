package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo

internal val LocalLiteWindowFocused = staticCompositionLocalOf { true }
private val LocalLiteOwnedWindowFocus = staticCompositionLocalOf<LiteOwnedWindowFocus?> { null }

/** Only real focus of mounted application-owned windows can keep the UI observation lane active. */
internal class LiteOwnedWindowFocus {
    private val windows = mutableStateMapOf<Any, Boolean>()
    fun update(token: Any, focused: Boolean) { windows[token] = focused }
    fun remove(token: Any) { windows.remove(token) }
    fun focused(rootFocused: Boolean): Boolean = rootFocused || windows.values.any { it }
}

@Composable
internal fun LiteWindowFocusHost(content: @Composable (Boolean) -> Unit) {
    val root = LocalWindowInfo.current
    val owned = remember { LiteOwnedWindowFocus() }
    val focused = owned.focused(root.isWindowFocused)
    CompositionLocalProvider(LocalLiteWindowFocused provides focused, LocalLiteOwnedWindowFocus provides owned) {
        content(focused)
    }
}

/** A focusable Android Popup owns another View window; editor activity alone is never a focus exemption. */
@Composable
internal fun LiteOwnedPopupFocus() {
    val owned = LocalLiteOwnedWindowFocus.current ?: return
    val view = LocalView.current
    val token = remember(view) { Any() }
    DisposableEffect(owned, view, token) {
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> owned.update(token, focused) }
        observer.addOnWindowFocusChangeListener(listener)
        owned.update(token, view.hasWindowFocus())
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            owned.remove(token)
        }
    }
}

internal fun liteWallpaperVisible(windowFocused: Boolean, opaqueScan: Boolean, opaqueConsent: Boolean): Boolean =
    windowFocused && !opaqueScan && !opaqueConsent
