package dev.aerove.lite.telemetry

import dev.aerove.lite.DashboardCounts
import dev.aerove.lite.Traffic
import dev.aerove.lite.observability.ObservabilityState
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DASHBOARD_TRAFFIC_SAMPLE_LIMIT = 30

internal fun appendDashboardTraffic(samples: List<Traffic>, sample: Traffic): List<Traffic> =
    samples.takeLast(DASHBOARD_TRAFFIC_SAMPLE_LIMIT - 1) + sample

internal class DashboardConnectionSummary<K : Any>(parent: CoroutineScope,
    private val read: suspend (K) -> Int, private val isCurrent: (K) -> Boolean) : Closeable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val mutable = MutableStateFlow<Int?>(null)
    val state = mutable.asStateFlow()
    private var key: K? = null
    private var active = false
    private var task: Job? = null
    private var generation = 0L

    fun bind(value: K?) {
        if (key == value) return
        cancel()
        key = value
        restart()
    }

    fun active(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) cancel() else restart()
    }

    private fun restart() {
        val current = key?.takeIf(isCurrent) ?: return
        if (!active || task != null || !scope.isActive) return
        val requested = ++generation
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (owns(current, requested)) {
                    try {
                        val count = read(current)
                        require(count >= 0)
                        if (owns(current, requested)) mutable.value = count
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (owns(current, requested)) mutable.value = null }
                    if (!owns(current, requested)) return@launch
                    delay(2000)
                }
            } finally { if (generation == requested) task = null }
        }
        task = pending
        pending.start()
    }

    private fun owns(value: K, requested: Long): Boolean =
        scope.isActive && active && generation == requested && key == value && isCurrent(value)

    private fun cancel() {
        generation++
        task?.cancel()
        task = null
        mutable.value = null
    }

    override fun close() { active = false; key = null; cancel(); scope.cancel() }
}

internal fun DashboardCounts.withDashboardHistory(history: ObservabilityState): DashboardCounts = copy(
    requestCount = history.snapshot.requestCount.takeIf { history.observationsAvailable },
    dnsCount = history.snapshot.dnsCount.takeIf { history.observationsAvailable },
    historyTruncated = history.snapshot.truncated,
)
