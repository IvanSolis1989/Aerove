package dev.aerove.lite.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class NativeResourceCallTest {
    @Test fun successDoesNotCancelOrChangeResult() = runBlocking {
        val cancels=AtomicInteger()
        assertEquals(37,nativeResourceCall({cancels.incrementAndGet()}) {37})
        assertEquals(0,cancels.get())
    }

    @Test fun activeNativeErrorKeepsIdentityAndSuppressedDiagnostic() = runBlocking {
        val cancels=AtomicInteger()
        val original=RuntimeException("Rust error: resourceRequestOwnerEnded")
        val diagnostic=RuntimeException("resourceRequestProducerExit stage=resourceProducerJoin outcome=cancelled panicked=false cancelled=true")
        original.addSuppressed(diagnostic)
        val caught=try {nativeResourceCall({cancels.incrementAndGet()}) {throw original};null} catch(error:Throwable) {error}
        assertSame(original,caught)
        assertSame(diagnostic,caught!!.suppressed.single())
        assertEquals(0,cancels.get())
    }

    @Test fun intentionalCancelKeepsCancellationAfterBlockedNativeReturnsItsCancellationError() = cancellation(false,false)
    @Test fun nativeCancelFailureIsSuppressedWithoutReplacingCancellation() = cancellation(true,false)
    @Test fun cancelledCallerWaitsForNativeExitEvenIfNativeReturnsSuccess() = cancellation(false,true)

    private fun cancellation(cancelFails:Boolean,nativeSucceeds:Boolean) = runBlocking {
        supervisorScope {
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val exits=AtomicInteger();val cancels=AtomicInteger()
            val original=CancellationException("controlled request cancellation")
            val cleanup=IllegalStateException("controlled nativeCancel error")
            val nativeFailure=RuntimeException("Rust error: resourceRequestOwnerEnded").apply {
                addSuppressed(RuntimeException("resourceRequestProducerExit stage=resourceProducerJoin outcome=cancelled panicked=false cancelled=true"))
            }
            val request=async {
                nativeResourceCall({
                    cancels.incrementAndGet();release.countDown()
                    if(cancelFails)throw cleanup
                }) {
                    entered.countDown()
                    try {
                        check(release.await(5,TimeUnit.SECONDS)) {"native worker was not released"}
                        if(nativeSucceeds)37 else throw nativeFailure
                    } finally {exits.incrementAndGet()}
                }
            }
            try {
                withContext(Dispatchers.IO) {check(entered.await(5,TimeUnit.SECONDS)) {"native worker did not enter"}}
                request.cancel(original)
                val caught=try {request.await();null} catch(error:Throwable) {error}
                assertTrue("cancellation was replaced by $caught",caught is CancellationException)
                assertSame(original,caught)
                assertEquals(1,cancels.get())
                assertEquals("cancel must join the real blocking worker before returning",1,exits.get())
                assertTrue(request.isCompleted)
                if(cancelFails)assertTrue(caught!!.suppressed.any {it===cleanup})
            } finally {release.countDown();withContext(NonCancellable) {request.join()}}
        }
    }
}
