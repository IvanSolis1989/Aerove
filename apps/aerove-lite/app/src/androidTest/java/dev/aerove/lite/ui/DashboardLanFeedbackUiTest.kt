package dev.aerove.lite.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.*
import dev.aerove.lite.networksettings.DashboardLanReceipt
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.notices.ClientNotices
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DashboardLanFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)
    private val notices = ClientNotices()
    private val state = mutableStateOf(LiteState(localAddress = "192.0.2.8"))
    private val pageActive = mutableStateOf(true)
    private val mounted = mutableStateOf(true)
    private var token = 0L
    private var enableCalls = 0

    private fun open(rejectCopy: Boolean = false) {
        compose.runOnUiThread {
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "keep existing"))
            notices.setForeground(true)
        }
        val actions = object : LiteActions by UiTestActions({ state.value = state.value.copy(settings = it) }, state.value.settings) {
            override fun enableDashboardLan(): Long { enableCalls++; return ++token }
        }
        compose.replaceAppContent {
            val platformClipboard = LocalClipboardManager.current
            val testClipboard = remember(platformClipboard) {
                object : androidx.compose.ui.platform.ClipboardManager by platformClipboard {
                    override fun setText(annotatedString: AnnotatedString) {
                        if (rejectCopy) throw SecurityException("clipboard fixture denied")
                        platformClipboard.setText(annotatedString)
                    }
                }
            }
            CompositionLocalProvider(LocalLitePageActive provides pageActive.value,
                LocalDiagnosticsForeground provides pageActive.value, LocalLiteWindowFocused provides pageActive.value,
                LocalClipboardManager provides testClipboard, LocalClientNotices provides notices) {
                LiteTheme(LiteSettings(material = MaterialTier.Solid)) {
                    val backdrop = rememberLayerBackdrop()
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop))
                        CompositionLocalProvider(LocalLiteBackdrop provides backdrop, LocalLiteInkBackdrop provides backdrop) {
                            if (mounted.value) DashboardCard(DashboardWidget.OutboundMode, state.value, actions,
                                {}, {}, {}, serviceStatusContent = {}, memoryInfoContent = {}, openRequests = {}, openDns = {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun complete(ip: String? = "192.0.2.8", receiptToken: Long = token, failed: Boolean = false) {
        compose.runOnUiThread {
            val settings = state.value.settings
            state.value = state.value.copy(settings = settings.copy(network = settings.network.copy(
                allowLan = true, ports = UserProxyPorts(mixed = 8899))), localAddress = ip,
                dashboardLanReceipt = DashboardLanReceipt(receiptToken, if (failed) null else 8899))
        }
        compose.waitForIdle()
    }

    private fun assertClipboard(expected: String) = compose.runOnIdle {
        assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }
    private fun texts() = notices.state.value.let { it.entries.map { entry -> entry.notice.text } + it.buffered.map { entry -> entry.text } }

    @Test fun actualCardWaitsForItsReceiptCopiesOnceAndKeepsItsGeometry() {
        open()
        val original = compose.onNodeWithTag("dashboard-network-controls").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("dashboard-intranet").performClick()
        assertEquals(1, enableCalls); assertClipboard("keep existing"); assertTrue(texts().isEmpty())
        complete()
        assertClipboard("192.0.2.8:8899")
        assertEquals(listOf(context.getString(R.string.lite_lan_address_copied, "192.0.2.8:8899")), texts())
        assertEquals(original, compose.onNodeWithTag("dashboard-network-controls").fetchSemanticsNode().boundsInRoot)
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "after success")) }
        compose.runOnUiThread { state.value = state.value.copy(localAddress = "192.0.2.9") }
        compose.waitForIdle(); assertClipboard("after success")
        compose.onNodeWithTag("dashboard-intranet").performClick()
        assertFalse(state.value.settings.network.allowLan); assertClipboard("after success")
        compose.onNodeWithTag("dashboard-lan-copy").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-lan-footer").assertDoesNotExist()
    }

    @Test fun noPhysicalAddressShowsTheLocalizedNoticeAndPreservesClipboard() {
        open(); compose.onNodeWithTag("dashboard-intranet").performClick(); complete(ip = null)
        assertClipboard("keep existing")
        assertEquals(listOf(context.getString(R.string.lite_lan_enabled_no_address)), texts())
    }

    @Test fun ipv6AndCopyFailureStillShowTheActualAddressWithoutASuccessClaim() {
        open(rejectCopy = true); compose.onNodeWithTag("dashboard-intranet").performClick(); complete(ip = "2001:db8::8")
        assertClipboard("keep existing")
        assertEquals(listOf(context.getString(R.string.lite_lan_address_copy_failed, "[2001:db8::8]:8899")), texts())
    }

    @Test fun ipv6IsCopiedWithBracketsAndTheActualMixedPort() {
        open(); compose.onNodeWithTag("dashboard-intranet").performClick(); complete(ip = "2001:db8::8")
        assertClipboard("[2001:db8::8]:8899")
        assertEquals(listOf(context.getString(R.string.lite_lan_address_copied, "[2001:db8::8]:8899")), texts())
    }

    @Test fun passiveRestoreAndFailureNeverCopy() {
        open(); complete()
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
        compose.onNodeWithTag("dashboard-intranet").performClick()
        compose.onNodeWithTag("dashboard-intranet").performClick(); complete(failed = true)
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
    }

    @Test fun backgroundingAndReturningCannotReplayAPendingEnable() {
        open(); compose.onNodeWithTag("dashboard-intranet").performClick()
        compose.runOnUiThread { pageActive.value = false }; compose.waitForIdle()
        complete()
        compose.runOnUiThread { pageActive.value = true }; compose.waitForIdle()
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
    }

    @Test fun wrongReceiptAndAClosedLanCannotConsumeAnOldEnable() {
        open(); compose.onNodeWithTag("dashboard-intranet").performClick(); complete(receiptToken = token + 1)
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
        compose.onNodeWithTag("dashboard-intranet").performClick()
        compose.runOnUiThread { state.value = state.value.copy(dashboardLanReceipt = DashboardLanReceipt(token, 8899)) }
        compose.waitForIdle()
        assertFalse(state.value.settings.network.allowLan)
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
    }

    @Test fun leavingAndRemountingCannotReplayAPendingEnable() {
        open(); compose.onNodeWithTag("dashboard-intranet").performClick()
        compose.runOnUiThread { mounted.value = false }; compose.waitForIdle()
        complete()
        compose.runOnUiThread { mounted.value = true }; compose.waitForIdle()
        assertClipboard("keep existing"); assertTrue(texts().isEmpty())
    }
}
