package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LiteAppRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionPagesChromeAndModalRenderWithoutInvokingCore() {
        fullAuroraMesh(FullAuroraSeed, FullAuroraColors, case.dark)
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(LiteState(settings = case.settings))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        val probe = RenderProbe()
        compose.replaceAppContent {
            val view = LocalView.current
            val density = LocalDensity.current.density
            val fontScale = LocalDensity.current.fontScale
            SideEffect { probe.view = view; probe.density = density; probe.fontScale = fontScale }
            Box(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["app"] = it.boundsInWindow() }) { LiteApp(state.value, actions) }
        }
        val capture = HardwareCapture(compose, probe, "app_$case")
        val report = JSONObject().put("case", case.toString()).put("businessState", "In-memory instrumentation fixture; no live VPN or production fake nodes")
        fun save(label: String) {
            compose.mainClock.advanceTimeBy(400)
            capture.capture(label).close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun text(id: Int) = context.getString(id)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        fun navigate(id: Int) { compose.onNode(tab and hasText(text(id))).performClick() }
        fun openTool(id: Int) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(id)) and hasClickAction())
            compose.onNode(hasText(text(id)) and hasClickAction()).performClick()
        }
        try {
            compose.onNodeWithContentDescription(text(R.string.lite_connect)).assertIsNotEnabled()
            compose.onAllNodes(tab).assertCountEquals(3)
            compose.onNode(tab and hasText(text(R.string.lite_tab_proxies))).assertDoesNotExist()
            save("home")
            compose.runOnUiThread { state.value = state.value.copy(groups = listOf(ProxyGroup("Fixture group", "Selector", "DIRECT", listOf(ProxyNode("DIRECT", "Direct"))))) }
            compose.onAllNodes(tab).assertCountEquals(4)
            navigate(R.string.lite_tab_proxies)
            compose.onNodeWithText("Fixture group").assertExists()
            save("proxies-test-fixture")
            navigate(R.string.lite_tab_profiles)
            compose.onNodeWithText(text(R.string.lite_no_profiles)).assertExists()
            save("profiles-empty")
            compose.onNodeWithContentDescription(text(R.string.lite_add_subscription)).performClick()
            compose.onNode(tab).assertDoesNotExist()
            compose.onNode(hasText(text(R.string.lite_add_subscription)) and hasClickAction()).performClick()
            compose.onNodeWithText(text(R.string.lite_subscription_error)).assertExists()
            save("subscription-validation-modal")
            compose.onNodeWithContentDescription(text(R.string.lite_close)).performClick()
            navigate(R.string.lite_tab_tools)
            save("tools")
            openTool(R.string.lite_tab_connections)
            compose.onNodeWithText(text(R.string.lite_no_connections)).assertExists()
            save("connections-empty")
            compose.onNodeWithContentDescription(text(R.string.lite_back)).performClick()
            openTool(R.string.lite_theme)
            save("theme")
            compose.onNodeWithContentDescription(text(R.string.lite_aurora)).performClick()
            compose.runOnUiThread { assertEquals(!case.aurora, state.value.settings.aurora) }
            save("theme-aurora-toggled")
            compose.runOnUiThread {
                assertEquals(1, actions.settings.size)
                assertTrue("These screenshot fixtures must not invoke a real import, VPN or core action", actions.events.all { it == "changeSettings" })
            }
            report.put("settingsActions", actions.settings.size).put("serviceActions", 0)
        } finally { try { capture.metrics(report) } finally { capture.close() } }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases(switchStates = false)
    }
}
