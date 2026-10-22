package dev.aerove.lite.profiles

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.ProfileDocumentSnapshot
import dev.aerove.lite.data.ProfileMaintenanceFacade
import dev.aerove.lite.data.ProfileMaintenanceResult
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.readBounded
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ProfileDocumentKind { Create, Open }
enum class ProfileListProgress { Updating, Reordering, Exporting, Replacing }
enum class ProfileListNotice { Updated, Reordered, Exported, Replaced, Cancelled, Failed, PartialDocument }
data class ProfileUpdateFailure(val id: String, val name: String, val message: String)
data class ProfileBatchResult(val updated: Int = 0, val skipped: Int = 0, val failures: List<ProfileUpdateFailure> = emptyList())
data class ProfileDocumentRequest(val id: Long, val profileId: String, val kind: ProfileDocumentKind,
    val suggestedName: String, val dispatched: Boolean = false)
data class ProfileListState(
    val progress: ProfileListProgress? = null,
    val activeProfileIds: Set<String> = emptySet(),
    val batch: ProfileBatchResult? = null,
    val document: ProfileDocumentRequest? = null,
    val notice: ProfileListNotice? = null,
    val error: String? = null,
) {
    val busy: Boolean get() = progress != null || document != null
}

/** The adapter opens only SAF content references; the feature always closes streams through this contract. */
interface ProfileDocuments {
    suspend fun <T> read(reference: String, action: suspend (InputStream) -> T): T
    suspend fun write(reference: String, bytes: ByteArray)
    suspend fun deleteCreated(reference: String): Boolean
}

class ProfileListFeature(
    private val repository: ProfileRepository,
    private val maintenance: ProfileMaintenanceFacade,
    scope: CoroutineScope,
    private val documents: ProfileDocuments,
    private val onChanged: suspend () -> Unit,
    private val onReplaced: suspend (ProfileSummary, () -> Boolean) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner + Dispatchers.IO)
    private val gate = Any()
    private val mutableState = MutableStateFlow(ProfileListState())
    val state = mutableState.asStateFlow()
    private var worker: Job? = null
    private var epoch = 0L
    private var serial = 0L
    private var closed = false
    private var pending: ProfileDocumentSnapshot? = null
    private var disableAutoUpdate = false
    private val retiredCreates = linkedSetOf<Long>()

    fun updateAll() = start(ProfileListProgress.Updating) { token ->
        val ids = maintenance.reload().profiles.map { it.id }
        publish { it.copy(batch = ProfileBatchResult()) }
        coroutineScope {
            ids.forEach { id -> launch {
                currentCoroutineContext().ensureActive()
                val latest = maintenance.reload().profiles.find { it.id == id }
                synchronized(gate) {
                    if (!current(token)) throw CancellationException()
                    if (latest?.url == null) {
                        publish { it.copy(batch = it.batch!!.copy(skipped = it.batch.skipped + 1)) }
                        return@launch
                    }
                    publish { it.copy(activeProfileIds = it.activeProfileIds + id) }
                }
                try {
                    val result = maintenance.refresh(id)
                    synchronized(gate) {
                        if (current(token)) publish { state ->
                            val batch = checkNotNull(state.batch)
                            val summary = when (result) {
                                is ProfileMaintenanceResult.Updated -> batch.copy(updated = batch.updated + 1)
                                is ProfileMaintenanceResult.Failed -> batch.copy(failures = batch.failures +
                                    ProfileUpdateFailure(id, latest!!.name, result.error.message.orEmpty()))
                                ProfileMaintenanceResult.Cancelled -> batch.copy(failures = batch.failures +
                                    ProfileUpdateFailure(id, latest!!.name, "Cancelled"))
                                ProfileMaintenanceResult.Unavailable -> batch.copy(skipped = batch.skipped + 1)
                            }
                            state.copy(batch = summary)
                        }
                    }
                } finally {
                    if (!current(token)) maintenance.cancel(id)
                    publish { it.copy(activeProfileIds = it.activeProfileIds - id) }
                }
            } }
        }
        if (current(token)) {
            publish { it.copy(notice = ProfileListNotice.Updated) }
            onChanged()
        }
    }

    fun reorder(ids: List<String>) = start(ProfileListProgress.Reordering) { token ->
        repository.reorder(ids) { current(token) }
        withContext(NonCancellable) { maintenance.reload(); onChanged() }
        if (current(token)) publish { it.copy(notice = ProfileListNotice.Reordered) }
    }

    fun requestExport(id: String) = pick(id, ProfileDocumentKind.Create, false)
    fun requestReplacement(id: String, disableAutoUpdate: Boolean = false) = pick(id, ProfileDocumentKind.Open, disableAutoUpdate)

    private fun pick(id: String, kind: ProfileDocumentKind, disable: Boolean) = start(
        if (kind == ProfileDocumentKind.Create) ProfileListProgress.Exporting else ProfileListProgress.Replacing,
    ) { _ ->
        val snapshot = repository.documentSnapshot(id)
        synchronized(gate) {
            pending = snapshot
            disableAutoUpdate = disable
            worker = null
            val fileName = snapshot.profile.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(180).ifBlank { "profile" } + ".yaml"
            publish { it.copy(progress = null, document = ProfileDocumentRequest(++serial, id, kind, fileName)) }
        }
    }

    fun documentLaunched(id: Long): Boolean = synchronized(gate) {
        val request = state.value.document
        if (closed || request?.id != id || request.dispatched) false else {
            publish { it.copy(document = request.copy(dispatched = true)) }
            true
        }
    }

    fun documentLaunchFailed(id: Long) = synchronized(gate) {
        if (state.value.document?.id == id) {
            pending = null
            publish { it.copy(document = null, notice = ProfileListNotice.Failed, error = "Document picker unavailable") }
        }
    }

    fun documentResult(id: Long, reference: String?) = synchronized(gate) {
        if (retiredCreates.remove(id)) {
            if (reference != null) jobs.launch(NonCancellable) { cleanupCreated(reference) }
            return@synchronized
        }
        val request = state.value.document?.takeIf { it.id == id && it.dispatched } ?: return@synchronized
        if (closed) return@synchronized
        val snapshot = pending ?: return@synchronized
        pending = null
        publish { it.copy(document = null) }
        if (reference == null) {
            publish { it.copy(notice = ProfileListNotice.Cancelled) }
            return@synchronized
        }
        val disable = disableAutoUpdate
        start(if (request.kind == ProfileDocumentKind.Create) ProfileListProgress.Exporting else ProfileListProgress.Replacing) { token ->
            if (request.kind == ProfileDocumentKind.Create) export(reference, snapshot) else {
                val updated = documents.read(reference) { input ->
                    repository.replaceDocument(snapshot, input, disable) { current(token) }
                }
                withContext(NonCancellable) { maintenance.reload(); onChanged() }
                if (current(token) && repository.isCurrentSource(updated.id, updated.sourceIdentity)) {
                    onReplaced(updated) { current(token) }
                }
                publish { it.copy(notice = ProfileListNotice.Replaced) }
            }
        }
    }

    private suspend fun export(reference: String, snapshot: ProfileDocumentSnapshot) {
        val bytes = snapshot.yaml.toByteArray(Charsets.UTF_8)
        var verified = false
        try {
            val latest = repository.documentSnapshot(snapshot.profile.id)
            check(latest.profile.sourceIdentity == snapshot.profile.sourceIdentity && latest.revision == snapshot.revision) {
                "Profile changed while selecting the export document"
            }
            documents.write(reference, bytes)
            documents.read(reference) { input ->
                check(input.readBounded(MAX_PROFILE_BYTES).contentEquals(bytes)) { "Export read-back did not match configuration" }
            }
            currentCoroutineContext().ensureActive()
            verified = true
            publish { it.copy(notice = ProfileListNotice.Exported) }
        } finally {
            if (!verified) withContext(NonCancellable) {
                cleanupCreated(reference)
            }
        }
    }

    private suspend fun cleanupCreated(reference: String) {
        if (!runCatching { documents.deleteCreated(reference) }.getOrDefault(false)) {
            publish { it.copy(notice = ProfileListNotice.PartialDocument) }
        }
    }

    fun cancel() = synchronized(gate) {
        epoch++
        retireDocument()
        state.value.activeProfileIds.forEach(maintenance::cancel)
        worker?.cancel()
        pending = null
        publish { it.copy(progress = null, activeProfileIds = emptySet(), document = null, notice = ProfileListNotice.Cancelled) }
    }

    fun screenDetached() = synchronized(gate) {
        if (state.value.document != null || state.value.progress in setOf(ProfileListProgress.Exporting, ProfileListProgress.Replacing)) cancel()
    }

    private fun retireDocument() {
        state.value.document?.takeIf { it.kind == ProfileDocumentKind.Create && it.dispatched }?.let {
            retiredCreates += it.id
            while (retiredCreates.size > 32) retiredCreates.remove(retiredCreates.first())
        }
    }

    fun dismissNotice() { publish { it.copy(notice = null, error = null, batch = null) } }

    private fun current(token: Long): Boolean = synchronized(gate) { !closed && epoch == token && owner.isActive }
    private fun publish(change: (ProfileListState) -> ProfileListState) = synchronized(gate) {
        if (!closed) mutableState.value = change(mutableState.value)
    }

    private fun start(progress: ProfileListProgress, action: suspend (Long) -> Unit) = synchronized(gate) {
        if (closed || worker?.isCompleted == false || state.value.document != null) return@synchronized
        val token = ++epoch
        publish { it.copy(progress = progress, notice = null, error = null) }
        val task = jobs.launch(start = CoroutineStart.LAZY) {
            try { action(token) }
            catch (_: CancellationException) { if (current(token)) publish { it.copy(notice = ProfileListNotice.Cancelled) } }
            catch (error: Exception) { if (current(token)) publish { it.copy(error = error.message, notice =
                if (it.notice == ProfileListNotice.PartialDocument) it.notice else ProfileListNotice.Failed) } }
            finally { if (current(token)) publish { it.copy(progress = null, activeProfileIds = emptySet()) } }
        }
        worker = task
        task.start()
    }

    override fun close() = synchronized(gate) {
        if (!closed) {
            retireDocument()
            closed = true
            epoch++
            pending = null
            owner.cancel()
            mutableState.value = ProfileListState()
        }
    }
}
