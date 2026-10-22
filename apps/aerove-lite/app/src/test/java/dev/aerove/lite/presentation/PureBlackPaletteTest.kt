package dev.aerove.lite.presentation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.ui.fullColorScheme
import dev.aerove.lite.ui.colorutilities.Hct
import org.junit.Assert.*
import org.junit.Test

class PureBlackPaletteTest {
    @Test fun darkSurfacesUseTheStandardHctShiftWithoutChangingAccent() {
        val ordinary = fullColorScheme(0xff007aff.toInt(), true, false, ColorStyle.Vivid)
        val black = fullColorScheme(0xff007aff.toInt(), true, false, ColorStyle.Vivid, true)
        assertEquals(Color.Black, black.surface)
        assertEquals(Color.Black, black.surfaceDim)
        assertEquals(Color.Black, black.surfaceContainerLowest)
        assertEquals(ordinary.primary, black.primary)
        val shift = Hct.fromInt(ordinary.surface.toArgb()).tone
        val before = listOf(ordinary.surfaceContainerLow, ordinary.surfaceContainer, ordinary.surfaceContainerHigh,
            ordinary.surfaceContainerHighest, ordinary.surfaceBright)
        val after = listOf(black.surfaceContainerLow, black.surfaceContainer, black.surfaceContainerHigh,
            black.surfaceContainerHighest, black.surfaceBright)
        before.zip(after).forEach { (source, result) ->
            val hct = Hct.fromInt(source.toArgb())
            assertEquals(Hct.from(hct.hue, hct.chroma, (hct.tone - shift).coerceAtLeast(0.0)).toInt(), result.toArgb())
        }
        assertSame(ordinary, fullColorScheme(0xff007aff.toInt(), true, false, ColorStyle.Vivid))
        assertSame(black, fullColorScheme(0xff007aff.toInt(), true, false, ColorStyle.Vivid, true))
    }
    @Test fun pureBlackDoesNotAlterTheLightPaletteOrSavedAuroraChoice() {
        assertSame(fullColorScheme(0xff007aff.toInt(), false, false), fullColorScheme(0xff007aff.toInt(), false, false, pureBlack = true))
        assertTrue(effectiveAurora(true, true, false, false))
        assertFalse(effectiveAurora(true, true, true, false))
    }
}
