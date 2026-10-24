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
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
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

    private fun fixture(widgets: List<DashboardWidget>) = LiteState(
        settings = LiteSettings(material = MaterialTier.Solid, aurora = false, dashboardWidgets = widgets),
        profiles = listOf(ProfileSummary("ui-fixture", "Selected profile fixture", null, 0)), selectedProfile = "ui-fixture",
        outboundIdentity = OutboundIdentity("198.51.100.8", "SG", "Example ISP", "Residential"),
        localAddress = "192.0.2.8", traffic = Traffic(1024, 2048, 4096, 8192),
        groups = listOf(ProxyGroup("Group fixture", "Selector", "Node fixture", listOf(ProxyNode("Node fixture", "Direct")))),
        connections = List(2) { ConnectionInfo("fixture-$it", "example.invalid", "tcp", "MATCH", emptyList(), 0, 0) },
    )

    private fun launch(initial: LiteState, fontScale: Float = 1f, maxWidth: Dp = 592.dp,
        deviceDensity: Float? = null, language: String? = null) {
        state.value = initial
        compose.replaceAppContent {
            val configuration = Configuration(context.resources.configuration).apply {
                if (language != null) setLocale(Locale.forLanguageTag(language))
            }
            val localizedContext = context.createConfigurationContext(configuration)
            IpQualityFixture(state.value) {
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
        compose.onNodeWithContentDescription(context.getString(R.string.lite_traffic_total, formatBytes(4096))).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.lite_traffic_total, formatBytes(8192))).assertIsDisplayed()
        compose.onNodeWithTag("dashboard-upload-total-raw", useUnmergedTree = true).assertTextEquals(formatBytes(4096)).assertIsDisplayed()
        compose.onNodeWithTag("dashboard-download-total-raw", useUnmergedTree = true).assertTextEquals(formatBytes(8192)).assertIsDisplayed()
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
            assertTrue(cell.height >= (36f + 32f) * density - 1f)
        }
        listOf("Netflix", "YouTube").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(!layouts.single().didOverflowHeight)
        }
    }

    private fun footerBaseline(tag: String): Float {
        val node = compose.onNodeWithTag(tag)
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return node.fetchSemanticsNode().boundsInRoot.top + layouts.single().firstBaseline
    }

    private fun assertFooterBaselineAndCopyTarget() {
        assertEquals(footerBaseline("dashboard-vpn-footer"), footerBaseline("dashboard-lan-footer"), 0.5f)
        val address = compose.onNodeWithTag("dashboard-lan-footer").fetchSemanticsNode().boundsInRoot
        val copy = compose.onNodeWithTag("dashboard-lan-copy").fetchSemanticsNode()
        assertTrue(!address.overlaps(copy.boundsInRoot))
        assertTrue(copy.touchBoundsInRoot.width >= 48f * density - 1f)
        assertTrue(copy.touchBoundsInRoot.height >= 48f * density - 1f)
        compose.onNodeWithTag("dashboard-lan-copy").performClick()
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun networkControlFootersShareActualTextBaseline() {
        launch(fixture(listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp)), maxWidth = 412.dp)
        assertFooterBaselineAndCopyTarget()
    }

    @Test
    fun narrowLargeTextIpv6FooterRetainsBaselineAndIndependentCopy() {
        val initial = fixture(listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp))
        launch(initial.copy(localAddress = "2001:db8:1234:5678:9abc:def0:1234:5678",
            settings = initial.settings.copy(network = initial.settings.network.copy(allowLan = true))),
            fontScale = 2f, maxWidth = 328.dp)
        assertFooterBaselineAndCopyTarget()
        listOf(R.string.lite_mode_rule, R.string.lite_mode_global, R.string.lite_mode_direct).forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(context.getString(label)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(!layouts.single().didOverflowHeight)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.lite_vpn)).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithContentDescription(context.getString(R.string.network_allow_lan)).assertIsOn().performClick().assertIsOff()
        assertTrue(!state.value.settings.network.vpnEnabled && !state.value.settings.network.allowLan)
    }

    private fun statusRect(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

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
        assertTrue(actions.events.isEmpty())
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
        compose.onNodeWithText(context.getString(R.string.lite_upload)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lite_download)).assertIsDisplayed()
    }

}
