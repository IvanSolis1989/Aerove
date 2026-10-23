package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.profilesource.AgeSecret
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileMaintenanceStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val yaml = "rules: ['MATCH,DIRECT']"
    private fun repository(root: File): ProfileRepository {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        return ProfileRepository(root, validator, resources = ResourceBundleManager(File(root, "runtime"), validator,
            NativeResourceValidator { _, _, _, _ -> null }))
    }
    private fun write(root: File, path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test fun pruneUsesVerifiedIndexAndPreservesPrivateKeyLiveLeaseAndUnknownFiles() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val profile = repo.importText("live", yaml)
        AgeSecret("private-test-identity".toByteArray()).use { repo.setAgeSecret(profile.id, it) }
        val prepared = repo.prepare(profile.id)
        val lease = repo.retain(prepared)
        val orphan = write(root, "profiles/${UUID.randomUUID()}.yaml", yaml)
        val unknown = write(root, "profiles/user-kept.txt", "must survive")
        repo.pruneCache()
        assertFalse(orphan.exists())
        assertEquals("must survive", unknown.readText())
        assertEquals(prepared.source, File(root, "profiles/${prepared.revision}").readText())
        repo.readAgeSecret(profile.id)!!.use { secret ->
            val bytes = secret.copyBytes()
            try { assertEquals("private-test-identity", bytes.toString(Charsets.UTF_8)) } finally { bytes.fill(0) }
        }
        assertEquals(profile.id, repo.snapshot().selectedId)
        repo.release(lease)
    }

    @Test fun inventoryTracksExactOwnedPersistentMetadataAndRejectsStaleConfirmationWithoutWrites() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val settings = LiteSettingsStore(root)
        settings.read()
        val profile = repo.importText("live", yaml)
        val prepared = repo.prepare(profile.id)
        val lease = repo.retain(prepared)
        val original = repo.inspectMaintenance()
        assertEquals(1, original.profiles)
        assertEquals(1, original.retainedFiles)
        assertTrue(original.cacheBytes > 0)
        repo.release(lease)
        assertEquals(original.revision, repo.inspectMaintenance().revision)
        var revision = original.revision
        listOf("settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json",
            "runtime/resources/library/${"a".repeat(64)}.json").forEach { path ->
            write(root, path, "changed-$path")
            val next = repo.inspectMaintenance().revision
            assertNotEquals(path, revision, next)
            revision = next
        }
        settings.save(settings.read().copy(ipv6 = true))
        assertNotEquals(revision, repo.inspectMaintenance().revision)
        var invoked = false
        try { repo.resetUserData(original.revision) { invoked = true }; fail("Stale reset must abort") } catch (_: IllegalStateException) { }
        assertFalse(invoked)
        val orphan = write(root, "profiles/${UUID.randomUUID()}.yaml", yaml)
        try { repo.pruneCache(original.revision); fail("Stale prune must abort") } catch (_: IllegalStateException) { }
        assertTrue(orphan.isFile)
        assertEquals(profile.id, repo.profileSummary(profile.id)!!.id)
    }

    @Test fun resetReportsPostCommitCleanupFailureWithoutClaimingRollbackOrResumingOldData() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val settings = LiteSettingsStore(root)
        repo.importText("live", yaml)
        val malformedOrphan = File(root, "profiles/${UUID.randomUUID()}.yaml").apply { mkdir() }
        File(malformedOrphan, "keep-unexpected-child").writeText("do not recursively delete")
        val result = settings.deferPublication { repo.resetUserData { settings.reset() } }
        assertFalse(result.cleanupComplete)
        assertTrue(result.cleanupFailure is java.nio.file.DirectoryNotEmptyException)
        assertTrue(repository(root).list().isEmpty())
        assertTrue(malformedOrphan.isDirectory)
        assertFalse(File(root, ".restore-journal").exists())
    }

    @Test fun corruptIndexRefusesPruningBeforeDeletingAnyCandidate() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val candidate = write(root, "profiles/${UUID.randomUUID()}.yaml", yaml)
        write(root, "profiles/index.json", "not json")
        try { repo.pruneCache(); fail("Unreadable references must abort") } catch (_: org.json.JSONException) { }
        assertTrue(candidate.isFile)
    }

    @Test fun failedResetRestoresEveryOwnedMetadataFileAndDoesNotCollectSource() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val settings = LiteSettingsStore(root)
        settings.save(LiteSettings(ipv6 = true))
        val profile = repo.importText("live", yaml)
        AgeSecret("private-test-identity".toByteArray()).use { repo.setAgeSecret(profile.id, it) }
        val paths = listOf("settings/settings.json", "profiles/index.json", "settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json")
        paths.drop(2).forEach { write(root, it, "existing-$it") }
        val original = paths.associateWith { File(root, it).readBytes() }
        val revision = repo.documentSnapshot(profile.id).revision
        var failure: Throwable? = null
        try { settings.deferPublication { repo.resetUserData { settings.reset(); throw IOException("injected reset failure") } } }
        catch (caught: Throwable) { failure = caught }
        assertTrue(rollbackVerified(checkNotNull(failure)))
        paths.forEach { assertArrayEquals(it, original[it], File(root, it).readBytes()) }
        assertEquals(yaml, File(root, "profiles/$revision").readText())
        assertEquals(profile.id, repo.snapshot().selectedId)
        assertTrue(settings.read().ipv6)
    }

    @Test fun resetRequiresRuntimeReleaseThenCommitsFreshSettingsAndOnlyOwnedData() = runBlocking {
        val root = temporary.newFolder()
        val repo = repository(root)
        val settings = LiteSettingsStore(root)
        val before = settings.save(LiteSettings(ipv6 = true, auroraSeed = 918273))
        val profile = repo.importText("live", yaml)
        AgeSecret("private-test-identity".toByteArray()).use { repo.setAgeSecret(profile.id, it) }
        val prepared = repo.prepare(profile.id)
        val lease = repo.retain(prepared)
        var invoked = false
        try { repo.resetUserData { invoked = true }; fail("A live lease must prevent reset") } catch (_: IllegalStateException) { }
        assertFalse(invoked)
        repo.release(lease)
        listOf("settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json").forEach { write(root, it, "private") }
        val untouched = write(root, "unrelated/private-file", "keep")
        val result = settings.deferPublication { repo.resetUserData { settings.reset() } }
        assertTrue(result.cleanupComplete)
        assertNull(result.cleanupFailure)
        assertTrue(repository(root).list().isEmpty())
        assertFalse(File(root, "profiles/${prepared.revision}").exists())
        assertFalse(prepared.resourceDirectory!!.exists())
        assertFalse(File(root, "profiles/index.json").readText().contains("private-test-identity"))
        assertFalse(File(root, "settings/overrides.json").exists())
        assertFalse(File(root, "settings/builder-resources.yaml").exists())
        assertFalse(File(root, "backups/webdav.json").exists())
        assertFalse(settings.read().ipv6)
        assertNotEquals(before.auroraSeed, settings.read().auroraSeed)
        assertEquals("keep", untouched.readText())
    }
}
