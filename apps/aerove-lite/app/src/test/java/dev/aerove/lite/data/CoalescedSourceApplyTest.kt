package dev.aerove.lite.data

import dev.aerove.lite.integration.CoalescedSourceApply
import dev.aerove.lite.resources.ResourceApplyReceipt
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CoalescedSourceApplyTest {
    @Test fun libraryAndScriptChangesShareOneApplyAndAwaitItsActualReceipt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val owner = CoalescedSourceApply(scope, { true }, { current ->
            calls.incrementAndGet(); entered.complete(Unit); release.await(); check(current()); ResourceApplyReceipt("actual-runtime")
        }, { throw AssertionError(it) })
        try {
            owner.request(); owner.request()
            val receipt = async { owner.requestAndAwait() }
            val second = async { owner.requestAndAwait() }
            withTimeout(3000) { entered.await() }
            assertFalse(receipt.isCompleted); assertFalse(second.isCompleted); assertEquals(1, calls.get())
            release.complete(Unit)
            assertEquals("actual-runtime", withTimeout(3000) { receipt.await() }.appliedGeneration)
            assertEquals("actual-runtime", withTimeout(3000) { second.await() }.appliedGeneration)
        } finally { release.complete(Unit); owner.quiesce(); owner.close(); scope.cancel() }
    }
    @Test fun maintenanceJoinsAlreadyAcceptedUncancellableApplyAndCannotPublishItsReceipt() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = CoalescedSourceApply(scope, { true }, {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); ResourceApplyReceipt("must-not-publish") }
        }, { throw AssertionError(it) })
        try {
            val receipt = async { runCatching { owner.requestAndAwait() } }
            withTimeout(3000) { entered.await() }
            val barrier = async { owner.quiesce() }; yield(); assertFalse(barrier.isCompleted)
            release.complete(Unit); withTimeout(3000) { barrier.await() }
            assertTrue(withTimeout(3000) { receipt.await() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { owner.requestAndAwait() }.exceptionOrNull() is CancellationException)
        } finally { release.complete(Unit); owner.quiesce(); owner.close(); scope.cancel() }
    }
    @Test fun stopInvalidatesReceiptButMaintenanceStillJoinsTheRetiredApplyTail() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val owner = CoalescedSourceApply(scope, { true }, {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); ResourceApplyReceipt("stale") }
        }, { throw AssertionError(it) })
        try {
            val receipt = async { runCatching { owner.requestAndAwait() } }
            withTimeout(3000) { entered.await() }; owner.cancelPending()
            assertTrue(withTimeout(3000) { receipt.await() }.exceptionOrNull() is CancellationException)
            val barrier = async { owner.quiesce() }; yield(); assertFalse(barrier.isCompleted)
            release.complete(Unit); withTimeout(3000) { barrier.await() }
        } finally { release.complete(Unit); owner.quiesce(); owner.close(); scope.cancel() }
    }

}
