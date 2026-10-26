package dev.aerove.lite.telemetry

import dev.aerove.lite.DashboardCounts
import dev.aerove.lite.Traffic
import dev.aerove.lite.observability.HistoryCounts
import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Window focus is a read lease, independent from the Activity/control-session generation. */
internal class UiReadSignals {
    private var foreground = false
    private var focused = false
    private val mutable = MutableStateFlow(TelemetryVisibility())
    val state = mutable.asStateFlow()
    fun foreground(value: Boolean) { foreground = value; update() }
    fun focused(value: Boolean) { focused = value; update() }
    private fun update() { mutable.update { it.changed(foreground && focused) } }
}

internal data class UiTelemetrySnapshot(val traffic: Traffic = Traffic(),
    val history: List<Traffic> = emptyList(), val historyRevision: Long = 0,
    val counts: DashboardCounts = DashboardCounts())

/** Demand-driven publication only: source periods stay unchanged and no timer runs without updates. */
internal class UiTelemetryPublication(parent: CoroutineScope,
    private val publish: (UiTelemetrySnapshot) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val await: suspend (Long) -> Unit = { delay(it) }) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val gate = Any()
    private var active = false
    private var interacting = false
    private var closed = false
    private var generation = 0L
    private var pending: Job? = null
    private var last = Long.MIN_VALUE
    private var traffic = Traffic()
    private var trafficSeen = false
    private val history = ArrayDeque<Traffic>()
    private var revision = 0L
    private var cachedRevision = -1L
    private var cachedHistory = emptyList<Traffic>()
    private var counts = DashboardCounts()
    private var countsPending = false
    private var historyBoundary = -1L
    private val interval get() = if (interacting) 600L else 300L

    fun active(value: Boolean) = synchronized(gate) {
        if (closed || active == value) return@synchronized
        active = value; cancel()
        if (value) flush()
    }
    fun interacting(value: Boolean) = synchronized(gate) {
        if (closed || interacting == value) return@synchronized
        interacting = value
        if (pending != null) { cancel(); request() }
    }
    fun traffic(value: Traffic, recordHistory: Boolean) = synchronized(gate) {
        if (closed) return@synchronized
        traffic = value
        if (recordHistory) {
            if (history.size == DASHBOARD_TRAFFIC_SAMPLE_LIMIT) history.removeFirst()
            history.addLast(value); revision++
        }
        val first = !trafficSeen; trafficSeen = true
        // The first traffic value must not flush an already pending count before its 300/600ms deadline.
        request(first && !countsPending)
    }
    fun counts(value: DashboardCounts, immediate: Boolean = false) = synchronized(gate) {
        if (closed) return@synchronized
        if (counts != value) { counts = value; countsPending = true }
        if (countsPending) request(immediate)
    }
    fun history(value: HistoryCounts) = synchronized(gate) {
        val immediate = historyBoundary != value.boundaryRevision
        historyBoundary = value.boundaryRevision
        counts(counts.copy(requestCount = value.requestCount.takeIf { value.observationsAvailable },
            dnsCount = value.dnsCount.takeIf { value.observationsAvailable }), immediate)
    }
    fun connections(value: Int?) = synchronized(gate) {
        counts(counts.copy(connectionCount = value), immediate = counts.connectionCount == null && value != null && !countsPending)
    }
    fun reset() = synchronized(gate) {
        if (closed) return@synchronized
        cancel(); traffic = Traffic(); trafficSeen = false; counts = DashboardCounts(); countsPending = false; historyBoundary = -1L
        history.clear(); revision = 0; cachedRevision = -1
        // Runtime retirement is authoritative even while no UI owns a read lease.
        flush()
    }
    private fun request(immediate: Boolean = false) {
        if (!active || closed) return
        val wait = if (last == Long.MIN_VALUE) 0 else (last + interval - now()).coerceAtLeast(0)
        if (immediate || wait == 0L) { cancel(); flush(); return }
        if (pending != null) return
        val token = generation
        pending = scope.launch(start = CoroutineStart.LAZY) {
            await(wait)
            synchronized(gate) {
                if (token == generation && active && !closed) { pending = null; flush() }
            }
        }.also { it.start() }
    }
    private fun flush() {
        if (cachedRevision != revision) { cachedHistory = history.toList(); cachedRevision = revision }
        last = now(); countsPending = false; publish(UiTelemetrySnapshot(traffic, cachedHistory, revision, counts))
    }
    private fun cancel() { generation++; pending?.cancel(); pending = null }
    override fun close() = synchronized(gate) { closed = true; cancel(); scope.cancel() }
}
