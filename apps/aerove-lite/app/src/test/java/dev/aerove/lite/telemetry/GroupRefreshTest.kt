package dev.aerove.lite.telemetry

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupRefreshTest {
    @Test fun initialRevisionFetchesOnceAndIdleTimeDoesNotPoll() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        var reads = 0
        val written = mutableListOf<Int>()
        val groups = GroupRefresh(session, { ++reads }, { throw it }, { written += it })
        runCurrent()
        assertEquals(0, reads)
        assertTrue(groups.changed(0uL))
        runCurrent()
        assertEquals(listOf(1), written)
        assertFalse(groups.changed(0uL))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, reads)
        session.close()
    }

    @Test fun changesDuringReadMergeIntoOneFollowupAndNeverPublishTheStaleRead() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val written = mutableListOf<Int>()
        val groups = GroupRefresh(session, {
            val result = ++reads
            if (result == 1) gate.await()
            result
        }, { throw it }, { written += it })
        groups.changed(0uL)
        runCurrent()
        groups.changed(1uL)
        groups.changed(2uL)
        groups.changed(3uL)
        runCurrent()
        assertEquals(1, reads)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, reads)
        assertEquals(listOf(2), written)
        groups.changed(3uL)
        runCurrent()
        assertEquals(2, reads)
        session.close()
    }

    @Test fun errorsUseFullRetryBudgetAndAReconnectedBaselineCanRecoverWithoutPolling() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        var broken = true
        val reads = mutableListOf<Long>()
        val errors = mutableListOf<Throwable>()
        val written = mutableListOf<Int>()
        val groups = GroupRefresh(session, {
            reads += testScheduler.currentTime
            if (broken) throw IOException("unavailable")
            7
        }, { errors += it }, { written += it })
        groups.changed(0uL)
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf(0L, 200L, 400L), reads)
        assertEquals(1, errors.size)
        assertTrue(written.isEmpty())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(3, reads.size)
        broken = false
        groups.changed(0uL)
        runCurrent()
        assertEquals(listOf(7), written)
        session.close()
    }

    @Test fun hiddenSessionDropsPendingChangesAndCannotRestartTheDrain() = runTest {
        val session = TelemetrySession(backgroundScope) { true }
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        val written = mutableListOf<Int>()
        val groups = GroupRefresh(session, {
            reads++
            gate.await()
            reads
        }, { throw it }, { written += it })
        groups.changed(0uL)
        runCurrent()
        groups.changed(1uL)
        session.close()
        runCurrent()
        gate.complete(Unit)
        groups.changed(2uL)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(written.isEmpty())
    }
}
