package dev.aerove.lite.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileMaintenanceRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val original = "rules: ['MATCH,DIRECT']"
    private val replacement = "mode: direct\nrules: ['MATCH,DIRECT']"

    @Test fun responseHeadersAndSuccessfulTimePersistFromOneDownload() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(original).setHeader("subscription-userinfo", "upload=1; download=2; total=100; expire=123")
                .setHeader("content-disposition", "attachment; filename=plan.yaml"))
            val repo = ProfileRepository(temporary.root, ProfileValidator { }, clock = { 1234L })
            val imported = repo.addSubscription("Plan", server.url("/subscribe").toString())
            assertEquals(1, server.requestCount)
            assertEquals(1234L, imported.lastSuccessfulUpdateAt)
            assertTrue(imported.autoUpdate)
            assertEquals(86_400_000L, imported.updateIntervalMillis)
            assertEquals("attachment; filename=plan.yaml", imported.contentDisposition)
            assertEquals("upload=1; download=2; total=100; expire=123", imported.subscriptionUserInfo)
            assertEquals(imported, ProfileRepository(temporary.root, ProfileValidator { }).list().single())
        }
    }

    @Test fun metadataEditDuringDownloadIsPreservedWithoutChangingSourceIdentity() = runBlocking<Unit> {
        raced { repo, id, identity, release, pending ->
            val edited = repo.editMetadata(id, "Renamed", repo.list().single().url, false, 60_000L)
            assertEquals(identity, edited.sourceIdentity)
            release.countDown()
            val updated = pending.await().getOrThrow()
            assertEquals("Renamed", updated.name)
            assertFalse(updated.autoUpdate)
            assertEquals(60_000L, updated.updateIntervalMillis)
            assertEquals(identity, updated.sourceIdentity)
            assertEquals(4567L, updated.lastSuccessfulUpdateAt)
        }
    }

    @Test fun sourceEditRejectsOldResponseBeforeAtomicSave() = runBlocking<Unit> {
        raced { repo, id, identity, release, pending ->
            val edited = repo.editMetadata(id, "Changed", "https://example.com/new", true, 86_400_000L)
            assertNotEquals(identity, edited.sourceIdentity)
            release.countDown()
            assertTrue(pending.await().exceptionOrNull() is CancellationException)
            assertEquals(original, repo.readConfig(id))
            assertNull(repo.list().single().lastSuccessfulUpdateAt)
        }
    }

    @Test fun deletionWinsOverPendingResponse() = runBlocking<Unit> {
        raced { repo, id, _, release, pending ->
            repo.delete(id)
            release.countDown()
            assertTrue(pending.await().isFailure)
            assertTrue(repo.list().isEmpty())
        }
    }

    @Test fun replacementKeepsRevisionGuardAndChangesSourceIntent() = runBlocking<Unit> {
        raced { repo, id, identity, release, pending ->
            repo.replace(id, original + "\n# user edit")
            assertFalse(repo.isCurrentSource(id, identity))
            release.countDown()
            assertTrue(pending.await().exceptionOrNull() is ProfileConflictException)
            assertTrue(repo.readConfig(id).contains("user edit"))
        }
    }

    @Test fun cancelledRequestCannotCommitEvenWhenValidationCompletes() = runBlocking<Unit> {
        var current = true
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(replacement))
            val repo = ProfileRepository(temporary.root, ProfileValidator { if (it == replacement) current = false })
            val profile = repo.importText("A", original, server.url("/").toString())
            val before = File(temporary.root, "profiles/index.json").readBytes()
            val result = runCatching { repo.update(profile.id, ProfileMaintenanceRequest(profile.id, profile.sourceIdentity) { current }) }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertArrayEquals(before, File(temporary.root, "profiles/index.json").readBytes())
            assertEquals(original, repo.readConfig(profile.id))
        }
    }

    private suspend fun raced(action: suspend (ProfileRepository, String, String, CountDownLatch, kotlinx.coroutines.Deferred<Result<dev.aerove.lite.ProfileSummary>>) -> Unit) =
        kotlinx.coroutines.coroutineScope {
            val validating = CountDownLatch(1)
            val release = CountDownLatch(1)
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(replacement))
                val repo = ProfileRepository(temporary.root, ProfileValidator {
                    if (it == replacement) {
                        validating.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }, clock = { 4567L })
                val profile = repo.importText("A", original, server.url("/").toString())
                val pending = async(Dispatchers.Default) { runCatching { repo.update(profile.id,
                    ProfileMaintenanceRequest(profile.id, profile.sourceIdentity) { true }) } }
                try {
                    assertTrue(validating.await(10, TimeUnit.SECONDS))
                    action(repo, profile.id, profile.sourceIdentity, release, pending)
                } finally { release.countDown() }
            }
        }
}
