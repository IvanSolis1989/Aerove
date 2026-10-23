package dev.aerove.lite.observability

import java.math.BigInteger
import java.util.Locale

data class HistoryProjection<T>(val rows: List<T>, val keywords: List<String>)

fun projectRequests(source: List<RequestHistoryEntry>, query: String = "", keywords: Set<String> = emptySet(),
    sort: RequestHistorySort? = null, descending: Boolean = true): HistoryProjection<RequestHistoryEntry> {
    val rows = source.asReversed().filter { entry -> keywords.all { it == entry.process || it in entry.chains } && historyMatches(query, entry.searchFields) }
    val comparator = Comparator<RequestHistoryEntry> { a, b ->
        val order = when (sort) {
            RequestHistorySort.Time -> a.time.compareTo(b.time)
            RequestHistorySort.Process -> a.process.compareTo(b.process, true)
            RequestHistorySort.Target -> a.target.compareTo(b.target, true)
            RequestHistorySort.Protocol -> a.network.compareTo(b.network, true)
            RequestHistorySort.Rule -> "${a.rule} ${a.rulePayload}".compareTo("${b.rule} ${b.rulePayload}", true)
            RequestHistorySort.Exit -> a.chains.firstOrNull().orEmpty().compareTo(b.chains.firstOrNull().orEmpty(), true)
            RequestHistorySort.Download -> a.download.compareTo(b.download)
            RequestHistorySort.Upload -> a.upload.compareTo(b.upload)
            RequestHistorySort.Traffic -> total(a).compareTo(total(b))
            null -> 0
        }
        if (descending) -order else order
    }
    return HistoryProjection(rows.sortedWith(comparator), source.flatMap { listOf(it.process) + it.chains }.filter(String::isNotBlank).distinct().sorted())
}

fun projectDns(source: List<DnsHistoryEntry>, query: String = "", keywords: Set<String> = emptySet(),
    sort: DnsHistorySort? = null, descending: Boolean = true,
    labels: Map<String, String> = emptyMap()): HistoryProjection<DnsHistoryEntry> {
    fun tags(entry: DnsHistoryEntry) = listOfNotNull(entry.type, entry.initiator, entry.rcode.takeIf { it.isNotEmpty() && it != "NOERROR" }, "cache".takeIf { entry.cached })
    val rows = source.asReversed().filter { entry -> keywords.all { it in tags(entry) } && historyMatches(query,
        entry.searchFields + tags(entry).mapNotNull(labels::get)) }
    val comparator = Comparator<DnsHistoryEntry> { a, b ->
        val order = when (sort) {
            DnsHistorySort.Time -> a.time.compareTo(b.time)
            DnsHistorySort.Domain -> a.domain.compareTo(b.domain, true)
            DnsHistorySort.Type -> a.type.compareTo(b.type)
            DnsHistorySort.Initiator -> a.initiator.orEmpty().compareTo(b.initiator.orEmpty())
            DnsHistorySort.Result -> a.summary.compareTo(b.summary, true)
            DnsHistorySort.Upstream -> a.upstream.compareTo(b.upstream, true)
            DnsHistorySort.Delay -> a.delayMillis.compareTo(b.delayMillis)
            null -> 0
        }
        if (descending) -order else order
    }
    return HistoryProjection(rows.sortedWith(comparator), source.flatMap(::tags).distinct().sorted())
}

fun projectLogs(source: List<HistoryLogEntry>, query: String = "", levels: Set<String> = emptySet()): List<HistoryLogEntry> =
    source.filter { (levels.isEmpty() || levels.all { level -> level == it.level.lowercase(Locale.ROOT) }) && historyMatches(query, listOf(it.level, it.message)) }.asReversed()

private fun total(entry: RequestHistoryEntry): BigInteger = BigInteger(entry.upload.toString()).add(BigInteger(entry.download.toString()))

/** Standard keeps the trimmed head while follow is paused, with a bounded double-size anchor. */
class HistoryAnchor<T>(private val limit: Int, private val key: (T) -> Any) {
    private var current = emptyList<T>()
    @Synchronized fun update(latest: List<T>, following: Boolean): List<T> {
        if (following || current.isEmpty() || latest.isEmpty()) current = latest
        else {
            val index = current.indexOfFirst { key(it) == key(latest.first()) }
            current = when {
                index == 0 -> latest
                index > 0 -> (current.take(index) + latest).take(limit * 2)
                else -> (current + latest).distinctBy(key).take(limit * 2)
            }
        }
        return current
    }
}
