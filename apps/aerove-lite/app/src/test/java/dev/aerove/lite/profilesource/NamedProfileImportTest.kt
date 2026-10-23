package dev.aerove.lite.profilesource

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NamedProfileImportTest {
    @Test fun batch_markup_dedup_existing_invalid_line_and_first_share_order() = runBlocking {
        val plan = NamedImportPlanner().batch("- 'https://one.example/a',\nhttps://old.example/a https://one.example/a\nss://YWVzLTEyOC1nY206cHc@node.example:443#Node One\ninvalid://no\nhttps://two.example/a",
            setOf("https://old.example/a"))
        assertEquals(1, plan.skippedExisting)
        assertEquals(3, plan.items.size)
        assertEquals(3, plan.items.map { it.id }.toSet().size)
        assertTrue(plan.items[0].entry is dev.aerove.lite.data.ProfileLinkEntry.Subscription)
        assertTrue(plan.items[1].entry is dev.aerove.lite.data.ProfileLinkEntry.Inline)
        assertEquals(4, plan.issues.single().line)
        assertFalse(plan.toString().contains("https://"))
    }
    @Test fun single_name_and_private_identity_survive_planning() = runBlocking {
        AgeSecret("private-test-key".toByteArray()).use { secret ->
            val plan = NamedImportPlanner().single(NamedProfileInput(" My provider ", "https://sub.example/a", secret))
            assertEquals("My provider", plan.items.single().entry.name)
            assertSame(secret, plan.items.single().secret)
            assertFalse(plan.items.single().toString().contains("private-test"))
            assertTrue(NamedImportPlanner().single(NamedProfileInput("", "proxies: []", secret)).items.isEmpty())
        }
    }
    @Test fun bounded_concurrency_and_cancel_before_launch_close_private_identity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val active = java.util.concurrent.atomic.AtomicInteger()
            val peak = java.util.concurrent.atomic.AtomicInteger()
            val four = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val session = NamedBatchImportSession(scope) {
                val count = active.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf)
                if (count == 4) four.complete(Unit)
                try { release.await() } finally { active.decrementAndGet() }
            }
            session.start(NamedImportPlanner().batch((1..8).joinToString("\n") { "https://provider$it.example/sub" }))
            withTimeout(3000) { four.await() }
            assertEquals(4, peak.get()); release.complete(Unit)
            withTimeout(3000) { session.state.first { !it.running } }
            assertTrue(peak.get() <= 4)
        } finally { scope.cancel() }
        val stopped = CoroutineScope(SupervisorJob() + Dispatchers.Default); stopped.cancel()
        val secret = AgeSecret("private-test".toByteArray())
        val plan = NamedImportPlanner().single(NamedProfileInput("", "https://example.com/sub", secret))
        val session = NamedBatchImportSession(stopped) { fail("Cancelled scope imported an item") }
        session.start(plan)
        withTimeout(3000) { session.state.first { !it.running } }
        assertTrue(runCatching { secret.copyBytes() }.isFailure)
        assertEquals(ImportItemStatus.CANCELLED, session.state.value.items.single().status)
    }
    @Test fun partial_success_failure_cancellation_and_latest_intent_keep_committed_items() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val committed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val started = CompletableDeferred<Unit>()
            val plan = NamedImportPlanner().batch("https://ok.example/a\nhttps://fail.example/a\nhttps://wait.example/a")
            val session = NamedBatchImportSession(scope) { item ->
                when (item.entry.name) {
                    "ok.example" -> committed += item.id
                    "fail.example" -> throw IllegalStateException("private-body")
                    else -> { started.complete(Unit); delay(60_000) }
                }
            }
            session.start(plan)
            withTimeout(3000) { started.await(); session.state.first { it.items.any { it.status == ImportItemStatus.SUCCEEDED } && it.items.any { it.status == ImportItemStatus.FAILED } } }
            session.cancel()
            val ended = withTimeout(3000) { session.state.first { !it.running } }
            assertEquals(listOf(ImportItemStatus.SUCCEEDED, ImportItemStatus.FAILED, ImportItemStatus.CANCELLED), ended.items.map { it.status })
            assertEquals("import_failed", ended.items[1].errorCode)
            assertEquals(setOf(plan.items.first().id), committed)
            session.start(NamedImportPlanner().batch("https://ok.example/new"))
            val latest = withTimeout(3000) { session.state.first { !it.running && it.generation == 2L } }
            assertEquals(ImportItemStatus.SUCCEEDED, latest.items.single().status)
            assertEquals(2, committed.size)
        } finally { scope.cancel() }
    }
}
