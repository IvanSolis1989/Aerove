package dev.aerove.lite.telemetry

import dev.aerove.lite.DashboardCounts
import dev.aerove.lite.Traffic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class UiTelemetrySignalsTest {
    private suspend fun until(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(2) }
    @Test fun focusLeaseDoesNotRetireControlOrMutationsAndRestartsAtLatestValue() = runBlocking<Unit> {
        val signals = UiReadSignals(); signals.foreground(true); signals.focused(true)
        val values = MutableStateFlow(1); val seen = mutableListOf<Int>()
        var opened = 0; var closed = 0; var controlCurrent = true
        val session = TelemetrySession(this) { controlCurrent }
        val mutationRelease = CompletableDeferred<Unit>(); var mutationFinished = false
        session.launch("mutation") { mutationRelease.await(); mutationFinished = true }
        session.observeReadOnly("read", signals.state, { flow { opened++; try { emitAll(values) } finally { closed++ } } },
            { it == signals.state.value && it.active }, { throw it }, seen::add)
        try {
            until { seen == listOf(1) }
            signals.focused(false); until { closed == 1 }; values.value = 2; delay(15)
            assertEquals(listOf(1), seen); assertTrue(session.current)
            mutationRelease.complete(Unit); until { mutationFinished }
            signals.focused(true); until { seen == listOf(1, 2) }
            assertEquals(2, opened); assertTrue(session.current)
            val generation = signals.state.value.generation
            signals.focused(false); signals.focused(true)
            until { opened == 3 }; assertEquals(generation + 2, signals.state.value.generation)
            controlCurrent = false; assertFalse(session.current)
        } finally { mutationRelease.complete(Unit); session.close() }
    }
    @Test fun canceledReadJoinsBeforeFocusSuccessorAndLateValueCannotPublish() = runBlocking<Unit> {
        val signals = UiReadSignals(); signals.foreground(true); signals.focused(true)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var starts = 0; val seen = mutableListOf<Int>()
        val session = TelemetrySession(this) { true }
        session.observeReadOnly("read", signals.state, { flow {
            val n = ++starts
            if (n == 1) withContext(NonCancellable) { entered.complete(Unit); release.await() }
            emit(n); awaitCancellation()
        } }, { it == signals.state.value && it.active }, { throw it }, seen::add)
        try {
            entered.await(); signals.focused(false); yield(); signals.focused(true); delay(15)
            assertEquals(1, starts); assertTrue(seen.isEmpty())
            release.complete(Unit); until { seen == listOf(2) }; assertEquals(2, starts)
        } finally { release.complete(Unit); session.close() }
    }
    private class Clock {
        var time = 0L
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        suspend fun await(ms: Long) { val gate = CompletableDeferred<Unit>(); waits += ms to gate; gate.await() }
        fun advance(ms: Long) { time += ms; waits.last().second.complete(Unit) }
    }
    @Test fun countsHaveIndependentOneSecondDeadlineAndNoSilentTicker() = runBlocking<Unit> {
        val clock = Clock(); val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(this, seen::add, { clock.time }, clock::await)
        try {
            owner.countsActive(true); owner.active(true)
            owner.counts(DashboardCounts(connectionCount = 1)); yield()
            assertEquals(1000L, clock.waits.single().first)
            owner.counts(DashboardCounts(connectionCount = 2)); owner.interacting(true)
            owner.traffic(Traffic(1, 2), true)
            assertNull(seen.last().counts.connectionCount)
            assertEquals(1, clock.waits.size)
            clock.advance(1000); yield()
            assertEquals(2, seen.last().counts.connectionCount)
            val waits = clock.waits.size; val size = seen.size
            delay(15); assertEquals(waits, clock.waits.size); assertEquals(size, seen.size)
        } finally { owner.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun pauseAt999CancelsOldCountsAndResumeIsLatestWithoutMovingTraffic() = runTest {
        for (hide in listOf(false, true)) {
            val seen = mutableListOf<UiTelemetrySnapshot>()
            val owner = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
            try {
                owner.countsActive(true); owner.active(true)
                owner.traffic(Traffic(1, 2), true)
                owner.counts(DashboardCounts(connectionCount = 1))
                advanceTimeBy(999); runCurrent()
                assertNull(seen.last().counts.connectionCount)
                if (hide) owner.countsActive(false) else owner.active(false)
                val size = seen.size
                owner.counts(DashboardCounts(connectionCount = 2))
                advanceTimeBy(1); runCurrent(); assertEquals(size, seen.size)
                if (hide) owner.countsActive(true) else owner.active(true)
                assertEquals(2, seen.last().counts.connectionCount)
                val resumed = seen.size
                runCurrent(); assertEquals(resumed, seen.size)
                owner.counts(DashboardCounts(connectionCount = 3))
                owner.active(false); owner.active(true)
                assertEquals(3, seen.last().counts.connectionCount)
                val latest = seen.size
                advanceTimeBy(1000); runCurrent(); assertEquals(latest, seen.size)
                owner.active(false); owner.reset(); assertEquals(UiTelemetrySnapshot(), seen.last())
            } finally { owner.close() }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun countPublicationCannotFlushPendingTrafficOrReorderItsDeadline() = runTest {
        val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
        try {
            owner.countsActive(true); owner.active(true); owner.interacting(true)
            owner.traffic(Traffic(1, 2), true)
            owner.counts(DashboardCounts(connectionCount = 1))
            advanceTimeBy(900); runCurrent()
            owner.traffic(Traffic(2, 4), true)
            owner.traffic(Traffic(3, 6), true)
            advanceTimeBy(99); runCurrent(); assertNull(seen.last().counts.connectionCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, seen.last().counts.connectionCount)
            assertEquals(Traffic(2, 4), seen.last().traffic)
            val countsHistory = seen.last().history
            advanceTimeBy(499); runCurrent(); assertEquals(Traffic(2, 4), seen.last().traffic)
            advanceTimeBy(1); runCurrent(); assertEquals(Traffic(3, 6), seen.last().traffic)
            assertEquals(2, countsHistory.size)
            assertEquals(3, seen.last().history.size)
        } finally { owner.close() }
    }
    @Test fun pauseRetainsLatestResumeImmediatelyAndRuntimeResetClearsEvenInactive() = runBlocking<Unit> {
        val clock = Clock(); val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(this, seen::add, { clock.time }, clock::await)
        try {
            owner.countsActive(true); owner.active(true); owner.traffic(Traffic(1, 2), true)
            owner.counts(DashboardCounts(connectionCount = 9)); yield()
            owner.active(false); val n = seen.size; clock.advance(600); yield()
            assertEquals(n, seen.size)
            owner.counts(DashboardCounts(connectionCount = 10)); owner.active(true)
            assertEquals(10, seen.last().counts.connectionCount); assertEquals(Traffic(1, 2), seen.last().traffic)
            owner.active(false); owner.reset()
            assertEquals(UiTelemetrySnapshot(), seen.last())
        } finally { owner.close() }
    }
    @Test fun coalescingRetainsThirtyRawSamplesAndImmutableHistory() = runBlocking<Unit> {
        val clock = Clock(); val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(this, seen::add, { clock.time }, clock::await)
        try {
            owner.active(true); owner.traffic(Traffic(0, 0), true); val first = seen.last()
            repeat(40) { owner.traffic(Traffic((it + 1).toLong(), 0), true) }
            yield(); clock.advance(300); until { seen.last().historyRevision == 41L }
            assertEquals(30, seen.last().history.size); assertEquals(11L, seen.last().history.first().uploadPerSecond)
            assertEquals(listOf(Traffic()), first.history); assertEquals(1L, first.historyRevision)
        } finally { owner.close() }
    }
    @Test fun groupReadsPauseJoinAndResumeExactlyOneFreshSnapshot() = runBlocking<Unit> {
        val session = TelemetrySession(this) { true }; val release = CompletableDeferred<Unit>()
        var calls = 0; var finished = false; val seen = mutableListOf<Int>()
        val group = GroupRefresh(session, {
            val n = ++calls
            if (n == 1) withContext(NonCancellable) { release.await(); finished = true }
            n
        }, { throw it }, seen::add)
        try {
            group.readDemand(false); group.initial(); delay(20); assertEquals(0, calls)
            group.readDemand(true); until { calls == 1 }; group.readDemand(false); group.readDemand(true)
            delay(20); assertEquals(1, calls); assertTrue(seen.isEmpty())
            release.complete(Unit); until { seen == listOf(2) }; assertTrue(finished)
            delay(350); assertEquals(2, calls)
        } finally { release.complete(Unit); session.close() }
    }
    @Test fun connectionSummaryCachesDuringPauseAndPublishesCurrentValueOnResume() = runBlocking<Unit> {
        var count = 4; var calls = 0
        val owner = DashboardConnectionSummary(this, { _: Int -> calls++; count }, { true })
        try {
            owner.bind(1); owner.active(true); until { owner.state.value == 4 }
            owner.active(false); count = 7; delay(20)
            assertEquals(4, owner.state.value); assertEquals(1, calls)
            owner.active(true); until { owner.state.value == 7 }; assertEquals(2, calls)
            owner.bind(null); assertNull(owner.state.value)
        } finally { owner.close() }
    }
    @Test fun summaryResumeJoinsOldReadBeforeIssuingNewRequest() = runBlocking<Unit> {
        var calls = 0; val release = CompletableDeferred<Unit>()
        val owner = DashboardConnectionSummary(this, { _: Int ->
            val n = ++calls; if (n == 1) withContext(NonCancellable) { release.await() }; n
        }, { true })
        try {
            owner.bind(1); owner.active(true); until { calls == 1 }
            owner.active(false); owner.active(true); delay(20); assertEquals(1, calls)
            release.complete(Unit); until { owner.state.value == 2 }; assertEquals(2, calls)
        } finally { release.complete(Unit); owner.close() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun eachOrdinaryCountFieldStartsATrailingBatchAfterSilence() = runTest {
        for (field in 0..2) {
            val seen = mutableListOf<UiTelemetrySnapshot>()
            val owner = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
            try {
                owner.countsActive(true); owner.active(true)
                val stable = DashboardCounts(1, 10, 20)
                owner.counts(stable, immediate = true)
                advanceTimeBy(2500); runCurrent()
                val changed = when (field) {
                    0 -> stable.copy(connectionCount = 2)
                    1 -> stable.copy(requestCount = 11)
                    else -> stable.copy(dnsCount = 21)
                }
                owner.counts(changed); runCurrent()
                assertEquals(stable, seen.last().counts)
                advanceTimeBy(999); runCurrent(); assertEquals(stable, seen.last().counts)
                advanceTimeBy(1); runCurrent(); assertEquals(changed, seen.last().counts)
            } finally { owner.close() }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun sustainedChangesPublishLatestOncePerFirstDirtyBatch() = runTest {
        val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
        try {
            owner.countsActive(true); owner.active(true)
            owner.counts(DashboardCounts(connectionCount = 0), immediate = true)
            advanceTimeBy(2500); runCurrent()
            repeat(3) { batch ->
                val previous = seen.last().counts
                val before = seen.size
                repeat(10) { event ->
                    owner.connections(batch * 10 + event + 1); runCurrent()
                    advanceTimeBy(if (event == 9) 99 else 100)
                }
                runCurrent(); assertEquals(previous, seen.last().counts)
                assertEquals(before, seen.size)
                advanceTimeBy(1); runCurrent()
                assertEquals(batch * 10 + 10, seen.last().counts.connectionCount)
                assertEquals(before + 1, seen.size)
            }
            val size = seen.size
            advanceTimeBy(60000); runCurrent(); assertEquals(size, seen.size)
        } finally { owner.close() }
    }}
