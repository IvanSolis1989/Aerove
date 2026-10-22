package dev.aerove.lite.ui

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.aerove.lite.ConnectionInfo
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R
import dev.aerove.lite.connections.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ConnectionsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf(ConnectionSort.Activity) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val projector = remember { ConnectionProjector() }
    val projection by produceState(ConnectionProjection(), state.connections, query, filters, sort, descending) {
        value = withContext(Dispatchers.Default) {
            projector.project(state.connections, SystemClock.elapsedRealtime(), query, filters.toSet(), sort, descending)
        }
    }
    val overlay = LocalLiteOverlay.current
    val latest by rememberUpdatedState(state)
    val latestActions by rememberUpdatedState(actions)
    val latestProjection by rememberUpdatedState(projection)
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteText(stringResource(R.string.lite_connection_count, state.connections.size), style = LiteType.Headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LiteText("↓ ${formatBytes(state.traffic.downloadPerSecond)}/s", Modifier.weight(1f), style = LiteType.Label)
                        LiteText("↑ ${formatBytes(state.traffic.uploadPerSecond)}/s", Modifier.weight(1f), style = LiteType.Label)
                    }
                    LiteField(query, { query = it }, stringResource(R.string.lite_search), Modifier.testTag("connections-search"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LiteButton(stringResource(R.string.connections_sort), {
                            overlay.content = {
                                LiteModal(stringResource(R.string.connections_sort), overlay::dismiss) {
                                    ConnectionSort.entries.forEach { option ->
                                        SettingsRow(sortLabel(option), onClick = {
                                            if (option != sort) descending = option in setOf(ConnectionSort.DownloadSpeed,
                                                ConnectionSort.UploadSpeed, ConnectionSort.Download, ConnectionSort.Upload,
                                                ConnectionSort.Traffic, ConnectionSort.Started)
                                            sort = option; overlay.dismiss()
                                        }) {
                                            if (sort == option) LiteIcon(FullGlyphs.check)
                                        }
                                    }
                                    LiteChoiceRow(listOf(false, true), descending,
                                        { stringResource(if (it) R.string.connections_descending else R.string.connections_ascending) },
                                        { descending = it })
                                }
                            }
                        }, Modifier.weight(1f), FullGlyphs.speed)
                        LiteButton(stringResource(R.string.connections_filter), {
                            overlay.content = {
                                ConnectionFilterDialog(latestProjection.keywords, filters, { filters = it }, overlay::dismiss)
                            }
                        }, Modifier.weight(1f), FullGlyphs.settings)
                    }
                    if (filters.isNotEmpty()) LiteText(filters.joinToString(" · "), style = LiteType.Caption)
                    if (state.connections.isNotEmpty()) LiteButton(
                        stringResource(R.string.lite_close_all), actions::closeAllConnections,
                        Modifier.fillMaxWidth().testTag("connections-close-all"), FullGlyphs.close,
                        enabled = state.busy.isEmpty(), destructive = true)
                }
            }
        }
        if (state.connections.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_connections), stringResource(R.string.lite_no_connections_detail), FullGlyphs.connections)
        } else if (projection.rows.isEmpty()) item { LiteText(stringResource(R.string.lite_no_matches), Modifier.padding(16.dp)) }
        items(projection.rows, key = { it.connection.id }) { row ->
            val connection = row.connection
            LitePane(Modifier.fillMaxWidth().testTag("connection-${connection.id}").clickable(role = Role.Button) {
                overlay.content = {
                    val current = latest.connections.find { it.id == connection.id }
                    var lastObserved by remember(connection.id) { mutableStateOf(connection) }
                    SideEffect { if (current != null) lastObserved = current }
                    LiteModal(stringResource(R.string.connections_details), overlay::dismiss) {
                        ConnectionDetails(current ?: lastObserved)
                        if (current == null) LiteText(stringResource(R.string.connections_ended), style = LiteType.Caption)
                        LiteButton(stringResource(R.string.lite_close_connection), { latestActions.closeConnection(connection.id) },
                            Modifier.fillMaxWidth().testTag("connection-detail-close"), FullGlyphs.close,
                            enabled = current != null && latest.busy.isEmpty(), destructive = true)
                    }
                }
            }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteText(connection.destination.ifBlank { stringResource(R.string.connections_unknown) }, style = LiteType.Headline)
                    connection.process?.takeIf(String::isNotBlank)?.let { LiteText(it, style = LiteType.Caption) }
                    LiteText(listOf(connection.network, connection.rule, connection.rulePayload.orEmpty()).filter(String::isNotBlank).joinToString(" · "), style = LiteType.Caption)
                    if (connection.chains.isNotEmpty()) LiteText(connection.chains.asReversed().joinToString(" → "), style = LiteType.Caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f)) {
                            LiteText("↓ ${amount(connection.download)}", style = LiteType.Caption)
                            LiteText(stringResource(R.string.lite_rate, amount(row.downloadSpeed)), style = LiteType.Caption)
                        }
                        Column(Modifier.weight(1f)) {
                            LiteText("↑ ${amount(connection.upload)}", style = LiteType.Caption)
                            LiteText(stringResource(R.string.lite_rate, amount(row.uploadSpeed)), style = LiteType.Caption)
                        }
                    }
                    LiteIconButton(stringResource(R.string.lite_close_connection), FullGlyphs.close,
                        { actions.closeConnection(connection.id) }, Modifier.testTag("connection-close-${connection.id}"), enabled = state.busy.isEmpty())
                }
            }
        }
    }
}

@Composable
private fun ConnectionFilterDialog(keywords: List<String>, selected: List<String>, onChanged: (List<String>) -> Unit, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val choices by produceState(emptyList<String>(), keywords, query) {
        value = withContext(Dispatchers.Default) { keywords.asSequence().filter { it.contains(query, true) }.take(30).toList() }
    }
    LiteModal(stringResource(R.string.connections_filter), close) {
        LiteText(stringResource(R.string.connections_filter_hint), style = LiteType.Caption)
        LiteField(query, { query = it }, stringResource(R.string.connections_find_keyword))
        if (keywords.size > 30) LiteText(stringResource(R.string.connections_refine), style = LiteType.Caption)
        choices.forEach { keyword ->
            SettingsRow(keyword, onClick = { onChanged(if (keyword in selected) selected - keyword else selected + keyword) }) {
                if (keyword in selected) LiteIcon(FullGlyphs.check)
            }
        }
        LiteButton(stringResource(R.string.connections_clear_filters), { onChanged(emptyList()); close() })
    }
}

@Composable
private fun amount(bytes: Long?): String = bytes?.let(::formatBytes) ?: stringResource(R.string.connections_unknown)

@Composable
private fun ConnectionDetails(connection: ConnectionInfo) {
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    val entries = listOf(
        R.string.connections_id to connection.id,
        R.string.connections_started to connection.startedAt?.let(formatter::format),
        R.string.lite_network to connection.network,
        R.string.connections_inbound to connection.inboundType,
        R.string.connections_process to connection.process,
        R.string.connections_process_path to connection.processPath,
        R.string.connections_uid to connection.uid?.toString(),
        R.string.connections_user to connection.inboundUser,
        R.string.lite_rule to listOfNotNull(connection.rule.takeIf(String::isNotBlank), connection.rulePayload?.takeIf(String::isNotBlank)).joinToString(" · "),
        R.string.connections_host to connection.host,
        R.string.connections_source to connectionEndpoint(connection.sourceIP, connection.sourcePort),
        R.string.connections_target to connectionEndpoint(connection.destinationIP, connection.destinationPort),
        R.string.connections_country to connection.country,
        R.string.connections_asn to connection.asn,
        R.string.lite_chain to connection.chains.asReversed().joinToString(" → "),
        R.string.lite_download to connection.download?.let(::formatBytes),
        R.string.lite_upload to connection.upload?.let(::formatBytes),
    )
    entries.forEach { (label, value) -> if (!value.isNullOrBlank()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiteText(stringResource(label), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        SelectionContainer { LiteText(value, style = LiteType.Label) }
    } }
}

@Composable
private fun sortLabel(sort: ConnectionSort): String = stringResource(when (sort) {
    ConnectionSort.Activity -> R.string.connections_activity
    ConnectionSort.Process -> R.string.connections_process
    ConnectionSort.Target -> R.string.connections_target
    ConnectionSort.Network -> R.string.lite_network
    ConnectionSort.Rule -> R.string.lite_rule
    ConnectionSort.Exit -> R.string.lite_chain
    ConnectionSort.DownloadSpeed -> R.string.connections_download_speed
    ConnectionSort.UploadSpeed -> R.string.connections_upload_speed
    ConnectionSort.Download -> R.string.lite_download
    ConnectionSort.Upload -> R.string.lite_upload
    ConnectionSort.Traffic -> R.string.connections_traffic
    ConnectionSort.Started -> R.string.connections_started
})
