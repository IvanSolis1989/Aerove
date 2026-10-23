package dev.aerove.lite.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aerove.lite.LiteActions
import dev.aerove.lite.LiteState
import dev.aerove.lite.LogLine
import dev.aerove.lite.R
import dev.aerove.lite.observability.*
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LogsScreen(feature: ObservabilityFeature, padding: PaddingValues) {
    HistoryVisibility(feature, HistoryKind.Logs)
    val state by feature.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var levels by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var following by rememberSaveable { mutableStateOf(true) }
    val anchor = remember(feature, state.snapshot.sessionRevision) { HistoryAnchor<HistoryLogEntry>(5000) { it.id } }
    val rows by produceState(emptyList<HistoryLogEntry>(), anchor, state.snapshot.logs, query, levels, following) {
        value = withContext(Dispatchers.Default) { projectLogs(anchor.update(state.snapshot.logs, following), query, levels.toSet()) }
    }
    val list = rememberLazyListState()
    HistoryFollow(list, rows.firstOrNull()?.id, following) { following = false }
    HistoryLogDocumentHost(feature)
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HistoryControls(query, { query = it }, levels, state.snapshot.logs.map { it.level.lowercase(java.util.Locale.ROOT) }.distinct().sorted(),
            following, { following = !following }, rows.size, onFilters = { levels = it })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LiteButton(stringResource(R.string.history_export_logs), feature::requestLogExport,
                Modifier.weight(1f).testTag("history-log-export"), FullGlyphs.arrowUp, enabled = !state.exporting && state.document == null)
            LiteIconButton(stringResource(R.string.lite_clear_logs), FullGlyphs.delete, { feature.clear(HistoryKind.Logs) },
                Modifier.testTag("history-log-clear"))
            if (state.exporting || state.document != null) LiteButton(stringResource(R.string.lite_cancel), feature::cancelExport)
        }
        HistoryNoticeView(state, feature)
        if (copied) LiteText(stringResource(R.string.history_copied), style = LiteType.Caption)
        if (rows.isEmpty()) HistoryEmpty(query.isNotBlank() || levels.isNotEmpty())
        else LazyColumn(Modifier.weight(1f), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(rows, key = { it.id }) { line ->
                var expanded by rememberSaveable(line.id) { mutableStateOf(false) }
                LitePane(Modifier.fillMaxWidth().testTag("history-log-${line.id}").clickable { expanded = !expanded }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LiteText(historyTime(java.time.Instant.ofEpochMilli(line.time)), style = LiteType.Caption)
                            val color = when (line.level.lowercase(java.util.Locale.ROOT)) {
                                "error" -> MaterialTheme.colorScheme.error
                                "warn", "warning" -> LocalLiteVisuals.current.primary
                                else -> LocalLiteVisuals.current.secondaryInk
                            }
                            LiteText(line.level, Modifier.clickable { levels = (levels + line.level.lowercase(java.util.Locale.ROOT)).distinct() }, style = LiteType.Caption, color = color)
                        }
                        SelectionContainer { LiteText(line.message, style = LiteType.Label, maxLines = if (expanded) Int.MAX_VALUE else 5) }
                        if (expanded) LiteButton(stringResource(R.string.history_copy), {
                            clipboard.setText(AnnotatedString(line.message)); copied = true
                        }, icon = FullGlyphs.copy)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryLogDocumentHost(feature: ObservabilityFeature) {
    val state by feature.state.collectAsStateWithLifecycle()
    val active = LocalLitePageActive.current
    val currentActive by rememberUpdatedState(active)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var requestId by rememberSaveable { mutableLongStateOf(0L) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (!currentActive) feature.cancelExport()
        feature.documentResult(requestId, uri?.toString()); requestId = 0L
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(feature, active, resumed, state.document?.id) {
        if (!active) { feature.cancelExport(); return@LaunchedEffect }
        if (!resumed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        val request = state.document ?: return@LaunchedEffect
        if (!request.dispatched && requestId != 0L) { feature.cancelExport(); return@LaunchedEffect }
        if (feature.documentLaunched(request.id)) try {
            requestId = request.id; launcher.launch(request.name)
        } catch (_: RuntimeException) { feature.documentResult(request.id, null); requestId = 0L }
    }
    val context = LocalContext.current
    val activity = remember(context) {
        var item = context
        while (item is ContextWrapper && item !is Activity) item = item.baseContext
        item as? Activity
    }
    DisposableEffect(feature, activity) { onDispose { if (activity?.isChangingConfigurations != true) feature.cancelExport() } }
}

@Composable
internal fun LogsScreen(state: LiteState, actions: LiteActions, padding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    val matching by produceState<List<LogLine>>(emptyList(), state.logs, query) {
        value = withContext(Dispatchers.Default) {
            if (query.isBlank()) state.logs else state.logs.filter { it.message.contains(query, true) || it.level.contains(query, true) }
        }
    }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LiteField(query, { query = it }, stringResource(R.string.lite_search), Modifier.weight(1f))
                if (state.logs.isNotEmpty()) LiteIconButton(stringResource(R.string.lite_clear_logs), FullGlyphs.delete, actions::clearLogs, enabled = state.busy.isEmpty())
            }
        }
        if (state.logs.isEmpty()) item {
            EmptyContent(stringResource(R.string.lite_no_logs), stringResource(R.string.lite_no_logs_detail), FullGlyphs.logs)
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
