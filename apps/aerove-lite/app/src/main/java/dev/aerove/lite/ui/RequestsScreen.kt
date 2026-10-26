package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.aerove.lite.R
import dev.aerove.lite.observability.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@Composable
internal fun RequestsScreen(feature: ObservabilityFeature, padding: PaddingValues, tables: HistoryTablePreferences,
    onTableChange: (HistoryTableChange) -> Unit) {
    HistoryVisibility(feature, HistoryKind.Requests)
    val state by rememberHistoryState(feature)
    val active = LocalLitePageActive.current
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf<RequestHistorySort?>(null) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var following by rememberSaveable { mutableStateOf(true) }
    fun selectSort(option: RequestHistorySort) {
        val first = option in setOf(RequestHistorySort.Time, RequestHistorySort.Traffic, RequestHistorySort.Upload, RequestHistorySort.Download)
        val next = nextHistorySort(sort?.let { HistorySortSelection(it.columnId, descending) }, option.columnId, first)
        sort = next?.let { selection -> RequestHistorySort.entries.first { it.columnId == selection.columnId } }
        next?.let { descending = it.descending }
    }
    val anchor = remember(feature, state.snapshot.sessionRevision) { HistoryAnchor<RequestHistoryEntry>(2000) { it.id } }
    val projection by key(state.snapshot.sessionRevision) { produceState(HistoryProjection<RequestHistoryEntry>(emptyList(), emptyList()), anchor, state.snapshot.requestRevision, active, query, filters, sort, descending, following) {
        if (active) value = withContext(Dispatchers.Default) {
            val context = coroutineContext
            projectRequests(anchor.update(state.snapshot.requests, following), query, filters.toSet(), sort, descending, checkActive = { context.ensureActive() })
        }
    } }
    val list = rememberLazyListState()
    HistoryFollow(list, projection.rows.firstOrNull()?.id, following) { following = false }
    val overlay = LocalLiteOverlay.current
    val measure = rememberTextMeasurer()
    val protocolWidth = kotlin.math.ceil(measure.measure(AnnotatedString(requestSortLabel(RequestHistorySort.Protocol)), style = LiteType.Caption).size.width / LocalDensity.current.density.toDouble()) + 16
    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HistoryControls(query, { query = it }, filters, projection.keywords, following, { following = !following }, projection.rows.size,
            sort = { overlay.content = {
                LiteModal(stringResource(R.string.history_sort), overlay::dismiss) {
                    RequestHistorySort.entries.forEach { option -> SettingsRow(requestSortLabel(option), onClick = {
                        selectSort(option)
                        overlay.dismiss()
                    }) { if (sort == option) LiteIcon(if (descending) FullGlyphs.arrowDown else FullGlyphs.arrowUp) } }
                }
            } }, onFilters = { filters = it })
        HistoryNoticeView(state, feature)
        if (projection.rows.isEmpty()) HistoryEmpty(query.isNotBlank() || filters.isNotEmpty())
        else BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 1000.dp
            val isTable = maxWidth >= 720.dp
            val columns = requestHistoryColumns(wide, protocolWidth)
            val table = rememberHistoryTable("requests", columns, maxWidth.value.toDouble(), tables, onTableChange)
            Column {
            if (isTable) HistoryTableHeader("requests", table,
                { id -> requestSortLabel(RequestHistorySort.entries.first { it.columnId == id }) },
                sort?.columnId, descending, { id -> selectSort(RequestHistorySort.entries.first { it.columnId == id }) })
            HistoryTableBody(isTable, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(if (isTable) 0.dp else 10.dp)) {
                items(projection.rows, key = { it.id }) { entry ->
                    HistoryRecordContainer(isTable, Modifier.fillMaxWidth().testTag("history-request-${entry.id}").clickable {
                        overlay.content = { LiteModal(stringResource(R.string.history_request_details), overlay::dismiss) { RequestHistoryDetails(entry) } }
                    }) {
                        if (isTable) HistoryTableCells(table, columns.map { column -> when (column.id) {
                            "time" -> historyTime(entry.time)
                            "process" -> entry.process
                            "target" -> entry.target
                            "protocol" -> entry.network.uppercase(java.util.Locale.ROOT)
                            "rule" -> listOf(entry.rule, entry.rulePayload).filter(String::isNotEmpty).joinToString(" · ")
                            "exit" -> entry.chains.firstOrNull().orEmpty()
                            "download" -> historyAmount(entry.download)
                            "upload" -> historyAmount(entry.upload)
                            else -> "↓ ${historyAmount(entry.download)}  ↑ ${historyAmount(entry.upload)}"
                        } }, clicks = buildMap {
                            if (entry.process.isNotEmpty()) put("process", { filters = (filters + entry.process).distinct() })
                            entry.chains.firstOrNull()?.let { value -> put("exit", { filters = (filters + value).distinct() }) }
                        }) else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiteText(historyTime(entry.time), style = LiteType.Caption)
                            LiteText(entry.target, style = LiteType.Headline)
                            if (entry.process.isNotEmpty()) HistoryKeyword(entry.process) { filters = (filters + it).distinct() }
                            LiteText(listOf(entry.network, entry.rule, entry.rulePayload).filter(String::isNotEmpty).joinToString(" · "), style = LiteType.Caption)
                            if (entry.chains.isNotEmpty()) HistoryKeyword(entry.chains.first()) { filters = (filters + it).distinct() }
                            LiteText("↓ ${historyAmount(entry.download)}  ↑ ${historyAmount(entry.upload)}", style = LiteType.Caption)
                        }
                    }
                }
            }
            }
            }
        }
    }
}

/** A retained page does not collect presentation changes while another route covers it. */
@Composable
internal fun rememberHistoryState(feature: ObservabilityFeature): State<ObservabilityState> {
    val active = LocalLitePageActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(feature) { mutableStateOf(feature.state.value) }
    LaunchedEffect(feature, active, lifecycle) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            feature.state.collect { state.value = it }
        }
    }
    return state
}

@Composable
internal fun HistoryVisibility(feature: ObservabilityFeature, kind: HistoryKind) {
    val active = LocalLitePageActive.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(feature, kind, active, lifecycle) {
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { feature.lease(kind).use { awaitCancellation() } }
    }
}

@Composable
internal fun HistoryFollow(list: LazyListState, newest: Any?, following: Boolean, pause: () -> Unit) {
    val active = LocalLitePageActive.current
    val latestPause by rememberUpdatedState(pause)
    LaunchedEffect(active, list) {
        if (active) snapshotFlow { list.isScrollInProgress && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) }
            .distinctUntilChanged().collect { if (it) latestPause() }
    }
    LaunchedEffect(newest, following, active) { if (active && following && newest != null) list.scrollToItem(0) }
}

@Composable
internal fun HistoryControls(query: String, onQuery: (String) -> Unit, filters: List<String>, keywords: List<String>, following: Boolean,
    toggleFollow: () -> Unit, count: Int, sort: (() -> Unit)? = null, keywordLabels: Map<String, String> = emptyMap(), onFilters: (List<String>) -> Unit) {
    val overlay = LocalLiteOverlay.current
    LitePane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LiteText(stringResource(R.string.history_count, count), style = LiteType.Caption)
            LiteField(query, onQuery, stringResource(R.string.lite_search), Modifier.testTag("history-search"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sort?.let { LiteButton(stringResource(R.string.history_sort), it, Modifier.weight(1f), FullGlyphs.speed) }
                LiteButton(stringResource(R.string.history_filter), { overlay.content = {
                    var find by rememberSaveable { mutableStateOf("") }
                    val shown = keywords.filter { it.contains(find, true) || keywordLabels[it]?.contains(find, true) == true }.take(30)
                    LiteModal(stringResource(R.string.history_filter), overlay::dismiss) {
                        LiteText(stringResource(R.string.history_filter_hint), style = LiteType.Caption)
                        LiteField(find, { find = it }, stringResource(R.string.lite_search))
                        shown.forEach { keyword -> SettingsRow(keywordLabels[keyword] ?: keyword, onClick = {
                            onFilters(if (keyword in filters) filters - keyword else filters + keyword)
                            overlay.dismiss()
                        }) { if (keyword in filters) LiteIcon(FullGlyphs.check) } }
                        LiteButton(stringResource(R.string.history_clear_filters), { onFilters(emptyList()); overlay.dismiss() })
                    }
                } }, Modifier.weight(1f), FullGlyphs.settings)
                LiteButton(stringResource(if (following) R.string.history_follow else R.string.history_paused), toggleFollow,
                    Modifier.weight(1f).testTag("history-follow"), selected = following)
            }
            if (filters.isNotEmpty()) LiteText(filters.joinToString(" · ") { keywordLabels[it] ?: it }, style = LiteType.Caption)
        }
    }
}

@Composable
internal fun HistoryEmpty(searching: Boolean) {
    if (searching) LiteText(stringResource(R.string.lite_no_matches), Modifier.padding(16.dp))
    else EmptyContent(stringResource(R.string.history_empty), stringResource(R.string.history_empty_detail), FullGlyphs.connections)
}

@Composable
internal fun HistoryNoticeView(state: ObservabilityState, feature: ObservabilityFeature) {
    if (state.snapshot.truncated) LiteText(stringResource(R.string.history_truncated), style = LiteType.Footnote)
    state.notice?.let { notice -> LitePane(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LiteText(stringResource(when (notice) {
                HistoryNotice.Exported -> R.string.history_exported
                HistoryNotice.Cancelled -> R.string.history_cancelled
                HistoryNotice.Failed -> R.string.history_failed
                HistoryNotice.PartialDocument -> R.string.history_partial_document
                HistoryNotice.DnsCacheCleared -> R.string.history_dns_cache_cleared
            }))
            LiteButton(stringResource(R.string.lite_dismiss), feature::dismissNotice)
        }
    } }
}

@Composable
internal fun HistoryKeyword(value: String, click: (String) -> Unit) {
    LiteText(value, Modifier.clickable { click(value) }, style = LiteType.Caption, color = LocalLiteVisuals.current.primary)
}

@Composable
internal fun HistoryDetailRows(entries: List<Pair<String, String>>, copyText: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    entries.filter { it.second.isNotEmpty() }.forEach { (label, value) -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LiteText(label, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk)
        SelectionContainer { LiteText(value, Modifier.clickable { clipboard.setText(AnnotatedString(value)); copied = true }, style = LiteType.Label) }
    } }
    LiteButton(stringResource(R.string.history_copy), { clipboard.setText(AnnotatedString(copyText)); copied = true }, Modifier.fillMaxWidth(), FullGlyphs.copy)
    if (copied) LiteText(stringResource(R.string.history_copied), style = LiteType.Caption)
}

@Composable
private fun RequestHistoryDetails(entry: RequestHistoryEntry) {
    HistoryDetailRows(listOf(
        stringResource(R.string.connections_id) to entry.id,
        stringResource(R.string.connections_started) to historyTime(entry.time, true),
        stringResource(R.string.history_closed) to entry.end?.let { historyTime(it, true) }.orEmpty(),
        stringResource(R.string.lite_network) to entry.network,
        stringResource(R.string.connections_inbound) to entry.inbound,
        stringResource(R.string.connections_process) to entry.process,
        stringResource(R.string.connections_process_path) to entry.processPath,
        stringResource(R.string.connections_uid) to entry.uid,
        stringResource(R.string.connections_host) to entry.host,
        stringResource(R.string.connections_source) to historyEndpoint(entry.sourceIP, entry.sourcePort),
        stringResource(R.string.connections_target) to historyEndpoint(entry.destinationIP, entry.destinationPort),
        stringResource(R.string.lite_rule) to listOf(entry.rule, entry.rulePayload).filter(String::isNotEmpty).joinToString(" · "),
        stringResource(R.string.lite_chain) to entry.chains.asReversed().joinToString(" → "),
        stringResource(R.string.lite_upload) to historyAmount(entry.upload),
        stringResource(R.string.lite_download) to historyAmount(entry.download)), requestHistoryText(entry))
}

internal fun historyEndpoint(host: String, port: Int?): String =
    if (host.isEmpty()) "" else (if (':' in host) "[$host]" else host) + (port?.let { ":$it" } ?: "")
internal fun historyAmount(value: ULong): String = if (value <= Long.MAX_VALUE.toULong()) formatBytes(value.toLong()) else "$value B"

@Composable
internal fun historyTime(time: Instant, full: Boolean = false): String {
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale, full) { (if (full) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        else DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)).withLocale(locale).withZone(ZoneId.systemDefault()) }
    return formatter.format(time)
}

@Composable
private fun requestSortLabel(sort: RequestHistorySort): String = stringResource(when (sort) {
    RequestHistorySort.Time -> R.string.connections_started
    RequestHistorySort.Process -> R.string.connections_process
    RequestHistorySort.Target -> R.string.connections_target
    RequestHistorySort.Protocol -> R.string.lite_network
    RequestHistorySort.Rule -> R.string.lite_rule
    RequestHistorySort.Exit -> R.string.lite_chain
    RequestHistorySort.Download -> R.string.lite_download
    RequestHistorySort.Upload -> R.string.lite_upload
    RequestHistorySort.Traffic -> R.string.connections_traffic
})
