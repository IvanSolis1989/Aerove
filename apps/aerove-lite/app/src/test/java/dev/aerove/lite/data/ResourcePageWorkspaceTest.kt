package dev.aerove.lite.data

import dev.aerove.lite.integration.ResourcePageWorkspace
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.resources.*
import dev.aerove.lite.overrides.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ResourcePageWorkspaceTest {
    private class Fixture(val server: MockWebServer, workAllowed: () -> Boolean = { true }) {
        val root = Files.createTempDirectory("resource-page").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var preparations = 0
        val validator = CompatibilityValidator(NativeConfigValidator { preparations++; null }, File(root, "runtime"))
        val parser = object : NativeRulePayloadInspector {
            override fun inspect(path: String) = RulePayloadInspection("text", "domain", 1)
            override fun dump(path: String, behavior: String, format: String) = File(path).readText()
        }
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, offlineRules = parser)
        val builder = BuilderResourceLibrary(root)
        val profiles = ProfileRepository(root, ProfileValidator { preparations++ }, resources = manager, globalResources = builder)
        val library = ResourceManagementLibraryRepository(builder, manager, GlobalRulePayloadRepository(root, builder, manager, parser, profiles))
        val scripts = ScriptLibrarySession(OverrideRepository(root), SubscriptionDownloader())
        val preferences = GeoResourcePreferences(urls = GeoResourceKind.entries.associateWith { server.url("/geo/${it.wire}").toString() })
        val geo = GeoResourceCatalog(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), { preferences })
        val repository = ResourceManagementRepository(profiles, offlineRules = parser)
        val session = ResourceManagementSession(repository, scope, {}, library = library, geoCatalog = geo)
        val maintenance = ResourceManagementMaintenance(geo, library, scripts, { preferences }, {})
        val page = ResourcePageWorkspace(session, scripts, maintenance, repository, scope, { "not-a-real-profile" }, workAllowed, { true }, { throw it })
        suspend fun close() { page.quiesce(); session.closeAndJoin(); scripts.closeAndJoin(); page.close(); scope.cancel(); root.deleteRecursively() }
    }
    @Test fun globalPageLoadsWithoutPreparingSelectedProfileAndManualUpdateUsesSameRepositories() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(if (request.path == "/icons")
                    """{"name":"set","icons":[{"name":"A","url":"https://example.com/a.png"}]}""" else "binary fixture")
            }
            server.start()
            val f = Fixture(server)
            try {
                f.builder.importRemoteIconSet(f.builder.snapshot(), server.url("/icons").toString(), "icons")
                val initialRequests = server.requestCount
                f.page.load()
                withTimeout(5000) { f.session.state.first { it.library.size == 1 && it.busy.isEmpty() } }
                assertNull(f.session.state.value.profileId)
                assertNull(f.session.state.value.snapshot)
                assertEquals(0, f.preparations)
                f.page.updateIfDue { true }
                withTimeout(5000) { f.page.state.first { it.completed == 1L } }
                assertEquals(initialRequests, server.requestCount) // autoUpdate=false means no extra HTTP.
                f.page.updateAll()
                withTimeout(5000) { f.page.state.first { it.completed == 2L && !it.updating } }
                assertTrue(f.page.state.value.failures.isEmpty())
                assertEquals(initialRequests + 4, server.requestCount)
                assertEquals(3, f.geo.snapshot().count { it.saved != null })
                assertEquals("icons", BuilderResourceLibrary(f.root).snapshot().resources.iconSets.single().name)
                assertEquals(0, f.preparations)
            } finally { f.close() }
        }
    }
    @Test fun retainedGlobalPageReadsActualLibraryAfterRestoreResume() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); val f = Fixture(server)
            val global = dev.aerove.lite.integration.GlobalResourceWorkspace(f.builder, f.profiles,
                GlobalRulePayloadRepository(f.root, f.builder, f.manager, f.parser, f.profiles), { null }, {})
            try {
                assertTrue(global.prepare())
                assertTrue(global.session.putProxy(null, "before", mapOf("type" to "direct")))
                global.quiesce()
                val before = f.builder.snapshot()
                f.builder.replace(before, before.resources.copy(proxies = listOf(BuilderEntry("restored", mapOf("type" to "direct")))))
                assertEquals("before", global.session.state.value.snapshot!!.resources.proxies.single().name)
                global.resumeAfterMaintenance()
                assertEquals("restored", global.session.state.value.snapshot!!.resources.proxies.single().name)
            } finally { global.closeAndJoin(); f.close() }
        }
    }
    @Test fun maintenanceWaitsForLateOpenAndClosesDiscardedInputBeforeReturning() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); val f = Fixture(server)
            val opened = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var closes = 0
            try {
                val resource = ManagedResource("$.rule-providers.one", "ruleset", "hash", 1, 1, null, null, false, "domain", "text")
                f.page.sideload("not-a-real-profile", resource) {
                    withContext(NonCancellable) { opened.complete(Unit); release.await() }
                    object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closes++; super.close() } }
                }
                opened.await()
                val drain = async(start = CoroutineStart.UNDISPATCHED) { f.page.quiesce() }
                assertFalse(drain.isCompleted)
                release.complete(Unit); drain.await()
                assertEquals(1, closes)
                assertNull(f.session.state.value.snapshot)
                f.page.resumeAfterMaintenance()
            } finally { release.complete(Unit); f.close() }
        }
    }
    @Test fun completedAutomaticUpdateImmediatelyAdmitsManualUpdateBeforeOldJobReturns() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("binary fixture")
            }
            server.start(); val f = Fixture(server)
            val submitted = CompletableDeferred<Unit>()
            val observer = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                f.page.state.first { it.completed == 1L }
                f.page.updateAll()
                submitted.complete(Unit)
            }
            try {
                f.page.updateIfDue { true }
                withTimeout(5000) { submitted.await() }
                withTimeout(5000) { f.page.state.first { it.completed == 2L && !it.updating } }
                assertTrue(f.page.state.value.failures.isEmpty())
                assertEquals(3, server.requestCount)
                assertEquals(3, f.geo.snapshot().count { it.saved != null })
                assertEquals(0, f.preparations)
            } finally { observer.cancelAndJoin(); f.close() }
        }
    }

    @Test fun pausedAutomaticLateCompletionCannotReleaseTheNewManualUpdateSlot() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val oldAtReturn = java.util.concurrent.CountDownLatch(1)
            val releaseOld = java.util.concurrent.CountDownLatch(1)
            val manualHttp = java.util.concurrent.CountDownLatch(1)
            val releaseHttp = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    manualHttp.countDown()
                    check(releaseHttp.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("binary fixture")
                }
            }
            server.start()
            lateinit var f: Fixture
            val activeChecks = java.util.concurrent.atomic.AtomicInteger()
            f = Fixture(server) {
                if (f.page.state.value.updating && activeChecks.incrementAndGet() == 2) {
                    oldAtReturn.countDown()
                    check(releaseOld.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                true
            }
            try {
                f.page.updateIfDue { true }
                assertTrue(oldAtReturn.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val oldJob = f.scope.coroutineContext[Job]!!.children.single()
                f.page.pauseAutomatic()
                f.page.updateAll()
                assertTrue(manualHttp.await(5, java.util.concurrent.TimeUnit.SECONDS))
                releaseOld.countDown()
                oldJob.join()
                assertTrue(f.page.state.value.updating)
                assertEquals(0L, f.page.state.value.completed)
                releaseHttp.countDown()
                withTimeout(5000) { f.page.state.first { it.completed == 1L && !it.updating } }
                assertEquals(3, server.requestCount)
            } finally { releaseOld.countDown(); releaseHttp.countDown(); f.close() }
        }
    }

    @Test fun reloadNotificationReentryCannotCommitTheCancelledAutomaticOverManualUpdate() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val oldAtReturn = java.util.concurrent.CountDownLatch(1)
            val releaseOld = java.util.concurrent.CountDownLatch(1)
            val manualHttp = java.util.concurrent.CountDownLatch(1)
            val releaseHttp = java.util.concurrent.CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    manualHttp.countDown()
                    check(releaseHttp.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    return MockResponse().setBody("binary fixture")
                }
            }
            server.start()
            lateinit var f: Fixture
            val activeChecks = java.util.concurrent.atomic.AtomicInteger()
            f = Fixture(server) {
                if (f.page.state.value.updating && activeChecks.incrementAndGet() == 2) {
                    oldAtReturn.countDown()
                    check(releaseOld.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                true
            }
            val observer = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                f.session.state.first { it.busy.isNotEmpty() }
                f.page.pauseAutomatic()
                f.page.updateAll()
                check(manualHttp.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            try {
                f.page.updateIfDue { true }
                assertTrue(oldAtReturn.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val oldJob = f.scope.coroutineContext[Job]!!.children.single()
                releaseOld.countDown()
                withTimeout(5000) { observer.join(); oldJob.join() }
                assertTrue(f.page.state.value.updating)
                assertEquals(0L, f.page.state.value.completed)
                releaseHttp.countDown()
                withTimeout(5000) { f.page.state.first { it.completed == 1L && !it.updating } }
                assertEquals(3, server.requestCount)
            } finally { releaseOld.countDown(); releaseHttp.countDown(); observer.cancelAndJoin(); f.close() }
        }
    }

}
