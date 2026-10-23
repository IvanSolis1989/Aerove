package dev.aerove.lite.profilesource

import dev.aerove.lite.data.ProfileLinkDecoder
import dev.aerove.lite.data.ProfileLinkEntry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class NamedProfileInput(val label: String, val url: String, val secret: AgeSecret? = null) {
    override fun toString(): String = "NamedProfileInput([private])"
}
class PlannedProfileImport(val id: String, val entry: ProfileLinkEntry, val secret: AgeSecret? = null) {
    override fun toString(): String = "PlannedProfileImport(id=$id)"
}
data class NamedInputIssue(val line: Int, val code: String, val field: String? = null, val reason: String? = null)
class NamedImportPlan(val items: List<PlannedProfileImport>, val issues: List<NamedInputIssue>, val skippedExisting: Int) {
    override fun toString(): String = "NamedImportPlan(count=${items.size}, issues=$issues, skippedExisting=$skippedExisting)"
}

/** Standard parseUrlInput ordering/markup/dedup, followed by the shared production link decoder. */
class NamedImportPlanner(private val decoder: ProfileLinkDecoder = ProfileLinkDecoder()) {
    suspend fun single(input: NamedProfileInput, existingNames: Set<String> = emptySet()): NamedImportPlan {
        if (input.label.trim().length > 200 || input.url.any { it == '\r' || it == '\n' } ||
            !Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(input.url.trim()) || input.url.toByteArray().size > 64 * 1024)
            return NamedImportPlan(emptyList(), listOf(NamedInputIssue(1, "invalid_url")), 0)
        val decoded = decoder.decode(input.url.trim(), existingNames)
        val entries = decoded.entries.map { entry ->
            val label = input.label.trim()
            val named = if (label.isEmpty()) entry else when (entry) {
                is ProfileLinkEntry.Subscription -> entry.copy(name = label)
                is ProfileLinkEntry.Inline -> entry.copy(name = label)
            }
            PlannedProfileImport(UUID.randomUUID().toString(), named, input.secret)
        }
        return NamedImportPlan(entries, decoded.errors.map { NamedInputIssue(it.source.line, it.code.name.lowercase(), it.field, it.reason) }, 0)
    }

    suspend fun batch(text: String, existingUrls: Set<String> = emptySet(), existingNames: Set<String> = emptySet()): NamedImportPlan {
        if (text.toByteArray().size > MAX_PLAINTEXT_BYTES) return NamedImportPlan(emptyList(), listOf(NamedInputIssue(1, "limit_exceeded")), 0)
        val urls = mutableListOf<String>()
        val lines = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        var skipped = 0
        text.split(Regex("\r?\n")).forEachIndexed { index, raw ->
            val line = raw.trim().removePrefix("- ").trim().removeSuffix(",").trim()
            val whole = unquote(line)
            val share = whole.substringBefore("://").lowercase() in ProfileLinkDecoder.SHARE_SCHEMES
            for (token in if (share) listOf(line) else line.split(Regex("\\s+"))) {
                val url = unquote(token)
                if (url.isEmpty() || !seen.add(url)) continue
                if (url in existingUrls) { skipped++; continue }
                urls += url; lines += index + 1
            }
        }
        val decoded = decoder.decode(urls, existingNames)
        return NamedImportPlan(decoded.entries.map { PlannedProfileImport(UUID.randomUUID().toString(), it) },
            decoded.errors.map { NamedInputIssue(lines.getOrElse(it.source.inputIndex) { 1 }, it.code.name.lowercase(), it.field, it.reason) }, skipped)
    }

    private fun unquote(value: String): String = value.trim().let {
        if (it.length >= 2 && (it.first() == '\'' || it.first() == '"') && it.last() == it.first()) it.substring(1, it.lastIndex).trim() else it
    }
}

enum class ImportItemStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
data class ImportItemResult(val id: String, val name: String, val status: ImportItemStatus, val errorCode: String? = null)
data class BatchImportState(val generation: Long = 0, val items: List<ImportItemResult> = emptyList(), val running: Boolean = false)

/** The host persists this reserved ID before returning; completion means saved/read back, not dispatched. */
fun interface ProfileImportExecutor { suspend fun import(item: PlannedProfileImport) }

class NamedBatchImportSession(private val scope: CoroutineScope, private val executor: ProfileImportExecutor) {
    private val mutable = MutableStateFlow(BatchImportState())
    val state = mutable.asStateFlow()
    private var active: Job? = null
    private var generation = 0L
    private val started = LinkedHashMap<Job, CompletableDeferred<Unit>>()
    private val maintenance = Mutex()
    private var blocked = false
    private var closed = false

    /** Rejected plans remain caller-owned; accepted plans close their identities after all children finish. */
    @Synchronized fun start(plan: NamedImportPlan) {
        check(!closed) { "import_closed" }
        check(!blocked) { "import_maintenance" }
        active?.cancel()
        val current = ++generation
        mutable.value = BatchImportState(current, plan.items.map { ImportItemResult(it.id, it.entry.name, ImportItemStatus.QUEUED) }, true)
        val finished = CompletableDeferred<Unit>()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val permits = Semaphore(4) // Standard maxConcurrentImports.
            fun update(id: String, status: ImportItemStatus, error: String? = null) {
                mutable.update { state -> if (state.generation != current) state else state.copy(items = state.items.map {
                    if (it.id == id) it.copy(status = status, errorCode = error) else it
                }) }
            }
            try {
                coroutineScope {
                    plan.items.forEach { item -> launch {
                        try {
                            permits.withPermit {
                                ensureActive(); update(item.id, ImportItemStatus.RUNNING)
                                executor.import(item)
                                // Persisted successes survive subsequent cancellation and partial failure.
                                update(item.id, ImportItemStatus.SUCCEEDED)
                            }
                        } catch (cancelled: CancellationException) { update(item.id, ImportItemStatus.CANCELLED); throw cancelled }
                        catch (failure: Exception) { update(item.id, ImportItemStatus.FAILED,
                            (failure as? AgeSourceException)?.code ?: "import_failed") }
                    } }
                }
            } finally {
                mutable.update { state -> if (state.generation != current) state else state.copy(running = false, items = state.items.map {
                    if (it.status == ImportItemStatus.QUEUED || it.status == ImportItemStatus.RUNNING) it.copy(status = ImportItemStatus.CANCELLED) else it
                }) }
            }
        }
        active = job
        started[job] = finished
        job.invokeOnCompletion {
            try {
                plan.items.mapNotNull { it.secret }.distinct().forEach { it.close() }
                mutable.update { state -> if (state.generation != current) state else state.copy(running = false, items = state.items.map {
                    if (it.status == ImportItemStatus.QUEUED || it.status == ImportItemStatus.RUNNING) it.copy(status = ImportItemStatus.CANCELLED) else it
                }) }
            } finally {
                synchronized(this) {
                    started.remove(job)
                    if (active === job) active = null
                }
                finished.complete(Unit)
            }
        }
        job.start()
    }
    @Synchronized fun cancel() { active?.cancel() }

    /** Blocks new intents before draining every accepted flight, including superseded commits/callbacks. */
    suspend fun quiesce() = withContext(NonCancellable) {
        maintenance.withLock {
            val flights = synchronized(this@NamedBatchImportSession) { blocked = true; started.toList() }
            flights.forEach { (job, _) -> job.cancel() }
            flights.forEach { (_, finished) -> finished.await() }
        }
    }

    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        maintenance.withLock { synchronized(this@NamedBatchImportSession) { if (!closed) blocked = false } }
    }

    suspend fun closeAndJoin() = withContext(NonCancellable) {
        maintenance.withLock {
            val flights = synchronized(this@NamedBatchImportSession) { closed = true; blocked = true; started.toList() }
            flights.forEach { (job, _) -> job.cancel() }
            flights.forEach { (_, finished) -> finished.await() }
        }
    }
}
