package dev.aerove.lite.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class StatusAlignmentUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false,
        dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo, DashboardWidget.OutboundMode)),
        phase = VpnPhase.Running, outboundIdentity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential"),
        traffic = Traffic(1024, 2048, 4096, 8192)))
    private val editing = mutableStateOf(false)
    private val mounted = mutableIntStateOf(0)
    private val traffic = mutableStateOf(true)
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }
    private var pixels = 1f

    private fun mount(reuse: Boolean, scale: Float = 1f) {
        compose.replaceAppContent {
            IpQualityFixture(state.value) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale), LocalLiteMotionOverride provides true) {
                    LiteTheme(state.value.settings) {
                        pixels = LocalDensity.current.density
                        val source = rememberLayerBackdrop()
                        val scroll = rememberLazyListState()
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                                LiteWallpaper(remember { mutableLongStateOf(0L) }, Modifier.fillMaxSize())
                            }
                            CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source,
                                LocalLiteListState provides scroll) {
                                Box(Modifier.widthIn(max = 328.dp).fillMaxSize()) {
                                    key(mounted.intValue) {
                                        val card: @Composable (Int) -> Unit = { index ->
                                            Box(Modifier.fillMaxWidth().testTag("alignment-subject-$index")) {
                                                OutboundStatusCard(state.value, actions, showTraffic = traffic.value)
                                            }
                                        }
                                        if (editing.value) DashboardEditor(state.value.settings, actions::changeSettings,
                                            { editing.value = false }, cardContent = { widget ->
                                                if (widget == DashboardWidget.CurrentOutbound) card(0)
                                                else Box(Modifier.fillMaxWidth().height(48.dp)) { LiteText(widget.wireName) }
                                            })
                                        else if (reuse) LazyColumn(Modifier.testTag("alignment-list")) {
                                            items((0..7).toList(), key = { it }, contentType = { "status" }) { card(it) }
                                        }
                                        else OverviewScreen(state.value.settings, PaddingValues()) { widget ->
                                            if (widget == DashboardWidget.CurrentOutbound) card(0)
                                            else Box(Modifier.fillMaxWidth().height(48.dp)) { LiteText(widget.wireName) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun node(tag: String, index: Int = 0) = compose.onNode(hasTestTag(tag) and
        hasAnyAncestor(hasTestTag("alignment-subject-$index")), useUnmergedTree = true)
    private fun rect(tag: String, index: Int = 0) = node(tag, index).fetchSemanticsNode().boundsInRoot
    private fun checkReadings(index: Int = 0) {
        val card = rect("dashboard-status", index)
        val content = rect("dashboard-status-content", index)
        val body = rect("dashboard-status-body", index)
        val readings = rect("dashboard-status-traffic", index)
        val upload = rect("dashboard-upload-speed", index)
        val download = rect("dashboard-download-speed", index)
        assertTrue(upload.bottom <= download.top)
        assertTrue(readings.left >= card.left - 1f && readings.right <= card.right + 1f)
        assertTrue(readings.bottom <= card.bottom + 1f)
        assertEquals(readings.right, upload.right, 0.5f)
        assertEquals(readings.right, download.right, 0.5f)
        if (readings.top >= body.bottom) {
            assertEquals(8f * pixels, readings.top - body.bottom, 1f)
            assertEquals(body.height + 8f * pixels + readings.height, content.height, 1f)
        } else {
            assertEquals(body.bottom, content.bottom, 1f)
        }
        for (tag in listOf("dashboard-upload-value", "dashboard-download-value")) {
            val results = mutableListOf<TextLayoutResult>()
            node("$tag-raw", index).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            val result = results.single()
            assertFalse("$tag retains the complete rate and unit", result.didOverflowWidth || result.didOverflowHeight)
            assertFalse(result.isLineEllipsized(0))
            assertEquals(10f, result.layoutInput.style.fontSize.value, 0f)
            assertTrue(result.layoutInput.text.text.contains(" "))
        }
    }

    @Test fun productionStatusAlignmentSurvivesEditorRemovalAndRemount() {
        mount(reuse = false)
        checkReadings()
        compose.runOnUiThread { editing.value = true }
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-row-outboundMode"))
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_remove,
            context.getString(R.string.lite_proxy_mode))).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_confirm)).performClick()
        assertEquals(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo), state.value.settings.dashboardWidgets)
        checkReadings()
        compose.runOnUiThread { mounted.intValue++ }
        checkReadings()
    }

    @Test fun productionAlignmentQueriesSurviveLazyReuseWithNaturalFooterPlacement() {
        mount(reuse = true, scale = 1.15f)
        for (material in MaterialTier.entries) {
            compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(material = material)) }
            checkReadings()
            compose.onNodeWithTag("alignment-list").performScrollToIndex(5)
            checkReadings(5)
            compose.onNodeWithTag("alignment-list").performScrollToIndex(0)
            checkReadings()
            compose.runOnUiThread { traffic.value = false }
            node("dashboard-status-traffic").assertDoesNotExist()
            compose.runOnUiThread { traffic.value = true }
            checkReadings()
        }
    }
}
