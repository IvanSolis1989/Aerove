package dev.aerove.lite.flutter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class MeowSetupDiagnosticTest {
    @Test fun allFixedPhasesSurviveAsyncFailureWithNoSourceFrames() = runBlocking<Unit> {
        for (phase in MeowSetupPhase.entries) {
            try {
                traceMeowSetup { trace -> withContext(Dispatchers.Default) {
                    trace.phase = phase
                    throw IllegalStateException("private token").also { it.stackTrace = emptyArray() }
                } }
                fail("Expected failure")
            } catch (failure: IllegalStateException) {
                val text = meowFailureLocation("setupConfig", failure)
                assertTrue(text, text.contains("{setupPhase=${phase.name}}"))
                assertFalse(text.contains("private token"))
            }
        }
    }
    @Test fun cancellationAndSuccessDoNotCreateFailureEvidence() = runBlocking<Unit> {
        assertEquals(7, traceMeowSetup { 7 })
        val cancelled = CancellationException("cancel")
        try { traceMeowSetup<Unit> { throw cancelled }; fail("Expected cancellation") }
        catch (failure: CancellationException) { assertSame(cancelled, failure); assertTrue(failure.suppressed.isEmpty()) }
    }
}
