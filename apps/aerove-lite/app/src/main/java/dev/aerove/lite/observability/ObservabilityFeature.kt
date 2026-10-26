package dev.aerove.lite.observability

import dev.aerove.lite.LogLine
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface HistoryDocuments : AutoCloseable {
    suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean)
    suspend fun deleteCreated(reference: String): Boolean
    override fun close() = Unit
}

data class HistoryDocumentRequest(val id: Long, val name: String = "aerove-logs.txt", val dispatched: Boolean = false)
enum class HistoryNotice { Exported, Cancelled, Failed, PartialDocument, DnsCacheCleared }
data class ObservabilityState(val snapshot: HistorySnapshot = HistorySnapshot(),
    val document: HistoryDocumentRequest? = null, val exporting: Boolean = false,
    val clearingDns: Boolean = false, val notice: HistoryNotice? = null,
    val observationsAvailable: Boolean = false)

data class HistoryCounts(val requestCount: Long? = null, val dnsCount: Long? = null,
    val sessionRevision: Long = 0, val observationsAvailable: Boolean = false,
    val boundaryRevision: Long = 0)

class ObservabilityFeature(scope: CoroutineScope, private val documents: HistoryDocuments,
    private val clearDnsCache: suspend () -> Unit) : Closeable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val gate = Any()
    private val repository = HistoryRepository()
    private val mutableState = MutableStateFlow(ObservabilityState())
    val state = mutableState.asStateFlow()
    private val mutableCounts = MutableStateFlow(HistoryCounts())
    val counts = mutableCounts.asStateFlow()
    private var countsBoundary = 0L
    private val enabled = MutableStateFlow(false)
    val observationsEnabled = enabled.asStateFlow()
    private val leases = mutableMapOf<Long, Pair<HistoryKind, Boolean>>()
    private var publication: Job? = null
    private var publicationEpoch = 0L
    private val retiredCreates = linkedSetOf<Long>()
    private var serial = 0L
    private var binding: Long? = null
    private var foreground = false
    private var closed = false
    private var exportEpoch = 0L
    private var exportLines = emptyList<String>()
    private var exportJob: Job? = null
    private var dnsJob: Job? = null
    private var dnsEpoch = 0L

    fun bindSession(token: Long?) = synchronized(gate) {
        if (binding == token) return@synchronized
        cancelPublication(); binding = token; repository.bindSession(token); cancelExport()
        dnsEpoch++; dnsJob?.cancel(); dnsJob = null
        publish { it.copy(snapshot = repository.snapshot(visibleKinds()), clearingDns = false, observationsAvailable = false) }
        publishCounts(available = false, immediate = true)
        updateDemand()
    }

    fun setForeground(value: Boolean) = synchronized(gate) {
        if (foreground == value) return@synchronized
        foreground = value; cancelPublication(); updateDemand()
        if (value) publishHistory()
    }

    fun lease(kind: HistoryKind, visible: Boolean = true): Closeable = synchronized(gate) {
        val id = ++serial
        if (!closed) leases[id] = kind to visible
        updateDemand()
        if (foreground && visible) publishHistory()
        val released = AtomicBoolean(false)
        Closeable { if (released.compareAndSet(false, true)) synchronized(gate) { leases.remove(id); updateDemand() } }
    }

    private fun updateDemand() { enabled.value = !closed && foreground && binding != null && leases.values.any { it.first != HistoryKind.Logs } }

    fun acceptWindow(token: Long, window: ObservationWindow) = synchronized(gate) {
        if (!enabled.value || !repository.accept(token, window)) return@synchronized
        val first = !counts.value.observationsAvailable || counts.value.sessionRevision != repository.snapshot(emptySet()).sessionRevision
        publishCounts(available = true, immediate = first)
        if (first) publishHistory(true)
        else schedulePublication()
    }

    fun acceptLog(token: Long, line: LogLine) = synchronized(gate) {
        if (!foreground || closed || !repository.acceptLog(token, line)) return@synchronized
        schedulePublication()
    }

    fun appendLocalLogs(lines: List<LogLine>, isCurrent: () -> Boolean = { true }) = synchronized(gate) {
        if (closed || !isCurrent() || lines.isEmpty()) return@synchronized
        repository.appendLocalLogs(lines)
        publishHistory()
    }

    fun clear(kind: HistoryKind) = synchronized(gate) {
        repository.clear(kind); cancelPublication(); publishHistory()
        publishCounts(immediate = true)
    }

    private fun visibleKinds(): Set<HistoryKind> = if (foreground) leases.values.filter { it.second }.map { it.first }.toSet() else emptySet()

    private fun cancelPublication() { publicationEpoch++; publication?.cancel(); publication = null }

    private fun publishHistory(available: Boolean = state.value.observationsAvailable) {
        publish { it.copy(snapshot = repository.snapshot(visibleKinds()), observationsAvailable = available) }
    }

    private fun publishCounts(available: Boolean = counts.value.observationsAvailable, immediate: Boolean = false) {
        if (immediate) countsBoundary++
        val snapshot = repository.snapshot(emptySet())
        mutableCounts.value = HistoryCounts(snapshot.requestCount, snapshot.dnsCount, snapshot.sessionRevision,
            available, countsBoundary)
    }

    /** One trailing publication per burst; later arrivals update the rings without resetting the timer. */
    private fun schedulePublication() {
        if (!foreground || publication != null) return
        val epoch = publicationEpoch
        publication = jobs.launch(start = CoroutineStart.LAZY) {
            delay(300)
            synchronized(gate) {
                if (epoch == publicationEpoch && foreground && !closed) {
                    publication = null; publishHistory()
                }
            }
        }.also { it.start() }
    }

    fun flushDnsCache() = synchronized(gate) {
        if (closed || dnsJob?.isCompleted == false) return@synchronized
        val token = binding
        val operation = ++dnsEpoch
        publish { it.copy(clearingDns = true, notice = null) }
        dnsJob = jobs.launch {
            try { clearDnsCache(); synchronized(gate) { if (token == binding && operation == dnsEpoch) publish { it.copy(notice = HistoryNotice.DnsCacheCleared) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(gate) { if (token == binding && operation == dnsEpoch) publish { it.copy(notice = HistoryNotice.Failed) } } }
            finally { synchronized(gate) { if (operation == dnsEpoch) publish { it.copy(clearingDns = false) } } }
        }
    }

    fun requestLogExport() = synchronized(gate) {
        if (closed || state.value.document != null || exportJob?.isCompleted == false) return@synchronized
        exportLines = repository.snapshot(setOf(HistoryKind.Logs)).logs.map { "${java.time.Instant.ofEpochMilli(it.time)} [${it.level}] ${it.message}" }
        publish { it.copy(document = HistoryDocumentRequest(++serial), notice = null) }
    }

    fun documentLaunched(id: Long): Boolean = synchronized(gate) {
        val request = state.value.document
        if (closed || request?.id != id || request.dispatched) false
        else { publish { it.copy(document = request.copy(dispatched = true)) }; true }
    }

    fun documentResult(id: Long, reference: String?) = synchronized(gate) {
        if (retiredCreates.remove(id)) {
            if (reference != null) jobs.launch(NonCancellable) { cleanup(reference) }
            return@synchronized
        }
        state.value.document?.takeIf { it.id == id && it.dispatched } ?: return@synchronized
        if (closed) return@synchronized
        val lines = exportLines; exportLines = emptyList()
        publish { it.copy(document = null) }
        if (reference == null) { publish { it.copy(notice = HistoryNotice.Cancelled) }; return@synchronized }
        val token = ++exportEpoch
        publish { it.copy(exporting = true, notice = null) }
        exportJob = jobs.launch {
            var verified = false
            try {
                documents.save(reference, lines) { synchronized(gate) { !closed && token == exportEpoch && owner.isActive } }
                currentCoroutineContext().ensureActive()
                synchronized(gate) {
                    check(token == exportEpoch && !closed)
                    verified = true; publish { it.copy(notice = HistoryNotice.Exported) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { synchronized(gate) { if (token == exportEpoch) publish { it.copy(notice = HistoryNotice.Failed) } } }
            finally {
                if (!verified) withContext(NonCancellable) { cleanup(reference) }
                synchronized(gate) { if (token == exportEpoch) publish { it.copy(exporting = false) } }
            }
        }
    }

    fun cancelExport() = synchronized(gate) {
        state.value.document?.takeIf { it.dispatched }?.let {
            retiredCreates += it.id
            while (retiredCreates.size > 32) retiredCreates.remove(retiredCreates.first())
        }
        exportEpoch++; exportJob?.cancel(); exportJob = null; exportLines = emptyList()
        publish { it.copy(document = null, exporting = false, notice = if (it.document != null || it.exporting) HistoryNotice.Cancelled else it.notice) }
    }

    private suspend fun cleanup(reference: String) {
        if (!runCatching { documents.deleteCreated(reference) }.getOrDefault(false)) publish { it.copy(notice = HistoryNotice.PartialDocument) }
    }

    fun dismissNotice() = publish { it.copy(notice = null) }
    private fun publish(change: (ObservabilityState) -> ObservabilityState) = synchronized(gate) {
        if (!closed) mutableState.value = change(mutableState.value)
    }

    override fun close() = synchronized(gate) {
        if (!closed) { cancelPublication(); cancelExport(); closed = true; leases.clear(); enabled.value = false; owner.cancel(); documents.close() }
    }
}
