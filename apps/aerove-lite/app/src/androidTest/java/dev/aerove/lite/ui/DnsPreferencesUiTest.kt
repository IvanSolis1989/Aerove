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
import dev.aerove.lite.dnssettings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DnsPreferencesUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val preferences = mutableStateOf(DnsPreferences())
    private val busy = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private val receipt = mutableStateOf<DnsSaveReceipt?>(null)
    private val attempts = DnsSaveAttempts { receipt.value = it }
    private var submittedToken = 0L
    private val requests = mutableListOf<DnsPreferences>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(hosts: Boolean = false, custom: Boolean = false) {
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
                        val submit: (DnsPreferences) -> Long = { draft ->
                            requests += draft
                            attempts.begin().also { submittedToken = it }
                        }
                        if (hosts) HostsPreferencesScreen(preferences.value, busy.value, PaddingValues(16.dp), submit, receipt.value, error.value)
                        else DnsPreferencesScreen(preferences.value, busy.value, PaddingValues(16.dp), submit, receipt.value, custom, error.value)
                        overlay.content?.invoke()
                    }
                }
            }
        }
    }

    @Test fun nameserverFormWaitsForSavedReadbackAndMapsTheActualOrderedSourceList() {
        open()
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1))
            .performTextReplacement("https://1.1.1.1/dns-query")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        assertEquals(1, requests.size)
        compose.onNodeWithTag("dns-save").assertExists()
        val requestedServers = (requests.single().value(DnsField.Nameserver) as DnsValue.Entries).value
        assertEquals(listOf("https://1.1.1.1/dns-query", "https://doh.pub/dns-query"), requestedServers)
        assertEquals(requestedServers, (transformDnsPreferences(emptyMap(), requests.single())["dns"] as Map<*, *>)["nameserver"])
        compose.runOnUiThread { preferences.value = requests.single(); attempts.complete(submittedToken, failed = false) }
        compose.onNodeWithTag("dns-save").assertDoesNotExist()
    }

    @Test fun cancellationDoesNotSaveAndRestoringInheritanceRetainsStoredValue() {
        preferences.value = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("1.1.1.1")))
        open()
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1)).performTextReplacement("8.8.8.8")
        compose.onNodeWithText(context.getString(R.string.dns_preferences_cancel)).performScrollTo().performClick()
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_override)).performClick()
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        assertFalse(DnsField.Nameserver in requests.single().overridden)
        assertEquals(listOf("1.1.1.1"), (requests.single().value(DnsField.Nameserver) as DnsValue.Entries).value)
    }

    @Test fun failedSavedReadbackRetainsEditableDraftAndAllowsRetry() {
        open()
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1)).performTextReplacement("1.1.1.1")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        compose.runOnUiThread { busy.value = true }
        compose.runOnUiThread { busy.value = false; error.value = "fixture disk failure"; attempts.complete(submittedToken, failed = true) }
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1)).assertTextEquals("1.1.1.1")
        compose.onNodeWithTag("dns-save").assertIsEnabled()
        assertEquals(1, requests.size)
    }

    @Test fun customProfileCanSaveBooleanOverrideWithoutApplyingTheGlobalDnsLayer() {
        open(custom = true)
        compose.onNodeWithTag("dns-field-enable").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_enabled)).performClick()
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        assertEquals(DnsValue.Bool(false), requests.single().value(DnsField.Enable))
        val global = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("9.9.9.9")))
        val final = transformDnsPreferences(emptyMap(), global, requests.single(), customProfile = true)["dns"] as Map<*, *>
        assertEquals(false, final["enable"])
        assertNotEquals(listOf("9.9.9.9"), final["nameserver"])
    }

    @Test fun hostsStructuredFormRejectsAliasThenSavesAndReadbacksRealIpMapping() {
        open(hosts = true)
        compose.onNodeWithText(context.getString(R.string.dns_preferences_hosts)).performClick()
        compose.onNodeWithTag("dns-add-entry").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_domain, 1)).performTextReplacement("+.example.org")
        val target = compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_target, 1))
        target.performTextReplacement("alias.example.org")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        assertTrue(requests.isEmpty())
        target.performTextReplacement("192.0.2.10")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        assertEquals(mapOf("+.example.org" to "192.0.2.10"), transformDnsPreferences(emptyMap(), requests.single())["hosts"])
        compose.runOnUiThread { preferences.value = requests.single(); attempts.complete(submittedToken, failed = false) }
        compose.onNodeWithTag("dns-save").assertDoesNotExist()
    }

    @Test fun fastRepeatedFailuresWithTheSameErrorKeepTheDraftEditable() {
        open()
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        val field = compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1))
        field.performTextReplacement("1.1.1.1")
        repeat(2) {
            compose.onNodeWithTag("dns-save").performScrollTo().performClick()
            compose.runOnUiThread { error.value = "same disk failure"; attempts.complete(submittedToken, failed = true) }
            field.assertTextEquals("1.1.1.1")
            compose.onNodeWithTag("dns-save").assertIsEnabled()
        }
        assertEquals(2, requests.size)
    }

    @Test fun successReceiptWaitsForSavedReadbackAndOldFailureCannotEnableNewPending() {
        open()
        compose.onNodeWithTag("dns-field-nameserver").performScrollTo().performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_entry, 1)).performTextReplacement("1.1.1.1")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        val old = submittedToken
        compose.runOnUiThread { attempts.complete(old, failed = true) }
        compose.onNodeWithTag("dns-save").assertIsEnabled()
        compose.onNodeWithTag("dns-save").performClick()
        compose.runOnUiThread { attempts.complete(old, failed = true) }
        compose.onNodeWithTag("dns-save").assertIsNotEnabled()
        compose.runOnUiThread { attempts.complete(submittedToken, failed = false) }
        compose.onNodeWithTag("dns-save").assertIsNotEnabled()
        compose.runOnUiThread { preferences.value = requests.last() }
        compose.onNodeWithTag("dns-save").assertDoesNotExist()
    }

    @Test fun failedHostsReceiptKeepsTheOriginalIpDraftAvailableForRetry() {
        open(hosts = true)
        compose.onNodeWithText(context.getString(R.string.dns_preferences_hosts)).performClick()
        compose.onNodeWithTag("dns-add-entry").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_domain, 1)).performTextReplacement("example.org")
        val target = compose.onNodeWithContentDescription(context.getString(R.string.dns_preferences_target, 1))
        target.performTextReplacement("192.0.2.10")
        compose.onNodeWithTag("dns-save").performScrollTo().performClick()
        compose.runOnUiThread { attempts.complete(submittedToken, failed = true) }
        target.assertTextEquals("192.0.2.10")
        compose.onNodeWithTag("dns-save").assertIsEnabled()
        compose.onNodeWithTag("dns-save").performClick()
        assertEquals(2, requests.size)
    }
}
