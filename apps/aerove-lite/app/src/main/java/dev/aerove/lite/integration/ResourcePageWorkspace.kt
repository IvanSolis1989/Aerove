package dev.aerove.lite.integration

import dev.aerove.lite.overrides.ScriptLibrarySession
import dev.aerove.lite.resources.*
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns page operations and the existing visible-event maintenance trigger; never creates a timer. */
class ResourcePageWorkspace(val session: ResourceManagementSession, val scripts: ScriptLibrarySession,
    private val maintenance: ResourceManagementMaintenance, private val repository: ResourceManagementRepository,
    private val scope: CoroutineScope, private val selected: () -> String?,
    private val canWork: () -> Boolean, private val coreAvailable: () -> Boolean,
    private val failed: (Exception) -> Unit) {
    data class State(val updating: Boolean = false, val failures: Set<String> = emptySet(), val completed: Long = 0)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val gate = Any()
    private val barrier = kotlinx.coroutines.sync.Mutex()
    private val jobs = mutableSetOf<Job>()
    private var paused = false
    private var closed = false
    private var update: Job? = null
    private var automatic: Job? = null
    fun load() { if (canWork()) launch { scripts.refresh(); if (session.state.value.busy.isEmpty()) session.load(null) } }
    fun updateAll() = update(manual = true, canWork)
    fun updateIfDue(isCurrent: () -> Boolean) = update(manual = false, isCurrent)
    private fun update(manual: Boolean, isCurrent: () -> Boolean) = synchronized(gate) {
        if (paused || closed || update?.isActive == true || !isCurrent()) return@synchronized
        update = launch {
            mutable.value = mutable.value.copy(updating = true, failures = emptySet())
            try {
                val result = if (manual) maintenance.updateAll({ canWork() && isCurrent() }, coreAvailable)
                    else maintenance.updateIfDue({ canWork() && isCurrent() }, coreAvailable)
                mutable.value = State(failures = result.failed, completed = mutable.value.completed + 1)
                if (canWork() && isCurrent()) session.load(null)
            } finally { mutable.value = mutable.value.copy(updating = false) }
        }
        if (!manual) automatic = update
    }
    fun pauseAutomatic() { synchronized(gate) { automatic?.cancel(); automatic = null } }
    fun cancel() { synchronized(gate) { jobs.toList().forEach { it.cancel() } }; session.cancel() }
    fun sync(remote: ResourceManagementRemoteResource) {
        if (remote.kind == ResourceManagementRemoteKind.Script) launch {
            val receipt = scripts.updateWithReceipt(remote.id.removePrefix("script:"))
            mutable.value = mutable.value.copy(failures = if (receipt.errorCode == null) emptySet() else setOf(remote.id))
            session.load(null)
        } else if (canWork()) session.syncLibrary(remote.id)
    }
    private fun matches(profile: String?, resource: ManagedResource): Boolean = canWork() && profile == selected() &&
        session.state.value.profileId == profile && session.state.value.snapshot?.entries?.any { it.key == resource.key && it.sha256 == resource.sha256 } == true
    fun sideload(profile: String?, resource: ManagedResource, open: suspend () -> InputStream) = launch {
        val owned = AtomicReference<InputStream?>()
        try {
            withContext(Dispatchers.IO) { owned.set(open()) }
            currentCoroutineContext().ensureActive()
            if (matches(profile, resource)) {
                val input = checkNotNull(owned.getAndSet(null))
                try { session.sideload(resource.key, input) } catch (failure: Throwable) { input.close(); throw failure }
            }
        } finally { owned.getAndSet(null)?.close() }
    }
    suspend fun preview(resource: ManagedResource): String = tracked {
        val profile = selected()
        check(matches(profile, resource)) { "Resource selection changed" }
        repository.preview(checkNotNull(profile), resource.key).also {
            currentCoroutineContext().ensureActive()
            check(matches(profile, resource)) { "Resource selection changed" }
        }
    }
    private fun launch(action: suspend () -> Unit): Job? = synchronized(gate) {
        if (paused || closed || !canWork()) return@synchronized null
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(failure) }
        }
        jobs += pending; pending.invokeOnCompletion { synchronized(gate) { jobs -= pending } }; pending.start(); pending
    }
    private suspend fun <T> tracked(action: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(gate) { check(!paused && !closed && canWork()); jobs += job }
        job.invokeOnCompletion { synchronized(gate) { jobs -= job } }
        action()
    }
    suspend fun quiesce() = withContext(NonCancellable) {
        barrier.lock()
        try {
            val pending = synchronized(gate) { paused = true; jobs.toList() }
            pending.forEach { it.cancel() }; pending.joinAll()
            maintenance.quiesce()
        } finally { barrier.unlock() }
    }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        barrier.lock()
        try { maintenance.resumeAfterMaintenance(); synchronized(gate) { check(!closed); paused = false } }
        finally { barrier.unlock() }
        mutable.value = mutable.value.copy(updating = false, failures = emptySet())
        load()
    }
    fun close() = synchronized(gate) { closed = true; paused = true; jobs.toList().forEach { it.cancel() } }
}
