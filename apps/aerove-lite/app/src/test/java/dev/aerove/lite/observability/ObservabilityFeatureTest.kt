package dev.aerove.lite.observability

import dev.aerove.lite.LogLine
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ObservabilityFeatureTest {
    private class DiskDocuments : HistoryDocuments {
        val directory = Files.createTempDirectory("aerove-history-").toFile()
        var corrupt = false
        var entered: CompletableDeferred<Unit>? = null
        var hold: CompletableDeferred<Unit>? = null
        fun file(name: String) = File(directory, name)
        override suspend fun save(reference: String, lines: List<String>, isCurrent: () -> Boolean) {
            val target = file(reference)
            val digest = target.outputStream().use { writeHistoryExport(it, lines, isCurrent) }
            entered?.complete(Unit); hold?.await()
            if (corrupt) target.appendText("damaged")
            target.inputStream().use { verifyHistoryExport(it, digest, isCurrent) }
        }
        override suspend fun deleteCreated(reference: String): Boolean = file(reference).let { !it.exists() || it.delete() }
        override fun close() { directory.deleteRecursively() }
    }
    private suspend fun ObservabilityFeature.until(predicate: (ObservabilityState) -> Boolean) = withTimeout(3000) { state.first(predicate) }
    private fun window() = ObservationWindow(UUID.randomUUID().toString(), 1u, 1u, 0u,
        listOf(ObservationEvent.Request(1u, RequestHistoryEntry(UUID.randomUUID().toString(), Instant.EPOCH, "tcp", host = "example.test"))))

    @Test fun foregroundOwnerCollectsWithoutAnOpenHistoryPageAndBackgroundRetainsExistingHistory() = runBlocking {
        val feature = ObservabilityFeature(this, DiskDocuments()) {}
        try {
            feature.bindSession(1); feature.setForeground(true)
            val applicationLease = feature.lease(HistoryKind.Requests)
            val page = feature.lease(HistoryKind.Dns); page.close()
            assertTrue(feature.observationsEnabled.value)
            feature.acceptLog(1, LogLine(0, "info", "before first window")); feature.acceptWindow(1, window())
            assertEquals(1, feature.state.value.snapshot.requests.size)
            assertEquals(1, feature.state.value.snapshot.logs.size)
            feature.setForeground(false); assertFalse(feature.observationsEnabled.value)
            feature.acceptWindow(1, window()); assertEquals(1, feature.state.value.snapshot.requests.size)
            feature.setForeground(true); assertTrue(feature.observationsEnabled.value)
            assertEquals(1, feature.state.value.snapshot.requests.size)
            feature.bindSession(2); feature.acceptWindow(1, window())
            assertTrue(feature.state.value.snapshot.requests.isEmpty()); assertTrue(feature.state.value.snapshot.logs.isEmpty())
            applicationLease.close(); assertFalse(feature.observationsEnabled.value)
        } finally { feature.close() }
    }

    @Test fun exportWritesAndReadsBackAllRealLogsAndCorruptReadbackFailsAndCleansItsOwnDocument() = runBlocking {
        val documents = DiskDocuments(); val feature = ObservabilityFeature(this, documents) {}
        try {
            feature.bindSession(1); feature.setForeground(true)
            feature.acceptLog(1, LogLine(0, "info", "first 中文")); feature.acceptLog(1, LogLine(1, "error", "second"))
            feature.requestLogExport(); val first = feature.state.value.document!!
            assertTrue(feature.documentLaunched(first.id)); assertFalse(feature.documentLaunched(first.id))
            feature.documentResult(first.id, "good.txt")
            feature.until { !it.exporting && it.notice == HistoryNotice.Exported }
            assertEquals("1970-01-01T00:00:00Z [info] first 中文\n1970-01-01T00:00:00.001Z [error] second", documents.file("good.txt").readText())
            documents.corrupt = true
            feature.requestLogExport(); val second = feature.state.value.document!!
            feature.documentLaunched(second.id); feature.documentResult(second.id, "bad.txt")
            feature.until { !it.exporting && it.notice == HistoryNotice.Failed }
            assertFalse(documents.file("bad.txt").exists()); assertTrue(documents.file("good.txt").exists())
        } finally { feature.close() }
    }

    @Test fun cancellingAnExportAllowsImmediateRetryAndLatePickerCannotConsumeTheNewIntent() = runBlocking {
        val documents = DiskDocuments(); val feature = ObservabilityFeature(this, documents) {}
        try {
            feature.bindSession(1); feature.setForeground(true); feature.acceptLog(1, LogLine(0, "info", "kept"))
            documents.entered = CompletableDeferred(); documents.hold = CompletableDeferred()
            feature.requestLogExport(); val old = feature.state.value.document!!
            feature.documentLaunched(old.id); feature.documentResult(old.id, "cancelled.txt")
            withTimeout(3000) { documents.entered!!.await() }
            feature.cancelExport()
            feature.requestLogExport(); val retry = feature.state.value.document!!
            assertNotEquals(old.id, retry.id)
            documents.hold!!.complete(Unit); documents.entered = null; documents.hold = null
            feature.documentLaunched(retry.id); feature.documentResult(retry.id, "retry.txt")
            feature.until { !it.exporting && it.notice == HistoryNotice.Exported }
            withTimeout(3000) { while (documents.file("cancelled.txt").exists()) yield() }
            feature.requestLogExport(); val late = feature.state.value.document!!; feature.documentLaunched(late.id)
            feature.cancelExport(); feature.requestLogExport(); val current = feature.state.value.document!!
            documents.file("late.txt").writeText("picker created")
            feature.documentResult(late.id, "late.txt")
            withTimeout(3000) { while (documents.file("late.txt").exists()) yield() }
            assertEquals(current.id, feature.state.value.document!!.id)
            assertEquals(1, feature.state.value.snapshot.logs.size)
        } finally { feature.close() }
    }

    @Test fun switchingRuntimeCancelsCacheActionAndCannotReportOldSuccessForTheNewBinding() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>()
        val feature = ObservabilityFeature(this, DiskDocuments()) { entered.complete(Unit); hold.await() }
        try {
            feature.bindSession(1); feature.flushDnsCache(); withTimeout(3000) { entered.await() }
            assertTrue(feature.state.value.clearingDns)
            feature.bindSession(2); hold.complete(Unit); yield()
            assertFalse(feature.state.value.clearingDns)
            assertNotEquals(HistoryNotice.DnsCacheCleared, feature.state.value.notice)
            feature.flushDnsCache(); feature.until { it.notice == HistoryNotice.DnsCacheCleared }
        } finally { feature.close() }
    }
}
