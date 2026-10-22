package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.presentation.SystemFontCatalog
import dev.aerove.lite.editor.EditorDocumentController
import dev.aerove.lite.editor.EditorPalette
import dev.aerove.lite.editor.EditorPreferences
import dev.aerove.lite.editor.EditorFontSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class DisplayPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "display-preferences-${UUID.randomUUID()}")
    private val store = LiteSettingsStore(directory)
    private val state = mutableStateOf(LiteState())
    @After fun clean() { directory.deleteRecursively() }
    private fun text(id: Int) = context.getString(id)
    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    private fun launch() {
        val actions = UiTestActions { settings ->
            runBlocking(Dispatchers.IO) { store.save(settings) }
            state.value = state.value.copy(settings = settings)
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_theme)) and hasClickAction())
        compose.onNode(hasText(text(R.string.lite_theme)) and hasClickAction()).performClick()
    }
    private fun scrollTo(label: Int) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(label)))
    @Test fun productionThemeRoutePersistsScaleAndPureBlack() {
        launch()
        scrollTo(R.string.presentation_pure_black)
        compose.onNodeWithContentDescription(text(R.string.presentation_pure_black)).performClick()
        assertTrue(read().pureBlack)
        scrollTo(R.string.presentation_text_scale)
        compose.onNodeWithContentDescription(text(R.string.presentation_text_scale)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("presentation-text-scale"))
        compose.onNodeWithTag("presentation-text-scale").performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
        assertTrue(read().textScaleEnabled)
        assertEquals(1.25f, read().textScale, 0.0001f)
    }
    @Test fun productionFontRouteReadsSystemCatalogAndPersistsSelection() {
        val installed = runBlocking { SystemFontCatalog.load() }
        assertTrue("Device system catalog must contain named font families", installed.isNotEmpty())
        val chosen = installed.first()
        launch()
        scrollTo(R.string.presentation_font_family)
        compose.onNode(hasText(text(R.string.presentation_font_family)) and hasClickAction()).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("font-family-$chosen").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(chosen) and hasClickAction()).performClick()
        assertEquals(chosen, read().fontFamily)
        compose.onNode(hasText(text(R.string.presentation_font_default)) and hasClickAction()).performClick()
        assertNull(read().fontFamily)
    }
    @Test fun nativeEditorBodyKeepsLogicalSizeAcrossSystemFontScales() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            instrumentation.runOnMainSync {
                val measured = listOf(1f, 1.5f).map { scale ->
                    val configuration = android.content.res.Configuration(context.resources.configuration).apply { fontScale = scale }
                    val scaledContext = android.view.ContextThemeWrapper(context.createConfigurationContext(configuration), android.R.style.Theme_Material_Light_NoActionBar)
                    val controller = EditorDocumentController(scaledContext, scope, false, {}, {}, {})
                    try {
                        val view = controller.createView()
                        val palette = EditorPalette(false, -1, android.graphics.Color.BLACK, android.graphics.Color.GRAY, android.graphics.Color.BLUE)
                        controller.style(palette, EditorPreferences())
                        assertEquals(16f * scaledContext.resources.displayMetrics.density, view.textSizePx, 0.01f)
                        controller.style(palette, EditorPreferences(fontSize = EditorFontSize.Large))
                        assertEquals(18f * scaledContext.resources.displayMetrics.density, view.textSizePx, 0.01f)
                        view.textSizePx
                    } finally { controller.release() }
                }
                assertEquals(measured[0], measured[1], 0.01f)
            }
        } finally { scope.cancel() }
    }

}
