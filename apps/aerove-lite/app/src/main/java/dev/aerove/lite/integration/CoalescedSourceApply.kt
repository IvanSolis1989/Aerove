package dev.aerove.lite.integration

import dev.aerove.lite.resources.ResourceApplyReceipt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Standard source changes share one 600 ms debounce; an awaiting caller receives actual runtime readback. */
class CoalescedSourceApply(private val scope: CoroutineScope, private val canWork: () -> Boolean,
    private val apply: suspend (isCurrent: () -> Boolean) -> ResourceApplyReceipt,
    private val onFailure: (Exception) -> Unit) {
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val waiting = mutableSetOf<CompletableDeferred<ResourceApplyReceipt>>()
    private var job: Job? = null
    private var generation = 0L
    private var paused = false
    private var closed = false

    fun request() { enqueue(null) }
    suspend fun requestAndAwait(): ResourceApplyReceipt {
        val result = CompletableDeferred<ResourceApplyReceipt>()
        enqueue(result)
        try { return result.await() } finally { synchronized(gate) { waiting -= result } }
    }
    private fun enqueue(result: CompletableDeferred<ResourceApplyReceipt>?) = synchronized(gate) {
        if (paused || closed || !canWork()) {
            result?.completeExceptionally(CancellationException("Source application is unavailable"))
            return@synchronized
        }
        if (result != null) waiting += result
        val token = ++generation
        job?.cancel()
        val next = scope.launch(start = CoroutineStart.LAZY) {
            fun current() = synchronized(gate) { !closed && !paused && token == generation } && canWork()
            try {
                delay(600)
                currentCoroutineContext().ensureActive()
                if (!current()) throw CancellationException("Source application was superseded")
                val receipt = apply(::current)
                currentCoroutineContext().ensureActive()
                synchronized(gate) {
                    if (!current()) throw CancellationException("Source application was superseded")
                    waiting.toList().forEach { it.complete(receipt) }; waiting.clear()
                }
            } catch (cancelled: CancellationException) {
                synchronized(gate) {
                    if (token == generation) { waiting.toList().forEach { it.completeExceptionally(cancelled) }; waiting.clear() }
                }
                throw cancelled
            } catch (failure: Exception) {
                synchronized(gate) {
                    if (token == generation) { waiting.toList().forEach { it.completeExceptionally(failure) }; waiting.clear(); onFailure(failure) }
                }
            }
        }
        jobs += next
        next.invokeOnCompletion { failure -> synchronized(gate) {
            jobs -= next
            if (failure != null && token == generation) { waiting.toList().forEach { it.completeExceptionally(failure) }; waiting.clear() }
        } }
        job = next
        next.start()
    }
    fun cancelPending() = synchronized(gate) {
        generation++; jobs.toList().forEach(Job::cancel)
        waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application was cancelled")) }; waiting.clear()
    }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) {
            paused = true; generation++
            waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application is paused")) }; waiting.clear()
            jobs.toList()
        }
        pending.forEach(Job::cancel); pending.joinAll()
    } }
    suspend fun resumeAfterMaintenance() = maintenance.withLock { synchronized(gate) { check(!closed); paused = false } }
    fun close() = synchronized(gate) {
        closed = true; generation++; jobs.toList().forEach(Job::cancel)
        waiting.toList().forEach { it.completeExceptionally(CancellationException("Source application is closed")) }; waiting.clear()
    }
}
