package dev.aerove.lite.telemetry

import dev.aerove.lite.LiteState
import dev.aerove.lite.DashboardCounts
import dev.aerove.lite.acceptDashboardTraffic
import dev.aerove.lite.resetDashboardRuntime
import dev.aerove.lite.Traffic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardTelemetryTest {
    @Test fun historyRetainsThirtyRealSamplesAndDoesNotPadOrMutateOldSnapshots() {
        var samples = emptyList<Traffic>()
        val first = appendDashboardTraffic(samples, Traffic())
        assertEquals(listOf(Traffic()), first)
        repeat(40) { index -> samples = appendDashboardTraffic(samples, Traffic(index.toLong(), (index * 2).toLong())) }
        assertEquals(30, samples.size)
        assertEquals(10L, samples.first().uploadPerSecond)
        assertEquals(78L, samples.last().downloadPerSecond)
        assertEquals(listOf(Traffic()), first)
    }

    @Test fun countOnlySummaryReadsAtTwoSecondsOnlyWhileVisibleAndNeverDuplicatesBinding() = runTest {
        var reads = 0
        val summary = DashboardConnectionSummary(backgroundScope, { _: String -> ++reads }, { true })
        summary.bind("core")
        runCurrent()
        assertEquals(0, reads)
        assertNull(summary.state.value)
        summary.active(true)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1, summary.state.value)
        repeat(5) { summary.bind("core"); summary.active(true) }
        advanceTimeBy(1999); runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, reads)
        summary.active(false)
        assertEquals(2, summary.state.value)
        advanceTimeBy(60000); runCurrent()
        assertEquals(2, reads)
        summary.active(true); runCurrent()
        assertEquals(3, reads)
        summary.close()
    }

    @Test fun cancelledOldSummaryCannotPublishIntoNewCoreAndUnknownIsNotZero() = runTest {
        var current = "old"
        val replies = mutableListOf<CompletableDeferred<Int>>()
        val summary = DashboardConnectionSummary(backgroundScope, { _: String ->
            val reply = CompletableDeferred<Int>(); replies += reply
            withContext(NonCancellable) { reply.await() }
        }, { it == current })
        summary.bind(current); summary.active(true); runCurrent()
        assertNull(summary.state.value)
        current = "new"; summary.bind(current); runCurrent()
        assertEquals(1, replies.size)
        replies.first().complete(99); runCurrent()
        assertNull(summary.state.value)
        assertEquals(2, replies.size)
        replies.last().complete(0); runCurrent()
        assertEquals(0, summary.state.value)
        summary.close()
    }

    @Test fun failedSummaryKeepsUnknownAndRetriesOnlyAtTheExistingTwoSecondCadence() = runTest {
        var reads = 0
        val summary = DashboardConnectionSummary(backgroundScope, { _: String ->
            reads++; if (reads == 1) error("unavailable") else 7
        }, { true })
        summary.bind("core"); summary.active(true); runCurrent()
        assertNull(summary.state.value)
        advanceTimeBy(1999); runCurrent(); assertEquals(1, reads)
        advanceTimeBy(1); runCurrent(); assertEquals(7, summary.state.value)
        summary.bind(null)
        advanceTimeBy(60000); runCurrent(); assertEquals(2, reads)
        summary.close()
    }

    @Test fun acceptedZeroAndRepeatedSamplesAdvanceRevisionEvenWhenHistoryIsEqual() {
        var state = LiteState()
        repeat(30) { state = state.acceptDashboardTraffic(Traffic()) }
        val full = state
        state = state.acceptDashboardTraffic(Traffic())
        assertEquals(full.trafficHistory, state.trafficHistory)
        assertEquals(30L, full.trafficHistoryRevision)
        assertEquals(31L, state.trafficHistoryRevision)
        val repeat = Traffic(7, 9)
        state = state.acceptDashboardTraffic(repeat).acceptDashboardTraffic(repeat)
        assertEquals(33L, state.trafficHistoryRevision)
        assertEquals(repeat, state.traffic)
        assertEquals(listOf(repeat, repeat), state.trafficHistory.takeLast(2))
    }

    @Test fun runtimeResetClearsHistoryAndRevisionBeforeNextAcceptedSample() {
        val sampled = LiteState(dashboardCounts = DashboardCounts(3, 4, 5))
            .acceptDashboardTraffic(Traffic(1, 2))
        val reset = sampled.resetDashboardRuntime()
        assertEquals(emptyList<Traffic>(), reset.trafficHistory)
        assertEquals(0L, reset.trafficHistoryRevision)
        assertEquals(DashboardCounts(), reset.dashboardCounts)
        assertEquals(1L, reset.acceptDashboardTraffic(Traffic()).trafficHistoryRevision)
        assertEquals(1L, sampled.trafficHistoryRevision)
    }

    @Test(expected = ArithmeticException::class)
    fun trafficRevisionCannotSilentlyWrap() {
        LiteState(trafficHistoryRevision = Long.MAX_VALUE).acceptDashboardTraffic(Traffic())
    }

}
