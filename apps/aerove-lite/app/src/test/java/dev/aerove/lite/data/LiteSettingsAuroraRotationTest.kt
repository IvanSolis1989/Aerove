package dev.aerove.lite.data

import dev.aerove.lite.*
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteSettingsAuroraRotationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = ZonedDateTime.parse("2026-10-22T05:00+09:00[Asia/Tokyo]")

    @Test fun rotationReadsTheLatestUserSettingsInsteadOfAnOldCallerSnapshot() = runBlocking<Unit> {
        val first = LiteSettingsStore(temporary.root)
        val second = LiteSettingsStore(temporary.root)
        first.save(LiteSettings(auroraRotationDay = 20261021L))
        val stale = first.read()
        val latest = stale.copy(
            material = MaterialTier.Frosted, themeColor = 0xFF123456L, colorStyle = ColorStyle.Tinted,
            themeColors = listOf(0xFF123456L), followSystemColor = true,
            editorFontSize = 20, editorLineWrap = true,
            auroraColors = listOf(0xFF010203L), mode = ProxyMode.Global, ipv6 = true,
            overrideDns = true, dnsServers = listOf("tls://8.8.8.8:853"),
            appFilterMode = AppFilterMode.Include, packages = setOf("com.example.one"), openLogs = true,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound),
        )
        second.save(latest)
        val changed = first.rotateAuroraForDay(now, CountingRandom())
        assertEquals(latest.copy(auroraSeed = latest.auroraSeed + 1, auroraRotationDay = 20261022L), changed)
        assertEquals(changed, LiteSettingsStore(temporary.root).read())
        assertFalse(changed.autoStart)
    }

    @Test fun concurrentDailyChecksAcrossStoreInstancesPersistOneSeedChange() = runBlocking<Unit> {
        val initial = LiteSettings(auroraSeed = 42L, auroraRotationDay = 20261021L)
        val store = LiteSettingsStore(temporary.root)
        store.save(initial)
        val random = CountingRandom()
        val results = coroutineScope {
            (0 until 8).map { async(Dispatchers.Default) { LiteSettingsStore(temporary.root).rotateAuroraForDay(now, random) } }.awaitAll()
        }
        val expected = initial.copy(auroraSeed = 43L, auroraRotationDay = 20261022L)
        assertTrue(results.all { it == expected })
        assertEquals(expected, store.read())
        assertEquals(1, random.draws.get())
    }

    @Test fun concurrentManualShufflesAreAtomicAndNeverLowerTheDayMarker() = runBlocking<Unit> {
        val initial = LiteSettings(auroraSeed = 10L, auroraRotationDay = 20261231L)
        val store = LiteSettingsStore(temporary.root)
        store.save(initial)
        val random = CountingRandom()
        val results = coroutineScope {
            (0 until 8).map { async(Dispatchers.Default) { LiteSettingsStore(temporary.root).shuffleAuroraWallpaper(now, random) } }.awaitAll()
        }
        assertEquals((11L..18L).toSet(), results.map { it.auroraSeed }.toSet())
        assertEquals(initial.copy(auroraSeed = 18L), store.read())
        assertEquals(8, random.draws.get())
    }

    @Test fun disabledCurrentPeriodAndClockRollbackDoNotRewriteOrDrawRandomness() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val random = CountingRandom()
        listOf(
            LiteSettings(aurora = false, auroraRotationDay = 0L),
            LiteSettings(auroraRotationDay = 20261022L),
            LiteSettings(auroraRotationDay = 20261231L),
        ).forEach { current ->
            store.save(current)
            val file = File(temporary.root, "settings/settings.json")
            file.writeText("\n${file.readText()}\n")
            val originalBytes = file.readBytes()
            assertEquals(current, store.rotateAuroraForDay(now, random))
            assertArrayEquals(originalBytes, file.readBytes())
            if (!current.aurora) {
                assertEquals(current, store.shuffleAuroraWallpaper(now, random))
                assertArrayEquals(originalBytes, file.readBytes())
            }
        }
        assertEquals(0, random.draws.get())
    }

    @Test fun internalRotationPreservesAuthorizedAutoStartButDoesNotBypassPublicSaveGate() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val initial = LiteSettings(autoStart = true, auroraRotationDay = 20261021L)
        store.save(initial, vpnAuthorized = true)
        val changed = store.rotateAuroraForDay(now, CountingRandom())
        assertTrue(changed.autoStart)
        assertEquals(initial.copy(auroraSeed = initial.auroraSeed + 1, auroraRotationDay = 20261022L), changed)
        val file = File(temporary.root, "settings/settings.json")
        val originalBytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(changed) } }
        assertArrayEquals(originalBytes, file.readBytes())
        val shuffled = store.shuffleAuroraWallpaper(now, CountingRandom())
        assertTrue(shuffled.autoStart)
        assertEquals(changed.auroraSeed + 1, shuffled.auroraSeed)
    }

    @Test fun firstInternalUpdateCannotEnableBootAutoStart() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val changed = store.rotateAuroraForDay(now, CountingRandom())
        assertFalse(changed.autoStart)
        assertEquals(changed, LiteSettingsStore(temporary.root).read())
    }

    @Test fun internalUpdatesStillRejectBadStoredNetworkSettingsWithoutWriting() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(auroraRotationDay = 20261021L))
        val file = File(temporary.root, "settings/settings.json")
        file.writeText(JSONObject(file.readText()).put("dnsServers", JSONArray(listOf("system"))).toString())
        val originalBytes = file.readBytes()
        val random = CountingRandom()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.rotateAuroraForDay(now, random) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.shuffleAuroraWallpaper(now, random) } }
        assertEquals(0, random.draws.get())
        assertArrayEquals(originalBytes, file.readBytes())
    }

    private class CountingRandom : Random() {
        val draws = AtomicInteger()
        override fun nextBits(bitCount: Int): Int = error("Expected nextInt(until)")
        override fun nextInt(until: Int): Int {
            check(until == 0x3ffffffe)
            draws.incrementAndGet()
            return 0
        }
    }
}
