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

    private class MotionGolden(val seed: Long, val micros: Long, val width: Double, val height: Double,
                               val angle: Double, val x: Double, val y: Double, val side: Double)

    @Test
    fun motionMatchesStandardGoldenPosesBitForBit() {
        // The first 16 rows are pinned in apps/aerove/test/widgets/aurora_motion_test.dart; the rest are edge cases of reference.py.
        val goldens = listOf(
            MotionGolden(832838199L, 0L, 390.0, 844.0, 4.708915131711234, 0.0, 0.0, 1206.9000092093268),
            MotionGolden(832838199L, 2_000_000L, 390.0, 844.0, 4.87782879206507, 32.73327297002594, -17.480551140786897, 1206.9000092093268),
            MotionGolden(832838199L, 5_000_000L, 390.0, 844.0, 5.109019275106863, 83.11485803179671, -41.26636130120369, 1206.9000092093268),
            MotionGolden(832838199L, 3_600_000_000L, 390.0, 844.0, 6.3297701677309295, -115.64232059337, -33.179728688427986, 1206.9000092093268),
            MotionGolden(832838199L, 0L, 412.0, 915.0, 4.708915131711234, 0.0, 0.0, 1296.4748346940519),
            MotionGolden(832838199L, 2_000_000L, 412.0, 915.0, 4.87782879206507, 34.579765291412016, -18.466633512831287, 1296.4748346940519),
            MotionGolden(832838199L, 5_000_000L, 412.0, 915.0, 5.109019275106863, 87.80338848487243, -43.59420732332287, 1296.4748346940519),
            MotionGolden(832838199L, 3_600_000_000L, 412.0, 915.0, 6.3297701677309295, -122.16573354991907, -35.05140569136495, 1296.4748346940519),
            MotionGolden(3919277682L, 0L, 390.0, 844.0, 2.3951164410395274, 0.0, 0.0, 1206.9000092093268),
            MotionGolden(3919277682L, 2_000_000L, 390.0, 844.0, 2.559900709932794, 36.52912307394951, -0.22472780647865187, 1206.9000092093268),
            MotionGolden(3919277682L, 5_000_000L, 390.0, 844.0, 2.8153214385981, 90.97911740293785, -0.1934214407862733, 1206.9000092093268),
            MotionGolden(3919277682L, 3_600_000_000L, 390.0, 844.0, 3.0972836184169243, -37.797860558927034, -53.48944907633882, 1206.9000092093268),
            MotionGolden(3919277682L, 0L, 412.0, 915.0, 2.3951164410395274, 0.0, 0.0, 1296.4748346940519),
            MotionGolden(3919277682L, 2_000_000L, 412.0, 915.0, 2.559900709932794, 38.58974027299282, -0.23740475966462707, 1296.4748346940519),
            MotionGolden(3919277682L, 5_000_000L, 412.0, 915.0, 2.8153214385981, 96.11127274361638, -0.2043323938562682, 1296.4748346940519),
            MotionGolden(3919277682L, 3_600_000_000L, 412.0, 915.0, 3.0972836184169243, -39.930047564815226, -56.506802613978444, 1296.4748346940519),
            MotionGolden(-1L, 249_999L, 390.0, 844.0, 2.0729239659156105, -3.58643532357973, 2.945536307534923, 1206.9000092093268),
            MotionGolden(-1L, 250_000L, 360.5, 780.25, 2.0729240457312637, -3.315166929902607, 2.722743820972139, 1115.6923122108155),
            MotionGolden(-1L, 479_999_999L, 1280.0, 800.0, 8.152818273241042, 3.734830338361639e-05, -7.429039443670321e-06, 2073.9713509403473),
            MotionGolden(-1L, 480_000_000L, 1.0, 1.0, 8.152818346537101, 0.0, 0.0, 2.1151556979968262),
            MotionGolden(-1L, 480_000_001L, 1.0, 1.0, 8.152818419833155, -4.668537934714089e-08, 9.28629932793464e-09, 2.1151556979968262),
            MotionGolden(-1L, 8_640_000_000_000_000L, 2400.0, 1.0, 4.0972596262356396, 0.0, 0.0, 2424.6870104166574),
            MotionGolden(Long.MAX_VALUE, 1_000_000_012_345L, 412.0, 915.0, 2.4656032730726074, 76.13457584523289, -33.91879960562904, 1296.4748346940519),
            MotionGolden(Long.MIN_VALUE, 1_000_000_012_345L, 1280.0, 800.0, 2.8564408194524686, -105.05780848930917, 112.06492517019988, 2073.9713509403473),
            MotionGolden(Long.MIN_VALUE, 2_819_430_706_432L, 1.0, 1.0, 8.305291668931288, -0.028886017479256766, -0.20309667277701984, 2.1151556979968262),
        )
        for (golden in goldens) {
            val pose = FullAuroraMotion(golden.seed).at(golden.micros, golden.width, golden.height)
            val label = "${golden.seed} t=${golden.micros} ${golden.width}x${golden.height}"
            for ((name, expected, actual) in listOf(Triple("angle", golden.angle, pose.angle), Triple("x", golden.x, pose.x),
                Triple("y", golden.y, pose.y), Triple("side", golden.side, pose.side))) {
                assertEquals("$label $name expected $expected, got $actual", expected.toRawBits(), actual.toRawBits())
            }
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
