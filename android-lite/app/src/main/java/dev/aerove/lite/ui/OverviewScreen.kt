package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.R
import dev.aerove.lite.VpnPhase

@Composable
internal fun OverviewScreen(
    state: LiteState,
    actions: LiteActions,
    padding: PaddingValues,
    openProfiles: () -> Unit,
    openLogs: () -> Unit,
) {
    val visuals = LocalLiteVisuals.current
    val profile = state.profiles.firstOrNull { it.id == state.selectedProfile }
    val status = when (state.phase) {
        VpnPhase.Stopped -> R.string.lite_disconnected
        VpnPhase.Starting -> R.string.lite_connecting
        VpnPhase.Running -> R.string.lite_connected
        VpnPhase.Stopping -> R.string.lite_disconnecting
        VpnPhase.Failed -> R.string.lite_failed
    }
    val transitioning = state.phase == VpnPhase.Starting || state.phase == VpnPhase.Stopping
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    LiteIcon(
                        Icons.Outlined.PowerSettingsNew,
                        color = if (state.phase == VpnPhase.Failed) androidx.compose.material3.MaterialTheme.colorScheme.error else visuals.primary,
                    )
                    LiteText(stringResource(status), style = LiteType.Title)
                    if (profile != null) {
                        LiteText(profile.name, color = visuals.secondaryInk)
                    } else {
                        LiteText(stringResource(R.string.lite_profile_required), color = visuals.secondaryInk)
                    }
                    LiteButton(
                        label = stringResource(if (state.phase == VpnPhase.Running) R.string.action_disconnect else if (transitioning) status else R.string.lite_connect),
                        onClick = if (state.phase == VpnPhase.Running) actions::disconnect else actions::connect,
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Outlined.PowerSettingsNew,
                        selected = true,
                        enabled = !transitioning && state.busy.isEmpty() && (profile != null || state.phase == VpnPhase.Running),
                    )
                }
            }
        }
        if (profile == null) item {
            EmptyContent(
                stringResource(R.string.lite_profile_required),
                stringResource(R.string.lite_profile_required_detail),
                Icons.Outlined.FolderOpen,
            ) {
                LiteButton(stringResource(R.string.lite_import), actions::importFile, Modifier.fillMaxWidth(), Icons.Outlined.FileDownload, enabled = state.busy.isEmpty())
                LiteButton(stringResource(R.string.lite_tab_profiles), openProfiles, Modifier.fillMaxWidth())
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiteText(stringResource(R.string.lite_traffic), style = LiteType.Headline)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TrafficMetric(
                            stringResource(R.string.lite_download),
                            stringResource(R.string.lite_rate, formatBytes(state.traffic.downloadPerSecond)),
                            Icons.Outlined.ArrowDownward,
                            Modifier.weight(1f),
                        )
                        TrafficMetric(
                            stringResource(R.string.lite_upload),
                            stringResource(R.string.lite_rate, formatBytes(state.traffic.uploadPerSecond)),
                            Icons.Outlined.ArrowUpward,
                            Modifier.weight(1f),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            LiteText(stringResource(R.string.lite_download_total), style = LiteType.Caption, color = visuals.secondaryInk)
                            LiteText(formatBytes(state.traffic.download), style = LiteType.Label)
                        }
                        Column(Modifier.weight(1f)) {
                            LiteText(stringResource(R.string.lite_upload_total), style = LiteType.Caption, color = visuals.secondaryInk)
                            LiteText(formatBytes(state.traffic.upload), style = LiteType.Label)
                        }
                    }
                    LiteText(stringResource(R.string.lite_connection_count, state.connections.size), style = LiteType.Caption, color = visuals.secondaryInk)
                }
            }
        }
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiteText(stringResource(R.string.lite_proxy_mode), style = LiteType.Headline)
                    LiteChoiceRow(
                        ProxyMode.entries,
                        state.settings.mode,
                        label = { proxyModeLabel(it) },
                        onSelect = { actions.changeSettings(state.settings.copy(mode = it)) },
                        enabled = state.busy.isEmpty(),
                    )
                }
            }
        }
        item {
            LiteButton(stringResource(R.string.lite_logs), openLogs, Modifier.fillMaxWidth(), Icons.Outlined.ReceiptLong)
        }
    }
}

@Composable
private fun TrafficMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiteIcon(icon, color = LocalLiteVisuals.current.primary)
            LiteText(label, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        }
        LiteText(value, style = LiteType.Headline, maxLines = 1)
    }
}

@Composable
internal fun proxyModeLabel(mode: ProxyMode): String = stringResource(when (mode) {
    ProxyMode.Rule -> R.string.lite_mode_rule
    ProxyMode.Global -> R.string.lite_mode_global
    ProxyMode.Direct -> R.string.lite_mode_direct
})
