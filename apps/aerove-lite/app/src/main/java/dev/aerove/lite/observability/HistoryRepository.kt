package dev.aerove.lite.observability

import dev.aerove.lite.LogLine

data class HistorySnapshot(
    val requests: List<RequestHistoryEntry> = emptyList(),
    val dns: List<DnsHistoryEntry> = emptyList(),
    val logs: List<HistoryLogEntry> = emptyList(),
    val truncated: Boolean = false,
    val sessionRevision: Long = 0,
    val requestCount: Long? = null,
    val dnsCount: Long? = null,
)

/** Consumes only the existing stream; it owns no timer, network client or coroutine. */
class HistoryRepository {
    private var token: Long? = null
    private var runtime: String? = null
    private var generation: ULong? = null
    private var cursor = 0uL
    private var dropped = 0uL
    private var truncated = false
    private val retiredRuntimes = linkedSetOf<String>()
    private val requests = linkedMapOf<String, RequestHistoryEntry>()
    private val dns = linkedMapOf<String, DnsHistoryEntry>()
    private val logs = ArrayDeque<HistoryLogEntry>()
    private var logSerial = 0L
    private var sessionRevision = 0L
    private var activation: ULong? = null
    private var requestCount = 0L
    private var dnsCount = 0L
    private var countsReady = false
    private var countsComplete = true

    @Synchronized fun bindSession(value: Long?) {
        if (token == value) return
        sessionRevision++
        token = value; runtime = null; generation = null; cursor = 0uL; dropped = 0uL
        retiredRuntimes.clear(); requests.clear(); dns.clear(); logs.clear(); truncated = false
        resetCounts()
    }

    private fun resetCounts() {
        activation = null; requestCount = 0; dnsCount = 0; countsReady = false; countsComplete = true
    }

    @Synchronized fun accept(binding: Long, window: ObservationWindow): Boolean {
        if (token == null || token != binding || window.sessionId in retiredRuntimes) return false
        if (window.activationCursor?.let { it > window.cursor } == true) return false
        if (window.events.isEmpty() && window.activationCursor != null &&
            (runtime != window.sessionId || generation != window.generation) &&
            window.cursor != window.activationCursor) return false
        if (runtime != window.sessionId) {
            runtime?.let { retiredRuntimes += it; requests.clear(); dns.clear(); logs.clear(); truncated = false; sessionRevision++ }
            while (retiredRuntimes.size > 16) retiredRuntimes.remove(retiredRuntimes.first())
            runtime = window.sessionId; generation = null
            resetCounts()
        }
        val oldGeneration = generation
        if (oldGeneration != null && window.generation < oldGeneration) return false
        if (window.generation != oldGeneration) {
            if (countsReady && window.activationCursor == null) countsComplete = false
            activation = window.activationCursor
            countsReady = activation != null
            generation = window.generation
            cursor = if (window.events.isEmpty()) window.cursor else 0uL
            dropped = window.dropped
        } else if (window.cursor < cursor || window.activationCursor != activation) return false
        var previous = cursor
        var liveCursor = maxOf(cursor, activation ?: cursor)
        if (window.dropped > dropped) truncated = true
        window.events.filter { it.cursor > cursor }.forEach { event ->
            if (event.cursor - previous > 1uL) truncated = true
            previous = event.cursor
            if (activation?.let { event.cursor > it } == true) {
                if (event.cursor - liveCursor > 1uL) countsComplete = false
                liveCursor = event.cursor
                when (event) {
                    is ObservationEvent.Request -> if (requestCount < Long.MAX_VALUE) requestCount++ else countsComplete = false
                    is ObservationEvent.Dns -> if (dnsCount < Long.MAX_VALUE) dnsCount++ else countsComplete = false
                    else -> Unit
                }
            }
            when (event) {
                is ObservationEvent.Request -> requests[event.value.id] = event.value
                is ObservationEvent.Traffic -> requests[event.id]?.let {
                    requests[event.id] = it.copy(upload = event.upload, download = event.download)
                }
                is ObservationEvent.Closed -> requests[event.id]?.let {
                    requests[event.id] = it.copy(upload = event.upload, download = event.download, end = event.end)
                }
                is ObservationEvent.Dns -> dns["${window.sessionId}:${event.value.id}"] = event.value.copy(id = "${window.sessionId}:${event.value.id}")
            }
            while (requests.size > 2000) requests.remove(requests.keys.first())
            while (dns.size > 3000) dns.remove(dns.keys.first())
        }
        if (window.cursor > previous) truncated = true
        if (countsReady && window.cursor > liveCursor) countsComplete = false
        cursor = window.cursor; dropped = window.dropped
        return true
    }

    @Synchronized fun acceptLog(binding: Long, line: LogLine): Boolean {
        if (token == null || token != binding) return false
        appendLog(line)
        return true
    }

    /** Explicit application log actions share the core log ring without pretending to have a runtime. */
    @Synchronized fun appendLocalLogs(lines: List<LogLine>) {
        lines.forEach(::appendLog)
    }

    private fun appendLog(line: LogLine) {
        logs.addLast(HistoryLogEntry(++logSerial, line.time, line.level, line.message))
        while (logs.size > 5000) logs.removeFirst()
    }

    @Synchronized fun clear(kind: HistoryKind) {
        when (kind) { HistoryKind.Requests -> requests.clear(); HistoryKind.Dns -> dns.clear(); HistoryKind.Logs -> logs.clear() }
    }

    @Synchronized fun snapshot(): HistorySnapshot = HistorySnapshot(requests.values.toList(), dns.values.toList(), logs.toList(), truncated, sessionRevision,
        requestCount.takeIf { countsReady && countsComplete }, dnsCount.takeIf { countsReady && countsComplete })
}
