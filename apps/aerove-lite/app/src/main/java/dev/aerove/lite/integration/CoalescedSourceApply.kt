package dev.aerove.lite.integration

import dev.aerove.lite.resources.ResourceApplyReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One latest source intent. Background defers work and retains demand; a real resume drains it once. */
class CoalescedSourceApply(private val scope: CoroutineScope, private val canWork: () -> Boolean,
    private val apply: suspend (isCurrent: () -> Boolean) -> ResourceApplyReceipt,
    private val onFailure: (Exception) -> Unit) {
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val waiting = mutableSetOf<CompletableDeferred<ResourceApplyReceipt>>()
    private var job: Job? = null
    private var generation = 0L
    private var pending = false
    private var foreground = true
    private var paused = false
    private var closed = false

    fun request() { enqueue(null) }
    suspend fun requestAndAwait(): ResourceApplyReceipt {
        val result = CompletableDeferred<ResourceApplyReceipt>()
        enqueue(result)
        try { return result.await() } finally { synchronized(gate) { waiting -= result } }
    }
    private fun enqueue(result: CompletableDeferred<ResourceApplyReceipt>?) = synchronized(gate) {
        if (paused || closed || !scope.isActive || !canWork()) {
            result?.completeExceptionally(CancellationException("Source application is unavailable"))
            return@synchronized
        }
        if (result != null) waiting += result
        pending = true
        if (foreground) schedule(600)
    }
    // Invoked under gate; every successor joins all retired work before preparing another generation.
    private fun schedule(debounce: Long) {
        val token = ++generation
        val predecessors = jobs.toList()
        predecessors.forEach(Job::cancel)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = synchronized(gate) { !closed && !paused && foreground && token == generation } && canWork()
            try {
                predecessors.joinAll()
                delay(debounce)
                currentCoroutineContext().ensureActive()
                if (!current()) throw CancellationException("Source application was superseded")
                val receipt = apply(::current)
                currentCoroutineContext().ensureActive()
                val accepted = synchronized(gate) {
                    if (!current()) throw CancellationException("Source application was superseded")
                    pending = false
                    waiting.toList().also { waiting.clear() }
                }
                accepted.forEach { it.complete(receipt) }
            } catch (cancelled: CancellationException) {
                val rejected = synchronized(gate) {
                    if (token == generation) waiting.toList().also { waiting.clear() } else emptyList()
                }
                rejected.forEach { it.completeExceptionally(cancelled) }
                throw cancelled
            } catch (failure: Exception) {
                val rejected = synchronized(gate) {
                    if (token == generation) { pending = true; waiting.toList().also { waiting.clear() } } else null
                }
                rejected?.forEach { it.completeExceptionally(failure) }
                if (rejected != null) onFailure(failure)
            } finally { synchronized(gate) { if (token == generation) job = null } }
        }
        jobs += next
        next.invokeOnCompletion { failure ->
            val abandoned = synchronized(gate) {
                jobs -= next
                if (failure != null && token == generation) waiting.toList().also { waiting.clear() } else emptyList()
            }
            if (failure != null) abandoned.forEach { it.completeExceptionally(failure) }
        }
        job = next
        next.start()
    }
    fun foreground(active: Boolean) = synchronized(gate) {
        if (foreground == active) return@synchronized
        foreground = active
        if (!active) { generation++; if (job != null) pending = true; jobs.toList().forEach(Job::cancel); job = null }
        else if (pending && !closed && !paused && canWork()) schedule(0)
    }
    fun cancelPending() = synchronized(gate) {
        pending = false; generation++; jobs.toList().forEach(Job::cancel); job = null
        waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application was cancelled")) }; waiting.clear()
    }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val retiring = synchronized(gate) {
            paused = true; pending = false; generation++; job = null
            waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application is paused")) }; waiting.clear()
            jobs.toList()
        }
        retiring.forEach(Job::cancel); retiring.joinAll()
    } }
    suspend fun resumeAfterMaintenance() = maintenance.withLock { synchronized(gate) { check(!closed); paused = false } }
    fun close() = synchronized(gate) {
        closed = true; pending = false; generation++; jobs.toList().forEach(Job::cancel); job = null
        waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application is closed")) }; waiting.clear()
    }
}
