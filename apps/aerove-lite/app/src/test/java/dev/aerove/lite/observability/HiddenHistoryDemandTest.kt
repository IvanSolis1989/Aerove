package dev.aerove.lite.observability

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HiddenHistoryDemandTest {
    private class Documents : HistoryDocuments {
        val release = CompletableDeferred<Unit>(); var saving = false; var saved = false
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) {
            saving = true; release.await(); check(isCurrent()); saved = true
        }
        override suspend fun deleteCreated(reference: String) = true
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    @Test fun hiddenApplicationLeaseCollectsCountsWithoutMaterializingRequests() = runBlocking<Unit> {
        val docs = Documents(); val feature = ObservabilityFeature(this, docs, {})
        val hidden = feature.lease(HistoryKind.Requests, visible = false)
        try {
            feature.bindSession(1); feature.setForeground(true)
            assertTrue(feature.observationsEnabled.value)
            val session = java.util.UUID.randomUUID().toString()
            feature.acceptWindow(1, ObservationWindow(session, 1u, 1u, 0u,
                listOf(ObservationEvent.Request(1u, RequestHistoryEntry("request", java.time.Instant.EPOCH,
                    "tcp", host = "example.com"))), activationCursor = 0u))
            assertEquals(1L, feature.state.value.snapshot.requestCount)
            assertTrue(feature.state.value.snapshot.requests.isEmpty())
            val page = feature.lease(HistoryKind.Requests)
            val cached = feature.state.value.snapshot.requests
            assertEquals(1, cached.size); page.close()
            feature.setForeground(false); assertFalse(feature.observationsEnabled.value)
            feature.setForeground(true); assertTrue(feature.observationsEnabled.value)
            assertSame(cached, feature.state.value.snapshot.requests)
            assertEquals(1L, feature.state.value.snapshot.requestCount)
            feature.acceptWindow(1, ObservationWindow(session, 1u, 2u, 0u,
                listOf(ObservationEvent.Request(2u, RequestHistoryEntry("second", java.time.Instant.EPOCH,
                    "tcp", host = "second.example"))), activationCursor = 0u))
            until { feature.state.value.snapshot.requestCount == 2L }
            assertSame(cached, feature.state.value.snapshot.requests)
            assertEquals(1, cached.size)
        } finally { hidden.close(); feature.close() }
    }
    @Test fun readPauseDoesNotCancelPendingDnsMutationOrExport() = runBlocking<Unit> {
        val docs = Documents(); val dns = CompletableDeferred<Unit>(); var cleared = false
        val feature = ObservabilityFeature(this, docs, { dns.await(); cleared = true })
        try {
            feature.bindSession(1); feature.setForeground(true)
            feature.flushDnsCache(); feature.requestLogExport()
            val request = feature.state.value.document!!; assertTrue(feature.documentLaunched(request.id))
            feature.documentResult(request.id, "document"); until { docs.saving && feature.state.value.clearingDns }
            feature.setForeground(false); dns.complete(Unit); docs.release.complete(Unit)
            until { !feature.state.value.exporting && !feature.state.value.clearingDns }
            assertTrue(cleared); assertTrue(docs.saved)
        } finally { dns.complete(Unit); docs.release.complete(Unit); feature.close() }
    }
}
