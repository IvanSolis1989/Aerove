package dev.aerove.lite.overrides

import dev.aerove.lite.data.SubscriptionDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ScriptSaveReceipt(val serial: Long, val busy: Boolean, val errorCode: String? = null)
data class ScriptUpdateReceipt(val committed: Boolean, val errorCode: String? = null)
data class ScriptLibraryState(val scripts: List<ScriptResource> = emptyList(), val busyIds: Set<String> = emptySet(), val errorCode: String? = null,
    val saves: Map<String, ScriptSaveReceipt> = emptyMap())

class ScriptLibrarySession(
    private val repository: OverrideRepository,
    private val downloader: SubscriptionDownloader,
    private val onChanged: suspend () -> Unit = {},
) {
    private val mutable = MutableStateFlow(ScriptLibraryState())
    val state = mutable.asStateFlow()
    private val gate = Any()
    private val updates = mutableMapOf<String, Job>()
    private val started = LinkedHashMap<Job, CompletableDeferred<Unit>>()
    private val maintenance = Mutex()
    private var blocked = false
    private var closed = false
    private val saveSerial = java.util.concurrent.atomic.AtomicLong()

    suspend fun refresh() { mutable.update { it.copy(scripts = repository.snapshot().scripts) } }
    suspend fun put(script: ScriptResource): Unit = operation(script.id, { Unit }) {
        val serial = saveSerial.incrementAndGet()
        mutable.update { it.copy(busyIds = it.busyIds + script.id, errorCode = null,
            saves = it.saves + (script.id to ScriptSaveReceipt(serial, true))) }
        var error: String? = null
        var committed = false
        try {
        val previous = repository.snapshot().scripts.find { it.id == script.id }
        val remoteUrl = script.url
        val candidate = if (remoteUrl != null && remoteUrl != previous?.url) {
            val content = downloader.download(remoteUrl)
            require(content.toByteArray(Charsets.UTF_8).size <= OverrideRepository.MAX_SCRIPT_BYTES)
            currentCoroutineContext().ensureActive()
            script.copy(content = content, updatedAt = System.currentTimeMillis())
        } else script
        if (previous == null) repository.putScript(candidate)
        else if (!repository.replaceScriptIfCurrent(previous, candidate)) throw ScriptExecutionException("edit_conflict")
        committed = true
        refresh(); onChanged()
        } catch (cancelled: CancellationException) { error = "cancelled"; throw cancelled }
        catch (failure: Exception) { error = if (committed) "apply_failed" else "save_failed"; throw failure }
        finally {
            mutable.update { it.copy(busyIds = it.busyIds - script.id, errorCode = error,
                saves = it.saves + (script.id to ScriptSaveReceipt(serial, false, error))) }
        }
    }

    suspend fun import(name: String, content: String, url: String? = null): Unit = operation(null, { Unit }) {
        val current = repository.snapshot()
        var unique = name.trim().take(180).ifBlank { "script" }
        val base = unique
        var suffix = 2
        while (current.scripts.any { it.name == unique }) unique = "$base (${suffix++})"
        repository.putScript(ScriptResource(java.util.UUID.randomUUID().toString(), unique, url, content))
        refresh(); onChanged()
    }
    suspend fun delete(id: String): Unit = operation(null, { Unit }) { cancel(id); repository.deleteScript(id); refresh(); onChanged() }
    suspend fun move(id: String, destination: Int): Unit = operation(null, { Unit }) { repository.moveScript(id, destination); refresh() }
    fun cancel(id: String) { synchronized(gate) { updates[id] }?.cancel() }

    suspend fun update(id: String) { updateWithReceipt(id) }

    suspend fun updateWithReceipt(id: String): ScriptUpdateReceipt = operation(id, { ScriptUpdateReceipt(false) }) {
        mutable.update { it.copy(busyIds = it.busyIds + id, errorCode = null) }
        var committed = false
        try {
            val expected = repository.snapshot().scripts.single { it.id == id }
            val url = expected.url ?: throw IllegalArgumentException("Local script")
            val content = downloader.download(url)
            require(content.toByteArray(Charsets.UTF_8).size <= OverrideRepository.MAX_SCRIPT_BYTES)
            currentCoroutineContext().ensureActive()
            if (repository.replaceScriptIfCurrent(expected, expected.copy(content = content, updatedAt = System.currentTimeMillis()))) {
                committed = true
                refresh()
                onChanged()
                ScriptUpdateReceipt(true)
            } else {
                mutable.update { it.copy(errorCode = "edit_conflict") }
                ScriptUpdateReceipt(false, "edit_conflict")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Neither URL credentials/query nor a remote response/script body enter user-facing errors.
            mutable.update { it.copy(errorCode = if (committed) "apply_failed" else "update_failed") }
            ScriptUpdateReceipt(committed, if (committed) "apply_failed" else "update_failed")
        } finally {
            mutable.update { it.copy(busyIds = it.busyIds - id) }
        }
    }

    private suspend fun <T> operation(id: String?, coalesced: () -> T, body: suspend () -> T): T = coroutineScope {
        currentCoroutineContext().ensureActive()
        val job = requireNotNull(currentCoroutineContext()[Job])
        val finished = CompletableDeferred<Unit>()
        val accepted = synchronized(gate) {
            check(!closed) { "script_closed" }; check(!blocked) { "script_maintenance" }
            if (id != null && id in updates) false else {
                if (id != null) updates[id] = job
                started[job] = finished
                true
            }
        }
        if (!accepted) return@coroutineScope coalesced()
        job.invokeOnCompletion {
            synchronized(gate) {
                if (id != null && updates[id] === job) updates.remove(id)
                started.remove(job)
            }
            finished.complete(Unit)
        }
        currentCoroutineContext().ensureActive()
        body()
    }

    suspend fun quiesce() = withContext(NonCancellable) {
        maintenance.withLock {
            val pending = synchronized(gate) { blocked = true; started.toList() }
            pending.forEach { (job, _) -> job.cancel() }
            pending.forEach { (_, finished) -> finished.await() }
        }
    }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        maintenance.withLock { synchronized(gate) { if (!closed) blocked = false } }
    }
    suspend fun closeAndJoin() = withContext(NonCancellable) {
        maintenance.withLock {
            val pending = synchronized(gate) { closed = true; blocked = true; started.toList() }
            pending.forEach { (job, _) -> job.cancel() }
            pending.forEach { (_, finished) -> finished.await() }
        }
    }
}
