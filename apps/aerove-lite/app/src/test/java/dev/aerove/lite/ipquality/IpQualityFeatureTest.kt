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

    @Test fun persistedIpCacheUsesWallClockThreeDayTtlAndRejectsFutureChecks() = runBlocking {
        val directory = Files.createTempDirectory("quality-wall-cache").toFile()
        try {
            val ip = "1.1.1.1"; val checkedAt = 1_000_000L; val ttl = 3L * 24 * 60 * 60 * 1000
            val q = quality(ip); val store = IpQualityStore(directory.resolve("cache.json"))
            store.write(SavedIpQuality(cache = listOf(
                IpQualityCheck(ip, q, checkedAt), IpQualityCheck("8.8.8.8", quality("8.8.8.8"), checkedAt - ttl),
                IpQualityCheck("9.9.9.9", quality("9.9.9.9"), checkedAt + ttl))), 1, checkedAt + ttl - 1)
            assertEquals(listOf(ip), store.read().cache.map { it.ip })
            var wall = checkedAt + ttl - 1; var calls = 0
            val fresh = IpQualityFeature(this, { calls++; quality(it) }, store = store, wallClockNow = { wall })
            fresh.bind("p", true, ip); fresh.setForeground(true); val lease = fresh.observe()
            withTimeout(1000) { fresh.state.first { it.quality != null || it.failed } }
            assertEquals(q, fresh.state.value.quality); assertEquals(0, calls)
            lease.close(); fresh.close()

            val expiredFile = directory.resolve("expired.json"); val expiredStore = IpQualityStore(expiredFile)
            expiredStore.write(SavedIpQuality(cache = listOf(IpQualityCheck(ip, q, checkedAt))), 1, checkedAt)
            wall = checkedAt + ttl; calls = 0
            val expired = IpQualityFeature(this, { calls++; quality(it) }, store = expiredStore, wallClockNow = { wall })
            expired.bind("p", true, ip); expired.setForeground(true); val expiredLease = expired.observe()
            withTimeout(1000) { expired.state.first { it.quality != null } }
            assertEquals(1, calls)
            withTimeout(1000) { while (expiredStore.read().cache.singleOrNull()?.checkedAtWallClock != wall) delay(5) }
            expiredLease.close(); expired.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun successfulLookupPersistsWallClockAndExplicitRetrySkipsFreshCache() = runBlocking {
        val directory = Files.createTempDirectory("quality-save-cache").toFile()
        try {
            val ip = "8.8.8.8"; var wall = 42_000L; var calls = 0
            val store = IpQualityStore(directory.resolve("state.json"))
            val owner = IpQualityFeature(this, { calls++; quality(it) }, store = store, wallClockNow = { wall })
            owner.bind("p", true, ip); owner.setForeground(true); val lease = owner.observe()
            withTimeout(1000) { owner.state.first { it.quality != null } }
            withTimeout(1000) { while (store.read().cache.isEmpty()) delay(5) }
            assertEquals(IpQualityCheck(ip, quality(ip), wall), store.read().cache.single())
            wall += 1_000; owner.retry()
            withTimeout(1000) { owner.state.first { !it.loading && it.quality?.ip == ip } }
            withTimeout(1000) { while (calls < 2 || store.read().cache.singleOrNull()?.checkedAtWallClock != wall) delay(5) }
            assertEquals(2, calls); lease.close(); owner.close()
        } finally { directory.deleteRecursively() }
    }

    @Test fun persistedSizeBudgetIsExactAtBothSidesOfOneMiB() {
        val directory = Files.createTempDirectory("quality-exact-budget").toFile()
        try {
            val ip = "1.1.1.1"; val now = System.currentTimeMillis(); val maxBytes = 1024 * 1024
            fun expectedSize(organization: String): Int {
                val encodedQuality = org.json.JSONObject().put("ip", ip).put("source", IpQualitySource.IdentMe.name)
                    .put("type", IpType.Residential.name).put("organization", organization).put("asn", null as Any?)
                    .put("proxy", false).put("vpn", false).put("tor", false).put("abuser", false)
                val row = org.json.JSONObject().put("ip", ip).put("checkedAtWallClock", now).put("quality", encodedQuality)
                return org.json.JSONObject().put("hidden", true).put("cache", org.json.JSONArray().put(row))
                    .toString().toByteArray(Charsets.UTF_8).size
            }
            for (targetSize in listOf(maxBytes - 1, maxBytes, maxBytes + 1, maxBytes + 2)) {
                var organizationSize = (targetSize - expectedSize("")).coerceAtLeast(0)
                while (expectedSize("x".repeat(organizationSize)) < targetSize) organizationSize++
                while (expectedSize("x".repeat(organizationSize)) > targetSize) organizationSize--
                val organization = "x".repeat(organizationSize)
                assertEquals(targetSize, expectedSize(organization))
                val file = directory.resolve("$targetSize.json")
                val store = IpQualityStore(file)
                val quality = IpQuality(ip, IpQualitySource.IdentMe, IpType.Residential, organization = organization)
                store.write(SavedIpQuality(hidden = true,
                    cache = listOf(IpQualityCheck(ip, quality, now))), targetSize.toLong(), now)
                val saved = store.read()
                assertTrue("hidden preference at $targetSize bytes", saved.hidden)
                val emptyStateSize = org.json.JSONObject().put("hidden", true).put("cache", org.json.JSONArray())
                    .toString().toByteArray(Charsets.UTF_8).size
                assertEquals("stored byte count at $targetSize bytes",
                    if (targetSize <= maxBytes) targetSize else emptyStateSize, file.length().toInt())
                if (targetSize <= maxBytes) {
                    assertEquals(1, saved.cache.size)
                    assertEquals(organization, saved.cache.single().quality.organization)
                } else assertTrue("over-budget row evicted at $targetSize bytes", saved.cache.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun visibleSameIpExpiresOnceAndRefreshesWithoutRebinding() = runBlocking {
        val firstExpiry = CompletableDeferred<Long>(); val secondExpiry = CompletableDeferred<Long>()
        val releaseFirst = CompletableDeferred<Unit>(); val releaseSecond = CompletableDeferred<Unit>()
        val secondLookup = CompletableDeferred<Unit>(); val releaseLookup = CompletableDeferred<Unit>()
        var wall = 1_000_000L; var calls = 0; var expiries = 0
        val ip = "1.1.1.1"
        val ttl = 3L * 24 * 60 * 60 * 1000
        val owner = IpQualityFeature(this, { current ->
            if (++calls == 1) quality(current) else { secondLookup.complete(Unit); releaseLookup.await(); quality(current) }
        }, wallClockNow = { wall }, awaitExpiry = { waitMs ->
            if (++expiries == 1) { firstExpiry.complete(waitMs); releaseFirst.await() }
            else { secondExpiry.complete(waitMs); releaseSecond.await() }
        })
        owner.bind("p", true, ip); owner.setForeground(true); val lease = owner.observe()
        withTimeout(1000) { owner.state.first { it.quality != null } }
        assertEquals(ttl, firstExpiry.await())
        wall += ttl
        owner.bind("p", true, ip)
        releaseFirst.complete(Unit)
        withTimeout(1000) { secondLookup.await() }
        assertEquals(2, calls)
        assertNull(owner.state.value.quality)
        releaseLookup.complete(Unit)
        withTimeout(1000) { owner.state.first { it.quality?.ip == ip } }
        assertEquals(ttl, secondExpiry.await())
        owner.setForeground(false)
        lease.close(); owner.close(); releaseSecond.complete(Unit)
        Unit
    }

    @Test fun boundedStoreEvictsCacheCopiesWithoutLosingHiddenOrFullLastExit() {
        val directory = Files.createTempDirectory("quality-size-bound").toFile()
        try {
            val now = System.currentTimeMillis(); val ip = "1.1.1.1"
            val large = IpQuality(ip, IpQualitySource.IdentMe, IpType.Residential, organization = "x".repeat(610_000))
            val duplicateStoreFile = directory.resolve("duplicate.json")
            val duplicateStore = IpQualityStore(duplicateStoreFile)
            duplicateStore.write(SavedIpQuality(hidden = false,
                lastExit = LastQualityExit("p", true, ip, large),
                cache = listOf(IpQualityCheck(ip, large, now))), 1, now)
            assertTrue(duplicateStoreFile.length() <= 1024 * 1024)
            val duplicateRead = duplicateStore.read()
            assertFalse(duplicateRead.hidden)
            assertEquals(large, duplicateRead.lastExit?.quality)
            assertTrue(duplicateRead.cache.isEmpty())

            val hiddenStoreFile = directory.resolve("hidden.json")
            val hiddenStore = IpQualityStore(hiddenStoreFile)
            val other = large.copy(ip = "8.8.8.8")
            hiddenStore.write(SavedIpQuality(hidden = true, cache = listOf(
                IpQualityCheck(ip, large, now), IpQualityCheck(other.ip, other, now - 1))), 1, now)
            assertTrue(hiddenStoreFile.length() <= 1024 * 1024)
            val hiddenRead = hiddenStore.read()
            assertTrue(hiddenRead.hidden)
            assertEquals(1, hiddenRead.cache.size)
            assertEquals(large.organization, hiddenRead.cache.single().quality.organization)
        } finally { directory.deleteRecursively() }
    }

    @Test fun backgroundCancelsAndResumeRestartsWithoutPublishingLateOldIp() = runBlocking {
        val old = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val owner = IpQualityFeature(this, { ip -> calls++
            if (calls == 1) { old.complete(Unit); withContext(NonCancellable) { release.await() } }
            quality(ip)
        })
        owner.bind("profile", true, "1.1.1.1"); owner.setForeground(true); val lease = owner.observe()
        try {
            old.await(); owner.setForeground(false); owner.bind("profile", true, "8.8.8.8"); owner.setForeground(true)
            delay(10); assertEquals(1, calls); assertNull(owner.state.value.quality)
            release.complete(Unit)
            withTimeout(1000) { owner.state.first { it.quality?.ip == "8.8.8.8" } }
            assertEquals(2, calls); assertEquals("8.8.8.8", owner.state.value.quality?.ip)
        } finally { release.complete(Unit); lease.close(); owner.close() }
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
