package dev.aerove.lite.ui

import android.util.Log
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performScrollTo
import dev.aerove.lite.dnssettings.*
import org.json.JSONObject
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import dev.aerove.lite.LiteController
import dev.aerove.lite.LiteFeatureHost
import dev.aerove.lite.ColorMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.appaccess.AppAccessFeature
import dev.aerove.lite.appaccess.AppAccessMode
import dev.aerove.lite.appaccess.AppAccessPreferences
import dev.aerove.lite.appaccess.ApplicationLoader
import dev.aerove.lite.appaccess.InstalledApplication
import dev.aerove.lite.appaccess.InstalledApplicationSnapshot
import dev.aerove.lite.integration.withAppAccess
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FixtureApplicationLoader(private val app: InstalledApplication) : ApplicationLoader {
    override suspend fun load() = InstalledApplicationSnapshot(listOf(app), permissionGranted = true)
    override suspend fun chinaPackages(applications: List<InstalledApplication>) = emptySet<String>()
}

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
        var composeRootFocused = false
        compose.replaceAppContent {
            val currentRootFocused = LocalWindowInfo.current.isWindowFocused
            SideEffect { composeRootFocused = currentRootFocused }
            LiteApp(state.value, actions)
        }
        fun assertDemand(stage: String, expected: List<Boolean>) {
            val connectionNodes = compose.onAllNodesWithTag("connections-search").fetchSemanticsNodes().size
            compose.runOnIdle {
                val receipt = JSONObject().put("stage", stage)
                    .put("rootWindowFocused", compose.activity.window.decorView.hasWindowFocus())
                    .put("composeRootWindowFocused", composeRootFocused)
                    .put("lifecycle", compose.activity.lifecycle.currentState.name)
                    .put("connectionsSearchNodes", connectionNodes)
                    .put("fixtureMessage", state.value.message)
                    .put("connectionTelemetry", actions.connectionTelemetry.toString()).toString()
                Log.i("ConnectionDemandReceipt", receipt)
                assertEquals(receipt, expected, actions.connectionTelemetry)
            }
        }
        assertDemand("initial", emptyList())
        openSettingsDetail(R.string.lite_tab_connections)
        assertDemand("connections-open", listOf(true))
        compose.runOnUiThread { state.value = state.value.copy(message = "Fixture overlay") }
        compose.waitForIdle()
        assertDemand("covered", listOf(true, false))
        compose.runOnUiThread { state.value = state.value.copy(message = null) }
        compose.waitForIdle()
        assertDemand("uncovered", listOf(true, false, true))
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        assertDemand("started", listOf(true, false, true, false))
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertDemand("resumed", listOf(true, false, true, false, true))
        compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
        assertDemand("closed", listOf(true, false, true, false, true, false))
        compose.runOnIdle {
            assertTrue("Lifecycle fixture observations are not user/network actions", actions.events.isEmpty())
        }
    }

    @Test
    fun logsAreOptInPersistedAndTheirEntryTracksTheSetting() {
        val state = mutableStateOf(LiteState())
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        lateinit var controller: LiteController
        compose.activityRule.scenario.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
        compose.replaceAppContent {
            val scope = rememberCoroutineScope()
            val feature = remember {
                dev.aerove.lite.observability.ObservabilityFeature(scope, object : dev.aerove.lite.observability.HistoryDocuments {
                    override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean): Unit =
                        error("Unused logs setting fixture export")
                    override suspend fun deleteCreated(reference: String): Boolean = error("Unused logs setting fixture document cleanup")
                }) { error("Unused logs setting fixture DNS cache clear") }
            }
            DisposableEffect(feature) { onDispose { feature.close() } }
            val host = remember(feature) {
                object : LiteFeatureHost by controller {
                    override val observability get() = feature
                }
            }
            CompositionLocalProvider(LocalLiteFeatures provides { host }) { LiteApp(state.value, actions) }
        }
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNodeWithText(text(R.string.lite_logs)).assertDoesNotExist()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_general)) and hasClickAction())
        click(R.string.lite_general)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(text(R.string.lite_open_logs)))
        compose.onNodeWithContentDescription(text(R.string.lite_open_logs)).performClick()
        compose.runOnUiThread { assertEquals(listOf(LiteSettings(openLogs = true)), actions.settings) }
        compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_logs)) and hasClickAction())
        click(R.string.lite_logs)
        compose.onNodeWithTag("history-log-export").assertExists()
        compose.onNodeWithTag("history-log-clear").assertExists()
        compose.onNodeWithText(text(R.string.history_empty)).assertExists()
        compose.onNodeWithText(text(R.string.history_empty_detail)).assertExists()
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
        val refresh = compose.onNodeWithTag("dashboard-status-flag")
        refresh.assertContentDescriptionEquals("${text(R.string.lite_outbound_retry)}\n${identity.countryCode}")
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
        val inherited = DnsPreferences(values = mapOf(DnsField.Nameserver to DnsValue.Entries(listOf("https://1.1.1.1/dns-query"))))
        val initial = LiteSettings(colorMode = ColorMode.Light, dnsPreferences = inherited)
        val state = mutableStateOf(LiteState(settings = initial))
        val requests = mutableListOf<DnsPreferences>()
        val attempts = DnsSaveAttempts { state.value = state.value.copy(dnsSaveReceipt = it) }
        var submitted = 0L
        val settingsActions = UiTestActions { state.value = state.value.copy(settings = it) }
        val actions = object : dev.aerove.lite.LiteActions by settingsActions {
            override fun saveDnsPreferences(preferences: DnsPreferences): Long {
                requests += preferences
                return attempts.begin().also { submitted = it }
            }
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        openSettingsDetail(R.string.integration_advanced_configuration)
        click(R.string.lite_dns)
        compose.onNodeWithTag("dns-preferences").assertExists()
        val nameserver = compose.onNodeWithTag("dns-field-nameserver")
        nameserver.performScrollTo().assert(hasAnyDescendant(hasText(text(R.string.dns_preferences_inherit))))
        compose.onNodeWithText("https://1.1.1.1/dns-query").assertDoesNotExist()
        nameserver.performClick()
        val override = compose.onNodeWithContentDescription(text(R.string.dns_preferences_override))
        override.assertIsOff().performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()); assertEquals(inherited, state.value.settings.dnsPreferences) }
        val entry = compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1))
        entry.performTextReplacement("")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.dns_preferences_invalid, "dns.nameserver[0]")).assertExists()
        compose.runOnIdle { assertTrue(requests.isEmpty()); assertEquals(inherited, state.value.settings.dnsPreferences) }
        val server = "https://9.9.9.9/dns-query"
        entry.performTextReplacement(server)
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        val saved = inherited.withValue(DnsField.Nameserver, DnsValue.Entries(listOf(server)))
        compose.runOnIdle { assertEquals(listOf(saved), requests); assertEquals(initial, state.value.settings) }
        compose.onNodeWithTag("dns-save").assertIsNotEnabled()
        compose.runOnUiThread {
            state.value = state.value.copy(settings = initial.copy(dnsPreferences = requests.single()))
            attempts.complete(submitted, failed = false)
        }
        compose.onNodeWithTag("dns-save").assertDoesNotExist()
        nameserver.performScrollTo().performClick()
        override.assertIsOn().performClick()
        compose.runOnIdle { assertEquals(1, requests.size); assertEquals(saved, state.value.settings.dnsPreferences) }
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(saved, saved.inherit(DnsField.Nameserver)), requests)
            assertFalse(DnsField.Nameserver in requests.last().overridden)
            assertEquals(listOf(server), (requests.last().value(DnsField.Nameserver) as DnsValue.Entries).value)
            assertEquals(saved, state.value.settings.dnsPreferences)
            assertTrue(settingsActions.settings.isEmpty())
        }
        compose.onNodeWithTag("dns-save").assertIsNotEnabled()
        compose.runOnUiThread {
            state.value = state.value.copy(settings = initial.copy(dnsPreferences = requests.last()))
            attempts.complete(submitted, failed = false)
        }
        compose.onNodeWithTag("dns-save").assertDoesNotExist()
        nameserver.assert(hasAnyDescendant(hasText(text(R.string.dns_preferences_inherit))))
        compose.runOnIdle { assertEquals(initial.copy(dnsPreferences = saved.inherit(DnsField.Nameserver)), state.value.settings) }
    }

    @Test
    fun firstIncludeSelectionIsLocalUntilOneAtomicApply() {
        val packageName = "dev.aerove.fixture.mail"
        val app = InstalledApplication(packageName, "Fixture Mail", system = false, internet = true, lastUpdateTime = 1L)
        val initial = LiteSettings(colorMode = ColorMode.Light).withAppAccess(
            AppAccessPreferences(enabled = true, mode = AppAccessMode.Include))
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions({ state.value = state.value.copy(settings = it) }, initial)
        lateinit var controller: LiteController
        compose.activityRule.scenario.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
        compose.replaceAppContent {
            val scope = rememberCoroutineScope()
            val feature = remember {
                AppAccessFeature(scope, initial.appAccess, FixtureApplicationLoader(app)) { preferences ->
                    actions.changeSettings(state.value.settings.withAppAccess(preferences))
                }
            }
            DisposableEffect(feature) { onDispose { feature.close() } }
            val host = remember(feature) {
                object : LiteFeatureHost by controller {
                    override val appAccess get() = feature
                    override fun closeAppAccess() = feature.close()
                }
            }
            CompositionLocalProvider(LocalLiteFeatures provides { host }) { LiteApp(state.value, actions) }
        }
        openSettingsDetail(R.string.lite_app_filter)
        compose.onNodeWithText(app.label).assertExists()
        compose.onNodeWithContentDescription(text(R.string.access_save)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue("Opening app access must not persist", actions.settings.isEmpty())
            assertEquals(initial, state.value.settings)
        }
        val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onNode(checkbox and hasText(app.label)).performClick()
        compose.onNodeWithContentDescription(text(R.string.access_save)).assertExists()
        compose.runOnIdle {
            assertTrue("Selecting an application must remain a local draft", actions.settings.isEmpty())
            assertEquals(initial, state.value.settings)
        }
        compose.onNodeWithContentDescription(text(R.string.access_save)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val expected = initial.withAppAccess(initial.appAccess.copy(includePackages = setOf(packageName)))
            assertEquals(listOf(expected), actions.settings)
            assertEquals(expected, state.value.settings)
            assertTrue(actions.events.none { it == "connect" || it == "disconnect" })
        }
    }
}
