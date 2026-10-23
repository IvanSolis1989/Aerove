package dev.aerove.lite.proxies

import dev.aerove.lite.data.CoreApiClient
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
class ProxyPresentationDelayTests(private val core: CoreApiClient, private val scope: CoroutineScope) : Closeable {
    private val pool = Semaphore(32)
    private val mutable = MutableStateFlow(ProxyDelayProgress())
    val state: StateFlow<ProxyDelayProgress> = mutable.asStateFlow()
    private val jobs = mutableSetOf<Job>()
    @Volatile private var epoch = 0L
    private var closed = false

    @Synchronized fun start(targets: List<ProxyDelayTarget>, timeoutMillis: Int = 8000): Job {
        check(!closed)
        require(timeoutMillis in 1..60000)
        val acquired = targets.distinct().filterNot { it in mutable.value.pending }.toSet()
        val revision = epoch
        mutable.update { it.copy(pending = it.pending + acquired) }
        val job = scope.launch {
            try {
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
            } finally { publish(revision) { it.copy(pending = it.pending - acquired, running = it.running - acquired) } }
        }
        jobs += job
        job.invokeOnCompletion { synchronized(this) { jobs -= job } }
        return job
    }

    @Synchronized private fun publish(revision: Long, change: (ProxyDelayProgress) -> ProxyDelayProgress) {
        if (revision == epoch) mutable.update(change)
    }

    @Synchronized fun cancel(clearResults: Boolean = false) {
        epoch++
        jobs.toList().forEach(Job::cancel)
        jobs.clear()
        mutable.value = ProxyDelayProgress(results = if (clearResults) emptyMap() else mutable.value.results)
    }
    @Synchronized override fun close() { closed = true; cancel(true) }
}
