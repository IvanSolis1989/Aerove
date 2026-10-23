package dev.aerove.lite.data

import dev.aerove.lite.integration.OverrideWorkspace
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.OverrideSnapshot
import dev.aerove.lite.overrides.ProfileOverride
import dev.aerove.lite.overrides.ScriptResource
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class OverrideWorkspaceIntegrationTest {
    @Test fun orderedProfileWritesReadBackAndApplyOnceWhenLeavingEditor() = runBlocking<Unit> {
        val root = Files.createTempDirectory("override-workspace").toFile()
        val repository = OverrideRepository(root)
        val applied = CompletableDeferred<Pair<OverrideSnapshot, OverrideSnapshot>>()
        var calls = 0
        val workspace = OverrideWorkspace(repository, SubscriptionDownloader(), this) { before, after ->
            calls++; applied.complete(before to after)
        }
        try {
            workspace.setEditorActive(true)
            val one = ProfileOverride("profile", addedRules = listOf("DOMAIN,one.test,DIRECT"))
            val two = one.copy(addedRules = one.addedRules + "DOMAIN,two.test,DIRECT")
            workspace.putProfile(one)!!.join(); workspace.putProfile(two)!!.join()
            assertEquals(two, repository.snapshot().profiles.single())
            assertEquals(two, workspace.state.value.snapshot!!.profiles.single())
            assertFalse(applied.isCompleted)
            workspace.setEditorActive(false)
            val change = withTimeout(3000) { applied.await() }
            assertTrue(change.first.profiles.isEmpty())
            assertEquals(two, change.second.profiles.single())
            assertEquals(1, calls)
        } finally { workspace.close(); root.deleteRecursively() }
    }

    @Test fun failedRemotePropertiesKeepStoredScriptAndExposeFailureForRetry() = runBlocking<Unit> {
        val root = Files.createTempDirectory("script-properties").toFile()
        val repository = OverrideRepository(root)
        val previous = ScriptResource("script", "Saved", content = "function main(config) { return config; }")
        repository.putScript(previous)
        MockWebServer().use { server ->
            val workspace = OverrideWorkspace(repository, SubscriptionDownloader(), this) { _, _ -> }
            try {
                workspace.refresh()!!.join()
                server.enqueue(MockResponse().setResponseCode(403))
                workspace.putScript(previous.copy(name = "Draft", url = server.url("/script").toString()))!!.join()
                assertTrue(workspace.state.value.failed)
                assertEquals(previous, repository.snapshot().scripts.single())
                assertTrue(workspace.state.value.busy.isEmpty())
            } finally { workspace.close() }
        }
        root.deleteRecursively()
    }

    @Test fun maintenanceQuiesceCancelsActualDownloadBeforeLibraryCommit() = runBlocking<Unit> {
        val root = Files.createTempDirectory("script-maintenance").toFile()
        val repository = OverrideRepository(root)
        MockWebServer().use { server ->
            val workspace = OverrideWorkspace(repository, SubscriptionDownloader(), this) { _, _ -> }
            try {
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val job = workspace.putScript(ScriptResource("script", "Pending", server.url("/pending").toString(), ""))!!
                // The caller stays responsive; the actual HTTP socket is owned by the tracked job.
                kotlinx.coroutines.yield()
                assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                workspace.quiesce()
                assertTrue(job.isCancelled)
                assertTrue(repository.snapshot().scripts.isEmpty())
                assertTrue(workspace.state.value.busy.isEmpty())
            } finally { workspace.close() }
        }
        root.deleteRecursively()
    }
}
