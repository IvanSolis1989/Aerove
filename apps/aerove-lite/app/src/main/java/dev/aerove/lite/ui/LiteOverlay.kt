package dev.aerove.lite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

internal class LiteOverlayState {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
    fun dismiss() { content = null }
}

internal val LocalLiteOverlay = staticCompositionLocalOf<LiteOverlayState> { error("LiteApp overlay host is missing") }
