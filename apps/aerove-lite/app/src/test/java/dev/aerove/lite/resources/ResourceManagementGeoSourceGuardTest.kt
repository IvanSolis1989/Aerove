package dev.aerove.lite.resources

import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ResourceDownloader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementGeoSourceGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun changedUrlRejectsOldLatePayloadBeforeCachePublicationAndRequiresExplicitNewSync() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/old") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return MockResponse().setBody(if (request.path == "/old") "old-geo-payload" else "new-geo-payload")
                }
            }
            server.start()
            val old = GeoResourcePreferences(urls = GeoResourceKind.entries.associateWith { server.url(if (it == GeoResourceKind.MMDB) "/old" else "/${it.wire}").toString() })
            val settings = AtomicReference(old)
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val catalog = GeoResourceCatalog(runtime, validator, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), settings::get)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val pending = scope.async { runCatching { catalog.sync(GeoResourceKind.MMDB) } }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val fresh = old.withUrl(GeoResourceKind.MMDB, server.url("/new").toString())
                settings.set(fresh); release.countDown()
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is CancellationException)
                assertEquals(1, server.requestCount)
                settings.set(old)
                assertNull("The old URL response must not publish an index before the post-check", catalog.snapshot().single { it.kind == GeoResourceKind.MMDB }.saved)
                settings.set(fresh)
                assertNull(catalog.snapshot().single { it.kind == GeoResourceKind.MMDB }.saved)
                val saved = catalog.sync(GeoResourceKind.MMDB).single { it.kind == GeoResourceKind.MMDB }.saved
                assertNotNull(saved)
                assertEquals("new-geo-payload".toByteArray().size.toLong(), saved!!.bytes)
                assertEquals(2, server.requestCount)
            } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
