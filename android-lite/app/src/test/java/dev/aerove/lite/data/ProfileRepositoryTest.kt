package dev.aerove.lite.data

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val valid = "mode: rule\nrules:\n  - MATCH,DIRECT\n"
    private val validate = ProfileValidator { text ->
        if ("invalid" in text) throw ConfigValidationException(listOf(ConfigIssue("$.mode", "Invalid mode")))
    }

    private fun repository() = ProfileRepository(temporary.root, validate)

    @Test fun sourceAndSelectionSurviveReopen() = runBlocking<Unit> {
        val first = repository().importText("Main", valid, "https://example.com/token?key=private")
        val second = repository().importText("Local", valid.replace("rule", "direct"))
        repository().select(second.id)
        val reopened = repository()
        assertEquals(listOf(first, second), reopened.list())
        assertEquals(second.id, reopened.snapshot().selectedId)
        assertEquals(valid, reopened.readConfig(first.id))
    }

    @Test fun failedValidationPreservesCurrentPreviousAndUsable() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Main", valid).id
        repo.markUsable(id, valid)
        val replacement = valid.replace("rule", "global")
        repo.replace(id, replacement)
        val before = File(temporary.root, "profiles/index.json").readBytes()
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.replace(id, "mode: invalid") } }
        assertArrayEquals(before, File(temporary.root, "profiles/index.json").readBytes())
        assertEquals(replacement, repo.readConfig(id))
        assertEquals(valid, repo.readPreviousConfig(id))
        assertEquals(valid, repo.readLastUsableConfig(id))
    }

    @Test fun failedUsableConfirmationDoesNotMarkANewerRevision() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Main", valid).id
        repo.replace(id, valid.replace("rule", "direct"))
        assertThrows(ProfileConflictException::class.java) { runBlocking { repo.markUsable(id, valid) } }
        assertNull(repo.readLastUsableConfig(id))
    }

    @Test fun updatesKeepConfirmedConfigurationAcrossMultipleRevisions() = runBlocking<Unit> {
        val repo = repository()
        val id = repo.importText("Main", valid).id
        repo.markUsable(id, valid)
        repo.replace(id, valid.replace("rule", "global"))
        repo.replace(id, valid.replace("rule", "direct"))
        assertEquals(valid, repo.readLastUsableConfig(id))
        assertEquals(valid.replace("rule", "global"), repo.readPreviousConfig(id))
        assertEquals(3, File(temporary.root, "profiles").listFiles()!!.count { it.extension == "yaml" })
    }

    @Test fun localFileIsCopiedAndMalformedUtf8IsRefused() = runBlocking<Unit> {
        var closed = false
        val input = object : ByteArrayInputStream(valid.toByteArray()) {
            override fun close() { closed = true; super.close() }
        }
        val profile = repository().importFile("File", input)
        assertTrue(closed)
        assertNull(profile.url)
        assertEquals(valid, repository().readConfig(profile.id))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            runBlocking { repository().importFile("Broken", ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))) }
        }
        assertEquals(1, repository().list().size)
    }

    @Test fun concurrentInstancesDoNotLoseImports() = runBlocking<Unit> {
        coroutineScope {
            (0 until 12).map { index -> async { repository().importText("Profile $index", valid) } }.awaitAll()
        }
        assertEquals(12, repository().list().map { it.id }.distinct().size)
    }

    @Test fun deletionPersistsAndSelectsAnExistingProfile() = runBlocking<Unit> {
        val repo = repository()
        val a = repo.importText("A", valid)
        val b = repo.importText("B", valid)
        repo.delete(a.id)
        assertEquals(b.id, repository().snapshot().selectedId)
        repo.delete(b.id)
        assertEquals(ProfileSnapshot(emptyList(), null), repository().snapshot())
    }

    @Test fun corruptIndexDoesNotGetOverwrittenAsEmpty() = runBlocking<Unit> {
        val repo = repository()
        repo.importText("A", valid)
        val index = File(temporary.root, "profiles/index.json")
        index.writeText("{broken")
        assertThrows(org.json.JSONException::class.java) { runBlocking { repo.importText("B", valid) } }
        assertEquals("{broken", index.readText())
    }

    @Test fun indexCannotReadAPathOutsidePrivateStorage() = runBlocking<Unit> {
        val repo = repository()
        repo.importText("A", valid)
        val index = File(temporary.root, "profiles/index.json")
        val json = org.json.JSONObject(index.readText())
        json.getJSONArray("profiles").getJSONObject(0).put("current", "../outside.yaml")
        index.writeText(json.toString())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.snapshot() } }
    }
}
