package dev.aerove.lite.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import dev.aerove.lite.networksettings.NetworkSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkTunPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val settings = mutableStateOf(NetworkSettings())
    private val busy = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val requested = mutableListOf<NetworkSettings>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open() {
        compose.replaceAppContent {
            val overlay = remember { LiteOverlayState() }
            LiteTheme(LiteSettings(material = MaterialTier.Solid)) {
                val backdrop = rememberLayerBackdrop()
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                    CompositionLocalProvider(
                        LocalLiteOverlay provides overlay,
                        LocalLiteListState provides listState,
                        LocalLiteBackdrop provides backdrop,
                        LocalLiteInkBackdrop provides backdrop,
                    ) {
                        NetworkSettingsScreen(settings.value, busy.value, PaddingValues(16.dp), { requested += it }, error.value)
                        overlay.content?.invoke()
                    }
                }
            }
        }
    }

    @Test fun mtuChecksExactBoundsThenWaitsForAuthoritativeReadback() {
        open()
        compose.onNodeWithText(context.getString(R.string.network_mtu)).performScrollTo().performClick()
        val field = compose.onNodeWithContentDescription(context.getString(R.string.network_mtu))
        field.performTextReplacement("1279")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertTrue(requested.isEmpty())
        field.performScrollTo().performTextReplacement("1280")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertEquals(1280, requested.single().mtu)
        compose.onNodeWithTag("network-save").assertExists()
        compose.runOnUiThread { settings.value = requested.single() }
        compose.onNodeWithTag("network-save").assertDoesNotExist()
    }

    @Test fun routeFormHasRealEntryOrderingAndCancelDoesNotSave() {
        open()
        compose.onNodeWithText(context.getString(R.string.network_route_addresses)).performScrollTo().performClick()
        compose.onNodeWithTag("network-add-route").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_route_entry, 1)).performTextReplacement("192.0.2.0/24")
        compose.onNodeWithTag("network-add-route").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_route_entry, 2)).performTextReplacement("2001:db8::/32")
        compose.onNodeWithText(context.getString(R.string.network_move_route_up)).performScrollTo().performClick()
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertEquals(listOf("2001:db8::/32", "192.0.2.0/24"), requested.single().routeAddress)
        compose.runOnUiThread { settings.value = requested.single() }
        compose.onNodeWithTag("network-save").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.network_route_exclusions)).performScrollTo().performClick()
        compose.onNodeWithTag("network-add-route").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_route_entry, 1)).performTextReplacement("192.0.2.1/32")
        compose.onNodeWithText(context.getString(R.string.network_cancel)).performScrollTo().performClick()
        assertEquals(1, requested.size); assertTrue(settings.value.routeExcludeAddress.isEmpty())
    }

    @Test fun badCidrAndFailedSaveNeverDiscardTheDraftOrInventAppliedState() {
        open()
        compose.onNodeWithText(context.getString(R.string.network_route_addresses)).performScrollTo().performClick()
        compose.onNodeWithTag("network-add-route").performClick()
        val field = compose.onNodeWithContentDescription(context.getString(R.string.network_route_entry, 1))
        field.performTextReplacement("example.org/24")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        assertTrue(requested.isEmpty())
        field.performScrollTo().performTextReplacement("192.0.2.0/24")
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
        compose.runOnUiThread { busy.value = true }
        compose.runOnUiThread { busy.value = false; error.value = "fixture persistence failure" }
        field.performScrollTo().assertTextEquals("192.0.2.0/24")
        compose.onNodeWithTag("network-save").performScrollTo().assertIsEnabled()
        assertTrue(settings.value.routeAddress.isEmpty())
    }

    @Test fun dnsHijackAndPrivateBypassOnlyRequestSavedChanges() {
        open()
        val dns = compose.onNodeWithContentDescription(context.getString(R.string.network_dns_hijacking))
        dns.performScrollTo().assertIsOff().performClick()
        assertTrue(requested.single().dnsHijacking)
        dns.assertIsOff()
        compose.runOnUiThread { settings.value = requested.single() }
        dns.assertIsOn()
        compose.onNodeWithContentDescription(context.getString(R.string.network_bypass_private_route)).performScrollTo().assertIsOn().performClick()
        assertFalse(requested.last().bypassPrivateRoute)
        assertTrue(settings.value.bypassPrivateRoute)
    }
}
