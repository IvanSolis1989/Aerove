package dev.aerove.lite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import dev.aerove.lite.ui.colorutilities.TonalPalette
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.aerove.lite.LiteActions
import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.R
import dev.aerove.lite.VpnPhase
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.HorizontalDivider
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection

internal val LocalDashboardFusion = staticCompositionLocalOf { true }

@Composable
internal fun OverviewScreen(
    settings: LiteSettings,
    padding: PaddingValues,
    cardContent: @Composable (DashboardWidget) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = maxOf(16.dp, (maxWidth - 560.dp) / 2)
        LazyColumn(
            modifier = Modifier.testTag("dashboard-layout"),
            state = LocalLiteListState.current,
            contentPadding = PaddingValues(start = side, end = side, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(dashboardPresentationWidgets(settings.dashboardWidgets), key = { it.wireName }, contentType = { it }) { widget ->
                Box(Modifier.fillMaxWidth().testTag("dashboard-card-${widget.wireName}")) { cardContent(widget) }
            }
        }
    }
}

@Composable
internal fun DashboardCard(
    widget: DashboardWidget,
    state: LiteState,
    actions: LiteActions,
    openProfiles: () -> Unit,
    openConnections: () -> Unit,
    openProxies: () -> Unit,
    serviceStatusContent: @Composable () -> Unit,
    memoryInfoContent: @Composable () -> Unit,
    openProfileDetails: () -> Unit = openProfiles,
    openRequests: () -> Unit,
    openDns: () -> Unit,
) {
    if (LocalDashboardFusion.current) {
        val shown = state.settings.dashboardWidgets.toSet()
        when (widget) {
            in DashboardStatusMembers -> {
                if (DashboardWidget.CurrentOutbound in shown) OutboundStatusCard(state, actions)
                return
            }
            in DashboardControlMembers -> { NetworkControlsCard(state, actions); return }
            in DashboardOverviewMembers -> {
                DiagnosticsLeaf(state) { leaf -> ServiceStatusPanel(leaf, actions, showServices = DashboardWidget.ServiceStatus in shown,
                    titleResource = R.string.dashboard_network_overview) {
                    if (DashboardWidget.ConnectionStats in shown) DashboardStatistics(state, actions, openConnections, openRequests, openDns)
                } }
                return
            }
            else -> Unit
        }
    }
    when (widget) {
        DashboardWidget.CurrentOutbound -> OutboundStatusCard(state, actions)
        DashboardWidget.NetworkSpeed -> Unit
        DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp -> NetworkControlsCard(state, actions)
        DashboardWidget.ServiceStatus -> serviceStatusContent()
        DashboardWidget.Profiles -> ProfilesCard(state, actions, openProfiles, openProfileDetails)
        DashboardWidget.ProxyGroups -> ProxyGroupsCard(state, openProxies)
        DashboardWidget.ConnectionStats -> ConnectionStatsCard(state, openConnections)
        DashboardWidget.MemoryInfo -> memoryInfoContent()
    }
}

@Composable
internal fun DashboardEditAction(onEdit: () -> Unit) {
    LiteIconButton(stringResource(R.string.lite_dashboard_edit), DashboardEditGlyph, onEdit)
}

private val DashboardEditGlyph = LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(4, 20, 1), vertex(8.75, 18.75, 1), vertex(20.25, 7.25, 1.5), vertex(16.75, 3.75, 1.5), vertex(5.25, 15.25, 1)), closed = true),
    glyphPath("M13.25 7.25L16.75 10.75", role = LiteGlyphRole.Detail),
))

@Composable
private fun ProfilesCard(state: LiteState, actions: LiteActions, openProfiles: () -> Unit, openDetails: () -> Unit) {
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfile }
    if (profile == null) EmptyContent(stringResource(R.string.lite_profile_required), stringResource(R.string.lite_profile_required_detail), FullGlyphs.profiles) {
        LiteButton(stringResource(R.string.lite_import), actions::importFile, Modifier.fillMaxWidth(), FullGlyphs.importFile, enabled = state.busy.isEmpty())
        LiteButton(stringResource(R.string.lite_tab_profiles), openProfiles, Modifier.fillMaxWidth())
    } else DashboardPane(Modifier.clickable(role = Role.Button, onClick = openDetails)) {
        DashboardHeader(FullGlyphs.profiles, stringResource(R.string.lite_tab_profiles))
        Spacer(Modifier.height(12.dp))
        LiteText(profile.name, style = LiteType.Headline, maxLines = 1)
    }
}

@Composable
private fun ProxyGroupsCard(state: LiteState, openProxies: () -> Unit) {
    DashboardPane(if (state.groups.isNotEmpty()) Modifier.clickable(role = Role.Button, onClick = openProxies) else Modifier) {
        DashboardHeader(FullGlyphs.proxies, stringResource(R.string.lite_tab_proxies))
        Spacer(Modifier.height(12.dp))
        if (state.groups.isEmpty()) LiteText(stringResource(if (state.phase == VpnPhase.Running) R.string.lite_no_proxies else R.string.lite_no_proxies_detail),
            style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk)
        else state.groups.forEach { group ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiteText(group.name, Modifier.weight(1f), style = LiteType.Label, maxLines = 1)
                LiteText(group.selected, style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ConnectionStatsCard(state: LiteState, openConnections: () -> Unit) {
    DashboardPane(Modifier.clickable(role = Role.Button, onClick = openConnections)) {
        DashboardHeader(FullGlyphs.connections, stringResource(R.string.lite_tab_connections))
        Spacer(Modifier.height(12.dp))
        ConnectionsLeaf(state) { LiteText(stringResource(R.string.lite_connection_count, it.connections.size), style = LiteType.Title2) }
    }
}

@Composable
private fun DashboardPane(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    content: @Composable ColumnScope.() -> Unit) {
    val visuals = LocalLiteVisuals.current
    LitePane(
        modifier.fillMaxWidth(), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList(),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun DashboardHeader(icon: LiteGlyph, title: String, leading: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiteIcon(icon, color = LocalLiteVisuals.current.secondaryInk, size = 18.dp)
        LiteText(title, style = LiteType.Label, maxLines = 1)
        leading()
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

// Compose inherits alignment lines with each child's placement offset in the same measure pass.
private class StatusBounds {
    var outline: androidx.compose.ui.graphics.Path? = null
    val left = VerticalAlignmentLine(::minOf)
    val right = VerticalAlignmentLine(::maxOf)
    val top = HorizontalAlignmentLine(::minOf)
    val bottom = HorizontalAlignmentLine(::maxOf)

    fun rects(parent: Placeable, lineInk: List<Rect>? = null): List<Rect> {
        val box = rect(parent) ?: return emptyList()
        return lineInk?.map { it.translate(box.topLeft) } ?: listOf(box)
    }

    fun rect(parent: Placeable, x: Int = 0, y: Int = 0): Rect? {
        val start = parent[left]
        if (start == AlignmentLine.Unspecified) return null
        return Rect((x + start).toFloat(), (y + parent[top]).toFloat(), (x + parent[right]).toFloat(), (y + parent[bottom]).toFloat())
    }
}

private enum class StatusForeground {
    Heading, Address, Flag, Quality, Provisional, Uptime, Organization, Button, ListenerError, OutboundError;
    val bounds = StatusBounds()
}

private enum class StatusMetricPart {
    UploadValue, DownloadValue, UploadInk, DownloadInk, TitleInk, FooterLabelInk, FooterValueInk, OrganizationLabelInk, OrganizationValueInk, UploadArrowInk, DownloadArrowInk;
    val bounds = StatusBounds()
}

private fun Modifier.statusBounds(bounds: StatusBounds, shape: androidx.compose.ui.graphics.Shape? = null) = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    bounds.outline = (shape?.createOutline(androidx.compose.ui.geometry.Size(child.width.toFloat(), child.height.toFloat()), layoutDirection, this)
        as? androidx.compose.ui.graphics.Outline.Generic)?.path
    layout(child.width, child.height, mapOf(bounds.left to 0, bounds.top to 0, bounds.right to child.width, bounds.bottom to child.height)) {
        child.place(0, 0)
    }
}

private fun Modifier.statusGlyphBounds(part: StatusMetricPart, glyph: LiteGlyph, fill: Float,
    ink: MutableMap<StatusMetricPart, List<Rect>>) = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    ink[part] = listOfNotNull(glyph.paintedBounds(androidx.compose.ui.geometry.Size(child.width.toFloat(), child.height.toFloat()), fill, layoutDirection))
    layout(child.width, child.height, mapOf(part.bounds.left to 0, part.bounds.top to 0,
        part.bounds.right to child.width, part.bounds.bottom to child.height)) { child.place(0, 0) }
}

private fun Modifier.statusForeground(part: StatusForeground, shape: androidx.compose.ui.graphics.Shape? = null) = statusBounds(part.bounds, shape)

/** The body's controls, which a screen reader takes after everything else the body reports. */
private enum class StatusControl { Flag, Address, Button }

private fun Modifier.statusControl(control: StatusControl) = semantics { traversalIndex = control.ordinal + 1f }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OutboundStatusCard(state: LiteState, actions: LiteActions, showTraffic: Boolean = true) {
    val visuals = LocalLiteVisuals.current
    val running = state.phase == VpnPhase.Running
    val suspended = running && state.suspended
    var visible by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && LocalLitePageActive.current && LocalDiagnosticsForeground.current &&
        LocalLiteOverlay.current.content == null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val time = rememberDashboardTime((running || state.outboundLoading) && !suspended && active)
    val identity = state.outboundIdentity
    val qualityFactory = LocalLiteFeatures.current
    val qualityFeature = if (identity != null || state.outboundLoading) remember(qualityFactory) { qualityFactory().ipQuality } else null
    val qualityState = qualityFeature?.state?.collectAsStateWithLifecycle()?.value
    val intendsRunning = running || state.phase == VpnPhase.Starting
    val enabled = state.profiles.isNotEmpty()
    val failed = identity == null && !state.outboundLoading && !state.outboundError.isNullOrBlank()
    val status = when (state.phase) {
        VpnPhase.Running -> if (suspended) R.string.integration_suspended else R.string.lite_running
        VpnPhase.Starting -> R.string.lite_connecting
        VpnPhase.Stopping -> R.string.lite_disconnecting
        VpnPhase.Failed -> R.string.lite_failed
        VpnPhase.Stopped -> R.string.lite_not_running
    }
    val trafficRowSpan = remember { androidx.compose.runtime.mutableFloatStateOf(20f) }
    val statusInk = remember { mutableMapOf<StatusMetricPart, List<Rect>>() }
    DisposableEffect(actions, active) {
        actions.setStatusTrafficActive(active)
        onDispose { actions.setStatusTrafficActive(false) }
    }
    LitePane(Modifier.fillMaxWidth().testTag("dashboard-status").onGloballyPositioned {
        measured = true
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }, corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList()) {
        val decorations = rememberLayerBackdrop()
        val controlsSource = rememberCombinedBackdrop(LocalLiteBackdrop.current, decorations)
        Box(Modifier.matchParentSize().layerBackdrop(decorations)) {
            StatusTrafficBackground(state, running && !suspended, active, measured, Modifier.matchParentSize())
        }
        CompositionLocalProvider(LocalLiteBackdrop provides controlsSource) {
            Layout(modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp).testTag("dashboard-status-content")
                .semantics { isTraversalGroup = true }, content = {
                Column(Modifier.fillMaxWidth().testTag("dashboard-status-body").semantics { isTraversalGroup = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Top) {
                            FlowRow(Modifier.statusForeground(StatusForeground.Heading).testTag("dashboard-status-heading"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.alignBy(FirstBaseline), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    LiteIcon(FullGlyphs.status, color = visuals.secondaryInk, size = 18.dp)
                                    StatusMetricText(stringResource(R.string.lite_status), "dashboard-status-title", StatusMetricPart.TitleInk, LiteType.Label, visuals.secondaryInk, description = stringResource(R.string.lite_status))
                                }
                                Box(Modifier.alignBy(FirstBaseline)) { StatusChip(stringResource(status), running, suspended) }
                            }
                            Spacer(Modifier.height(12.dp))

                            if (qualityFeature != null) IpQualityStatus(qualityFeature) { quality, open ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CountryBubble(identity?.countryCode ?: quality.countryCode, state.outboundLoading || quality.loading, running, time, actions::refreshOutbound)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val shown = identity?.address ?: quality.ip?.takeIf { state.outboundLoading && quality.provisional }
                                        val detail = stringResource(R.string.lite_details, stringResource(R.string.lite_outbound_ip))
                                        if (shown != null) {
                                            val address = if (quality.hidden) "*****" else shown
                                            // TalkBack joins Standard's address and its unique tooltip with ", "; Compose exposes no tooltip.
                                            // Standard also reads the provisional last exit while its first probe runs (outbound_ip.dart).
                                            LiteText(address, Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip").statusControl(StatusControl.Address)
                                                .clickable(onClick = open).semantics { contentDescription = "$address, $detail" },
                                                style = LiteType.Title2, color = qualityInk(quality.quality?.level))
                                        }
                                        else if (state.outboundLoading) LiteText(stringResource(R.string.lite_outbound_loading), Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip")
                                            .statusControl(StatusControl.Address).semantics { hideFromAccessibility() }, style = LiteType.Footnote, color = visuals.secondaryInk, maxLines = 2)
                                        else MissingExit(failed)
                                        val pending = if (identity == null) state.outboundLoading else quality.loading
                                        Box(Modifier.statusForeground(StatusForeground.Quality)) { IpQualityChips(quality, open, placeholder = quality.quality == null && pending) }
                                        if (quality.provisional) LiteText(stringResource(R.string.quality_provisional), Modifier.statusForeground(StatusForeground.Provisional).testTag("dashboard-status-provisional")
                                            .semantics { hideFromAccessibility() }, style = LiteType.Caption, color = visuals.secondaryInk)
                                    }
                                }
                            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CountryBubble(identity?.countryCode, state.outboundLoading, running, time, actions::refreshOutbound)
                                if (failed) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    MissingExit(true)
                                    LiteText(stringResource(R.string.lite_tap_icon_to_retry), Modifier.statusForeground(StatusForeground.Quality).testTag("dashboard-status-retry-hint"),
                                        style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                                } else MissingExit(false)
                            }
                            val organization = qualityState?.quality?.let { it.organization?.takeIf(String::isNotEmpty) ?: it.asn?.let { asn -> "AS$asn" } }
                            val organizationLabel = stringResource(R.string.lite_organization)
                            val organizationPending = if (identity == null) !failed else qualityState?.loading == true
                            Spacer(Modifier.height(16.dp))
                            StatusCells(state.uptimeMillis, intendsRunning && !suspended, active,
                                organization, organizationLabel, organizationPending, statusInk)
                        }
                        StartBubble(intendsRunning, enabled, stringResource(if (suspended) R.string.integration_suspended else if (intendsRunning) R.string.lite_stop else R.string.lite_start), time,
                            if (intendsRunning) actions::disconnect else actions::connect, suspended)
                    }
                    state.listenerError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.ListenerError).testTag("dashboard-status-listener-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    state.outboundError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.OutboundError).testTag("dashboard-status-outbound-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                }
                if (showTraffic) StatusTrafficReadings(state, active, trafficRowSpan, statusInk,
                    Modifier.testTag("dashboard-status-traffic").semantics { isTraversalGroup = true })
            }) { children, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val body = children[0].measure(loose.copy(minWidth = constraints.maxWidth))
                if (!showTraffic) {
                    return@Layout layout(constraints.maxWidth, body.height) { body.placeRelative(0, 0) }
                }
                val labelInk = StatusMetricPart.FooterLabelInk.bounds.rect(body)
                val valueInk = StatusMetricPart.FooterValueInk.bounds.rect(body)
                trafficRowSpan.floatValue = (valueInk?.center?.y ?: 20.dp.toPx()) - (labelInk?.center?.y ?: 0f)
                val traffic = children[1].measure(loose)
                val gap = 8.dp.roundToPx()
                val bodyY = 0
                val mainHeight = body.height
                val radius = LiteCorner.Dashboard.roundToPx()
                val rightPadding = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr) 16.dp else 20.dp
                val preferredX = dashboardFooterPreferredX(constraints.maxWidth, traffic.width, radius, rightPadding.roundToPx())
                val speedInk = StatusMetricPart.UploadInk.bounds.rect(traffic)
                val anchorY = ((labelInk?.center?.y ?: 0f) -
                    (speedInk?.center?.y ?: traffic.height / 4f)).roundToInt()
                val metricBounds = listOf(StatusMetricPart.UploadArrowInk, StatusMetricPart.UploadInk,
                    StatusMetricPart.DownloadArrowInk, StatusMetricPart.DownloadInk)
                    .flatMap { part -> part.bounds.rects(traffic, statusInk[part]).map { it.translate(0f, anchorY.toFloat()) } }
                val bodyBounds = StatusForeground.entries.filterNot { it == StatusForeground.Uptime || it == StatusForeground.Organization }
                    .mapNotNull { part -> part.bounds.rect(body)?.let { DashboardClearance(it, part.bounds.outline) } } +
                    listOf(StatusMetricPart.FooterLabelInk, StatusMetricPart.FooterValueInk,
                        StatusMetricPart.OrganizationLabelInk, StatusMetricPart.OrganizationValueInk)
                        .flatMap { part -> part.bounds.rects(body, statusInk[part]).map { DashboardClearance(it) } }
                val slot = dashboardFooterOffsetX(preferredX, constraints.maxWidth - traffic.width,
                    metricBounds, bodyBounds)
                val appended = dashboardFooterAppends(slot, fontScale)
                val trafficX = slot ?: preferredX.coerceAtLeast(0)
                val trafficY = if (appended) mainHeight + gap else anchorY
                val height = if (appended) maxOf(mainHeight, trafficY + traffic.height) else mainHeight
                layout(constraints.maxWidth, constraints.constrainHeight(height)) {
                    body.placeRelative(0, bodyY)
                    traffic.place(trafficX, trafficY)
                }
            }
        }
    }
}

@Composable
private fun StatusMetricText(text: String, tag: String, part: StatusMetricPart, style: TextStyle,
    color: Color, modifier: Modifier = Modifier, description: String? = null,
    painted: MutableMap<StatusMetricPart, List<Rect>>? = null) {
    val density = LocalDensity.current
    val family = style.fontFamily ?: LocalLiteFontFamily.current
    val resolvedTypeface by LocalFontFamilyResolver.current.resolve(family, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
    val locales = style.localeList ?: LocaleList.current
    val paint = remember(style.fontSize, resolvedTypeface, locales, density.density, density.fontScale) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { style.fontSize.toPx() }
            this.typeface = resolvedTypeface as android.graphics.Typeface
            textLocales = android.os.LocaleList(*(0 until locales.size).map { Locale.forLanguageTag(locales[it].toLanguageTag()) }.toTypedArray())
        }
    }
    val ink = remember(text, paint) { android.graphics.Rect().also { paint.getTextBounds(text, 0, text.length, it) } }
    val paragraph = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    val leading = with(density) { ceil(style.lineHeight.toPx()).toInt() }
    // Preserve the natural paragraph; its transparent leading does not belong to the compact line.
    Layout(modifier = modifier.testTag(tag).then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description }
        else Modifier.semantics {}), content = {
        LiteText(text, Modifier.testTag("$tag-raw"), style = style, color = color, maxLines = 1,
            onTextLayout = if (painted == null) null else { result -> paragraph[0] = result })
    }, measurePolicy = object : MeasurePolicy {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val glyphs = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity))
        val height = maxOf(leading, ink.height())
        val baseline = (height - ink.height()) / 2 - ink.top
        val originalBaseline = glyphs[FirstBaseline]
        check(originalBaseline != AlignmentLine.Unspecified)
        paragraph[0]?.takeIf { painted != null }?.let { result ->
            painted!![part] = statusWordInk(text, result, paint).map {
                it.translate(-minOf(0, ink.left).toFloat(), (-originalBaseline - ink.top).toFloat())
            }
        }
        return layout(glyphs.width, height, mapOf(FirstBaseline to baseline, LastBaseline to baseline,
            part.bounds.left to minOf(0, ink.left), part.bounds.right to maxOf(glyphs.width, ink.right),
            part.bounds.top to baseline + ink.top, part.bounds.bottom to baseline + ink.bottom)) {
            glyphs.place(0, baseline - originalBaseline)
        }
        }
        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = measurables.single().maxIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = measurables.single().maxIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = maxOf(leading, ink.height())
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = maxOf(leading, ink.height())
    })
}

private val statusWordPattern = Regex("\\S+")

private fun statusWordInk(text: String, paragraph: androidx.compose.ui.text.TextLayoutResult,
    paint: android.graphics.Paint): List<Rect> = buildList {
    for (line in 0 until paragraph.lineCount) {
        val start = paragraph.getLineStart(line)
        val end = paragraph.getLineEnd(line, visibleEnd = true)
        for (word in statusWordPattern.findAll(text.substring(start, end))) {
            val first = start + word.range.first
            val last = start + word.range.last + 1
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (index in first until last) {
                val box = paragraph.getBoundingBox(index)
                left = minOf(left, box.left)
                right = maxOf(right, box.right)
            }
            val ink = android.graphics.Rect().also { paint.getTextBounds(text, first, last, it) }
            val baseline = paragraph.getLineBaseline(line)
            add(Rect(minOf(left, left + ink.left), baseline + ink.top,
                maxOf(right, left + ink.right), baseline + ink.bottom))
        }
    }
}

@Composable
private fun StatusFooterText(text: String, part: StatusMetricPart, modifier: Modifier = Modifier,
    style: TextStyle, color: Color = LocalLiteVisuals.current.ink, ink: MutableMap<StatusMetricPart, List<Rect>>) {
    val density = LocalDensity.current
    val family = style.fontFamily ?: LocalLiteFontFamily.current
    val resolved by LocalFontFamilyResolver.current.resolve(family, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
    val locales = style.localeList ?: LocaleList.current
    val paint = remember(style.fontSize, resolved, locales, density.density, density.fontScale) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { style.fontSize.toPx() }
            typeface = resolved as android.graphics.Typeface
            textLocales = android.os.LocaleList(*(0 until locales.size).map { Locale.forLanguageTag(locales[it].toLanguageTag()) }.toTypedArray())
        }
    }
    val result = remember { arrayOfNulls<androidx.compose.ui.text.TextLayoutResult>(1) }
    val tag = if (part == StatusMetricPart.OrganizationLabelInk) "dashboard-status-organization-label" else "dashboard-status-organization-value"
    Layout(modifier = modifier.testTag(tag), content = { LiteText(text, Modifier.testTag("$tag-raw"), style = style, color = color, onTextLayout = { result[0] = it }) },
        measurePolicy = object : MeasurePolicy {
        override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
            val child = measurables.single().measure(constraints)
            val paragraph = checkNotNull(result[0])
            ink[part] = statusWordInk(text, paragraph, paint)
            return layout(child.width, child.height, mapOf(part.bounds.left to 0, part.bounds.top to 0,
                part.bounds.right to child.width, part.bounds.bottom to child.height)) { child.place(0, 0) }
        }
        override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = measurables.single().minIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = measurables.single().maxIntrinsicWidth(height)
        override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = measurables.single().minIntrinsicHeight(width)
        override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = measurables.single().maxIntrinsicHeight(width)
    })
}

@Composable
private fun StatusUptime(millis: Long, running: Boolean, active: Boolean) {
    val label = stringResource(R.string.lite_uptime)
    val elapsed = displayValue(LocalLiteDisplayStreams.current?.uptimeMillis, millis, active).value
    val uptime = if (running) formatUptime(elapsed) else "—"
    Column(Modifier.graphicsLayer().statusForeground(StatusForeground.Uptime).testTag("dashboard-status-uptime")
        .clearAndSetSemantics { contentDescription = "$label\n$uptime" }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatusMetricText(label, "dashboard-status-uptime-label", StatusMetricPart.FooterLabelInk,
            LiteType.Caption, LocalLiteVisuals.current.secondaryInk)
        StatusMetricText(uptime, "dashboard-status-uptime-value", StatusMetricPart.FooterValueInk,
            LiteType.Label.copy(fontFeatureSettings = "tnum"), LocalLiteVisuals.current.ink)
    }
}

@Composable
private fun StatusCells(millis: Long, running: Boolean, active: Boolean, organization: String?,
    organizationLabel: String, pending: Boolean, ink: MutableMap<StatusMetricPart, List<Rect>>) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    Layout(content = {
        StatusUptime(millis, running, active)
        Column(Modifier.statusForeground(StatusForeground.Organization).testTag("dashboard-status-organization")
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(organizationLabel, organization ?: "—".takeUnless { pending }).joinToString("\n")
            }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            StatusFooterText(organizationLabel, StatusMetricPart.OrganizationLabelInk, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, ink = ink)
            StatusFooterText(organization ?: "—", StatusMetricPart.OrganizationValueInk, Modifier.testTag("dashboard-status-organization-value"), style = LiteType.Label, ink = ink)
        }
    }) { children, constraints ->
        val gap = 24.dp.roundToPx()
        val width = constraints.maxWidth
        val firstWidth = children[0].maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity)
        val beside = !stacked && firstWidth + gap + children[1].minIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity) <= width
        val first = children[0].measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = if (beside) firstWidth else width))
        val second = children[1].measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = if (beside) width - first.width - gap else width))
        val height = if (beside) maxOf(first.height, second.height) else first.height + 8.dp.roundToPx() + second.height
        layout(width, height) {
            first.placeRelative(0, 0)
            second.placeRelative(if (beside) first.width + gap else 0, if (beside) 0 else first.height + 8.dp.roundToPx())
        }
    }
}

@Composable
private fun StatusSpeed(upload: Boolean, rate: Long, modifier: Modifier, ink: MutableMap<StatusMetricPart, List<Rect>>) {
    val visuals = LocalLiteVisuals.current
    val (speed, unit) = dashboardTraffic(rate)
    val prefix = if (upload) "dashboard-upload" else "dashboard-download"
    val label = stringResource(if (upload) R.string.lite_upload else R.string.lite_download)
    val rateUnit = stringResource(R.string.lite_rate, unit)
    val part = if (upload) StatusMetricPart.UploadValue else StatusMetricPart.DownloadValue
    val glyph = if (upload) FullGlyphs.arrowUp else FullGlyphs.arrowDown
    val arrow = if (upload) StatusMetricPart.UploadArrowInk else StatusMetricPart.DownloadArrowInk
    val glyphFill = 0f
    Row(modifier.testTag("$prefix-speed").statusBounds(part.bounds)
        .clearAndSetSemantics { contentDescription = "$label\n$speed $rateUnit" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        LiteIcon(glyph,
            Modifier.testTag("$prefix-arrow").statusGlyphBounds(arrow, glyph, glyphFill, ink).semantics { hideFromAccessibility() },
            size = 10.dp, color = visuals.secondaryInk, fill = glyphFill)
        StatusMetricText("$speed $rateUnit", "$prefix-value", if (upload) StatusMetricPart.UploadInk else StatusMetricPart.DownloadInk,
            LiteType.Caption.copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Normal), visuals.secondaryInk, painted = ink)
    }
}

@Composable
private fun StatusChip(label: String, running: Boolean, suspended: Boolean = false) {
    val visuals = LocalLiteVisuals.current
    val warning = remember { TonalPalette.fromInt(0xffff9800.toInt()) }
    val ink = if (suspended) Color(warning.tone(if (visuals.dark) 90 else 30))
        else if (running) { if (visuals.dark) Color(0xFF86EB81) else Color(0xFF125919) } else visuals.secondaryInk
    LitePane(corner = LiteCorner.Full, solidColor = if (suspended) Color(warning.tone(if (visuals.dark) 30 else 90))
        else if (running) { if (visuals.dark) Color(0xFF123719) else Color(0xFF9AF692) } else dashboardTone(),
        glassTint = ink.copy(alpha = if (running) 0.12f else 0.06f)) {
        Row(Modifier.heightIn(min = 24.dp).padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (running) LiteText("•", Modifier.clearAndSetSemantics {}, style = LiteType.Caption, color = ink)
            LiteText(label, Modifier.testTag("dashboard-status-chip"), style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold), color = ink)
        }
    }
}

@Composable
private fun CountryBubble(code: String?, loading: Boolean, running: Boolean, time: State<Float>, refresh: () -> Unit) {
    val normalized = code?.trim()?.uppercase(Locale.ROOT)
    val valid = normalized != null && normalized.length == 2 && normalized.all { it in 'A'..'Z' } && normalized !in setOf("XX", "ZZ")
    val flag = if (valid) normalized!!.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("") else null
    val phase = if (loading) time.value else 0f
    val shape = remember(loading, phase) { DashboardBubbleOutline(0f, phase, if (loading) 0.55f else 0f, irregularity = 0f) }
    val retry = stringResource(R.string.lite_outbound_retry)
    val label = if (flag != null) "$retry\n$normalized" else retry
    Box(Modifier.size(48.dp).statusForeground(StatusForeground.Flag).testTag("dashboard-status-flag").statusControl(StatusControl.Flag)
        .semantics { contentDescription = label }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = running && !loading, role = Role.Button, onClick = refresh),
        propagateMinConstraints = true) {
        if (loading || flag == null) LitePane(Modifier.matchParentSize(), customShape = shape, corner = LiteCorner.Full) {
            // The disabled flag alone reports a check, so the indicator is only drawn.
            if (loading) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center).semantics { hideFromAccessibility() },
                color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
            else LiteIcon(FullGlyphs.proxies, Modifier.align(Alignment.Center), LocalLiteVisuals.current.secondaryInk, size = 24.dp)
        }
        else Box(Modifier.fillMaxSize().drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size.height }
            val bounds = android.graphics.Rect()
            paint.getTextBounds(flag, 0, flag.length, bounds)
            val diagonal = hypot(bounds.width().toFloat(), bounds.height().toFloat()).coerceAtLeast(1f)
            paint.textSize *= size.minDimension * 0.76f / diagonal
            paint.getTextBounds(flag, 0, flag.length, bounds)
            val x = size.width / 2 - bounds.exactCenterX()
            val y = size.height / 2 - bounds.exactCenterY()
            val radius = hypot(bounds.width().toFloat(), bounds.height().toFloat()) / 2f
            val mask = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(size.width / 2f, size.height / 2f, radius.coerceAtLeast(1f),
                    intArrayOf(android.graphics.Color.WHITE, android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0f, 0.88f, 1f), android.graphics.Shader.TileMode.CLAMP)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            }
            onDrawBehind { drawIntoCanvas {
                val canvas = it.nativeCanvas
                val checkpoint = canvas.saveLayer(0f, 0f, size.width, size.height, null)
                canvas.drawText(flag, x, y, paint)
                canvas.drawRect(0f, 0f, size.width, size.height, mask)
                canvas.restoreToCount(checkpoint)
            } }
        })
    }
}

@Composable
private fun MissingExit(failed: Boolean) {
    val modifier = Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip").statusControl(StatusControl.Address)
    if (failed) LiteText(stringResource(R.string.diag_service_failed), modifier, style = LiteType.Title2, color = MaterialTheme.colorScheme.error, maxLines = 1)
    else LiteText("—", modifier.semantics { hideFromAccessibility() }, style = LiteType.Title2)
}

@Composable
private fun StatusCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiteText(label, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
        LiteText(value, style = LiteType.Label, maxLines = 1)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StartBubble(running: Boolean, enabled: Boolean, label: String, time: State<Float>, onClick: () -> Unit, suspended: Boolean = false) {
    val visuals = LocalLiteVisuals.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(if (running) 1f else 0f, spring(dampingRatio = 0.558f, stiffness = 260f), label = "startMorph")
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 120 else 500), label = "startPress")
    val clock = time.value
    val outline = remember(progress, clock, press) { DashboardBubbleOutline(progress, clock, 0.55f * progress, press + progress * (1 - progress) * 2.4f) }
    val glyph = remember(progress, suspended) { if (suspended) SuspendedWifiGlyph else FullGlyphs.playPause(progress) }
    val solid = if (visuals.aurora) { if (visuals.dark) Color.White else Color.Black } else visuals.primary
    val tooltipState = androidx.compose.material3.rememberTooltipState()
    val tooltipScope = rememberCoroutineScope()
    val pageActive = LocalLitePageActive.current
    val focus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(pageActive) { if (!pageActive) tooltipState.dismiss() }
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = tooltipState, enableUserInput = false,
        tooltip = { LitePane(corner = LiteCorner.Control, solidColor = MaterialTheme.colorScheme.inverseSurface) {
            LiteText(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        } },
        modifier = Modifier.testTag("dashboard-status-button").statusControl(StatusControl.Button).semantics(mergeDescendants = true) {
            contentDescription = label
            role = Role.Button
            this.onClick(label = null) { if (enabled) onClick(); enabled }
            if (enabled) {
                focused = inputFocused
                requestFocus { focus.requestFocus(FocusDirection.Enter) }
            } else disabled()
        },
    ) {
    // Touch can show the tooltip without adding a TalkBack long-click action absent from Standard.
    Box(Modifier.clearAndSetSemantics {}, propagateMinConstraints = true) {
    LitePane(
        Modifier.size(88.dp).statusForeground(StatusForeground.Button, outline).alpha(if (enabled) 1f else 0.48f)
            .focusRequester(focus).onFocusChanged { inputFocused = it.isFocused }
            .combinedClickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                onLongClick = { if (pageActive) tooltipScope.launch { tooltipState.show() } }, onClick = onClick),
        corner = LiteCorner.Full, customShape = outline,
        solidColor = if (enabled) solid else dashboardTone(),
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.surface.copy(alpha = 0.15f) else Color.Transparent,
        interactionSource = interaction,
    ) {
        val foreground = if (enabled && visuals.material == MaterialTier.Solid) { if (solid == Color.White) Color.Black else Color.White } else visuals.secondaryInk
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LiteIcon(glyph, Modifier.testTag("dashboard-status-glyph"), foreground, fill = 1f, size = 88.dp * 0.34f)
        }
    }
    }
    }
}

@Composable
private fun dashboardTone(): Color = MaterialTheme.colorScheme.fullBlock(LocalLiteVisuals.current.dark)

@Composable
internal fun proxyModeLabel(mode: ProxyMode): String = stringResource(when (mode) {
    ProxyMode.Rule -> R.string.lite_mode_rule
    ProxyMode.Global -> R.string.lite_mode_global
    ProxyMode.Direct -> R.string.lite_mode_direct
})

private val SuspendedWifiGlyph = LiteGlyph(listOf(
    glyphPath("M3.32 9.56 A13.5 13.5 0 0 1 6.15 7.73", role = LiteGlyphRole.Line),
    glyphPath("M10.75 6.46 A13.5 13.5 0 0 1 20.68 9.56", role = LiteGlyphRole.Line),
    glyphPath("M6.54 13.39 A8.5 8.5 0 0 1 10.56 11.52", role = LiteGlyphRole.Line),
    glyphPath("M15.27 12.05 A8.5 8.5 0 0 1 17.46 13.39", role = LiteGlyphRole.Line),
    glyphCircle(12, 18.6, 1.7, role = LiteGlyphRole.Line, solid = true),
    glyphPolyline(listOf(vertex(4.5, 3), vertex(20.5, 19)), role = LiteGlyphRole.Line)))

internal val DashboardModePillShape = LiteSuperellipse(LiteCorner.Full)

@Composable
private fun NetworkControlsCard(state: LiteState, actions: LiteActions) {
    val network = state.settings.network
    val enabled = state.busy.isEmpty() && state.phase != VpnPhase.Starting && state.phase != VpnPhase.Stopping
    val current by androidx.compose.runtime.rememberUpdatedState(state.settings)
    DashboardPane(Modifier.testTag("dashboard-network-controls")) {
        DashboardHeader(FullGlyphs.split, stringResource(R.string.dashboard_network_control))
        Spacer(Modifier.height(12.dp))
        val candidates = ProxyMode.entries.map { proxyModeLabel(it) } + listOf(stringResource(R.string.lite_vpn), stringResource(R.string.network_allow_lan))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = LiteType.Footnote.copy(fontFamily = LocalLiteFontFamily.current)
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
        val plan = remember(maxWidth, candidates, style, density, locale) {
            val forms = candidates.associateWith { label ->
                val hinted = dashboardControlLabel(label)
                fun width(value: String) = measurer.measure(AnnotatedString(value), style = style, softWrap = false).size.width.toFloat()
                Triple(width(label), dashboardBalancedLabel(hinted, ::width), label)
            }
            val cell = with(density) { (maxWidth.toPx() - 6.dp.toPx()) / 3 }
            fun fit(room: Float) = forms.values.minOf { (single, split) -> minOf(1f, room / (split?.second ?: single).coerceAtLeast(1f)) }
            val inset = listOf(8.dp, 4.dp).firstOrNull { fit(cell - with(density) { 2 * it.toPx() }) >= 1f } ?: 4.dp
            val room = cell - with(density) { 2 * inset.toPx() }
            val scale = maxOf(minOf(1f, 11f / (style.fontSize.value * density.fontScale)), fit(room))
            DashboardControlPlan(forms.mapValues { (_, form) ->
                if (form.first * scale <= room + 0.01f || form.second == null) form.third else form.second!!.first
            }, scale, inset)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            NetworkControlPill(proxyModeLabel(state.settings.mode), when (state.settings.mode) { ProxyMode.Rule -> FullGlyphs.rules; ProxyMode.Global -> FullGlyphs.proxies; ProxyMode.Direct -> FullGlyphs.target }, true, enabled,
                Modifier.weight(1f).testTag("dashboard-mode-cycle"), plan = plan) {
                val settings = current
                actions.changeSettings(settings.copy(mode = when (settings.mode) {
                    ProxyMode.Rule -> ProxyMode.Global; ProxyMode.Global -> ProxyMode.Direct; ProxyMode.Direct -> ProxyMode.Rule
                }))
            }
            NetworkControlPill(stringResource(R.string.lite_vpn), FullGlyphs.vpn, network.vpnEnabled, enabled,
                Modifier.weight(1f).testTag("dashboard-vpn"), toggle = true, plan = plan) {
                val settings = current
                actions.changeSettings(settings.copy(network = settings.network.copy(vpnEnabled = !settings.network.vpnEnabled)))
            }
            NetworkControlPill(stringResource(R.string.network_allow_lan), FullGlyphs.devices, network.allowLan, enabled,
                Modifier.weight(1f).testTag("dashboard-intranet"), toggle = true, plan = plan) {
                val settings = current
                actions.changeSettings(settings.copy(network = settings.network.copy(allowLan = !settings.network.allowLan)))
            }
        }
        }
    }
}

private data class DashboardControlPlan(val shown: Map<String, String>, val scale: Float, val inset: androidx.compose.ui.unit.Dp)

@Composable
private fun NetworkControlPill(label: String, icon: LiteGlyph, selected: Boolean, enabled: Boolean,
    modifier: Modifier, toggle: Boolean = false, plan: DashboardControlPlan, onClick: () -> Unit) {
    val visuals = LocalLiteVisuals.current
    val interaction = remember { MutableInteractionSource() }
    val shown = plan.shown.getValue(label)
    LitePane(modifier.fillMaxHeight().heightIn(min = 52.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).clickable(interactionSource = interaction, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick).semantics {
            contentDescription = label
            if (toggle) toggleableState = if (selected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
        }, corner = LiteCorner.Full, customShape = DashboardModePillShape, selected = selected,
        interactionSource = interaction, solidColor = if (selected) lerp(dashboardTone(), visuals.primary, 0.13f) else dashboardTone()) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = plan.inset, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiteIcon(icon, color = visuals.secondaryInk, size = 18.dp)
            Text(shown, Modifier.testTag("dashboard-control-label").semantics { hideFromAccessibility() },
                color = visuals.ink, style = LiteType.Footnote.copy(fontFamily = LocalLiteFontFamily.current,
                    fontSize = LiteType.Footnote.fontSize * plan.scale, lineHeight = LiteType.Footnote.lineHeight * plan.scale,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
                    hyphens = androidx.compose.ui.text.style.Hyphens.None), softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Visible)
        }
    }
}

@Composable
private fun DashboardStatistics(state: LiteState, actions: LiteActions, openConnections: () -> Unit, openRequests: () -> Unit, openDns: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val foreground = LocalDiagnosticsForeground.current
    val overlay = LocalLiteOverlay.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = visible && foreground && overlay.content == null
    val counts = displayValue(LocalLiteDisplayStreams.current?.dashboardCounts, state.dashboardCounts, active).value
    DisposableEffect(lifecycle, actions, active) {
        var declared = false
        fun update() {
            val wanted = active && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (wanted != declared) { declared = wanted; actions.setDashboardTelemetryActive(wanted) }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); if (declared) actions.setDashboardTelemetryActive(false) }
    }
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = LocalLiteVisuals.current.secondaryInk.copy(alpha = 0.12f))
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = !bounds.isEmpty && bounds.overlaps(Rect(0f, 0f, view.rootView.width.toFloat(), view.rootView.height.toFloat()))
    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DashboardStat(counts.connectionCount?.toString(), R.string.lite_tab_connections, Modifier.weight(1f), openConnections)
        DashboardStat(counts.requestCount?.toString(), R.string.history_requests, Modifier.weight(1f), openRequests)
        DashboardStat(counts.dnsCount?.toString(), R.string.history_dns, Modifier.weight(1f), openDns)
    }
}

@Composable
private fun DashboardStat(value: String?, label: Int, modifier: Modifier, open: () -> Unit) {
    Column(modifier.clickable(role = Role.Button, onClick = open), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LiteText(value ?: "—", style = LiteType.Title2, maxLines = 1)
        LiteText(stringResource(label), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
    }
}

@Composable
private fun StatusTrafficReadings(state: LiteState, active: Boolean, rowSpan: State<Float>,
    ink: MutableMap<StatusMetricPart, List<Rect>>, modifier: Modifier) {
    val traffic = displayValue(LocalLiteDisplayStreams.current?.traffic, state.traffic, active).value
    Layout(modifier = modifier, content = {
        StatusSpeed(true, traffic.uploadPerSecond, Modifier, ink)
        StatusSpeed(false, traffic.downloadPerSecond, Modifier, ink)
    }) { children, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val upload = children[0].measure(loose)
        val download = children[1].measure(loose)
        val firstInk = StatusMetricPart.UploadInk.bounds.rect(upload)
        val secondInk = StatusMetricPart.DownloadInk.bounds.rect(download)
        val secondY = (rowSpan.value + (firstInk?.center?.y ?: upload.height / 2f) -
            (secondInk?.center?.y ?: download.height / 2f)).roundToInt().coerceAtLeast(upload.height)
        val width = maxOf(upload.width, download.width)
        layout(width, secondY + download.height) {
            upload.place(width - upload.width, 0)
            download.place(width - download.width, secondY)
        }
    }
}

@Composable
private fun StatusTrafficBackground(state: LiteState, running: Boolean, active: Boolean, measured: Boolean,
    modifier: Modifier) {
    val display = LocalLiteDisplayStreams.current
    val history = displayValue(display?.trafficHistory, state.trafficHistory, active).value
    val revision = displayValue(display?.trafficHistoryRevision, state.trafficHistoryRevision, active).value
    val episode = remember { DashboardTrafficEpisode() }
    val samples = if (measured) episode.samples(history, revision, active && running) else emptyList()
    DashboardTrafficBackdrop(samples, running, active, modifier)
}
