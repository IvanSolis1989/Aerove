package dev.aerove.lite.integration

import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.OverrideRule
import dev.aerove.lite.overrides.OverrideSnapshot
import dev.aerove.lite.overrides.ProfileOverride
import dev.aerove.lite.overrides.ScriptLibrarySession
import dev.aerove.lite.overrides.ScriptResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One retained adapter over the actual C repository and downloader. No second storage owner. */
data class OverrideWorkspaceState(val snapshot: OverrideSnapshot? = null,
    val busy: Set<String> = emptySet(), val failed: Boolean = false)

class OverrideWorkspace(val repository: OverrideRepository, downloader: SubscriptionDownloader,
    private val scope: CoroutineScope, private val canWrite: () -> Boolean = { true },
    private val onChanged: suspend (OverrideSnapshot, OverrideSnapshot) -> Unit) {
    private val mutable = MutableStateFlow(OverrideWorkspaceState())
    val state = mutable.asStateFlow()
    private val publishing = Mutex()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    @Volatile private var closed = false
    @Volatile private var editing = false
    private var pendingBefore: OverrideSnapshot? = null
    private var pendingAfter: OverrideSnapshot? = null
    val scripts = ScriptLibrarySession(repository, downloader) { publish(true) }

    fun refresh(): Job? = run("load") { scripts.refresh(); publish(false) }
    fun putProfile(value: ProfileOverride): Job? = run("profile") { repository.setProfile(value); publish(true) }
    fun putGlobalRules(value: List<OverrideRule>): Job? = run("rules") { repository.setGlobalRules(value); publish(true) }
    fun putScript(value: ScriptResource): Job? = run(value.id) { scripts.put(value) }
    fun deleteScript(id: String): Job? = run(id) { scripts.delete(id) }
    fun moveScript(id: String, destination: Int): Job? = run(id) { scripts.move(id, destination); publish(false) }
    fun updateScript(id: String): Job? = run(id) { scripts.update(id) }
    fun importScript(name: String, content: String): Job? = run("import") { scripts.import(name, content) }
    fun scriptSaved(): Job? = run("scripts") { scripts.refresh(); publish(true) }
    fun cancelScript(id: String) { scripts.cancel(id); jobs[id]?.cancel() }

    // Standard ExtendView applies once on leaving the editor; child library/editor routes share this lease.
    fun setEditorActive(active: Boolean) {
        editing = active
        if (!active) scope.launch { flush() }
    }

    suspend fun quiesce() {
        scripts.quiesce()
        jobs.values.toList().forEach { it.cancelAndJoin() }
    }

    suspend fun resumeAfterMaintenance() { scripts.resumeAfterMaintenance() }

    fun close() {
        closed = true
        jobs.values.toList().forEach(Job::cancel)
        scripts.state.value.busyIds.forEach(scripts::cancel)
    }

    private fun run(key: String, action: suspend () -> Unit): Job? {
        if (closed || !canWrite() || jobs.containsKey(key)) return null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withContext(Dispatchers.IO) {
                    if (state.value.snapshot == null) publish(false)
                    check(canWrite()) { "Maintenance is active" }
                    action()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(failed = true) } }
            finally { jobs.remove(key); mutable.update { it.copy(busy = it.busy - key) } }
        }
        if (jobs.putIfAbsent(key, job) != null) { job.cancel(); return null }
        mutable.update { it.copy(busy = it.busy + key, failed = false) }
        job.start()
        return job
    }

    private suspend fun publish(changed: Boolean) {
        publishing.withLock {
            val previous = state.value.snapshot
            val actual = repository.snapshot()
            mutable.update { it.copy(snapshot = actual) }
            if (changed && previous != null && previous != actual) {
                if (pendingBefore == null) pendingBefore = previous
                pendingAfter = actual
            }
        }
        flush()
    }

    private suspend fun flush() {
        val change = publishing.withLock {
            if (editing || closed || pendingBefore == null || pendingAfter == null) null
            else (pendingBefore!! to pendingAfter!!).also { pendingBefore = null; pendingAfter = null }
        }
        if (change != null) onChanged(change.first, change.second)
    }
}
