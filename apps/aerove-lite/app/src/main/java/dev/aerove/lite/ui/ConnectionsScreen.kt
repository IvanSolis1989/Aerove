package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.R

@Composable
internal fun ConnectionsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LiteText(stringResource(R.string.lite_connection_count, state.connections.size), style = LiteType.Headline)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            LiteText(stringResource(R.string.lite_download), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                            LiteText(stringResource(R.string.lite_rate, formatBytes(state.traffic.downloadPerSecond)), style = LiteType.Label)
                        }
                        Column(Modifier.weight(1f)) {
                            LiteText(stringResource(R.string.lite_upload), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                            LiteText(stringResource(R.string.lite_rate, formatBytes(state.traffic.uploadPerSecond)), style = LiteType.Label)
                        }
                    }
                    if (state.connections.isNotEmpty()) LiteButton(
                        stringResource(R.string.lite_close_all), actions::closeAllConnections,
                        Modifier.fillMaxWidth(), FullGlyphs.close,
                        enabled = state.busy.isEmpty(), destructive = true,
                    )
                }
            }
        }
        if (state.connections.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_connections), stringResource(R.string.lite_no_connections_detail), FullGlyphs.connections)
        }
        items(state.connections, key = { it.id }) { connection ->
            LitePane(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SelectionContainer(Modifier.weight(1f)) { LiteText(connection.destination, style = LiteType.Headline) }
                        LiteIconButton(
                            stringResource(R.string.lite_close_connection), FullGlyphs.close,
                            { actions.closeConnection(connection.id) }, enabled = state.busy.isEmpty(),
                        )
                    }
                    if (connection.network.isNotBlank()) ConnectionDetail(stringResource(R.string.lite_network), connection.network)
                    if (connection.rule.isNotBlank()) ConnectionDetail(stringResource(R.string.lite_rule), connection.rule)
                    if (connection.chains.isNotEmpty()) ConnectionDetail(stringResource(R.string.lite_chain), connection.chains.joinToString(" → "))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        LiteText("↓ ${formatBytes(connection.download)}", style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                        LiteText("↑ ${formatBytes(connection.upload)}", style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiteText(label, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        SelectionContainer { LiteText(value, style = LiteType.Label) }
    }
}
