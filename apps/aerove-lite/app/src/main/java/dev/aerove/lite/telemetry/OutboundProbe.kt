package dev.aerove.lite.telemetry

import dev.aerove.lite.OutboundIdentity
import dev.aerove.lite.ProxyGroup
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class OutboundAnswer(val identity: OutboundIdentity, val chains: List<String>)

internal data class OutboundProbeState(
    val identity: OutboundIdentity? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

internal fun List<ProxyGroup>.routePicks(): Map<String, String> =
    filter { it.selected.isNotEmpty() }.associate { it.name to it.selected }

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
    private var picks = emptyMap<String, String>()
    private var cached: OutboundAnswer? = null
    private var task: Job? = null
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
        if (active) failed = false
        changed()
    }

    fun awaitingGroups() {
        synced = false
        changed()
    }

    fun groupsFailed(failure: Throwable) {
        if (key?.let(isCurrent) != true) return
        mutableState.value = OutboundProbeState(error = failure.message ?: failure.javaClass.simpleName)
    }

    fun synchronize(picks: Map<String, String>) {
        val previous = this.picks
        this.picks = picks.toMap()
        synced = true
        if (failed && failedChain?.let { routeOf(it, previous) != routeOf(it, this.picks) } == true) failed = false
        changed()
    }

    fun beginSelection(): Long = (++selectionId).also { selections += it; changed() }

    fun endSelection(id: Long, group: String, proxy: String, succeeded: Boolean) {
        if (!selections.remove(id)) return
        if (succeeded) picks = picks + (group to proxy)
        changed()
    }

    fun refresh() {
        if (task != null) return
        forced = true
        failed = false
        staleUnder = null
        changed()
    }

    private fun changed() {
        signals.value++
        if (!synced || selections.isNotEmpty()) return
        val current = key?.takeIf(isCurrent) ?: return
        cached?.takeUnless { fresh(it.chains) }?.let {
            cached = null
            cancelTask()
            failed = false
            staleUnder = null
            mutableState.value = OutboundProbeState()
        }
        if (cached != null && task == null && !forced) mutableState.value = OutboundProbeState(cached?.identity)
        if (active && task == null && (forced || (cached == null && !failed))) start(current)
    }

    private fun fresh(chains: List<String>): Boolean = chains.indices.drop(1).all { index ->
        val selected = picks[chains[index]]
        selected == null || selected == chains[index - 1]
    }

    private fun routeOf(chains: List<String>, picks: Map<String, String>): List<Pair<String, String?>> =
        chains.drop(1).map { it to picks[it] }

    private fun start(current: K) {
        forced = false
        val id = ++run
        mutableState.value = OutboundProbeState(cached?.identity, loading = true)
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            var stale = false
            try {
                val answer = read(current)
                require(answer.chains.isNotEmpty()) { "Core returned no outbound route chain" }
                // An answer may finish hidden, but needs a new snapshot before it can become fresh.
                signals.first { !owns(current, id) || (synced && selections.isEmpty()) }
                if (!owns(current, id)) return@launch
                if (fresh(answer.chains)) {
                    cached = answer.copy(chains = answer.chains.toList())
                    failed = false
                    failedChain = null
                    staleUnder = null
                    mutableState.value = OutboundProbeState(answer.identity)
                } else {
                    cached = null
                    mutableState.value = OutboundProbeState(loading = true)
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
                    mutableState.value = OutboundProbeState(error = failure.message ?: failure.javaClass.simpleName)
                }
            } finally {
                if (owns(current, id)) {
                    task = null
                    if (stale) changed()
                }
            }
        }
        task = pending
        pending.start()
    }

    private fun owns(current: K, id: Long): Boolean = scope.isActive && key == current && run == id && isCurrent(current)

    private fun cancelTask() {
        run++
        task?.cancel()
        task = null
    }

    override fun close() {
        key = null
        cancelTask()
        scope.cancel()
    }
}
