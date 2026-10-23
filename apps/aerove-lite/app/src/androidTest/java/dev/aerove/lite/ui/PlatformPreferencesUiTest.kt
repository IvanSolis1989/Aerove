package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PlatformPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    @Test fun startupConsentPersistsOnlyAfterExactRestatementAndCancelLeavesOriginalChoice() {
        val directory = File(context.cacheDir, "consent-${UUID.randomUUID()}")
        try {
            val state = mutableStateOf(LiteState(initialized = true))
            val actions = UiTestActions { wanted ->
                val actual = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).save(wanted) }
                state.value = state.value.copy(settings = actual)
            }
            compose.replaceAppContent { LiteApp(state.value, actions) }
            compose.onNodeWithText(text(R.string.maintenance_agree)).performClick()
            compose.onNodeWithText(text(R.string.maintenance_confirm)).assertIsNotEnabled()
            compose.onNodeWithText(text(R.string.maintenance_cancel)).performClick()
            assertEquals(0, runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read().acceptedDisclaimerVersion })
            compose.onNodeWithText(text(R.string.maintenance_agree)).performClick()
            compose.onNodeWithContentDescription(text(R.string.maintenance_disclaimer_restate_hint))
                .performTextInput(text(R.string.maintenance_disclaimer_no_service_statement))
            compose.onNodeWithText(text(R.string.maintenance_confirm)).performClick()
            assertEquals(1, runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read().acceptedDisclaimerVersion })
            compose.onNodeWithText(text(R.string.maintenance_agree)).assertDoesNotExist()
        } finally { directory.deleteRecursively() }
    }
    @Test fun externalProfileConfirmationCancelsOnBackAndImportsOnlyOnExplicitAction() {
        val url = "https://example.com/subscription"
        val state = mutableStateOf(LiteState(initialized = true, settings = LiteSettings(acceptedDisclaimerVersion = 1), externalProfile = url))
        var accepted = 0
        val actions = object : LiteActions by UiTestActions({}) {
            override fun dismissExternalProfile() { state.value = state.value.copy(externalProfile = null) }
            override fun acceptExternalProfile() { accepted++; dismissExternalProfile() }
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        compose.onNodeWithText(text(R.string.integration_external_profile_confirm).format(url)).assertIsDisplayed()
        assertEquals(0, accepted)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertNull(state.value.externalProfile)
        assertEquals(0, accepted)
        compose.runOnUiThread { state.value = state.value.copy(externalProfile = url) }
        compose.onNodeWithText(text(R.string.lite_import)).performClick()
        assertEquals(1, accepted)
        assertNull(state.value.externalProfile)
    }
}
