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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
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
                if (DashboardWidget.CurrentOutbound in shown) OutboundStatusCard(state, actions, DashboardWidget.NetworkSpeed in shown)
                else NetworkSpeedCard(state)
                return
            }
            in DashboardControlMembers -> { NetworkControlsCard(state, actions, shown); return }
            in DashboardOverviewMembers -> {
                ServiceStatusPanel(state, actions, showServices = DashboardWidget.ServiceStatus in shown,
                    titleResource = R.string.dashboard_network_overview) {
                    if (DashboardWidget.ConnectionStats in shown) DashboardStatistics(state, actions, openConnections, openRequests, openDns)
                }
                return
            }
            else -> Unit
        }
    }
    when (widget) {
        DashboardWidget.CurrentOutbound -> OutboundStatusCard(state, actions)
        DashboardWidget.NetworkSpeed -> NetworkSpeedCard(state)
        DashboardWidget.OutboundMode -> OutboundModeCard(state, actions)
        DashboardWidget.VpnButton -> DashboardVpnCard(state, actions)
        DashboardWidget.IntranetIp -> IntranetCard(state.localAddress)
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
        LiteText(stringResource(R.string.lite_connection_count, state.connections.size), style = LiteType.Title2)
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
    val left = VerticalAlignmentLine(::minOf)
    val right = VerticalAlignmentLine(::maxOf)
    val top = HorizontalAlignmentLine(::minOf)
    val bottom = HorizontalAlignmentLine(::maxOf)

    fun rect(parent: Placeable, x: Int = 0, y: Int = 0): Rect? {
        val start = parent[left]
        if (start == AlignmentLine.Unspecified) return null
        return Rect((x + start).toFloat(), (y + parent[top]).toFloat(), (x + parent[right]).toFloat(), (y + parent[bottom]).toFloat())
    }
}

private enum class StatusForeground {
    Address, Flag, Quality, Provisional, Uptime, Organization, Button, ListenerError, OutboundError;
    val bounds = StatusBounds()
}

private enum class StatusMetricPart {
    UploadArrow, UploadLabel, UploadValue, UploadUnit, UploadTotal,
    DownloadArrow, DownloadLabel, DownloadValue, DownloadUnit, DownloadTotal;
    val bounds = StatusBounds()
}

private fun Modifier.statusBounds(bounds: StatusBounds) = layout { measurable, constraints ->
    val child = measurable.measure(constraints)
    layout(child.width, child.height, mapOf(bounds.left to 0, bounds.top to 0, bounds.right to child.width, bounds.bottom to child.height)) {
        child.place(0, 0)
    }
}

private fun Modifier.statusForeground(part: StatusForeground) = statusBounds(part.bounds)

/** The body's controls, which a screen reader takes after everything else the body reports. */
private enum class StatusControl { Flag, Address, Button }

private fun Modifier.statusControl(control: StatusControl) = semantics { traversalIndex = control.ordinal + 1f }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutboundStatusCard(state: LiteState, actions: LiteActions, showTraffic: Boolean = false) {
    val visuals = LocalLiteVisuals.current
    val running = state.phase == VpnPhase.Running
    val suspended = running && state.suspended
    var visible by remember { mutableStateOf(false) }
    var measured by remember { mutableStateOf(false) }
    val view = LocalView.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && LocalLitePageActive.current && LocalDiagnosticsForeground.current &&
        LocalLiteOverlay.current.content == null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val time = rememberDashboardTime(running && !suspended && active)
    val episode = remember { DashboardTrafficEpisode() }
    val history = if (measured) episode.samples(state.trafficHistory, state.trafficHistoryRevision, active && running && !suspended && showTraffic) else emptyList()
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
    val curves = showTraffic && running && !suspended && history.isNotEmpty()
    val motion = active && !LocalLiteReducedMotion.current
    val curveMix by animateFloatAsState(if (curves) 1f else 0f, tween(if (motion) 240 else 0), label = "statusTrafficBackdrop")
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
            if (curveMix < 1f) DashboardRibbon(time, Modifier.matchParentSize().alpha(1f - curveMix))
            if (curveMix > 0f) DashboardTrafficBackdrop(history, Modifier.matchParentSize().alpha(curveMix))
        }
        CompositionLocalProvider(LocalLiteBackdrop provides controlsSource) {
            // Groups keep each part's stops together; geometry alone puts traffic in the heading's row or after the body.
            Layout(modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp).testTag("dashboard-status-content")
                .semantics { isTraversalGroup = true }, content = {
                FlowRow(Modifier.testTag("dashboard-status-heading"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.alignBy(FirstBaseline), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiteIcon(FullGlyphs.status, color = visuals.secondaryInk, size = 18.dp)
                        LiteText(stringResource(R.string.lite_status), Modifier.testTag("dashboard-status-title"), style = LiteType.Label, maxLines = 1)
                    }
                    Box(Modifier.alignBy(FirstBaseline)) { StatusChip(stringResource(status), running, suspended) }
                }
                Column(Modifier.fillMaxWidth().testTag("dashboard-status-body").semantics { isTraversalGroup = true }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (qualityFeature != null) IpQualityStatus(qualityFeature) { quality, open ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CountryBubble(identity?.countryCode ?: quality.countryCode, state.outboundLoading || quality.loading, running, actions::refreshOutbound)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val shown = identity?.address ?: quality.ip?.takeIf { state.outboundLoading && quality.provisional }
                                        val detail = stringResource(R.string.lite_details, stringResource(R.string.lite_outbound_ip))
                                        if (shown != null) {
                                            val address = if (quality.hidden) "*****" else shown
                                            // TalkBack joins Standard's address and its unique tooltip with ", "; Compose exposes no tooltip.
                                            // Standard also reads the provisional last exit while its first probe runs (outbound_ip.dart).
                                            LiteText(address, Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip").statusControl(StatusControl.Address)
                                                .clickable(onClick = open).semantics { contentDescription = "$address, $detail" },
                                                style = LiteType.Title2, color = qualityInk(quality.quality?.level), maxLines = 1)
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
                                CountryBubble(identity?.countryCode, state.outboundLoading, running, actions::refreshOutbound)
                                if (failed) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    MissingExit(true)
                                    LiteText(stringResource(R.string.lite_tap_icon_to_retry), Modifier.statusForeground(StatusForeground.Quality).testTag("dashboard-status-retry-hint"),
                                        style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                                } else MissingExit(false)
                            }
                            val uptimeLabel = stringResource(R.string.lite_uptime)
                            val uptime = if (intendsRunning && !suspended) formatUptime(state.uptimeMillis) else "—"
                            LiteText("$uptimeLabel $uptime", Modifier.statusForeground(StatusForeground.Uptime).testTag("dashboard-status-uptime")
                                .semantics { contentDescription = "$uptimeLabel\n$uptime" }, style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                            val organization = qualityState?.quality?.let { it.organization?.takeIf(String::isNotEmpty) ?: it.asn?.let { asn -> "AS$asn" } }
                            val organizationLabel = stringResource(R.string.lite_organization)
                            val organizationPending = if (identity == null) !failed else qualityState?.loading == true
                            LiteText(organization ?: "—", Modifier.statusForeground(StatusForeground.Organization).testTag("dashboard-status-organization").semantics {
                                contentDescription = listOfNotNull(organizationLabel, organization ?: "—".takeUnless { organizationPending }).joinToString("\n")
                            }, style = LiteType.Label, maxLines = 2)
                        }
                        StartBubble(intendsRunning, enabled, stringResource(if (suspended) R.string.integration_suspended else if (intendsRunning) R.string.lite_stop else R.string.lite_start), time,
                            if (intendsRunning) actions::disconnect else actions::connect, suspended)
                    }
                    state.listenerError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.ListenerError).testTag("dashboard-status-listener-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    state.outboundError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.OutboundError).testTag("dashboard-status-outbound-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                }
                FlowRow(Modifier.testTag("dashboard-status-traffic").semantics { isTraversalGroup = true }, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showTraffic) {
                        StatusSpeed(true, state.traffic.uploadPerSecond, state.traffic.upload, Modifier)
                        StatusSpeed(false, state.traffic.downloadPerSecond, state.traffic.download, Modifier)
                    }
                }
            }) { children, constraints ->
                val loose = constraints.copy(minWidth = 0, minHeight = 0)
                val heading = children[0].measure(loose)
                val body = children[1].measure(loose.copy(minWidth = constraints.maxWidth))
                val traffic = children[2].measure(loose)
                val gap = 12.dp.roundToPx()
                val bodyY = heading.height + gap
                val mainHeight = bodyY + body.height
                val trafficX = if (layoutDirection == LayoutDirection.Ltr) constraints.maxWidth - traffic.width else 0
                val metricBounds = StatusMetricPart.entries.mapNotNull { it.bounds.rect(traffic, x = trafficX) }
                val overlapsForeground = showTraffic && StatusForeground.entries.any { part ->
                    part.bounds.rect(body, y = bodyY)?.let { occupied -> metricBounds.any(occupied::overlaps) } == true
                }
                // Only an actual foreground collision can append metrics without constraining the original content.
                val overlay = !showTraffic || (heading.width + 8.dp.roundToPx() + traffic.width <= constraints.maxWidth &&
                    !overlapsForeground)
                val height = mainHeight + if (overlay || !showTraffic) 0 else gap + traffic.height
                layout(constraints.maxWidth, constraints.constrainHeight(height)) {
                    heading.placeRelative(0, 0)
                    body.placeRelative(0, bodyY)
                    traffic.placeRelative(constraints.maxWidth - traffic.width, if (overlay) 0 else mainHeight + gap)
                }
            }
        }
    }
}

@Composable
private fun StatusMetricText(text: String, tag: String, part: StatusMetricPart, style: TextStyle,
    color: Color, modifier: Modifier = Modifier, description: String? = null) {
    val density = LocalDensity.current
    val family = style.fontFamily ?: LocalLiteFontFamily.current
    val resolvedTypeface by LocalFontFamilyResolver.current.resolve(family, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal, style.fontSynthesis ?: FontSynthesis.All)
    val locales = style.localeList ?: LocaleList.current
    val ink = remember(text, style.fontSize, resolvedTypeface, locales, density.density, density.fontScale) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { style.fontSize.toPx() }
            this.typeface = resolvedTypeface as android.graphics.Typeface
            textLocales = android.os.LocaleList(*(0 until locales.size).map { Locale.forLanguageTag(locales[it].toLanguageTag()) }.toTypedArray())
        }
        android.graphics.Rect().also { paint.getTextBounds(text, 0, text.length, it) }
    }
    val leading = with(density) { ceil(style.lineHeight.toPx()).toInt() }
    // Preserve the natural paragraph; its transparent leading does not belong to the compact line.
    Layout(modifier = modifier.testTag(tag).then(if (description != null) Modifier.clearAndSetSemantics { contentDescription = description }
        else Modifier.semantics {}), content = {
        LiteText(text, Modifier.testTag("$tag-raw"), style = style, color = color, maxLines = 1)
    }) { children, constraints ->
        val glyphs = children.single().measure(constraints.copy(minHeight = 0))
        val height = maxOf(leading, ink.height())
        val baseline = (height - ink.height()) / 2 - ink.top
        val originalBaseline = glyphs[FirstBaseline]
        check(originalBaseline != AlignmentLine.Unspecified)
        layout(glyphs.width, height, mapOf(FirstBaseline to baseline, LastBaseline to baseline,
            part.bounds.left to minOf(0, ink.left), part.bounds.right to maxOf(glyphs.width, ink.right),
            part.bounds.top to 0, part.bounds.bottom to height)) {
            glyphs.place(0, baseline - originalBaseline)
        }
    }
}

@Composable
private fun StatusSpeed(upload: Boolean, rate: Long, total: Long, modifier: Modifier) {
    val visuals = LocalLiteVisuals.current
    val (speed, unit) = dashboardTraffic(rate)
    val prefix = if (upload) "dashboard-upload" else "dashboard-download"
    val totalValue = dashboardTraffic(total).let { (value, totalUnit) -> "$value $totalUnit" }
    val label = stringResource(if (upload) R.string.lite_upload else R.string.lite_download)
    val rateUnit = stringResource(R.string.lite_rate, unit)
    Column(modifier.testTag(prefix).semantics { isTraversalGroup = true }, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Column(Modifier.testTag("$prefix-speed").clearAndSetSemantics { contentDescription = "$label\n$speed $rateUnit" }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LiteIcon(if (upload) FullGlyphs.arrowUp else FullGlyphs.arrowDown,
                    Modifier.testTag("$prefix-arrow").statusBounds((if (upload) StatusMetricPart.UploadArrow else StatusMetricPart.DownloadArrow).bounds),
                    size = 10.dp, color = visuals.secondaryInk)
                StatusMetricText(label, "$prefix-label",
                    if (upload) StatusMetricPart.UploadLabel else StatusMetricPart.DownloadLabel,
                    LiteType.Caption.copy(fontSize = 11.sp, lineHeight = 12.sp), visuals.ink)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                StatusMetricText(speed, "$prefix-value", if (upload) StatusMetricPart.UploadValue else StatusMetricPart.DownloadValue,
                    LiteType.Footnote.copy(fontSize = 14.sp, lineHeight = 14.sp), visuals.ink, Modifier.alignByBaseline())
                StatusMetricText(rateUnit, "$prefix-unit",
                    if (upload) StatusMetricPart.UploadUnit else StatusMetricPart.DownloadUnit,
                    LiteType.Caption.copy(fontSize = 10.sp, lineHeight = 10.sp), visuals.secondaryInk, Modifier.alignByBaseline())
            }
        }
        StatusMetricText(totalValue, "$prefix-total", if (upload) StatusMetricPart.UploadTotal else StatusMetricPart.DownloadTotal,
            LiteType.Caption.copy(fontSize = 10.sp, lineHeight = 10.sp), visuals.secondaryInk, Modifier.align(Alignment.End),
            description = stringResource(R.string.lite_traffic_total, totalValue))
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
        Row(Modifier.height(24.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (running) LiteText("•", Modifier.clearAndSetSemantics {}, style = LiteType.Caption, color = ink)
            LiteText(label, Modifier.testTag("dashboard-status-chip"), style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun CountryBubble(code: String?, loading: Boolean, running: Boolean, refresh: () -> Unit) {
    val normalized = code?.trim()?.uppercase(Locale.ROOT)
    val valid = normalized != null && normalized.length == 2 && normalized.all { it in 'A'..'Z' } && normalized !in setOf("XX", "ZZ")
    val flag = if (valid) normalized!!.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("") else null
    val shape = remember { DashboardBubbleOutline(0f, 0f, 0f, irregularity = 0f) }
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
        Modifier.size(88.dp).statusForeground(StatusForeground.Button).alpha(if (enabled) 1f else 0.48f)
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
private fun NetworkSpeedCard(state: LiteState) {
    DashboardPane(Modifier.testTag("dashboard-speed").semantics { isTraversalGroup = true }) {
        DashboardHeader(FullGlyphs.speed, stringResource(R.string.lite_network_speed))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedTile(true, state.traffic.uploadPerSecond, state.traffic.upload, Modifier.weight(1f))
            SpeedTile(false, state.traffic.downloadPerSecond, state.traffic.download, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedTile(upload: Boolean, speed: Long, total: Long, modifier: Modifier) {
    val visuals = LocalLiteVisuals.current
    val scale = LocalDensity.current.fontScale
    val prefix = if (upload) "dashboard-speed-upload" else "dashboard-speed-download"
    BoxWithConstraints(modifier.testTag(prefix).semantics { isTraversalGroup = true }) {
        val height = maxOf(maxWidth / 1.06f, (64 + 68 * scale).dp)
        LitePane(Modifier.fillMaxWidth().height(height), corner = LiteCorner.DashboardBlock, solidColor = dashboardTone(),
            glassTint = visuals.surface.copy(alpha = 0.08f)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                val label = stringResource(if (upload) R.string.lite_upload else R.string.lite_download)
                val (value, unit) = dashboardTraffic(speed)
                val rateUnit = stringResource(R.string.lite_rate, unit)
                Column(Modifier.testTag("$prefix-speed").clearAndSetSemantics { contentDescription = "$label\n$value $rateUnit" }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LiteIcon(if (upload) FullGlyphs.arrowUp else FullGlyphs.arrowDown, color = if (upload) visuals.secondaryInk else visuals.primary, size = 16.dp)
                        LiteText(label, style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = visuals.secondaryInk, maxLines = 1)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LiteText(value, style = LiteType.Title1, maxLines = 1)
                        LiteText(rateUnit, Modifier.padding(bottom = 4.dp), style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = visuals.secondaryInk, maxLines = 1)
                    }
                }
                val totalValue = dashboardTraffic(total).let { (amount, totalUnit) -> "$amount $totalUnit" }
                LiteText(stringResource(R.string.lite_traffic_total, totalValue), Modifier.testTag("$prefix-total"), style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OutboundModeCard(state: LiteState, actions: LiteActions) {
    val visuals = LocalLiteVisuals.current
    DashboardPane(Modifier.testTag("dashboard-mode")) {
        DashboardHeader(FullGlyphs.split, stringResource(R.string.lite_proxy_mode))
        Spacer(Modifier.height(12.dp))
        OutboundModeSelector(state, actions)
    }
}

@Composable
private fun DashboardVpnCard(state: LiteState, actions: LiteActions) {
    val vpnEnabled = state.settings.network.vpnEnabled
    val enabled = state.phase != VpnPhase.Starting && state.phase != VpnPhase.Stopping && state.busy.isEmpty()
    val visuals = LocalLiteVisuals.current
    LitePane(Modifier.fillMaxWidth().testTag("dashboard-vpn"), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LitePane(Modifier.size(48.dp), corner = LiteCorner.Full, solidColor = if (vpnEnabled && visuals.material == MaterialTier.Solid) visuals.primary else dashboardTone()) {
                LiteIcon(FullGlyphs.vpn, Modifier.align(Alignment.Center), if (vpnEnabled && visuals.material == MaterialTier.Solid) Color.White else visuals.secondaryInk, size = 22.dp)
            }
            Column(Modifier.weight(1f)) {
                LiteText(stringResource(R.string.lite_vpn), style = LiteType.Callout.copy(fontWeight = FontWeight.SemiBold))
                LiteText(stringResource(if (vpnEnabled) R.string.lite_enabled else R.string.lite_disabled), style = LiteType.Footnote, color = visuals.secondaryInk)
            }
            LiteSwitch(vpnEnabled, stringResource(R.string.lite_vpn),
                { actions.changeSettings(state.settings.copy(network = state.settings.network.copy(vpnEnabled = it))) }, enabled)
        }
    }
}

@Composable
private fun IntranetCard(address: String?) {
    val clipboard = LocalClipboardManager.current
    DashboardPane(Modifier.testTag("dashboard-intranet")) {
        DashboardHeader(FullGlyphs.devices, stringResource(R.string.lite_intranet_ip), trailing = {
            LiteIconButton(stringResource(R.string.lite_copy), FullGlyphs.copy, { if (address != null) clipboard.setText(AnnotatedString(address)) }, enabled = address != null)
        })
        Spacer(Modifier.height(12.dp))
        LiteText(address ?: "—", style = LiteType.Title1, maxLines = 1)
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
private fun OutboundModeSelector(state: LiteState, actions: LiteActions) {
    val visuals = LocalLiteVisuals.current
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ProxyMode.entries.forEach { mode ->
            val selected = state.settings.mode == mode
            val amount by animateFloatAsState(if (selected) 1f else 0f, tween(240), label = "modeShape")
            val fill = if (visuals.aurora) { if (visuals.dark) Color.White else Color.Black } else visuals.primary
            val foreground = if (selected && visuals.material == MaterialTier.Solid) { if (fill == Color.White) Color.Black else Color.White } else visuals.secondaryInk
            val interaction = remember { MutableInteractionSource() }
            LitePane(
                Modifier.weight(1f).heightIn(min = 52.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)).testTag("dashboard-mode-${mode.name}").selectable(selected, interactionSource = interaction, indication = null,
                    enabled = state.busy.isEmpty(), role = Role.RadioButton, onClick = { if (!selected) actions.changeSettings(state.settings.copy(mode = mode)) }),
                corner = LiteCorner.Full, selected = selected, customShape = DashboardModePillShape,
                solidColor = lerp(dashboardTone(), fill, amount),
                glassTint = visuals.primary.copy(alpha = 0.2f * amount),
                interactionSource = interaction,
            ) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    LiteIcon(when (mode) { ProxyMode.Rule -> FullGlyphs.rules; ProxyMode.Global -> FullGlyphs.proxies; ProxyMode.Direct -> FullGlyphs.target }, Modifier.testTag("dashboard-mode-${mode.name}-icon"), color = foreground, size = 18.dp)
                    LiteText(proxyModeLabel(mode), Modifier.testTag("dashboard-mode-${mode.name}-label"), style = LiteType.Footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = foreground, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NetworkControlsCard(state: LiteState, actions: LiteActions, shown: Set<DashboardWidget>) {
    val visuals = LocalLiteVisuals.current
    val network = state.settings.network
    val enabled = state.busy.isEmpty() && state.phase != VpnPhase.Starting && state.phase != VpnPhase.Stopping
    val clipboard = LocalClipboardManager.current
    val address = dashboardLanAddress(state.localAddress, network.ports.mixed, network.allowLan)
    DashboardPane(Modifier.testTag("dashboard-network-controls"), contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp,
        bottom = if (DashboardWidget.IntranetIp in shown) 0.dp else 18.dp)) {
        DashboardHeader(FullGlyphs.split, stringResource(R.string.dashboard_network_control))
        if (DashboardWidget.OutboundMode in shown) {
            Spacer(Modifier.height(12.dp))
            OutboundModeSelector(state, actions)
        }
        val vpn = DashboardWidget.VpnButton in shown
        val lan = DashboardWidget.IntranetIp in shown
        if (vpn || lan) {
            Spacer(Modifier.height(if (DashboardWidget.OutboundMode in shown) 16.dp else 12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vpn) Box(Modifier.weight(1f).testTag("dashboard-vpn")) {
                    NetworkControlToggle(stringResource(R.string.lite_vpn), network.vpnEnabled, enabled) {
                        actions.changeSettings(state.settings.copy(network = network.copy(vpnEnabled = it)))
                    }
                }
                if (lan) Box(Modifier.weight(1f).testTag("dashboard-intranet")) {
                    NetworkControlToggle(stringResource(R.string.network_allow_lan), network.allowLan, enabled) {
                        actions.changeSettings(state.settings.copy(network = network.copy(allowLan = it)))
                    }
                }
            }
            if (lan) Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("dashboard-lan-copy")
                .clickable(enabled = address != null, role = Role.Button, onClickLabel = stringResource(R.string.lite_copy)) {
                    address?.let { clipboard.setText(AnnotatedString(it)) }
                }.padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                LiteText(address ?: "—", Modifier.testTag("dashboard-lan-footer"),
                    style = LiteType.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    color = visuals.secondaryInk, maxLines = 2)
            }
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
        DashboardStat(state.dashboardCounts.connectionCount?.toString(), R.string.lite_tab_connections, Modifier.weight(1f), openConnections)
        DashboardStat(state.dashboardCounts.requestCount?.toString(), R.string.history_requests, Modifier.weight(1f), openRequests)
        DashboardStat(state.dashboardCounts.dnsCount?.toString(), R.string.history_dns, Modifier.weight(1f), openDns)
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
private fun NetworkControlToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LiteType.Label.copy(fontFamily = LocalLiteFontFamily.current)
    val labelWidth = measurer.measure(AnnotatedString(label), style = style, maxLines = 1).size.width
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // A large font or long translation may grow its own half; VPN and LAN remain side by side.
        if (labelWidth + with(density) { 66.dp.toPx() } <= with(density) { maxWidth.toPx() }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiteText(label, Modifier.weight(1f), style = LiteType.Label, maxLines = 1)
                LiteSwitch(checked, label, onChange, enabled)
            }
        } else Column(Modifier.fillMaxWidth()) {
            LiteText(label, style = LiteType.Label)
            LiteSwitch(checked, label, onChange, enabled)
        }
    }
}
