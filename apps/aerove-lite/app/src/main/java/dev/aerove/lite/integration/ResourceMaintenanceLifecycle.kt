package dev.aerove.lite.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun maintenanceRuntimeIdentity(snapshot: dev.aerove.lite.vpn.VpnSnapshot) =
    Triple(snapshot.phase, snapshot.sessionGeneration, snapshot.endpoint)

/** One retained composition owner; only the existing profile scheduler supplies a periodic clock. */
internal class ResourceMaintenanceLifecycle<E : Any>(parent: CoroutineScope,
    private val core: () -> E?, private val canWork: () -> Boolean,
    private val update: suspend (Boolean, () -> Boolean, () -> Boolean) -> Unit,
    private val resetBackoff: () -> Unit,
    initialNetworkRevision: Long, initialRuntimeIdentity: Any?,
    private val failure: (Exception) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val gate = Any()
    private val serial = Mutex()
    private val operations = linkedSetOf<Job>()
    private var foreground = false
    private var ready = false
    private var paused = false
    private var closed = false
    private var generation = 0L
    private var startup = false
    private var firstEndpoint = false
    private var pendingFull = false
    private var pendingEndpoint = false
    private var runtimeIdentity = initialRuntimeIdentity
    private var networkRevision = initialNetworkRevision
    private var endpointJob: Job? = null

    fun ready() { synchronized(gate) { ready = true }; trigger() }
    fun foreground(value: Boolean) {
        val canceled = synchronized(gate) {
            if (foreground == value || closed) return
            foreground = value
            if (!value) {
                if (endpointJob?.isActive == true && core() != null) pendingEndpoint = true
                generation++; operations.toList() + listOfNotNull(endpointJob)
            } else emptyList()
        }
        canceled.forEach(Job::cancel)
        if (value) trigger()
    }
    fun runtime(identity: Any?, available: E?) {
        val canceled = synchronized(gate) {
            if (closed || runtimeIdentity == identity) return
            runtimeIdentity = identity
            pendingEndpoint = available != null
            generation++; operations.toList() + listOfNotNull(endpointJob)
        }
        canceled.forEach(Job::cancel); resetBackoff(); trigger()
    }
    fun network(revision: Long) {
        val canceled = synchronized(gate) {
            if (closed || networkRevision == revision) return
            networkRevision = revision; generation++
            if (endpointJob?.isActive == true && core() != null) pendingEndpoint = true
            operations.toList() + listOfNotNull(endpointJob)
        }
        canceled.forEach(Job::cancel); resetBackoff(); trigger()
    }
    private fun eligible() = ready && foreground && !paused && !closed && canWork()
    private fun trigger() {
        var pending: Job? = null
        synchronized(gate) {
            if (!eligible()) return
            if (!startup || (!firstEndpoint && core() != null)) {
                startup = true
                if (core() != null) firstEndpoint = true
                pendingEndpoint = false; pendingFull = true
            }
            if ((pendingFull || (pendingEndpoint && core() != null)) && endpointJob?.isActive != true) {
                val full = pendingFull
                pendingEndpoint = false
                val job = scope.launch(start = CoroutineStart.LAZY) { run(full) { !full || synchronized(gate) { pendingFull } } }
                endpointJob = job
                job.invokeOnCompletion { synchronized(gate) { if (endpointJob === job) endpointJob = null } }
                pending = job
            }
        }
        pending?.start()
    }
    suspend fun afterAutomaticSweep(current: () -> Boolean) = run(full = true, current)
    private suspend fun run(full: Boolean, upstream: () -> Boolean) = coroutineScope {
        val operation = checkNotNull(currentCoroutineContext()[Job])
        val epoch = synchronized(gate) {
            if (!eligible()) return@coroutineScope
            operations += operation; generation
        }
        try {
            serial.withLock {
                val owner = core()
                fun current() = synchronized(gate) { eligible() && generation == epoch } &&
                    operation.isActive && core() === owner && upstream()
                if (current()) {
                    update(full, ::current) { core() != null }
                    if (full && current()) synchronized(gate) { pendingFull = false }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (operation.isActive) failure(error) }
        finally { synchronized(gate) { operations -= operation } }
    }
    suspend fun quiesce() {
        val caller = currentCoroutineContext()[Job]
        withContext(NonCancellable) {
            val canceled = synchronized(gate) {
                paused = true; generation++
                (operations.toList() + listOfNotNull(endpointJob)).distinct()
            }
            check(caller !in canceled)
            canceled.forEach(Job::cancel); canceled.joinAll()
            serial.withLock { }
        }
    }
    fun resumeAfterMaintenance() { synchronized(gate) { check(!closed); paused = false }; trigger() }
    override fun close() {
        val canceled = synchronized(gate) { closed = true; generation++; operations.toList() }
        canceled.forEach(Job::cancel); scope.cancel()
    }
}
