package dev.aerove.lite.telemetry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

internal class GroupRefresh<T>(
    private val session: TelemetrySession,
    private val read: suspend () -> T,
    private val failure: (Throwable) -> Unit,
    private val update: (T) -> Unit,
    private val retryIf: (T) -> Boolean = { false },
    private val healthUpdate: (T) -> Unit = update,
) {
    private data class Request(val stamp: Long, val route: Boolean, val version: ULong?)
    private val requests = Channel<Request>(Channel.CONFLATED)
    private var requested = 0L
    private var revision: ULong? = null
    private var refreshedRoute: ULong? = null
    val routeVersion: ULong? get() = refreshedRoute
    private var failed = false
    private var initialized = false
    private var healthPending = false
    private var testing = false
    private var trailing: Job? = null
    private var readActive = true
    private val readSignal = MutableStateFlow(true)
    private var reading: Job? = null

    init {
        val job = session.launch("groups-refresh", { requests.close() }) {
            for (request in requests) {
                readSignal.first { it }
                for (attempt in 0 until 3) {
                    if (!session.current || !readActive || request.stamp != requested) break
                    try {
                        val value = supervisorScope {
                            val task = async(start = CoroutineStart.LAZY) { read() }
                            reading = task; task.start()
                            try { task.await() } finally {
                                if (!task.isCompleted) { task.cancel(); withContext(NonCancellable) { task.join() } }
                                if (reading === task) reading = null
                            }
                        }
                        if (!session.current || !readActive || request.stamp != requested) break
                        if (attempt == 2 || !retryIf(value)) {
                            failed = false
                            if (request.route) refreshedRoute = request.version
                            session.accept { if (request.route) update(value) else healthUpdate(value) }
                            break
                        }
                    } catch (cancelled: CancellationException) {
                        if (!currentCoroutineContext().isActive || (readActive && request.stamp == requested)) throw cancelled
                        break
                    }
                    catch (error: Exception) {
                        if (!session.current || !readActive || request.stamp != requested) break
                        if (attempt == 2) { failed = true; session.accept { failure(error) }; break }
                    }
                    delay(200)
                }
            }
        }
        if (job == null) requests.close()
    }

    fun readDemand(value: Boolean) {
        if (readActive == value) return
        readActive = value; requested++
        reading?.cancel(); trailing?.cancel(); trailing = null
        readSignal.value = value
        if (value) { healthPending = false; request() }
    }

    fun initial() { if (!initialized) { initialized = true; request() } }
    fun request() { enqueue(true, null) }
    private fun enqueue(route: Boolean, version: ULong?) {
        if (session.current) requests.trySend(Request(++requested, route, version))
    }

    fun changed(revision: ULong, routeVersion: ULong = revision): Boolean {
        if (!session.current || (this.revision == revision && !failed)) return false
        this.revision = revision
        if (refreshedRoute != routeVersion || failed) {
            healthPending = false; trailing?.cancel(); trailing = null
            enqueue(true, routeVersion)
            return true
        }
        healthPending = true
        trail()
        return false
    }

    fun testing(value: Boolean) {
        if (testing == value) return
        testing = value
        if (value) { trailing?.cancel(); trailing = null }
        else if (healthPending) { healthPending = false; enqueue(false, refreshedRoute) }
    }

    private fun trail() {
        trailing?.cancel(); trailing = null
        if (!readActive || testing || !healthPending) return
        trailing = session.launch("groups-health-$revision") {
            delay(300)
            trailing = null
            if (!testing && healthPending) { healthPending = false; enqueue(false, refreshedRoute) }
        }
    }
}
