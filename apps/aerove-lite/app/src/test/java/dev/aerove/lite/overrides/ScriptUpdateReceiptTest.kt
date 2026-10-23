package dev.aerove.lite.overrides

import dev.aerove.lite.data.SubscriptionDownloader
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ScriptUpdateReceiptTest {
    @Test fun parallel_success_and_failure_return_independent_receipts() = runBlocking {
        MockWebServer().use { server ->
            val both = CountDownLatch(2)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    both.countDown()
                    if (!both.await(3, TimeUnit.SECONDS)) return MockResponse().setResponseCode(598)
                    return if (request.path!!.startsWith("/ok")) MockResponse().setBody("updated-ok")
                    else MockResponse().setResponseCode(500).setBody("private-response-do-not-return")
                }
            }
            val repo = OverrideRepository(Files.createTempDirectory("script-parallel").toFile())
            repo.putScript(ScriptResource("a", "A", server.url("/ok?token=private").toString(), "old-a"))
            repo.putScript(ScriptResource("b", "B", server.url("/bad?token=private").toString(), "old-b"))
            val applied = AtomicInteger()
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) { applied.incrementAndGet() }
            val receipts = coroutineScope {
                val good = async(Dispatchers.Default) { session.updateWithReceipt("a") }
                val bad = async(Dispatchers.Default) { session.updateWithReceipt("b") }
                mapOf("a" to good.await(), "b" to bad.await())
            }
            assertEquals(ScriptUpdateReceipt(true), receipts["a"])
            assertEquals(ScriptUpdateReceipt(false, "update_failed"), receipts["b"])
            assertEquals(1, applied.get())
            assertEquals(listOf("updated-ok", "old-b"), repo.snapshot().scripts.map { it.content })
            assertFalse(receipts.toString().contains("private"))
            assertTrue(session.state.value.busyIds.isEmpty())
        }
    }

    @Test fun same_id_coalesces_but_stale_url_response_is_an_explicit_conflict() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-stale-url").toFile())
            val original = ScriptResource("s", "Original", server.url("/old").toString(), "original")
            repo.putScript(original)
            val applied = AtomicInteger()
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) { applied.incrementAndGet() }
            server.enqueue(MockResponse().setBody("stale-download").setBodyDelay(250, TimeUnit.MILLISECONDS))
            val late = async(Dispatchers.Default) { session.updateWithReceipt("s") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(ScriptUpdateReceipt(false), session.updateWithReceipt("s"))
            val newer = original.copy(url = server.url("/new").toString(), content = "new-intent")
            repo.putScript(newer)
            assertEquals(ScriptUpdateReceipt(false, "edit_conflict"), late.await())
            assertEquals(newer, repo.snapshot().scripts.single()); assertEquals(0, applied.get())
            server.enqueue(MockResponse().setBody("new-download"))
            assertEquals(ScriptUpdateReceipt(true), session.updateWithReceipt("s"))
            assertEquals("/new", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertEquals("new-download", repo.snapshot().scripts.single().content); assertEquals(1, applied.get())
        }
    }

    @Test fun apply_failure_retains_commit_and_cancel_propagates_without_commit() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-apply-cancel").toFile())
            repo.putScript(ScriptResource("s", "Script", server.url("/script").toString(), "old"))
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) { throw IllegalStateException("private-apply-details") }
            server.enqueue(MockResponse().setBody("saved"))
            assertEquals(ScriptUpdateReceipt(true, "apply_failed"), session.updateWithReceipt("s"))
            assertEquals("saved", repo.snapshot().scripts.single().content)
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(MockResponse().setBody("must-not-save").setBodyDelay(1, TimeUnit.SECONDS))
            val pending = async(Dispatchers.Default) { session.updateWithReceipt("s") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); session.cancel("s")
            try { pending.await(); fail() } catch (_: CancellationException) { }
            session.quiesce()
            assertEquals("saved", repo.snapshot().scripts.single().content)
            assertTrue(session.state.value.busyIds.isEmpty())
        }
    }

    @Test fun maintenance_joins_noncancellable_apply_and_rejects_mutations_until_resume() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("script-maintenance").toFile())
            val original = ScriptResource("s", "Script", server.url("/script").toString(), "old")
            repo.putScript(original)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val callbacks = AtomicInteger(); val tails = AtomicInteger()
            val session = ScriptLibrarySession(repo, SubscriptionDownloader()) {
                if (callbacks.incrementAndGet() == 1) withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); tails.incrementAndGet()
                }
            }
            server.enqueue(MockResponse().setBody("saved-before-maintenance"))
            val pending = async(Dispatchers.Default) { session.updateWithReceipt("s") }
            try {
                withTimeout(3000) { entered.await() }
                // Independent user work remains possible while the old callback is cleaning up.
                withTimeout(3000) { session.put(ScriptResource("other", "Other", content = "local")) }
                val barrier = async(start = CoroutineStart.UNDISPATCHED) { session.quiesce() }
                assertFalse(barrier.isCompleted); assertEquals(0, tails.get())
                suspend fun rejected(action: suspend () -> Unit) {
                    try { action(); fail() } catch (failure: IllegalStateException) { assertEquals("script_maintenance", failure.message) }
                }
                rejected { session.put(original) }; rejected { session.import("blocked", "body") }
                rejected { session.delete("s") }; rejected { session.move("s", 0) }; rejected { session.updateWithReceipt("s") }
                release.complete(Unit); withTimeout(3000) { barrier.await() }
                try { pending.await(); fail() } catch (_: CancellationException) { }
                assertEquals(1, tails.get())
                repo.putScript(original.copy(content = "rollback"))
                session.resumeAfterMaintenance(); session.put(ScriptResource("resumed", "Resumed", content = "local"))
                session.closeAndJoin(); session.resumeAfterMaintenance()
                try { session.move("s", 0); fail() } catch (failure: IllegalStateException) { assertEquals("script_closed", failure.message) }
                assertEquals("rollback", repo.snapshot().scripts.single { it.id == "s" }.content)
            } finally { release.complete(Unit); pending.cancelAndJoin() }
        }
    }
}
