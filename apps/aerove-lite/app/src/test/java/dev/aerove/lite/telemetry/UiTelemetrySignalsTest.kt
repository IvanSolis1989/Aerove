package dev.aerove.lite.telemetry

import dev.aerove.lite.DashboardCounts
import dev.aerove.lite.Traffic
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
    @Test fun countsAndTrafficShare300Then600DeadlineAndPublishOnlyLatestWithoutPolling() = runBlocking<Unit> {
        val clock = Clock(); val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(this, seen::add, { clock.time }, clock::await)
        try {
            owner.active(true); assertEquals(1, seen.size)
            owner.counts(DashboardCounts(connectionCount = 1)); yield()
            assertEquals(300L, clock.waits.last().first)
            owner.counts(DashboardCounts(connectionCount = 2)); owner.counts(DashboardCounts(connectionCount = 3))
            assertEquals(1, clock.waits.size)
            clock.advance(300); until { seen.size == 2 }; assertEquals(3, seen.last().counts.connectionCount)
            owner.interacting(true); owner.traffic(Traffic(1, 2), true)
            assertEquals(3, seen.size) // first real traffic bypasses the empty initial snapshot
            owner.traffic(Traffic(2, 4), true); yield(); assertEquals(600L, clock.waits.last().first)
            owner.counts(DashboardCounts(connectionCount = 4)); clock.advance(600); until { seen.size == 4 }
            assertEquals(4, seen.last().counts.connectionCount); assertEquals(Traffic(2, 4), seen.last().traffic)
            val waits = clock.waits.size; delay(15); assertEquals(waits, clock.waits.size)
            assertEquals(4, seen.size)
        } finally { owner.close() }
    }
    @Test fun pauseRetainsLatestResumeImmediatelyAndRuntimeResetClearsEvenInactive() = runBlocking<Unit> {
        val clock = Clock(); val seen = mutableListOf<UiTelemetrySnapshot>()
        val owner = UiTelemetryPublication(this, seen::add, { clock.time }, clock::await)
        try {
            owner.active(true); owner.traffic(Traffic(1, 2), true)
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
}
