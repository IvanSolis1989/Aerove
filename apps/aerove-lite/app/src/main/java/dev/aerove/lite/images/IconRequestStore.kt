package dev.aerove.lite.images

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

internal data class IconRequestKey(val url: String, val userAgent: String, val checkCertificate: Boolean)
internal data class WeightedIcon<T>(val value: T, val bytes: Int, val validUntil: Long = Long.MAX_VALUE, val cacheable: Boolean = true)

internal class IconRequestStore<T>(
    private val scope: CoroutineScope,
    private val limit: Int = 16 shl 20,
    concurrency: Int = 8,
    private val preview: suspend (IconRequestKey) -> WeightedIcon<T>? = { null },
    private val fetch: suspend (IconRequestKey) -> WeightedIcon<T>,
) {
    private class Flight<T>(val result: Deferred<T>, var readers: Int, val preview: MutableStateFlow<T?>)
    private val mutex = Mutex()
    private val slots = Semaphore(concurrency)
    private val cached = LinkedHashMap<IconRequestKey, WeightedIcon<T>>()
    private val flights = HashMap<IconRequestKey, Flight<T>>()
    private var bytes = 0

    suspend fun load(key: IconRequestKey): T = observe(key).let { flow ->
        var result: T? = null
        flow.collect { result = it }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    fun observe(key: IconRequestKey): Flow<T> = channelFlow {
        val flight = mutex.withLock {
            cached.remove(key)?.let {
                if (it.validUntil > System.currentTimeMillis()) { cached[key] = it; trySend(it.value); return@channelFlow }
                bytes -= it.bytes
            }
            flights[key]?.let { it.readers++; return@withLock it }
            lateinit var created: Flight<T>
            val updates = MutableStateFlow<T?>(null)
            val result = scope.async(start = CoroutineStart.LAZY) {
                try {
                    val loaded = slots.withPermit {
                        val prior = preview(key)
                        updates.value = prior?.value
                        if (prior != null && prior.validUntil > System.currentTimeMillis()) prior else fetch(key)
                    }
                    require(loaded.bytes >= 0)
                    currentCoroutineContext().ensureActive()
                    mutex.withLock {
                        if (flights[key] === created && created.readers > 0 && loaded.cacheable && loaded.validUntil > System.currentTimeMillis() && loaded.bytes <= limit) {
                            cached.put(key, loaded)?.let { bytes -= it.bytes }
                            bytes += loaded.bytes
                            while (bytes > limit || cached.size > 256) {
                                val oldest = cached.entries.first()
                                bytes -= oldest.value.bytes
                                cached.remove(oldest.key)
                            }
                        }
                    }
                    loaded.value
                } finally {
                    withContext(NonCancellable) { mutex.withLock { if (flights[key] === created) flights.remove(key) } }
                }
            }
            Flight(result, 1, updates).also { created = it; flights[key] = it }
        }
        flight.result.start()
        var previewSent = false
        val previews = launch { flight.preview.filterNotNull().collect { send(it); previewSent = true } }
        try {
            val final = flight.result.await()
            previews.cancelAndJoin()
            if (!previewSent) flight.preview.value?.let { send(it) }
            if (final !== flight.preview.value) send(final)
        }
        finally {
            withContext(NonCancellable) {
                val cancel = mutex.withLock {
                    if (flights[key] !== flight) false else {
                        flight.readers--
                        if (flight.readers == 0) { flights.remove(key); true } else false
                    }
                }
                if (cancel) flight.result.cancel()
            }
        }
    }

    suspend fun invalidate(key: IconRequestKey) = mutex.withLock { cached.remove(key)?.let { bytes -= it.bytes }; Unit }
}
