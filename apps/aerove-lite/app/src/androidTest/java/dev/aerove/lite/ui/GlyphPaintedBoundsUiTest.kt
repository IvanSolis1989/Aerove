package dev.aerove.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.aerove.lite.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private data class ArrowPaintFixture(val glyph: LiteGlyph, val size: Int, val fill: Float)

class GlyphPaintedBoundsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualArrowPaintIncludingAntialiasFitsTheSharedEnvelope() {
        val state = mutableStateOf(ArrowPaintFixture(FullGlyphs.arrowUp, 10, 0f))
        compose.replaceAppContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.size(state.value.size.dp).background(Color.White).testTag("painted-arrow")) {
                    GlyphDrawing(state.value.glyph, Modifier.fillMaxSize(), Color.Magenta, state.value.fill)
                }
            }
        }
        for (glyph in listOf(FullGlyphs.arrowUp, FullGlyphs.arrowDown)) for (size in listOf(10, 24, 48)) for (fill in listOf(0f, 1f)) {
            compose.runOnUiThread { state.value = ArrowPaintFixture(glyph, size, fill) }
            compose.waitForIdle()
            val image = compose.onNodeWithTag("painted-arrow").captureToImage()
            val pixels = image.toPixelMap()
            val bounds = checkNotNull(glyph.paintedBounds(Size(image.width.toFloat(), image.height.toFloat()), fill, LayoutDirection.Ltr))
            var painted = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val pixel = pixels[x, y]
                if (pixel.red > 0.99f && pixel.blue > 0.99f && pixel.green < 0.999f) {
                    painted++
                    assertTrue("size=$size fill=$fill pixel=($x,$y) bounds=$bounds", bounds.contains(Offset(x + 0.5f, y + 0.5f)))
                }
            }
            assertTrue("the actual GlyphDrawing must paint", painted > 0)
            assertTrue("the envelope excludes transparent canvas edges", bounds.left > 0f && bounds.right < image.width)
        }
    }
}
