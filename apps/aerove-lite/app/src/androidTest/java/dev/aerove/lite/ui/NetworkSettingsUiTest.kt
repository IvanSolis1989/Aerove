package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.R
import dev.aerove.lite.networksettings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(LiteState(settings = LiteSettings(material = MaterialTier.Solid, aurora = false)))
    private val requested = mutableListOf<LiteSettings>()
    private val actions = UiTestActions { requested += it }

    private fun open() {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) }
        }
        compose.onNodeWithText(context.getString(R.string.lite_tab_tools)).performClick()
        compose.onNodeWithText(context.getString(R.string.network_title)).performScrollTo().performClick()
        compose.onNodeWithTag("network-settings").assertExists()
    }

    @Test fun rootPortEditorValidatesDuplicatesAndWaitsForAuthoritativeSavedReadback() {
        open()
        compose.onNodeWithText(context.getString(R.string.network_ports)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_http_port)).performTextReplacement("7890")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertTrue(requested.isEmpty())
        compose.onNodeWithContentDescription(context.getString(R.string.network_http_port)).performTextReplacement("7891")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertEquals(UserProxyPorts(7890, 7891, 0), requested.single().network.ports)
        compose.onNodeWithTag("network-save").assertExists()
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("settings")) }
        compose.runOnUiThread { state.value = state.value.copy(settings = requested.single(), busy = emptySet()) }
        compose.onNodeWithTag("network-save").assertDoesNotExist()
    }

    @Test fun rootFailedSaveRetainsDraftAndCancelDoesNotSave() {
        open()
        compose.onNodeWithText(context.getString(R.string.network_ports)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_http_port)).performTextReplacement("7891")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        compose.runOnUiThread { state.value = state.value.copy(busy = setOf("settings")) }
        compose.runOnUiThread { state.value = state.value.copy(busy = emptySet(), message = "fixture disk failure") }
        compose.onNodeWithText(context.getString(R.string.lite_dismiss)).performClick()
        compose.runOnUiThread { state.value = state.value.copy(message = null) }
        compose.onNodeWithContentDescription(context.getString(R.string.network_http_port)).assertTextEquals("7891")
        compose.onNodeWithText(context.getString(R.string.network_cancel)).performScrollTo().performClick()
        assertEquals(1, requested.size)
        assertEquals(0, state.value.settings.network.ports.http)
    }

    @Test fun rootAuthenticationKeepsSystemProxyPreferenceAndMasksThePassword() {
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(network = NetworkSettings(
            authentication = ProxyAuthentication(true, "alice", "private-password")))) }
        open()
        compose.onNodeWithText(context.getString(R.string.network_authenticated_proxy_detail)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.network_credentials)).performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_password)).assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithText(context.getString(R.string.network_cancel)).performScrollTo().performClick()
        assertTrue(requested.isEmpty())
        assertTrue(state.value.settings.network.systemProxy)
        assertNull(effectiveVpnNetworkOptions(state.value.settings.network).systemProxy)
    }

    @Test fun androidProxyInfoRetainsTheRealLoopbackPortAndExclusionList() {
        val settings = NetworkSettings(bypassDomains = listOf("localhost", "*.example.org", "127.*"))
        val proxy = effectiveVpnNetworkOptions(settings).systemProxy!!
        val actual = android.net.ProxyInfo.buildDirectProxy(proxy.host, proxy.port, proxy.exclusions)
        assertTrue(actual.isValid)
        assertEquals("127.0.0.1", actual.host); assertEquals(7890, actual.port)
        assertEquals(proxy.exclusions, actual.exclusionList.toList())
    }
}
