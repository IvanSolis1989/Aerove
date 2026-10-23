package dev.aerove.lite.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aerove.lite.R
import dev.aerove.lite.observability.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun DnsQueriesScreen(feature: ObservabilityFeature, padding: PaddingValues, tables: HistoryTablePreferences,
    onTableChange: (HistoryTableChange) -> Unit) {
    HistoryVisibility(feature, HistoryKind.Dns)
    val state by feature.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sort by rememberSaveable { mutableStateOf<DnsHistorySort?>(null) }
    var descending by rememberSaveable { mutableStateOf(true) }
    var following by rememberSaveable { mutableStateOf(true) }
    fun selectSort(option: DnsHistorySort) {
        val first = option in setOf(DnsHistorySort.Time, DnsHistorySort.Delay)
        val next = nextHistorySort(sort?.let { HistorySortSelection(it.columnId, descending) }, option.columnId, first)
        sort = next?.let { selection -> DnsHistorySort.entries.first { it.columnId == selection.columnId } }
        next?.let { descending = it.descending }
    }
    val labels = mapOf("App" to stringResource(R.string.history_app), "Rule" to stringResource(R.string.history_rule),
        "Direct" to stringResource(R.string.history_direct), "Proxy" to stringResource(R.string.history_proxy),
        "Other" to stringResource(R.string.history_other), "cache" to stringResource(R.string.history_cached))
    val anchor = remember(feature, state.snapshot.sessionRevision) { HistoryAnchor<DnsHistoryEntry>(3000) { it.id } }
    val projection by produceState(HistoryProjection<DnsHistoryEntry>(emptyList(), emptyList()), anchor, state.snapshot.dns, query, filters, sort, descending, following, labels) {
        value = withContext(Dispatchers.Default) { projectDns(anchor.update(state.snapshot.dns, following), query, filters.toSet(), sort, descending, labels) }
    }
    val list = rememberLazyListState()
    HistoryFollow(list, projection.rows.firstOrNull()?.id, following) { following = false }
    val overlay = LocalLiteOverlay.current
    Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HistoryControls(query, { query = it }, filters, projection.keywords, following, { following = !following }, projection.rows.size,
            sort = { overlay.content = {
                LiteModal(stringResource(R.string.history_sort), overlay::dismiss) {
                    DnsHistorySort.entries.forEach { option -> SettingsRow(dnsSortLabel(option), onClick = {
                        selectSort(option)
                        overlay.dismiss()
                    }) { if (sort == option) LiteIcon(if (descending) FullGlyphs.arrowDown else FullGlyphs.arrowUp) } }
                }
            } }, keywordLabels = labels, onFilters = { filters = it })
        LiteButton(stringResource(R.string.history_clear_dns_cache), feature::flushDnsCache,
            Modifier.fillMaxWidth().testTag("history-dns-flush"), FullGlyphs.delete, enabled = !state.clearingDns)
        HistoryNoticeView(state, feature)
        if (projection.rows.isEmpty()) HistoryEmpty(query.isNotBlank() || filters.isNotEmpty())
        else BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 1000.dp
            val isTable = maxWidth >= 720.dp
            val columns = dnsHistoryColumns(wide)
            val table = rememberHistoryTable("dns", columns, maxWidth.value.toDouble(), tables, onTableChange)
            Column {
            if (isTable) HistoryTableHeader("dns", table,
                { id -> dnsSortLabel(DnsHistorySort.entries.first { it.columnId == id }) },
                sort?.columnId, descending, { id -> selectSort(DnsHistorySort.entries.first { it.columnId == id }) })
            HistoryTableBody(isTable, Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, verticalArrangement = Arrangement.spacedBy(if (isTable) 0.dp else 10.dp)) {
                items(projection.rows, key = { it.id }) { entry ->
                    HistoryRecordContainer(isTable, Modifier.fillMaxWidth().testTag("history-dns-${entry.id}").clickable {
                        overlay.content = { LiteModal(stringResource(R.string.history_dns_details), overlay::dismiss) { DnsHistoryDetails(entry, labels) } }
                    }) {
                        val resultColor = if (entry.failed) MaterialTheme.colorScheme.error else LocalLiteVisuals.current.secondaryInk
                        if (isTable) HistoryTableCells(table, columns.map { column -> when (column.id) {
                            "time" -> historyTime(entry.time)
                            "domain" -> entry.domain
                            "type" -> entry.type
                            "initiator" -> labels[entry.initiator] ?: entry.initiator.orEmpty()
                            "result" -> entry.summary
                            "upstream" -> entry.upstream
                            else -> "${entry.delayMillis} ms"
                        } }, mapOf("result" to resultColor), buildMap {
                            put("type", { filters = (filters + entry.type).distinct() })
                            entry.initiator?.let { value -> put("initiator", { filters = (filters + value).distinct() }) }
                        }) else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiteText(historyTime(entry.time), style = LiteType.Caption)
                            LiteText(entry.domain, style = LiteType.Headline)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HistoryKeyword(entry.type) { filters = (filters + it).distinct() }
                                entry.initiator?.let { value -> HistoryKeyword(labels[value] ?: value) { filters = (filters + value).distinct() } }
                                if (entry.cached) HistoryKeyword(labels.getValue("cache")) { filters = (filters + "cache").distinct() }
                                if (entry.rcode.isNotEmpty() && entry.rcode != "NOERROR") HistoryKeyword(entry.rcode) { filters = (filters + it).distinct() }
                            }
                            if (entry.summary.isNotEmpty()) LiteText(entry.summary, style = LiteType.Label, color = resultColor)
                            LiteText("${entry.delayMillis} ms", style = LiteType.Caption)
                        }
                    }
                }
            }
            }
            }
        }
    }
}

@Composable
private fun DnsHistoryDetails(entry: DnsHistoryEntry, labels: Map<String, String>) {
    HistoryDetailRows(listOf(
        stringResource(R.string.connections_started) to historyTime(entry.time, true),
        stringResource(R.string.history_domain) to entry.domain,
        stringResource(R.string.history_record_type) to entry.type,
        stringResource(R.string.history_initiator) to (labels[entry.initiator] ?: entry.initiator.orEmpty()),
        stringResource(R.string.history_upstream) to entry.upstream,
        stringResource(R.string.history_cached) to stringResource(if (entry.cached) R.string.history_yes else R.string.history_no),
        stringResource(R.string.history_response_code) to entry.rcode,
        stringResource(R.string.history_elapsed) to "${entry.delayMillis} ms",
        stringResource(R.string.history_error) to entry.error,
        stringResource(R.string.history_answers) to entry.answers.joinToString("\n")), dnsHistoryText(entry))
}

@Composable
private fun dnsSortLabel(sort: DnsHistorySort): String = stringResource(when (sort) {
    DnsHistorySort.Time -> R.string.connections_started
    DnsHistorySort.Domain -> R.string.history_domain
    DnsHistorySort.Type -> R.string.history_record_type
    DnsHistorySort.Initiator -> R.string.history_initiator
    DnsHistorySort.Result -> R.string.history_result
    DnsHistorySort.Upstream -> R.string.history_upstream
    DnsHistorySort.Delay -> R.string.history_elapsed
})
