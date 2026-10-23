package dev.aerove.lite.resources

import dev.aerove.lite.data.*
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.ScriptLibrarySession
import dev.aerove.lite.overrides.ScriptResource
import dev.aerove.lite.profilebuilder.BuilderFilter
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResources
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementBarrierTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun profiles(root: File): ProfileRepository {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        return ProfileRepository(root, validator, resources = ResourceBundleManager(File(root, "runtime"), validator,
            NativeResourceValidator { _, _, _, _ -> null }))
    }

    @Test fun olderCancelledApplyMustJoinBeforePrimaryLibraryResetAndRetryRemainsConcurrent() = runBlocking<Unit> {
        val builder = BuilderResourceLibrary(temporary.root)
        val repository = profiles(temporary.root)
        val id = repository.importText("Fixture", "proxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n").id
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val session = ResourceManagementSession(ResourceManagementRepository(repository), scope, {
            entered.complete(Unit)
            withContext(NonCancellable) {
                release.await()
                builder.replace(builder.snapshot(), BuilderResources(filters = listOf(BuilderFilter("late-apply", ".*"))))
            }
        })
        try {
            session.load(id); withTimeout(5000) { session.state.first { it.snapshot != null && it.busy.isEmpty() } }
            session.refresh(); withTimeout(5000) { entered.await() }
            session.cancel()
            session.load(id)
            withTimeout(5000) { session.state.first { it.snapshot != null && it.busy.isEmpty() } }
            assertFalse(release.isCompleted)
            val paused = async { session.quiesce() }
            yield()
            val waitedForOldApply = !paused.isCompleted
            if (!waitedForOldApply) builder.replace(builder.snapshot(), BuilderResources())
            release.complete(Unit); withTimeout(5000) { paused.await() }
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            if (waitedForOldApply) builder.replace(builder.snapshot(), BuilderResources())
            val saved = builder.snapshot()
            assertTrue("Barrier returned before the old cancelled apply wrote ${saved.resources.filters.map { it.label }}",
                waitedForOldApply && saved.resources.filters.isEmpty())
            val before = session.state.value
            session.load("must-not-start")
            var inputClosed = false
            val input = object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { inputClosed = true; super.close() } }
            session.sideload("must-not-start", input)
            assertTrue(inputClosed); assertEquals(before, session.state.value)
            session.resumeAfterMaintenance()
            session.load(id); withTimeout(5000) { session.state.first { it.snapshot != null && it.busy.isEmpty() } }
            assertEquals(id, session.state.value.profileId)
        } finally { release.complete(Unit); session.close(); scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun globalMaintenanceJoinsLateBuilderAndScriptWritesAndBlocksNewOperations() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val native = NativeResourceValidator { _, _, _, _ -> null }
            val settings = GeoResourcePreferences(urls = GeoResourceKind.entries.associateWith { server.url("/${it.wire}").toString() })
            val builder = BuilderResourceLibrary(temporary.root)
            val scripts = OverrideRepository(temporary.root)
            val catalog = GeoResourceCatalog(runtime, validator, native, ResourceDownloader(), { settings })
            val library = ResourceManagementLibraryRepository(builder, ResourceBundleManager(runtime, validator, native))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val maintenance = ResourceManagementMaintenance(catalog, library, ScriptLibrarySession(scripts, SubscriptionDownloader()), { settings }, {
                entered.complete(Unit)
                withContext(NonCancellable) {
                    release.await()
                    builder.replace(builder.snapshot(), BuilderResources(filters = listOf(BuilderFilter("late-maintenance", ".*"))))
                    scripts.putScript(ScriptResource("late", "Late", null, "function main(config) { return config; }", 1L))
                }
            })
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                repeat(3) { server.enqueue(MockResponse().setBody("fixture-geo-$it")) }
                val update = scope.async { maintenance.updateAll({ true }, { true }) }
                withTimeout(5000) { entered.await() }
                val paused = async { maintenance.quiesce() }
                yield(); assertFalse(paused.isCompleted)
                assertTrue(runCatching { maintenance.updateAll({ true }, { true }) }.exceptionOrNull() is CancellationException)
                assertEquals(3, server.requestCount)
                release.complete(Unit); withTimeout(5000) { paused.await(); update.join() }
                builder.replace(builder.snapshot(), BuilderResources())
                scripts.deleteScript("late")
                assertTrue(builder.snapshot().resources.filters.isEmpty())
                assertTrue(scripts.snapshot().scripts.isEmpty())
                maintenance.resumeAfterMaintenance()
                assertTrue(maintenance.updateIfDue({ true }, { true }).updated.isEmpty())
                assertEquals(3, server.requestCount)
            } finally { release.complete(Unit); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
}
