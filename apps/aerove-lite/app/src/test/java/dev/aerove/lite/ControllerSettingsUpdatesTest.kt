package dev.aerove.lite

import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSettingsUpdatesTest {
    private val now = ZonedDateTime.parse("2026-10-22T05:00+09:00[Asia/Tokyo]")

    @Test fun queuedUserEditsRebaseWithoutLosingEarlierChanges() = runTest {
        val fake = Harness()
        fake.updates.load()
        val baseline = fake.shown
        fake.saveGate = CompletableDeferred()
        val first = launch { fake.updates.save(baseline, baseline.copy(colorStyle = ColorStyle.Soft)) }
        runCurrent()
        val second = launch { fake.updates.save(baseline, baseline.copy(dnsServers = listOf("tls://8.8.8.8:853"))) }
        runCurrent()
        assertEquals(1, fake.saveCalls)
        fake.saveGate!!.complete(Unit)
        first.join()
        second.join()
        assertEquals(ColorStyle.Soft, fake.shown.colorStyle)
        assertEquals(listOf("tls://8.8.8.8:853"), fake.shown.dnsServers)
        assertEquals(fake.disk, fake.shown)
        assertEquals(1, fake.readCalls)
    }

    @Test fun automaticUpdatePreferenceAndQueuedUserFieldsPreserveEachOtherInEitherOrder() = runTest {
        for (preferenceFirst in listOf(true, false)) {
            val fake = Harness()
            fake.updates.load()
            val baseline = fake.shown
            val preference = baseline.copy(autoCheckUpdate = false)
            val userFields = baseline.copy(colorStyle = ColorStyle.Soft, dnsServers = listOf("tls://8.8.8.8:853"))
            val requests = if (preferenceFirst) listOf(preference, userFields) else listOf(userFields, preference)
            fake.saveGate = CompletableDeferred()
            val first = launch { fake.updates.save(baseline, requests[0]) }
            runCurrent()
            val second = launch { fake.updates.save(baseline, requests[1]) }
            runCurrent()
            assertEquals(1, fake.saveCalls)
            fake.saveGate!!.complete(Unit)
            first.join()
            second.join()
            assertEquals(baseline.copy(autoCheckUpdate = false, colorStyle = ColorStyle.Soft,
                dnsServers = userFields.dnsServers), fake.disk)
            assertEquals(fake.disk, fake.shown)
            assertEquals(2, fake.saveCalls)
            assertEquals(1, fake.readCalls)
        }
    }

    @Test fun acceptedUserDeltaCanChangeEveryCurrentContractField() = runTest {
        val fake = Harness()
        fake.updates.load()
        val baseline = fake.shown
        val requested = LiteSettings(
            material = MaterialTier.Solid, aurora = false, colorMode = ColorMode.Dark,
            themeColor = 0xFF123456L, colorStyle = ColorStyle.Monochrome,
            themeColors = listOf(0xFF123456L), followSystemColor = true,
            editorFontSize = 20, editorLineWrap = true,
            serviceOrder = serviceTargetIds.reversed(), disabledServices = setOf("netflix"),
            auroraColors = listOf(0xFF010203L), auroraSeed = 42L, auroraRotationDay = 20261231L,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound),
            mode = ProxyMode.Direct, ipv6 = true, overrideDns = true,
            dnsServers = listOf("tls://8.8.8.8:853"), appFilterMode = AppFilterMode.Include,
            packages = setOf("com.example.one"), autoStart = true, autoCheckUpdate = false, openLogs = true,
        )
        fake.updates.save(baseline, requested)
        assertEquals(requested, fake.disk)
        assertEquals(requested, fake.shown)
        assertTrue(fake.restartAllowed.last())
    }

    @Test fun queuedAutomaticCheckSkipsStoreWhenBackgroundEligibilityExpires() = runTest {
        val fake = Harness()
        fake.updates.load()
        fake.saveGate = CompletableDeferred()
        val baseline = fake.shown
        val save = launch { fake.updates.save(baseline, baseline.copy(colorStyle = ColorStyle.Soft)) }
        runCurrent()
        var background = true
        val rotation = launch { fake.updates.rotate(now) { background } }
        runCurrent()
        background = false
        fake.saveGate!!.complete(Unit)
        save.join()
        rotation.join()
        assertEquals(0, fake.rotateCalls)
        assertEquals(ColorStyle.Soft, fake.shown.colorStyle)
    }

    @Test fun userSaveQueuedBehindRotationKeepsTheNewSeedAndLatestUserFields() = runTest {
        val fake = Harness()
        fake.updates.load()
        val baseline = fake.shown
        fake.rotateGate = CompletableDeferred()
        val rotation = launch { fake.updates.rotate(now) { true } }
        runCurrent()
        val requested = baseline.copy(themeColor = 0xFF123456L,
            themeColors = baseline.themeColors + 0xFF123456L,
            serviceOrder = serviceTargetIds.reversed(), disabledServices = setOf("spotify"))
        val save = launch { fake.updates.save(baseline, requested) }
        runCurrent()
        assertEquals(0, fake.saveCalls)
        fake.rotateGate!!.complete(Unit)
        rotation.join()
        save.join()
        assertNotEquals(baseline.auroraSeed, fake.shown.auroraSeed)
        assertEquals(20261022L, fake.shown.auroraRotationDay)
        assertEquals(0xFF123456L, fake.shown.themeColor)
        assertEquals(requested.themeColors, fake.shown.themeColors)
        assertEquals(requested.serviceOrder, fake.shown.serviceOrder)
        assertEquals(requested.disabledServices, fake.shown.disabledServices)
        assertEquals(fake.disk, fake.shown)
        assertEquals(1, fake.rotateCalls)
        assertEquals(1, fake.saveCalls)
    }

    @Test fun lateAutomaticResultNeverChangesForegroundWallpaperEvenAfterAnotherSave() = runTest {
        val fake = Harness()
        fake.updates.load()
        val original = fake.shown
        var background = true
        fake.rotateGate = CompletableDeferred()
        val rotation = launch { fake.updates.rotate(now) { background } }
        runCurrent()
        background = false
        fake.rotateGate!!.complete(Unit)
        rotation.join()
        assertNotEquals(original.auroraSeed, fake.disk.auroraSeed)
        assertEquals(original, fake.shown)
        fake.updates.save(original, original.copy(colorStyle = ColorStyle.Tinted))
        assertEquals(ColorStyle.Tinted, fake.shown.colorStyle)
        assertEquals(original.auroraSeed, fake.shown.auroraSeed)
        assertEquals(original.auroraRotationDay, fake.shown.auroraRotationDay)
        assertNotEquals(original.auroraSeed, fake.disk.auroraSeed)
        background = true
        fake.updates.rotate(now) { background }
        assertEquals(fake.disk, fake.shown)
        assertEquals(1, fake.rotateCalls)
    }

    @Test fun cancelingInFlightCheckStillRemembersItsAtomicDiskResult() = runTest {
        val fake = Harness()
        fake.updates.load()
        val original = fake.shown
        var background = true
        fake.rotateGate = CompletableDeferred()
        val rotation = launch { fake.updates.rotate(now) { background } }
        runCurrent()
        background = false
        rotation.cancel()
        fake.rotateGate!!.complete(Unit)
        rotation.join()
        val diskSeed = fake.disk.auroraSeed
        fake.updates.save(original, original.copy(openLogs = true))
        assertEquals(diskSeed, fake.disk.auroraSeed)
        assertEquals(original.auroraSeed, fake.shown.auroraSeed)
        assertTrue(fake.shown.openLogs)
    }

    @Test fun manualShuffleUsesLatestDiskStateAndCanPublishInForeground() = runTest {
        val fake = Harness()
        fake.updates.load()
        var background = true
        fake.rotateGate = CompletableDeferred()
        val rotation = launch { fake.updates.rotate(now) { background } }
        runCurrent()
        background = false
        fake.rotateGate!!.complete(Unit)
        rotation.join()
        val rotated = fake.disk
        fake.updates.shuffle()
        assertNotEquals(rotated.auroraSeed, fake.shown.auroraSeed)
        assertEquals(fake.disk, fake.shown)
        assertFalse(fake.restartAllowed.last())
    }

    @Test fun disabledAndAlreadyCurrentChecksDoNotAddStoreReadsOrWrites() = runTest {
        val disabled = Harness(LiteSettings(aurora = false))
        disabled.updates.load()
        disabled.updates.rotate(now) { true }
        disabled.updates.shuffle()
        assertEquals(0, disabled.rotateCalls)
        assertEquals(0, disabled.shuffleCalls)
        val current = Harness(LiteSettings(auroraRotationDay = 20261022L))
        current.updates.load()
        repeat(3) { current.updates.rotate(now) { true } }
        assertEquals(1, current.readCalls)
        assertEquals(0, current.rotateCalls)
        assertEquals(0, current.saveCalls)
    }

    @Test fun closeDuringAtomicWritePreventsPublicationAndNewMutations() = runTest {
        val fake = Harness()
        fake.updates.load()
        val original = fake.shown
        fake.rotateGate = CompletableDeferred()
        val rotation = launch { fake.updates.rotate(now) { true } }
        runCurrent()
        fake.updates.close()
        fake.rotateGate!!.complete(Unit)
        rotation.join()
        fake.updates.shuffle()
        fake.updates.save(original, original.copy(openLogs = true))
        assertEquals(original, fake.shown)
        assertEquals(0, fake.saveCalls)
        assertEquals(0, fake.shuffleCalls)
    }

    @Test fun saveFailurePropagatesWithoutPublishingOrBypassingTheDelegate() = runTest {
        val fake = Harness()
        fake.updates.load()
        val original = fake.shown
        fake.failSave = true
        try {
            fake.updates.save(original, original.copy(autoStart = true, autoCheckUpdate = false))
            fail("Expected authorization failure")
        } catch (expected: IllegalArgumentException) {
            assertEquals("VPN authorization", expected.message)
        }
        assertEquals(1, fake.saveCalls)
        assertEquals(original, fake.disk)
        assertEquals(original, fake.shown)
        assertEquals(listOf(false), fake.restartAllowed)
        fake.failSave = false
        fake.updates.save(original, original.copy(colorMode = ColorMode.Dark))
        assertEquals(ColorMode.Dark, fake.shown.colorMode)
        assertTrue(fake.shown.autoCheckUpdate)
        assertEquals(fake.disk, fake.shown)
    }

    private inner class Harness(initial: LiteSettings = LiteSettings(auroraRotationDay = 20261021L)) {
        var disk = initial
        var shown = initial
        var readCalls = 0
        var saveCalls = 0
        var rotateCalls = 0
        var shuffleCalls = 0
        var saveGate: CompletableDeferred<Unit>? = null
        var rotateGate: CompletableDeferred<Unit>? = null
        var failSave = false
        val restartAllowed = mutableListOf<Boolean>()
        val updates = ControllerSettingsUpdates(
            read = { readCalls++; disk },
            save = { requested ->
                saveCalls++
                saveGate?.await()
                require(!failSave) { "VPN authorization" }
                requested.normalized().also { disk = it }
            },
            rotate = { date ->
                rotateCalls++
                rotateGate?.await()
                rotateAuroraForDay(disk, date, Random(7)).also { disk = it }
            },
            shuffle = {
                shuffleCalls++
                shuffleAuroraWallpaper(disk, now, Random(9)).also { disk = it }
            },
            current = { shown },
            publish = { saved, restart -> shown = saved; restartAllowed += restart },
        )
    }
}
