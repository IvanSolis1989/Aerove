package dev.aerove.lite.data

import dev.aerove.lite.integration.RepositoryProfilePropertiesAccess
import dev.aerove.lite.profilesource.AgeSecret
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ProfilePropertiesAccessTest {
    @Test fun privateReadAndMetadataSavePreserveKeyAndOnlyPublishAfterRepositoryReadback() = runBlocking {
        val root = Files.createTempDirectory("private-properties").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var applies = 0
        var publications = 0
        val profiles = ProfileRepository(root, ProfileValidator {})
        val maintenance = ProfileMaintenanceFacade(profiles, owner, onUpdated = { _, _ -> applies++ })
        try {
            val seed = profiles.importText("Original", "rules: [MATCH,DIRECT]", "https://example.invalid/sub")
            AgeSecret("private-fixture".toByteArray()).use { profiles.setAgeSecret(seed.id, it) }
            val access = RepositoryProfilePropertiesAccess(profiles, maintenance, { true }, {
                assertEquals("Changed", ProfileRepository(root, ProfileValidator {}).profileSummary(seed.id)?.name)
                publications++
            })
            access.load(seed.id).use { private ->
                assertFalse(private.toString().contains("private-fixture"))
                val bytes = private.secret!!.copyBytes()
                try { assertEquals("private-fixture", bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
                val saved = access.save(private.document, "Changed", seed.url, false, 300_000, null, false)
                assertEquals("Changed", saved.name)
            }
            profiles.readAgeSecret(seed.id)!!.use { secret ->
                val bytes = secret.copyBytes()
                try { assertEquals("private-fixture", bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
            }
            assertEquals(1, publications)
            assertEquals(0, applies)
        } finally { maintenance.close(); owner.cancel(); root.deleteRecursively() }
    }

    @Test fun maintenanceAdmissionRejectsPrivateLoadAndCandidateWithoutWriting() = runBlocking {
        val root = Files.createTempDirectory("private-properties-paused").toFile()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val profiles = ProfileRepository(root, ProfileValidator {})
        val maintenance = ProfileMaintenanceFacade(profiles, owner)
        try {
            val seed = profiles.importText("Original", "rules: [MATCH,DIRECT]")
            val expected = profiles.documentSnapshot(seed.id)
            val access = RepositoryProfilePropertiesAccess(profiles, maintenance, { false }, { error("No publication") })
            assertTrue(runCatching { access.load(seed.id) }.isFailure)
            assertTrue(runCatching { access.save(expected, "Changed", null, false, 300_000, null, true) }.isFailure)
            assertEquals("Original", profiles.profileSummary(seed.id)?.name)
        } finally { maintenance.close(); owner.cancel(); root.deleteRecursively() }
    }
}
