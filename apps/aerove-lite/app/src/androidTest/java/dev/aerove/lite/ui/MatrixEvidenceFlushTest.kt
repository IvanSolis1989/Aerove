package dev.aerove.lite.ui

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MatrixEvidenceFlushTest {
    private fun failingBothFinallyWrites(primary: Throwable, caseWrite: IOException, outerWrite: IOException): Throwable? {
        var nextCaseStarted = false
        val caught = runCatching {
            var outerFailure: Throwable? = null
            try {
                var caseFailure: Throwable? = null
                try {
                    throw primary
                } catch (failure: Throwable) {
                    caseFailure = failure
                    throw failure
                } finally {
                    preserveMatrixEvidenceFailure(caseFailure) { throw caseWrite }
                }
                @Suppress("UNREACHABLE_CODE")
                run { nextCaseStarted = true }
            } catch (failure: Throwable) {
                outerFailure = failure
                throw failure
            } finally {
                preserveMatrixEvidenceFailure(outerFailure) { throw outerWrite }
            }
        }.exceptionOrNull()
        assertFalse(nextCaseStarted)
        return caught
    }

    @Test fun pendingCopyTerminalAndOriginalHardwareErrorSurviveBothIoFailures() {
        val hardware = AssertionError("No hardware frame was committed")
        val copy = AssertionError("PixelCopy did not return")
        val owner = PixelCopyOwnership {}
        owner.submit()
        val terminal = PixelCopyOwnershipFailure(copy, owner.snapshot()).also { it.addSuppressed(hardware) }
        val caseIo = IOException("case manifest full")
        val outerIo = IOException("final manifest full")
        assertSame(terminal, failingBothFinallyWrites(terminal, caseIo, outerIo))
        assertSame(copy, terminal.cause)
        assertEquals(listOf(hardware, caseIo, outerIo), terminal.suppressed.toList())
        owner.close()
        assertFalse(owner.snapshot().released)
        owner.complete(0)
    }

    @Test fun cancellationIdentitySurvivesBothIoFailures() {
        for (cancel in listOf(InterruptedException("cancel"), CancellationException("cancel"))) {
            val caseIo = IOException("case manifest denied")
            val outerIo = IOException("final manifest denied")
            assertSame(cancel, failingBothFinallyWrites(cancel, caseIo, outerIo))
            assertEquals(listOf(caseIo, outerIo), cancel.suppressed.toList())
        }
    }

    @Test fun manifestIoWithoutOriginalFailureStillFails() {
        val io = IOException("manifest cannot be saved")
        val failure = runCatching { preserveMatrixEvidenceFailure(null) { throw io } }.exceptionOrNull()
        assertSame(io, failure)
    }

    @Test fun recoverableOriginalFailureKeepsIoAndOverallRed() {
        val original = AssertionError("No hardware frame was committed")
        val io = IOException("case evidence failed")
        val ledger = MatrixEvidenceLedger(1)
        ledger.record(1, false, false, original)
        preserveMatrixEvidenceFailure(original) { throw io }
        val summary = runCatching { ledger.assertAllAccepted() }.exceptionOrNull()
        assertTrue(summary is AssertionError)
        assertSame(original, summary!!.suppressed.single())
        assertSame(io, original.suppressed.single())
        assertFalse(ledger.allAccepted)
    }
}
