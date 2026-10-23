package dev.aerove.lite.ipquality

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class IpQualityResetTest {
    private fun saved() = SavedIpQuality(lastExit = LastQualityExit("profile", true, "1.1.1.1"))

    @Test fun exactStoreClearRemovesOwnedTemporaryAndRejectsLateWrites() {
        val directory = Files.createTempDirectory("ip-reset-store").toFile()
        try {
            val file = directory.resolve("last-exit.json")
            val store = IpQualityStore(file)
            store.write(saved(), 1)
            val temporary = directory.resolve("last-exit.json.tmp").apply { writeText("owned temporary") }
            val unknown = directory.resolve("unrelated.json").apply { writeText("retain") }
            store.clearUserState()
            store.write(saved(), Long.MAX_VALUE)
            assertFalse(file.exists()); assertFalse(temporary.exists())
            assertEquals(SavedIpQuality(), IpQualityStore(file).read())
            assertEquals("retain", unknown.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun terminalClearJoinsNonCancellableLookupAndDiscardsItsLateResult() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("ip-reset-lookup").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = IpQualityStore(directory.resolve("last-exit.json"))
        val feature = IpQualityFeature(this, { ip ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            IpQuality(ip, IpQualitySource.IdentMe, IpType.Residential)
        }, store = store)
        try {
            feature.bind("profile", true, "1.1.1.1"); feature.setForeground(true)
            val lease = feature.observe()
            withTimeout(5000) { entered.await() }
            val reset = async { feature.clearUserState() }
            yield()
            assertFalse(reset.isCompleted)
            release.complete(Unit); withTimeout(5000) { reset.await() }
            assertEquals(IpQualityState(), feature.state.value)
            assertFalse(directory.resolve("last-exit.json").exists())
            assertTrue(runCatching { feature.bind("profile", true, "1.1.1.1") }.isFailure)
            assertTrue(runCatching { feature.resumeAfterMaintenance() }.isFailure)
            lease.close()
        } finally { release.complete(Unit); feature.close(); directory.deleteRecursively() }
    }

    @Test fun quiesceJoinsInitialReadAndRollbackResumeLoadsCurrentStore() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("ip-reset-read").toFile()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var reads = 0
        val store = IpQualityStore(directory.resolve("last-exit.json"))
        val feature = IpQualityFeature(this, { awaitCancellation() }, store = store, readSaved = {
            if (++reads == 1) { entered.complete(Unit); withContext(NonCancellable) { release.await() }; saved() }
            else SavedIpQuality(hidden = true)
        })
        try {
            withTimeout(5000) { entered.await() }
            val paused = async { feature.quiesce() }
            yield(); assertFalse(paused.isCompleted)
            release.complete(Unit); paused.await()
            assertNull(feature.state.value.ip)
            feature.resumeAfterMaintenance()
            withTimeout(5000) { while (reads != 2) yield() }
            assertTrue(feature.state.value.hidden)
            assertNull(feature.state.value.ip)
        } finally { release.complete(Unit); feature.close(); directory.deleteRecursively() }
    }

    @Test fun fileShapeFailurePropagatesAndNeverResumesClearedFeature() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("ip-reset-failure").toFile()
        val file = directory.resolve("last-exit.json").apply { mkdir() }
        val unknown = file.resolve("keep").apply { writeText("retain") }
        val feature = IpQualityFeature(this, { awaitCancellation() }, store = IpQualityStore(file))
        try {
            assertTrue(runCatching { feature.clearUserState() }.isFailure)
            assertEquals(IpQualityState(), feature.state.value)
            assertEquals("retain", unknown.readText())
            assertTrue(runCatching { feature.resumeAfterMaintenance() }.isFailure)
        } finally { feature.close(); directory.deleteRecursively() }
    }
}
