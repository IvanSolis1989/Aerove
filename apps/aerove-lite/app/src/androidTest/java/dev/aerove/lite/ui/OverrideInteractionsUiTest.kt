package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.overrides.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real Compose gestures + private-store readback; execution requires the Android CI/device lane. */
class OverrideInteractionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "override-ui-${UUID.randomUUID()}")
    private val repository = OverrideRepository(directory)
    private val active = mutableStateOf(true)
    private val rules = mutableStateOf(emptyList<OverrideRule>())
    private val scripts = mutableStateOf(ScriptLibraryState())
    private var writes = 0
    private var editorOpens = 0

    @After fun clean() { directory.deleteRecursively() }
    private fun snapshot() = runBlocking(Dispatchers.IO) { repository.snapshot() }
    private fun saveRules(next: List<OverrideRule>) {
        runBlocking(Dispatchers.IO) { repository.setGlobalRules(next) }
        rules.value = snapshot().globalRules; writes++
    }
    private fun launch(scriptLibrary: Boolean = false) {
        compose.replaceAppContent {
            LiteTheme(LiteSettings(material = MaterialTier.Solid, aurora = false)) {
                val source = rememberLayerBackdrop()
                val overlay = remember { LiteOverlayState() }
                val list = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(source))
                    CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source,
                        LocalLiteListState provides list, LocalLiteOverlay provides overlay, LocalLitePageActive provides active.value,
                        LocalLiteMotionOverride provides true) {
                        if (!scriptLibrary) OverrideGlobalRulesScreen(rules.value, ::saveRules, PaddingValues(16.dp))
                        else ScriptLibraryScreen(scripts.value, { script ->
                            runBlocking(Dispatchers.IO) { repository.putScript(script) }; scripts.value = scripts.value.copy(scripts = snapshot().scripts)
                        }, { editorOpens++ }, { id ->
                            runBlocking(Dispatchers.IO) { repository.deleteScript(id) }; scripts.value = scripts.value.copy(scripts = snapshot().scripts)
                        }, { id, destination ->
                            runBlocking(Dispatchers.IO) { repository.moveScript(id, destination) }
                            scripts.value = scripts.value.copy(scripts = snapshot().scripts); writes++
                        }, {}, {}, { _, _ -> }, PaddingValues(16.dp))
                        overlay.content?.invoke()
                    }
                }
            }
        }
    }
    private fun seedRules() {
        val seeded = listOf(OverrideRule("a", "DOMAIN,a.example,DIRECT"), OverrideRule("b", "DOMAIN,b.example,DIRECT"), OverrideRule("c", "DOMAIN,c.example,REJECT"))
        runBlocking(Dispatchers.IO) { repository.setGlobalRules(seeded) }
        rules.value = snapshot().globalRules
    }

    @Test fun structuredFormValidatesPayloadAndCancelDoesNotSave() {
        launch()
        compose.onNodeWithTag("override-add-rule").performClick()
        compose.onNodeWithTag("override-rule-action").performClick()
        compose.onNodeWithTag("override-option-NETWORK").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.override_content)).performTextReplacement("icmp")
        compose.onNodeWithTag("override-rule-save").performScrollTo().performClick()
        compose.onNodeWithTag("override-rule-error").assertIsDisplayed()
        assertEquals(0, writes)
        compose.onNodeWithContentDescription(context.getString(R.string.override_content)).performTextReplacement("udp")
        compose.onNodeWithTag("override-rule-save").performScrollTo().performClick()
        assertEquals("NETWORK,udp,DIRECT", snapshot().globalRules.single().value)
        compose.onNodeWithTag("override-rule-row-0").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.override_content)).performTextReplacement("tcp")
        compose.onNodeWithTag("override-rule-cancel").performScrollTo().performClick()
        assertEquals("NETWORK,udp,DIRECT", snapshot().globalRules.single().value)
        assertEquals(1, writes)
    }

    @Test fun batchSelectAllNeedsConfirmationAndBackClearsSelection() {
        seedRules(); launch()
        compose.onNodeWithTag("override-rule-select-0").performClick()
        compose.onNodeWithTag("override-rule-select-1").performClick()
        compose.onNodeWithTag("override-delete-selected").performClick()
        compose.onNodeWithText(context.getString(R.string.override_cancel)).performClick()
        assertEquals(3, snapshot().globalRules.size)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("override-delete-selected").assertDoesNotExist()
        compose.onNodeWithTag("override-rule-select-0").performClick()
        compose.onNodeWithTag("override-select-all").performClick()
        compose.onNodeWithTag("override-delete-selected").performClick()
        compose.onNodeWithTag("override-confirm-delete").performClick()
        assertTrue(snapshot().globalRules.isEmpty())
        assertEquals(1, writes)
    }

    @Test fun parameterControlsAndNestedPickerBackPreserveTheExistingDraft() {
        runBlocking(Dispatchers.IO) { repository.setGlobalRules(listOf(OverrideRule("ip", "IP-CIDR,10.0.0.0/8,MATCH,src,no-resolve"))) }
        rules.value = snapshot().globalRules
        launch()
        compose.onNodeWithTag("override-rule-row-0").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.override_no_resolve)).assertIsOn()
        compose.onNodeWithContentDescription(context.getString(R.string.override_match_source)).assertIsOn()
        compose.onNodeWithContentDescription(context.getString(R.string.override_content)).performTextReplacement("192.168.0.0/16")
        compose.onNodeWithTag("override-rule-action").performClick()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("override-rule-save").performScrollTo().performClick()
        assertEquals("IP-CIDR,192.168.0.0/16,MATCH,src,no-resolve", snapshot().globalRules.single().value)
        assertEquals(1, writes)
    }

    @Test fun modalOverlayBlocksBackgroundRuleDrag() {
        seedRules(); launch()
        val before = snapshot().globalRules
        val first = compose.onNodeWithTag("override-rule-row-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("override-rule-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("override-add-rule").performClick()
        compose.onNodeWithTag("override-rule-row-2").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, first.center.y - last.center.y)); up()
        }
        assertEquals(before, snapshot().globalRules)
        assertEquals(0, writes)
    }

    @Test fun searchSelectionDeletesOnlyVisibleMatchingRows() {
        seedRules(); launch()
        compose.onNodeWithContentDescription(context.getString(R.string.override_search)).performTextReplacement("b.example")
        compose.onNodeWithTag("override-rule-select-0").performClick()
        compose.onNodeWithTag("override-select-all").performClick() // all visible is already selected -> clear
        compose.onNodeWithTag("override-rule-select-0").performClick()
        compose.onNodeWithTag("override-delete-selected").performClick()
        compose.onNodeWithTag("override-confirm-delete").performClick()
        assertEquals(listOf("a", "c"), snapshot().globalRules.map { it.id })
    }

    @Test fun ruleLongPressDragCommitsExactlyOneStoreWrite() {
        seedRules(); launch()
        val first = compose.onNodeWithTag("override-rule-row-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("override-rule-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("override-rule-row-2").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, first.center.y - last.center.y)); up()
        }
        compose.waitForIdle()
        assertEquals(listOf("c", "a", "b"), snapshot().globalRules.map { it.id })
        assertEquals(1, writes)
    }

    @Test fun cancelledOrInactiveDragNeverPersistsOrder() {
        seedRules(); launch()
        val first = compose.onNodeWithTag("override-rule-row-0").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("override-rule-row-2").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("override-rule-row-2").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, first.center.y - last.center.y)); cancel()
        }
        compose.runOnIdle { active.value = false }
        compose.onNodeWithTag("override-rule-row-2").performTouchInput {
            down(center); advanceEventTime(650); moveBy(Offset(0f, first.center.y - last.center.y)); up()
        }
        assertEquals(listOf("a", "b", "c"), snapshot().globalRules.map { it.id })
        assertEquals(0, writes)
    }

    @Test fun scriptSortHandleDragsWithoutOpeningEditorOrContextMenu() {
        runBlocking(Dispatchers.IO) {
            listOf("a", "b", "c").forEach { repository.putScript(ScriptResource(it, it, content = "function main(c){return c}")) }
        }
        scripts.value = ScriptLibraryState(scripts = snapshot().scripts)
        launch(scriptLibrary = true)
        compose.onNodeWithTag("override-script-sort").performClick()
        val first = compose.onNodeWithTag("override-script-handle-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("override-script-handle-2", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("override-script-handle-2", useUnmergedTree = true).performTouchInput {
            down(center); moveBy(Offset(0f, first.center.y - last.center.y)); up()
        }
        assertEquals(listOf("c", "a", "b"), snapshot().scripts.map { it.id })
        assertEquals(1, writes)
        assertEquals(0, editorOpens)
    }
}
