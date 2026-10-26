package dev.aerove.lite.observability

import dev.aerove.lite.LogLine
import java.time.Instant
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ObservationPublicationTest {
    private val documents = object : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) = Unit
        override suspend fun deleteCreated(reference: String) = true
    }
    private fun window(cursor: ULong, generation: ULong = 1u) = ObservationWindow("runtime", generation, cursor, 0u,
        listOf(ObservationEvent.Request(cursor, RequestHistoryEntry("request-$cursor", Instant.EPOCH, "tcp", host = "example.test"))))

    @Test fun immutableSnapshotsReuseUnchangedCategoriesAndHiddenReadsDoNotCopy() {
        val repository = HistoryRepository(); repository.bindSession(1)
        repository.accept(1, window(1u))
        val first = repository.snapshot(setOf(HistoryKind.Requests))
        repository.acceptLog(1, LogLine(0, "info", "one"))
        val hidden = repository.snapshot(emptySet())
        assertSame(first.requests, hidden.requests); assertTrue(hidden.logs.isEmpty())
        val shown = repository.snapshot(setOf(HistoryKind.Logs))
        assertSame(first.requests, shown.requests); assertEquals(1, shown.logs.size)
        assertSame(shown.logs, repository.snapshot().logs)
        repository.accept(1, window(2u))
        assertSame(first.requests, repository.snapshot(emptySet()).requests)
        val newer = repository.snapshot(setOf(HistoryKind.Requests))
        assertNotSame(first.requests, newer.requests); assertEquals(1, first.requests.size); assertEquals(2, newer.requests.size)
        try { (newer.requests as MutableList).clear(); fail("mutable snapshot") } catch (_: UnsupportedOperationException) { }
        repository.bindSession(2); assertTrue(repository.snapshot(emptySet()).requests.isEmpty())
        assertFalse(repository.accept(1, window(3u)))
    }

    @Test fun trafficAndCloseInvalidateOnlyRequestsAndOldSnapshotsRemainStable() {
        val repository = HistoryRepository(); repository.bindSession(1); repository.accept(1, window(1u))
        val original = repository.snapshot()
        repository.accept(1, ObservationWindow("runtime", 1u, 2u, 0u,
            listOf(ObservationEvent.Traffic(2u, "request-1", 10u, 20u))))
        val traffic = repository.snapshot()
        assertTrue(traffic.requestRevision > original.requestRevision)
        assertSame(original.logs, traffic.logs); assertSame(original.dns, traffic.dns)
        assertEquals(0uL, original.requests.single().download); assertEquals(20uL, traffic.requests.single().download)
        repository.accept(1, ObservationWindow("runtime", 1u, 3u, 0u,
            listOf(ObservationEvent.Closed(3u, "request-1", end = Instant.ofEpochSecond(1), upload = 11u, download = 21u))))
        val closed = repository.snapshot()
        assertTrue(closed.requestRevision > traffic.requestRevision)
        assertNull(traffic.requests.single().end); assertNotNull(closed.requests.single().end)
    }

    @Test fun trailingPublicationCancelsInBackgroundAndResumeReadsLatestVisibleRing() = runBlocking {
        val feature = ObservabilityFeature(this, documents) {}
        try {
            feature.bindSession(1); feature.setForeground(true)
            val collection = feature.lease(HistoryKind.Requests, visible = false)
            feature.acceptWindow(1, window(1u)); assertTrue(feature.state.value.observationsAvailable)
            assertTrue(feature.state.value.snapshot.requests.isEmpty())
            val page = feature.lease(HistoryKind.Requests); assertEquals(1, feature.state.value.snapshot.requests.size)
            feature.acceptWindow(1, window(2u)); assertEquals(1, feature.state.value.snapshot.requests.size)
            withTimeout(2000) { while (feature.state.value.snapshot.requests.size != 2) delay(10) }
            page.close(); feature.acceptWindow(1, window(3u)); delay(350)
            assertEquals(2, feature.state.value.snapshot.requests.size)
            val reopened = feature.lease(HistoryKind.Requests); assertEquals(3, feature.state.value.snapshot.requests.size)
            feature.acceptWindow(1, window(4u)); feature.setForeground(false); delay(350)
            assertEquals(3, feature.state.value.snapshot.requests.size)
            feature.setForeground(true); assertEquals(4, feature.state.value.snapshot.requests.size)
            feature.acceptWindow(1, window(5u)); feature.bindSession(2); delay(350)
            assertTrue(feature.state.value.snapshot.requests.isEmpty()); assertFalse(feature.state.value.observationsAvailable)
            reopened.close(); collection.close()
        } finally { feature.close() }
    }

    @Test fun localActionsClearAndFirstWindowPublishImmediatelyAndCloseCancelsTail() = runBlocking {
        val feature = ObservabilityFeature(this, documents) {}
        feature.bindSession(1); feature.setForeground(true)
        feature.lease(HistoryKind.Logs).use {
            feature.appendLocalLogs(listOf(LogLine(0, "info", "local")))
            assertEquals(1, feature.state.value.snapshot.logs.size)
            feature.acceptLog(1, LogLine(1, "warn", "pending"))
            assertEquals(1, feature.state.value.snapshot.logs.size)
            feature.clear(HistoryKind.Logs); assertTrue(feature.state.value.snapshot.logs.isEmpty())
            feature.acceptLog(1, LogLine(2, "info", "after clear")); feature.close(); delay(350)
            assertTrue(feature.state.value.snapshot.logs.isEmpty())
        }
    }

    @Test fun projectionStopsWithinOneBoundedChunkWhenCancelled() {
        val source = List(5000) { HistoryLogEntry(it.toLong(), 0, "info", "line") }
        var checks = 0
        try { projectLogs(source, checkActive = { if (++checks == 3) throw CancellationException("cancelled") }); fail("continued") }
        catch (cancelled: CancellationException) { assertEquals("cancelled", cancelled.message) }
        assertEquals(3, checks)
    }
}
