package dev.aerove.lite.telemetry

import dev.aerove.lite.Traffic
import dev.aerove.lite.observability.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryCountPublicationTest {
    private class Documents : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) = Unit
        override suspend fun deleteCreated(reference: String) = true
    }
    private fun frame(session: String, cursor: ULong, dns: Boolean = false) = ObservationWindow(session, 1u, cursor, 0u,
        listOf(if (dns) ObservationEvent.Dns(cursor, DnsHistoryEntry("dns-$cursor", Instant.EPOCH, "example.test", "A"))
            else ObservationEvent.Request(cursor, RequestHistoryEntry("request-$cursor", Instant.EPOCH, "tcp", host = "example.test"))),
        activationCursor = 0u)

    @Test fun realHistoryHasOneSecondDeadlineRegardlessOfTrafficAndInteraction() = runTest {
        for (interaction in listOf(false, true)) {
            val history = ObservabilityFeature(backgroundScope, Documents(), {})
            val seen = mutableListOf<UiTelemetrySnapshot>()
            val ui = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
            val lease = history.lease(HistoryKind.Requests, visible = false)
            try {
                ui.countsActive(true); ui.active(true); ui.interacting(interaction)
                history.bindSession(1); history.setForeground(true); runCurrent()
                val session = UUID.randomUUID().toString()
                history.acceptWindow(1, frame(session, 1u)); runCurrent()
                assertEquals(1L, seen.last().counts.requestCount)
                val start = testScheduler.currentTime
                history.acceptWindow(1, frame(session, 2u, dns = true)); runCurrent()
                assertEquals(1L, history.counts.value.dnsCount)
                for (offset in listOf(250L, 500L, 750L)) {
                    advanceTimeBy(start + offset - testScheduler.currentTime)
                    ui.traffic(Traffic(offset, offset), true); runCurrent()
                    assertEquals(0L, seen.last().counts.dnsCount)
                }
                ui.connections(7); ui.interacting(!interaction); runCurrent()
                advanceTimeBy(start + 999 - testScheduler.currentTime); runCurrent()
                assertEquals(0L, seen.last().counts.dnsCount)
                advanceTimeBy(1); runCurrent()
                assertEquals(1L, seen.last().counts.dnsCount)
                assertEquals(7, seen.last().counts.connectionCount)
                assertEquals(1L, history.counts.value.requestCount)
                assertTrue(history.state.value.snapshot.requests.isEmpty())
            } finally { lease.close(); collector.cancel(); ui.close(); history.close() }
        }
    }

    @Test fun hiddenCountsKeepLatestWhileVisibleHistoryStillReceivesEveryForegroundEvent() = runTest {
        val history = ObservabilityFeature(backgroundScope, Documents(), {})
        val seen = mutableListOf<UiTelemetrySnapshot>()
        val ui = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
        val lease = history.lease(HistoryKind.Requests, visible = true)
        try {
            ui.countsActive(true); ui.active(true); history.bindSession(1); history.setForeground(true); runCurrent()
            val session = UUID.randomUUID().toString()
            history.acceptWindow(1, frame(session, 1u)); runCurrent()
            history.acceptWindow(1, frame(session, 2u)); runCurrent()
            advanceTimeBy(999); runCurrent(); ui.countsActive(false)
            val hidden = seen.last().counts
            for (cursor in 3uL..12uL) { history.acceptWindow(1, frame(session, cursor)); runCurrent() }
            advanceTimeBy(2000); runCurrent()
            assertEquals(hidden, seen.last().counts)
            assertEquals(12L, history.counts.value.requestCount)
            assertEquals(12, history.state.value.snapshot.requests.size)
            ui.countsActive(true)
            assertEquals(12L, seen.last().counts.requestCount)
            val size = seen.size
            advanceTimeBy(60000); runCurrent(); assertEquals(size, seen.size)
        } finally { lease.close(); collector.cancel(); ui.close(); history.close() }
    }

    @Test fun clearBindAndUnknownAreBoundariesButOrdinaryEventsAreNot() = runTest {
        val history = ObservabilityFeature(backgroundScope, Documents(), {})
        val seen = mutableListOf<UiTelemetrySnapshot>()
        val ui = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
        val lease = history.lease(HistoryKind.Requests, visible = false)
        try {
            ui.countsActive(true); ui.active(true); history.bindSession(1); history.setForeground(true); runCurrent()
            val session = UUID.randomUUID().toString()
            history.acceptWindow(1, frame(session, 1u)); runCurrent()
            history.acceptWindow(1, frame(session, 2u)); runCurrent()
            assertEquals(1L, seen.last().counts.requestCount)
            history.clear(HistoryKind.Requests); runCurrent()
            assertEquals(2L, seen.last().counts.requestCount)
            history.acceptWindow(1, frame(session, 3u)); runCurrent()
            assertEquals(2L, seen.last().counts.requestCount)
            history.bindSession(2); runCurrent(); assertNull(seen.last().counts.requestCount)
            history.acceptWindow(1, frame(session, 4u)); runCurrent()
            assertNull(seen.last().counts.requestCount)
            history.acceptWindow(2, frame(UUID.randomUUID().toString(), 1u)); runCurrent()
            assertEquals(1L, seen.last().counts.requestCount)
        } finally { lease.close(); collector.cancel(); ui.close(); history.close() }
    }
    @Test fun requestDnsAndConnectionAfterLongSilenceWaitForOneFullBatch() = runTest {
        val history = ObservabilityFeature(backgroundScope, Documents(), {})
        val seen = mutableListOf<UiTelemetrySnapshot>()
        val ui = UiTelemetryPublication(backgroundScope, seen::add, { testScheduler.currentTime })
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
        val lease = history.lease(HistoryKind.Requests, visible = false)
        try {
            ui.countsActive(true); ui.active(true); history.bindSession(1); history.setForeground(true); runCurrent()
            val session = UUID.randomUUID().toString()
            history.acceptWindow(1, frame(session, 1u)); runCurrent()
            assertEquals(1L, seen.last().counts.requestCount)
            advanceTimeBy(2500); runCurrent()
            val stable = seen.last().counts
            history.acceptWindow(1, frame(session, 2u)); runCurrent()
            assertEquals(stable, seen.last().counts)
            advanceTimeBy(250)
            history.acceptWindow(1, frame(session, 3u, dns = true)); runCurrent()
            advanceTimeBy(250); ui.connections(7); runCurrent()
            advanceTimeBy(499); runCurrent(); assertEquals(stable, seen.last().counts)
            advanceTimeBy(1); runCurrent()
            assertEquals(2L, seen.last().counts.requestCount)
            assertEquals(1L, seen.last().counts.dnsCount)
            assertEquals(7, seen.last().counts.connectionCount)
            val size = seen.size
            advanceTimeBy(60000); runCurrent(); assertEquals(size, seen.size)
        } finally { lease.close(); collector.cancel(); ui.close(); history.close() }
    }}
