package dev.aerove.lite.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PixelCopyOwnershipTest {
    @Test fun completedSuccessCanReleaseExactlyOnce() {
        var releases = 0
        val owner = PixelCopyOwnership { releases++ }
        owner.submit(); owner.complete(0)
        assertFalse(owner.snapshot().inFlight)
        assertFalse(owner.snapshot().released)
        owner.close(); owner.close(); owner.complete(0)
        assertEquals(1, releases)
        assertTrue(owner.snapshot().released)
    }

    @Test fun completedErrorIsDifferentFromAnOutstandingRequest() {
        var releases = 0
        val owner = PixelCopyOwnership { releases++ }
        owner.submit(); owner.complete(3); owner.close()
        assertEquals(3, owner.snapshot().callbackResult)
        assertFalse(owner.snapshot().inFlight)
        assertTrue(owner.snapshot().released)
        assertEquals(1, releases)
    }

    @Test fun timeoutCannotRecyclePendingDestinationOrStartAnotherCase() {
        var releases = 0
        val owner = PixelCopyOwnership { releases++ }
        owner.submit()
        val timeout = AssertionError("PixelCopy did not return")
        val frozen = owner.snapshot()
        owner.close(); owner.close()
        assertTrue(frozen.inFlight)
        assertEquals(0, releases)
        assertFalse(owner.snapshot().released)
        val terminal = PixelCopyOwnershipFailure(timeout, frozen)
        try { rethrowMatrixCancellation(terminal); fail("Pending copy must abort the collector") }
        catch (failure: Throwable) { assertSame(terminal, failure); assertSame(timeout, failure.cause) }
        owner.complete(0)
        assertEquals(1, releases)
        assertTrue(owner.snapshot().released)
        assertTrue(terminal.ownership.inFlight)
    }

    @Test fun lateErrorCallbackAlsoReleasesOnlyAfterCompletion() {
        var releases = 0
        val owner = PixelCopyOwnership { releases++ }
        owner.submit(); owner.close()
        assertEquals(0, releases)
        owner.complete(2)
        assertEquals(1, releases)
        assertFalse(owner.snapshot().inFlight)
    }

    @Test fun anUnsubmittedDestinationCanCloseImmediately() {
        var releases = 0
        val owner = PixelCopyOwnership { releases++ }
        owner.close()
        assertEquals(1, releases)
        assertFalse(owner.snapshot().inFlight)
    }

    @Test fun callbackRacingCloseReleasesOnceAndJoinsBothRealThreads() {
        val releases = AtomicInteger()
        val owner = PixelCopyOwnership { releases.incrementAndGet() }
        owner.submit()
        val start = CountDownLatch(1)
        val complete = Thread { check(start.await(1, TimeUnit.SECONDS)); owner.complete(0) }
        val close = Thread { check(start.await(1, TimeUnit.SECONDS)); owner.close() }
        complete.start(); close.start(); start.countDown()
        complete.join(1_000); close.join(1_000)
        assertFalse(complete.isAlive); assertFalse(close.isAlive)
        assertEquals(1, releases.get())
        assertTrue(owner.snapshot().released)
    }

    @Test fun originalHardwareDeadlineSurvivesPendingPixelCopyAbort() {
        val owner = PixelCopyOwnership {}
        owner.submit()
        val original = AssertionError("No hardware frame was committed")
        val terminal = PixelCopyOwnershipFailure(AssertionError("PixelCopy did not return"), owner.snapshot())
        try { rethrowMatrixCancellation(terminal, original); fail("Unconfirmed copy must stop") }
        catch (failure: Throwable) { assertSame(terminal, failure); assertSame(original, failure.suppressed.single()) }
        owner.close()
        assertFalse(owner.snapshot().released)
        owner.complete(0)
        assertTrue(owner.snapshot().released)
    }
}
