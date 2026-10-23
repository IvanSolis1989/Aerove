package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.observability.*

internal class HistoryTableUiState(val columns: List<HistoryColumn>, val widths: List<Double>,
    val start: () -> Unit, val drag: (Int, Double) -> Unit, val end: () -> Unit, val reset: () -> Unit)

@Composable
internal fun rememberHistoryTable(tableId: String, columns: List<HistoryColumn>, width: Double,
    preferences: HistoryTablePreferences, onChange: (HistoryTableChange) -> Unit): HistoryTableUiState {
    var draft by remember(tableId) { mutableStateOf<Map<String, Double>?>(null) }
    var edited by remember(tableId) { mutableStateOf(emptySet<String>()) }
    val actual = preferences.widths[tableId].orEmpty()
    val overrides = draft ?: actual
    val widths = historyColumnWidths(columns, width, overrides)
    val latestChange by rememberUpdatedState(onChange)
    val state = HistoryTableUiState(columns, widths,
        start = { draft = actual; edited = emptySet() },
        drag = { boundary, delta ->
            val before = draft ?: actual
            val current = historyColumnWidths(columns, width, before)
            val next = resizeHistoryColumns(columns, current, before, boundary, delta)
            edited = edited + next.keys.filter { next[it] != before[it] }
            draft = next
        },
        end = {
            val changed = draft.orEmpty().filterKeys { it in edited }
            draft = null; edited = emptySet()
            if (changed.isNotEmpty()) latestChange(HistoryTableChange(tableId, changed))
        },
        reset = { draft = null; edited = emptySet(); latestChange(HistoryTableChange(tableId, reset = true)) })
    return state
}

@Composable
internal fun HistoryTableHeader(tableId: String, table: HistoryTableUiState, label: @Composable (String) -> String,
    sorted: String?, descending: Boolean, onSort: (String) -> Unit) {
    val density = LocalDensity.current
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val latest by rememberUpdatedState(table)
    val height = (16 * density.fontScale + 16).dp
    LitePane(Modifier.fillMaxWidth()) {
        Box(Modifier.height(height)) {
            Row {
                table.columns.forEachIndexed { index, column ->
                    Box(
                        Modifier.width(table.widths[index].dp).fillMaxHeight().wrapContentHeight()
                            .testTag("history-sort-$tableId-${column.id}").clickable { onSort(column.id) }.padding(horizontal = 8.dp),
                        contentAlignment = if (column.end) Alignment.CenterEnd else Alignment.CenterStart) {
                        LiteText(label(column.id) + if (sorted == column.id) (if (descending) " ↓" else " ↑") else "",
                            style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                    }
                }
            }
            var edge = 0.0
            table.columns.dropLast(1).forEachIndexed { boundary, column ->
                edge += table.widths[boundary]
                val description = stringResource(R.string.history_resize_column, label(column.id))
                Box(Modifier.offset(x = (edge - 6).dp).width(12.dp).fillMaxHeight()
                    .testTag("history-resize-$tableId-${column.id}").semantics { contentDescription = description }
                    .pointerInput(tableId, column.id, density.density, direction) {
                        detectHorizontalDragGestures(onDragStart = { latest.start() }, onDragEnd = { latest.end() },
                            onDragCancel = { latest.end() }) { change, amount ->
                            change.consume(); latest.drag(boundary, (amount / density.density * direction).toDouble())
                        }
                    }.pointerInput(tableId, column.id) { detectTapGestures(onDoubleTap = { latest.reset() }) })
            }
        }
    }
}

@Composable
internal fun HistoryTableCells(table: HistoryTableUiState, values: List<String>, colors: Map<String, Color> = emptyMap(),
    clicks: Map<String, () -> Unit> = emptyMap()) {
    val height = (20 * LocalDensity.current.fontScale + 16).dp
    Row {
        values.forEachIndexed { index, value ->
            val column = table.columns[index]
            val click = clicks[column.id]
            Box(Modifier.width(table.widths[index].dp).heightIn(min = height).then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
                .padding(horizontal = 8.dp), contentAlignment = if (column.end) Alignment.CenterEnd else Alignment.CenterStart) {
                LiteText(value, style = LiteType.Caption, maxLines = 1,
                    color = colors[column.id] ?: if (click != null) LocalLiteVisuals.current.primary else LocalLiteVisuals.current.ink)
            }
        }
    }
}

@Composable
internal fun HistoryTableBody(table: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (table) LitePane(modifier) { content() } else Box(modifier) { content() }
}

@Composable
internal fun HistoryRecordContainer(table: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    if (table) Box(modifier) { content() } else LitePane(modifier) { content() }
}
