package dev.aerove.lite.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineResourceReadTest {
    private suspend fun await(latch: CountDownLatch) = withContext(Dispatchers.IO) {
        check(latch.await(5, TimeUnit.SECONDS)) { "Timed out waiting for actual worker" }
    }
    @Test fun successClosesTheExactOwnerBeforeTheCallerCanReleaseItsLease() = runBlocking {
        val calls = mutableListOf<String>()
        val result = ownedOfflineResourceRead({ calls += "create"; 41 },
            { error("Unexpected cancellation") }, { assertEquals(41L, it); calls += "join" },
            { assertEquals(41L, it); calls += "read"; "complete" })
        calls += "release"
        assertEquals("complete", result)
        assertEquals(listOf("create", "read", "join", "release"), calls)
    }
    @Test fun cancellationWaitsForRealWorkerExitAndJoinBeforeCallerCleanup() = runBlocking {
        val started = CountDownLatch(1); val cancelled = CountDownLatch(1); val allowExit = CountDownLatch(1)
        val exited = AtomicBoolean(); val joined = AtomicBoolean(); val released = AtomicBoolean()
        val running = launch {
            try {
                ownedOfflineResourceRead({ 42 }, { assertEquals(42L, it); cancelled.countDown() },
                    { assertTrue(exited.get()); joined.set(true) }, {
                        started.countDown()
                        check(allowExit.await(5, TimeUnit.SECONDS))
                        exited.set(true); "stale result"
                    })
                fail("Cancelled result must not publish")
            } finally { assertTrue(joined.get()); released.set(true) }
        }
        await(started); running.cancel(); await(cancelled)
        assertFalse(running.isCompleted); assertFalse(joined.get()); assertFalse(released.get())
        allowExit.countDown(); withTimeout(5000) { running.join() }
        assertTrue(exited.get()); assertTrue(joined.get()); assertTrue(released.get())
    }
    @Test fun cancellationDuringAllocationStillJoinsTheAllocatedOwnerWithoutReading() = runBlocking {
        val started = CountDownLatch(1); val allocate = CountDownLatch(1); val closed = AtomicInteger(); val reads = AtomicInteger()
        val running = launch {
            ownedOfflineResourceRead({ started.countDown(); check(allocate.await(5, TimeUnit.SECONDS)); 43 }, {},
                { assertEquals(43L, it); closed.incrementAndGet() }, { reads.incrementAndGet() })
        }
        await(started); running.cancel(); allocate.countDown()
        withTimeout(5000) { running.join() }
        assertEquals(1, closed.get()); assertEquals(0, reads.get())
    }
    @Test fun businessFailureKeepsCleanupFailureSuppressedAndDoesNotPublish() = runBlocking {
        val closed = AtomicInteger()
        val failure = runCatching { ownedOfflineResourceRead({ 44 }, {},
            { closed.incrementAndGet(); error("cleanup") }, { error("read failed") }) }.exceptionOrNull()!!
        assertEquals("read failed", failure.message)
        assertTrue(failure.suppressed.any { it.message == "cleanup" })
        assertEquals(1, closed.get())
    }
    @Test fun allocationFailureDoesNotCloseAnUnallocatedHandle() = runBlocking {
        val closed = AtomicInteger()
        val failure = runCatching { ownedOfflineResourceRead({ error("allocation failed") }, {},
            { closed.incrementAndGet() }, { fail("Must not read") }) }.exceptionOrNull()!!
        assertEquals("allocation failed", failure.message); assertEquals(0, closed.get())
    }
}
