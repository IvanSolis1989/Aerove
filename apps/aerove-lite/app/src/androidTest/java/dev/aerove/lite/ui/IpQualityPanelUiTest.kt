package dev.aerove.lite.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test

class IpQualityPanelUiTest {
    val compose = createAndroidComposeRule<MainActivity>()
    private val idleWatchdog = IpQualityIdleWatchdog()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(idleWatchdog).around(compose)
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
        compose.onNodeWithTag("ip-quality-summary").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithTag("dashboard-status-ip").performClick()
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
        idleWatchdog.mark("body.entry")
        val calls = AtomicInteger()
        idleWatchdog.observeCalls { calls.get() }
        val state = LiteState(phase = VpnPhase.Running, selectedProfile = "quality-display-fixture",
            outboundIdentity = OutboundIdentity("198.51.100.8", "SG", null, "unknown"))
        val lookup: suspend (String) -> IpQuality = { ip ->
            if (calls.incrementAndGet() == 2) throw IpQualityLookupException(listOf(
                IpQualityFailure(IpQualitySource.IpQuery, IpQualitySourceStatus.RateLimited)))
            IpQuality(ip, IpQualitySource.IdentMe, IpType.Business, "Fixture organization", 123)
        }
        idleWatchdog.mark("replaceAppContent.before")
        compose.replaceAppContent { IpQualityFixture(state, lookup) { LiteApp(state, UiTestActions {}) } }
        idleWatchdog.mark("replaceAppContent.after")
        idleWatchdog.mark("waitLookup1.before")
        compose.waitUntil(5000) { calls.get() == 1 }
        idleWatchdog.mark("waitLookup1.after")
        idleWatchdog.mark("summaryClick.before")
        compose.onNodeWithTag("ip-quality-summary").performClick()
        idleWatchdog.mark("summaryClick.after")
        idleWatchdog.mark("assertSource1.before")
        compose.onNodeWithText("ident.me").assertExists()
        idleWatchdog.mark("assertSource1.after")
        idleWatchdog.mark("assertAsn.before")
        compose.onNodeWithText("AS123").assertExists()
        idleWatchdog.mark("assertAsn.after")
        assertEquals(1, calls.get())
        idleWatchdog.mark("retry1Click.before")
        compose.onNodeWithText(context.getString(R.string.quality_retry)).performClick()
        idleWatchdog.mark("retry1Click.after")
        idleWatchdog.mark("waitLookup2.before")
        compose.waitUntil(5000) { calls.get() == 2 }
        idleWatchdog.mark("waitLookup2.after")
        idleWatchdog.mark("assertRateLimited.before")
        compose.onNodeWithText(context.getString(R.string.quality_source_rate_limited)).assertExists()
        idleWatchdog.mark("assertRateLimited.after")
        idleWatchdog.mark("retry2Click.before")
        compose.onNodeWithText(context.getString(R.string.quality_retry)).performClick()
        idleWatchdog.mark("retry2Click.after")
        idleWatchdog.mark("waitLookup3.before")
        compose.waitUntil(5000) { calls.get() == 3 }
        idleWatchdog.mark("waitLookup3.after")
        idleWatchdog.mark("assertRecoveredSource.before")
        compose.onNodeWithText("ident.me").assertExists()
        idleWatchdog.mark("assertRecoveredSource.after")
    }
}
