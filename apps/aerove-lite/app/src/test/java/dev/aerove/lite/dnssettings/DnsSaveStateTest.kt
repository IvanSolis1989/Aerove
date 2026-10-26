package dev.aerove.lite.dnssettings

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.io.IOException

class DnsSaveStateTest {
    private val original = DnsPreferences()
    private val draft = original.withValue(DnsField.Nameserver, DnsValue.Entries(listOf("1.1.1.1")))

    @Test fun fastFailureDoesNotNeedAnObservedBusyFrame() {
        val emitted = mutableListOf<DnsSaveReceipt>()
        val owner = DnsSaveAttempts(emitted::add)
        val token = owner.begin()
        val pending = PendingDnsSave(draft, token)
        owner.complete(token, failed = true)
        assertEquals(DnsSaveDecision.Failed, dnsSaveDecision(original, false, pending, emitted.single()))
    }

    @Test fun repeatedIdenticalFailureHasANewStableCompletion() {
        val emitted = mutableListOf<DnsSaveReceipt>(); val owner = DnsSaveAttempts(emitted::add)
        val first = owner.begin(); owner.complete(first, true)
        val second = owner.begin(); owner.complete(second, true)
        assertNotEquals(emitted[0], emitted[1]); assertTrue(second > first)
        val pending = PendingDnsSave(draft, second)
        assertEquals(DnsSaveDecision.Failed, dnsSaveDecision(original, false, pending, emitted.last()))
    }

    @Test fun olderCompletionCannotOverwriteTheNewRequestReceipt() {
        val emitted = mutableListOf<DnsSaveReceipt>(); val owner = DnsSaveAttempts(emitted::add)
        val old = owner.begin(); val current = owner.begin()
        owner.complete(old, true); assertTrue(emitted.isEmpty())
        owner.complete(current, false); owner.complete(old, true); owner.complete(current, true)
        assertEquals(listOf(DnsSaveReceipt(current, false)), emitted)
    }

    @Test fun stillPendingNeverClosesOrBecomesEditableOnASpuriousOldError() {
        val pending = PendingDnsSave(draft, 2)
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(original, false, pending, DnsSaveReceipt(1, true)))
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(original, true, pending, DnsSaveReceipt(2, true)))
    }

    @Test fun successfulReceiptAloneCannotInventSavedReadback() {
        val pending = PendingDnsSave(draft, 1); val receipt = DnsSaveReceipt(1, false)
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(original, false, pending, receipt))
        assertEquals(DnsSaveDecision.Saved, dnsSaveDecision(draft, false, pending, receipt))
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(draft, true, pending, receipt))
    }

    @Test fun matchingFailureDoesNotCloseEvenIfAnotherReadbackMatches() {
        assertEquals(DnsSaveDecision.Failed, dnsSaveDecision(draft, false, PendingDnsSave(draft, 3), DnsSaveReceipt(3, true)))
    }

    @Test fun noReceiptOrWrongTokenCannotInventCompletionEvenWithMatchingReadback() {
        val pending = PendingDnsSave(draft, 1)
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(draft, false, pending, null))
        assertEquals(DnsSaveDecision.Wait, dnsSaveDecision(draft, false, pending, DnsSaveReceipt(2, false)))
    }

    @Test fun hostsUseTheSameCompletionAndKeepTheSubmittedDraft() {
        val hosts = original.copy(hosts = mapOf("example.org" to listOf("192.0.2.1")))
        val pending = PendingDnsSave(hosts, 4)
        assertEquals(DnsSaveDecision.Failed, dnsSaveDecision(original, false, pending, DnsSaveReceipt(4, true)))
        assertEquals(hosts, pending.preferences)
    }

    @Test fun realSuspendWritePublishesNoSuccessBeforeItsCompletion() = runBlocking {
        val emitted = mutableListOf<DnsSaveReceipt>(); val owner = DnsSaveAttempts(emitted::add)
        val token = owner.begin(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var readback = original
        val job = launch { owner.persist(token) { entered.complete(Unit); release.await(); readback = draft } }
        entered.await(); assertTrue(emitted.isEmpty()); assertEquals(original, readback)
        release.complete(Unit); job.join()
        assertEquals(DnsSaveReceipt(token, false), emitted.single()); assertEquals(draft, readback)
    }

    @Test fun cancellationAndIoFailurePublishOnlyFailureAndRethrowTheOriginal() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), IOException("same disk error"), IOException("same disk error"))) {
            val emitted = mutableListOf<DnsSaveReceipt>(); val owner = DnsSaveAttempts(emitted::add); val token = owner.begin()
            val actual = try { owner.persist(token) { throw failure }; null } catch (caught: Exception) { caught }
            assertSame(failure, actual); assertEquals(listOf(DnsSaveReceipt(token, true)), emitted)
        }
    }
}
