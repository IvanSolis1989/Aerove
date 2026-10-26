package dev.aerove.lite.ui

import android.app.Dialog
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import dev.aerove.lite.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LiteWindowFocusUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realOwnedPopupRetainsFocusButUnregisteredDialogPausesItWithoutAnActivityPause() {
        val popup = mutableStateOf(false)
        var focused = false
        var rootFocused = false
        compose.replaceAppContent {
            val window = LocalWindowInfo.current
            val currentRootFocused = window.isWindowFocused
            SideEffect { rootFocused = currentRootFocused }
            LiteWindowFocusHost { active ->
                SideEffect { focused = active }
                Box(Modifier.fillMaxSize()) {
                    if (popup.value) Popup(properties = PopupProperties(focusable = true)) {
                        LiteOwnedPopupFocus()
                        Text("owned popup")
                    }
                }
            }
        }
        compose.waitUntil { focused }
        compose.runOnUiThread { popup.value = true }
        compose.waitUntil { !rootFocused && focused }
        var dialog: Dialog? = null
        try {
            compose.runOnUiThread {
                dialog = Dialog(compose.activity).apply {
                    setContentView(TextView(context).apply { text = "external focus stand-in" })
                    show()
                }
            }
            compose.waitUntil { !focused }
            assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
            compose.runOnUiThread { dialog?.dismiss(); dialog = null }
            compose.waitUntil { focused }
            compose.runOnUiThread { popup.value = false }
            compose.waitUntil { rootFocused && focused }
        } finally { compose.runOnUiThread { dialog?.dismiss() } }
    }

    @Test fun ownedDetailCollectionStillStopsOnFocusLossAndCatchesLatestOnReturn() {
        val focus = mutableStateOf(true)
        val values = MutableStateFlow(1)
        var shown = 0
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteWindowFocused provides focus.value, LocalLitePageActive provides false,
                LocalDiagnosticsForeground provides false) {
                val current = displayValue(values, 0, ownedOverlay = true).value
                SideEffect { shown = current }
            }
        }
        compose.waitUntil { shown == 1 }
        compose.runOnUiThread { focus.value = false }
        compose.waitForIdle()
        compose.runOnUiThread { values.value = 2 }
        compose.waitForIdle()
        assertEquals(1, shown)
        assertEquals(Lifecycle.State.RESUMED, compose.activity.lifecycle.currentState)
        compose.runOnUiThread { focus.value = true }
        compose.waitUntil { shown == 2 }
    }
}
