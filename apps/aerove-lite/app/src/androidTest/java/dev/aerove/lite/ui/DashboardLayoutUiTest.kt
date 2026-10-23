package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.ConnectionInfo
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.R
import dev.aerove.lite.Traffic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class DashboardLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(LiteState())
    private val editing = mutableStateOf(false)
    private val bounds = ConcurrentHashMap<String, Rect>()
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }
    private var profilesOpened = 0
    private var proxiesOpened = 0
    private var connectionsOpened = 0
    private var density = 1f

    private fun fixture(widgets: List<DashboardWidget>) = LiteState(
        settings = LiteSettings(material = MaterialTier.Solid, aurora = false, dashboardWidgets = widgets),
        profiles = listOf(ProfileSummary("ui-fixture", "Selected profile fixture", null, 0)), selectedProfile = "ui-fixture",
        outboundIdentity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential"),
        localAddress = "192.0.2.8", traffic = Traffic(1024, 2048, 4096, 8192),
        groups = listOf(ProxyGroup("Group fixture", "Selector", "Node fixture", listOf(ProxyNode("Node fixture", "Direct")))),
        connections = List(2) { ConnectionInfo("fixture-$it", "example.invalid", "tcp", "MATCH", emptyList(), 0, 0) },
    )

    private fun launch(initial: LiteState) {
        state.value = initial
        compose.replaceAppContent {
            IpQualityFixture(state.value) {
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                LiteTheme(state.value.settings) {
                    density = LocalDensity.current.density
                    val source = rememberLayerBackdrop()
                    val scroll = rememberLazyListState()
                    val content: @Composable (DashboardWidget) -> Unit = { widget ->
                        val mode = if (editing.value) "edit" else "view"
                        Box(Modifier.fillMaxWidth().onGloballyPositioned { bounds["$mode-${widget.wireName}"] = it.boundsInRoot() }) {
                            DashboardCard(widget, state.value, actions, { profilesOpened++ }, { connectionsOpened++ }, { proxiesOpened++ },
                                serviceStatusContent = { LiteText("Service content fixture") },
                                memoryInfoContent = { LiteText("Memory content fixture") })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                            LiteWallpaper(remember { mutableStateOf(0f) }, Modifier.fillMaxSize())
                        }
                        CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source, LocalLiteListState provides scroll) {
                            Box(Modifier.align(Alignment.TopCenter).widthIn(max = 592.dp).fillMaxSize()) {
                                if (editing.value) DashboardEditor(state.value.settings,
                                    { actions.changeSettings(it) }, { editing.value = false }, cardContent = content)
                                else {
                                    OverviewScreen(state.value.settings, PaddingValues(top = LiteMetrics.PageToolbar), content)
                                    LitePageHeader(context.getString(R.string.lite_tab_home), 0.dp, scroll,
                                        backLabel = context.getString(R.string.lite_back)) {
                                        DashboardEditAction { editing.value = true }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private fun scroll(widget: DashboardWidget) {
        compose.onNodeWithTag("dashboard-layout").performScrollToNode(hasTestTag("dashboard-card-${widget.wireName}"))
    }
    private fun tapCard(widget: DashboardWidget) {
        scroll(widget)
        compose.onNodeWithTag("dashboard-card-${widget.wireName}").performTouchInput { click() }
    }
    private fun top(widget: DashboardWidget) = compose.onNodeWithTag("dashboard-card-${widget.wireName}").fetchSemanticsNode().boundsInRoot.top

    @Test
    fun persistedOrderRemovalAndOptionalMemoryControlTheActualCards() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.IntranetIp, DashboardWidget.Profiles)))
        compose.onNodeWithText("198.51.100.8").assertIsDisplayed()
        compose.onNodeWithText("192.0.2.8").assertIsDisplayed()
        compose.onNodeWithText("Selected profile fixture").assertIsDisplayed()
        assertTrue(top(DashboardWidget.IntranetIp) < top(DashboardWidget.Profiles))
        compose.onNodeWithTag("dashboard-speed").assertDoesNotExist()
        compose.onNodeWithTag("dashboard-mode").assertDoesNotExist()
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(
            dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.Profiles, DashboardWidget.IntranetIp))) }
        assertTrue(top(DashboardWidget.Profiles) < top(DashboardWidget.IntranetIp))
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(
            dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.IntranetIp, DashboardWidget.MemoryInfo))) }
        compose.onNodeWithTag("dashboard-card-profiles").assertDoesNotExist()
        scroll(DashboardWidget.MemoryInfo)
        compose.onNodeWithText("Memory content fixture").assertIsDisplayed()
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun emptyProxyGroupsRemainInTheSavedLayoutWithoutInventingNodes() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.ProxyGroups)).copy(groups = emptyList()))
        scroll(DashboardWidget.ProxyGroups)
        compose.onNodeWithTag("dashboard-card-proxyGroups").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lite_no_proxies_detail)).assertIsDisplayed()
        tapCard(DashboardWidget.ProxyGroups)
        assertEquals(0, proxiesOpened)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun sharedCardsKeepTheActualDomainValuesControlsAndRoutes() {
        val defaults = LiteSettings().dashboardWidgets
        assertEquals(9, defaults.size)
        assertEquals(10, DashboardWidget.entries.size)
        launch(fixture(defaults))
        scroll(DashboardWidget.NetworkSpeed)
        compose.onNodeWithText(context.getString(R.string.lite_traffic_total, formatBytes(4096))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lite_traffic_total, formatBytes(8192))).assertIsDisplayed()
        scroll(DashboardWidget.OutboundMode)
        compose.onNode(hasText(context.getString(R.string.lite_mode_global)) and hasClickAction()).performClick().assertIsSelected()
        assertEquals(ProxyMode.Global, state.value.settings.mode)
        scroll(DashboardWidget.VpnButton)
        compose.onNodeWithContentDescription(context.getString(R.string.lite_vpn)).assertIsOn().performClick().assertIsOff()
        assertEquals(0, actions.events.count { it == "connect" || it == "disconnect" })
        tapCard(DashboardWidget.Profiles)
        assertEquals(1, profilesOpened)
        scroll(DashboardWidget.ProxyGroups)
        compose.onNodeWithText("Group fixture").assertIsDisplayed()
        compose.onNodeWithText("Node fixture").assertIsDisplayed()
        tapCard(DashboardWidget.ProxyGroups)
        assertEquals(1, proxiesOpened)
        scroll(DashboardWidget.ServiceStatus)
        compose.onNodeWithText("Service content fixture").assertIsDisplayed()
        scroll(DashboardWidget.ConnectionStats)
        compose.onNodeWithText(context.getString(R.string.lite_connection_count, 2)).assertIsDisplayed()
        tapCard(DashboardWidget.ConnectionStats)
        assertEquals(1, connectionsOpened)
    }

    @Test
    fun editActionMountsTheRealCardContentWithTheSameGeometryAndNoBusinessTaps() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.IntranetIp))
        launch(initial)
        compose.onNodeWithText("Example ISP").assertIsDisplayed()
        val normal = requireNotNull(bounds["view-currentOutbound"])
        compose.onNodeWithContentDescription(context.getString(R.string.lite_dashboard_edit)).performClick()
        compose.onNodeWithTag("dashboard-row-currentOutbound").assertIsDisplayed()
        val preview = requireNotNull(bounds["edit-currentOutbound"])
        assertEquals(normal.width, preview.width, 0.5f)
        assertEquals(normal.height, preview.height, 0.5f)
        compose.onNodeWithTag("dashboard-row-currentOutbound").performTouchInput {
            click(Offset(width - 60 * density, height / 2f))
        }
        assertTrue(actions.events.isEmpty())
        assertEquals(initial, state.value)
        assertEquals(0, profilesOpened + proxiesOpened + connectionsOpened)
    }
}
