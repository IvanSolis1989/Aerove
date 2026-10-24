package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class MatrixEvidenceLedgerTest {
    @Test fun lateImageCannotPassOriginalFailureAndAllTwelveRemainAttempted() {
        val ledger = MatrixEvidenceLedger(12)
        val original = AssertionError("No hardware frame was committed")
        for (number in 1..12) ledger.record(number, number != 3, number == 3, if (number == 3) original else null)
        assertEquals(11, ledger.acceptedCount)
        assertEquals(1, ledger.diagnosticCapturedCount)
        assertFalse(ledger.allAccepted)
        try { ledger.assertAllAccepted(); fail("Late images must stay RED") } catch (failure: AssertionError) {
            assertSame(original, failure.suppressed.single())
        }
    }

    @Test fun missingFrameRemainsFailedWithoutInventingDiagnosticImage() {
        val ledger = MatrixEvidenceLedger(2)
        ledger.record(1, false, false, AssertionError("No hardware frame was committed"))
        ledger.record(2, true, false, null)
        assertEquals(1, ledger.acceptedCount)
        assertEquals(0, ledger.diagnosticCapturedCount)
        assertFalse(ledger.allAccepted)
    }

    @Test fun onlyTwelveOriginalSuccessesCanComplete() {
        val ledger = MatrixEvidenceLedger(12)
        for (number in 1..12) ledger.record(number, true, false, null)
        assertTrue(ledger.allAccepted)
        ledger.assertAllAccepted()
    }

    @Test fun cancellationIsRethrownBeforeAnotherCase() {
        for (failure in listOf(InterruptedException("cancel"), CancellationException("cancel"))) {
            try { rethrowMatrixCancellation(failure); fail("Cancellation must stop the collector") }
            catch (caught: Throwable) { assertSame(failure, caught) }
        }
    }

    @Test fun unconfirmedOwnershipStopsAndRetainsTheFirstOriginalFailure() {
        val original = AssertionError("No hardware frame was committed")
        val stop = MatrixCaptureOwnershipFailure(original)
        try { rethrowMatrixCancellation(stop); fail("Unconfirmed ownership must stop the collector") }
        catch (failure: Throwable) { assertSame(stop, failure); assertSame(original, failure.cause) }
    }

    @Test fun cancellationDuringLateEvidencePreservesTheOriginalDeadlineFailure() {
        val original = AssertionError("No hardware frame was committed")
        val cancelled = InterruptedException("cancel during diagnostic wait")
        try { rethrowMatrixCancellation(cancelled, original); fail("Cancellation must remain terminal") }
        catch (failure: Throwable) { assertSame(cancelled, failure); assertSame(original, failure.suppressed.single()) }
    }
}
