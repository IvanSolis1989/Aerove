package dev.aerove.lite.telemetry

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetrySessionTest {
    @Test fun demandStartsOneStreamAndStopsItWithoutRestartingOtherTelemetry() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val connections = MutableStateFlow(false)
        var starts = 0
        var running = 0
        var trafficStarts = 0
        val received = mutableListOf<Int>()
        session.observe("traffic", flowOf(true), {
            flow { trafficStarts++; emit(7); awaitCancellation() }
        }, { throw it }, {})
        session.observe("connections", connections, {
            flow {
                starts++
                running++
                try { emit(starts); awaitCancellation() } finally { running-- }
            }
        }, { throw it }, { received += it })
        runCurrent()
        assertEquals(0, starts)
        connections.value = true
        runCurrent()
        assertEquals(1, running)
        connections.value = true
        runCurrent()
        assertEquals(1, starts)
        connections.value = false
        runCurrent()
        assertEquals(0, running)
        connections.value = true
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, running)
        assertEquals(1, trafficStarts)
        assertEquals(listOf(1, 2), received)
        session.close()
        runCurrent()
        assertEquals(0, running)
    }

    @Test fun logsRemainOffUntilEnabledAndDoNotRestartOnRepeatedTrue() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val enabled = MutableStateFlow(false)
        var starts = 0
        var stops = 0
        session.observe("logs", enabled, {
            flow<Int> { starts++; try { awaitCancellation() } finally { stops++ } }
        }, { throw it }, {})
        runCurrent()
        assertEquals(0, starts)
        enabled.value = true
        runCurrent()
        enabled.value = true
        runCurrent()
        assertEquals(1, starts)
        enabled.value = false
        runCurrent()
        assertEquals(1, stops)
        session.close()
    }

    @Test fun closingSessionCancelsQueuedProbesBeforeTheyAcquirePermits() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<Int>()
        val written = mutableListOf<Int>()
        var completed = 0
        val task = session.launch("delay:group", { completed++ }) {
            val limit = Semaphore(4)
            (0 until 30).map { node -> async {
                limit.withPermit {
                    started += node
                    gate.await()
                    session.accept { written += node }
                }
            } }.awaitAll()
        }
        runCurrent()
        assertEquals(4, started.size)
        session.close()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(task!!.isCancelled)
        assertEquals(4, started.size)
        assertTrue(written.isEmpty())
        assertEquals(1, completed)
    }

    @Test fun oldSessionCannotWriteAfterNewSessionEvenIfItsOperationIgnoresCancellation() = runTest {
        var revision = 1
        val release = CompletableDeferred<Unit>()
        val written = mutableListOf<String>()
        val old = TelemetrySession(backgroundScope) { revision == 1 }
        old.launch("delay:group") {
            withContext(NonCancellable) {
                release.await()
                old.accept { written += "old" }
            }
        }
        runCurrent()
        revision = 2
        assertFalse(old.current)
        old.accept { written += "late-before-cancel" }
        old.close()
        val next = TelemetrySession(backgroundScope) { revision == 2 }
        next.launch("delay:group") { next.accept { written += "new" } }
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), written)
        next.close()
    }

    @Test fun sameKeyIsSingleFlightAndFinishesBeforeItCanBeReused() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var completed = 0
        session.launch("delay:group", { completed++ }) { calls++; gate.await() }
        assertNull(session.launch("delay:group") { calls++ })
        runCurrent()
        assertEquals(1, calls)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, completed)
        assertNotNull(session.launch("delay:group") { calls++ })
        runCurrent()
        assertEquals(2, calls)
        session.close()
        assertNull(session.launch("delay:group") { calls++ })
    }

    @Test fun rapidPauseResumeRebindsTheSameEndpointWithoutDuplicateResumeSubscriptions() = runTest {
        val visibility = MutableStateFlow(TelemetryVisibility())
        val endpoint = MutableStateFlow<String?>("core")
        var opened = 0
        var closed = 0
        val observer = backgroundScope.launch {
            sessionBindings(visibility, endpoint).collectLatest { binding ->
                if (binding != null) {
                    opened++
                    try { awaitCancellation() } finally { closed++ }
                }
            }
        }
        runCurrent()
        assertEquals(0, opened)
        visibility.value = visibility.value.changed(true)
        runCurrent()
        assertEquals(1, opened)
        visibility.value = visibility.value.changed(false)
        visibility.value = visibility.value.changed(true)
        runCurrent()
        assertEquals(2, opened)
        assertEquals(1, closed)
        visibility.value = visibility.value.changed(true)
        runCurrent()
        assertEquals(2, opened)
        observer.cancel()
        runCurrent()
        assertEquals(2, closed)
    }
}
