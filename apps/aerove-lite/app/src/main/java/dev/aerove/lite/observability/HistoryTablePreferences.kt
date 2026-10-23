package dev.aerove.lite.observability

import org.json.JSONObject

/** Widths use logical pixels, the same unit as Android dp and Standard's table layout. */
data class HistoryTablePreferences(val widths: Map<String, Map<String, Double>> = emptyMap()) {
    init { widths.forEach { (table, values) -> validateHistoryWidths(table, values) } }
    fun apply(change: HistoryTableChange): HistoryTablePreferences {
        validateHistoryWidths(change.tableId, change.widths)
        val next = if (change.reset) emptyMap() else widths[change.tableId].orEmpty() + change.widths
        return HistoryTablePreferences((widths + (change.tableId to next)).filterValues { it.isNotEmpty() })
    }
}

data class HistoryTableChange(val tableId: String, val widths: Map<String, Double> = emptyMap(), val reset: Boolean = false) {
    init { validateHistoryWidths(tableId, widths); require(!reset || widths.isEmpty()) }
}

data class HistorySortSelection(val columnId: String, val descending: Boolean)
fun nextHistorySort(current: HistorySortSelection?, columnId: String, descendingFirst: Boolean): HistorySortSelection? =
    if (current == null || current.columnId != columnId) HistorySortSelection(columnId, descendingFirst)
    else if (current.descending == descendingFirst) HistorySortSelection(columnId, !descendingFirst) else null

private val historyColumns = mapOf(
    "requests" to setOf("time", "process", "target", "protocol", "rule", "exit", "traffic", "download", "upload"),
    "dns" to setOf("time", "domain", "type", "initiator", "result", "upstream", "elapsed"),
)

private fun validateHistoryWidths(table: String, widths: Map<String, Double>) {
    val allowed = requireNotNull(historyColumns[table]) { "Unknown history table" }
    require(widths.keys.all { it in allowed } && widths.values.all { it.isFinite() && it > 0.0 }) { "Invalid history column width" }
}

fun encodeHistoryTables(value: HistoryTablePreferences): JSONObject = JSONObject().also { root ->
    value.widths.toSortedMap().forEach { (table, values) ->
        validateHistoryWidths(table, values)
        if (values.isNotEmpty()) root.put(table, JSONObject().also { row -> values.toSortedMap().forEach { (column, width) -> row.put(column, width) } })
    }
}

fun decodeHistoryTables(value: JSONObject): HistoryTablePreferences = HistoryTablePreferences(value.keys().asSequence().associateWith { table ->
    val row = value.getJSONObject(table)
    row.keys().asSequence().associateWith { column ->
        val width = row.get(column)
        require(width is Number) { "Invalid history column width" }
        width.toDouble()
    }
})

/** Merge only edited columns into the latest saved settings, retaining other queued edits. */
fun mergeHistoryTables(baseline: HistoryTablePreferences, requested: HistoryTablePreferences,
    latest: HistoryTablePreferences): HistoryTablePreferences {
    var result = latest
    (baseline.widths.keys + requested.widths.keys).forEach { table ->
        val old = baseline.widths[table].orEmpty(); val next = requested.widths[table].orEmpty()
        if (old != next) {
            val merged = result.widths[table].orEmpty().toMutableMap()
            (old.keys + next.keys).filter { old[it] != next[it] }.forEach { column ->
                next[column]?.let { merged[column] = it } ?: merged.remove(column)
            }
            result = HistoryTablePreferences((result.widths + (table to merged)).filterValues { it.isNotEmpty() })
        }
    }
    return result
}

data class HistoryColumn(val id: String, val width: Double? = null, val flex: Double = 0.0, val end: Boolean = false) {
    init { require((width == null) != (flex == 0.0)); require(width?.let { it.isFinite() && it > 0 } ?: (flex.isFinite() && flex > 0)) }
}

val RequestHistorySort.columnId: String get() = name.lowercase(java.util.Locale.ROOT)
val DnsHistorySort.columnId: String get() = if (this == DnsHistorySort.Delay) "elapsed" else name.lowercase(java.util.Locale.ROOT)

fun requestHistoryColumns(wide: Boolean, protocolWidth: Double = 56.0): List<HistoryColumn> = buildList {
    add(HistoryColumn("time", 80.0)); add(HistoryColumn("process", if (wide) 160.0 else 140.0))
    add(HistoryColumn("target", flex = if (wide) 1.4 else 1.3))
    if (wide) add(HistoryColumn("protocol", protocolWidth.coerceAtLeast(56.0)))
    add(HistoryColumn("rule", flex = 1.0)); add(HistoryColumn("exit", 120.0))
    if (wide) { add(HistoryColumn("download", 100.0, end = true)); add(HistoryColumn("upload", 100.0, end = true)) }
    else add(HistoryColumn("traffic", 88.0, end = true))
}

fun dnsHistoryColumns(wide: Boolean): List<HistoryColumn> = buildList {
    add(HistoryColumn("time", 80.0)); add(HistoryColumn("domain", flex = 1.3))
    add(HistoryColumn("type", 72.0)); add(HistoryColumn("initiator", 72.0)); add(HistoryColumn("result", flex = 1.0))
    if (wide) add(HistoryColumn("upstream", flex = 0.8))
    add(HistoryColumn("elapsed", 76.0, end = true))
}

fun historyColumnWidths(columns: List<HistoryColumn>, width: Double, overrides: Map<String, Double>): List<Double> {
    require(width.isFinite() && width >= 0)
    val sized = columns.map { overrides[it.id] ?: it.width }
    val fixed = sized.filterNotNull().sum()
    val flexible = columns.indices.filter { sized[it] == null }
    val free = width - fixed
    if (overrides.isEmpty() || flexible.isNotEmpty() && free >= flexible.size * 48.0) {
        val totalFlex = flexible.sumOf { columns[it].flex }
        return columns.indices.map { sized[it] ?: (free.coerceAtLeast(0.0) * columns[it].flex / totalFlex) }
    }
    val natural = sized.map { it ?: 48.0 }; val total = natural.sum()
    return natural.map { it * width / total }
}

fun resizeHistoryColumns(columns: List<HistoryColumn>, widths: List<Double>, overrides: Map<String, Double>,
    boundary: Int, delta: Double): Map<String, Double> {
    require(boundary in 0 until columns.lastIndex && widths.size == columns.size && delta.isFinite())
    val resized = widths.toMutableList()
    if (delta > 0) {
        var rest = delta
        for (index in boundary + 1 until columns.size) {
            val taken = rest.coerceIn(0.0, (resized[index] - 48.0).coerceAtLeast(0.0))
            resized[index] -= taken; rest -= taken
            if (rest <= 0) break
        }
        resized[boundary] += delta - rest
    } else {
        val given = (-delta).coerceIn(0.0, (resized[boundary] - 48.0).coerceAtLeast(0.0))
        resized[boundary] -= given; resized[boundary + 1] += given
    }
    return overrides + columns.indices.filter { resized[it] != widths[it] }.associate { columns[it].id to resized[it] }
}
