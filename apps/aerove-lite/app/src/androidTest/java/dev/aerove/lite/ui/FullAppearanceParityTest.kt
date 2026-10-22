package dev.aerove.lite.ui

import androidx.compose.ui.graphics.toArgb
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.ui.colorutilities.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullAppearanceParityTest {
    @Test
    fun pre1ContrastTonesFollowBrightnessForEveryStyleAndAurora() {
        for (dark in listOf(false, true)) for (aurora in listOf(false, true)) {
            for (style in ColorStyle.entries) for (seed in listOf(0xffffff00, 0xffd8c0c3, 0xff03a9f4, 0xffe91e63)) {
                val colors = fullColorScheme(seed.toInt(), dark, aurora, style)
                val reason = "seed=$seed style=$style dark=$dark aurora=$aurora"
                assertEquals("$reason onSurface", if (dark) 97.0 else 10.0, Hct.fromInt(colors.onSurface.toArgb()).tone, 0.5)
                for ((name, color, tone) in listOf(
                    Triple("surfaceContainer", colors.surfaceContainer, if (dark) 15.0 else 94.0),
                    Triple("surfaceContainerHigh", colors.surfaceContainerHigh, if (dark) 21.0 else 92.0),
                    Triple("surfaceContainerHighest", colors.surfaceContainerHighest, if (dark) 27.0 else 90.0),
                )) assertEquals("$reason $name", tone, Hct.fromInt(color.toArgb()).tone, 0.6)
                assertEquals("$reason legacy ink alias", colors.onSurface, colors.onBackground)
                assertEquals("$reason card role", if (dark) colors.surfaceContainer else colors.surfaceBright, colors.fullCard(dark))
                assertEquals("$reason block role", if (dark) colors.surfaceContainerHigh else colors.surfaceContainer, colors.fullBlock(dark))
            }
        }
    }

    @Test
    fun auroraNightContrastUsesTheFullExactGrayRoles() {
        val expected = listOf(0xfff6f6f6, 0xff262626, 0xff323232, 0xff404040).map { it.toInt() }
        for (style in ColorStyle.entries) for (seed in listOf(0xff007aff, 0xffe91e63)) {
            val colors = fullColorScheme(seed.toInt(), true, true, style)
            assertEquals("seed=$seed style=$style", expected,
                listOf(colors.onSurface, colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest).map { it.toArgb() })
        }
    }

    @Test
    fun fourStylesKeepFullAccentAndNeutralRules() {
        for (dark in listOf(false, true)) for (seed in listOf(0xff007aff.toInt(), 0xffe91e63.toInt())) {
            val vivid = fullColorScheme(seed, dark, false, ColorStyle.Vivid)
            val tinted = fullColorScheme(seed, dark, false, ColorStyle.Tinted)
            val soft = fullColorScheme(seed, dark, false, ColorStyle.Soft)
            val mono = fullColorScheme(seed, dark, false, ColorStyle.Monochrome)
            assertEquals(vivid.primary, tinted.primary)
            assertEquals(vivid.onPrimary, tinted.onPrimary)
            vivid.assertStandardSurfaces(standardSurfaceGoldens.single { it.seed == seed && it.dark == dark && it.style == ColorStyle.Vivid })
            tinted.assertStandardSurfaces(standardSurfaceGoldens.single { it.seed == seed && it.dark == dark && it.style == ColorStyle.Tinted })
            assertTrue(vivid.surfaceContainer != tinted.surfaceContainer)
            assertTrue(Hct.fromInt(tinted.surface.toArgb()).chroma <= minOf(Hct.fromInt(seed).chroma / 6.0, 10.0) + 1.0)
            assertEquals(Hct.fromInt(vivid.surface.toArgb()).tone, Hct.fromInt(tinted.surface.toArgb()).tone, 0.5)
            assertTrue(soft.primary != vivid.primary)
            for (argb in listOf(mono.primary.toArgb(), mono.surface.toArgb())) {
                assertEquals(argb ushr 16 and 255, argb ushr 8 and 255)
                assertEquals(argb ushr 8 and 255, argb and 255)
            }
            for (style in ColorStyle.entries) {
                assertEquals(fullColorScheme(seed, dark, true).signature(), fullColorScheme(seed, dark, true, style).signature())
            }
        }
    }

    @Test
    fun motionMatchesFullSourceGoldenPoses() {
        val motion = FullAuroraMotion(832838199)
        val golden = arrayOf(
            doubleArrayOf(0.0, 4.739799584346775, -3.5765689743074005, -18.430129919413606),
            doubleArrayOf(2.0, 4.860840862640008, 7.7716651819280065, -10.753262087413878),
            doubleArrayOf(5.0, 5.031947763559606, 25.100219194938262, 3.3405496007086675),
        )
        for ((seconds, angle, x, y) in golden) {
            val pose = motion.at((seconds * 1_000_000).toLong(), 390.0, 844.0)
            assertEquals(angle, pose.angle, 1e-10)
            assertEquals(x, pose.x, 1e-10)
            assertEquals(y, pose.y, 1e-10)
            assertEquals(1065.5201878294001, pose.side, 1e-9)
        }
    }

    @Test
    fun seededPaletteKeepsFullToneAndChromaBudgets() {
        for (golden in standardSurfaceGoldens) {
            val seed = golden.seed
            val dark = golden.dark
            val colors = fullColorScheme(seed, dark, false, golden.style)
            colors.assertStandardSurfaces(golden)
            assertEquals(if (dark) 30.0 else 90.0, Hct.fromInt(colors.primaryContainer.toArgb()).tone, 1.0)
            val tone = Hct.fromInt(seed).tone.let { if (dark) (it + 3).coerceIn(50.0, 75.0) else it.coerceIn(35.0, 60.0) }
            assertEquals(tone, Hct.fromInt(colors.primary.toArgb()).tone, 0.6)
            assertEquals(colors.signature(), fullColorScheme(seed, dark, false, golden.style).signature())
        }
        for (dark in listOf(false, true)) assertEquals(fullColorScheme(0xff007aff.toInt(), dark, true).signature(), fullColorScheme(0xffe91e63.toInt(), dark, true).signature())
    }

    @Test
    fun auroraUsesFullFixedTessellationAndReadableLightField() {
        val light = fullAuroraMesh(FullAuroraSeed, FullAuroraColors, false)
        assertEquals(65 * 97, light.colors.size)
        assertEquals(65 * 97 * 2, FullAuroraMesh.positions.size)
        assertEquals(64 * 96 * 6, FullAuroraMesh.indices.size)
        assertTrue(light === fullAuroraMesh(FullAuroraSeed, FullAuroraColors, false))
        for (argb in light.colors) {
            fun linear(channel: Int): Double { val value = channel / 255.0; return if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4) }
            val luminance = 0.2126 * linear(argb ushr 16 and 255) + 0.7152 * linear(argb ushr 8 and 255) + 0.0722 * linear(argb and 255)
            assertTrue("Full 0.74 field after nearest 8-bit channel quantization", luminance >= 0.74 - 1.2 / (1.055 * 255))
        }
        val dark = fullAuroraMesh(FullAuroraSeed, FullAuroraColors, true)
        assertTrue(!light.colors.contentEquals(dark.colors))
        assertTrue(!dark.colors.contentEquals(fullAuroraMesh(FullAuroraSeed + 1, FullAuroraColors, true).colors))
    }
}

private fun androidx.compose.material3.ColorScheme.signature(): List<Int> = listOf(primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, tertiary, surface, surfaceBright, surfaceDim, surfaceContainer, surfaceContainerHighest, onSurface, onSurfaceVariant, outline, outlineVariant).map { it.toArgb() }

private fun androidx.compose.material3.ColorScheme.assertStandardSurfaces(golden: StandardSurfaceGolden) {
    val actual = listOf(surface, surfaceBright, surfaceDim, surfaceContainerLowest, surfaceContainerLow,
        surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)
    val names = listOf("surface", "surfaceBright", "surfaceDim", "surfaceContainerLowest", "surfaceContainerLow",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest")
    assertEquals(golden.argb.size, actual.size)
    actual.forEachIndexed { index, color ->
        assertEquals("Standard seed=${golden.seed.toUInt().toString(16)} dark=${golden.dark} style=${golden.style} ${names[index]}",
            golden.argb[index], color.toArgb())
    }
}
