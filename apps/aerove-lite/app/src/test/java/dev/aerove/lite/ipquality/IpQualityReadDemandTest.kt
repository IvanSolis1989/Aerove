package dev.aerove.lite.ipquality

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class IpQualityReadDemandTest {
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    private fun quality(ip: String) = IpQuality(ip, IpQualitySource.IdentMe, IpType.Residential)
    @Test fun focusDoesNotClearFiveMinuteFailureCooldown() = runBlocking<Unit> {
        var calls = 0; var monotonic = 0L
        val owner = IpQualityFeature(this, { calls++; error("offline") }, now = { monotonic })
        val lease = owner.observe()
        try {
            owner.bind("p", true, "1.1.1.1"); owner.setForeground(true); until { owner.state.value.failed }
            monotonic = 299999; repeat(3) { owner.readDemand(false); owner.readDemand(true) }; delay(20); assertEquals(1, calls)
            monotonic = 300000; owner.readDemand(false); owner.readDemand(true)
            until { calls == 2 && owner.state.value.failed }; assertNull(owner.state.value.quality)
        } finally { lease.close(); owner.close() }
    }
    @Test fun automaticLookupJoinsCanceledTailAndCannotPublishOldResult() = runBlocking<Unit> {
        var calls = 0; val release = CompletableDeferred<Unit>()
        val owner = IpQualityFeature(this, { ip ->
            if (++calls == 1) withContext(NonCancellable) { release.await() }; quality(ip)
        })
        val lease = owner.observe()
        try {
            owner.bind("p", true, "1.1.1.1"); owner.setForeground(true); until { calls == 1 }
            owner.readDemand(false); assertFalse(owner.state.value.loading); owner.readDemand(true)
            delay(20); assertEquals(1, calls); assertNull(owner.state.value.quality)
            release.complete(Unit); until { owner.state.value.quality != null }; assertEquals(2, calls)
        } finally { release.complete(Unit); lease.close(); owner.close() }
    }
    @Test fun explicitRetrySurvivesFocusAndObserverLossAndPersistsActualResult() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("quality-focus-manual").toFile()
        val store = IpQualityStore(dir.resolve("state.json"))
        val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = IpQualityFeature(this, { ip -> calls++; release.await(); quality(ip) }, store = store,
            readSaved = { SavedIpQuality() }, wallClockNow = { 1_000_000 })
        val lease = owner.observe()
        try {
            owner.bind("p", true, "1.1.1.1"); owner.setForeground(true); until { calls == 1 }
            owner.retry() // adopt the actual in-flight request, without a duplicate lookup
            owner.readDemand(false); lease.close(); release.complete(Unit)
            until { owner.state.value.quality != null && !owner.state.value.loading }
            withTimeout(4000) { while (withContext(Dispatchers.IO) { store.read().cache.isEmpty() }) delay(5) }
            assertEquals(1, calls); assertEquals("1.1.1.1", store.read().cache.single().quality.ip)
        } finally { release.complete(Unit); lease.close(); owner.quiesce(); owner.close(); dir.deleteRecursively() }
    }
    @Test fun focusResumeKeepsUnexpiredWallClockCacheAndOnlyRefreshesAfterThreeDays() = runBlocking<Unit> {
        val ttl = 3L * 24 * 60 * 60 * 1000; var wall = 1_000_000L; var calls = 0
        val owner = IpQualityFeature(this, { calls++; quality(it) }, wallClockNow = { wall }, awaitExpiry = { awaitCancellation() })
        val lease = owner.observe()
        try {
            owner.bind("p", true, "1.1.1.1"); owner.setForeground(true); until { owner.state.value.quality != null }
            wall += ttl - 1; owner.readDemand(false); owner.readDemand(true); delay(20); assertEquals(1, calls)
            wall++; owner.readDemand(false); owner.readDemand(true)
            until { calls == 2 && owner.state.value.quality != null }; assertEquals(2, calls)
        } finally { lease.close(); owner.close() }
    }
}
