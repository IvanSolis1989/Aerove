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

    @Test fun hiddenDemandIsRetainedAndResumeJoinsOldIoBeforeApplyingTheLatestIntent() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val owner=CoalescedSourceApply(scope,{true},{ current ->
            val call=calls.incrementAndGet()
            if(call==1) withContext(NonCancellable){entered.complete(Unit);release.await()}
            check(current());ResourceApplyReceipt("generation-$call")
        },{throw AssertionError(it)})
        try {
            val receipt=async{owner.requestAndAwait()};withTimeout(3000){entered.await()}
            owner.foreground(false);owner.request();delay(100);assertFalse(receipt.isCompleted);assertEquals(1,calls.get())
            owner.foreground(true);delay(100);assertEquals(1,calls.get())
            release.complete(Unit);assertEquals("generation-2",withTimeout(3000){receipt.await()}.appliedGeneration)
            assertEquals(2,calls.get());delay(650);assertEquals(2,calls.get())
        }finally{release.complete(Unit);owner.quiesce();owner.close();scope.cancel()}
    }
    @Test fun automaticFailureRetriesOnlyOnRealForegroundResumeAndClosedScopeRejectsAwaiters() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val calls=AtomicInteger();val failed=CompletableDeferred<Unit>()
        val owner=CoalescedSourceApply(scope,{true},{if(calls.incrementAndGet()==1)error("injected");ResourceApplyReceipt("latest")},{failed.complete(Unit)})
        try {
            owner.request();withTimeout(3000){failed.await()};delay(650);assertEquals(1,calls.get())
            owner.foreground(false);owner.foreground(true)
            withTimeout(3000){while(calls.get()!=2)delay(5)};delay(100);assertEquals(2,calls.get())
        }finally{owner.quiesce();owner.close();scope.cancel()}
        val dead=CoroutineScope(Job().apply{cancel()})
        val rejected=CoalescedSourceApply(dead,{true},{error("must not run")},{throw AssertionError(it)})
        try{assertTrue(runCatching{withTimeout(1000){rejected.requestAndAwait()}}.exceptionOrNull() is CancellationException)}finally{rejected.close()}
    }

}
