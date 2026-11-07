package dev.aerove.lite.usage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aerove.usage.LocalUsageLedgerAuthority
import dev.aerove.usage.LocalUsageLedgerFile
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real native owner/file instances. Lifecycle signals below are controlled, not a real Activity proof. */
@RunWith(AndroidJUnit4::class)
class LocalUsageLedgerStoreTest {
    private fun exercise(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.noBackupFilesDir.canonicalFile, "ledger-test-${UUID.randomUUID()}")
        try { block(directory) } finally { directory.deleteRecursively() }
    }
    private fun request(operation: String, vararg fields: Pair<String, Any?>): Map<String, Any?> =
        mapOf("operation" to operation, "activationVersion" to 2) + fields
    private fun claim(file: LocalUsageLedgerFile, engine: LocalUsageLedgerAuthority.Engine) =
        file.handle(request("claim"), engine.capture())
    private fun write(file: LocalUsageLedgerFile, engine: LocalUsageLedgerAuthority.Engine,
        view: Map<String, Any?>, payload: String) = file.handle(request("write",
            "lease" to view["lease"], "expectedRevision" to view["revision"], "payload" to payload), engine.capture())
    private fun read(file: LocalUsageLedgerFile, engine: LocalUsageLedgerAuthority.Engine, lease: Any?) =
        file.handle(request("read", "lease" to lease), engine.capture())

    @Test fun claim_ack_loss_retry_is_idempotent_and_cannot_retire_the_new_foreground_writer() = exercise { directory ->
        val authority = LocalUsageLedgerAuthority(); val file = LocalUsageLedgerFile(directory, authority)
        val a = authority.attachEngine(); a.resumed()
        val grantedA = claim(file, a) // Deliberately discard its ACK at the caller boundary.
        assertEquals(grantedA["lease"], claim(file, a)["lease"])
        val b = authority.attachEngine() // A prewarmed engine has no Activity activation.
        assertNull(b.capture())
        assertEquals("retired", claim(file, b)["status"])
        assertEquals(grantedA["lease"], claim(file, a)["lease"])
        b.resumed(); val grantedB = claim(file, b)
        val committed = write(file, b, grantedB, "{\"recorded\":7}")
        assertEquals("committed", committed["status"])
        assertEquals("retired", claim(file, a)["status"])
        assertEquals("{\"recorded\":7}", read(file, b, grantedB["lease"])["payload"])
        assertEquals(grantedB["lease"], claim(file, b)["lease"])
        assertEquals("conflict", write(file, b, grantedB, "{\"recorded\":99}")["status"])
        val nextAuthority = LocalUsageLedgerAuthority(); val nextEngine = nextAuthority.attachEngine(); nextEngine.resumed()
        val reopened = claim(LocalUsageLedgerFile(directory, nextAuthority), nextEngine)
        assertEquals(committed["revision"], reopened["revision"])
        assertEquals(committed["payload"], reopened["payload"])
    }

    @Test fun an_old_queued_claim_released_after_b_commits_cannot_steal_b_lease() = exercise { directory ->
        val authority = LocalUsageLedgerAuthority(); val file = LocalUsageLedgerFile(directory, authority)
        val a = authority.attachEngine(); a.resumed(); val capturedA = a.capture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Map<String, Any?>> {
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                file.handle(request("claim"), capturedA)
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            val b = authority.attachEngine(); b.resumed(); val grantedB = claim(file, b)
            val committedB = write(file, b, grantedB, "{\"recorded\":8}")
            release.countDown()
            assertEquals("retired", pending.get(30, TimeUnit.SECONDS)["status"])
            assertEquals(grantedB["lease"], claim(file, b)["lease"])
            assertEquals(committedB["payload"], read(file, b, grantedB["lease"])["payload"])
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun detach_reattach_aba_rejects_the_old_call_capture_and_real_focus_return_gets_a_new_activation() = exercise { directory ->
        val authority = LocalUsageLedgerAuthority(); val file = LocalUsageLedgerFile(directory, authority)
        val original = authority.attachEngine(); original.resumed(); val oldCapture = original.capture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queued = executor.submit<Map<String, Any?>> {
                entered.countDown(); check(release.await(30, TimeUnit.SECONDS))
                file.handle(request("claim"), oldCapture)
            }
            assertTrue(entered.await(30, TimeUnit.SECONDS))
            original.detach()
            val replacement = authority.attachEngine(); replacement.resumed()
            val granted = claim(file, replacement)
            release.countDown()
            assertEquals("retired", queued.get(30, TimeUnit.SECONDS)["status"])
            original.resumed() // A closed engine registration never revives.
            assertEquals(granted["lease"], claim(file, replacement)["lease"])
            val other = authority.attachEngine(); other.resumed(); val otherView = claim(file, other)
            assertEquals("retired", claim(file, replacement)["status"])
            replacement.resumed() // Controlled actual foreground return, not a Dart claim retry.
            val returned = claim(file, replacement)
            assertNotEquals(granted["activation"], returned["activation"])
            assertEquals("retired", read(file, other, otherView["lease"])["status"])
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun interrupted_new_file_and_corrupt_confirmed_data_never_become_a_zero_ledger() = exercise { directory ->
        val authority = LocalUsageLedgerAuthority(); val file = LocalUsageLedgerFile(directory, authority)
        val engine = authority.attachEngine(); engine.resumed()
        write(file, engine, claim(file, engine), "{\"recorded\":7}")
        File(directory, "record.json.new").writeText("incomplete candidate")
        assertEquals("{\"recorded\":7}", claim(file, engine)["payload"])
        val base = File(directory, "record.json"); base.writeText("corrupt confirmed file")
        try { claim(file, engine); fail("Corrupt data became empty") }
        catch (_: Exception) { assertEquals("corrupt confirmed file", base.readText()) }
    }
}
