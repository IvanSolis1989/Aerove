package dev.aerove.lite

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AuroraRotationPolicyTest {
    private val zone = ZoneId.of("Asia/Tokyo")

    @Test fun fiveAmPeriodsCrossMonthYearAndLeapDayBoundaries() {
        val cases = mapOf(
            "2026-10-18T04:59:59.999" to 20261017L,
            "2026-10-18T05:00" to 20261018L,
            "2026-10-18T23:59" to 20261018L,
            "2027-01-01T04:59" to 20261231L,
            "2027-01-01T05:00" to 20270101L,
            "2028-03-01T04:59" to 20280229L,
            "2028-03-01T05:00" to 20280301L,
            "2027-03-01T04:59" to 20270228L,
        )
        cases.forEach { (local, day) ->
            val now = ZonedDateTime.parse("${local}+09:00[Asia/Tokyo]")
            assertEquals(local, day, auroraRotationDayOf(now))
            assertEquals(day, auroraRotationDayOf(now.toInstant().atZone(zone)))
        }
    }

    @Test fun oneUpdateChangesOnlySeedAndDayAndDeduplicatesTheWholePeriod() {
        val original = LiteSettings(
            material = MaterialTier.Solid,
            auroraColors = listOf(0xFF65BDD2L, 0xFFC589CAL),
            auroraSeed = 271828L,
            auroraRotationDay = 20261017L,
            themeColor = 0xFF123456L,
            colorStyle = ColorStyle.Monochrome,
            dnsServers = listOf("tls://8.8.8.8:853"),
            openLogs = true,
        )
        val random = FixedRandom(7)
        val changed = rotateAuroraForDay(original, date("2026-10-18T05:00"), random)
        assertEquals(271836L, changed.auroraSeed)
        assertEquals(listOf(0x3ffffffe), random.bounds)
        assertEquals(20261018L, changed.auroraRotationDay)
        assertEquals(original, changed.copy(auroraSeed = original.auroraSeed, auroraRotationDay = original.auroraRotationDay))
        listOf("2026-10-18T05:00", "2026-10-19T04:59", "2026-10-17T23:00").forEach { local ->
            assertSame(changed, rotateAuroraForDay(changed, date(local), random))
        }
        assertEquals(1, random.bounds.size)
    }

    @Test fun auroraEnablementIsIndependentOfAllThreeMaterialTiers() {
        MaterialTier.entries.forEach { material ->
            val original = LiteSettings(material = material, aurora = false, auroraRotationDay = 20261018L)
            val random = FixedRandom(0)
            val now = date("2027-03-12T10:00")
            assertSame(original, rotateAuroraForDay(original, now, random))
            assertSame(original, shuffleAuroraWallpaper(original, now, random))
            assertTrue(random.bounds.isEmpty())
            val enabled = original.copy(aurora = true)
            val changed = rotateAuroraForDay(enabled, now, random)
            assertEquals(enabled.auroraSeed + 1, changed.auroraSeed)
            assertEquals(20270312L, changed.auroraRotationDay)
            assertEquals(material, changed.material)
            assertSame(changed, rotateAuroraForDay(changed, now, random))
        }
    }

    @Test fun manualShuffleCountsItsPeriodWithoutLoweringTheMarker() {
        val original = LiteSettings(auroraRotationDay = 20261231L)
        val now = date("2027-01-01T04:00")
        val first = shuffleAuroraWallpaper(original, now, FixedRandom(3))
        val second = shuffleAuroraWallpaper(first, now, FixedRandom(4))
        assertNotEquals(original.auroraSeed, first.auroraSeed)
        assertNotEquals(first.auroraSeed, second.auroraSeed)
        assertEquals(20261231L, second.auroraRotationDay)
        assertEquals(original.auroraColors, second.auroraColors)
        assertSame(second, rotateAuroraForDay(second, now))
        val next = rotateAuroraForDay(second, date("2027-01-01T05:00"), FixedRandom(0))
        assertEquals(20270101L, next.auroraRotationDay)
        assertEquals(20270101L, shuffleAuroraWallpaper(next, date("2026-12-30T00:00")).auroraRotationDay)
    }

    @Test fun seedWrapUsesTheFullPositiveModuloAndRandomBound() {
        val random = FixedRandom(0)
        assertEquals(0L, shuffleAuroraWallpaper(LiteSettings(auroraSeed = 0x7ffffffeL), date("2026-10-18T05:00"), random).auroraSeed)
        assertEquals(0x7ffffffeL, shuffleAuroraWallpaper(LiteSettings(auroraSeed = -2L), date("2026-10-18T05:00"), random).auroraSeed)
        val highestDraw = FixedRandom(0x3ffffffd)
        val changed = shuffleAuroraWallpaper(LiteSettings(auroraSeed = 0L), date("2026-10-18T05:00"), highestDraw)
        assertEquals(0x3ffffffeL, changed.auroraSeed)
    }

    private fun date(local: String): ZonedDateTime = java.time.LocalDateTime.parse(local).atZone(zone)

    private class FixedRandom(private val value: Int) : Random() {
        val bounds = mutableListOf<Int>()
        override fun nextBits(bitCount: Int): Int = error("Expected nextInt(until)")
        override fun nextInt(until: Int): Int {
            require(value in 0 until until)
            bounds += until
            return value
        }
    }
}
