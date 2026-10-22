package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.LogLine
import dev.aerove.lite.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LogsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    val matching by produceState<List<LogLine>>(emptyList(), state.logs, query) {
        value = withContext(Dispatchers.Default) {
            if (query.isBlank()) state.logs else state.logs.filter { it.message.contains(query, true) || it.level.contains(query, true) }
        }
    }
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiteField(query, { query = it }, stringResource(R.string.lite_search), Modifier.weight(1f))
                if (state.logs.isNotEmpty()) LiteIconButton(stringResource(R.string.lite_clear_logs), Icons.Outlined.DeleteOutline, actions::clearLogs, enabled = state.busy.isEmpty())
            }
        }
        if (state.logs.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_logs), stringResource(R.string.lite_no_logs_detail), Icons.Outlined.ReceiptLong)
        } else if (matching.isEmpty()) item { LiteText(stringResource(R.string.lite_no_matches), Modifier.padding(16.dp)) }
        itemsIndexed(matching, key = { index, line -> "${line.time}:$index" }) { _, line ->
            var expanded by rememberSaveable(line.time, line.message) { mutableStateOf(false) }
            LitePane(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiteText(line.level, style = LiteType.Caption, color = LocalLiteVisuals.current.primary)
                        LiteText(formatTime(line.time), style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
                    }
                    SelectionContainer { LiteText(line.message, style = LiteType.Label, maxLines = if (expanded) Int.MAX_VALUE else 5) }
                }
            }
        }
    }
}
