package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BackgroundPolicyUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    @Test fun cancelledSsidDraftDoesNotPublishAndSavedExactValueReadsBack() {
        val preferences = mutableStateOf(BackgroundPolicyPreferences())
        var saved: String? = null
        compose.replaceAppContent {
            BackgroundPolicyFixture {
                BackgroundPolicyScreen(preferences.value, { value ->
                    saved = BackgroundPolicyJson.encode(value).toString()
                    preferences.value = BackgroundPolicyJson.decode(JSONObject(checkNotNull(saved)))
                }, false, false, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.background_add)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" Office ")
        compose.onNodeWithText(text(R.string.background_cancel)).performClick()
        compose.runOnIdle { assertNull(saved); assertTrue(preferences.value.excludeSSIDs.isEmpty()) }
        compose.onNodeWithText(text(R.string.background_add)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput(" Office ")
        compose.onNodeWithText(text(R.string.background_save)).performClick()
        compose.runOnIdle {
            assertEquals(listOf(" Office "), BackgroundPolicyJson.decode(JSONObject(checkNotNull(saved))).excludeSSIDs)
        }
    }

    @Test fun prerequisiteActionsRemainReachableAfterDenial() {
        var locationRequests = 0
        var batteryRequests = 0
        compose.replaceAppContent {
            BackgroundPolicyFixture {
                BackgroundPolicyScreen(BackgroundPolicyPreferences(), {}, false, false,
                    { locationRequests++ }, { batteryRequests++ })
            }
        }
        compose.onAllNodesWithText(text(R.string.background_authorize))[0].performClick()
        compose.onAllNodesWithText(text(R.string.background_authorize))[1].performClick()
        compose.runOnIdle { assertEquals(1, locationRequests); assertEquals(1, batteryRequests) }
    }
}

@Composable
private fun BackgroundPolicyFixture(content: @Composable () -> Unit) {
    LiteTheme(LiteSettings()) {
        val source = rememberLayerBackdrop()
        val overlay = remember { LiteOverlayState() }
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(LocalLiteVisuals.current.page).layerBackdrop(source))
            CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source, LocalLiteOverlay provides overlay) {
                content()
                overlay.content?.invoke()
            }
        }
    }
}
