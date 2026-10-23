package dev.aerove.lite.overrides

import dev.aerove.lite.data.SubscriptionDownloader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ScriptLibrarySessionTest {
    @Test fun remote_update_and_failure_preserve_valid_library() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-http").toFile())
            val script = ScriptResource("s", "Remote", server.url("/s?token=secret").toString(), "old")
            repo.putScript(script)
            var applied = 0
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) { applied++ }
            server.enqueue(MockResponse().setBody("function main(c){return {...c,mode:'global'}}"))
            session.update("s")
            assertTrue(repo.snapshot().scripts.single().content.contains("global"))
            assertEquals(1, applied)
            val saved = repo.snapshot()
            server.enqueue(MockResponse().setResponseCode(500).setBody("secret-server-body"))
            session.update("s")
            assertEquals(saved, repo.snapshot())
            assertEquals("update_failed", session.state.value.errorCode)
            assertTrue(session.state.value.busyIds.isEmpty())
        }
    }

    @Test fun cancellation_and_edit_race_do_not_commit_stale_download() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-cancel").toFile())
            val old = ScriptResource("s", "Remote", server.url("/script").toString(), "old")
            repo.putScript(old)
            val session = ScriptLibrarySession(repo, SubscriptionDownloader())
            server.enqueue(MockResponse().setBody("downloaded").setBodyDelay(1, TimeUnit.SECONDS))
            val updating = launch(Dispatchers.IO) { session.update("s") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            session.cancel("s")
            updating.join()
            assertEquals("old", repo.snapshot().scripts.single().content)
            assertTrue(session.state.value.busyIds.isEmpty())
            server.enqueue(MockResponse().setBody("stale").setBodyDelay(150, TimeUnit.MILLISECONDS))
            val stale = async(Dispatchers.IO) { session.update("s") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            repo.putScript(old.copy(content = "edited"))
            stale.await()
            assertEquals("edited", repo.snapshot().scripts.single().content)
        }
    }

    @Test fun apply_failure_is_distinguished_from_download_failure() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-apply").toFile())
            repo.putScript(ScriptResource("s", "Remote", server.url("/script").toString(), "old"))
            server.enqueue(MockResponse().setBody("new"))
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) { throw ScriptExecutionException("invalid_result") }
            session.update("s")
            assertEquals("new", repo.snapshot().scripts.single().content)
            assertEquals("apply_failed", session.state.value.errorCode)
            assertTrue(session.state.value.busyIds.isEmpty())
        }
    }

    @Test fun changing_remote_url_fetches_before_atomic_save_and_local_import_names_are_unique() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-url").toFile())
            val old = ScriptResource("s", "Remote", content = "local")
            repo.putScript(old)
            val session = ScriptLibrarySession(repo, SubscriptionDownloader())
            server.enqueue(MockResponse().setResponseCode(403))
            try { session.put(old.copy(url = server.url("/bad").toString())); fail() } catch (_: java.io.IOException) { }
            assertEquals(old, repo.snapshot().scripts.single())
            server.enqueue(MockResponse().setBody("fetched"))
            session.put(old.copy(url = server.url("/good").toString()))
            assertEquals("fetched", repo.snapshot().scripts.single().content)
            session.import("Remote", "imported")
            assertEquals(listOf("Remote", "Remote (2)"), repo.snapshot().scripts.map { it.name })
        }
    }
}
