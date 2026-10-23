package dev.aerove.lite.profilesource

import dev.aerove.lite.data.ProfileLinkEntry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class NamedImportBarrierTest {
    private fun plan(id: String, secret: AgeSecret? = null) = NamedImportPlan(listOf(PlannedProfileImport(id,
        ProfileLinkEntry.Subscription(id, "https://example.com/sub", emptyList()), secret)), emptyList(), 0)

    @Test fun superseded_noncancellable_commit_is_joined_without_blocking_normal_retry() = runBlocking {
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lateCommits = AtomicInteger(); val callbacks = AtomicInteger(); val finalizers = AtomicInteger()
        val secret = AgeSecret("synthetic-test-identity".toByteArray())
        val session = NamedBatchImportSession(scope) { item ->
            if (item.id == "old") {
                entered.complete(Unit)
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit); release.await()
                        lateCommits.incrementAndGet(); callbacks.incrementAndGet(); finalizers.incrementAndGet()
                    }
                }
            }
        }
        try {
            session.start(plan("old", secret)); withTimeout(3000) { entered.await() }
            session.start(plan("retry"))
            withTimeout(3000) { cleanup.await(); session.state.first { it.generation == 2L && !it.running } }
            assertEquals(ImportItemStatus.SUCCEEDED, session.state.value.items.single().status)
            assertEquals(0, lateCommits.get())
            val barrier = async(start = CoroutineStart.UNDISPATCHED) { session.quiesce() }
            assertFalse(barrier.isCompleted)
            val rejected = AgeSecret("caller-owned".toByteArray())
            try {
                try { session.start(plan("rejected", rejected)); fail() } catch (failure: IllegalStateException) { assertEquals("import_maintenance", failure.message) }
                rejected.copyBytes().also { assertTrue(it.isNotEmpty()); it.fill(0) }
            } finally { rejected.close() }
            secret.copyBytes().fill(0) // still needed by the delayed private commit
            release.complete(Unit); withTimeout(3000) { barrier.await() }
            assertEquals(1, lateCommits.get()); assertEquals(1, callbacks.get()); assertEquals(1, finalizers.get())
            assertTrue(runCatching { secret.copyBytes() }.isFailure)
            // Restoration starts only after the old commit/callback and secret cleanup have finished.
            lateCommits.set(0)
            session.resumeAfterMaintenance(); session.start(plan("after-rollback"))
            withTimeout(3000) { session.state.first { it.generation == 3L && !it.running } }
            session.quiesce(); assertEquals(0, lateCommits.get()); assertEquals(1, finalizers.get())
            session.resumeAfterMaintenance(); session.closeAndJoin(); session.resumeAfterMaintenance()
            try { session.start(plan("after-close")); fail() } catch (failure: IllegalStateException) { assertEquals("import_closed", failure.message) }
        } finally { release.complete(Unit); owner.cancelAndJoin(); secret.close() }
    }

    @Test fun maintenance_caller_cancellation_cannot_skip_wait_or_private_finalization() = runBlocking {
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val finalized = AtomicInteger(); val secret = AgeSecret("synthetic-test-identity".toByteArray())
        val session = NamedBatchImportSession(scope) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await(); finalized.incrementAndGet() } }
        }
        try {
            session.start(plan("pending", secret)); withTimeout(3000) { entered.await() }
            val barrier = launch(start = CoroutineStart.UNDISPATCHED) { session.quiesce() }
            withTimeout(3000) { cleanup.await() }; barrier.cancel()
            assertFalse(barrier.isCompleted)
            release.complete(Unit); withTimeout(3000) { barrier.join() }
            assertEquals(1, finalized.get()); assertTrue(runCatching { secret.copyBytes() }.isFailure)
            session.quiesce(); session.quiesce(); assertEquals(1, finalized.get())
            session.resumeAfterMaintenance()
        } finally { release.complete(Unit); owner.cancelAndJoin(); secret.close() }
    }

    @Test fun close_before_launch_and_completed_failure_close_accepted_secrets() = runBlocking {
        val stopped = CoroutineScope(SupervisorJob() + Dispatchers.Default); stopped.cancel()
        val secret = AgeSecret("test".toByteArray())
        val session = NamedBatchImportSession(stopped) { fail("Stopped owner executed an import") }
        session.start(plan("never-started", secret)); session.closeAndJoin()
        assertTrue(runCatching { secret.copyBytes() }.isFailure)
        assertFalse(session.state.value.running)
        val owner = SupervisorJob(); val scope = CoroutineScope(owner + Dispatchers.Default)
        val failedSecret = AgeSecret("test-failure".toByteArray())
        try {
            val failing = NamedBatchImportSession(scope) { throw AgeSourceException("wrong_key") }
            failing.start(plan("failure", failedSecret))
            withTimeout(3000) { failing.state.first { !it.running } }; failing.quiesce()
            assertEquals("wrong_key", failing.state.value.items.single().errorCode)
            assertTrue(runCatching { failedSecret.copyBytes() }.isFailure)
            failing.resumeAfterMaintenance(); failing.closeAndJoin()
        } finally { owner.cancelAndJoin(); failedSecret.close() }
    }
}
