package dev.aerove.lite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.R
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.ipquality.IpQuality
import dev.aerove.lite.ipquality.IpQualityFailure
import dev.aerove.lite.ipquality.IpQualityLookupException
import dev.aerove.lite.ipquality.IpQualitySource
import dev.aerove.lite.ipquality.IpQualitySourceStatus
import dev.aerove.lite.ipquality.IpType
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IpQualityPanelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rootMessageCancelsCardAndOwnDetailWithoutStartingHiddenRequests() {
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val state = mutableStateOf(LiteState(phase = VpnPhase.Running, selectedProfile = "quality-display-fixture",
            outboundIdentity = OutboundIdentity("198.51.100.8", "SG", null, "unknown"), message = "Covered fixture"))
        val lookup: suspend (String) -> IpQuality = {
            calls.incrementAndGet()
            try { awaitCancellation() } finally { cancellations.incrementAndGet() }
        }
        compose.replaceAppContent { IpQualityFixture(state.value, lookup) { LiteApp(state.value, UiTestActions {}) } }
        compose.waitForIdle()
        assertEquals(0, calls.get())
        compose.runOnIdle { state.value = state.value.copy(message = null) }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.onNodeWithTag("ip-quality-summary").performClick()
        compose.onNodeWithText(context.getString(R.string.quality_detection_tip)).assertExists()
        compose.runOnIdle { assertEquals(1, calls.get()); assertEquals(0, cancellations.get()) }
        compose.runOnIdle { state.value = state.value.copy(message = "Detail covered fixture") }
        compose.waitUntil(5000) { cancellations.get() == 1 }
        compose.runOnIdle { assertEquals(1, calls.get()) }
        compose.runOnIdle { state.value = state.value.copy(message = null) }
        compose.waitUntil(5000) { calls.get() == 2 }
        compose.onNodeWithText(context.getString(R.string.quality_detection_tip)).assertExists()
    }

    @Test fun dashboardEditorPreviewDoesNotObserveQuality() {
        val calls = AtomicInteger()
        val cancellations = AtomicInteger()
        val state = LiteState(phase = VpnPhase.Running, selectedProfile = "quality-display-fixture",
            outboundIdentity = OutboundIdentity("198.51.100.8", "SG", null, "unknown"))
        val lookup: suspend (String) -> IpQuality = {
            calls.incrementAndGet()
            try { awaitCancellation() } finally { cancellations.incrementAndGet() }
        }
        compose.replaceAppContent { IpQualityFixture(state, lookup) { LiteApp(state, UiTestActions {}) } }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.waitUntil(5000) { cancellations.get() == 1 }
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun actualDashboardEntrySharesOwnerAndRendersSourceFailureRetryAndRecovery() {
        val calls = AtomicInteger()
        val state = LiteState(phase = VpnPhase.Running, selectedProfile = "quality-display-fixture",
            outboundIdentity = OutboundIdentity("198.51.100.8", "SG", null, "unknown"))
        val lookup: suspend (String) -> IpQuality = { ip ->
            if (calls.incrementAndGet() == 2) throw IpQualityLookupException(listOf(
                IpQualityFailure(IpQualitySource.IpQuery, IpQualitySourceStatus.RateLimited)))
            IpQuality(ip, IpQualitySource.IdentMe, IpType.Business, "Fixture organization", 123)
        }
        compose.replaceAppContent { IpQualityFixture(state, lookup) { LiteApp(state, UiTestActions {}) } }
        compose.waitUntil(5000) { calls.get() == 1 }
        compose.onNodeWithTag("ip-quality-summary").performClick()
        compose.onNodeWithText("ident.me").assertExists()
        compose.onNodeWithText("AS123").assertExists()
        assertEquals(1, calls.get())
        compose.onNodeWithText(context.getString(R.string.quality_retry)).performClick()
        compose.waitUntil(5000) { calls.get() == 2 }
        compose.onNodeWithText(context.getString(R.string.quality_source_rate_limited)).assertExists()
        compose.onNodeWithText(context.getString(R.string.quality_retry)).performClick()
        compose.waitUntil(5000) { calls.get() == 3 }
        compose.onNodeWithText("ident.me").assertExists()
    }
}
