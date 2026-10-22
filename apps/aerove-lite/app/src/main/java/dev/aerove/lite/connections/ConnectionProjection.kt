package dev.aerove.lite.connections

import dev.aerove.lite.ConnectionInfo
import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToLong

internal enum class ConnectionSort {
    Activity, Process, Target, Network, Rule, Exit, DownloadSpeed, UploadSpeed, Download, Upload, Traffic, Started,
}

internal data class ConnectionRow(val connection: ConnectionInfo, val uploadSpeed: Long?, val downloadSpeed: Long?)
internal data class ConnectionProjection(
    val rows: List<ConnectionRow> = emptyList(), val keywords: List<String> = emptyList(),
)

/** One screen-owned cache, evaluated on Dispatchers.Default; never starts a collector or timer. */
internal class ConnectionProjector {
    private data class Sample(val row: ConnectionRow, val at: Long, val rank: Double,
        val upload: Long?, val download: Long?)
    private var samples = emptyMap<String, Sample>()
    private var positions = emptyMap<String, Int>()
    private var source: List<ConnectionInfo>? = null
    private var ranked = emptyList<ConnectionRow>()
    private var texts = emptyMap<String, String>()
    private var keywords = emptyList<String>()

    @Synchronized
    fun project(connections: List<ConnectionInfo>, atMillis: Long, query: String = "",
        filters: Set<String> = emptySet(), sort: ConnectionSort = ConnectionSort.Activity,
        descending: Boolean = false): ConnectionProjection {
        if (source !== connections) {
            val fresh = connections.associate { info ->
                val old = samples[info.id]?.takeIf { it.row.connection.startedAt == info.startedAt }
                val observedAtMillis = info.observedAtNanos?.let { it / 1_000_000 } ?: atMillis
                val elapsed = old?.let { observedAtMillis - it.at }
                val upload = speed(old?.upload, info.upload, elapsed, old?.row?.uploadSpeed)
                val download = speed(old?.download, info.download, elapsed, old?.row?.downloadSpeed)
                val previousRank = old?.rank ?: 0.0
                val currentRank = if (elapsed != null && elapsed >= 500 && upload != null && download != null)
                    previousRank + (upload.toDouble() + download - previousRank) * 0.5 else previousRank
                val sample = if (old != null && elapsed != null && elapsed in 0..499)
                    old.copy(row = old.row.copy(connection = info))
                else Sample(ConnectionRow(info, upload, download), observedAtMillis, currentRank, info.upload, info.download)
                info.id to sample
            }
            val buckets = fresh.mapValues { (_, sample) -> sample.rank.let { if (it < 1) 0 else floor(ln(it) / ln(2.0)).toInt() + 1 } }
            fun bucket(id: String): Int = buckets.getValue(id)
            ranked = fresh.values.map { it.row }.sortedWith { a, b ->
                val group = bucket(b.connection.id).compareTo(bucket(a.connection.id))
                if (group != 0) group else {
                    val position = if (bucket(a.connection.id) > 0)
                        (positions[a.connection.id] ?: connections.size).compareTo(positions[b.connection.id] ?: connections.size) else 0
                    if (position != 0) position else {
                        val traffic = compareNullableDescending(total(a.connection), total(b.connection))
                        val start = compareNullableDescending(a.connection.startedAt, b.connection.startedAt)
                        if (traffic != 0) traffic else if (start != 0) start else a.connection.id.compareTo(b.connection.id)
                    }
                }
            }
            samples = fresh
            positions = ranked.mapIndexed { index, row -> row.connection.id to index }.toMap()
            texts = connections.associate { it.id to searchFields(it).joinToString("\n").lowercase(Locale.ROOT) }
            keywords = connections.flatMap { listOfNotNull(it.process?.takeIf(String::isNotBlank)) + it.chains }
                .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
            source = connections
        }
        val terms = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        val filtered = ranked.filter { row ->
            filters.all { it == row.connection.process || it in row.connection.chains } &&
                terms.all { texts.getValue(row.connection.id).contains(it) }
        }
        val rows = if (sort == ConnectionSort.Activity) filtered else filtered.sortedWith { a, b ->
            val first = key(a, sort); val second = key(b, sort)
            val order = when {
                first == null && second == null -> 0
                first == null -> 1
                second == null -> -1
                else -> compare(first, second) * if (descending) -1 else 1
            }
            if (order == 0) a.connection.id.compareTo(b.connection.id) else order
        }
        return ConnectionProjection(rows, keywords)
    }

    private fun speed(previous: Long?, current: Long?, elapsed: Long?, oldSpeed: Long?): Long? {
        if (previous == null || current == null || elapsed == null || elapsed <= 0) return null
        if (elapsed < 500) return oldSpeed
        return (maxOf(0.0, current.toDouble() - previous) * 1000 / elapsed).roundToLong()
    }

    private fun key(row: ConnectionRow, sort: ConnectionSort): Any? = with(row.connection) {
        when (sort) {
            ConnectionSort.Activity -> null
            ConnectionSort.Process -> process?.lowercase(Locale.ROOT)
            ConnectionSort.Target -> destination.lowercase(Locale.ROOT)
            ConnectionSort.Network -> network.lowercase(Locale.ROOT)
            ConnectionSort.Rule -> "$rule ${rulePayload.orEmpty()}".lowercase(Locale.ROOT)
            ConnectionSort.Exit -> chains.firstOrNull()?.lowercase(Locale.ROOT)
            ConnectionSort.DownloadSpeed -> row.downloadSpeed
            ConnectionSort.UploadSpeed -> row.uploadSpeed
            ConnectionSort.Download -> download
            ConnectionSort.Upload -> upload
            ConnectionSort.Traffic -> total(this)
            ConnectionSort.Started -> startedAt
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun compare(a: Any, b: Any): Int = (a as Comparable<Any>).compareTo(b)

    private fun <T : Comparable<T>> compareNullableDescending(a: T?, b: T?): Int = when {
        a == null && b == null -> 0
        a == null -> 1
        b == null -> -1
        else -> b.compareTo(a)
    }
}

private fun total(info: ConnectionInfo): Long? = info.upload?.let { upload -> info.download?.let { download ->
    if (upload > Long.MAX_VALUE - download) Long.MAX_VALUE else upload + download
} }

private fun searchFields(info: ConnectionInfo): List<String> = with(info) {
    listOfNotNull(id, destination, network, rule, rulePayload, host, sourceIP, sourcePort?.toString(),
        destinationIP, destinationPort?.toString(), process, processPath, uid?.toString(), inboundType,
        country, asn, inboundUser) + chains
}
