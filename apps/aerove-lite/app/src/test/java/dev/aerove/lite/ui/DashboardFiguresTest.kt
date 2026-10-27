package dev.aerove.lite.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFiguresTest {
    @Test fun trafficStepsBy1024AndKeepsOneDecimalWithoutTrailingZeros() {
        val expected = listOf(0L to ("0" to "B"), 512L to ("512" to "B"), 1023L to ("1023" to "B"), 1024L to ("1" to "KB"),
            1280L to ("1.3" to "KB"), 1485L to ("1.5" to "KB"), 1536L to ("1.5" to "KB"), 10_240L to ("10" to "KB"),
            1_048_575L to ("1024" to "KB"), 1_048_576L to ("1" to "MB"), 5L shl 40 to ("5" to "TB"), 2048L shl 40 to ("2048" to "TB"),
            Long.MAX_VALUE to ("8388608" to "TB"), (Long.MAX_VALUE - 1L) to ("8388608" to "TB"))
        assertEquals(expected, expected.map { (bytes, _) -> bytes to dashboardTraffic(bytes) })
    }

    @Test fun trafficIgnoresTheDeviceLocaleLikeDart() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("1.5" to "KB", dashboardTraffic(1536))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun runTimeIsHeldAt999Hours() {
        assertEquals("00:00:00", formatUptime(-5))
        assertEquals("01:01:01", formatUptime(3_661_999))
        assertEquals("999:59:59", formatUptime(999L * 3_600_000 + 3_599_000))
        assertEquals("999:59:59", formatUptime(1_000L * 3_600_000))
    }
}
