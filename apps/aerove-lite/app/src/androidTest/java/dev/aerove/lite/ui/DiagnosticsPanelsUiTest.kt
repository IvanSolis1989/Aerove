package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.DiagnosticsState
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.ServiceCheckView
import dev.aerove.lite.VpnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DiagnosticsPanelsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)))
    private val showMemory = mutableStateOf(true)
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }
    private lateinit var owner: FixtureLifecycle
    private val foreground = mutableStateOf(true)

    private class FixtureLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private fun launch(service: Boolean = false, scrollMemory: Boolean = false) {
        compose.runOnUiThread { owner = FixtureLifecycle().apply { registry.currentState = Lifecycle.State.RESUMED } }
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true, LocalLifecycleOwner provides owner) {
                LiteTheme(state.value.settings) {
                    val overlay = remember { LiteOverlayState() }
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().background(LocalLiteVisuals.current.page).layerBackdrop(backdrop))
                        CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop,
                            LocalLiteOverlay provides overlay, LocalDiagnosticsForeground provides foreground.value) {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                if (scrollMemory) Spacer(Modifier.fillMaxWidth().height(1800.dp))
                                if (service) ServiceStatusPanel(state.value, actions)
                                else if (showMemory.value) MemoryInfoPanel(state.value, actions)
                                Spacer(Modifier.height(1200.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).testTag("diagnostics-scroll-end"))
                            }
                            overlay.content?.invoke()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun serviceDetailsRefreshOneTargetAndManageTheSavedSelectionAndOrder() {
        state.value = state.value.copy(phase = VpnPhase.Running, settings = state.value.settings.copy(
            serviceOrder = listOf("google", "github"), disabledServices =
                dev.aerove.lite.data.ServiceTarget.entries.map { it.id }.toSet() - setOf("google", "github")),
            diagnostics = DiagnosticsState(services = listOf(
                ServiceCheckView("google", "pending", checkedAt = 0), ServiceCheckView("github", "available", checkedAt = 1))))
        launch(service = true)
        compose.onNodeWithTag("diagnostics-services").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.diag_service_check, "Google")).performClick()
        assertTrue(actions.events.contains("refreshServiceCheck:google"))
        compose.onNodeWithContentDescription(context.getString(R.string.diag_service_manage)).performClick()
        compose.onNodeWithContentDescription("Google").performClick()
        assertTrue("Management persists the disabled target", "google" in state.value.settings.disabledServices)
        compose.onNodeWithContentDescription("GitHub").assertExists()
        val reorder = compose.onNodeWithTag("service-manage-github").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { assertTrue(reorder.first { it.label == context.getString(R.string.diag_service_move_up) }.action()) }
        assertEquals("github", state.value.settings.serviceOrder.first())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(context.getString(R.string.diag_service_check_all)).assertIsDisplayed()
    }

    @Test
    fun memorySuspendsForCoveringRoutesAndResumesWithoutASyntheticSample() {
        launch()
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == true }
        compose.runOnUiThread { foreground.value = false }
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == false }
        compose.runOnUiThread { foreground.value = true }
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == true }
        compose.onNodeWithTag("diagnostics-memory-value", useUnmergedTree = true).assertTextEquals("—")
    }

    @Test
    fun absentSamplesDoNotInventMemoryOrAServiceCatalog() {
        launch()
        compose.onNodeWithText(context.getString(R.string.diag_memory_no_sample)).assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-memory-value", useUnmergedTree = true).assertTextEquals("—")
        compose.onNodeWithText("0 B").assertDoesNotExist()
        compose.runOnUiThread { showMemory.value = false }
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == false }
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun serviceEmptyStateChecksOnlyOnAnExplicitAction() {
        state.value = state.value.copy(phase = VpnPhase.Running)
        launch(service = true)
        fun assertNaturalEmptyHeight() {
            val bounds = compose.onNodeWithTag("diagnostics-services-empty", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Empty content must not reserve a 108dp service grid", bounds.height < 48f * context.resources.displayMetrics.density)
        }
        assertNaturalEmptyHeight()
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Stopped) }
        compose.onNodeWithText(context.getString(R.string.diag_service_connect)).assertIsDisplayed()
        assertNaturalEmptyHeight()
        assertTrue(actions.events.isEmpty())
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Running) }
        compose.onNodeWithText(context.getString(R.string.diag_service_pending)).assertIsDisplayed()
        compose.onNodeWithText("Google").assertDoesNotExist()
        assertTrue(actions.events.isEmpty())
        compose.onNodeWithTag("diagnostics-service-refresh").performClick()
        assertEquals(listOf("refreshServiceChecks"), actions.events)
        compose.runOnUiThread {
            state.value = state.value.copy(diagnostics = DiagnosticsState(serviceLoading = true, serviceError = "fixture: service unavailable"))
        }
        compose.onNodeWithText("fixture: service unavailable").assertIsDisplayed()
        compose.onNode(hasContentDescription(context.getString(R.string.diag_loading)) and
            hasAnyAncestor(hasTestTag("diagnostics-services-empty")), useUnmergedTree = true).assertIsDisplayed()
        assertNaturalEmptyHeight()
        compose.onNodeWithTag("diagnostics-service-refresh").assertDoesNotExist()
    }

    @Test
    fun serviceCardUsesFourActualResultsAndItsSheetKeepsTheRemainingResult() {
        state.value = state.value.copy(phase = VpnPhase.Running, diagnostics = DiagnosticsState(services = listOf(
            ServiceCheckView("google", "available", "SG", 73, listOf("route fixture"), checkedAt = 1000),
            ServiceCheckView("github", "restricted", checkedAt = 1000),
            ServiceCheckView("youtube", "timeout", checkedAt = 1000),
            ServiceCheckView("chatgpt", "originals-only", checkedAt = 1000),
            ServiceCheckView("claude", "available", checkedAt = 1000),
        )))
        launch(service = true)
        compose.onNodeWithText("Google", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Claude").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.diag_service_issues, 3), substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.diag_service_delay, 73), substring = true).assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-services").performClick()
        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("SG · route fixture").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.diag_service_check_all)).performClick()
        assertEquals(listOf("refreshServiceChecks"), actions.events)
    }

    @Test
    fun memoryReadsScopeAndLimitWithoutClaimingAHeapOrHidingAnError() {
        state.value = state.value.copy(diagnostics = DiagnosticsState(memoryInUseBytes = 1048576,
            memoryLimitBytes = 8388608, memoryScope = "process-rss fixture", memoryError = "fixture: stale sample"))
        launch()
        compose.onNodeWithText("process-rss fixture").assertIsDisplayed()
        compose.onNodeWithText("fixture: stale sample").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-memory").performClick()
        compose.onNodeWithText(context.getString(R.string.diag_memory_limit)).assertIsDisplayed()
        compose.onNodeWithText(formatBytes(8388608)).assertIsDisplayed()
        compose.onNodeWithText("Go heap").assertDoesNotExist()
        compose.runOnUiThread { state.value = state.value.copy(diagnostics = state.value.diagnostics.copy(memoryLimitBytes = 16777216)) }
        compose.onNodeWithText(formatBytes(16777216)).assertIsDisplayed()
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun memoryDemandFollowsResumedAndDisposalWithoutPollingOrBusinessActions() {
        launch()
        compose.waitUntil { actions.memoryTelemetry == listOf(true) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil { actions.memoryTelemetry == listOf(true, false) }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil { actions.memoryTelemetry == listOf(true, false, true) }
        compose.runOnUiThread { showMemory.value = false }
        compose.waitUntil { actions.memoryTelemetry == listOf(true, false, true, false) }
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun offscreenMemoryDoesNotDemandTelemetryUntilItIsVisible() {
        launch(scrollMemory = true)
        assertTrue(actions.memoryTelemetry.isEmpty())
        compose.onNodeWithTag("diagnostics-memory").performScrollTo()
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == true }
        compose.onNodeWithTag("diagnostics-scroll-end").performScrollTo()
        compose.waitUntil { actions.memoryTelemetry.lastOrNull() == false }
        assertTrue(actions.events.isEmpty())
    }
}
