package dev.aerove.lite.telemetry

import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProxyGroup
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import dev.aerove.lite.data.RouteHit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class OutboundAnswer(val identity: OutboundIdentity, val chains: List<String>,
    val hits: List<RouteHit> = emptyList(), val coreEpoch: ULong? = null, val routeVersion: ULong? = null)

internal data class OutboundProbeState(
    val identity: OutboundIdentity? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal fun List<ProxyGroup>.routePicks(): Map<String, String> =
    filter { it.selected.isNotEmpty() }.associate { it.name to it.selected }

internal class RouteSnapshotDemand {
    private val gate = Any()
    private var generation = 0L

    fun invalidate() = synchronized(gate) { generation++ }

    suspend fun <T> read(read: suspend () -> T, accept: (T) -> Unit) {
        val requested = synchronized(gate) { generation }
        val value = read()
        synchronized(gate) { if (requested == generation) accept(value) }
    }
}

internal class OutboundProbe<K : Any>(
    parent: CoroutineScope,
    private val read: suspend (K) -> OutboundAnswer,
    private val isCurrent: (K) -> Boolean,
) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(OutboundProbeState())
    val state = mutableState.asStateFlow()
    private val signals = MutableStateFlow(0L)
    private var key: K? = null
    private var active = false
    private var synced = false
    private var routePending = true
    private var picks = emptyMap<String, String>()
    private var coreEpoch: ULong? = null
    private var routeVersion: ULong? = null
    private var notBefore = 0L
    private var lastStarted = Long.MIN_VALUE
    private var bypassThrottle = false
    private val retiring = mutableListOf<Job>()
    fun routeHits(): List<RouteHit> = cached?.hits.orEmpty()
    private var cached: OutboundAnswer? = null
    private var task: Job? = null
    private var requestStarted = false
    private var run = 0L
    private var forced = false
    private var failed = false
    private var staleUnder: Map<String, String>? = null
    private var failedChain: List<String>? = null
    private var selectionId = 0L
    private val selections = mutableSetOf<Long>()

    fun bind(key: K?, initialPicks: Map<String, String>? = null) {
        if (this.key == key) return
        this.key = key
        cancelTask()
        cached = null
        coreEpoch = null
        routeVersion = null
        bypassThrottle = true
        notBefore = now() + 300
        forced = false
        failed = false
        staleUnder = null
        failedChain = null
        selections.clear()
        picks = initialPicks?.toMap().orEmpty()
        synced = initialPicks != null
        mutableState.value = OutboundProbeState()
        changed()
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        synced = false
        if (active) failed = false else cancelTask()
        changed()
    }

    fun awaitingGroups() {
        routePending = true
        synced = false
        changed()
    }

    fun groupsFailed(failure: Throwable) {
        if (key?.let(isCurrent) != true) return
        mutableState.value = OutboundProbeState(mutableState.value.identity, error = failure.message ?: failure.javaClass.simpleName)
    }

    fun replayConfirmedRoute(picks: Map<String, String>, coreEpoch: ULong) {
        if (!routePending) synchronize(picks, coreEpoch)
    }

    fun synchronize(picks: Map<String, String>, coreEpoch: ULong? = null,
        routeVersion: ULong? = null, held: Boolean? = null) {
        val reset = coreEpoch != null && this.coreEpoch != coreEpoch
        val moved = routeVersion != null && this.routeVersion != routeVersion
        if (reset || moved) {
            cancelTask()
            notBefore = now() + if (reset) 300 else 1500
            bypassThrottle = reset
            if (reset || held == false) {
                cached = null
                failed = false
                mutableState.value = OutboundProbeState(mutableState.value.identity)
            } else if (held == true) cached = cached?.copy(coreEpoch = coreEpoch, routeVersion = routeVersion)
        }
        this.coreEpoch = coreEpoch ?: this.coreEpoch
        this.routeVersion = routeVersion ?: this.routeVersion
        routePending = false
        this.picks = picks.toMap()
        synced = true
        if (cached?.hits?.isNotEmpty() == true && held == null && moved) synced = false
        changed()
    }

    fun beginSelection(): Long = (++selectionId).also { selections += it; changed() }

    fun endSelection(id: Long, group: String, proxy: String, succeeded: Boolean) {
        if (!selections.remove(id)) return
        if (succeeded) picks = picks + (group to proxy)
        changed()
    }

    fun networkChanged() {
        cancelTask(); cached = null; failed = false; bypassThrottle = true; notBefore = now() + 300
        mutableState.value = OutboundProbeState(mutableState.value.identity)
        changed()
    }

    fun refresh() {
        if (task != null && requestStarted) return
        if (task != null) cancelTask()
        forced = true
        bypassThrottle = true
        notBefore = 0
        failed = false
        staleUnder = null
        changed()
    }

    private fun changed() {
        signals.value++
        if (!synced || selections.isNotEmpty()) return
        val current = key?.takeIf(isCurrent) ?: return
        cached?.takeUnless { fresh(it) }?.let {
            cached = null
            cancelTask()
            failed = false
            staleUnder = null
            mutableState.value = OutboundProbeState(mutableState.value.identity)
        }
        if (cached != null && task == null && !forced) mutableState.value = OutboundProbeState(cached?.identity)
        if (active && task == null && (forced || (cached == null && !failed))) start(current)
    }

    private fun fresh(answer: OutboundAnswer): Boolean = if (answer.hits.isNotEmpty())
        answer.coreEpoch == coreEpoch && answer.routeVersion == routeVersion
    else answer.chains.indices.drop(1).all { index -> picks[answer.chains[index]]?.let { it == answer.chains[index - 1] } != false }

    private fun now(): Long = System.nanoTime() / 1_000_000

    private fun routeOf(chains: List<String>, picks: Map<String, String>): List<Pair<String, String?>> =
        chains.drop(1).map { it to picks[it] }

    private fun start(current: K) {
        forced = false
        val bypass = bypassThrottle
        bypassThrottle = false
        val due = maxOf(notBefore, if (bypass || lastStarted == Long.MIN_VALUE) 0 else lastStarted + 5000)
        retiring.removeAll { it.isCompleted }
        val predecessors = retiring.toList()
        val id = ++run
        mutableState.value = OutboundProbeState(mutableState.value.identity, loading = true)
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            var stale = false
            try {
                predecessors.joinAll()
                delay((due - now()).coerceAtLeast(0))
                if (!owns(current, id) || !active) return@launch
                lastStarted = now()
                requestStarted = true
                val answer = read(current)
                require(answer.chains.isNotEmpty()) { "Core returned no outbound route chain" }
                // An answer may finish hidden, but needs a new snapshot before it can become fresh.
                signals.first { !owns(current, id) || (synced && selections.isEmpty()) }
                if (!owns(current, id)) return@launch
                if (fresh(answer)) {
                    cached = answer.copy(chains = answer.chains.toList())
                    failed = false
                    failedChain = null
                    staleUnder = null
                    mutableState.value = OutboundProbeState(answer.identity)
                } else {
                    cached = null
                    mutableState.value = OutboundProbeState(mutableState.value.identity, loading = true)
                    val previous = staleUnder
                    val hops = answer.chains.drop(1) + failedChain.orEmpty().drop(1)
                    stale = previous == null || hops.any { previous[it] != picks[it] }
                    staleUnder = picks
                    failedChain = answer.chains
                    if (!stale) throw IOException("Outbound route changed during lookup")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(current, id)) {
                    failedChain = cached?.chains ?: failedChain
                    cached = null
                    failed = true
                    mutableState.value = OutboundProbeState(mutableState.value.identity, error = failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                if (owns(current, id)) {
                    task = null
                    if (stale) changed()
                }
            }
        }
        task = pending
        requestStarted = false
        pending.start()
    }

    private fun owns(current: K, id: Long): Boolean = scope.isActive && key == current && run == id && isCurrent(current)

    private fun cancelTask() {
        run++
        task?.let { it.cancel(); retiring += it }
        task = null
    }

    override fun close() {
        key = null
        cancelTask()
        scope.cancel()
    }
}
