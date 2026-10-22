package dev.aerove.lite.editor

import dev.aerove.lite.data.ConfigIssue
import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.EditorBackDecision
import dev.aerove.lite.data.MAX_PROFILE_BYTES
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.data.ProfileEditorSource
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileEditorSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "mode: rule\nrules:\n  - MATCH,DIRECT\n"
    private fun repository() = ProfileRepository(temporary.root, ProfileValidator { text ->
        if ("invalid" in text) throw ConfigValidationException(listOf(ConfigIssue("$.mode", "Invalid mode")))
    })

    @Test fun sourceEditValidatesAndAtomicallyPreservesPreviousAndUsable() = runBlocking<Unit> {
        val repo = repository()
        val profile = repo.importText("Local", original)
        repo.markUsable(profile.id, original)
        val session = ProfileEditorSession.local(repo, temporary.root, profile.id, profile.name)
        session.open()
        assertEquals(original, session.state.value.initialText)
        assertEquals(EditorBackDecision.Close, session.backDecision())
        session.contentChanged()
        assertEquals(EditorBackDecision.ConfirmChanges, session.backDecision())
        val indexBefore = File(temporary.root, "profiles/index.json").readBytes()
        assertFalse(session.save { "mode: invalid" })
        assertTrue(session.state.value.dirty)
        assertTrue(session.state.value.error!!.contains("$.mode"))
        assertArrayEquals(indexBefore, File(temporary.root, "profiles/index.json").readBytes())
        assertEquals(original, repo.readConfig(profile.id))
        val changed = original.replace("rule", "direct")
        assertTrue(session.save { changed })
        assertFalse(session.state.value.dirty)
        assertEquals(changed, repository().readConfig(profile.id))
        assertEquals(original, repo.readPreviousConfig(profile.id))
        assertEquals(original, repo.readLastUsableConfig(profile.id))
    }

    @Test fun externalUpdateSinceOpeningIsNeverOverwritten() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Local", original).id
        val session = ProfileEditorSession.local(repo, temporary.root, id, "Local")
        session.open()
        val refreshed = original.replace("rule", "global")
        repository().replace(id, refreshed)
        session.contentChanged()
        assertFalse(session.save { original.replace("rule", "direct") })
        assertEquals(refreshed, repository().readConfig(id))
        assertEquals(original, repo.readPreviousConfig(id))
        assertTrue(session.state.value.dirty)
    }

    @Test fun ioFailureRetainsDirtyDraftAndCanRetryWithoutReopening() = runBlocking<Unit> {
        var stored = original
        var fail = true
        val session = ProfileEditorSession.editable("Draft", object : ProfileEditorSource {
            override suspend fun read() = stored
            override suspend fun replace(expected: String, replacement: String) {
                assertEquals(expected, stored)
                if (fail) throw IOException("Disk full")
                stored = replacement
            }
        })
        session.open()
        session.contentChanged()
        assertFalse(session.save { "mode: direct" })
        assertEquals(original, stored)
        assertEquals("Disk full", session.state.value.error)
        assertFalse(session.state.value.saving)
        fail = false
        assertTrue(session.save { "mode: direct" })
        assertEquals("mode: direct", stored)
    }

    @Test fun previewLoadsResolvedConfigAndRejectsWrites() = runBlocking<Unit> {
        val resolved = "mode: rule\nproxies: [{name: resolved}]"
        val session = ProfileEditorSession.preview("Runtime") { resolved }
        session.open()
        assertTrue(session.readOnly)
        assertEquals(resolved, session.state.value.initialText)
        session.contentChanged()
        assertFalse(session.state.value.dirty)
        assertFalse(session.save { error("Preview must never read a save candidate") })
        assertEquals(EditorBackDecision.Close, session.backDecision())
    }

    @Test fun returningToSavedTextClearsDirtyAndDuplicateSavesAreCoalesced() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var writes = 0
        val session = ProfileEditorSession.editable("Local", object : ProfileEditorSource {
            override suspend fun read() = original
            override suspend fun replace(expected: String, replacement: String) { writes++; entered.complete(Unit); finish.await() }
        })
        session.open()
        session.contentChanged()
        session.reconcileDirty { original }
        assertFalse(session.state.value.dirty)
        session.contentChanged()
        val first = async { session.save { "mode: direct" } }
        entered.await()
        assertEquals(EditorBackDecision.Busy, session.backDecision())
        assertFalse(session.save { "mode: global" })
        finish.complete(Unit)
        assertTrue(first.await())
        assertEquals(1, writes)
    }

    @Test fun cancellationAfterCommitStartedFinishesCommitAndMarksTheExactTextSaved() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var stored = original
        val session = ProfileEditorSession.editable("Local", object : ProfileEditorSource {
            override suspend fun read() = stored
            override suspend fun replace(expected: String, replacement: String) {
                entered.complete(Unit); finish.await(); stored = replacement
            }
        })
        session.open()
        session.contentChanged()
        val saving = launch { session.save { "mode: direct" } }
        entered.await()
        saving.cancel()
        finish.complete(Unit)
        saving.cancelAndJoin()
        assertEquals("mode: direct", stored)
        assertFalse(session.state.value.dirty)
        assertFalse(session.state.value.saving)
    }

    @Test fun utf8ByteLimitIsCheckedBeforeAnyCommitAndFailedOpenCanRetry() = runBlocking<Unit> {
        var failOpen = true
        var writes = 0
        val session = ProfileEditorSession.editable("Local", object : ProfileEditorSource {
            override suspend fun read(): String { if (failOpen) throw IOException("Unavailable"); return original }
            override suspend fun replace(expected: String, replacement: String) { writes++ }
        })
        session.open()
        assertFalse(session.state.value.loading)
        assertNotNull(session.state.value.error)
        failOpen = false
        session.open()
        assertEquals(original, session.state.value.initialText)
        session.contentChanged()
        assertFalse(session.save { "中".repeat(MAX_PROFILE_BYTES / 3 + 1) })
        assertEquals(0, writes)
        assertTrue(session.state.value.dirty)
    }
}
