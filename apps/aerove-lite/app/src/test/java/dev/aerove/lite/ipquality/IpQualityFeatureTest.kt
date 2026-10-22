package dev.aerove.lite.ipquality

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class IpQualityFeatureTest {
    private fun quality(ip: String) = IpQuality(ip, IpQualitySource.IdentMe, IpType.Residential)
    @Test fun slowSavedPrivacyReadMasksConfirmedIpUntilHiddenChoiceIsKnown() = runBlocking {
        for (savedHidden in listOf(true, false)) {
            val directory = Files.createTempDirectory("quality-privacy-read").toFile()
            try {
                val read = CompletableDeferred<SavedIpQuality>()
                var requests = 0
                val owner = IpQualityFeature(this, { requests++; quality(it) },
                    store = IpQualityStore(directory.resolve("state.json")), readSaved = { read.await() })
                assertTrue(owner.state.value.hidden)
                owner.bind("profile", true, "1.1.1.1", "SG")
                owner.setForeground(true)
                val lease = owner.observe()
                delay(10)
                assertTrue(owner.state.value.hidden)
                assertEquals("1.1.1.1", owner.state.value.ip)
                assertFalse(owner.state.value.loading)
                assertEquals(0, requests)
                read.complete(SavedIpQuality(hidden = savedHidden))
                withTimeout(1000) { owner.state.first { it.quality != null } }
                assertEquals(savedHidden, owner.state.value.hidden)
                assertEquals(1, requests)
                lease.close(); owner.close()
            } finally { directory.deleteRecursively() }
        }
    }
    @Test fun lastValidQualityOnlyBridgesLoadingAndIsRemovedWhenNewLookupFails() = runBlocking {
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val owner = IpQualityFeature(this, { ip ->
            if (++calls == 1) quality(ip) else { release.await(); throw IpQualityLookupException(emptyList()) }
        })
        owner.bind("profile", true, "1.1.1.1", "SG"); owner.setForeground(true); val lease = owner.observe()
        withTimeout(1000) { owner.state.first { it.quality != null } }
        owner.retry()
        assertTrue(owner.state.value.loading); assertNotNull(owner.state.value.quality)
        release.complete(Unit)
        withTimeout(1000) { owner.state.first { !it.loading } }
        assertNull(owner.state.value.quality)
        owner.bind("profile", true, null)
        assertTrue(owner.state.value.provisional); assertEquals("SG", owner.state.value.countryCode)
        lease.close(); owner.close()
    }
    @Test fun foregroundDemandCoalescesAndSuccessfulResultsStayCachedByIp() = runBlocking {
        var calls = 0
        val owner = IpQualityFeature(this, { calls++; quality(it) })
        owner.bind("profile", true, "1.1.1.1")
        val first = owner.observe(); val second = owner.observe()
        delay(10); assertEquals(0, calls)
        owner.setForeground(true)
        withTimeout(1000) { owner.state.first { it.quality != null } }
        assertEquals(1, calls)
        owner.setForeground(false); owner.setForeground(true)
        first.close(); second.close()
        val again = owner.observe(); delay(10); assertEquals(1, calls)
        owner.bind("profile", true, "8.8.8.8")
        withTimeout(1000) { owner.state.first { it.quality?.ip == "8.8.8.8" } }
        owner.bind("other-profile", true, "1.1.1.1"); assertEquals("1.1.1.1", owner.state.value.quality?.ip)
        assertEquals(2, calls)
        again.close(); owner.close()
    }

    @Test fun failedLookupCoolsForFiveMinutesAndExplicitRetryOverridesCooldown() = runBlocking {
        var calls = 0; var clock = 0L
        val owner = IpQualityFeature(this, { calls++; throw IpQualityLookupException(listOf(IpQualityFailure(IpQualitySource.IdentMe, IpQualitySourceStatus.RateLimited))) }, now = { clock })
        owner.bind("profile", true, "1.1.1.1"); owner.setForeground(true)
        var lease = owner.observe()
        withTimeout(1000) { owner.state.first { it.failures.isNotEmpty() } }
        lease.close(); lease = owner.observe(); delay(10); assertEquals(1, calls)
        clock = 299999; owner.setForeground(false); owner.setForeground(true); delay(10); assertEquals(1, calls)
        owner.retry(); withTimeout(1000) { owner.state.first { it.failures.isNotEmpty() } }; assertEquals(2, calls)
        clock += 300001; lease.close(); lease = owner.observe()
        withTimeout(1000) { owner.state.first { it.failures.isNotEmpty() } }; assertEquals(3, calls)
        assertNull(owner.state.value.quality)
        lease.close(); owner.close()
    }

    @Test fun backgroundCancelsAndResumeRestartsWithoutPublishingLateOldIp() = runBlocking {
        val old = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = IpQualityFeature(this, { ip -> calls++
            if (calls == 1) { old.complete(Unit); withContext(NonCancellable) { release.await() } }
            quality(ip)
        })
        owner.bind("profile", true, "1.1.1.1"); owner.setForeground(true); val lease = owner.observe()
        old.await(); owner.setForeground(false); owner.bind("profile", true, "8.8.8.8"); owner.setForeground(true)
        withTimeout(1000) { owner.state.first { it.quality?.ip == "8.8.8.8" } }
        release.complete(Unit); delay(10)
        assertEquals("8.8.8.8", owner.state.value.quality?.ip)
        lease.close(); owner.close()
    }

    @Test fun rememberedExitIsScopedProvisionalAndHiddenClearsSavedIdentity() = runBlocking {
        val directory = Files.createTempDirectory("quality-test").toFile()
        try {
            val file = directory.resolve("last.json"); val store = IpQualityStore(file)
            store.write(SavedIpQuality(lastExit = LastQualityExit("profile", true, "1.1.1.1", quality("1.1.1.1"))), 0)
            val owner = IpQualityFeature(this, { awaitCancellation() }, store = IpQualityStore(file))
            owner.bind("profile", true, null)
            withTimeout(1000) { owner.state.first { it.provisional } }
            assertEquals("1.1.1.1", owner.state.value.ip)
            owner.bind("different", true, null); assertNull(owner.state.value.ip)
            owner.bind("profile", false, null); assertNull(owner.state.value.ip)
            owner.bind("profile", true, null); owner.setHidden(true)
            assertTrue(owner.state.value.hidden); assertNull(owner.state.value.ip)
            withTimeout(1000) { while (!store.read().hidden) delay(5) }
            assertNull(store.read().lastExit)
            owner.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun storageRoundTripAndOutOfOrderSavesPreserveNewestPrivacyChoice() {
        val directory = Files.createTempDirectory("quality-store").toFile()
        try {
            val store = IpQualityStore(directory.resolve("state.json"))
            val saved = SavedIpQuality(lastExit = LastQualityExit("p", true, "1.1.1.1", quality("1.1.1.1")))
            store.write(saved, 1); assertEquals(saved, store.read())
            store.write(SavedIpQuality(hidden = true), 3); store.write(saved, 2)
            assertEquals(SavedIpQuality(hidden = true), store.read())
        } finally { directory.deleteRecursively() }
    }
}
