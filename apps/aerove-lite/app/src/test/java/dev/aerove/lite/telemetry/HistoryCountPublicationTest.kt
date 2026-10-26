package dev.aerove.lite.telemetry

import dev.aerove.lite.Traffic
import dev.aerove.lite.observability.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class HistoryCountPublicationTest {
    private class Documents : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) = Unit
        override suspend fun deleteCreated(reference: String) = true
    }
    private class Clock {
        var time = 0L
        val waits = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        suspend fun await(ms: Long) { val done = CompletableDeferred<Unit>(); waits += ms to done; done.await() }
        fun advanceTo(value: Long, finish: Boolean = false) { time = value; if (finish) waits.last().second.complete(Unit) }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    private fun frame(session: String, cursor: ULong, dns: Boolean = false) = ObservationWindow(session, 1u, cursor, 0u,
        listOf(if (dns) ObservationEvent.Dns(cursor, DnsHistoryEntry("dns-$cursor", Instant.EPOCH, "example.test", "A"))
            else ObservationEvent.Request(cursor, RequestHistoryEntry("request-$cursor", Instant.EPOCH, "tcp", host = "example.test"))),
        activationCursor = 0u)
    private suspend fun exactDeadline(interacting: Boolean, connection: Boolean = false) = coroutineScope {
        val history = ObservabilityFeature(this, Documents(), {})
        val clock = Clock(); val values = mutableListOf<UiTelemetrySnapshot>()
        val ui = UiTelemetryPublication(this, values::add, { clock.time }, clock::await)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
        val lease = history.lease(HistoryKind.Requests, visible = false)
        try {
            ui.active(true); ui.interacting(interacting); history.bindSession(1); history.setForeground(true)
            val session = UUID.randomUUID().toString()
            history.acceptWindow(1, frame(session, 1u)); until { values.last().counts.requestCount == 1L }
            history.acceptWindow(1, frame(session, 2u, dns = true)); until { clock.waits.isNotEmpty() }
            assertEquals(1L, history.counts.value.dnsCount)
            assertEquals(0L, history.state.value.snapshot.dnsCount) // list publication is still pending
            assertTrue(history.state.value.snapshot.requests.isEmpty())
            val deadline = if (interacting) 600L else 300L
            assertEquals(deadline, clock.waits.single().first)
            clock.advanceTo(250)
            if (connection) ui.connections(7) else ui.traffic(Traffic(1, 2), false)
            assertEquals(0L, values.last().counts.dnsCount)
            assertEquals(1, clock.waits.size) // no second stage or reset of the pending deadline
            clock.advanceTo(deadline, finish = true); until { values.last().counts.dnsCount == 1L }
            assertEquals(deadline, clock.time); assertEquals(1L, values.last().counts.requestCount)
            if (connection) assertEquals(7, values.last().counts.connectionCount)
            else assertEquals(Traffic(1, 2), values.last().traffic)
        } finally { lease.close(); collector.cancelAndJoin(); ui.close(); history.close() }
    }
    @Test fun normalFeatureMetadataHasOnlyThe300msUiDeadline() = runBlocking { exactDeadline(false) }
    @Test fun interactionFeatureMetadataHasOnlyThe600msUiDeadline() = runBlocking { exactDeadline(true) }
    @Test fun firstConnectionCountCannotFlushPendingInteractionHistoryBefore600ms() = runBlocking { exactDeadline(true, connection = true) }
    @Test fun bindFirstWindowClearAndPauseKeepAuthoritativeMetadataWithoutCopyingLists() = runBlocking<Unit> {
        val history = ObservabilityFeature(this, Documents(), {}); val clock = Clock()
        val values = mutableListOf<UiTelemetrySnapshot>(); val ui = UiTelemetryPublication(this, values::add, { clock.time }, clock::await)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
        val lease = history.lease(HistoryKind.Requests, visible = false)
        try {
            ui.active(true); ui.interacting(true); history.bindSession(1); history.setForeground(true)
            val session = UUID.randomUUID().toString()
            history.acceptWindow(1, frame(session, 1u)); until { values.last().counts.requestCount == 1L }
            val empty = history.state.value.snapshot.requests
            history.acceptWindow(1, frame(session, 2u)); until { clock.waits.isNotEmpty() }
            assertEquals(1L, values.last().counts.requestCount)
            history.clear(HistoryKind.Requests); until { values.last().counts.requestCount == 2L }
            assertEquals(0L, clock.time) // explicit clear is an immediate boundary, not a timer exception for normal updates
            assertTrue(empty.isEmpty()); assertTrue(history.state.value.snapshot.requests.isEmpty())
            ui.active(false); history.setForeground(false)
            val counts = history.counts.value
            history.acceptWindow(1, frame(session, 3u)); assertSame(counts, history.counts.value)
            history.bindSession(2); history.acceptWindow(1, frame(session, 3u)); assertFalse(history.counts.value.observationsAvailable)
            history.setForeground(true); ui.active(true); yield()
            assertNull(values.last().counts.requestCount)
            history.acceptWindow(2, frame(UUID.randomUUID().toString(), 1u)); until { values.last().counts.requestCount == 1L }
        } finally { lease.close(); collector.cancelAndJoin(); ui.close(); history.close() }
    }
    @Test fun originalRealOwnerTrafficAt250msVectorNoLongerAddsASecondPacer() = runBlocking<Unit> {
        for (interaction in listOf(false, true)) {
            val history = ObservabilityFeature(this, Documents(), {})
            val latest = MutableStateFlow(UiTelemetrySnapshot()); val ui = UiTelemetryPublication(this, { latest.value = it })
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { history.counts.collect(ui::history) }
            val lease = history.lease(HistoryKind.Requests, visible = false)
            try {
                ui.active(true); ui.interacting(interaction); history.bindSession(1); history.setForeground(true)
                val session = UUID.randomUUID().toString()
                history.acceptWindow(1, frame(session, 1u)); withTimeout(3000) { latest.first { it.counts.requestCount == 1L } }
                val start = System.nanoTime(); history.acceptWindow(1, frame(session, 2u))
                delay(250); ui.traffic(Traffic(1, 2), false)
                assertEquals(1L, latest.value.counts.requestCount)
                withTimeout(3000) { latest.first { it.counts.requestCount == 2L } }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                println("SINGLE_PACER interacting=$interaction displayedMs=$elapsed")
                assertTrue("second pacer: $elapsed", elapsed < if (interaction) 750 else 450)
                assertTrue("early publication: $elapsed", elapsed >= if (interaction) 575 else 275)
            } finally { lease.close(); collector.cancelAndJoin(); ui.close(); history.close() }
        }
    }
}
