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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
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
import java.util.Locale
import kotlin.math.hypot

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
            items(settings.dashboardWidgets, key = { it.wireName }, contentType = { it }) { widget ->
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
) {
    when (widget) {
        DashboardWidget.CurrentOutbound -> OutboundStatusCard(state, actions)
        DashboardWidget.NetworkSpeed -> NetworkSpeedCard(state)
        DashboardWidget.OutboundMode -> OutboundModeCard(state, actions)
        DashboardWidget.VpnButton -> DashboardVpnCard(state, actions)
        DashboardWidget.IntranetIp -> IntranetCard(state.localAddress)
        DashboardWidget.ServiceStatus -> serviceStatusContent()
        DashboardWidget.Profiles -> ProfilesCard(state, actions, openProfiles)
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
private fun ProfilesCard(state: LiteState, actions: LiteActions, openProfiles: () -> Unit) {
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfile }
    if (profile == null) EmptyContent(stringResource(R.string.lite_profile_required), stringResource(R.string.lite_profile_required_detail), FullGlyphs.profiles) {
        LiteButton(stringResource(R.string.lite_import), actions::importFile, Modifier.fillMaxWidth(), FullGlyphs.importFile, enabled = state.busy.isEmpty())
        LiteButton(stringResource(R.string.lite_tab_profiles), openProfiles, Modifier.fillMaxWidth())
    } else DashboardPane(Modifier.clickable(role = Role.Button, onClick = openProfiles)) {
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

@Composable
private fun OutboundStatusCard(state: LiteState, actions: LiteActions) {
    val visuals = LocalLiteVisuals.current
    val running = state.phase == VpnPhase.Running
    val time = rememberDashboardTime(running)
    val identity = state.outboundIdentity
    val qualityFactory = LocalLiteFeatures.current
    val qualityFeature = if (identity != null || state.outboundLoading) remember(qualityFactory) { qualityFactory().ipQuality } else null
    val qualityState = qualityFeature?.state?.collectAsState()?.value
    val busy = state.phase == VpnPhase.Starting || state.phase == VpnPhase.Stopping || state.busy.isNotEmpty()
    val enabled = !busy && (running || state.selectedProfile != null)
    val status = when (state.phase) {
        VpnPhase.Running -> R.string.lite_running
        VpnPhase.Starting -> R.string.lite_connecting
        VpnPhase.Stopping -> R.string.lite_disconnecting
        VpnPhase.Failed -> R.string.lite_failed
        VpnPhase.Stopped -> R.string.lite_not_running
    }
    LitePane(
        Modifier.fillMaxWidth().testTag("dashboard-status"), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList(),
    ) {
        val decorations = rememberLayerBackdrop()
        val controlsSource = rememberCombinedBackdrop(LocalLiteBackdrop.current, decorations)
        DashboardRibbon(time, Modifier.matchParentSize().layerBackdrop(decorations))
        CompositionLocalProvider(LocalLiteBackdrop provides controlsSource) {
        Row(Modifier.padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                DashboardHeader(FullGlyphs.status, stringResource(R.string.lite_status), leading = { StatusChip(stringResource(status), running) })
                Spacer(Modifier.height(12.dp))
                if (qualityFeature != null) IpQualityStatus(qualityFeature) { quality, open ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CountryBubble(identity?.countryCode ?: quality.countryCode, state.outboundLoading || quality.loading, running, actions::refreshOutbound)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val shown = identity?.address ?: quality.ip?.takeIf { state.outboundLoading && quality.provisional }
                            if (shown != null) LiteText(if (quality.hidden) "*****" else shown,
                                Modifier.clickable(role = Role.Button, onClick = open), style = LiteType.Title2,
                                color = qualityInk(quality.quality?.level), maxLines = 1)
                            else if (state.outboundLoading) LiteText(stringResource(R.string.lite_outbound_loading), style = LiteType.Footnote, color = visuals.secondaryInk, maxLines = 2)
                            else LiteText("—", style = LiteType.Title2)
                            IpQualityChips(quality, open)
                            if (quality.provisional) LiteText(stringResource(R.string.quality_provisional), style = LiteType.Caption,
                                color = visuals.secondaryInk)
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CountryBubble(identity?.countryCode, state.outboundLoading, running, actions::refreshOutbound)
                    LiteText("—", style = LiteType.Title2)
                }
                state.outboundError?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(Modifier.height(8.dp))
                    LiteText(error, style = LiteType.Caption, color = MaterialTheme.colorScheme.error, maxLines = 2)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    StatusCell(stringResource(R.string.lite_uptime), if (running) formatUptime(state.uptimeMillis) else "—")
                    StatusCell(stringResource(R.string.lite_organization), qualityState?.quality?.organization ?: "—", Modifier.weight(1f))
                }
            }
            StartBubble(running, enabled, stringResource(if (running) R.string.action_disconnect else R.string.lite_connect), time,
                if (running) actions::disconnect else actions::connect)
        }
        }
    }
}

@Composable
private fun StatusChip(label: String, running: Boolean) {
    val visuals = LocalLiteVisuals.current
    val ink = if (running) { if (visuals.dark) Color(0xFF86EB81) else Color(0xFF125919) } else visuals.secondaryInk
    LitePane(corner = LiteCorner.Full, solidColor = if (running) { if (visuals.dark) Color(0xFF123719) else Color(0xFF9AF692) } else dashboardTone(),
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
    LitePane(Modifier.size(48.dp).semantics { contentDescription = label; stateDescription = normalized ?: "" }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = running && !loading, role = Role.Button, onClick = refresh), customShape = shape, corner = LiteCorner.Full) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp).align(Alignment.Center), color = LocalLiteVisuals.current.primary, strokeWidth = 2.dp)
        else if (flag == null) LiteIcon(FullGlyphs.proxies, Modifier.align(Alignment.Center), LocalLiteVisuals.current.secondaryInk, size = 24.dp)
        else Box(Modifier.fillMaxSize().drawWithCache {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = size.height }
            val bounds = android.graphics.Rect()
            paint.getTextBounds(flag, 0, flag.length, bounds)
            val diagonal = hypot(bounds.width().toFloat(), bounds.height().toFloat()).coerceAtLeast(1f)
            paint.textSize *= size.minDimension * 0.76f / diagonal
            paint.getTextBounds(flag, 0, flag.length, bounds)
            val x = size.width / 2 - bounds.exactCenterX()
            val y = size.height / 2 - bounds.exactCenterY()
            onDrawBehind { drawIntoCanvas { it.nativeCanvas.drawText(flag, x, y, paint) } }
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

@Composable
private fun StartBubble(running: Boolean, enabled: Boolean, label: String, time: State<Float>, onClick: () -> Unit) {
    val visuals = LocalLiteVisuals.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by animateFloatAsState(if (running) 1f else 0f, spring(dampingRatio = 0.558f, stiffness = 260f), label = "startMorph")
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 120 else 500), label = "startPress")
    val clock = time.value
    val outline = remember(progress, clock, press) { DashboardBubbleOutline(progress, clock, 0.55f * progress, press + progress * (1 - progress) * 2.4f) }
    val glyph = remember(progress) { FullGlyphs.playPause(progress) }
    val solid = if (visuals.aurora) { if (visuals.dark) Color.White else Color.Black } else visuals.primary
    LitePane(
        Modifier.size(88.dp).alpha(if (enabled) 1f else 0.48f).semantics { contentDescription = label }
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
                    Modifier.weight(1f).height(52.dp).selectable(selected, interactionSource = interaction, indication = null,
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
}

@Composable
private fun DashboardVpnCard(state: LiteState, actions: LiteActions) {
    val running = state.phase == VpnPhase.Running
    val enabled = state.phase != VpnPhase.Starting && state.phase != VpnPhase.Stopping && state.busy.isEmpty() && (running || state.selectedProfile != null)
    val visuals = LocalLiteVisuals.current
    LitePane(Modifier.fillMaxWidth().testTag("dashboard-vpn"), corner = LiteCorner.Dashboard,
        glassTint = if (visuals.material == MaterialTier.Frosted) visuals.ink.copy(alpha = 0.06f) else Color.Transparent,
        shadows = if (visuals.material == MaterialTier.Solid) fullDashboardShadows(visuals.dark) else emptyList()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LitePane(Modifier.size(48.dp), corner = LiteCorner.Full, solidColor = if (running && visuals.material == MaterialTier.Solid) visuals.primary else dashboardTone()) {
                LiteIcon(FullGlyphs.vpn, Modifier.align(Alignment.Center), if (running && visuals.material == MaterialTier.Solid) Color.White else visuals.secondaryInk, size = 22.dp)
            }
            Column(Modifier.weight(1f)) {
                LiteText(stringResource(R.string.lite_vpn), style = LiteType.Callout.copy(fontWeight = FontWeight.SemiBold))
                LiteText(stringResource(if (running) R.string.lite_enabled else R.string.lite_disabled), style = LiteType.Footnote, color = visuals.secondaryInk)
            }
            LiteSwitch(running, stringResource(R.string.lite_vpn), { if (it) actions.connect() else actions.disconnect() }, enabled)
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
