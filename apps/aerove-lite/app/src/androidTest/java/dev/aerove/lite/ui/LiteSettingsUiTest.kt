package dev.aerove.lite.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.ColorMode
import dev.aerove.lite.InstalledApp
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiteSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)
    private fun click(id: Int) { compose.onNode(hasText(text(id)) and hasClickAction()).performClick() }

    private fun openSettingsDetail(id: Int) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(id)) and hasClickAction())
        click(id)
    }

    @Test
    fun connectionDemandExistsOnlyOnTheResumedConnectionsPage() {
        val state = mutableStateOf(LiteState())
        val actions = UiTestActions {}
        compose.replaceAppContent { LiteApp(state.value, actions) }
        compose.runOnUiThread { assertTrue(actions.connectionTelemetry.isEmpty()) }
        openSettingsDetail(R.string.lite_tab_connections)
        compose.runOnUiThread { assertEquals(listOf(true), actions.connectionTelemetry) }
        compose.runOnUiThread { state.value = state.value.copy(message = "Fixture overlay") }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals(listOf(true, false), actions.connectionTelemetry) }
        compose.runOnUiThread { state.value = state.value.copy(message = null) }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals(listOf(true, false, true), actions.connectionTelemetry) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.runOnUiThread { assertEquals(listOf(true, false, true, false), actions.connectionTelemetry) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnUiThread { assertEquals(listOf(true, false, true, false, true), actions.connectionTelemetry) }
        compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
        compose.runOnUiThread {
            assertEquals(listOf(true, false, true, false, true, false), actions.connectionTelemetry)
            assertTrue("Lifecycle fixture observations are not user/network actions", actions.events.isEmpty())
        }
    }

    @Test
    fun logsAreOptInPersistedAndTheirEntryTracksTheSetting() {
        val state = mutableStateOf(LiteState())
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNodeWithText(text(R.string.lite_logs)).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_general)) and hasClickAction())
        click(R.string.lite_general)
        compose.onNodeWithContentDescription(text(R.string.lite_open_logs)).performClick()
        compose.runOnUiThread { assertEquals(listOf(LiteSettings(openLogs = true)), actions.settings) }
        compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_logs)) and hasClickAction())
        click(R.string.lite_logs)
        compose.onNodeWithText(text(R.string.lite_no_logs)).assertExists()
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(openLogs = false)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.lite_back)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.lite_logs)).assertDoesNotExist()
        compose.runOnUiThread { assertEquals(listOf("changeSettings"), actions.events) }
    }

    @Test
    fun outboundRefreshUsesAuthoritativeLoadingFailureAndIdentity() {
        val identity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential")
        val state = mutableStateOf(LiteState(phase = VpnPhase.Running, outboundIdentity = identity))
        val actions = UiTestActions {}
        compose.replaceAppContent { IpQualityFixture(state.value) { LiteApp(state.value, actions) } }
        val refresh = compose.onNodeWithContentDescription(text(R.string.lite_outbound_refresh))
        refresh.performClick()
        compose.runOnUiThread {
            assertEquals(listOf("refreshOutbound"), actions.events)
            assertEquals(identity, state.value.outboundIdentity)
            state.value = state.value.copy(outboundLoading = true)
        }
        refresh.assertIsNotEnabled()
        compose.onNodeWithText(identity.address).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)).assertExists()
        val failure = "Fixture: outbound probe timed out"
        compose.runOnUiThread { state.value = state.value.copy(outboundLoading = false, outboundError = failure) }
        refresh.assertIsEnabled()
        compose.onNodeWithText(failure).assertExists()
        compose.onNodeWithText(identity.address).assertExists()
        compose.runOnUiThread { state.value = state.value.copy(outboundIdentity = null, outboundLoading = true, outboundError = null) }
        compose.onNodeWithText(text(R.string.lite_outbound_loading)).assertExists()
        compose.onNodeWithText(identity.address).assertDoesNotExist()
        refresh.assertIsNotEnabled()
        compose.runOnUiThread { assertEquals(listOf("refreshOutbound"), actions.events) }
    }

    @Test
    fun customDnsRequiresExplicitValidatedSaveAndCanReturnToProfile() {
        val initial = LiteSettings(colorMode = ColorMode.Light)
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_dns)) and hasClickAction())
        compose.onNodeWithText(text(R.string.lite_dns_follow_profile)).assertExists()
        compose.onNodeWithText(initial.dnsServers.single()).assertDoesNotExist()
        click(R.string.lite_dns)
        compose.onNodeWithText(text(R.string.lite_dns_follow_profile)).assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.lite_dns_custom)).performClick()
        compose.runOnIdle { assertTrue("Enabling editing must not silently apply the saved/default address", actions.settings.isEmpty()) }
        compose.onNode(hasSetTextAction()).performTextReplacement("")
        click(R.string.lite_save)
        compose.onNodeWithText(text(R.string.lite_dns_empty)).assertExists()
        compose.runOnIdle { assertTrue(actions.settings.isEmpty()) }
        val server = "https://9.9.9.9/dns-query"
        compose.onNode(hasSetTextAction()).performTextReplacement(server)
        click(R.string.lite_save)
        compose.runOnIdle {
            assertEquals(listOf(initial.copy(overrideDns = true, dnsServers = listOf(server))), actions.settings)
            assertTrue(state.value.settings.overrideDns)
        }
        compose.onNodeWithContentDescription(text(R.string.lite_dns_custom)).performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.lite_dns_follow_profile)).assertExists()
        compose.runOnIdle {
            assertEquals(2, actions.settings.size)
            assertFalse(actions.settings.last().overrideDns)
            assertEquals(listOf(server), actions.settings.last().dnsServers)
        }
    }

    @Test
    fun firstIncludeSelectionIsLocalUntilOneAtomicApply() {
        val initial = LiteSettings(colorMode = ColorMode.Light)
        val app = InstalledApp("dev.aerove.fixture.mail", "Fixture Mail")
        val state = mutableStateOf(LiteState(settings = initial, installedApps = listOf(app)))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        openSettingsDetail(R.string.lite_app_filter)
        compose.onNodeWithText(app.label).assertDoesNotExist()
        click(R.string.lite_apps_include)
        compose.onNode(hasText(text(R.string.lite_apply)) and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { assertTrue(actions.settings.isEmpty()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(app.label))
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onNode(checkbox and hasText(app.label)).performClick()
        compose.runOnIdle { assertTrue("Picking applications must remain a local draft", actions.settings.isEmpty()) }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_apply)) and hasClickAction())
        click(R.string.lite_apply)
        compose.runOnIdle {
            assertEquals(listOf(initial.copy(appFilterMode = AppFilterMode.Include, packages = setOf(app.packageName))), actions.settings)
            assertTrue(actions.events.none { it == "connect" || it == "disconnect" })
        }
    }
}
