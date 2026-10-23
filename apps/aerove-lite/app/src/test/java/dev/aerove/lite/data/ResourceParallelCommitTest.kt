package dev.aerove.lite.data

import dev.aerove.lite.resources.ResourceManagementRuleCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceParallelCommitTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun cache(root: File) = ResourceManagementRuleCache(root,
        CompatibilityValidator(NativeConfigValidator { null }, File(temporary.root, "runtime")),
        NativeResourceValidator { _, path, _, _ -> check(File(path).isFile); null }, ResourceDownloader(), { 1000L })

    @Test fun differentGeoDownloadsBothStartBeforeEitherResponseAndBothPersist() = runBlocking<Unit> {
        val started = CountDownLatch(2); val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("geo-${request.path}")
                }
            }
            server.start()
            val root = temporary.newFolder("shared-cache")
            val firstCache = cache(root); val secondCache = cache(root)
            coroutineScope {
                val first = async { firstCache.sync("MMDB", server.url("/mmdb").toString(), "", "", assetKind = "mmdb") }
                val second = async { secondCache.sync("ASN", server.url("/asn").toString(), "", "", assetKind = "mmdb") }
                try { withContext(Dispatchers.IO) { assertTrue("Both HTTP requests must begin before either response", started.await(3, TimeUnit.SECONDS)) } }
                finally { release.countDown() }
                val saved = awaitAll(first, second)
                assertEquals(2, saved.size)
                assertEquals(saved[0], firstCache.snapshot("MMDB", server.url("/mmdb").toString(), "", "", "mmdb"))
                assertEquals(saved[1], secondCache.snapshot("ASN", server.url("/asn").toString(), "", "", "mmdb"))
            }
        }
    }

    @Test fun sameContentRestoreBoundaryRejectsOldDownloadAndCompletesItsCleanup() = runBlocking<Unit> {
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("late-content")
                }
            }
            server.start()
            val root = temporary.newFolder("restore-cache")
            val first = cache(root); val otherOwner = cache(root)
            val url = server.url("/source").toString()
            val old = first.sync("MMDB", url, "", "", "original".byteInputStream(), assetKind = "mmdb")
            val pending = async { runCatching { first.sync("MMDB", url, "", "", assetKind = "mmdb") } }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                otherOwner.withRestoreTransaction(invalidateInFlight = true) { assertEquals(old, otherOwner.snapshot("MMDB", url, "", "", "mmdb")) }
            } finally { release.countDown() }
            assertTrue(pending.await().exceptionOrNull() is IllegalStateException)
            assertEquals(old, first.snapshot("MMDB", url, "", "", "mmdb"))
            assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun sourceGuardChangedDuringValidationRejectsBeforeIndexPublication() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val root = temporary.newFolder("source-guard")
            val oldOwner = cache(root)
            val url = server.url("/geo").toString()
            val saved = oldOwner.sync("MMDB", url, "", "", "original".byteInputStream(), assetKind = "mmdb")
            var current = true
            val changedOwner = ResourceManagementRuleCache(root,
                CompatibilityValidator(NativeConfigValidator { null }, File(temporary.root, "runtime")),
                NativeResourceValidator { _, _, _, _ -> current = false; null }, ResourceDownloader(), { 2000L })
            server.enqueue(MockResponse().setBody("stale-content"))
            try { changedOwner.sync("MMDB", url, "", "", assetKind = "mmdb", isCurrent = { current }); fail() }
            catch (_: CancellationException) { }
            assertEquals(saved, oldOwner.snapshot("MMDB", url, "", "", "mmdb"))
            assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancelledParallelDownloadsJoinBeforeAllStagingIsReleased() = runBlocking<Unit> {
        val started = CountDownLatch(2); val release = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("late-content")
                }
            }
            server.start()
            val root = temporary.newFolder("cancel-parallel")
            val owner = cache(root)
            val operation = launch { coroutineScope {
                launch { owner.sync("MMDB", server.url("/one").toString(), "", "", assetKind = "mmdb") }
                launch { owner.sync("ASN", server.url("/two").toString(), "", "", assetKind = "mmdb") }
            } }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                operation.cancelAndJoin()
                assertTrue(File(root, ".staging").listFiles().orEmpty().isEmpty())
                assertNull(owner.snapshot("MMDB", server.url("/one").toString(), "", "", "mmdb"))
                assertNull(owner.snapshot("ASN", server.url("/two").toString(), "", "", "mmdb"))
            } finally { release.countDown(); operation.cancelAndJoin() }
        }
    }
}
