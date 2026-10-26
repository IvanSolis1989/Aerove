package dev.aerove.lite.data

import java.io.File
import okio.utf8Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ProfileEditorSource {
    suspend fun read(): String
    suspend fun replace(expected: String, replacement: String)
}

data class ProfileEditorState(
    val initialText: String? = null,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val version: Long = 0,
    val error: String? = null,
)

enum class EditorBackDecision { Close, ConfirmChanges, Busy }

class ProfileEditorSession private constructor(
    val title: String,
    val readOnly: Boolean,
    private val source: ProfileEditorSource,
    val maxBytes: Int = MAX_PROFILE_BYTES,
) {
    private val operation = Mutex()
    private val mutableState = MutableStateFlow(ProfileEditorState())
    val state: StateFlow<ProfileEditorState> = mutableState.asStateFlow()
    @Volatile private var savedText: String? = null

    suspend fun open() = operation.withLock {
        if (savedText != null) return@withLock
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val text = withContext(Dispatchers.IO) { source.read().also { checkEditorSize(it, maxBytes) } }
            savedText = text
            mutableState.value = ProfileEditorState(initialText = text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = failure.message ?: failure.javaClass.simpleName) }
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }

    fun contentChanged() {
        if (readOnly) return
        mutableState.update { it.copy(dirty = true, version = it.version + 1, error = null) }
    }

    suspend fun reconcileDirty(readDocument: suspend () -> String) {
        val before = state.value
        if (readOnly || before.loading || before.saving || savedText == null) return
        val baseline = savedText
        val equal = withContext(Dispatchers.Default) { readDocument() == baseline }
        mutableState.update { if (it.version == before.version && !it.saving && savedText === baseline) it.copy(dirty = !equal) else it }
    }

    suspend fun save(readDocument: suspend () -> String): Boolean {
        if (readOnly || !operation.tryLock()) return false
        try {
            val before = state.value
            val expected = savedText ?: return false
            if (!before.dirty || before.loading) return false
            mutableState.update { it.copy(saving = true, error = null) }
            try {
                val candidate = withContext(Dispatchers.IO) { readDocument().also { checkEditorSize(it, maxBytes) } }
                // An atomic store commit must finish even if the route's coroutine is cancelled.
                withContext(NonCancellable + Dispatchers.IO) {
                    source.replace(expected, candidate)
                    savedText = candidate
                    mutableState.update { it.copy(initialText = candidate, dirty = it.version != before.version) }
                }
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(error = failure.message ?: failure.javaClass.simpleName) }
                return false
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        } finally {
            operation.unlock()
        }
    }

    fun backDecision(): EditorBackDecision = when {
        state.value.saving -> EditorBackDecision.Busy
        state.value.dirty -> EditorBackDecision.ConfirmChanges
        else -> EditorBackDecision.Close
    }

    fun dismissError() { mutableState.update { it.copy(error = null) } }

    companion object {
        fun local(repository: ProfileRepository, privateDirectory: File, profileId: String, title: String): ProfileEditorSession =
            editable(title, object : ProfileEditorSource {
                override suspend fun read(): String = repository.readConfig(profileId)
                override suspend fun replace(expected: String, replacement: String) {
                    privateDomainTransaction(privateDirectory) {
                        if (repository.readConfig(profileId) != expected) throw ProfileConflictException(profileId)
                        repository.replace(profileId, replacement)
                    }
                }
            })

        fun editable(title: String, source: ProfileEditorSource, maxBytes: Int = MAX_PROFILE_BYTES): ProfileEditorSession = ProfileEditorSession(title, false, source, maxBytes)

        fun ruleEditable(title: String, source: ProfileEditorSource): ProfileEditorSession =
            ProfileEditorSession(title, false, source, MAX_RULE_PREVIEW_BYTES)

        fun preview(title: String, maxBytes: Int = MAX_PROFILE_BYTES, readRuntimeConfig: suspend () -> String): ProfileEditorSession =
            ProfileEditorSession(title, true, object : ProfileEditorSource {
                override suspend fun read(): String = readRuntimeConfig()
                override suspend fun replace(expected: String, replacement: String): Unit = error("Preview is read-only")
            }, maxBytes)
    }
}

internal fun checkEditorSize(text: String, maxBytes: Int = MAX_PROFILE_BYTES) {
    if (text.length > maxBytes || text.utf8Size() > maxBytes) editorSizeFailure(maxBytes)
}

/** The editor's refusal, raised before a source too large to open is read into memory. */
internal fun editorSizeFailure(maxBytes: Int = MAX_PROFILE_BYTES): Nothing = throw IllegalArgumentException("Configuration exceeds $maxBytes UTF-8 bytes")
