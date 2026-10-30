package dev.aerove.lite.flutter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MeowPermissionLedgerTest {
    @Test fun aCancelledPromptResultCannotResolveItsReplacement() {
        val ledger = MeowPermissionLedger<String>()
        val first = ledger.issue("A")
        assertTrue(ledger.cancel(first))
        val replacement = ledger.issue("B")
        val old = checkNotNull(ledger.resolve(first.requestCode))
        assertSame(first, old.entry)
        assertTrue(old.cancelled)
        assertEquals(listOf(replacement), ledger.activeEntries())
        val current = checkNotNull(ledger.resolve(replacement.requestCode))
        assertSame(replacement, current.entry)
        assertFalse(current.cancelled)
    }

    @Test fun listenerReattachmentKeepsTheSameOutstandingReceipt() {
        val ledger = MeowPermissionLedger<Any>()
        val entry = ledger.issue(Any())
        val retained = ledger.activeEntries().single()
        assertSame(entry, retained)
        val replacementListener = ledger::resolve
        assertSame(entry, replacementListener(retained.requestCode)?.entry)
        assertTrue(ledger.activeEntries().isEmpty())
    }

    @Test fun differentDefaultLedgersUseTheSameProcessAllocatorWithoutCodeReuse() {
        val codes = (1..30).map { MeowPermissionLedger<Int>().issue(it).requestCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in 0x6000..0x7ffe })
        assertTrue(codes.zipWithNext().all { (before, after) -> before < after })
    }

    @Test fun consumedCodesAndNewLedgersCannotResetOrWrapAnExhaustedAllocator() {
        val codes = MeowPermissionRequestCodes(10, 11)
        val ledger = MeowPermissionLedger<String>(codes, 2)
        val first = ledger.issue("A")
        ledger.resolve(first.requestCode)
        val second = ledger.issue("B")
        ledger.resolve(second.requestCode)
        assertEquals(listOf(10, 11), listOf(first.requestCode, second.requestCode))
        repeat(3) {
            try { MeowPermissionLedger<String>(codes, 2).issue("C"); error("Exhausted codes wrapped") }
            catch (_: MeowPermissionCodeExhausted) { }
        }
    }

    @Test fun cancelledTombstonesStillCountAgainstCapacityUntilTheirResultsDrain() {
        val ledger = MeowPermissionLedger<String>(MeowPermissionRequestCodes(20, 25), 1)
        val first = ledger.issue("A")
        ledger.cancel(first)
        try { ledger.issue("B"); error("Undrained tombstone released capacity") }
        catch (failure: MeowPermissionLedgerFull) { assertEquals(1, failure.capacity) }
        assertTrue(checkNotNull(ledger.resolve(first.requestCode)).cancelled)
        assertEquals(21, ledger.issue("B").requestCode)
    }

    @Test fun aForeignReceiptWithTheSameCodeCannotCancelALocalPrompt() {
        val ledger = MeowPermissionLedger<String>(MeowPermissionRequestCodes(30, 31), 1)
        val other = MeowPermissionLedger<String>(MeowPermissionRequestCodes(30, 31), 1)
        val entry = ledger.issue("A")
        assertFalse(ledger.cancel(other.issue("B")))
        assertFalse(checkNotNull(ledger.resolve(entry.requestCode)).cancelled)
    }

    @Test fun unknownAndRepeatedResultsCannotConsumeAnotherOutstandingReceipt() {
        val ledger = MeowPermissionLedger<String>()
        val first = ledger.issue("A")
        val second = ledger.issue("B")
        assertNull(ledger.resolve(-1))
        assertSame(first, ledger.resolve(first.requestCode)?.entry)
        assertNull(ledger.resolve(first.requestCode))
        assertEquals(listOf(second), ledger.activeEntries())
        assertSame(second, ledger.resolve(second.requestCode)?.entry)
    }

    @Test fun cancelAllMarksOnlyActiveEntriesOnceAndKeepsEachTombstone() {
        val ledger = MeowPermissionLedger<String>()
        val first = ledger.issue("A")
        val second = ledger.issue("B")
        ledger.cancel(first)
        assertEquals(listOf(second), ledger.cancelAll())
        assertTrue(ledger.cancelAll().isEmpty())
        assertTrue(ledger.activeEntries().isEmpty())
        assertTrue(checkNotNull(ledger.resolve(first.requestCode)).cancelled)
        assertTrue(checkNotNull(ledger.resolve(second.requestCode)).cancelled)
    }

    @Test fun cancellationAfterResolutionCannotChangeTheResolvedReceipt() {
        val ledger = MeowPermissionLedger<String>()
        val entry = ledger.issue("A")
        val result = checkNotNull(ledger.resolve(entry.requestCode))
        assertFalse(ledger.cancel(entry))
        assertFalse(result.cancelled)
    }
}
