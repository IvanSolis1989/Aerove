package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Exercises real file/revision transactions. Native parser admission is a separate Android gate. */
class ProfileBuilderSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository() = ProfileRepository(temporary.root, ProfileValidator { yaml ->
        val issues = ProfileBuilderDocument.parse(yaml).issues()
        require(issues.isEmpty()) { issues.toString() }
    })

    @Test fun nodeGroupRuleSaveReadbackAndReopenUseRealRepository() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Graph", ProfileBuilderDocument.empty().yaml()).id
        val session = ProfileBuilderSession(repo, id)
        session.open()
        session.edit { it.put(BuilderSection.Proxies, null, "node", mapOf("type" to "ss", "server" to "example.com", "port" to 443, "password" to "secret", "cipher" to "aes-128-gcm")) }
        session.edit { it.put(BuilderSection.Groups, null, "group", mapOf("type" to "select", "proxies" to listOf("node", "DIRECT"))) }
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,group")) }
        assertTrue(session.save())
        assertFalse(session.state.value.dirty)
        val reopened = ProfileBuilderSession(repository(), id)
        reopened.open()
        assertEquals("node", reopened.state.value.document!!.entries(BuilderSection.Proxies).single().name)
        assertEquals("MATCH,group", reopened.state.value.document!!.entries(BuilderSection.Rules).single().fields["rule"])
    }

    @Test fun staleSessionCannotOverwriteExternalReplacement() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Graph", ProfileBuilderDocument.empty().yaml()).id
        val session = ProfileBuilderSession(repo, id)
        session.open()
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,REJECT")) }
        val external = "mode: direct\nrules: ['MATCH,DIRECT']\n"
        repo.replace(id, external)
        assertFalse(session.save())
        assertTrue(session.state.value.dirty)
        assertNotNull(session.state.value.error)
        assertEquals(external, repository().readConfig(id))
    }

    @Test fun invalidReferenceNeverReplacesSavedRevision() = runBlocking<Unit> {
        val repo = repository()
        val original = ProfileBuilderDocument.empty().yaml()
        val id = repo.importText("Graph", original).id
        val session = ProfileBuilderSession(repo, id)
        session.open()
        session.edit { it.put(BuilderSection.Rules, 0, "", mapOf("rule" to "MATCH,missing")) }
        assertFalse(session.save())
        assertEquals(original, repo.readConfig(id))
        assertTrue(session.state.value.error!!.contains("$.rules[0]"))
    }
}
