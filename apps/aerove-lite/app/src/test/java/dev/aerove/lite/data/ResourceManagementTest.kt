package dev.aerove.lite.data

import dev.aerove.lite.resources.ResourceManagementRepository
import dev.aerove.lite.resources.ResourceManagementLibraryRepository
import dev.aerove.lite.resources.ResourceSubscriptionInfo
import dev.aerove.lite.profilebuilder.BuilderEntry
import dev.aerove.lite.profilebuilder.BuilderResources
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(root: File, native: NativeResourceValidator = NativeResourceValidator { _, _, _, _ -> null }): ProfileRepository {
        val runtime = File(root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        return ProfileRepository(root, validator, resources = ResourceBundleManager(runtime, validator, native))
    }
    private fun source(server: MockWebServer, second: Boolean = false) = """
        rule-providers:
          one: {type: http, url: '${server.url("/one")}', path: one.yaml, behavior: domain}
          ${if (second) "two: {type: http, url: '${server.url("/two")}', path: two.yaml, behavior: domain}" else ""}
        rules: ['RULE-SET,one,DIRECT', 'MATCH,DIRECT']
    """.trimIndent()
    private fun payload(domain: String) = "payload:\n - '+.$domain'\n"
    private fun body(domain: String) = MockResponse().setBody(payload(domain))
    private val key = "$.rule-providers.one"

    @Test fun sideloadPersistsOriginalSourceAndSingleSyncRestoresUrlWithoutRefetchingOtherAssets() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(body("first.example")); server.enqueue(body("other.example"))
            val repo = repository(temporary.root)
            val yaml = source(server, true)
            val id = repo.importText("Managed", yaml).id
            val resources = ResourceManagementRepository(repo)
            val before = resources.snapshot(id)
            val lease = repo.retain(before.prepared)
            val replaced = resources.sideload(id, key, ByteArrayInputStream(payload("local.example").toByteArray()))
            assertNotEquals(before.prepared.generation, replaced.prepared.generation)
            assertEquals(yaml, repo.readConfig(id))
            assertEquals(before.entries.single { it.key.endsWith("two") }.sha256, replaced.entries.single { it.key.endsWith("two") }.sha256)
            assertEquals(2, server.requestCount)
            assertTrue(replaced.entries.single { it.key == key }.sideLoaded)
            assertTrue(ResourceManagementRepository(repository(temporary.root)).preview(id, key).contains("local.example"))
            assertTrue(before.prepared.resourceDirectory!!.isDirectory)
            server.enqueue(body("synced.example"))
            val synced = resources.refresh(id, key)
            assertFalse(synced.entries.single { it.key == key }.sideLoaded)
            assertTrue(resources.preview(id, key).contains("synced.example"))
            assertEquals(3, server.requestCount)
            assertEquals(yaml, synced.prepared.source)
            repo.release(lease)
        }
    }

    @Test fun failedAndCancelledReplacementRestorePreviousOverrideAndGeneration() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(body("first.example"))
            var cancelled = false
            val repo = repository(temporary.root, NativeResourceValidator { _, _, _, _ -> if (cancelled) throw CancellationException("fixture cancellation") else null })
            val id = repo.importText("Managed", source(server)).id
            val resources = ResourceManagementRepository(repo)
            resources.sideload(id, key, ByteArrayInputStream(payload("local.example").toByteArray()))
            val generation = repo.prepare(id).generation
            cancelled = true
            try { resources.sideload(id, key, ByteArrayInputStream(payload("new.example").toByteArray())); fail() }
            catch (_: CancellationException) { }
            cancelled = false
            assertEquals(generation, repo.prepare(id).generation)
            assertTrue(resources.preview(id, key).contains("local.example"))
            server.enqueue(MockResponse().setResponseCode(503))
            try { resources.refresh(id, key); fail() } catch (_: ConfigValidationException) { }
            assertEquals(generation, repo.prepare(id).generation)
            assertTrue(resources.snapshot(id).entries.single { it.key == key }.sideLoaded)
        }
    }

    @Test fun realBackupContainsOverrideAndRestoreDoesNotDownloadOriginalUrl() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(body("remote.example"))
            val root = temporary.newFolder("source")
            val repo = repository(root)
            val id = repo.importText("Managed", source(server)).id
            ResourceManagementRepository(repo).sideload(id, key, ByteArrayInputStream(payload("local.example").toByteArray()))
            val archive = temporary.newFile("backup.zip")
            val manifest = BackupRepository(root, repo, LiteSettingsStore(root)).createBackup(archive)
            assertTrue(manifest.files.any { it.path.contains("/resources/.overrides/") })
            val target = temporary.newFolder("target")
            val restored = repository(target)
            BackupRepository(target, restored, LiteSettingsStore(target)).restore(archive.inputStream(), BackupRestoreScope.ProfilesOnly,
                BackupRestoreStrategy.Compatible, false)
            val restoredId = restored.list().single().id
            assertTrue(ResourceManagementRepository(restored).preview(restoredId, key).contains("local.example"))
            assertEquals(1, server.requestCount)
            assertEquals(source(server), restored.readConfig(restoredId))
        }
    }

    @Test fun globalLibrarySyncValidatesSavesReadsBackAndAliasedProfileReusesExactPayload() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(body("global.example"))
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val manager = ResourceBundleManager(runtime, validator, NativeResourceValidator { _, path, _, _ ->
                assertTrue(File(path).isFile); null
            })
            val library = BuilderResourceLibrary(temporary.root)
            library.replace(library.snapshot(), BuilderResources(ruleProviders = listOf(BuilderEntry("one", mapOf(
                "type" to "http", "url" to server.url("/one").toString(), "path" to "rules.yaml", "behavior" to "domain")))))
            val global = ResourceManagementLibraryRepository(library, manager)
            assertNull(global.snapshot().entries.single().bytes)
            val resourceId = global.snapshot().entries.single().id
            val saved = global.sync(resourceId).entries.single()
            assertEquals(1, saved.count)
            assertEquals(payload("global.example").toByteArray().size.toLong(), saved.bytes)
            val repo = ProfileRepository(temporary.root, validator, resources = manager)
            val id = repo.importText("Alias", source(server).replace("one:", "alias:").replace("RULE-SET,one,", "RULE-SET,alias,")).id
            assertEquals(1, server.requestCount)
            val resources = ResourceManagementRepository(repo)
            assertTrue(resources.preview(id, "$.rule-providers.alias").contains("global.example"))
            assertEquals(saved.bytes, resources.snapshot(id).entries.single().bytes)
            server.enqueue(body("target.example"))
            resources.refresh(id, "$.rule-providers.alias")
            assertEquals(2, server.requestCount)
            assertTrue(resources.preview(id, "$.rule-providers.alias").contains("target.example"))
            server.enqueue(MockResponse().setBody("payload: [1]"))
            try { global.sync(resourceId); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(saved, global.snapshot().entries.single())
        }
    }

    @Test fun subscriptionHeaderMetadataIsRealBoundedAndInvalidMetadataDoesNotDiscardPayload() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(body("quota.example").setHeader("Subscription-Userinfo", "upload=3; download=7; total=100; expire=1900000000"))
            val repo = repository(temporary.root)
            val id = repo.importText("Quota", source(server)).id
            val resources = ResourceManagementRepository(repo)
            val actual = resources.snapshot(id).entries.single().subscriptionInfo!!
            assertEquals(10L, actual.used); assertEquals(100L, actual.total); assertEquals(1900000000L, actual.expireSeconds)
            server.enqueue(body("valid.example").setHeader("Subscription-Userinfo", "upload=NaN; download=-1; total=100"))
            assertNull(resources.refresh(id, key).entries.single().subscriptionInfo)
            assertTrue(resources.preview(id, key).contains("valid.example"))
            listOf("upload=1; download=2; total=0", "upload=1; download=2; total=999999999999999999999999", "upload=1;upload=2;download=3;total=4",
                "upload=9223372036854775807;download=1;total=2", "x".repeat(8193)).forEach { assertNull(ResourceSubscriptionInfo.parse(it)) }
        }
    }
}
