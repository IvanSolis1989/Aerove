package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DashboardEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var inputModes: InputModeManager? = null

    @Test fun rootEntryHasOneStableRowPerFusionAndBackDoesNotRewriteRawSettings() {
        val initial = LiteSettings(aurora = false, material = MaterialTier.Solid, dashboardWidgets = listOf(
            DashboardWidget.CurrentOutbound, DashboardWidget.IntranetIp, DashboardWidget.VpnButton,
            DashboardWidget.ConnectionStats, DashboardWidget.ServiceStatus))
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent {
            val modeManager = LocalInputModeManager.current
            SideEffect { inputModes = modeManager }
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.onNodeWithTag("dashboard-row-currentOutbound").assertIsDisplayed()
        compose.onAllNodesWithTag("dashboard-row-outboundMode").assertCountEquals(1)
        compose.onNodeWithTag("dashboard-row-vpnButton").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-row-intranetIp").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-add-intranetIp").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-row-serviceStatus"))
        compose.onAllNodesWithTag("dashboard-row-serviceStatus").assertCountEquals(1)
        compose.onNodeWithTag("dashboard-row-connectionStats").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-row-serviceStatus").performTouchInput { click(Offset(width / 2f, height / 2f)) }
        assertTrue(actions.events.isEmpty())
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(initial, state.value.settings)
        assertTrue(actions.settings.isEmpty())
        assertTrue(actions.connectionTelemetry.none { it })
    }

    @Test fun partialOverviewHideAndExplicitRestorePreviewTheCurrentFullFamilyWithoutReadDemand() {
        val settings = mutableStateOf(LiteSettings(aurora = false, material = MaterialTier.Solid,
            dashboardWidgets = listOf(DashboardWidget.IntranetIp, DashboardWidget.ConnectionStats)))
        val actions = UiTestActions { settings.value = it }
        val previews = ConcurrentHashMap<DashboardWidget, LiteSettings>()
        var routes = 0
        compose.replaceAppContent {
            val modeManager = LocalInputModeManager.current
            SideEffect { inputModes = modeManager }
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(settings.value) {
                    val source = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                        CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source) {
                            DashboardEditor(settings.value, actions::changeSettings, {}, draftCardContent = { widget, draft ->
                                SideEffect { previews[widget] = draft }
                                Box(Modifier.fillMaxWidth()) {
                                    DashboardCard(widget, LiteState(settings = draft), actions, { routes++ }, { routes++ }, { routes++ },
                                        serviceStatusContent = {}, memoryInfoContent = {}, openRequests = { routes++ }, openDns = { routes++ })
                                }
                            })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("dashboard-row-serviceStatus").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(DashboardWidget.IntranetIp, DashboardWidget.ConnectionStats), previews[DashboardWidget.ConnectionStats]?.dashboardWidgets)
            settings.value = settings.value.copy(auroraSeed = 123456)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_remove,
            context.getString(R.string.lite_dashboard_service_status))).performClick()
        assertEquals(listOf(DashboardWidget.IntranetIp), settings.value.dashboardWidgets)
        assertEquals(1, actions.settings.size)
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-add-serviceStatus"))
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_add,
            context.getString(R.string.lite_dashboard_service_status))).performClick()
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-row-serviceStatus"))
        compose.waitUntil { previews[DashboardWidget.ServiceStatus]?.dashboardWidgets == settings.value.dashboardWidgets }
        assertEquals(listOf(DashboardWidget.IntranetIp, DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats), settings.value.dashboardWidgets)
        assertEquals(123456L, settings.value.auroraSeed)
        assertEquals(2, actions.settings.size)
        compose.onNodeWithTag("dashboard-row-serviceStatus").performTouchInput { click() }
        assertEquals(0, routes)
        assertTrue(actions.events.isEmpty())
        assertTrue(actions.dashboardTelemetry.none { it })
        assertTrue(actions.serviceTelemetry.all { it.isEmpty() })
        assertTrue(actions.memoryTelemetry.none { it })
        compose.onNodeWithContentDescription(context.getString(R.string.lite_confirm)).performClick()
        assertEquals(2, actions.settings.size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun previewRejectsNestedFocusAndKeysButExternalEditorActionsKeepKeyboardAccess() {
        val settings = mutableStateOf(LiteSettings(aurora = false, material = MaterialTier.Solid,
            dashboardWidgets = listOf(DashboardWidget.OutboundMode)))
        val editing = mutableStateOf(false)
        val showOld = mutableStateOf(true)
        val oldFocus = FocusRequester()
        val nestedFocus = FocusRequester()
        var oldFocused = false
        var nestedFocused = false
        var activations = 0
        var closed = 0
        val actions = UiTestActions { settings.value = it }
        compose.replaceAppContent {
            val modeManager = LocalInputModeManager.current
            SideEffect { inputModes = modeManager }
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(settings.value) {
                    val source = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                        if (showOld.value) LiteButton("Old normal control", { activations++ },
                            Modifier.focusRequester(oldFocus).onFocusChanged { oldFocused = it.isFocused })
                        CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source) {
                            if (editing.value) DashboardEditor(settings.value, actions::changeSettings,
                                { closed++; editing.value = false }, draftCardContent = { widget, draft ->
                                    Column(Modifier.fillMaxWidth()) {
                                        DashboardCard(widget, LiteState(settings = draft), actions, { activations++ },
                                            { activations++ }, { activations++ }, serviceStatusContent = {},
                                            memoryInfoContent = {}, openRequests = { activations++ }, openDns = { activations++ })
                                        Column(Modifier.focusGroup()) {
                                            Box(Modifier.focusRequester(nestedFocus).onFocusChanged { nestedFocused = it.isFocused }
                                                .clickable { activations++ }) { LiteText("Nested keyboard activation probe") }
                                        }
                                    }
                                })
                        }
                    }
                }
            }
        }
        compose.runOnIdle { assertTrue(inputModes!!.requestInputMode(InputMode.Keyboard)); assertTrue(oldFocus.requestFocus()) }
        compose.runOnIdle { assertTrue(oldFocused); editing.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse("Editor entry releases the still-mounted old normal control", oldFocused)
            showOld.value = false
            assertFalse("Even a nested focus group cannot enter preview", nestedFocus.requestFocus())
        }
        compose.onRoot().performKeyInput { pressKey(Key.Spacebar); pressKey(Key.Enter) }
        compose.runOnIdle { assertFalse(nestedFocused); assertEquals(0, activations) }
        assertTrue(actions.settings.isEmpty())
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-add-memoryInfo"))
        val add = compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_add,
            context.getString(R.string.lite_dashboard_memory)))
        add.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        add.assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertTrue(DashboardWidget.MemoryInfo in settings.value.dashboardWidgets)
        assertEquals(1, actions.settings.size)
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-row-memoryInfo"))
        val row = compose.onNodeWithTag("dashboard-row-memoryInfo")
        val moveActions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread {
            assertTrue(moveActions.first { it.label == context.getString(R.string.lite_dashboard_move_up) }.action())
        }
        assertEquals(2, actions.settings.size)
        val remove = compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_remove,
            context.getString(R.string.lite_dashboard_memory)))
        remove.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertFalse(DashboardWidget.MemoryInfo in settings.value.dashboardWidgets)
        assertEquals(3, actions.settings.size)
        compose.onNodeWithTag("dashboard-editor").performScrollToNode(hasTestTag("dashboard-add-memoryInfo"))
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_add,
            context.getString(R.string.lite_dashboard_memory)))
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(4, actions.settings.size)
        val back = compose.onNodeWithContentDescription(context.getString(R.string.lite_back))
        back.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, closed)
        assertEquals(4, actions.settings.size)
        compose.runOnIdle { editing.value = true }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_confirm))
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertEquals(2, closed)
        assertEquals(4, actions.settings.size)
        assertEquals(0, activations)
        assertTrue(actions.events.isEmpty())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun actualControlsAcceptNormalKeysButEditorTabAndActivationCannotChangeTheirSettings() {
        val root = mutableStateOf(LiteState(settings = LiteSettings(aurora = false, material = MaterialTier.Solid,
            dashboardWidgets = listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp))))
        val actions = UiTestActions { root.value = root.value.copy(settings = it) }
        compose.replaceAppContent {
            val modeManager = LocalInputModeManager.current
            SideEffect { inputModes = modeManager }
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(root.value, actions) }
        }
        compose.runOnIdle { assertTrue(inputModes!!.requestInputMode(InputMode.Keyboard)) }
        val initial = root.value.settings
        val vpn = compose.onNodeWithTag("dashboard-vpn", useUnmergedTree = true)
        vpn.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        vpn.assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.Spacebar) }
        assertEquals(!initial.network.vpnEnabled, root.value.settings.network.vpnEnabled)
        val mode = compose.onNodeWithTag("dashboard-mode-cycle", useUnmergedTree = true)
        mode.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        assertNotEquals(initial.mode, root.value.settings.mode)
        vpn.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        val beforeEdit = root.value.settings
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.onNodeWithTag("dashboard-editor").assertIsDisplayed()
        repeat(12) {
            if (compose.onAllNodesWithTag("dashboard-editor").fetchSemanticsNodes().isNotEmpty()) {
                compose.onRoot().performKeyInput { pressKey(Key.Tab); pressKey(Key.Spacebar) }
                if (compose.onAllNodesWithTag("dashboard-editor").fetchSemanticsNodes().isNotEmpty())
                    compose.onRoot().performKeyInput { pressKey(Key.Enter) }
                assertEquals(beforeEdit.mode, root.value.settings.mode)
                assertEquals(beforeEdit.network, root.value.settings.network)
            }
        }
        assertTrue(actions.events.none { it == "connect" || it == "disconnect" || it == "refreshServiceChecks" })
    }
}
