package dev.aerove.lite.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

@RunWith(AndroidJUnit4::class)
class CountryFlagInkTest {
    @Test fun actualSystemGlyphInkIsCenteredAtPhysicalSizeAndCached() = runBlocking {
        for (flag in listOf("🇸🇬", "🇯🇵", "🇨🇭", "🇳🇵")) {
            val ink = checkNotNull(CountryFlagInkCache.load(flag, 126, "device-test"))
            assertSame(ink, CountryFlagInkCache.load(flag, 126, "device-test"))
            assertEquals(Bitmap.DENSITY_NONE, ink.bitmap.density)
            assertEquals(126 * 0.76, hypot(ink.bitmap.width.toDouble(), ink.bitmap.height.toDouble()), 3.0)
            val target = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { density = 420 }
            try {
                Canvas(target).drawBitmap(ink.bitmap, (256 - ink.bitmap.width) / 2f, (256 - ink.bitmap.height) / 2f, null)
                var left = 256
                var top = 256
                var right = -1
                var bottom = -1
                val colors = HashSet<Int>()
                for (y in 0 until 256) for (x in 0 until 256) {
                    val color = target.getPixel(x, y)
                    if (Color.alpha(color) == 0) continue
                    left = minOf(left, x); right = maxOf(right, x)
                    top = minOf(top, y); bottom = maxOf(bottom, y)
                    if (Color.alpha(color) > 200) colors.add(color and 0xffffff)
                }
                assertTrue("A real color glyph must be present for $flag", colors.size > 1)
                assertEquals(128.0, (left + right + 1) / 2.0, 0.5)
                assertEquals(128.0, (top + bottom + 1) / 2.0, 0.5)
                assertEquals(ink.bitmap.width, right - left + 1)
                assertEquals(ink.bitmap.height, bottom - top + 1)
            } finally {
                target.recycle()
            }
        }
    }

    @Test fun emptyInkFallsBackAndCacheStaysUsable() = runBlocking {
        assertNull(CountryFlagInkCache.load("", 126, "empty-test"))
        assertNotNull(CountryFlagInkCache.load("🇸🇬", 126, "empty-test"))
        assertNull(CountryFlagInkCache.load("🇸🇬", 0, "empty-test"))
    }
}
