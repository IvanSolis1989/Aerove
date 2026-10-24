package dev.aerove.lite.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
private fun DashboardPane(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val visuals = LocalLiteVisuals.current
    LitePane(
        modifier.fillMaxWidth(), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
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
    val busy = state.phase == VpnPhase.Starting || state.phase == VpnPhase.Stopping || state.busy.isNotEmpty()
    val enabled = !busy && (running || state.selectedProfile != null)
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
            Layout(modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp).testTag("dashboard-status-content"), content = {
                FlowRow(Modifier.testTag("dashboard-status-heading"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.alignBy(FirstBaseline), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LiteIcon(FullGlyphs.status, color = visuals.secondaryInk, size = 18.dp)
                        LiteText(stringResource(R.string.lite_status), style = LiteType.Label, maxLines = 1)
                    }
                    Box(Modifier.alignBy(FirstBaseline)) { StatusChip(stringResource(status), running, suspended) }
                }
                Column(Modifier.fillMaxWidth().testTag("dashboard-status-body")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (qualityFeature != null) IpQualityStatus(qualityFeature) { quality, open ->
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CountryBubble(identity?.countryCode ?: quality.countryCode, state.outboundLoading || quality.loading, running, actions::refreshOutbound)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val shown = identity?.address ?: quality.ip?.takeIf { state.outboundLoading && quality.provisional }
                                        if (shown != null) LiteText(if (quality.hidden) "*****" else shown,
                                            Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip").clickable(role = Role.Button, onClick = open), style = LiteType.Title2,
                                            color = qualityInk(quality.quality?.level), maxLines = 1)
                                        else if (state.outboundLoading) LiteText(stringResource(R.string.lite_outbound_loading), Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip"), style = LiteType.Footnote, color = visuals.secondaryInk, maxLines = 2)
                                        else LiteText("—", Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip"), style = LiteType.Title2)
                                        Box(Modifier.statusForeground(StatusForeground.Quality)) { IpQualityChips(quality, open) }
                                        if (quality.provisional) LiteText(stringResource(R.string.quality_provisional), Modifier.statusForeground(StatusForeground.Provisional).testTag("dashboard-status-provisional"), style = LiteType.Caption, color = visuals.secondaryInk)
                                    }
                                }
                            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CountryBubble(identity?.countryCode, state.outboundLoading, running, actions::refreshOutbound)
                                LiteText("—", Modifier.statusForeground(StatusForeground.Address).testTag("dashboard-status-ip"), style = LiteType.Title2)
                            }
                            LiteText("${stringResource(R.string.lite_uptime)} ${if (running) formatUptime(state.uptimeMillis) else "—"}",
                                Modifier.statusForeground(StatusForeground.Uptime).testTag("dashboard-status-uptime"), style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                            LiteText(qualityState?.quality?.organization ?: "—", Modifier.statusForeground(StatusForeground.Organization).testTag("dashboard-status-organization"), style = LiteType.Label, maxLines = 2)
                        }
                        StartBubble(running, enabled, stringResource(if (suspended) R.string.integration_suspended else if (running) R.string.action_disconnect else R.string.lite_connect), time,
                            if (running) actions::disconnect else actions::connect, suspended)
                    }
                    state.listenerError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.ListenerError).testTag("dashboard-status-listener-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                    state.outboundError?.takeIf { it.isNotBlank() }?.let { LiteText(it, Modifier.statusForeground(StatusForeground.OutboundError).testTag("dashboard-status-outbound-error"), style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                }
                FlowRow(Modifier.testTag("dashboard-status-traffic"), horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Layout(modifier = modifier.testTag(tag).semantics(mergeDescendants = true) {
        if (description != null) contentDescription = description
    }, content = {
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
    val value = formatBytes(rate).split(' ', limit = 2)
    val prefix = if (upload) "dashboard-upload" else "dashboard-download"
    val totalValue = formatBytes(total)
    Column(modifier.testTag(prefix), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            LiteIcon(if (upload) FullGlyphs.arrowUp else FullGlyphs.arrowDown,
                Modifier.testTag("$prefix-arrow").statusBounds((if (upload) StatusMetricPart.UploadArrow else StatusMetricPart.DownloadArrow).bounds),
                size = 10.dp, color = visuals.secondaryInk)
            StatusMetricText(stringResource(if (upload) R.string.lite_upload else R.string.lite_download), "$prefix-label",
                if (upload) StatusMetricPart.UploadLabel else StatusMetricPart.DownloadLabel,
                LiteType.Caption.copy(fontSize = 11.sp, lineHeight = 12.sp), visuals.ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            StatusMetricText(value[0], "$prefix-value", if (upload) StatusMetricPart.UploadValue else StatusMetricPart.DownloadValue,
                LiteType.Footnote.copy(fontSize = 14.sp, lineHeight = 14.sp), visuals.ink, Modifier.alignByBaseline())
            StatusMetricText(stringResource(R.string.lite_rate, value.getOrElse(1) { "B" }), "$prefix-unit",
                if (upload) StatusMetricPart.UploadUnit else StatusMetricPart.DownloadUnit,
                LiteType.Caption.copy(fontSize = 10.sp, lineHeight = 10.sp), visuals.secondaryInk, Modifier.alignByBaseline())
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
            if (running) LiteText("•", style = LiteType.Caption, color = ink)
            LiteText(label, style = LiteType.Caption.copy(fontWeight = FontWeight.SemiBold), color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun CountryBubble(code: String?, loading: Boolean, running: Boolean, refresh: () -> Unit) {
    val normalized = code?.trim()?.uppercase(Locale.ROOT)
    val valid = normalized != null && normalized.length == 2 && normalized.all { it in 'A'..'Z' } && normalized !in setOf("XX", "ZZ")
    val flag = if (valid) normalized!!.map { String(Character.toChars(0x1F1E6 + it.code - 'A'.code)) }.joinToString("") else null
    val shape = remember { DashboardBubbleOutline(0f, 0f, 0f, irregularity = 0f) }
    val label = stringResource(R.string.lite_outbound_refresh)
    Box(Modifier.size(48.dp).statusForeground(StatusForeground.Flag).testTag("dashboard-status-flag").semantics { contentDescription = label; stateDescription = normalized ?: "" }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = running && !loading, role = Role.Button, onClick = refresh),
        propagateMinConstraints = true) {
        if (loading || flag == null) LitePane(Modifier.matchParentSize(), customShape = shape, corner = LiteCorner.Full) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center), color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
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
    val pageActive = LocalLitePageActive.current
    LaunchedEffect(pageActive) { if (!pageActive) tooltipState.dismiss() }
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = tooltipState, enableUserInput = pageActive,
        tooltip = { LitePane(corner = LiteCorner.Control, solidColor = MaterialTheme.colorScheme.inverseSurface) {
            LiteText(label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.inverseOnSurface)
        } },
    ) {
    LitePane(
        Modifier.size(88.dp).statusForeground(StatusForeground.Button).testTag("dashboard-status-button").alpha(if (enabled) 1f else 0.48f).semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        corner = LiteCorner.Full, customShape = outline,
        solidColor = if (enabled) solid else dashboardTone(),
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.surface.copy(alpha = 0.15f) else Color.Transparent,
        interactionSource = interaction,
    ) {
        val foreground = if (enabled && visuals.material == MaterialTier.Solid) { if (solid == Color.White) Color.Black else Color.White } else visuals.secondaryInk
        LiteIcon(glyph, Modifier.align(Alignment.Center), foreground, fill = 1f, size = 88.dp * 0.34f)
    }
    }
}

@Composable
private fun NetworkSpeedCard(state: LiteState) {
    DashboardPane(Modifier.testTag("dashboard-speed")) {
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
    BoxWithConstraints(modifier) {
        val height = maxOf(maxWidth / 1.06f, (64 + 68 * scale).dp)
        LitePane(Modifier.fillMaxWidth().height(height), corner = LiteCorner.DashboardBlock, solidColor = dashboardTone(),
            glassTint = visuals.surface.copy(alpha = 0.08f)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiteIcon(if (upload) FullGlyphs.arrowUp else FullGlyphs.arrowDown, color = if (upload) visuals.secondaryInk else visuals.primary, size = 16.dp)
                    LiteText(stringResource(if (upload) R.string.lite_upload else R.string.lite_download), style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = visuals.secondaryInk, maxLines = 1)
                }
                Spacer(Modifier.height(4.dp))
                val show = formatBytes(speed).split(' ', limit = 2)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiteText(show[0], style = LiteType.Title1, maxLines = 1)
                    LiteText(stringResource(R.string.lite_rate, show.getOrElse(1) { "B" }), Modifier.padding(bottom = 4.dp), style = LiteType.Footnote.copy(fontWeight = FontWeight.SemiBold), color = visuals.secondaryInk, maxLines = 1)
                }
                LiteText(stringResource(R.string.lite_traffic_total, formatBytes(total)), style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
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

private fun formatUptime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

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

@Composable
private fun OutboundModeSelector(state: LiteState, actions: LiteActions) {
    val visuals = LocalLiteVisuals.current
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ProxyMode.entries.forEachIndexed { index, mode ->
            val selected = state.settings.mode == mode
            val amount by animateFloatAsState(if (selected) 1f else 0f, tween(240), label = "modeShape")
            val shape = remember(index, amount, visuals.material) {
                if (visuals.material != MaterialTier.Solid) LiteSuperellipse(LiteCorner.Full)
                else LiteSuperellipse((if (index == 0) 26.dp else 12.dp) + (if (index == 0) 0.dp else 14.dp) * amount,
                    (if (index == 2) 26.dp else 12.dp) + (if (index == 2) 0.dp else 14.dp) * amount)
            }
            val fill = if (visuals.aurora) { if (visuals.dark) Color.White else Color.Black } else visuals.primary
            val foreground = if (selected && visuals.material == MaterialTier.Solid) { if (fill == Color.White) Color.Black else Color.White } else visuals.secondaryInk
            val interaction = remember { MutableInteractionSource() }
            LitePane(
                Modifier.weight(1f).heightIn(min = 52.dp).selectable(selected, interactionSource = interaction, indication = null,
                    enabled = state.busy.isEmpty(), role = Role.RadioButton, onClick = { if (!selected) actions.changeSettings(state.settings.copy(mode = mode)) }),
                corner = LiteCorner.Full, selected = selected, customShape = shape,
                solidColor = lerp(dashboardTone(), fill, amount),
                glassTint = visuals.primary.copy(alpha = 0.2f * amount),
                interactionSource = interaction,
            ) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LiteIcon(when (mode) { ProxyMode.Rule -> FullGlyphs.rules; ProxyMode.Global -> FullGlyphs.proxies; ProxyMode.Direct -> FullGlyphs.target }, color = foreground, size = 18.dp)
                    LiteText(proxyModeLabel(mode), style = LiteType.Footnote.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal), color = foreground, maxLines = 1)
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
    DashboardPane(Modifier.testTag("dashboard-network-controls")) {
        DashboardHeader(FullGlyphs.split, stringResource(R.string.dashboard_network_control))
        if (DashboardWidget.OutboundMode in shown) {
            Spacer(Modifier.height(12.dp))
            OutboundModeSelector(state, actions)
        }
        val vpn = DashboardWidget.VpnButton in shown
        val lan = DashboardWidget.IntranetIp in shown
        if (vpn || lan) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = visuals.secondaryInk.copy(alpha = 0.12f))
            Spacer(Modifier.height(12.dp))
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
            Layout(modifier = Modifier.fillMaxWidth(), content = {
                LiteText(if (vpn) stringResource(if (network.vpnEnabled) R.string.lite_enabled else R.string.lite_disabled) else "",
                    Modifier.testTag("dashboard-vpn-footer"), style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                LiteText(if (lan) address ?: "—" else "", Modifier.testTag("dashboard-lan-footer"),
                    style = LiteType.Caption, color = visuals.secondaryInk, maxLines = 1)
                Box {
                    if (lan) LiteIconButton(stringResource(R.string.lite_copy), FullGlyphs.copy,
                        { address?.let { clipboard.setText(AnnotatedString(it)) } },
                        Modifier.testTag("dashboard-lan-copy"), enabled = address != null)
                }
            }) { children, constraints ->
                val columnGap = if (vpn && lan) 16.dp.roundToPx() else 0
                val columnWidth = (constraints.maxWidth - columnGap) / if (vpn && lan) 2 else 1
                val loose = constraints.copy(minWidth = 0, minHeight = 0, maxWidth = columnWidth)
                val copy = children[2].measure(loose)
                val left = children[0].measure(loose)
                val right = children[1].measure(loose.copy(maxWidth = (columnWidth - copy.width - 4.dp.roundToPx()).coerceAtLeast(0)))
                val leftBaseline = left[FirstBaseline]
                val rightBaseline = right[FirstBaseline]
                val above = maxOf(leftBaseline, rightBaseline)
                val below = maxOf(left.height - leftBaseline, right.height - rightBaseline)
                val height = maxOf(copy.height, above + below)
                val baseline = (height - above - below) / 2 + above
                val lanX = if (vpn && lan) columnWidth + columnGap else 0
                layout(constraints.maxWidth, height) {
                    if (vpn) left.placeRelative(0, baseline - leftBaseline)
                    if (lan) {
                        right.placeRelative(lanX, baseline - rightBaseline)
                        copy.placeRelative(lanX + columnWidth - copy.width, (height - copy.height) / 2)
                    }
                }
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
