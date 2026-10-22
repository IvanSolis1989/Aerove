package dev.aerove.lite.ui

import android.graphics.RectF
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceGlyphPathTest {
    @Test
    fun discordArcFlagsCreateRealAndroidContours() {
        val path = PathParser.createPathFromPathData(DiscordServicePath)
        assertNotNull(path)
        val bounds = RectF()
        requireNotNull(path).computeBounds(bounds, true)
        assertFalse("Discord has an empty contour", bounds.isEmpty)
    }
}
