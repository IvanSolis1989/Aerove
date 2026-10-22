package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfilePropertiesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val initial = ProfileSummary("profile-fixture", "Original", "https://example.invalid/sub", 0,
        subscriptionUserInfo = "upload=1048576; download=1048576; total=4194304; expire=1700000000")
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false),
        profiles = listOf(initial)))
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }

    private fun launch() {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) }
        }
        compose.onNodeWithText(context.getString(R.string.lite_tab_profiles)).performClick()
    }

    private fun openProperties() {
        compose.onNodeWithTag("profile-${initial.id}").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.profile_properties)).performClick()
    }

    @Test fun rootPropertiesValidatesMinutesAndClosesOnlyAfterThePersistedMetadataReadBack() {
        launch()
        openProperties()
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).performTextReplacement("Changed")
        compose.onNodeWithContentDescription(context.getString(R.string.profile_update_minutes)).performTextReplacement("0")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.profile_interval_invalid)).assertIsDisplayed()
        assertTrue(actions.events.none { it.startsWith("updateProfileMetadata:") })
        compose.onNodeWithContentDescription(context.getString(R.string.profile_update_minutes)).performTextReplacement("5")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        assertTrue(actions.events.contains("updateProfileMetadata:${initial.id}:Changed:${initial.url}:true:300000"))
        compose.onNodeWithTag("profile-property-save").assertExists()
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("profile:${initial.id}")) }
        compose.runOnUiThread {
            state.value = state.value.copy(profiles = listOf(initial.copy(name = "Changed", updateIntervalMillis = 300_000)), busy = emptySet())
        }
        compose.onNodeWithTag("profile-property-save").assertDoesNotExist()
        compose.onNodeWithText("Changed").assertIsDisplayed()
    }

    @Test fun rootFailedSavePreservesTheInputAndCancelUpdateRemainsUsableWhileBusy() {
        launch()
        openProperties()
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).performTextReplacement("Draft survives")
        compose.onNodeWithTag("profile-property-save").performScrollTo().performClick()
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("profile:${initial.id}")) }
        compose.runOnUiThread { state.value = state.value.copy(busy = emptySet(), message = "fixture: disk failure") }
        compose.onNodeWithText(context.getString(R.string.lite_dismiss)).performClick()
        compose.runOnUiThread { state.value = state.value.copy(message = null) }
        compose.onNodeWithContentDescription(context.getString(R.string.profile_name)).assertTextEquals("Draft survives")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("profile:${initial.id}"), updatingProfileIds = setOf(initial.id)) }
        compose.onNodeWithTag("profile-cancel-${initial.id}").performClick()
        assertTrue(actions.events.contains("cancelProfileUpdate:${initial.id}"))
    }

    @Test fun rootUsageDetailsFollowTheLatestHeaderWithoutSyntheticTotals() {
        launch()
        compose.onNodeWithTag("profile-usage-${initial.id}").performClick()
        compose.onNodeWithText(context.getString(R.string.profile_usage_total)).assertIsDisplayed()
        compose.onNodeWithText(formatBytes(4_194_304)).assertIsDisplayed()
        compose.runOnUiThread {
            state.value = state.value.copy(profiles = listOf(initial.copy(subscriptionUserInfo = "upload=1; total=8388608; expire=0")))
        }
        compose.onNodeWithText(formatBytes(8_388_608)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.profile_never_expires)).assertIsDisplayed()
        assertTrue(actions.events.none { it.startsWith("updateProfile:") })
    }
}
