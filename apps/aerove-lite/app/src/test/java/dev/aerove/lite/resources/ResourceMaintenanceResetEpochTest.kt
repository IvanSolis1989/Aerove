package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceMaintenanceResetEpochTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val owner: ResourceManagementMaintenance, val repository: OverrideRepository,
        val scripts: ScriptLibrarySession, val builder: BuilderResourceLibrary)
    private suspend fun fixture(server: MockWebServer, script: Boolean = true, now: Long = System.currentTimeMillis()): Fixture {
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val native = NativeResourceValidator { _, _, _, _ -> null }
        val prefs = GeoResourcePreferences(autoUpdate = true, urls = GeoResourceKind.entries.associateWith { server.url("/geo-${it.wire}").toString() })
        val repository = OverrideRepository(temporary.root)
        if (script) repository.putScript(ScriptResource("s", "remote", server.url("/script").toString(), "old", 0))
        val scripts = ScriptLibrarySession(repository, SubscriptionDownloader())
        val builder = BuilderResourceLibrary(temporary.root)
        val owner = ResourceManagementMaintenance(GeoResourceCatalog(runtime, validator, native, ResourceDownloader(), { prefs }, { now }),
            ResourceManagementLibraryRepository(builder, ResourceBundleManager(runtime, validator, native)), scripts, { prefs }, {}, { now })
        return Fixture(owner, repository, scripts, builder)
    }
    private suspend fun scriptFailure(resetKey: String?, obsolete: Boolean = false) = coroutineScope {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (requests.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setResponseCode(503)
                } else MockResponse().setBody("new-script")
            }
            server.start(); val fixture = fixture(server); val current = AtomicBoolean(true)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val old = scope.async { fixture.owner.updateIfDue(current::get, { false }) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                if (obsolete) current.set(false) else fixture.owner.resetAutomaticBackoff(resetKey)
                assertFalse(old.isCompleted)
                release.countDown(); val result = withTimeout(5000) { old.await() }
                if (obsolete) assertTrue("stale errorCode must not enter current result", result.failed.isEmpty())
                val retried = fixture.owner.updateIfDue({ true }, { false })
                assertEquals(2, requests.get()); assertTrue("script:s" in retried.updated)
                assertEquals("new-script", fixture.repository.snapshot().scripts.single().content)
                fixture.owner.resetAutomaticBackoff()
                fixture.owner.updateIfDue({ true }, { false }); assertEquals(2, requests.get())
            } finally { release.countDown(); fixture.owner.quiesce(); fixture.scripts.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    @Test fun oldScriptErrorCodeCannotRestoreGloballyResetBackoff() = runBlocking<Unit> { scriptFailure(null) }
    @Test fun oldScriptErrorCodeCannotRestoreKeyResetBackoff() = runBlocking<Unit> { scriptFailure("script:s") }
    @Test fun obsoleteScriptReceiptCannotRecordFailure() = runBlocking<Unit> { scriptFailure(null, obsolete = true) }

    @Test fun oldGeoFailureCannotRestoreResetBackoff() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (requests.incrementAndGet() == 1) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setResponseCode(503)
                } else MockResponse().setBody("binary fixture")
            }
            server.start(); val fixture = fixture(server, script = false)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val old = scope.async { fixture.owner.updateIfDue({ true }, { true }) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                fixture.owner.resetAutomaticBackoff(); release.countDown(); old.await()
                fixture.owner.updateIfDue({ true }, { true }); assertEquals(4, requests.get())
            } finally { release.countDown(); fixture.owner.quiesce(); fixture.scripts.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun oldIconFailureCannotRestoreResetBackoff() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val requests = AtomicInteger()
            val icons = """{"name":"set","icons":[{"name":"A","url":"https://example.com/a.png"}]}"""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (requests.incrementAndGet() == 2) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); MockResponse().setResponseCode(503)
                } else MockResponse().setBody(icons)
            }
            server.start(); val fixture = fixture(server, script = false, now = System.currentTimeMillis() + 25 * 3_600_000L)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                fixture.builder.importRemoteIconSet(fixture.builder.snapshot(), server.url("/icons").toString(), "icons")
                val old = scope.async { fixture.owner.updateIfDue({ true }, { false }) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                fixture.owner.resetAutomaticBackoff(); release.countDown(); old.await()
                fixture.owner.updateIfDue({ true }, { false }); assertEquals(3, requests.get())
            } finally { release.countDown(); fixture.owner.quiesce(); fixture.scripts.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun resetDoesNotCancelManualResourceUpdate() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/script") { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    return MockResponse().setBody("downloaded")
                }
            }
            server.start(); val fixture = fixture(server)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val work = scope.async { fixture.owner.updateAll({ true }, { true }) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                fixture.owner.resetAutomaticBackoff(); assertFalse(work.isCompleted); release.countDown()
                val result = withTimeout(5000) { work.await() }
                assertTrue(result.failed.isEmpty()); assertTrue("script:s" in result.updated)
                assertEquals("downloaded", fixture.repository.snapshot().scripts.single().content)
            } finally { release.countDown(); fixture.owner.quiesce(); fixture.scripts.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
