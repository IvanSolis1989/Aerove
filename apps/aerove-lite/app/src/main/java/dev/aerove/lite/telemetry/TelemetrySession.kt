package dev.aerove.lite.telemetry

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

// Preserve lifecycle boundaries when StateFlow conflates a rapid pause/resume.
internal data class TelemetryVisibility(val active: Boolean = false, val generation: Long = 0) {
    fun changed(active: Boolean): TelemetryVisibility =
        if (this.active == active) this else TelemetryVisibility(active, generation + 1)
}

internal fun <T : Any> sessionBindings(
    visible: Flow<TelemetryVisibility>,
    endpoints: Flow<T?>,
): Flow<Pair<T, Long>?> = combine(visible, endpoints) { foreground, endpoint ->
    if (foreground.active && endpoint != null) endpoint to foreground.generation else null
}.distinctUntilChanged()

internal class TelemetrySession(parent: CoroutineScope, private val isCurrent: () -> Boolean) : Closeable {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val tasks = ConcurrentHashMap<String, Job>()

    val current: Boolean get() = lifetime.isActive && isCurrent()

    fun accept(update: () -> Unit) {
        if (current) update()
    }

    fun launch(key: String, finished: () -> Unit = {}, work: suspend CoroutineScope.() -> Unit): Job? {
        if (!current) return null
        val task = scope.launch(start = CoroutineStart.LAZY, block = work)
        if (tasks.putIfAbsent(key, task) != null) {
            task.cancel()
            return null
        }
        task.invokeOnCompletion {
            tasks.remove(key, task)
            finished()
        }
        task.start()
        return task
    }

    fun <T> observe(
        key: String,
        enabled: Flow<Boolean>,
        source: () -> Flow<T>,
        failure: (Throwable) -> Unit,
        update: (T) -> Unit,
    ): Job? = launch(key) {
        enabled.distinctUntilChanged().collectLatest { active ->
            if (active && current) source()
                .retryWhen { _, attempt ->
                    if (!current || attempt >= 3) false else {
                        delay(1000)
                        current
                    }
                }
                .catch { error -> accept { failure(error) } }
                .collect { value -> accept { update(value) } }
        }
    }

    fun <T> observeReadOnly(key: String, demand: Flow<TelemetryVisibility>,
        source: () -> Flow<T>, valid: (TelemetryVisibility) -> Boolean,
        failure: (Throwable) -> Unit, update: (T) -> Unit): Job? = launch(key) {
        demand.distinctUntilChanged().collectLatest { lease ->
            if (lease.active && current && valid(lease)) source()
                .retryWhen { _, attempt ->
                    if (!current || !valid(lease) || attempt >= 3) false else {
                        delay(1000); current && valid(lease)
                    }
                }
                .catch { error -> if (valid(lease)) accept { failure(error) } }
                .collect { value -> if (valid(lease)) accept { update(value) } }
        }
    }

    override fun close() { scope.cancel() }
}
