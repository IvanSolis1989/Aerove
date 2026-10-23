package dev.aerove.lite.integration

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.profilesource.*
import java.io.InputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileImportPresentation(val preparing: Boolean = false, val filePrompt: Boolean = false,
    val fileBusy: Boolean = false, val errorCode: String? = null,
    val issues: List<NamedInputIssue> = emptyList(), val skipped: Int = 0,
    val input: String = "", val name: String = "", val batchGeneration: Long? = null,
    val itemIssues: Map<String, List<dev.aerove.lite.data.ConfigIssue>> = emptyMap())

/** One retained public import owner. Secret handles and file grants never enter public/saved application state. */
class ProfileImportWorkspace(private val scope: CoroutineScope, private val profiles: ProfileRepository,
    private val available: () -> Boolean, private val changed: suspend (ProfileSummary) -> Unit,
    private val promptChanged: (Boolean) -> Unit, private val dismissed: () -> Unit,
    private val failed: (List<dev.aerove.lite.data.ConfigIssue>) -> Unit) {
    private data class FileInput(val name: String, val open: suspend () -> InputStream)
    private val gate = Any()
    private val barrier = Mutex()
    private val jobs = mutableMapOf<Job, CompletableDeferred<Unit>>()
    private var paused = false
    private var closed = false
    private var epoch = 0L
    private var file: FileInput? = null
    private var activeItems: Set<String> = emptySet()
    private val mutable = MutableStateFlow(ProfileImportPresentation())
    val state = mutable.asStateFlow()
    private val planner = NamedImportPlanner()
    private val imports = NamedBatchImportSession(scope, ProfileImportExecutor { item ->
        val saved = try { profiles.importPlanned(item) }
        catch (failure: dev.aerove.lite.data.ConfigValidationException) {
            if (synchronized(gate) { !paused && !closed && item.id in activeItems })
                mutable.update { it.copy(itemIssues = it.itemIssues + (item.id to failure.issues)) }
            throw failure
        }
        withContext(NonCancellable) { if (available()) changed(saved) }
    })
    val batchState = imports.state

    fun single(input: NamedProfileInput) {
        mutable.update { it.copy(input = input.url, name = input.label) }
        begin(input.secret) { token, transfer ->
        val names = profiles.list().mapTo(mutableSetOf()) { it.name }
        val plan = withContext(Dispatchers.Default) { if (input.secret == null && !Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(input.url.trim())) {
            val decoded = dev.aerove.lite.data.ProfileLinkDecoder().decode(input.url, names)
            NamedImportPlan(decoded.entries.map { entry ->
                val named = if (input.label.isBlank() || decoded.entries.size != 1) entry else when (entry) {
                    is dev.aerove.lite.data.ProfileLinkEntry.Inline -> entry.copy(name = input.label.trim())
                    is dev.aerove.lite.data.ProfileLinkEntry.Subscription -> entry.copy(name = input.label.trim())
                }
                PlannedProfileImport(java.util.UUID.randomUUID().toString(), named)
            }, decoded.errors.map { NamedInputIssue(it.source.line, it.code.name.lowercase(), it.field, it.reason) }, 0)
        } else planner.single(input, names) }
        current(token)
        mutable.update { it.copy(issues = plan.issues, skipped = plan.skippedExisting,
            errorCode = if (plan.issues.isEmpty()) null else "invalid_input") }
        if (plan.items.isNotEmpty()) {
            synchronized(gate) { activeItems = plan.items.mapTo(mutableSetOf()) { it.id } }
            imports.start(plan); transfer()
            mutable.update { it.copy(batchGeneration = imports.state.value.generation) }
        }
        }
    }

    fun batch(text: String) = begin { token, _ ->
        val existing = profiles.list()
        val plan = withContext(Dispatchers.Default) {
            planner.batch(text, existing.mapNotNullTo(mutableSetOf()) { it.url }, existing.mapTo(mutableSetOf()) { it.name })
        }
        current(token)
        mutable.update { it.copy(issues = plan.issues, skipped = plan.skippedExisting,
            errorCode = if (plan.issues.isEmpty()) null else "invalid_input") }
        if (plan.items.isNotEmpty()) {
            synchronized(gate) { activeItems = plan.items.mapTo(mutableSetOf()) { it.id } }
            imports.start(plan)
            mutable.update { it.copy(batchGeneration = imports.state.value.generation) }
        }
    }

    fun importFile(name: String, open: suspend () -> InputStream) {
        synchronized(gate) { if (paused || closed || !available()) return; file = FileInput(name, open) }
        runFile(null)
    }

    /** Takes ownership even when maintenance rejects the retry. */
    fun submitFileKey(secret: AgeSecret) = runFile(secret)

    private fun runFile(secret: AgeSecret?) = begin(secret) { token, _ ->
        val input = synchronized(gate) { file } ?: return@begin
        mutable.update { it.copy(fileBusy = true, errorCode = null) }
        try {
            val source = input.open()
            val saved = source.use { profiles.importFile(input.name, it, secret) }
            withContext(NonCancellable) { if (available()) changed(saved) }
            current(token)
            synchronized(gate) { if (token == epoch && file === input) file = null }
            setPrompt(false)
        } catch (failure: AgeSourceException) {
            current(token)
            if (failure.code in setOf("secret_required", "wrong_key", "invalid_identity", "decrypt_failed")) {
                mutable.update { it.copy(errorCode = failure.code) }
                setPrompt(true)
            } else throw failure
        } finally {
            if (synchronized(gate) { token == epoch }) mutable.update { it.copy(fileBusy = false) }
        }
    }

    private fun setPrompt(value: Boolean) {
        mutable.update { it.copy(filePrompt = value) }
        promptChanged(value)
    }

    private fun current(token: Long) {
        if (!synchronized(gate) { !paused && !closed && token == epoch } || !available())
            throw CancellationException("import_superseded")
    }

    private fun begin(secret: AgeSecret? = null, operation: suspend (Long, () -> Unit) -> Unit) {
        synchronized(gate) {
            if (paused || closed || !available()) { secret?.close(); return }
            val token = ++epoch
            activeItems = emptySet()
            imports.cancel()
            jobs.keys.toList().forEach(Job::cancel)
            mutable.update { it.copy(preparing = true, errorCode = null, issues = emptyList(), skipped = 0, batchGeneration = null, itemIssues = emptyMap()) }
            val finished = CompletableDeferred<Unit>()
            var transferred = false
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try { operation(token) { transferred = true } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (synchronized(gate) { token == epoch && !paused && !closed })
                        mutable.update { it.copy(errorCode = (failure as? AgeSourceException)?.code ?: "import_failed") }
                    if (synchronized(gate) { token == epoch && !paused && !closed })
                        failed((failure as? dev.aerove.lite.data.ConfigValidationException)?.issues.orEmpty())
                } finally {
                    if (synchronized(gate) { token == epoch }) mutable.update { it.copy(preparing = false) }
                }
            }
            jobs[job] = finished
            job.invokeOnCompletion {
                try { if (!transferred) secret?.close() }
                finally { synchronized(gate) { jobs.remove(job) }; finished.complete(Unit) }
            }
            job.start()
        }
    }

    fun cancel() {
        synchronized(gate) { ++epoch; jobs.keys.toList().forEach(Job::cancel); file = null; activeItems = emptySet() }
        imports.cancel()
        mutable.update { it.copy(preparing = false, fileBusy = false, filePrompt = false) }
        promptChanged(false)
    }
    fun dismiss() { cancel(); mutable.value = ProfileImportPresentation(); dismissed() }

    suspend fun quiesce() = withContext(NonCancellable) {
        barrier.withLock {
            val pending = synchronized(gate) { paused = true; ++epoch; file = null; activeItems = emptySet(); jobs.toList() }
            pending.forEach { it.first.cancel() }
            imports.quiesce()
            pending.forEach { it.second.await() }
            mutable.value = ProfileImportPresentation()
            promptChanged(false)
        }
    }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        barrier.withLock {
            imports.resumeAfterMaintenance()
            synchronized(gate) { if (!closed) paused = false }
        }
    }
    fun close() { synchronized(gate) { closed = true }; cancel() }
}
