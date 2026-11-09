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
    @Test fun graphPhaseSurvivesStopWrappingAndCoroutineRecoveryWithoutPrivatePayload() = runBlocking<Unit> {
        val original = IllegalStateException("https://subscription.invalid/private-token").also { it.stackTrace = emptyArray() }
        val failure = runCatching {
            restoreQueryGraphAfterConfirmedStop {
                traceMeowSetup<Unit> { trace ->
                    trace.phase = MeowSetupPhase.GRAPH_OPEN
                    withContext(Dispatchers.Default) { throw original }
                }
            }
        }.exceptionOrNull() as MeowProtocolFailure
        assertEquals("stop_graph_restore_failed", failure.code)
        val text = failure.details["stopRestoreLocation"].toString()
        assertTrue(text, text.contains("{setupPhase=GRAPH_OPEN}"))
        assertFalse(text.contains("subscription.invalid")); assertFalse(text.contains("private-token"))
        assertTrue(original.suppressed.any { it is MeowSetupFailureEvidence && it.phase == MeowSetupPhase.GRAPH_OPEN })
    }

}
