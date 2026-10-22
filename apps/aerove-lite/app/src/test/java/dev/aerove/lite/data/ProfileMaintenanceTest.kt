package dev.aerove.lite.data

import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMaintenanceTest {
    private class Clock(var time: Long = 1_000_000_000L) {
        private data class Wait(val at: Long, val continuation: CancellableContinuation<Unit>)
        private val waits = mutableListOf<Wait>()
        val pending: Int get() = waits.count { it.continuation.isActive }

        suspend fun sleep(millis: Long) {
            if (millis <= 0) return
            suspendCancellableCoroutine { continuation ->
                val wait = Wait(time + millis, continuation)
                waits += wait
                continuation.invokeOnCancellation { waits.remove(wait) }
            }
        }

        fun advance(millis: Long) {
            time += millis
            while (true) {
                val ready = waits.filter { it.at <= time }
                if (ready.isEmpty()) return
                waits.removeAll(ready.toSet())
                ready.forEach { if (it.continuation.isActive) it.continuation.resume(Unit) }
            }
        }
    }

    private class Harness(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) : AutoCloseable {
        val clock = Clock()
        val owner = SupervisorJob()
        val calls = mutableListOf<ProfileMaintenanceRequest>()
        val errors = mutableListOf<Pair<String, Throwable>>()
        var handler: suspend (ProfileMaintenanceRequest) -> Long = { clock.time }
        val maintenance = ProfileMaintenance(
            CoroutineScope(owner + dispatcher),
            update = { calls += it; handler(it) },
            onAutomaticError = { id, error -> errors += id to error },
            now = { clock.time },
            waitMillis = clock::sleep,
            dispatcher = dispatcher,
        )

        override fun close() { maintenance.close(); owner.cancel() }
    }

    private fun policy(
        id: String = "one",
        source: String = "https://example.test/$id",
        subscription: Boolean = true,
        enabled: Boolean = true,
        interval: Long = 86_400_000L,
        last: Long? = null,
    ) = ProfileMaintenancePolicy(id, source, subscription, enabled, interval, last)

    @Test fun shortHidePreservesOriginalDeadlineAndLongHideChecksOnce() {
        Harness().use { h ->
            h.maintenance.replacePolicies(listOf(policy(interval = 1)))
            h.maintenance.setForeground(true)
            val deadline = h.clock.time + 20 * 60_000
            assertEquals(deadline, h.maintenance.snapshot().nextCheckAtMillis)
            assertEquals(1, h.clock.pending)
            assertTrue(h.calls.isEmpty())
            h.clock.advance(5 * 60_000)
            h.maintenance.setForeground(false)
            assertEquals(0, h.clock.pending)
            h.clock.advance(2000)
            h.maintenance.setForeground(true)
            h.maintenance.setForeground(true)
            assertEquals(deadline, h.maintenance.snapshot().nextCheckAtMillis)
            assertTrue(h.calls.isEmpty())
            h.clock.advance(deadline - h.clock.time - 1)
            assertTrue(h.calls.isEmpty())
            h.clock.advance(1)
            assertEquals(1, h.calls.size)
            h.maintenance.setForeground(false)
            h.clock.advance(40 * 60_000)
            assertEquals(1, h.calls.size)
            assertEquals(0, h.clock.pending)
            h.maintenance.setForeground(true)
            assertEquals(2, h.calls.size)
            h.maintenance.setForeground(false)
            h.maintenance.setForeground(true)
            assertEquals(2, h.calls.size)
            assertEquals(1, h.clock.pending)
        }
    }

    @Test fun InitiallyHiddenDefersSingleBootstrapCheckUntilVisible() {
        Harness().use { h ->
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(false)
            h.maintenance.checkDue()
            h.clock.advance(40 * 60_000)
            assertTrue(h.calls.isEmpty())
            h.maintenance.setForeground(true)
            assertEquals(1, h.calls.size)
            h.maintenance.setForeground(true)
            assertEquals(1, h.calls.size)
        }
    }

    @Test fun defaultDayAndStrictExpiryReuseSuccessfulReceiptDespiteStalePolicy() {
        Harness().use { h ->
            val stale = policy()
            h.maintenance.replacePolicies(listOf(stale, policy("off", enabled = false), policy("file", subscription = false)))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            assertEquals(listOf("one"), h.calls.map { it.profileId })
            h.maintenance.replacePolicies(listOf(stale))
            h.maintenance.checkDue()
            assertEquals(1, h.calls.size)
            h.clock.advance(86_400_000)
            assertEquals(1, h.calls.size)
            h.clock.advance(20 * 60_000)
            assertEquals(2, h.calls.size)
            assertEquals(h.clock.time, h.maintenance.snapshot().cachedUpdatedAtMillis["one"])
        }
    }

    @Test fun metadataChangesDoNotDownloadAndLatestPolicyWinsBeforeEachAutoItem() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { if (it.profileId == "one") release.await(); h.clock.time }
            h.maintenance.replacePolicies(listOf(policy(), policy("two")))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            assertEquals(listOf("one"), h.calls.map { it.profileId })
            h.maintenance.replacePolicies(listOf(policy(interval = 3 * 86_400_000L), policy("two", enabled = false)))
            release.complete(Unit)
            assertEquals(listOf("one"), h.calls.map { it.profileId })
            h.clock.advance(2 * 86_400_000L)
            assertEquals(1, h.calls.size)
        }
    }

    @Test fun sequentialAutoFailuresContinueAndRetryOnOriginalCadence() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            val failure = IllegalStateException("network failed")
            h.handler = { request ->
                if (request.profileId == "one") { release.await(); throw failure }
                h.clock.time
            }
            h.maintenance.replacePolicies(listOf(policy(), policy("two")))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            assertEquals(listOf("one"), h.calls.map { it.profileId })
            release.complete(Unit)
            assertEquals(listOf("one", "two"), h.calls.map { it.profileId })
            assertEquals(listOf("one" to failure), h.errors)
            assertFalse(h.maintenance.snapshot().cachedUpdatedAtMillis.containsKey("one"))
            h.clock.advance(20 * 60_000 - 1)
            assertEquals(2, h.calls.size)
            h.clock.advance(1)
            assertEquals(listOf("one", "two", "one"), h.calls.map { it.profileId })
        }
    }

    @Test fun invalidationIsForegroundOnlyAndKeepsTheOriginalPeriodicDeadline() {
        Harness().use { h ->
            h.maintenance.replacePolicies(listOf(policy(last = h.clock.time)))
            h.maintenance.setForeground(true)
            val deadline = h.maintenance.snapshot().nextCheckAtMillis
            h.clock.advance(60_000)
            h.maintenance.invalidate("one")
            assertEquals(1, h.calls.size)
            assertEquals(deadline, h.maintenance.snapshot().nextCheckAtMillis)
            h.maintenance.setForeground(false)
            h.maintenance.invalidate("one")
            h.maintenance.invalidate("one")
            h.clock.advance(60_000)
            assertEquals(1, h.calls.size)
            h.maintenance.setForeground(true)
            assertEquals(2, h.calls.size)
            assertEquals(deadline, h.maintenance.snapshot().nextCheckAtMillis)
            assertTrue(h.maintenance.snapshot().invalidatedProfileIds.isEmpty())
        }
    }

    @Test fun manualRefreshAndRepeatedInvalidationJoinAndPromoteAutomaticWork() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { release.await(); h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            val first = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            val second = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            repeat(5) { h.maintenance.invalidate("one") }
            h.maintenance.setForeground(false)
            assertTrue(h.calls.single().isCurrent())
            h.clock.advance(60_000)
            release.complete(Unit)
            assertEquals(ProfileMaintenanceResult.Updated(h.clock.time), first.await())
            assertEquals(first.await(), second.await())
            h.maintenance.setForeground(true)
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().runningProfileIds.isEmpty())
        }
    }

    @Test fun hiddenAutomaticWorkIsInvalidatedAndSuccessorWaitsForItsExit() {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            var active = 0
            var maximum = 0
            h.handler = {
                active++
                maximum = maxOf(active, maximum)
                try {
                    if (h.calls.size == 1) withContext(NonCancellable) { release.await() }
                    h.clock.time
                } finally { active-- }
            }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.maintenance.setForeground(false)
            assertFalse(h.calls.first().isCurrent())
            h.maintenance.setForeground(true)
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
            release.complete(Unit)
            assertEquals(2, h.calls.size)
            assertEquals(1, maximum)
            assertEquals(h.clock.time, h.maintenance.snapshot().cachedUpdatedAtMillis["one"])
            assertEquals(1, h.clock.pending)
        }
    }

    @Test fun sourceChangeRejectsOldCompletionAndRefreshesNewSourceOnce() {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { if (h.calls.size == 1) withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.maintenance.replacePolicies(listOf(policy(source = "new-source")))
            assertFalse(h.calls.first().isCurrent())
            assertEquals(1, h.calls.size)
            release.complete(Unit)
            assertEquals(listOf("https://example.test/one", "new-source"), h.calls.map { it.sourceIdentity })
            assertTrue(h.calls.last().isCurrent())
        }
    }

    @Test fun deletionPrunesCacheAndCannotResurrectLateResults() {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.maintenance.replacePolicies(emptyList())
            assertFalse(h.calls.single().isCurrent())
            release.complete(Unit)
            h.clock.advance(40 * 60_000)
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().runningProfileIds.isEmpty())
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
        }
    }

    @Test fun cancellingOneManualCallerDoesNotCancelSharedDownload() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { release.await(); h.clock.time }
            h.maintenance.replacePolicies(listOf(policy(enabled = false)))
            val first = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            val second = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            first.cancelAndJoin()
            assertTrue(h.calls.single().isCurrent())
            release.complete(Unit)
            assertEquals(ProfileMaintenanceResult.Updated(h.clock.time), second.await())
            assertEquals(1, h.calls.size)
            assertEquals(ProfileMaintenanceResult.Unavailable, h.maintenance.refresh("missing"))
            h.maintenance.replacePolicies(listOf(policy(subscription = false)))
            assertEquals(ProfileMaintenanceResult.Unavailable, h.maintenance.refresh("one"))
        }
    }

    @Test fun explicitCancelReleasesAllWaitersAndSerializesTheNextRequest() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { if (h.calls.size == 1) withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            val first = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            val second = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            h.maintenance.cancel("one")
            assertEquals(ProfileMaintenanceResult.Cancelled, first.await())
            assertEquals(ProfileMaintenanceResult.Cancelled, second.await())
            assertFalse(h.calls.first().isCurrent())
            val next = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
            release.complete(Unit)
            assertEquals(ProfileMaintenanceResult.Updated(h.clock.time), next.await())
            assertEquals(2, h.calls.size)
        }
    }

    @Test fun workerThreadCompletionIsSharedByAllCallersAndTheAutomaticScan() = runBlocking {
        val owner = SupervisorJob()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val maintenance = ProfileMaintenance(
            CoroutineScope(owner),
            update = { calls.incrementAndGet(); entered.complete(Unit); release.await(); 1_000_000_000L },
            now = { 1_000_000_000L },
            waitMillis = { completed.await() },
        )
        try {
            maintenance.replacePolicies(listOf(policy()))
            maintenance.setForeground(true)
            val callers = List(24) { async(Dispatchers.Unconfined) { maintenance.refresh("one") } }
            entered.await()
            maintenance.checkDue()
            release.complete(Unit)
            callers.forEach { assertEquals(ProfileMaintenanceResult.Updated(1_000_000_000L), it.await()) }
            assertEquals(1, calls.get())
            assertEquals(1_000_000_000L, maintenance.snapshot().cachedUpdatedAtMillis["one"])
        } finally { maintenance.close(); owner.cancel() }
    }

    @Test fun changingSourceImmediatelyReleasesOldManualWaiter() = runBlocking {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { if (h.calls.size == 1) withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            val old = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            h.maintenance.replacePolicies(listOf(policy(source = "replacement")))
            assertEquals(ProfileMaintenanceResult.Cancelled, old.await())
            assertFalse(h.calls.first().isCurrent())
            val next = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            assertEquals(1, h.calls.size)
            release.complete(Unit)
            assertEquals(ProfileMaintenanceResult.Updated(h.clock.time), next.await())
            assertEquals("replacement", h.calls.last().sourceIdentity)
        }
    }

    @Test fun closeRejectsLateCompletionAndLeavesOwnerScopeAlive() {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.maintenance.close()
            assertTrue(h.owner.isActive)
            assertFalse(h.calls.single().isCurrent())
            release.complete(Unit)
            h.clock.advance(86_400_000)
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().closed)
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
            assertEquals(0, h.clock.pending)
        }
    }

    @Test fun parentCancellationIsTerminalEvenBeforeUncooperativeWorkExits() {
        Harness().use { h ->
            val release = CompletableDeferred<Unit>()
            h.handler = { withContext(NonCancellable) { release.await() }; h.clock.time }
            h.maintenance.replacePolicies(listOf(policy()))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.owner.cancel()
            assertFalse(h.calls.single().isCurrent())
            assertTrue(h.maintenance.snapshot().closed)
            h.maintenance.setForeground(true)
            h.maintenance.invalidate("one")
            release.complete(Unit)
            h.clock.advance(86_400_000)
            assertEquals(1, h.calls.size)
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
            assertEquals(0, h.clock.pending)
        }
    }

    @Test fun cancellingBeforeDispatchDoesNotLeaveActiveFlights() = runBlocking {
        val dispatcher = object : CoroutineDispatcher() {
            val queued = ArrayDeque<Runnable>()
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
            fun drain() { while (queued.isNotEmpty()) queued.removeFirst().run() }
        }
        Harness(dispatcher).use { h ->
            h.maintenance.replacePolicies(listOf(policy()))
            val result = async(Dispatchers.Unconfined) { h.maintenance.refresh("one") }
            h.maintenance.close()
            dispatcher.drain()
            assertEquals(ProfileMaintenanceResult.Cancelled, result.await())
            assertTrue(h.calls.isEmpty())
            assertTrue(h.maintenance.snapshot().runningProfileIds.isEmpty())
        }
    }

    @Test fun invalidPoliciesAndCallbackTimestampsFailExplicitlyWithoutCaching() = runBlocking {
        Harness().use { h ->
            assertThrows(IllegalArgumentException::class.java) { policy(interval = 0) }
            assertThrows(IllegalArgumentException::class.java) { policy(last = -1) }
            assertThrows(IllegalArgumentException::class.java) { h.maintenance.replacePolicies(listOf(policy(), policy())) }
            h.maintenance.replacePolicies(listOf(policy()))
            h.handler = { -1 }
            assertTrue(h.maintenance.refresh("one") is ProfileMaintenanceResult.Failed)
            assertTrue(h.maintenance.snapshot().cachedUpdatedAtMillis.isEmpty())
            assertTrue(h.errors.isEmpty())
        }
    }

    @Test fun farFutureTimestampAndLargeIntervalCannotOverflowIntoAnImmediateFetch() {
        Harness().use { h ->
            h.maintenance.replacePolicies(listOf(policy(last = h.clock.time, interval = Long.MAX_VALUE)))
            h.maintenance.setForeground(true)
            h.maintenance.checkDue()
            h.clock.advance(86_400_000)
            assertTrue(h.calls.isEmpty())
        }
    }
}
