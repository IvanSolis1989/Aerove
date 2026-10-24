package dev.aerove.lite.ui

import java.util.concurrent.CancellationException

/** Evidence never upgrades a failed original acceptance gate. */
internal class MatrixEvidenceLedger(private val expectedCases: Int) {
    private val visited = mutableSetOf<Int>()
    private val failures = mutableListOf<Throwable>()
    var acceptedCount = 0
        private set
    var diagnosticCapturedCount = 0
        private set
    val allAccepted get() = visited.size == expectedCases && failures.isEmpty() && acceptedCount == expectedCases

    fun record(number: Int, accepted: Boolean, diagnosticCaptured: Boolean, failure: Throwable?) {
        require(number in 1..expectedCases && visited.add(number))
        require(accepted == (failure == null))
        require(!accepted || !diagnosticCaptured)
        if (accepted) acceptedCount++ else failures += requireNotNull(failure)
        if (diagnosticCaptured) diagnosticCapturedCount++
    }

    fun assertAllAccepted() {
        check(visited.size == expectedCases) { "Not every matrix case was attempted" }
        if (!allAccepted) throw AssertionError("${failures.size} of $expectedCases matrix cases failed their original acceptance; diagnostic images cannot pass them")
            .also { summary -> failures.forEach(summary::addSuppressed) }
    }
}

internal class MatrixCaptureOwnershipFailure(originalFailure: Throwable) :
    IllegalStateException("Matrix capture owner cleanup was not confirmed", originalFailure)

internal fun rethrowMatrixCancellation(failure: Throwable, originalFailure: Throwable? = null) {
    if (failure is InterruptedException || failure is CancellationException || failure is VirtualMachineError ||
        failure is ThreadDeath || failure is LinkageError || failure is MatrixCaptureOwnershipFailure ||
        failure is PixelCopyOwnershipFailure ||
        Thread.currentThread().isInterrupted) {
        if (originalFailure != null && originalFailure !== failure) failure.addSuppressed(originalFailure)
        throw failure
    }
}
