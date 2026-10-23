package dev.aerove.lite.observability

import dev.aerove.lite.LogLine
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LocalHistoryLogsTest {
    private val documents = object : HistoryDocuments {
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) = error("No export requested")
        override suspend fun deleteCreated(reference: String) = error("No document requested")
    }
    private fun samples(count: Int = 1000) = List(count) { LogLine(it.toLong(), "info", "[$it] explicit developer log test") }

    @Test fun explicitDeveloperBatchWorksWithoutRuntimeAndDoesNotCreateTelemetryOrRequestData() = runBlocking<Unit> {
        val feature = ObservabilityFeature(this, documents) { error("No cache flush requested") }
        try {
            assertTrue(feature.state.value.snapshot.logs.isEmpty())
            feature.appendLocalLogs(samples())
            val snapshot = feature.state.value.snapshot
            assertEquals(1000, snapshot.logs.size)
            assertEquals("[0] explicit developer log test", snapshot.logs.first().message)
            assertEquals("[999] explicit developer log test", snapshot.logs.last().message)
            assertEquals((1L..1000L).toList(), snapshot.logs.map { it.id })
            assertTrue(snapshot.requests.isEmpty()); assertTrue(snapshot.dns.isEmpty()); assertFalse(snapshot.truncated)
            assertFalse(feature.observationsEnabled.value)
            feature.setForeground(false); feature.appendLocalLogs(listOf(LogLine(1000, "info", "explicit app action")))
            assertEquals(1001, feature.state.value.snapshot.logs.size)
            assertFalse(feature.observationsEnabled.value)
        } finally { feature.close() }
    }

    @Test fun coreAndLocalLogsUseOneBoundedRingWithoutChangingSessionCursorRules() {
        val repository = HistoryRepository(); repository.bindSession(1)
        val runtime = UUID.randomUUID().toString(); val id = UUID.randomUUID().toString()
        repository.accept(1, ObservationWindow(runtime, 1u, 1u, 0u, listOf(
            ObservationEvent.Request(1u, RequestHistoryEntry(id, Instant.EPOCH, "tcp", host = "real.test")))))
        val revision = repository.snapshot().sessionRevision
        repository.appendLocalLogs(samples(4000))
        repeat(1001) { assertTrue(repository.acceptLog(1, LogLine(it.toLong(), "debug", "core $it"))) }
        val logs = repository.snapshot().logs
        assertEquals(5000, logs.size); assertEquals(2L, logs.first().id); assertEquals(5001L, logs.last().id)
        assertTrue(repository.accept(1, ObservationWindow(runtime, 1u, 2u, 0u, listOf(ObservationEvent.Traffic(2u, id, 7u, 9u)))))
        assertEquals(7uL, repository.snapshot().requests.single().upload); assertFalse(repository.snapshot().truncated)
        repository.accept(1, ObservationWindow(runtime, 2u, 80u, 0u, emptyList()))
        assertEquals(logs, repository.snapshot().logs); assertEquals(revision, repository.snapshot().sessionRevision)
        repository.bindSession(2)
        assertTrue(repository.snapshot().logs.isEmpty()); assertTrue(repository.snapshot().requests.isEmpty())
        assertFalse(repository.acceptLog(1, LogLine(0, "info", "stale core")))
    }

    @Test fun batchAndWsIngressAreSerializedAndLocalBatchKeepsItsOrder() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val feature = ObservabilityFeature(scope, documents) {}
        try {
            feature.bindSession(1); feature.setForeground(true)
            val start = CompletableDeferred<Unit>()
            val local = async(Dispatchers.Default) { start.await(); feature.appendLocalLogs(samples()) }
            val core = async(Dispatchers.Default) { start.await(); repeat(1000) { feature.acceptLog(1, LogLine(it.toLong(), "debug", "core $it")) } }
            start.complete(Unit); local.await(); core.await()
            val logs = feature.state.value.snapshot.logs
            assertEquals(2000, logs.size)
            assertEquals((1L..2000L).toList(), logs.map { it.id })
            val indexes = logs.indices.filter { logs[it].message.endsWith("explicit developer log test") }
            assertEquals(1000, indexes.size); assertEquals((indexes.first()..indexes.first() + 999).toList(), indexes)
            assertEquals(samples().map { it.message }, indexes.map { logs[it].message })
        } finally { feature.close(); scope.cancel() }
    }

    @Test fun staleExplicitIntentAndClosedOwnerCannotAppendAfterClear() = runBlocking<Unit> {
        val feature = ObservabilityFeature(this, documents) {}
        var current = false
        try {
            feature.appendLocalLogs(samples()) { current }
            assertTrue(feature.state.value.snapshot.logs.isEmpty())
            current = true; feature.appendLocalLogs(samples()) { current }
            assertEquals(1000, feature.state.value.snapshot.logs.size)
            feature.clear(HistoryKind.Logs); current = false
            feature.appendLocalLogs(samples()) { current }
            assertTrue(feature.state.value.snapshot.logs.isEmpty())
            feature.close(); feature.appendLocalLogs(samples())
            assertTrue(feature.state.value.snapshot.logs.isEmpty())
        } finally { feature.close() }
    }
}
