package dev.aerove.lite.ui

import dev.aerove.lite.Traffic
import org.junit.Assert.*
import org.junit.Test

class DashboardTrafficEpisodeTest {
    @Test fun coreStopStartsAFreshTraceWithoutClearingTheSharedWindow() {
        val trace = DashboardTrafficEpisode()
        val before = Traffic(11, 21)
        val during = Traffic(12, 22)
        val after = Traffic(13, 23)
        assertEquals(listOf(before), trace.samples(listOf(before), 1, true))
        assertTrue(trace.samples(listOf(before, during), 2, false).isEmpty())
        assertTrue(trace.samples(listOf(before, during), 2, true).isEmpty())
        val shared = listOf(before, during, after)
        val fresh = trace.samples(shared, 3, true)
        assertEquals(listOf(after), fresh)
        assertSame(fresh, trace.samples(shared, 3, true))
        assertEquals(3, shared.size)
    }
    @Test fun sameFrameRunningAndFirstSampleStartsImmediatelyAfterEachBoundary() {
        val trace = DashboardTrafficEpisode()
        val shared = MutableList(30) { Traffic() }
        assertEquals(30, trace.samples(shared, 30, true).size)
        repeat(4) { boundary ->
            val revision = 30L + boundary * 2
            assertTrue(trace.samples(shared, revision, false).isEmpty())
            assertEquals(listOf(Traffic()), trace.samples(shared, revision + 1, true))
            val fresh = trace.samples(shared, revision + 2, true)
            assertEquals(listOf(Traffic(), Traffic()), fresh)
            assertEquals(listOf(DashboardCurvePoint(0f, 1f), DashboardCurvePoint(1f, 1f)), dashboardTrafficCurves(fresh).upload)
            assertEquals(30, shared.size)
        }
    }
    @Test fun stoppedFirstMountAndRuntimeCursorResetDoNotReplayOldSamples() {
        val trace = DashboardTrafficEpisode()
        assertTrue(trace.samples(listOf(Traffic(1)), 9, false).isEmpty())
        assertEquals(listOf(Traffic(1)), trace.samples(listOf(Traffic(1)), 10, true))
        assertEquals(listOf(Traffic(1), Traffic(2)), trace.samples(listOf(Traffic(1), Traffic(2)), 11, true))
        assertTrue(trace.samples(listOf(Traffic(3)), 1, true).isEmpty())
        assertEquals(listOf(Traffic(4)), trace.samples(listOf(Traffic(3), Traffic(4)), 2, true))
    }
}
