package dev.aerove.lite.profiles

import dev.aerove.lite.data.ProfileMaintenanceFacade
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileListMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val yaml = "rules: ['MATCH,DIRECT']"

    private class Documents(val directory: File) : ProfileDocuments {
        val writeEntered = CompletableDeferred<Unit>()
        val deleteEntered = CompletableDeferred<Unit>()
        val deleted = CompletableDeferred<Unit>()
        val deletes = AtomicInteger()
        var writeRelease: CompletableDeferred<Unit>? = null
        var deleteRelease: CompletableDeferred<Unit>? = null
        fun file(reference: String) = File(directory, reference)
        override suspend fun <T> read(reference: String, action: suspend (InputStream) -> T): T = file(reference).inputStream().use { action(it) }
        override suspend fun write(reference: String, bytes: ByteArray) = withContext(NonCancellable) {
            writeEntered.complete(Unit); writeRelease?.await(); file(reference).writeBytes(bytes)
        }
        override suspend fun deleteCreated(reference: String): Boolean = withContext(NonCancellable) {
            deleteEntered.complete(Unit); deleteRelease?.await()
            deletes.incrementAndGet()
            val result = !file(reference).exists() || file(reference).delete()
            deleted.complete(Unit); result
        }
    }

    private suspend fun awaitDocument(feature: ProfileListFeature) = withTimeout(5000) { feature.state.first { it.document != null }.document!! }

    @Test fun quiesceWaitsForAnAlreadyEnteredNonCancellableRepositoryCallbackAndRejectsNewIntentUntilResume() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = ProfileRepository(temporary.newFolder("profiles"), ProfileValidator {})
        val first = repo.importText("First", yaml); val second = repo.importText("Second", yaml)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Unit>()
        val facade = ProfileMaintenanceFacade(repo, scope)
        val feature = ProfileListFeature(repo, facade, scope, Documents(temporary.newFolder("documents")), onChanged = {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await(); finished.complete(Unit) }
        })
        try {
            feature.reorder(listOf(second.id, first.id))
            withTimeout(5000) { entered.await() }
            assertEquals(listOf(second.id, first.id), repo.list().map { it.id })
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { feature.quiesce() }
            assertFalse("Maintenance cannot pass a callback still executing on accepted repository work", barrier.isCompleted)
            release.complete(Unit); withTimeout(5000) { barrier.await() }
            assertTrue(finished.isCompleted)
            feature.requestExport(first.id); feature.requestReplacement(first.id); feature.updateAll(); feature.reorder(listOf(first.id, second.id))
            assertFalse(feature.state.value.busy)
            assertEquals(listOf(second.id, first.id), repo.list().map { it.id })
            feature.resumeAfterMaintenance()
            feature.requestExport(first.id)
            assertEquals(first.id, awaitDocument(feature).profileId)
        } finally { release.complete(Unit); feature.close(); facade.close(); scope.cancel() }
    }

    @Test fun quiesceJoinsExportWriteAndItsNonCancellableCreatedDocumentCleanup() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = ProfileRepository(temporary.newFolder("profiles"), ProfileValidator {})
        val profile = repo.importText("Profile", yaml)
        val docs = Documents(temporary.newFolder("documents"))
        val writeRelease = CompletableDeferred<Unit>(); val deleteRelease = CompletableDeferred<Unit>()
        docs.writeRelease = writeRelease; docs.deleteRelease = deleteRelease
        val facade = ProfileMaintenanceFacade(repo, scope)
        val feature = ProfileListFeature(repo, facade, scope, docs, onChanged = {})
        try {
            feature.requestExport(profile.id); val request = awaitDocument(feature)
            feature.documentLaunched(request.id); feature.documentResult(request.id, "created.yaml")
            withTimeout(5000) { docs.writeEntered.await() }
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { feature.quiesce() }
            assertFalse("Maintenance cannot pass a document provider write", barrier.isCompleted)
            writeRelease.complete(Unit); withTimeout(5000) { docs.deleteEntered.await() }
            assertFalse("Maintenance also waits for cancellation cleanup", barrier.isCompleted)
            deleteRelease.complete(Unit); withTimeout(5000) { barrier.await() }
            assertFalse(docs.file("created.yaml").exists())
            assertEquals(yaml, repo.readConfig(profile.id)); assertFalse(feature.state.value.busy)
        } finally { writeRelease.complete(Unit); deleteRelease.complete(Unit); feature.close(); facade.close(); scope.cancel() }
    }

    @Test fun quiesceAlsoJoinsRetiredPickerCleanupThatHasNoCurrentWorkerReference() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = ProfileRepository(temporary.newFolder("profiles"), ProfileValidator {})
        val profile = repo.importText("Profile", yaml)
        val docs = Documents(temporary.newFolder("documents")); val release = CompletableDeferred<Unit>(); docs.deleteRelease = release
        val facade = ProfileMaintenanceFacade(repo, scope)
        val feature = ProfileListFeature(repo, facade, scope, docs, onChanged = {})
        try {
            feature.requestExport(profile.id); val request = awaitDocument(feature)
            feature.documentLaunched(request.id); feature.cancel()
            docs.file("late.yaml").writeText("created by picker")
            feature.documentResult(request.id, "late.yaml")
            withTimeout(5000) { docs.deleteEntered.await() }
            assertFalse(feature.state.value.busy)
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { feature.quiesce() }
            assertFalse("All owned cleanup jobs must be drained, not only worker", barrier.isCompleted)
            release.complete(Unit); withTimeout(5000) { barrier.await() }
            assertTrue(docs.deleted.isCompleted); assertFalse(docs.file("late.yaml").exists())
        } finally { release.complete(Unit); feature.close(); facade.close(); scope.cancel() }
    }

    @Test fun pausedLatePickerCannotStartRepositoryWorkOrCleanupIoAndResumeOnlyCleansItsCreatedDocument() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repo = ProfileRepository(temporary.newFolder("profiles"), ProfileValidator {})
        val profile = repo.importText("Profile", yaml)
        val docs = Documents(temporary.newFolder("documents"))
        val facade = ProfileMaintenanceFacade(repo, scope)
        val feature = ProfileListFeature(repo, facade, scope, docs, onChanged = {})
        try {
            feature.requestExport(profile.id); val request = awaitDocument(feature); feature.documentLaunched(request.id)
            feature.quiesce()
            docs.file("late.yaml").writeText("picker file"); docs.file("source.yaml").writeText("source must remain")
            feature.documentResult(request.id, "late.yaml")
            feature.requestReplacement(profile.id)
            assertFalse(feature.state.value.busy); assertEquals(0, docs.deletes.get())
            assertEquals(yaml, repo.readConfig(profile.id))
            feature.resumeAfterMaintenance()
            withTimeout(5000) { docs.deleted.await() }
            assertFalse(docs.file("late.yaml").exists()); assertEquals("source must remain", docs.file("source.yaml").readText())
            feature.requestReplacement(profile.id)
            assertEquals(ProfileDocumentKind.Open, awaitDocument(feature).kind)
        } finally { feature.close(); facade.close(); scope.cancel() }
    }
}
