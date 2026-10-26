package dev.aerove.lite.proxies

import dev.aerove.lite.data.CoreApiClient
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ProxyDelayProgress(val pending: Set<ProxyDelayTarget> = emptySet(), val running: Set<ProxyDelayTarget> = emptySet(),
    val results: Map<ProxyDelayTarget, Int> = emptyMap())

/** Owned by the visible proxy session; core/route/background changes call cancel(). */
class ProxyPresentationDelayTests(private val core: CoreApiClient, private val scope: CoroutineScope,
    private val beforeStart: suspend () -> Unit = {}) : Closeable {
    private val pool = Semaphore(32)
    private val mutable = MutableStateFlow(ProxyDelayProgress())
    val state: StateFlow<ProxyDelayProgress> = mutable.asStateFlow()
    private val jobs = mutableSetOf<Job>()
    private val retiring = mutableListOf<Job>()
    @Volatile private var epoch = 0L
    private var closed = false
    private var buffered = ProxyDelayProgress()
    private var flushJob: Job? = null

    @Synchronized fun start(targets: List<ProxyDelayTarget>, timeoutMillis: Int = 8000): Job {
        check(!closed)
        require(timeoutMillis in 1..60000)
        val acquired = targets.distinct().filterNot { it in buffered.pending }.toSet()
        val revision = epoch
        retiring.removeAll { it.isCompleted }
        val predecessors = retiring.toList()
        buffered = buffered.copy(pending = buffered.pending + acquired)
        flush()
        val job = scope.launch {
            try {
                beforeStart()
                predecessors.forEach { it.join() }
                coroutineScope {
                    acquired.map { target -> async {
                        pool.withPermit {
                            if (revision != epoch) return@withPermit
                            publish(revision) { it.copy(running = it.running + target) }
                            try {
                                val result = try { core.delay(target.proxy, target.url, timeoutMillis) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: IOException) { -1 }
                                publish(revision) { it.copy(results = it.results + (target to result)) }
                            } finally { publish(revision) { it.copy(running = it.running - target) } }
                        }
                    } }.awaitAll()
                }
            } finally { finish(revision, acquired) }
        }
        jobs += job
        job.invokeOnCompletion { synchronized(this) { jobs -= job } }
        return job
    }

    @Synchronized private fun publish(revision: Long, change: (ProxyDelayProgress) -> ProxyDelayProgress) {
        if (revision != epoch || closed) return
        buffered = change(buffered)
        if (flushJob == null) {
            val pending = scope.launch(start = CoroutineStart.LAZY) {
                delay(300)
                synchronized(this@ProxyPresentationDelayTests) {
                    if (revision == epoch && !closed) { flushJob = null; mutable.value = buffered }
                }
            }
            flushJob = pending
            pending.start()
        }
    }

    @Synchronized private fun finish(revision: Long, acquired: Set<ProxyDelayTarget>) {
        if (revision != epoch || closed) return
        buffered = buffered.copy(pending = buffered.pending - acquired, running = buffered.running - acquired)
        flush()
    }

    private fun flush() {
        flushJob?.cancel()
        flushJob = null
        mutable.value = buffered
    }

    @Synchronized fun cancel(clearResults: Boolean = false) {
        epoch++
        retiring += jobs.toList()
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        flushJob?.cancel(); flushJob = null
        buffered = ProxyDelayProgress(results = if (clearResults) emptyMap() else mutable.value.results)
        mutable.value = buffered
    }
    @Synchronized internal fun retire(): List<Job> {
        val pending = (retiring + jobs + listOfNotNull(flushJob)).distinct()
        closed = true; cancel(true)
        return pending
    }
    override fun close() { retire() }
}
