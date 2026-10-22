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

class LanguageScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "language-menu-${UUID.randomUUID()}")
    private val state = mutableStateOf(LiteState())
    private val store = LiteSettingsStore(directory)
    private var writes = 0
    @After fun clean() { directory.deleteRecursively() }
    private fun text(id: Int) = context.getString(id)
    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    private fun launchTools() {
        val actions = UiTestActions { settings ->
            runBlocking(Dispatchers.IO) { store.save(settings) }
            writes++
            state.value = state.value.copy(settings = settings)
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("language-preference-row"))
    }

    @Test fun actualToolsRowOpensAnAnchoredCheckedMenuAndSelectionClosesAndPersists() {
        launchTools()
        compose.onNodeWithTag("language-preference-row").performClick()
        compose.onNodeWithTag("language-menu").assertIsDisplayed()
        compose.onNodeWithTag("locale-option-system").assertIsSelected()
        for (tag in listOf("en", "es", "fr", "ja", "ko", "ru", "zh_CN")) {
            compose.onNodeWithTag("locale-option-$tag").performScrollTo().performClick()
            assertEquals(tag, read().locale)
            compose.onNodeWithTag("language-menu").assertDoesNotExist()
            compose.onNodeWithTag("language-preference-row").assertIsDisplayed().performClick()
            compose.onNodeWithTag("locale-option-$tag").performScrollTo().assertIsSelected()
        }
        compose.onNodeWithTag("locale-option-system").performScrollTo().performClick()
        assertNull(read().locale)
        assertEquals(8, writes)
        compose.onNodeWithTag("language-menu").assertDoesNotExist()
    }

    @Test fun currentValueButtonAlsoOpensAndOutsideOrBackOnlyCancel() {
        launchTools()
        compose.onNodeWithTag("language-preference-value").performClick()
        compose.onNodeWithTag("language-menu").assertIsDisplayed()
        compose.onNodeWithTag("language-menu-dismiss").performTouchInput { click(Offset(2f, 2f)) }
        compose.onNodeWithTag("language-menu").assertDoesNotExist()
        assertEquals(0, writes)
        compose.onNodeWithTag("language-preference-value").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("language-menu").assertDoesNotExist()
        compose.onNodeWithTag("language-preference-row").assertIsDisplayed()
        assertEquals(0, writes)
        assertNull(read().locale)
    }
}
