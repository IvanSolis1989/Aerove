package dev.aerove.lite.overrides

import dev.aerove.lite.data.SubscriptionDownloader
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ScriptSaveReceiptTest {
    @Test fun failed_saves_have_distinct_receipts_and_retry_reads_back_actual_saved_metadata() = runBlocking {
        MockWebServer().use { server ->
            val repo = OverrideRepository(Files.createTempDirectory("save-receipt").toFile())
            val old = ScriptResource("s", "Original", content = "old")
            repo.putScript(old)
            val session = ScriptLibrarySession(repo, SubscriptionDownloader())
            val change = old.copy(name = "Changed", url = server.url("/script?secret=private").toString())
            repeat(2) {
                server.enqueue(MockResponse().setResponseCode(500))
                try { session.put(change); fail() } catch (_: java.io.IOException) { }
                assertEquals((it + 1).toLong(), session.state.value.saves["s"]!!.serial)
                assertEquals("save_failed", session.state.value.saves["s"]!!.errorCode)
                assertEquals(old, repo.snapshot().scripts.single())
            }
            server.enqueue(MockResponse().setBody("new")); session.put(change)
            assertEquals(3L, session.state.value.saves["s"]!!.serial)
            assertFalse(session.state.value.saves["s"]!!.busy)
            assertNull(session.state.value.saves["s"]!!.errorCode)
            assertEquals(change.name, session.state.value.scripts.single().name)
            assertEquals("new", session.state.value.scripts.single().content)
        }
    }
    @Test fun public_fingerprint_excludes_unrelated_metadata_but_detects_content_change() {
        val profile = ProfileOverride("p", mode = OverrideMode.Script, scriptId = "s")
        val snapshot = OverrideSnapshot(scripts = listOf(ScriptResource("s", "One", content = "one")), profiles = listOf(profile))
        val renamed = snapshot.copy(scripts = listOf(snapshot.scripts.single().copy(name = "Two", url = "https://example.com", updatedAt = 20)))
        assertEquals(overridePolicyFingerprint(snapshot, "p"), overridePolicyFingerprint(renamed, "p"))
        assertNotEquals(overridePolicyFingerprint(snapshot, "p"), overridePolicyFingerprint(renamed.copy(scripts = listOf(renamed.scripts.single().copy(content = "two"))), "p"))
    }
}
