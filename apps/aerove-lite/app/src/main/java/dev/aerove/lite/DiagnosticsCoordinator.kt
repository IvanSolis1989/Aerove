package dev.aerove.lite

import dev.aerove.lite.data.MemorySnapshot
import dev.aerove.lite.data.ServiceCheck
import dev.aerove.lite.data.ServiceTarget
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import dev.aerove.lite.data.RouteHit
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Main-thread owner: service demand is event driven; only visible memory is periodically sampled. */
internal class DiagnosticsCoordinator<K : Any>(
    parent: CoroutineScope,
    private val readServices: suspend (K, List<ServiceTarget>) -> List<ServiceCheck>,
    private val readMemory: suspend (K) -> MemorySnapshot,
    private val isCurrent: (K) -> Boolean,
    private val sameSession: (K, K) -> Boolean = { a, b -> a == b },
) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(DiagnosticsState())
    val state = mutableState.asStateFlow()
    private var key: K? = null
    private var retainedKey: K? = null
    private var backgrounded = false
    private val notBefore = mutableMapOf<String, Long>()
    private val lastStarted = mutableMapOf<String, Long>()
    private val bypass = mutableSetOf<String>()
    private val retiring = mutableMapOf<String, MutableList<Job>>()
    fun routeHits(): Map<String, List<RouteHit>> = answers.mapValues { it.value.hits }
    fun runtimeChanged() {
        generation++; tasks.keys.toList().forEach(::cancelService)
        retainedKey = null; synced = false; epoch = null; version = null
        answers.clear(); failed.clear(); forced.clear(); explicit.clear()
        stopMemory(); mutableState.value = DiagnosticsState(); publishServices()
    }
    fun foreground(active: Boolean) {
        if (!active) { backgrounded = true; tasks.keys.toList().forEach(::cancelService) }
        else if (backgrounded) {
            backgrounded = false
            failed.toList().forEach { answers.remove(it) }; failed.clear()
            publishServices(); launchDue()
        }
    }
    fun networkChanged() {
        tasks.keys.toList().forEach(::cancelService)
        answers.clear(); failed.clear()
        settings.enabledServiceIds().forEach { notBefore[it] = now() + 300; bypass += it }
        publishServices(); launchDue()
    }
    private fun now(): Long = System.nanoTime() / 1_000_000
    private var settings = LiteSettings()
    private var watched = emptySet<String>()
    private var epoch: ULong? = null
    private var version: ULong? = null
    private var picks = emptyMap<String, String>()
    private var synced = false
    private val routeSignals = MutableStateFlow(0L)
    private var generation = 0L
    private val answers = mutableMapOf<String, ServiceCheck>()
    private val failed = mutableSetOf<String>()
    private val forced = mutableSetOf<String>()
    private val explicit = mutableSetOf<String>()
    private val tasks = mutableMapOf<String, Job>()
    private val waiting = mutableSetOf<String>()
    private val requests = mutableMapOf<String, Long>()
    private var sequence = 0L
    private var memoryWanted = false
    private var memoryTask: Job? = null
    private var memoryGeneration = 0L

    fun settings(value: LiteSettings) {
        settings = value.normalized()
        val enabled = settings.enabledServiceIds().toSet()
        tasks.keys.toList().filterNot { it in enabled }.forEach { cancelService(it); explicit.remove(it) }
        forced.retainAll(enabled)
        publishServices()
        launchDue()
    }

    fun bind(value: K?) {
        if (key == value) return
        generation++
        tasks.keys.toList().forEach(::cancelService)
        key = value
        synced = false
        val reset = value != null && retainedKey?.let { !sameSession(it, value) } != false
        if (reset) {
            epoch = null; version = null; picks = emptyMap()
            answers.clear(); failed.clear(); forced.clear(); explicit.clear(); lastStarted.clear()
            settings.enabledServiceIds().forEach { notBefore[it] = now() + 300; bypass += it }
            mutableState.value = DiagnosticsState()
        }
        if (value != null) retainedKey = value
        stopMemory()
        publishServices()
        startMemory()
    }

    fun awaitingRoute() {
        synced = false
        // Keep calls and cached results until a coherent snapshot judges their actual chains.
        publishServices()
    }

    fun needsRouteSync(coreEpoch: ULong, routeVersion: ULong): Boolean = !synced ||
        epoch == null || coreEpoch != epoch!! || (coreEpoch == epoch && routeVersion != (version ?: 0uL))

    fun route(coreEpoch: ULong, routeVersion: ULong, selections: Map<String, String>, held: Map<String, Boolean> = emptyMap()) {
        // Independent HTTP completions must not move the host back behind a newer snapshot.
        val previousEpoch = epoch
        if (previousEpoch != null && (coreEpoch < previousEpoch ||
            (coreEpoch == previousEpoch && version?.let { routeVersion < it } == true))) return
        val reset = epoch != coreEpoch
        val changed = reset || version != routeVersion || picks != selections
        epoch = coreEpoch
        version = routeVersion
        picks = selections.toMap()
        synced = true
        routeSignals.value++
        if (changed) {
            // Pending calls carry the old launch route, even when a changed group was unrelated.
            forced += tasks.keys.filter { it in explicit }
            tasks.keys.toList().forEach(::cancelService)
            answers.keys.toList().forEach { id ->
                val answer = answers.getValue(id)
                if (!reset && held[id] == true) answers[id] = answer.copy(coreEpoch = coreEpoch, routeVersion = routeVersion)
                else if (reset || held[id] == false || !fresh(answer)) { answers.remove(id); failed.remove(id) }
            }
            settings.enabledServiceIds().forEach { id ->
                notBefore[id] = now() + if (reset) 300 else 1500
                if (reset) bypass += id
            }
            if (reset) failed.clear()
        }
        publishServices()
        launchDue()
    }

    fun routeFailed(failure: Throwable) {
        synced = false
        forced += tasks.keys.filter { it in explicit }
        tasks.keys.toList().forEach(::cancelService)
        mutableState.update { it.copy(serviceError = failure.message ?: failure.javaClass.simpleName) }
        publishServices()
    }

    fun watch(ids: Set<String>) {
        val previous = watched
        watched = ids.filterTo(linkedSetOf()) { it in serviceTargetIds }
        (previous - watched).forEach(::cancelService)
        if (watched.isEmpty()) {
            tasks.keys.toList().forEach(::cancelService)
            forced.clear()
            explicit.clear()
        }
        publishServices()
        launchDue()
    }

    fun refresh(ids: Set<String>) {
        ids.filter { it in settings.enabledServiceIds() }.forEach { id ->
            if (id in waiting) cancelService(id)
            if (id !in tasks) {
                failed.remove(id)
                forced += id
                explicit += id
                bypass += id
                notBefore[id] = 0
            }
        }
        launchDue()
    }

    fun memory(active: Boolean) {
        if (memoryWanted == active) return
        memoryWanted = active
        if (active) startMemory() else stopMemory()
    }

    private fun launchDue() {
        val current = key?.takeIf(isCurrent) ?: return
        if (!synced) return
        settings.enabledServiceIds().filter { it !in tasks && (it in forced ||
            (it in watched && it !in failed && it !in answers)) }.forEach {
                forced -= it
                startService(current, it)
            }
    }

    private fun fresh(answer: ServiceCheck): Boolean = answer.coreEpoch == epoch &&
        if (answer.hits.isNotEmpty() || answer.chains.isEmpty()) answer.routeVersion == version else answer.chains.indices.drop(1).all { index ->
            picks[answer.chains[index]]?.let { it == answer.chains[index - 1] } != false
        }

    private fun startService(current: K, id: String) {
        val target = ServiceTarget.byId(id) ?: return
        val request = ++sequence
        val session = generation
        val skipThrottle = bypass.remove(id)
        val due = maxOf(notBefore[id] ?: 0, if (skipThrottle) 0 else lastStarted[id]?.plus(5000) ?: 0)
        retiring[id]?.removeAll { it.isCompleted }
        val predecessors = retiring[id].orEmpty().toList()
        requests[id] = request
        waiting += id
        val task = scope.launch(start = CoroutineStart.LAZY) {
            try {
                predecessors.joinAll()
                delay((due - now()).coerceAtLeast(0))
                if (!owns(current, session, id, request)) return@launch
                waiting -= id
                lastStarted[id] = now()
                val answer = readServices(current, listOf(target)).single()
                routeSignals.first { !owns(current, session, id, request) || synced }
                if (!owns(current, session, id, request)) return@launch
                if (synced && answer.target == target && fresh(answer)) {
                    answers[id] = answer
                    if (answer.status.id in setOf("failed", "timeout")) failed += id else failed -= id
                    mutableState.update { it.copy(serviceError = null) }
                } else {
                    answers.remove(id)
                    failed += id
                    mutableState.update { it.copy(serviceError = "Service route changed during check") }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (owns(current, session, id, request)) {
                    answers.remove(id)
                    failed += id
                    mutableState.update { it.copy(serviceError = failure.message ?: failure.javaClass.simpleName) }
                }
            } finally {
                if (requests[id] == request) {
                    tasks.remove(id)
                    explicit.remove(id)
                    publishServices()
                }
            }
        }
        tasks[id] = task
        publishServices()
        task.start()
    }

    private fun owns(current: K, session: Long, id: String, request: Long): Boolean =
        scope.isActive && key == current && generation == session && requests[id] == request && isCurrent(current)

    private fun cancelService(id: String) {
        requests.remove(id)
        waiting -= id
        tasks.remove(id)?.let { it.cancel(); retiring.getOrPut(id) { mutableListOf() } += it }
    }

    private fun publishServices() {
        val services = settings.enabledServiceIds().map { id ->
            answers[id]?.let { answer -> ServiceCheckView(id, answer.status.id, answer.region, answer.delayMillis,
                answer.chains.toList(), checkedAt = answer.checkedAtMillis,
                coreEpoch = answer.coreEpoch, routeVersion = answer.routeVersion)
            } ?: ServiceCheckView(id, if (id in failed) "failed" else "pending", checkedAt = 0)
        }
        mutableState.update { it.copy(services = services, serviceLoading = tasks.isNotEmpty(),
            refreshingServices = tasks.keys.toSet()) }
    }

    private fun startMemory() {
        val current = key?.takeIf(isCurrent) ?: return
        if (!memoryWanted || memoryTask != null) return
        val session = generation
        val request = ++memoryGeneration
        memoryTask = scope.launch {
            while (isActive && memoryWanted && key == current && session == generation && isCurrent(current)) {
                mutableState.update { it.copy(memoryLoading = true) }
                try {
                    val sample = readMemory(current)
                    if (request != memoryGeneration || key != current || session != generation || !isCurrent(current)) return@launch
                    mutableState.update { it.copy(memoryInUseBytes = sample.processRssBytes.asDisplayBytes(),
                        memoryLimitBytes = sample.configuredLimitBytes.asDisplayBytes(), memoryScope = "process-rss",
                        memoryLoading = false, memoryError = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (request == memoryGeneration && key == current && session == generation && isCurrent(current)) {
                        mutableState.update { it.copy(memoryLoading = false,
                            memoryError = failure.message ?: failure.javaClass.simpleName) }
                    }
                }
                // Standard ActivePollingMixin delays after completion, never at a fixed overlapping rate.
                delay(2_000)
            }
        }
    }

    private fun stopMemory() {
        memoryGeneration++
        memoryTask?.cancel()
        memoryTask = null
        mutableState.update { it.copy(memoryLoading = false) }
    }

    override fun close() {
        bind(null)
        scope.cancel()
    }
}

private fun ULong?.asDisplayBytes(): Long? = this?.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
