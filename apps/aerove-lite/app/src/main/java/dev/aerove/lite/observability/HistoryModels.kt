package dev.aerove.lite.observability

import java.time.Instant
import java.util.Locale

data class HistoryLogEntry(val id: Long, val time: Long, val level: String, val message: String)

enum class HistoryKind { Requests, Dns, Logs }
enum class RequestHistorySort { Time, Process, Target, Protocol, Rule, Exit, Download, Upload, Traffic }
enum class DnsHistorySort { Time, Domain, Type, Initiator, Result, Upstream, Delay }

data class RequestHistoryEntry(
    val id: String,
    val time: Instant,
    val network: String,
    val host: String = "",
    val sourceIP: String = "",
    val sourcePort: Int? = null,
    val destinationIP: String = "",
    val destinationPort: Int? = null,
    val inbound: String = "",
    val process: String = "",
    val processPath: String = "",
    val uid: String = "",
    val chains: List<String> = emptyList(),
    val rule: String = "",
    val rulePayload: String = "",
    val upload: ULong = 0uL,
    val download: ULong = 0uL,
    val end: Instant? = null,
) {
    val target: String get() = host.ifBlank { destinationIP }
    val searchFields: List<String> get() = listOf(network, host, sourceIP, sourcePort?.toString().orEmpty(),
        destinationIP, destinationPort?.toString().orEmpty(), inbound, process, processPath, uid, rule, rulePayload) + chains
}

data class DnsHistoryEntry(
    val id: String,
    val time: Instant,
    val domain: String,
    val type: String,
    val initiator: String? = null,
    val upstream: String = "",
    val cached: Boolean = false,
    val answers: List<String> = emptyList(),
    val rcode: String = "",
    val error: String = "",
    val delayMillis: ULong = 0uL,
) {
    val failed: Boolean get() = error.isNotEmpty() || (rcode.isNotEmpty() && rcode != "NOERROR")
    val summary: String get() = error.ifEmpty { answers.joinToString(", ") }
    val searchFields: List<String> get() = listOf(domain, type, initiator.orEmpty(), upstream, rcode, error,
        if (cached) "cache" else "") + answers
}

internal fun historyMatches(query: String, fields: List<String>): Boolean {
    val text = fields.joinToString("\n").lowercase(Locale.ROOT)
    return query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty).all(text::contains)
}

internal fun requestHistoryText(entry: RequestHistoryEntry): String = buildString {
    appendLine(entry.time.toString())
    entry.searchFields.filter(String::isNotEmpty).forEach(::appendLine)
    appendLine("upload=${entry.upload}")
    append("download=${entry.download}")
}

internal fun dnsHistoryText(entry: DnsHistoryEntry): String = buildString {
    appendLine(entry.time.toString())
    appendLine(entry.domain); appendLine(entry.type)
    entry.initiator?.let(::appendLine)
    appendLine(entry.upstream); appendLine("cached=${entry.cached}")
    appendLine(entry.rcode); appendLine("${entry.delayMillis} ms")
    entry.answers.forEach(::appendLine)
    append(entry.error)
}
