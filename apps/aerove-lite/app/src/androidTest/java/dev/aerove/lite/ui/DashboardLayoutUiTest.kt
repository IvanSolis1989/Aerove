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
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.test.platform.app.InstrumentationRegistry
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.ServiceCheckView
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
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.ipquality.IpQuality
import dev.aerove.lite.ipquality.IpQualitySource
import dev.aerove.lite.ipquality.IpType
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

private const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"
private const val DESCRIPTION_FAKE = 2_000_000_000
private const val ROLE_FAKE = 1_000_000_000
private val ROLES = mapOf("android.view.View" to "", "android.widget.TextView" to "", "android.widget.Button" to TalkBackFocus.BUTTON)

class DashboardLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val state = mutableStateOf(LiteState())
    private val editing = mutableStateOf(false)
    private val foreground = mutableStateOf(true)
    private val bounds = ConcurrentHashMap<String, Rect>()
    private val actions = UiTestActions { state.value = state.value.copy(settings = it) }
    private var profilesOpened = 0
    private var proxiesOpened = 0
    private var connectionsOpened = 0
    private var requestsOpened = 0
    private var dnsOpened = 0
    private var density = 1f
    private var effectiveFontScale = 1f
    private var inputModes: InputModeManager? = null

    private fun fixture(widgets: List<DashboardWidget>) = LiteState(
        settings = LiteSettings(material = MaterialTier.Solid, aurora = false, dashboardWidgets = widgets),
        profiles = listOf(ProfileSummary("ui-fixture", "Selected profile fixture", null, 0)), selectedProfile = "ui-fixture",
        outboundIdentity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential"),
        localAddress = "192.0.2.8", traffic = Traffic(1024, 2048, 4096, 8192),
        groups = listOf(ProxyGroup("Group fixture", "Selector", "Node fixture", listOf(ProxyNode("Node fixture", "Direct")))),
        connections = List(2) { ConnectionInfo("fixture-$it", "example.invalid", "tcp", "MATCH", emptyList(), 0, 0) },
    )

    private fun launch(initial: LiteState, fontScale: Float = 1f, maxWidth: Dp = 592.dp,
        deviceDensity: Float? = null, language: String? = null,
        lookup: suspend (String) -> IpQuality = { IpQuality(it, IpQualitySource.IdentMe, IpType.Residential, organization = "Example ISP") }) {
        state.value = initial
        compose.replaceAppContent {
            val configuration = Configuration(context.resources.configuration).apply {
                if (language != null) setLocale(Locale.forLanguageTag(language))
            }
            val localizedContext = context.createConfigurationContext(configuration)
            inputModes = LocalInputModeManager.current
            IpQualityFixture(state.value, lookup) {
            CompositionLocalProvider(LocalContext provides localizedContext, LocalConfiguration provides configuration, LocalLiteMotionOverride provides true, LocalDiagnosticsForeground provides foreground.value,
                LocalDensity provides Density(deviceDensity ?: LocalDensity.current.density, fontScale)) {
                LiteTheme(state.value.settings) {
                    density = LocalDensity.current.density
                    effectiveFontScale = LocalDensity.current.fontScale
                    val source = rememberLayerBackdrop()
                    val scroll = rememberLazyListState()
                    val content: @Composable (DashboardWidget) -> Unit = { widget ->
                        val mode = if (editing.value) "edit" else "view"
                        Box(Modifier.fillMaxWidth().onGloballyPositioned { bounds["$mode-${widget.wireName}"] = it.boundsInRoot() }) {
                            DashboardCard(widget, state.value, actions, { profilesOpened++ }, { connectionsOpened++ }, { proxiesOpened++ },
                                serviceStatusContent = { LiteText("Service content fixture") },
                                memoryInfoContent = { LiteText("Memory content fixture") },
                                openRequests = { requestsOpened++ }, openDns = { dnsOpened++ })
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().layerBackdrop(source)) {
                            LiteWallpaper(remember { mutableStateOf(0f) }, Modifier.fillMaxSize())
                        }
                        CompositionLocalProvider(LocalLiteBackdrop provides source, LocalLiteInkBackdrop provides source, LocalLiteListState provides scroll) {
                            Box(Modifier.align(Alignment.TopCenter).widthIn(max = maxWidth).fillMaxSize()) {
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
        val anchor = dashboardPresentationWidgets(state.value.settings.dashboardWidgets).first { candidate ->
            candidate == widget || (candidate in DashboardStatusMembers && widget in DashboardStatusMembers) ||
                (candidate in DashboardControlMembers && widget in DashboardControlMembers) ||
                (candidate in DashboardOverviewMembers && widget in DashboardOverviewMembers)
        }
        compose.onNodeWithTag("dashboard-layout").performScrollToNode(hasTestTag("dashboard-card-${anchor.wireName}"))
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
        // Standard's dashboardTraffic writes 4096 and 8192 bytes as these.
        compose.onNodeWithContentDescription(context.getString(R.string.lite_traffic_total, "4 KB")).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_traffic_total, "8 KB")).assertIsDisplayed()
        compose.onNodeWithTag("dashboard-upload-total-raw", useUnmergedTree = true).assertTextEquals("4 KB").assertIsDisplayed()
        compose.onNodeWithTag("dashboard-download-total-raw", useUnmergedTree = true).assertTextEquals("8 KB").assertIsDisplayed()
        scroll(DashboardWidget.OutboundMode)
        compose.onNode(hasText(context.getString(R.string.lite_mode_global)) and hasClickAction()).performClick().assertIsSelected()
        assertEquals(ProxyMode.Global, state.value.settings.mode)
        scroll(DashboardWidget.VpnButton)
        compose.onNodeWithContentDescription(context.getString(R.string.lite_vpn)).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.network_allow_lan)).assertIsOff().performClick().assertIsOn()
        assertTrue(state.value.settings.network.allowLan)
        assertTrue(!state.value.settings.network.vpnEnabled)
        assertEquals(0, actions.events.count { it == "connect" || it == "disconnect" })
        tapCard(DashboardWidget.Profiles)
        assertEquals(1, profilesOpened)
        scroll(DashboardWidget.ProxyGroups)
        compose.onNodeWithText("Group fixture").assertIsDisplayed()
        compose.onNodeWithText("Node fixture").assertIsDisplayed()
        tapCard(DashboardWidget.ProxyGroups)
        assertEquals(1, proxiesOpened)
        scroll(DashboardWidget.ServiceStatus)
        compose.onNodeWithText(context.getString(R.string.dashboard_network_overview)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lite_tab_connections)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_requests)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_dns)).performClick()
        assertEquals(1, connectionsOpened)
        assertEquals(1, requestsOpened)
        assertEquals(1, dnsOpened)
        assertTrue(actions.dashboardTelemetry.contains(true))
        assertTrue(actions.connectionTelemetry.none { it })
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
    @Test
    fun summaryDemandPausesWhenCoveredAndServiceRefreshDoesNotRequestOtherData() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.ServiceStatus, DashboardWidget.ConnectionStats)).copy(phase = dev.aerove.lite.VpnPhase.Running))
        scroll(DashboardWidget.ServiceStatus)
        compose.waitUntil { actions.dashboardTelemetry.lastOrNull() == true }
        compose.onNodeWithContentDescription(context.getString(R.string.diag_service_check_all)).performClick()
        assertEquals(listOf("refreshServiceChecks"), actions.events)
        compose.runOnUiThread { foreground.value = false }
        compose.waitUntil { actions.dashboardTelemetry.lastOrNull() == false }
        val declarations = actions.dashboardTelemetry.size
        compose.runOnUiThread { state.value = state.value.copy(uptimeMillis = 42) }
        compose.waitForIdle()
        assertEquals(declarations, actions.dashboardTelemetry.size)
        compose.runOnUiThread { foreground.value = true }
        compose.waitUntil { actions.dashboardTelemetry.lastOrNull() == true }
        assertTrue(actions.connectionTelemetry.none { it })
    }

    @Test
    fun statisticsOnlyHasThreeRealRoutesWithoutServiceDisclosureOrDemand() {
        launch(fixture(listOf(DashboardWidget.ConnectionStats)))
        compose.onNodeWithTag("diagnostics-disclosure").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.lite_tab_connections)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_requests)).performClick()
        compose.onNodeWithText(context.getString(R.string.history_dns)).performClick()
        assertEquals(1, connectionsOpened)
        assertEquals(1, requestsOpened)
        assertEquals(1, dnsOpened)
        assertTrue(actions.serviceTelemetry.all { it.isEmpty() })
    }

    @Test
    fun serviceCellsGrowForTwoLinesAtLargeText() {
        val initial = fixture(listOf(DashboardWidget.ServiceStatus))
        launch(initial.copy(diagnostics = initial.diagnostics.copy(services = listOf(
            ServiceCheckView("netflix", "available", checkedAt = 1),
            ServiceCheckView("youtube", "restricted", checkedAt = 1)))), fontScale = 2f, maxWidth = 328.dp)
        listOf("netflix", "youtube").forEach { id ->
            val cell = compose.onNodeWithTag("dashboard-service-$id").fetchSemanticsNode().boundsInRoot
            val lines = listOf("label", "status").map { part ->
                val node = compose.onNodeWithTag("dashboard-service-$id-$part", useUnmergedTree = true)
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                node.fetchSemanticsNode().boundsInRoot to layouts.single()
            }
            val measurement = "service=$id cell=$cell density=$density fontScale=$effectiveFontScale " +
                "lines=${lines.map { (bounds, result) -> "bounds=$bounds size=${result.size} baseline=${result.firstBaseline} overflow=${result.didOverflowHeight}" }}"
            assertTrue(measurement, cell.height >= (36f + 32f) * density - 1f)
            assertTrue(measurement, cell.height >= 50f * effectiveFontScale.coerceAtLeast(1f) * density - 1f)
            lines.forEach { (bounds, result) ->
                assertTrue(measurement, !result.didOverflowHeight)
                assertTrue(measurement, bounds.top >= cell.top - 1f && bounds.bottom <= cell.bottom + 1f)
            }
            assertTrue(measurement, lines[0].first.bottom <= lines[1].first.top + 1f)
        }
        listOf("Netflix", "YouTube").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(!layouts.single().didOverflowHeight)
        }
    }

    private fun assertCenteredAddressAndCopyTarget() {
        compose.onNodeWithTag("dashboard-vpn-footer").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.lite_enabled)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.lite_disabled)).assertDoesNotExist()
        val address = statusRect("dashboard-lan-footer")
        val copy = compose.onNodeWithTag("dashboard-lan-copy").fetchSemanticsNode()
        val vpn = statusRect("dashboard-vpn")
        val lan = statusRect("dashboard-intranet")
        val measured = "address=$address copy=${copy.boundsInRoot} vpn=$vpn lan=$lan density=$density"
        assertEquals(measured, copy.boundsInRoot.center.x, address.center.x, 0.5f)
        assertEquals(measured, copy.boundsInRoot.center.y, address.center.y, 0.5f)
        assertEquals(measured, vpn.left, copy.boundsInRoot.left, 0.5f)
        assertEquals(measured, lan.right, copy.boundsInRoot.right, 0.5f)
        assertTrue(measured, !copy.boundsInRoot.overlaps(vpn) && !copy.boundsInRoot.overlaps(lan))
        assertTrue(measured, copy.touchBoundsInRoot.width >= 48f * density - 1f)
        assertTrue(measured, copy.touchBoundsInRoot.height >= 48f * density - 1f)
        if (effectiveFontScale == 1f) assertEquals(measured, 220f * density, statusRect("dashboard-network-controls").height, 1f)
        assertEquals(context.getString(R.string.lite_copy), copy.config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("dashboard-lan-copy").performTouchInput { click(Offset(2f, height / 2f)) }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val expected = dashboardLanAddress(state.value.localAddress, state.value.settings.network.ports.mixed,
            state.value.settings.network.allowLan)
        compose.runOnIdle { assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun networkControlsCenterFullWidthCopyAddress() {
        launch(fixture(listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp)), maxWidth = 412.dp)
        assertCenteredAddressAndCopyTarget()
    }

    @Test
    fun narrowLargeTextIpv6AddressRetainsCenterAndIndependentCopy() {
        val initial = fixture(listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp))
        launch(initial.copy(localAddress = "2001:db8:1234:5678:9abc:def0:1234:5678",
            settings = initial.settings.copy(network = initial.settings.network.copy(allowLan = true))),
            fontScale = 2f, maxWidth = 328.dp)
        assertCenteredAddressAndCopyTarget()
        listOf(R.string.lite_mode_rule, R.string.lite_mode_global, R.string.lite_mode_direct).forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(label)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(!layouts.single().didOverflowHeight)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_vpn)).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.network_allow_lan)).assertIsOn().performClick().assertIsOff()
        assertTrue(!state.value.settings.network.vpnEnabled && !state.value.settings.network.allowLan)
    }

    @Test
    fun lanWholeRowCopiesCompleteAddressesAndDisablesMissingValues() {
        launch(fixture(listOf(DashboardWidget.VpnButton, DashboardWidget.IntranetIp)), maxWidth = 328.dp)
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        data class CopyCase(val ip: String?, val enabled: Boolean, val port: Int, val expected: String?)
        for ((ip, enabled, port, expected) in listOf(
            CopyCase("192.0.2.8", true, 7890, "192.0.2.8:7890"),
            CopyCase("192.0.2.8", false, 7890, "192.0.2.8"),
            CopyCase("2001:db8::8", true, 7890, "[2001:db8::8]:7890"),
            CopyCase("[2001:db8::8]", true, 7890, "[2001:db8::8]:7890"),
            CopyCase("2001:db8::8", false, 7890, "2001:db8::8"),
            CopyCase(null, true, 7890, null), CopyCase(" ", false, 7890, null),
            CopyCase("192.0.2.8", true, 0, null), CopyCase("192.0.2.8", true, 65536, null))) {
            compose.runOnUiThread {
                val network = state.value.settings.network
                state.value = state.value.copy(localAddress = ip, settings = state.value.settings.copy(
                    network = network.copy(allowLan = enabled, ports = network.ports.copy(mixed = port))))
            }
            val row = compose.onNodeWithTag("dashboard-lan-copy")
            if (expected == null) row.assertIsNotEnabled() else {
                val before = state.value.settings.network
                row.performTouchInput { click(Offset(width - 2f, height / 2f)) }
                compose.runOnIdle { assertEquals(expected, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
                assertEquals(before, state.value.settings.network)
            }
        }
    }

    @Test
    fun modePillsHaveEqualFullShapesAndCenteredActualIconTextGroups() {
        launch(fixture(listOf(DashboardWidget.OutboundMode)), maxWidth = 412.dp, deviceDensity = 2.625f)
        for (material in MaterialTier.entries) for (aurora in listOf(false, true))
            for (colorMode in listOf(dev.aerove.lite.ColorMode.Light, dev.aerove.lite.ColorMode.Dark)) {
                compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(
                    material = material, aurora = aurora, colorMode = colorMode, mode = ProxyMode.Rule)) }
                val pills = ProxyMode.entries.map { mode ->
                    val pill = statusRect("dashboard-mode-${mode.name}")
                    val icon = statusRect("dashboard-mode-${mode.name}-icon")
                    val label = statusRect("dashboard-mode-${mode.name}-label")
                    val content = Rect(minOf(icon.left, label.left), minOf(icon.top, label.top),
                        maxOf(icon.right, label.right), maxOf(icon.bottom, label.bottom))
                    val measured = "material=$material aurora=$aurora color=$colorMode mode=$mode pill=$pill icon=$icon label=$label"
                    assertEquals(measured, pill.center.x, icon.center.x, 0.5f)
                    assertEquals(measured, pill.center.x, label.center.x, 0.5f)
                    assertEquals(measured, pill.center.y, content.center.y, 0.5f)
                    assertTrue(measured, content.top >= pill.top && content.bottom <= pill.bottom)
                    assertTrue(measured, pill.height >= 48f * density - 1f)
                    val shape = DashboardModePillShape.geometry(pill.size, androidx.compose.ui.unit.LayoutDirection.Ltr, Density(density))
                    assertEquals(measured, 1, shape.radii.distinct().size)
                    assertTrue(measured, shape.radii.first() >= minOf(pill.width, pill.height) / density / 2 - 0.01)
                    pill
                }
                assertTrue("pill widths=$pills", pills.maxOf { it.width } - pills.minOf { it.width } <= 1f)
                assertTrue("pill heights=$pills", pills.maxOf { it.height } - pills.minOf { it.height } <= 1f)
                compose.onNodeWithTag("dashboard-mode-Global").performClick()
                assertEquals(ProxyMode.Global, state.value.settings.mode)
            }
    }

    @Test
    fun startGlyphCanvasKeepsItsOwnSizeAndRealPathInsideOriginalButton() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound))
        launch(initial.copy(phase = dev.aerove.lite.VpnPhase.Running), maxWidth = 412.dp, deviceDensity = 2.625f)
        for ((phase, suspended) in listOf(dev.aerove.lite.VpnPhase.Running to false,
            dev.aerove.lite.VpnPhase.Stopped to false, dev.aerove.lite.VpnPhase.Starting to false,
            dev.aerove.lite.VpnPhase.Stopping to false, dev.aerove.lite.VpnPhase.Running to true)) {
            compose.runOnUiThread { state.value = state.value.copy(phase = phase, suspended = suspended) }
            val button = statusRect("dashboard-status-button")
            val canvas = statusRect("dashboard-status-glyph")
            val measured = "phase=$phase suspended=$suspended button=$button canvas=$canvas density=$density"
            assertEquals(measured, 88f * density, button.width, 1f)
            assertEquals(measured, 88f * density, button.height, 1f)
            assertEquals(measured, 88f * 0.34f * density, canvas.width, 1f)
            assertEquals(measured, 88f * 0.34f * density, canvas.height, 1f)
            assertEquals(measured, button.center.x, canvas.center.x, 0.5f)
            assertEquals(measured, button.center.y, canvas.center.y, 0.5f)
            if (!suspended) {
                val progress = if (phase == dev.aerove.lite.VpnPhase.Running || phase == dev.aerove.lite.VpnPhase.Starting) 1f else 0f
                val paths = FullGlyphs.playPause(progress).shapes.map { it.path.getBounds().inflate(0.9f) }
                val ink = Rect(paths.minOf { it.left }, paths.minOf { it.top }, paths.maxOf { it.right }, paths.maxOf { it.bottom })
                val inkHeight = ink.height / 24f * canvas.height
                assertTrue("$measured normalizedPath=$ink inkHeight=$inkHeight", ink.top >= 0 && ink.bottom <= 24 && inkHeight < button.height / 4)
                if (progress == 1f) assertEquals(measured, 14f / 24f * canvas.height, inkHeight, 0.1f)
            }
        }

    }

    private fun statusRect(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun localized(language: String) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })

    private class ScreenReader(val provider: AccessibilityNodeProvider, val infos: Map<Int, AccessibilityNodeInfo>,
        nodes: Map<Int, ReaderNode>, chain: List<Int>, inside: Set<Int>, val tags: Map<Int, String?>, val bounds: Map<Int, Rect>) {
        private val focus = TalkBackFocus(nodes)
        val stops = chain.filter { it in inside && focus.stops(it) }
        val speech = nodes.keys.associateWith(focus::speech)

        fun reachableIn(area: Rect) = infos.keys.filter { bounds.getValue(it).within(area) && (focus.reachable(it) || focus.stops(it)) }.sorted()
    }

    private fun readerNode(id: Int, info: AccessibilityNodeInfo, parent: Int?, children: List<Int>, window: android.graphics.Rect): ReaderNode {
        fun supports(vararg actions: AccessibilityAction) = actions.any(info::offers)
        val scrolls = supports(AccessibilityAction.ACTION_SCROLL_FORWARD, AccessibilityAction.ACTION_SCROLL_BACKWARD,
            AccessibilityAction.ACTION_SCROLL_UP, AccessibilityAction.ACTION_SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT, AccessibilityAction.ACTION_SCROLL_RIGHT)
        val lists = setOf("android.widget.ListView", "android.widget.GridView", "android.widget.ScrollView", "android.widget.HorizontalScrollView")
        val className = info.className?.toString()
        val roleDescription = AccessibilityNodeInfoCompat.wrap(info).roleDescription?.toString().orEmpty()
        return ReaderNode(id, parent, children, info.childCount, info.isVisibleToUser, info.isScreenReaderFocusable,
            actionable = info.isClickable || info.isLongClickable || info.isFocusable || supports(AccessibilityAction.ACTION_FOCUS),
            pressable = info.isClickable || info.isLongClickable, scrolls = scrolls,
            listRole = info.collectionInfo != null || className in lists,
            text = info.collectionInfo == null && listOf(info.text, info.contentDescription, info.hintText).any { !it.isNullOrEmpty() },
            state = !info.stateDescription.isNullOrEmpty() || info.isCheckable || info.rangeInfo.let { it != null && it.max > it.min },
            windowSized = android.graphics.Rect().also(info::getBoundsInScreen) == window,
            description = info.contentDescription?.toString().orEmpty(), words = info.text?.toString().orEmpty(),
            stateDescription = info.stateDescription?.toString().orEmpty(), role = roleDescription.ifEmpty { ROLES[className].orEmpty() },
            enabled = info.isEnabled)
    }

    // Compose computes the traversal-before links TalkBack follows only while an accessibility service is on, and
    // UiAutomation does not count as one, so the root forces them for the one UI-thread pass that reads the provider.
    // A merging node's description and role move to fake children (ui-android 1.12.0 SemanticsNode.emitFakeNodes)
    // that the test tree omits; the provider serves them at the node id plus the fake offsets.
    private fun <T> readScreen(rootTag: String, read: (ScreenReader) -> T): T {
        compose.waitForIdle()
        val root = compose.onNodeWithTag(rootTag, useUnmergedTree = true).fetchSemanticsNode()
        val owner = requireNotNull(root.root as? ViewRootForTest) { "no Android root for $rootTag" }
        val every = SemanticsMatcher("any node") { true }
        val unmerged = compose.onAllNodes(every, useUnmergedTree = true).fetchSemanticsNodes().associateBy { it.id }
        return read(compose.runOnUiThread {
            owner.forceAccessibilityForTesting(true)
            try {
                val provider = requireNotNull(owner.view.accessibilityNodeProvider)
                val infos = unmerged.keys.mapNotNull { id -> provider.createAccessibilityNodeInfo(id)?.let { id to it } }.toMap()
                val fakes = infos.keys.associateWith { id -> listOf(id + DESCRIPTION_FAKE, id + ROLE_FAKE).associateWith(provider::createAccessibilityNodeInfo) }
                infos.forEach { (id, info) ->
                    val own = unmerged.getValue(id).config
                    val description = own.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                    if (own.isMergingSemanticsOfDescendants && description != null && info.contentDescription.isNullOrEmpty())
                        assertEquals("fake description of $id", description, fakes.getValue(id)[id + DESCRIPTION_FAKE]?.contentDescription?.toString())
                }
                val host = owner.view.rootView
                val corner = IntArray(2).also(host::getLocationOnScreen)
                val window = android.graphics.Rect(corner[0], corner[1], corner[0] + host.width, corner[1] + host.height)
                val next = infos.mapNotNull { (id, info) -> info.extras.takeIf { it.containsKey(TRAVERSAL_BEFORE) }?.let { id to it.getInt(TRAVERSAL_BEFORE) } }.toMap()
                val heads = next.keys - next.values.toSet()
                assertEquals("one traversal chain: $next", 1, heads.size)
                val chain = generateSequence(heads.single()) { next[it] }.take(infos.size + 1).toList()
                assertTrue("traversal must not loop: $chain", chain.size <= infos.size)
                val inside = mutableSetOf<Int>()
                fun collect(node: SemanticsNode) { inside += node.id; node.children.forEach { collect(it) } }
                collect(root)
                val nodes = infos.mapValues { (id, info) ->
                    val node = unmerged.getValue(id)
                    val (described, roled) = listOf(id + DESCRIPTION_FAKE, id + ROLE_FAKE).map { fake -> fake.takeIf { fakes.getValue(id)[it] != null } }
                    readerNode(id, info, node.parent?.id?.takeIf(infos::containsKey),
                        listOfNotNull(described) + node.children.map { it.id }.filter(infos::containsKey) + listOfNotNull(roled), window)
                } + fakes.flatMap { (id, served) -> served.mapNotNull { (fake, info) -> info?.let { fake to readerNode(fake, it, id, emptyList(), window) } } }
                inside += fakes.filterKeys(inside::contains).values.flatMap { served -> served.filterValues { it != null }.keys }
                val allInfos = infos + fakes.values.flatMap { served -> served.mapNotNull { (fake, info) -> info?.let { fake to it } } }
                allInfos.filterKeys(inside::contains).values.forEach { info ->
                    assertTrue("TalkBack role of ${info.className} is not modeled: $info", !info.isVisibleToUser ||
                        !AccessibilityNodeInfoCompat.wrap(info).roleDescription.isNullOrEmpty() || info.className?.toString() in ROLES)
                }
                ScreenReader(provider, allInfos, nodes, chain, inside,
                    allInfos.keys.associateWith { unmerged[it]?.config?.getOrNull(SemanticsProperties.TestTag) },
                    allInfos.keys.associateWith { (unmerged[it] ?: unmerged.getValue(nodes.getValue(it).parent!!)).boundsInRoot })
            } finally {
                owner.forceAccessibilityForTesting(false)
            }
        })
    }

    private fun screenReaderStops(rootTag: String) = readScreen(rootTag) { reader -> reader.stops.map { reader.tags[it] to reader.speech.getValue(it) } }

    /** Standard reads each direction as its label and figure merged by a line break, then the total once. */
    private fun trafficSpeech(language: String): List<String> {
        val strings = localized(language)
        // Standard's dashboardTraffic writes the fixture's 1024, 2048, 4096 and 8192 bytes as these.
        return listOf(R.string.lite_upload to ("1 KB" to "4 KB"), R.string.lite_download to ("2 KB" to "8 KB")).flatMap { (label, values) ->
            listOf("${strings.getString(label)}\n${values.first}/s", strings.getString(R.string.lite_traffic_total, values.second))
        }
    }

    private fun button(label: String, enabled: Boolean = true) =
        listOfNotNull(label, TalkBackFocus.BUTTON, TalkBackFocus.DISABLED.takeUnless { enabled }).joinToString(TalkBackFocus.SEPARATOR)

    /** Lite's status-card order: title, state, overlaid traffic, summary, controls, then appended traffic. */
    private fun assertStatusReadingOrder(overlay: Boolean, traffic: Boolean) {
        val heading = listOf("dashboard-status-title", "dashboard-status-chip")
        val figures = listOf("dashboard-upload-speed", "dashboard-upload-total", "dashboard-download-speed", "dashboard-download-total")
        val summary = listOf("ip-quality-summary", "dashboard-status-retry-hint", "dashboard-status-uptime", "dashboard-status-organization",
            "dashboard-status-listener-error", "dashboard-status-outbound-error")
        val controls = listOf("dashboard-status-flag", "dashboard-status-ip", "dashboard-status-button")
        val stops = screenReaderStops("dashboard-status")
        val tags = stops.map { it.first }
        // Like Standard's skeleton, the address is silent until an exit is known or its check failed.
        val address = state.value.outboundIdentity != null || !state.value.outboundError.isNullOrBlank()
        val required = heading + listOf("dashboard-status-uptime", "dashboard-status-organization") +
            controls.filter { address || it != "dashboard-status-ip" } + if (traffic) figures else emptyList()
        assertTrue("missing ${required - tags.toSet()}: $stops", tags.containsAll(required))
        val canonical = heading + (if (traffic && overlay) figures else emptyList()) + summary + controls + if (traffic && !overlay) figures else emptyList()
        assertEquals("$stops", canonical.filter { it in tags }, tags)
    }

    private fun statusForegroundRects(): Map<String, Rect> = buildMap {
        listOf("dashboard-status-heading", "dashboard-status-body", "dashboard-status-ip",
            "dashboard-status-organization", "dashboard-status-uptime").forEach { put(it, statusRect(it)) }
        listOf("ip-quality-summary", "dashboard-status-provisional", "dashboard-status-listener-error",
            "dashboard-status-outbound-error").forEach { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.let { put(tag, it.boundsInRoot) }
        }
        put("flag", statusRect("dashboard-status-flag"))
        put("button", statusRect("dashboard-status-button"))
    }

    private fun trafficTextLayouts(): Map<String, TextLayoutResult> = buildMap {
        for (direction in listOf("upload", "download")) for (line in listOf("label", "value", "unit", "total")) {
            val tag = "dashboard-$direction-$line"
            val results = mutableListOf<TextLayoutResult>()
            compose.onNodeWithTag("$tag-raw", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            put(tag, results.single())
        }
    }

    private fun metricElementRects(): Map<String, Rect> = buildMap {
        val layouts = trafficTextLayouts()
        for (direction in listOf("upload", "download")) for (part in listOf("arrow", "label", "value", "unit", "total")) {
            val tag = "dashboard-$direction-$part"
            val line = statusRect(tag)
            if (part == "arrow") put(tag, line) else {
                val glyph = metricGlyphBounds(layouts.getValue(tag))
                val original = statusRect("$tag-raw")
                put(tag, Rect(minOf(line.left, original.left + glyph.left), line.top,
                    maxOf(line.right, original.left + glyph.right), line.bottom))
            }
        }
    }

    private fun metricGlyphBounds(layout: TextLayoutResult): android.graphics.Rect {
        val input = layout.layoutInput
        val style = input.style
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(input.density) { style.fontSize.toPx() }
            typeface = input.fontFamilyResolver.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
                style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All).value as android.graphics.Typeface
            style.localeList?.firstOrNull()?.let { textLocale = Locale.forLanguageTag(it.toLanguageTag()) }
        }
        return android.graphics.Rect().also { paint.getTextBounds(input.text.text, 0, input.text.length, it) }
    }

    private fun trafficMeasurements(): String {
        val heading = statusRect("dashboard-status-heading")
        val body = statusRect("dashboard-status-body")
        val traffic = statusRect("dashboard-status-traffic")
        val lines = listOf(12f, 14f, 10f).map { it * density * effectiveFontScale }
        val ceiling = lines.sumOf { ceil(it.toDouble()).toInt() }
        val rounded = lines.sum().roundToInt()
        val candidate = Rect(traffic.left, heading.top, traffic.right, heading.top + traffic.height)
        val candidateElements = metricElementRects().mapValues { (_, rect) -> rect.translate(Offset(0f, heading.top - traffic.top)) }
        val collisions = statusForegroundRects().filterKeys { it != "dashboard-status-body" && it != "dashboard-status-heading" }
            .filterValues { occupied -> candidateElements.values.any(occupied::overlaps) }.keys
        val linesMeasured = trafficTextLayouts().mapValues { (_, text) ->
            "raw=${text.size} baseline=${text.firstBaseline} glyph=${metricGlyphBounds(text)} overflow=${text.didOverflowHeight}"
        }
        return "candidateElements=$candidateElements metricLines=$linesMeasured phase=${state.value.phase} identity=${state.value.outboundIdentity != null} loading=${state.value.outboundLoading} candidate=$candidate collisions=$collisions density=$density fontScale=$effectiveFontScale content=${statusRect("dashboard-status-content")} " +
            "heading=$heading body=$body traffic=$traffic bodyY=${body.top - heading.top} " +
            "nominalLineCeiling=$ceiling nominalLineTotalRounded=$rounded nominalRoundingAllowance=${ceiling - rounded} foreground=${statusForegroundRects()} " +
            "widthFits=${heading.width + (8 * density).roundToInt() + traffic.width <= statusRect("dashboard-status-content").width}"
    }

    private fun addTrafficAndAssertOriginalForeground(expectedOverlay: Boolean) {
        val card = statusRect("dashboard-status")
        val before = statusForegroundRects()
        assertStatusReadingOrder(overlay = true, traffic = false)
        compose.runOnUiThread {
            state.value = state.value.copy(settings = state.value.settings.copy(
                dashboardWidgets = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed)))
        }
        compose.waitForIdle()
        val measurements = trafficMeasurements()
        val after = statusForegroundRects()
        val traffic = statusRect("dashboard-status-traffic")
        val content = statusRect("dashboard-status-content")
        val heading = before.getValue("dashboard-status-heading")
        val candidate = Rect(content.right - traffic.width, heading.top, content.right, heading.top + traffic.height)
        val elements = metricElementRects()
        val candidateElements = elements.mapValues { (_, rect) -> rect.translate(Offset(0f, heading.top - traffic.top)) }
        val collision = before.filterKeys { it != "dashboard-status-body" && it != "dashboard-status-heading" }
            .values.any { occupied -> candidateElements.values.any(occupied::overlaps) }
        val measuredFit = heading.width + (8 * density).roundToInt() + traffic.width <= content.width && !collision
        val overlay = expectedOverlay
        println("STATUS_OVERLAY expectedOverlay=$expectedOverlay measuredFit=$measuredFit $measurements")
        trafficTextLayouts().forEach { (tag, text) ->
            val glyph = metricGlyphBounds(text)
            assertTrue("$tag must retain its measured glyphs: $measurements", !text.didOverflowHeight &&
                text.firstBaseline + glyph.top >= 0 && text.firstBaseline + glyph.bottom <= text.size.height)
            val line = elements.getValue(tag)
            val original = statusRect("$tag-raw")
            val inkTop = original.top + text.firstBaseline + glyph.top
            val inkBottom = original.top + text.firstBaseline + glyph.bottom
            assertTrue("$tag actual drawn ink must fit its compact line: inkTop=$inkTop inkBottom=$inkBottom line=$line $measurements",
                inkTop >= line.top && inkBottom <= line.bottom)
        }
        if (overlay) assertEquals(measurements, card, statusRect("dashboard-status"))
        else assertTrue(measurements, statusRect("dashboard-status").height > card.height)
        before.forEach { (key, rect) -> assertEquals("$key $measurements", rect, after.getValue(key)) }
        val upload = statusRect("dashboard-upload")
        val download = statusRect("dashboard-download")
        assertEquals(measurements, content.right, traffic.right)
        if (overlay) {
            assertEquals(measurements, before.getValue("dashboard-status-heading").top, traffic.top)
            assertEquals(measurements, traffic.top, upload.top)
            assertEquals(measurements, traffic.top, download.top)
        } else {
            assertTrue(measurements, traffic.top >= before.getValue("dashboard-status-body").bottom + (12 * density).roundToInt())
            assertTrue("Fallback requires measured overflow: $measurements", !measuredFit)
        }
        // The body's transparent full-width box can contain unused space; its
        // actual foreground elements, including the independent controls, cannot.
        before.filterKeys { it != "dashboard-status-body" }.forEach { (key, rect) ->
            elements.forEach { (metric, bounds) -> assertTrue("$key/$metric $measurements", !rect.overlaps(bounds)) }
        }
        elements.entries.forEachIndexed { index, first ->
            elements.entries.drop(index + 1).forEach { second ->
                assertTrue("${first.key}/${second.key} $measurements", !first.value.overlaps(second.value))
            }
        }
        assertTrue(measurements, !upload.overlaps(download))
        assertStatusReadingOrder(expectedOverlay, traffic = true)
        assertTrue(actions.events.isEmpty())
    }

    private fun assertNoValueReadTwice(stops: List<Pair<String?, String>>) = stops.forEach { (tag, said) ->
        val parts = said.split(TalkBackFocus.SEPARATOR).withIndex()
        assertTrue("$tag reads a value twice: $said", parts.none { (i, part) -> parts.any { (j, other) -> i != j && other.contains(part) } })
    }

    private fun awaitOrganization(described: String) = compose.waitUntil(5_000) {
        compose.onNodeWithTag("dashboard-status-organization").fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription) == listOf(described)
    }

    /** Stops in reading order with what TalkBack says; the Lite-only quality summary and error captions are left out. */
    private fun convergedStatusSpeech(reader: ScreenReader) = reader.stops.map { reader.tags[it] to reader.speech.getValue(it) }
        .filter { it.first !in setOf("ip-quality-summary", "dashboard-status-listener-error", "dashboard-status-outbound-error") }

    private fun assertStatusSpeech(maxWidth: Dp, fontScale: Float, overlay: Boolean) {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed)).copy(phase = VpnPhase.Running)
        launch(initial, fontScale = fontScale, maxWidth = maxWidth, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        awaitOrganization("${strings.getString(R.string.lite_organization)}\nExample ISP")
        assertStatusReadingOrder(overlay, traffic = true)
        val stops = screenReaderStops("dashboard-status")
        val title = strings.getString(R.string.lite_status)
        assertEquals("$stops", listOf(title, strings.getString(R.string.lite_running)), stops.take(2).map { it.second })
        assertEquals("$stops", 1, stops.count { it.second.startsWith(title) })
        val figures = stops.filter { it.first?.let { tag -> tag.startsWith("dashboard-upload") || tag.startsWith("dashboard-download") } == true }
        assertEquals("$stops", trafficSpeech("zh-CN"), figures.map { it.second })
        val detail = strings.getString(R.string.lite_details, strings.getString(R.string.lite_outbound_ip))
        assertEquals("$stops", listOf("${strings.getString(R.string.lite_uptime)}\n00:00:00", "${strings.getString(R.string.lite_organization)}\nExample ISP",
            button("${strings.getString(R.string.lite_outbound_retry)}\nSG"), "198.51.100.8, $detail", button(strings.getString(R.string.lite_stop))),
            listOf("dashboard-status-uptime", "dashboard-status-organization", "dashboard-status-flag", "dashboard-status-ip", "dashboard-status-button")
                .map { tag -> stops.single { it.first == tag }.second })
        assertNoValueReadTwice(stops)
    }

    @Test
    fun overlaidStatusCardReadsTitleOnceStateTrafficSummaryThenControls() = assertStatusSpeech(412.dp, 1f, overlay = true)

    @Test
    fun stackedStatusCardReadsTitleOnceStateSummaryControlsThenTraffic() = assertStatusSpeech(328.dp, 2f, overlay = false)

    private fun assertStartControl(label: String, enabled: Boolean, measured: String) {
        val button = compose.onNodeWithTag("dashboard-status-button", useUnmergedTree = true).fetchSemanticsNode()
        readScreen("dashboard-status") { reader ->
            val info = reader.infos.getValue(button.id)
            val context = "$measured stops=${reader.stops} node=$info"
            assertEquals(context, listOf(button.id), reader.reachableIn(button.boundsInRoot))
            assertEquals(context, listOf(button.id), reader.stops.filter { reader.bounds.getValue(it).within(button.boundsInRoot) })
            assertEquals(context, button(label, enabled), reader.speech.getValue(button.id))
            assertEquals(context, enabled, info.isEnabled)
            assertEquals(context, false, info.isLongClickable)
            assertEquals(context, enabled, info.offers(AccessibilityAction.ACTION_CLICK))
            assertEquals(context, false, info.offers(AccessibilityAction.ACTION_LONG_CLICK))
            assertEquals(context, enabled, info.isFocusable)
            assertEquals(context, enabled, info.offers(AccessibilityAction.ACTION_FOCUS) || info.offers(AccessibilityAction.ACTION_CLEAR_FOCUS))
        }
    }

    @Test
    fun startControlFollowsTheRunIntentAsOneLabeledStop() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        // Standard enables the toggle whenever a profile exists and names the latest intent (current_outbound.dart, setup.dart).
        for ((phase, label) in listOf(VpnPhase.Stopped to R.string.lite_start, VpnPhase.Starting to R.string.lite_stop,
            VpnPhase.Running to R.string.lite_stop, VpnPhase.Stopping to R.string.lite_start, VpnPhase.Failed to R.string.lite_start)) {
            compose.runOnUiThread { state.value = state.value.copy(phase = phase) }
            assertStartControl(strings.getString(label), enabled = true, measured = "phase=$phase")
        }
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Stopped, profiles = emptyList(), selectedProfile = null) }
        assertStartControl(strings.getString(R.string.lite_start), enabled = false, measured = "no profile")
        compose.runOnUiThread { state.value = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running) }
        val tooltip = strings.getString(R.string.lite_stop)
        assertTrue(compose.onAllNodesWithText(tooltip).fetchSemanticsNodes().isEmpty())
        val button = compose.onNodeWithTag("dashboard-status-button", useUnmergedTree = true).fetchSemanticsNode()
        // Unforced, so the click event Compose sends is dropped instead of reaching a disabled AccessibilityManager.
        val provider = requireNotNull((button.root as ViewRootForTest).view.accessibilityNodeProvider)
        compose.runOnUiThread { assertTrue(provider.performAction(button.id, AccessibilityAction.ACTION_CLICK.id, null)) }
        compose.runOnUiThread { assertEquals(listOf("disconnect"), actions.events) }
        compose.onNodeWithTag("dashboard-status-button").performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(tooltip).fetchSemanticsNodes().isNotEmpty() }
        // Touch mode refuses input focus to clickables (Focusability.SystemDefined), so the round trip runs in keyboard mode.
        val requestFocus = requireNotNull(button.config.getOrNull(SemanticsActions.RequestFocus)?.action)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.setInTouchMode(false)
        try {
            compose.waitUntil(5_000) { inputModes?.inputMode == InputMode.Keyboard }
            assertTrue("the focus request moves input focus to the pane", compose.runOnUiThread { requestFocus() })
            compose.waitForIdle()
            assertEquals(true, compose.onNodeWithTag("dashboard-status-button", useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Focused))
        } finally {
            instrumentation.setInTouchMode(true)
            compose.waitUntil(5_000) { inputModes?.inputMode == InputMode.Touch }
        }
    }

    @Test
    fun startThenStopDuringStartingCancelsTheStartFromTheSameControl() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        val toggle = compose.onNodeWithTag("dashboard-status-button")
        toggle.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("connect"), actions.events) }
        // While the start is pending the control reads Stop and its tap reaches disconnect(), which cancels that start.
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Starting) }
        assertStartControl(strings.getString(R.string.lite_stop), enabled = true, measured = "starting")
        toggle.performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("connect", "disconnect"), actions.events) }
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Stopping) }
        assertStartControl(strings.getString(R.string.lite_start), enabled = true, measured = "stopping")
        toggle.performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(listOf("connect", "disconnect", "connect"), actions.events) }
        compose.runOnUiThread { state.value = state.value.copy(phase = VpnPhase.Stopped) }
        assertStartControl(strings.getString(R.string.lite_start), enabled = true, measured = "stopped")
    }

    @Test
    fun checkingExitReadsOnlyTheDisabledRetryFlag() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running, outboundIdentity = null, outboundLoading = true)
        launch(initial, maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        val identity = requireNotNull(fixture(emptyList()).outboundIdentity)
        val retry = strings.getString(R.string.lite_outbound_retry)
        val organization = strings.getString(R.string.lite_organization)
        val detail = strings.getString(R.string.lite_details, strings.getString(R.string.lite_outbound_ip))
        val shared = listOf("dashboard-status-title" to strings.getString(R.string.lite_status),
            "dashboard-status-chip" to strings.getString(R.string.lite_running))
        val uptime = "dashboard-status-uptime" to "${strings.getString(R.string.lite_uptime)}\n00:00:00"
        val toggle = "dashboard-status-button" to button(strings.getString(R.string.lite_stop))
        for ((loading, expected) in listOf(
            true to shared + listOf(uptime, "dashboard-status-organization" to organization, "dashboard-status-flag" to button(retry, enabled = false), toggle),
            false to shared + listOf(uptime, "dashboard-status-organization" to "$organization\nExample ISP",
                "dashboard-status-flag" to button("$retry\n${identity.countryCode}"), "dashboard-status-ip" to "${identity.address}, $detail", toggle))) {
            compose.runOnUiThread { state.value = state.value.copy(outboundLoading = loading, outboundIdentity = identity.takeIf { !loading }) }
            awaitOrganization(expected.first { it.first == "dashboard-status-organization" }.second)
            val flag = compose.onNodeWithTag("dashboard-status-flag", useUnmergedTree = true).fetchSemanticsNode()
            readScreen("dashboard-status") { reader ->
                val read = reader.stops.map { reader.tags[it] to reader.speech.getValue(it) }
                val measured = "loading=$loading read=$read node=${reader.infos.getValue(flag.id)}"
                assertEquals(measured, expected, convergedStatusSpeech(reader))
                assertEquals(measured, !loading, read.any { it.first == "ip-quality-summary" })
                assertEquals(measured, listOf(flag.id), reader.reachableIn(flag.boundsInRoot))
                assertEquals(measured, !loading, reader.infos.getValue(flag.id).isEnabled)
                assertNoValueReadTwice(read)
            }
        }
    }

    private fun assertRecheckedFlag(measured: String) {
        val strings = localized("zh-CN")
        val flag = compose.onNodeWithTag("dashboard-status-flag", useUnmergedTree = true).fetchSemanticsNode()
        readScreen("dashboard-status") { reader ->
            val info = reader.infos.getValue(flag.id)
            val context = "$measured stops=${reader.stops} node=$info"
            assertEquals(context, button("${strings.getString(R.string.lite_outbound_retry)}\nSG", enabled = false), reader.speech.getValue(flag.id))
            assertEquals(context, false, info.isEnabled)
            assertEquals(context, listOf(flag.id), reader.reachableIn(flag.boundsInRoot))
        }
    }

    @Test
    fun recheckingAKnownExitKeepsItsCountryOnTheDisabledRetryFlag() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        awaitOrganization("${strings.getString(R.string.lite_organization)}\nExample ISP")
        // OutboundProbe.start keeps the known identity while a retry runs, as Standard's refresh keeps its value.
        compose.runOnUiThread { state.value = state.value.copy(outboundLoading = true) }
        assertRecheckedFlag("rechecking")
        val detail = strings.getString(R.string.lite_details, strings.getString(R.string.lite_outbound_ip))
        assertTrue(screenReaderStops("dashboard-status").contains("dashboard-status-ip" to "198.51.100.8, $detail"))
    }

    @Test
    fun provisionalLastExitReadsItsAddressWhileTheFirstCheckRuns() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        val organization = "${strings.getString(R.string.lite_organization)}\nExample ISP"
        awaitOrganization(organization)
        // Standard reads its provisional last exit as the address while the first probe runs (outbound_ip.dart).
        compose.runOnUiThread { state.value = state.value.copy(outboundIdentity = null, outboundLoading = true) }
        compose.onNodeWithTag("dashboard-status-provisional", useUnmergedTree = true).assertExists()
        assertRecheckedFlag("provisional")
        val detail = strings.getString(R.string.lite_details, strings.getString(R.string.lite_outbound_ip))
        val stops = screenReaderStops("dashboard-status")
        assertTrue("$stops", stops.contains("dashboard-status-ip" to "198.51.100.8, $detail"))
        assertTrue("$stops", stops.contains("dashboard-status-organization" to organization))
        assertTrue("$stops", stops.none { it.first == "dashboard-status-provisional" })
    }

    @Test
    fun startingRunReadsTheRunTimeOfTheLatestIntent() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Starting, uptimeMillis = 3_000), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val label = localized("zh-CN").getString(R.string.lite_uptime)
        compose.onNodeWithTag("dashboard-status-uptime").assertTextEquals("$label 00:00:03")
        assertTrue(screenReaderStops("dashboard-status").contains("dashboard-status-uptime" to "$label\n00:00:03"))
    }

    @Test
    fun loadingQualityOfAKnownExitKeepsItsCountryOnTheDisabledRetryFlag() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running), maxWidth = 412.dp, deviceDensity = 2.625f,
            language = "zh-CN", lookup = { awaitCancellation() })
        val strings = localized("zh-CN")
        awaitOrganization(strings.getString(R.string.lite_organization))
        assertRecheckedFlag("quality loading")
        assertTrue(screenReaderStops("dashboard-status").none { it.first == "ip-quality-summary" })
    }

    @Test
    fun organizationWithoutANameReadsItsAsnLikeStandard() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running), maxWidth = 412.dp, deviceDensity = 2.625f,
            language = "zh-CN", lookup = { IpQuality(it, IpQualitySource.IdentMe, IpType.Residential, asn = 64500) })
        awaitOrganization("${localized("zh-CN").getString(R.string.lite_organization)}\nAS64500")
        compose.onNodeWithTag("dashboard-status-organization").assertTextEquals("AS64500")
    }

    @Test
    fun suspendedRunShowsThePlaceholderRunTimeAndTheSuspendedToggle() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running, suspended = true, uptimeMillis = 61_000),
            maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        val label = strings.getString(R.string.lite_uptime)
        compose.onNodeWithTag("dashboard-status-uptime").assertTextEquals("$label —")
        val stops = screenReaderStops("dashboard-status")
        assertTrue("$stops", stops.contains("dashboard-status-uptime" to "$label\n—"))
        assertTrue("$stops", stops.contains("dashboard-status-button" to button(strings.getString(R.string.integration_suspended))))
    }

    @Test
    fun failedExitReadsTheCheckFailureAndTheRetryHintLikeStandard() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = VpnPhase.Running, outboundIdentity = null,
            outboundError = "Fixture: outbound probe timed out"), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        readScreen("dashboard-status") { reader ->
            assertEquals("${reader.stops}", listOf("dashboard-status-title" to strings.getString(R.string.lite_status),
                "dashboard-status-chip" to strings.getString(R.string.lite_running),
                "dashboard-status-retry-hint" to strings.getString(R.string.lite_tap_icon_to_retry),
                "dashboard-status-uptime" to "${strings.getString(R.string.lite_uptime)}\n00:00:00",
                "dashboard-status-organization" to "${strings.getString(R.string.lite_organization)}\n—",
                "dashboard-status-flag" to button(strings.getString(R.string.lite_outbound_retry)),
                "dashboard-status-ip" to strings.getString(R.string.diag_service_failed),
                "dashboard-status-button" to button(strings.getString(R.string.lite_stop))), convergedStatusSpeech(reader))
        }
    }

    @Test
    fun stoppedCardWithoutAnExitKeepsTheAddressAndOrganizationValueSilent() {
        launch(fixture(listOf(DashboardWidget.CurrentOutbound)).copy(outboundIdentity = null), maxWidth = 412.dp, deviceDensity = 2.625f, language = "zh-CN")
        val strings = localized("zh-CN")
        val stops = screenReaderStops("dashboard-status")
        assertEquals("$stops", listOf("dashboard-status-title" to strings.getString(R.string.lite_status),
            "dashboard-status-chip" to strings.getString(R.string.lite_not_running),
            "dashboard-status-uptime" to "${strings.getString(R.string.lite_uptime)}\n—",
            "dashboard-status-organization" to strings.getString(R.string.lite_organization),
            "dashboard-status-button" to button(strings.getString(R.string.lite_start))), stops.filter { it.first != "dashboard-status-flag" })
        // The stopped flag's state stays open: Standard still probes directly while stopped and Lite has no such probe yet.
        assertTrue("$stops", stops.single { it.first == "dashboard-status-flag" }.second.startsWith(button(strings.getString(R.string.lite_outbound_retry))))
    }

    @Test
    fun separateSpeedCardReadsTitleOnceThenTwoStopsPerDirection() {
        launch(fixture(listOf(DashboardWidget.NetworkSpeed)), language = "zh-CN")
        val stops = screenReaderStops("dashboard-speed")
        assertEquals("$stops", listOf(null, "dashboard-speed-upload-speed", "dashboard-speed-upload-total",
            "dashboard-speed-download-speed", "dashboard-speed-download-total"), stops.map { it.first })
        assertEquals("$stops", listOf(localized("zh-CN").getString(R.string.lite_network_speed)) + trafficSpeech("zh-CN"),
            stops.map { it.second })
    }

    @Test
    fun compactTrafficOverlayPreservesOriginalStatusForegroundBounds() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, maxWidth = 412.dp)
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityEnglishMatrixFixtureKeepsTrafficAtTopRight() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(
            phase = dev.aerove.lite.VpnPhase.Running, traffic = Traffic(),
            trafficHistory = List(30) { Traffic() }, trafficHistoryRevision = 30)
        launch(initial, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "en")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityRunningWithoutIdentityUsesOnlyMeasuredFreeSpace() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(
            phase = dev.aerove.lite.VpnPhase.Running, outboundIdentity = null, traffic = Traffic())
        launch(initial, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "en")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityLoadingIdentityUsesOnlyMeasuredFreeSpace() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(
            phase = dev.aerove.lite.VpnPhase.Running, outboundIdentity = null, outboundLoading = true, traffic = Traffic())
        launch(initial, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "en")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityStoppedStatusUsesOnlyMeasuredFreeSpace() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(outboundIdentity = null, traffic = Traffic())
        launch(initial, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "en")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityChineseStatusKeepsTrafficAtTopRight() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "zh-CN")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityEnglishAtThirteenTenthsKeepsOriginalGeometryAndGlyphs() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, fontScale = 1.3f, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "en")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun fractionalDensityChineseAtThirteenTenthsKeepsOriginalGeometryAndGlyphs() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, fontScale = 1.3f, maxWidth = (1080f / 2.625f).dp, deviceDensity = 2.625f, language = "zh-CN")
        addTrafficAndAssertOriginalForeground(expectedOverlay = true)
    }

    @Test
    fun narrowLargeChineseTextAppendsOnlyForMeasuredCollision() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, fontScale = 2f, maxWidth = 328.dp, deviceDensity = 2.625f, language = "zh-CN")
        addTrafficAndAssertOriginalForeground(expectedOverlay = false)
    }

    @Test
    fun narrowLargeTextKeepsStatusAndSpeedForegroundsSeparate() {
        val initial = fixture(listOf(DashboardWidget.CurrentOutbound)).copy(phase = dev.aerove.lite.VpnPhase.Running)
        launch(initial, fontScale = 2f, maxWidth = 328.dp, deviceDensity = 2.625f)
        addTrafficAndAssertOriginalForeground(expectedOverlay = false)
        compose.onNodeWithText(context.getString(R.string.lite_status)).assertIsDisplayed()
        // The figures speak through one cleared description, so their visible labels live in the unmerged tree.
        compose.onNodeWithText(context.getString(R.string.lite_upload), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lite_download), useUnmergedTree = true).assertIsDisplayed()
    }

}

private fun AccessibilityNodeInfo.offers(action: AccessibilityAction) = actionList.any { it.id == action.id }

private fun Rect.within(area: Rect) = left >= area.left - 0.5f && top >= area.top - 0.5f &&
    right <= area.right + 0.5f && bottom <= area.bottom + 0.5f
