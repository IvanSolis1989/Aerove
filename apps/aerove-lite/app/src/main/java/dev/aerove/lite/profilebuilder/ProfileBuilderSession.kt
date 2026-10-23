package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ProfileDocumentSnapshot
import dev.aerove.lite.data.ProfileRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ProfileBuilderState(
    val document: ProfileBuilderDocument? = null,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/** The repository's revision/source CAS and native validation remain the only commit path. */
class ProfileBuilderSession private constructor(private val repository: ProfileRepository,
    private val sourceId: String?, private val initialDraftName: String?) {
    constructor(repository: ProfileRepository, profileId: String) : this(repository, profileId, null)
    private val draftId = "draft:${UUID.randomUUID()}"
    private var committedId: String? = null
    val profileId: String get() = sourceId ?: committedId ?: draftId
    val savedProfileId: String? get() = sourceId ?: committedId
    val isNew: Boolean get() = sourceId == null && committedId == null
    private val seed = if (initialDraftName != null) ProfileBuilderDocument.empty() else null
    private val mutable = MutableStateFlow(ProfileBuilderState(document = seed, name = initialDraftName.orEmpty()))
    val state: StateFlow<ProfileBuilderState> = mutable
    private val operation = Mutex()
    private var baseline: ProfileDocumentSnapshot? = null
    private var savedDocument: ProfileBuilderDocument? = seed
    private var savedName: String = initialDraftName.orEmpty()
    private var closed = false
    private data class Edit(val document: ProfileBuilderDocument, val name: String)
    private val undo = java.util.ArrayDeque<Edit>()
    private val redo = java.util.ArrayDeque<Edit>()

    suspend fun open() = operation.withLock {
        if (closed || baseline != null || initialDraftName != null) return@withLock
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            val snapshot = withContext(Dispatchers.IO) { repository.documentSnapshot(profileId) }
            val document = withContext(Dispatchers.Default) { ProfileBuilderDocument.parse(snapshot.yaml) }
            baseline = snapshot
            savedDocument = document
            savedName = snapshot.profile.name
            mutable.value = ProfileBuilderState(document, name = savedName)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.value = mutable.value.copy(error = failure.message) }
        finally { mutable.value = mutable.value.copy(busy = false) }
    }

    fun edit(change: (ProfileBuilderDocument) -> ProfileBuilderDocument) {
        check(!state.value.busy && !closed) { "Editor is not editable" }
        val document = requireNotNull(state.value.document)
        try {
            val next = change(document)
            if (next.sameContent(document)) return
            rememberEdit(document, state.value.name)
            present(next, state.value.name)
        }
        catch (failure: Exception) { mutable.value = state.value.copy(error = failure.message) }
    }

    fun renameDraft(name: String) {
        check(isNew && !state.value.busy && !closed) { "Only an open draft can be renamed" }
        if (name == state.value.name) return
        rememberEdit(requireNotNull(state.value.document), state.value.name)
        present(requireNotNull(state.value.document), name)
    }

    fun undo() = restore(undo, redo)
    fun redo() = restore(redo, undo)

    /** Closing never writes or deletes profiles. A never-saved draft exists only in this session. */
    fun cancel(): Boolean {
        if (state.value.busy) return false
        closed = true
        undo.clear(); redo.clear()
        mutable.value = state.value.copy(dirty = false, canUndo = false, canRedo = false)
        return true
    }

    private fun rememberEdit(document: ProfileBuilderDocument, name: String) {
        if (undo.size == 64) undo.removeFirst()
        undo.addLast(Edit(document, name)); redo.clear()
    }

    private fun restore(from: java.util.ArrayDeque<Edit>, to: java.util.ArrayDeque<Edit>) {
        if (state.value.busy || closed || from.isEmpty()) return
        to.addLast(Edit(requireNotNull(state.value.document), state.value.name))
        val edit = from.removeLast()
        present(edit.document, edit.name)
    }

    private fun present(document: ProfileBuilderDocument, name: String) {
        mutable.value = state.value.copy(document = document, name = name,
            dirty = !document.sameContent(savedDocument) || name != savedName,
            error = null, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty())
    }

    suspend fun save(): Boolean = operation.withLock {
        if (closed) return@withLock false
        val expected = baseline
        val document = state.value.document ?: return@withLock false
        if (!state.value.dirty) return@withLock expected != null
        mutable.value = state.value.copy(busy = true, error = null)
        try {
            val yaml = withContext(Dispatchers.Default) {
                val name = state.value.name.trim()
                require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() }) { "Invalid profile name" }
                val issues = document.issues()
                require(issues.isEmpty()) { issues.joinToString("\n") { "${it.path}: ${it.reason}" } }
                document.yaml()
            }
            val saved = withContext(NonCancellable + Dispatchers.IO) {
                if (expected == null) {
                    check(committedId == null) { "Profile was committed; reopen it before editing again" }
                    val created = repository.importText(state.value.name.trim(), yaml, null, custom = true)
                    committedId = created.id
                    repository.documentSnapshot(created.id)
                } else {
                    repository.replaceDocument(expected, yaml.byteInputStream())
                    repository.documentSnapshot(profileId)
                }
            }
            // Do not claim our candidate was saved if a concurrent replacement won after the commit.
            require(saved.yaml == yaml) { "Configuration changed after save; reopen before editing" }
            baseline = saved
            savedDocument = ProfileBuilderDocument.parse(saved.yaml)
            savedName = saved.profile.name
            undo.clear(); redo.clear()
            mutable.value = ProfileBuilderState(ProfileBuilderDocument.parse(saved.yaml), name = savedName)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.value = state.value.copy(error = failure.message); false }
        finally { mutable.value = state.value.copy(busy = false) }
    }

    companion object {
        suspend fun create(repository: ProfileRepository, name: String): ProfileBuilderSession {
            require(name.isNotBlank() && name.length <= 200 && name.none { it.isISOControl() }) { "Invalid profile name" }
            return ProfileBuilderSession(repository, null, name.trim())
        }
    }
}
