package dev.aerove.lite.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileMaintenancePolicy(
    val id: String,
    val sourceIdentity: String,
    val isSubscription: Boolean,
    val autoUpdate: Boolean = true,
    val intervalMillis: Long = 86_400_000L,
    val lastUpdatedAtMillis: Long? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= 200)
        require(sourceIdentity.isNotEmpty() && sourceIdentity.length <= 16384)
        require(intervalMillis > 0 && (lastUpdatedAtMillis == null || lastUpdatedAtMillis >= 0))
    }

    override fun toString(): String = "ProfileMaintenancePolicy(id=$id, autoUpdate=$autoUpdate, intervalMillis=$intervalMillis)"
}

class ProfileMaintenanceRequest internal constructor(
    val profileId: String,
    val sourceIdentity: String,
    val isCurrent: () -> Boolean,
) {
    override fun toString(): String = "ProfileMaintenanceRequest(profileId=$profileId)"
}

sealed interface ProfileMaintenanceResult {
    data class Updated(val updatedAtMillis: Long) : ProfileMaintenanceResult
    data class Failed(val error: Throwable) : ProfileMaintenanceResult
    data object Cancelled : ProfileMaintenanceResult
    data object Unavailable : ProfileMaintenanceResult
}

data class ProfileMaintenanceSnapshot(
    val foreground: Boolean,
    val nextCheckAtMillis: Long?,
    val runningProfileIds: Set<String>,
    val invalidatedProfileIds: Set<String>,
    val cachedUpdatedAtMillis: Map<String, Long>,
    val closed: Boolean,
)

class ProfileMaintenance(
    ownerScope: CoroutineScope,
    private val update: suspend (ProfileMaintenanceRequest) -> Long,
    private val onAutomaticError: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMillis: suspend (Long) -> Unit = { delay(it) },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val checkIntervalMillis: Long = 20 * 60 * 1000L,
) : AutoCloseable {
    private val lock = Any()
    private val lifetime = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext + lifetime + dispatcher)
    private val rows = linkedMapOf<String, Row>()
    private var order = emptyList<String>()
    private var foreground = false
    private var started = false
    private var closed = false
    private var generation = 0L
    private var dueAt: Long? = null
    private var due = false
    private var scan: Job? = null
    private var timer: Job? = null
    private val mutableRunning = MutableStateFlow<Set<String>>(emptySet())
    val runningProfileIds = mutableRunning.asStateFlow()

    private class Row(var policy: ProfileMaintenancePolicy?) {
        val gate = Mutex()
        var epoch = 0L
        var receipt: Long? = null
        var invalidated = false
        var active = 0
        var flight: Flight? = null
    }

    private class Flight(val row: Row, val policy: ProfileMaintenancePolicy, val epoch: Long, var manual: Boolean) {
        val result = CompletableDeferred<ProfileMaintenanceResult>()
        var valid = true
        var outcome: ProfileMaintenanceResult = ProfileMaintenanceResult.Cancelled
        var report: Throwable? = null
        lateinit var job: Job
    }

    init {
        require(checkIntervalMillis > 0)
        lifetime.invokeOnCompletion { closeState() }
    }

    fun replacePolicies(policies: List<ProfileMaintenancePolicy>) {
        require(policies.map { it.id }.distinct().size == policies.size)
        val cancel = mutableListOf<Flight>()
        synchronized(lock) {
            if (closed || !lifetime.isActive) return
            val incoming = policies.associateBy { it.id }
            rows.forEach { (id, row) ->
                val replacement = incoming[id]
                if (replacement == null || replacement.sourceIdentity != row.policy?.sourceIdentity) {
                    row.epoch++
                    row.receipt = null
                    row.invalidated = replacement != null && row.active > 0
                    row.flight?.let { it.valid = false; cancel += it }
                    row.flight = null
                }
                row.policy = replacement
            }
            rows.entries.removeAll { it.value.policy == null && it.value.active == 0 }
            policies.forEach { policy -> rows.getOrPut(policy.id) { Row(policy) }.policy = policy }
            order = policies.map { it.id }
        }
        cancelFlights(cancel)
        startScan(regular = false)
    }

    fun setForeground(value: Boolean) {
        val cancel = mutableListOf<Job>()
        val flights = mutableListOf<Flight>()
        var regular = false
        var dirty = false
        synchronized(lock) {
            if (closed || !lifetime.isActive || (started && foreground == value)) return
            val first = !started
            started = true
            foreground = value
            generation++
            timer?.let(cancel::add)
            timer = null
            if (!value) {
                if (first || scan != null) due = true
                scan?.let(cancel::add)
                rows.values.forEach { row ->
                    row.flight?.takeIf { !it.manual && it.valid }?.let { flight ->
                        flight.valid = false
                        row.invalidated = true
                        flights += flight
                    }
                }
            } else {
                if (first) dueAt = addTime(now(), checkIntervalMillis)
                regular = due || (dueAt != null && now() >= dueAt!!)
                dirty = rows.values.any { it.invalidated && it.policy?.let(::automatic) == true }
            }
        }
        cancel.forEach { it.cancel() }
        cancelFlights(flights)
        if (value) {
            if (regular || dirty) startScan(regular) else schedule()
        }
    }

    fun checkDue() = startScan(regular = true)

    fun invalidate(profileId: String) {
        synchronized(lock) {
            if (closed || !lifetime.isActive) return
            val row = rows[profileId] ?: return
            row.receipt = null
            row.invalidated = row.flight?.let { !it.valid || it.result.isCompleted } ?: true
        }
        startScan(regular = false)
    }

    suspend fun refresh(profileId: String): ProfileMaintenanceResult = acquire(profileId, manual = true)?.result?.await()
        ?: ProfileMaintenanceResult.Unavailable

    fun cancel(profileId: String) {
        val flight = synchronized(lock) {
            val row = rows[profileId] ?: return
            row.invalidated = false
            row.flight?.takeIf { it.valid }?.also { it.valid = false }
        }
        if (flight != null) cancelFlights(listOf(flight))
    }

    fun snapshot(): ProfileMaintenanceSnapshot = synchronized(lock) {
        ProfileMaintenanceSnapshot(foreground, dueAt, rows.filterValues { it.active > 0 }.keys.toSet(),
            rows.filterValues { it.invalidated }.keys.toSet(), rows.mapNotNull { (id, row) -> row.receipt?.let { id to it } }.toMap(), closed || !lifetime.isActive)
    }

    private fun automatic(policy: ProfileMaintenancePolicy): Boolean = policy.isSubscription && policy.autoUpdate

    private fun current(flight: Flight): Boolean = !closed && lifetime.isActive && flight.valid &&
        flight.row.epoch == flight.epoch && flight.row.policy?.sourceIdentity == flight.policy.sourceIdentity &&
        (foreground || flight.manual)

    private fun acquire(profileId: String, manual: Boolean, regular: Boolean = false, expectedGeneration: Long? = null): Flight? {
        var launch: Job? = null
        val flight = synchronized(lock) {
            if (closed || !lifetime.isActive) return null
            val row = rows[profileId] ?: return null
            val policy = row.policy?.takeIf { it.isSubscription } ?: return null
            if (!manual) {
                if (!foreground || !automatic(policy) || generation != expectedGeneration) return null
                val last = listOfNotNull(policy.lastUpdatedAtMillis, row.receipt).maxOrNull()
                if (!row.invalidated && (!regular || (last != null && addTime(last, policy.intervalMillis) >= now()))) return null
            }
            row.flight?.takeIf { it.valid && !it.result.isCompleted }?.let {
                if (manual) it.manual = true
                row.invalidated = false
                return@synchronized it
            }
            val created = Flight(row, policy, row.epoch, manual)
            created.job = scope.launch(start = CoroutineStart.LAZY) { runFlight(created) }
            if (!created.job.isActive && created.job.isCancelled) return null
            row.active++
            mutableRunning.value = rows.filterValues { it.active > 0 }.keys.toSet()
            row.flight = created
            created.job.invokeOnCompletion { finishFlight(created) }
            row.invalidated = false
            launch = created.job
            created
        }
        launch?.start()
        return flight
    }

    private suspend fun runFlight(flight: Flight) {
        try {
            flight.row.gate.withLock {
                if (!synchronized(lock) { current(flight) }) return@withLock
                val request = ProfileMaintenanceRequest(flight.policy.id, flight.policy.sourceIdentity) {
                    synchronized(lock) { current(flight) }
                }
                val timestamp = update(request)
                require(timestamp >= 0) { "Update callback returned an invalid timestamp" }
                synchronized(lock) {
                    if (current(flight)) {
                        flight.row.receipt = timestamp
                        flight.outcome = ProfileMaintenanceResult.Updated(timestamp)
                    }
                }
            }
        } catch (_: CancellationException) {
            flight.outcome = ProfileMaintenanceResult.Cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (current(flight)) {
                    flight.outcome = ProfileMaintenanceResult.Failed(error)
                    if (!flight.manual) flight.report = error
                }
            }
        }
    }

    private fun finishFlight(flight: Flight) {
        synchronized(lock) {
            flight.row.active--
            mutableRunning.value = rows.filterValues { it.active > 0 }.keys.toSet()
            if (flight.row.flight === flight) flight.row.flight = null
            if (flight.row.policy == null && flight.row.active == 0) rows.remove(flight.policy.id)
        }
        try { flight.report?.let { onAutomaticError(flight.policy.id, it) } }
        finally { flight.result.complete(flight.outcome) }
    }

    private fun cancelFlights(flights: List<Flight>) {
        flights.forEach { it.job.cancel(); it.result.complete(ProfileMaintenanceResult.Cancelled) }
    }

    private fun startScan(regular: Boolean) {
        var launch: Job? = null
        synchronized(lock) {
            if (closed || !lifetime.isActive || !foreground) return
            if (scan != null) {
                if (regular) due = true
                return
            }
            val ids = if (regular) order.toList() else order.filter { id -> rows[id]?.let { row -> row.policy?.let(::automatic) == true && row.invalidated } == true }
            if (!regular && ids.isEmpty()) return
            timer?.cancel()
            timer = null
            if (regular) due = false
            val epoch = generation
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                for (id in ids) {
                    if (!synchronized(lock) { !closed && foreground && generation == epoch }) break
                    acquire(id, manual = false, regular = regular, expectedGeneration = epoch)?.result?.await()
                }
            }
            scan = job
            job.invokeOnCompletion {
                var again = false
                var dirty = false
                synchronized(lock) {
                    if (scan === job) scan = null
                    if (!closed && foreground) {
                        if (regular && generation == epoch && !due) dueAt = addTime(now(), checkIntervalMillis)
                        again = due
                        dirty = rows.values.any { it.invalidated && it.policy?.let(::automatic) == true }
                    }
                }
                if (again || dirty) startScan(again) else schedule()
            }
            launch = job
        }
        launch?.start()
    }

    private fun schedule() {
        val job = synchronized(lock) {
            if (closed || !lifetime.isActive || !foreground || scan != null || timer != null) return
            val deadline = dueAt ?: addTime(now(), checkIntervalMillis).also { dueAt = it }
            val remaining = (deadline - now()).coerceIn(0, checkIntervalMillis)
            lateinit var created: Job
            created = scope.launch(start = CoroutineStart.LAZY) {
                waitMillis(remaining)
                val valid = synchronized(lock) {
                    if (timer !== created) false else { timer = null; !closed && foreground }
                }
                if (valid) startScan(regular = true)
            }
            timer = created
            created
        }
        job.start()
    }

    override fun close() {
        closeState()
        scope.cancel()
    }

    private fun closeState() {
        val jobs = mutableListOf<Job>()
        val flights = mutableListOf<Flight>()
        synchronized(lock) {
            if (closed) return
            closed = true
            foreground = false
            generation++
            timer?.let(jobs::add)
            timer = null
            scan?.let(jobs::add)
            scan = null
            rows.values.forEach { row -> row.flight?.let { it.valid = false; flights += it } }
        }
        jobs.forEach { it.cancel() }
        cancelFlights(flights)
    }

    private fun addTime(time: Long, interval: Long): Long =
        if (time > Long.MAX_VALUE - interval) Long.MAX_VALUE else time + interval
}
