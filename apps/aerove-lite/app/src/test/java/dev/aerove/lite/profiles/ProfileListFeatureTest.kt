package dev.aerove.lite.profiles

import dev.aerove.lite.data.ProfileMaintenanceFacade
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileListFeatureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val yaml = "rules: ['MATCH,DIRECT']"
    private val validator = ProfileValidator { require(!it.contains("INVALID")) { "Invalid fixture configuration" } }

    private class Documents : ProfileDocuments {
        val files = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        val deleted = mutableListOf<String>()
        var corruptWrite = false
        var allowDelete = true
        override suspend fun <T> read(reference: String, action: suspend (java.io.InputStream) -> T): T =
            ByteArrayInputStream(files.getValue(reference)).use { action(it) }
        override suspend fun write(reference: String, bytes: ByteArray) { files[reference] = if (corruptWrite) byteArrayOf(1) else bytes }
        override suspend fun deleteCreated(reference: String): Boolean {
            synchronized(deleted) { deleted += reference }
            if (allowDelete) files.remove(reference)
            return allowDelete
        }
    }

    private suspend fun await(feature: ProfileListFeature, predicate: (ProfileListState) -> Boolean): ProfileListState =
        withTimeout(10_000) { feature.state.first(predicate) }

    @Test fun staleSortPreservesNewMetadataNewImportsAndDeletion() = runBlocking<Unit> {
        val repo = ProfileRepository(temporary.root, validator)
        val a = repo.importText("A", yaml)
        val b = repo.importText("B", yaml)
        val c = repo.importText("C", yaml)
        repo.select(c.id)
        repo.delete(b.id)
        val d = repo.importText("D", yaml)
        repo.editMetadata(a.id, "Renamed", null, false, 60_000)
        val snapshot = repo.reorder(listOf(c.id, b.id, a.id))
        assertEquals(listOf(c.id, a.id, d.id), snapshot.profiles.map { it.id })
        assertEquals("Renamed", snapshot.profiles[1].name)
        assertFalse(snapshot.profiles[1].autoUpdate)
        assertEquals(c.id, snapshot.selectedId)
        assertEquals(snapshot, ProfileRepository(temporary.root, validator).snapshot())
    }

    @Test fun exportUsesAcknowledgedRequestAndActualReadback() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("Plan", yaml)
            feature.requestExport(profile.id)
            val request = await(feature) { it.document != null }.document!!
            feature.documentResult(request.id, "unacknowledged")
            assertTrue(documents.files.isEmpty())
            assertTrue(feature.documentLaunched(request.id))
            assertFalse(feature.documentLaunched(request.id))
            feature.documentResult(request.id, "created")
            await(feature) { it.notice == ProfileListNotice.Exported && !it.busy }
            assertArrayEquals(yaml.toByteArray(), documents.files["created"])
            feature.documentResult(request.id, "duplicate")
            assertFalse(documents.files.containsKey("duplicate"))
            assertTrue(documents.deleted.isEmpty())
        }
    }

    @Test fun failedExportCleansOnlyCreatedDocumentAndReportsPartialCleanupFailure() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("A", yaml)
            documents.files["source"] = yaml.toByteArray()
            documents.corruptWrite = true
            documents.allowDelete = false
            feature.requestExport(profile.id)
            val request = await(feature) { it.document != null }.document!!
            feature.documentLaunched(request.id)
            feature.documentResult(request.id, "created")
            await(feature) { it.notice == ProfileListNotice.PartialDocument && !it.busy }
            assertEquals(listOf("created"), documents.deleted)
            assertArrayEquals(yaml.toByteArray(), documents.files["source"])
        }
    }

    @Test fun replacementPreservesCurrentMetadataAndCanDisableAutomaticUpdates() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("A", yaml, "https://example.com/sub")
            feature.requestReplacement(profile.id, disableAutoUpdate = true)
            val request = await(feature) { it.document != null }.document!!
            repo.editMetadata(profile.id, "Latest name", profile.url, true, 120_000)
            documents.files["source"] = "$yaml\n# replacement".toByteArray()
            feature.documentLaunched(request.id)
            feature.documentResult(request.id, "source")
            await(feature) { it.notice == ProfileListNotice.Replaced && !it.busy }
            val updated = repo.list().single()
            assertEquals("Latest name", updated.name)
            assertEquals(120_000L, updated.updateIntervalMillis)
            assertFalse(updated.autoUpdate)
            assertNotEquals(profile.sourceIdentity, updated.sourceIdentity)
            assertTrue(repo.readConfig(profile.id).contains("replacement"))
            assertTrue(documents.deleted.isEmpty())
        }
    }

    @Test fun sourceChangeWhilePickerOpenRejectsReplacementWithoutDeletingInput() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("A", yaml, "https://example.com/old")
            feature.requestReplacement(profile.id)
            val request = await(feature) { it.document != null }.document!!
            repo.editMetadata(profile.id, "A", "https://example.com/new", true, 86_400_000)
            documents.files["source"] = "$yaml\n# old intent".toByteArray()
            feature.documentLaunched(request.id)
            feature.documentResult(request.id, "source")
            await(feature) { it.notice == ProfileListNotice.Cancelled && !it.busy }
            assertEquals(yaml, repo.readConfig(profile.id))
            assertTrue(documents.deleted.isEmpty())
            assertTrue(documents.files.containsKey("source"))
        }
    }

    @Test fun cancelledPickerIgnoresReplacementAndCleansLateCreateResult() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("A", yaml)
            feature.requestReplacement(profile.id)
            var request = await(feature) { it.document != null }.document!!
            feature.documentLaunched(request.id)
            feature.cancel()
            documents.files["source"] = "INVALID".toByteArray()
            feature.documentResult(request.id, "source")
            assertTrue(documents.deleted.isEmpty())
            assertEquals(yaml, repo.readConfig(profile.id))
            feature.requestExport(profile.id)
            request = await(feature) { it.document != null }.document!!
            feature.documentLaunched(request.id)
            feature.screenDetached()
            documents.files["created"] = byteArrayOf()
            feature.documentResult(request.id, "created")
            withTimeout(10_000) { while (documents.files.containsKey("created")) kotlinx.coroutines.yield() }
            assertFalse(documents.files.containsKey("created"))
            assertTrue(documents.files.containsKey("source"))
        }
    }

    @Test fun concurrentBatchSkipsLocalAndAggregatesFailures() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(yaml))
            server.enqueue(MockResponse().setBody("INVALID"))
            server.enqueue(MockResponse().setBody(yaml))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repo = ProfileRepository(temporary.root, validator)
            val ids = (1..3).map { repo.importText("Plan $it", yaml, server.url("/$it").toString()).id }
            repo.importText("Local", yaml)
            val callbacks = AtomicInteger()
            val facade = ProfileMaintenanceFacade(repo, scope, onUpdated = { _, _ -> callbacks.incrementAndGet() })
            val feature = ProfileListFeature(repo, facade, scope, Documents(), onChanged = {})
            try {
                feature.updateAll()
                val result = await(feature) { it.notice == ProfileListNotice.Updated && !it.busy }.batch!!
                assertEquals(2, result.updated)
                assertEquals(1, result.skipped)
                assertTrue(result.failures.single().id in ids)
                assertEquals(setOf("/1", "/2", "/3"), (1..3).map { server.takeRequest().path }.toSet())
                assertEquals(3, server.requestCount)
                assertEquals(2, callbacks.get())
            } finally { feature.close(); facade.close(); scope.cancel() }
        }
    }

    @Test fun parallelBatchSurvivesScreenDetachAndRetainsSourceGuard() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val incoming = "$yaml\n# concurrent download"
            server.enqueue(MockResponse().setBody(incoming))
            server.enqueue(MockResponse().setBody(incoming))
            val validating = CountDownLatch(2)
            val release = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repo = ProfileRepository(temporary.root, ProfileValidator {
                if (it == incoming) { validating.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            })
            val first = repo.importText("First", yaml, server.url("/first").toString())
            val second = repo.importText("Second", yaml, server.url("/second").toString())
            val facade = ProfileMaintenanceFacade(repo, scope)
            val feature = ProfileListFeature(repo, facade, scope, Documents(), onChanged = {})
            try {
                feature.updateAll()
                assertTrue("Both downloads must reach validation before either is released", validating.await(10, TimeUnit.SECONDS))
                assertEquals(setOf(first.id, second.id), feature.state.value.activeProfileIds)
                feature.screenDetached()
                assertEquals(ProfileListProgress.Updating, feature.state.value.progress)
                assertEquals(setOf(first.id, second.id), feature.state.value.activeProfileIds)
                repo.editMetadata(second.id, "New source", "https://example.com/new", true, 60_000)
                release.countDown()
                val result = await(feature) { it.notice == ProfileListNotice.Updated && !it.busy }.batch!!
                assertEquals(1, result.updated)
                assertEquals(second.id, result.failures.single().id)
                assertEquals(2, server.requestCount)
                assertEquals(incoming, repo.readConfig(first.id))
                assertEquals(yaml, repo.readConfig(second.id))
            } finally { release.countDown(); feature.close(); facade.close(); scope.cancel() }
        }
    }

    @Test fun changedExportSourceCleansNewDocumentAndDoesNotWriteStaleContent() = runBlocking<Unit> {
        fixture { repo, feature, documents ->
            val profile = repo.importText("A", yaml, "https://example.com/old")
            feature.requestExport(profile.id)
            val request = await(feature) { it.document != null }.document!!
            repo.editMetadata(profile.id, "A", "https://example.com/new", true, 86_400_000)
            feature.documentLaunched(request.id)
            feature.documentResult(request.id, "created")
            await(feature) { it.notice == ProfileListNotice.Failed && !it.busy }
            assertEquals(listOf("created"), documents.deleted)
            assertFalse(documents.files.containsKey("created"))
            assertEquals(yaml, repo.readConfig(profile.id))
        }
    }

    @Test fun cancellingBatchCancelsAllActiveFlights() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val incoming = "$yaml\n# downloaded"
            server.enqueue(MockResponse().setBody(incoming))
            server.enqueue(MockResponse().setBody(incoming))
            val validating = CountDownLatch(2)
            val release = CountDownLatch(1)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repo = ProfileRepository(temporary.root, ProfileValidator {
                if (it == incoming) { validating.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            })
            val first = repo.importText("First", yaml, server.url("/first").toString())
            val second = repo.importText("Second", yaml, server.url("/second").toString())
            val facade = ProfileMaintenanceFacade(repo, scope)
            val feature = ProfileListFeature(repo, facade, scope, Documents(), onChanged = {})
            try {
                feature.updateAll()
                assertTrue(validating.await(10, TimeUnit.SECONDS))
                feature.cancel()
                release.countDown()
                withTimeout(10_000) { facade.runningProfileIds.first { it.isEmpty() } }
                assertEquals(2, server.requestCount)
                assertEquals(yaml, repo.readConfig(first.id))
                assertEquals(yaml, repo.readConfig(second.id))
                assertNull(repo.list().find { it.id == first.id }!!.lastSuccessfulUpdateAt)
                assertEquals(ProfileListNotice.Cancelled, feature.state.value.notice)
            } finally { release.countDown(); feature.close(); facade.close(); scope.cancel() }
        }
    }

    private suspend fun fixture(action: suspend (ProfileRepository, ProfileListFeature, Documents) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = ProfileRepository(temporary.root, validator)
        val facade = ProfileMaintenanceFacade(repo, scope)
        val documents = Documents()
        val feature = ProfileListFeature(repo, facade, scope, documents, onChanged = {})
        try { action(repo, feature, documents) } finally { feature.close(); facade.close(); scope.cancel() }
    }
}
