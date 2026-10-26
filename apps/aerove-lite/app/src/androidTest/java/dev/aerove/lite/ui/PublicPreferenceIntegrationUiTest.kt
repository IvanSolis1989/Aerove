package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class PublicPreferenceIntegrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "public-preferences-${UUID.randomUUID()}")
    private val state = mutableStateOf(LiteState())
    private fun text(id: Int) = context.getString(id)
    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    @After fun clean() { directory.deleteRecursively() }
    private fun launchAgents() {
        val actions = UiTestActions { settings ->
            val saved = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).save(settings) }
            state.value = state.value.copy(settings = saved)
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNodeWithText(text(R.string.lite_general)).performClick()
        compose.onNodeWithText(text(R.string.requests_user_agent)).performClick()
        compose.onNodeWithTag("user-agents-list").assertIsDisplayed()
    }
    @Test fun actualGeneralRouteSelectsAddsAndReadsPersistentUserAgentThenCancelsEditing() {
        launchAgents()
        val original = state.value.settings.requests.userAgents.first()
        compose.onNodeWithTag("user-agent-$original").performClick()
        assertEquals(original, read().requests.globalUserAgent)
        compose.onNodeWithTag("user-agent-default").performClick()
        assertNull(read().requests.globalUserAgent)
        compose.onNodeWithText(text(R.string.requests_add)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Saved/6")
        compose.onNodeWithText(text(R.string.requests_save)).performClick()
        assertTrue("Only persisted success closes the overlay", "Saved/6" in read().requests.userAgents)
        compose.onNodeWithTag("user-agent-Saved/6").performTouchInput { longClick() }
        compose.onNodeWithTag("user-agent-menu").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.requests_edit)).performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("NotSaved/7")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertFalse("Back discards only the draft", "NotSaved/7" in read().requests.userAgents)
        assertTrue("Saved/6" in read().requests.userAgents)
    }
    @Test fun sortHandleDragPersistsOrderAndDefaultRowStaysOutsideSortableItems() {
        launchAgents()
        val before = state.value.settings.requests.userAgents
        compose.onNodeWithText(text(R.string.profile_list_sort)).performClick()
        val distance = compose.onNodeWithTag("user-agent-${before.first()}").fetchSemanticsNode().boundsInRoot.height * 1.5f
        compose.onNodeWithTag("user-agent-drag-${before.first()}", useUnmergedTree = true).performTouchInput {
            down(center); moveBy(Offset(0f, distance)); up()
        }
        compose.waitForIdle()
        val saved = read().requests.userAgents
        assertEquals(before.toSet(), saved.toSet())
        assertNotEquals(before, saved)
        compose.onNodeWithTag("user-agent-default").assertIsDisplayed()
    }
}
