package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileBuilderDraftTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository() = ProfileRepository(temporary.root, ProfileValidator { yaml ->
        require(ProfileBuilderDocument.parse(yaml).issues().isEmpty()) { "Invalid graph" }
    })

    @Test fun untouchedDraftNeverCreatesSelectsOrDeletesAnyProfile() = runBlocking<Unit> {
        val repo = repository()
        val session = ProfileBuilderSession.create(repo, "New")
        session.open()
        assertTrue(session.isNew)
        assertNull(session.savedProfileId)
        assertTrue(repo.list().isEmpty())
        assertNull(repo.snapshot().selectedId)
        assertFalse(session.save())
        assertTrue(session.cancel())
        assertTrue(repo.list().isEmpty())
    }

    @Test fun dirtyDraftCancellationCannotPersistPartialGraph() = runBlocking<Unit> {
        val repo = repository()
        val original = repo.importText("Existing", ProfileBuilderDocument.empty().yaml())
        val session = ProfileBuilderSession.create(repo, "New")
        session.edit { it.put(BuilderSection.Proxies, null, "half", mapOf("type" to "direct")) }
        assertTrue(session.state.value.dirty)
        assertTrue(session.cancel())
        assertFalse(session.save())
        assertEquals(listOf(original.id), repo.list().map { it.id })
        assertEquals(original.id, repo.snapshot().selectedId)
    }

    @Test fun firstConfirmedSavePersistsOneCompleteGraphAndKeepsExistingSelection() = runBlocking<Unit> {
        val repo = repository()
        val previous = repo.importText("Existing", ProfileBuilderDocument.empty().yaml())
        val session = ProfileBuilderSession.create(repo, "Draft")
        session.edit { it.put(BuilderSection.Proxies, null, "node", mapOf("type" to "direct")) }
        session.edit { it.put(BuilderSection.Groups, null, "group", mapOf("type" to "select", "proxies" to listOf("node"))) }
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,group")) }
        session.renameDraft("Confirmed")
        assertEquals(1, repo.list().size)
        assertTrue(session.save())
        val id = requireNotNull(session.savedProfileId)
        assertFalse(session.isNew)
        assertEquals(id, session.profileId)
        assertEquals(2, repo.list().size)
        assertTrue(repo.list().single { it.id == id }.custom)
        assertFalse(repo.list().single { it.id == previous.id }.custom)
        assertEquals(previous.id, repo.snapshot().selectedId)
        val reopened = ProfileBuilderSession(repository(), id)
        reopened.open()
        assertEquals("Confirmed", reopened.state.value.name)
        assertEquals("MATCH,group", reopened.state.value.document!!.entries(BuilderSection.Rules).single().fields["rule"])
        assertFalse(session.state.value.canUndo)
        assertTrue(session.save())
        assertEquals(2, repo.list().size)
    }

    @Test fun undoToInitialStateDropsDraftAndRedoRestoresActualChanges() = runBlocking<Unit> {
        val repo = repository()
        val session = ProfileBuilderSession.create(repo, "Draft")
        session.edit { it.put(BuilderSection.Proxies, null, "node", mapOf("type" to "direct")) }
        session.undo()
        assertFalse(session.state.value.dirty)
        assertFalse(session.save())
        assertTrue(repo.list().isEmpty())
        session.redo()
        assertTrue(session.state.value.dirty)
        assertTrue(session.save())
        assertEquals(1, repo.list().size)
    }

    @Test fun invalidDraftSaveLeavesNoSelectableRecordAndCanBeUndone() = runBlocking<Unit> {
        val repo = repository()
        val session = ProfileBuilderSession.create(repo, "Draft")
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,missing")) }
        assertFalse(session.save())
        assertNotNull(session.state.value.error)
        assertTrue(repo.list().isEmpty())
        session.undo()
        assertFalse(session.state.value.dirty)
        assertNull(session.state.value.error)
    }

    @Test fun cancellingExistingEditPreservesSavedRevisionAndCannotSaveClosedSession() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Existing", ProfileBuilderDocument.empty().yaml()).id
        val before = repo.documentSnapshot(id)
        val session = ProfileBuilderSession(repo, id)
        session.open()
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,REJECT")) }
        assertTrue(session.cancel())
        assertFalse(session.save())
        assertEquals(before, repo.documentSnapshot(id))
    }
}
