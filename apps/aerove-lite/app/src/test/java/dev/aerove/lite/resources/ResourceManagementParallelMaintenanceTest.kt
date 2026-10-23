package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementParallelMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val maintenance: ResourceManagementMaintenance, val scripts: OverrideRepository,
        val builder: BuilderResourceLibrary, val changed: AtomicInteger)

    private suspend fun fixture(server: MockWebServer, automatic: Boolean = false, onScriptChanged: suspend () -> Unit = {}): Fixture {
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val native = NativeResourceValidator { _, _, _, _ -> null }
        val settings = GeoResourcePreferences(autoUpdate = automatic, urls = GeoResourceKind.entries.associateWith { server.url("/geo-${it.wire}").toString() })
        val builder = BuilderResourceLibrary(temporary.root)
        val repository = OverrideRepository(temporary.root)
        listOf("a", "b").forEach { id -> repository.putScript(ScriptResource(id, "Script $id", server.url("/script-$id").toString(), "old-$id", 1)) }
        val changed = AtomicInteger()
        val maintenance = ResourceManagementMaintenance(
            GeoResourceCatalog(runtime, validator, native, ResourceDownloader(), { settings }),
            ResourceManagementLibraryRepository(builder, ResourceBundleManager(runtime, validator, native)),
            ScriptLibrarySession(repository, SubscriptionDownloader(), onScriptChanged), { settings }, { changed.incrementAndGet() })
        return Fixture(maintenance, repository, builder, changed)
    }

    @Test fun independentRemoteScriptsBothStartBeforeEitherResponseAndBothPersist() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val entered = CountDownLatch(2); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/script-")) {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return MockResponse().setBody("downloaded:${request.path}")
                }
            }
            server.start()
            val fixture = fixture(server)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val operation = scope.async { fixture.maintenance.updateAll({ true }, { true }) }
                val simultaneous = withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) }
                release.countDown()
                val result = withTimeout(5000) { operation.await() }
                assertTrue("The second independent script did not start until the first response was released", simultaneous)
                assertTrue(result.failed.isEmpty())
                assertTrue(result.updated.containsAll(setOf("script:a", "script:b")))
                assertEquals(mapOf("a" to "downloaded:/script-a", "b" to "downloaded:/script-b"),
                    fixture.scripts.snapshot().scripts.associate { it.id to it.content })
                assertEquals(1, fixture.changed.get())
            } finally { release.countDown(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun cancelThenMaintenanceJoinsEveryAlreadyStartedNonCancellableChildBeforeReset() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("downloaded:${request.path}")
            }
            server.start()
            val entered = CountDownLatch(2)
            val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            val committed = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
            val tails = AtomicInteger()
            val builder = BuilderResourceLibrary(temporary.root)
            val fixture = fixture(server) {
                val index = tails.getAndIncrement()
                withContext(NonCancellable) {
                    entered.countDown()
                    releases[index].await()
                    val before = builder.snapshot()
                    val set = BuilderIconSet("late-$index", icons = listOf(BuilderIcon("Late", "https://example.com/late-$index.png")))
                    builder.replace(before, before.resources.copy(iconSets = before.resources.iconSets + set))
                    committed[index].complete(Unit)
                }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val operation = scope.async { fixture.maintenance.updateAll({ true }, { true }) }
                assertTrue("Independent script commit callbacks did not both begin", withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                operation.cancel()
                val started = CompletableDeferred<Unit>()
                val barrier = async(Dispatchers.Default) { started.complete(Unit); fixture.maintenance.quiesce() }
                started.await()
                assertNull(withTimeoutOrNull(250) { barrier.await(); true })
                releases[0].complete(Unit)
                withTimeout(5000) { committed[0].await() }
                assertNull("Barrier skipped the other cancelled child's late commit", withTimeoutOrNull(250) { barrier.await(); true })
                releases[1].complete(Unit)
                withTimeout(5000) { barrier.await(); operation.join() }
                val beforeReset = builder.snapshot()
                assertEquals(2, beforeReset.resources.iconSets.size)
                builder.replace(beforeReset, BuilderResources())
                assertTrue(builder.snapshot().resources.iconSets.isEmpty())
                assertEquals(0, fixture.changed.get())
                assertTrue(runCatching { fixture.maintenance.updateAll({ true }, { true }) }.exceptionOrNull() is CancellationException)
                fixture.maintenance.resumeAfterMaintenance()
                assertTrue(fixture.maintenance.updateIfDue({ false }, { true }).updated.isEmpty())
            } finally { releases.forEach { it.complete(Unit) }; scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun concurrentFailureIsAttributedOnlyToItsOwnScript() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/script-a") MockResponse().setResponseCode(500)
                    else MockResponse().setBody("downloaded:${request.path}")
            }
            server.start()
            val fixture = fixture(server)
            val result = fixture.maintenance.updateAll({ true }, { true })
            assertEquals(setOf("script:a"), result.failed)
            assertTrue("Successful peer must not inherit the other script's global error", "script:b" in result.updated)
            assertFalse("script:a" in result.updated)
            val saved = fixture.scripts.snapshot().scripts.associate { it.id to it.content }
            assertEquals("old-a", saved["a"])
            assertEquals("downloaded:/script-b", saved["b"])
            assertEquals(1, fixture.changed.get())
        }
    }

    @Test fun automaticTriggerKeepsStandardSerialOrderAndStoppedCoreGate() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path!!
                    val body = if (request.path == "/icons") """{"name":"Icons","icons":[{"name":"New","url":"https://example.com/new.png"}]}"""
                        else "downloaded:${request.path}"
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val fixture = fixture(server, automatic = true)
            val before = fixture.builder.snapshot()
            fixture.builder.replace(before, before.resources.copy(iconSets = listOf(BuilderIconSet("Icons", server.url("/icons").toString(),
                listOf(BuilderIcon("Old", "https://example.com/old.png")), 1))))
            val result = fixture.maintenance.updateIfDue({ true }, { false })
            assertEquals(listOf("/icons", "/script-a", "/script-b"), paths.toList())
            assertEquals(3, result.updated.size)
            assertTrue(result.failed.isEmpty())
            assertEquals("New", fixture.builder.snapshot().resources.iconSets.single().icons.single().name)
            assertEquals(1, fixture.changed.get())
        }
    }
}
