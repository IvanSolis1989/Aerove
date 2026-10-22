package dev.aerove.lite.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.ui.colorutilities.DynamicColor
import dev.aerove.lite.ui.colorutilities.DynamicScheme
import dev.aerove.lite.ui.colorutilities.Hct
import dev.aerove.lite.ui.colorutilities.MaterialDynamicColors
import dev.aerove.lite.ui.colorutilities.SchemeContent
import dev.aerove.lite.ui.colorutilities.SchemeMonochrome
import dev.aerove.lite.ui.colorutilities.SchemeTonalSpot
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlin.math.min

// apps/aerove/lib/ui/color.dart and aurora.dart; MCU is package-isolated at the pinned 1.13.0 source.
internal fun fullColorScheme(seedArgb: Int, dark: Boolean, aurora: Boolean, style: ColorStyle = ColorStyle.Vivid, pureBlack: Boolean = false): ColorScheme {
    val key = FullPaletteKey(if (aurora) 0xff808080.toInt() else seedArgb, dark, if (aurora) ColorStyle.Monochrome else style, pureBlack && dark)
    return synchronized(fullPalettes) {
        fullPalettes.getOrPut(key) { buildFullColorScheme(seedArgb, dark, aurora, style).let { if (pureBlack && dark) it.withPureBlackSurfaces() else it } }
    }
}

private data class FullPaletteKey(val seed: Int, val dark: Boolean, val style: ColorStyle, val pureBlack: Boolean)
private const val DarkOnSurfaceTone = 97
private const val DarkCardTone = 15.0
private const val DarkBlockTone = 21.0
private const val DarkInsetTone = 27.0
private val fullPalettes = object : LinkedHashMap<FullPaletteKey, ColorScheme>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FullPaletteKey, ColorScheme>?) = size > 64
}

private fun buildFullColorScheme(seedArgb: Int, dark: Boolean, aurora: Boolean, style: ColorStyle): ColorScheme {
    val seed = Hct.fromInt(if (aurora) 0xff808080.toInt() else seedArgb)
    val effectiveStyle = if (aurora) ColorStyle.Monochrome else style
    val scheme: DynamicScheme = when (effectiveStyle) {
        ColorStyle.Vivid, ColorStyle.Tinted -> SchemeContent(seed, dark, 0.0)
        ColorStyle.Soft -> SchemeTonalSpot(seed, dark, 0.0)
        ColorStyle.Monochrome -> SchemeMonochrome(seed, dark, 0.0)
    }
    val tint = if (effectiveStyle == ColorStyle.Tinted) min(seed.chroma / 6.0, 10.0) else null
    val roles = MaterialDynamicColors()
    fun role(color: DynamicColor) = Color(color.getArgb(scheme))
    fun neutral(color: DynamicColor, chroma: Double, darkTone: Double? = null): Color {
        val palette = color.palette.apply(scheme)
        val tone = (if (dark) darkTone else null) ?: color.getTone(scheme)
        return Color(Hct.from(palette.hue, tint ?: min(palette.chroma, chroma), tone).toInt())
    }
    val tone = if (dark) (seed.tone + 3).coerceIn(50.0, 75.0) else seed.tone.coerceIn(35.0, 60.0)
    val accent = effectiveStyle == ColorStyle.Vivid || effectiveStyle == ColorStyle.Tinted
    val primary = if (!accent) role(roles.primary()) else if (tone == seed.tone) Color(seedArgb) else Color(Hct.from(seed.hue, seed.chroma, tone).toInt())
    val onPrimary = if (!accent) role(roles.onPrimary()) else if (tone <= 60) Color.White else Color(TonalPalette.fromHueAndChroma(seed.hue, seed.chroma).tone(10))
    val container = TonalPalette.fromHueAndChroma(scheme.primaryPalette.hue, min(scheme.primaryPalette.chroma, 48.0))
    val onSurface = if (dark) Color(roles.onSurface().palette.apply(scheme).tone(DarkOnSurfaceTone)) else role(roles.onSurface())
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary, surfaceTint = primary,
        primaryContainer = Color(container.tone(if (dark) 30 else 90)),
        onPrimaryContainer = Color(container.tone(if (dark) 90 else 30)),
        inversePrimary = role(roles.inversePrimary()),
        secondary = role(roles.secondary()), onSecondary = role(roles.onSecondary()),
        secondaryContainer = role(roles.secondaryContainer()), onSecondaryContainer = role(roles.onSecondaryContainer()),
        tertiary = role(roles.tertiary()), onTertiary = role(roles.onTertiary()),
        tertiaryContainer = role(roles.tertiaryContainer()), onTertiaryContainer = role(roles.onTertiaryContainer()),
        error = role(roles.error()), onError = role(roles.onError()),
        errorContainer = role(roles.errorContainer()), onErrorContainer = role(roles.onErrorContainer()),
        onSurface = onSurface, onSurfaceVariant = role(roles.onSurfaceVariant()),
        inverseSurface = role(roles.inverseSurface()), inverseOnSurface = role(roles.inverseOnSurface()),
        outline = role(roles.outline()), outlineVariant = neutral(roles.outlineVariant(), 4.0),
        scrim = role(roles.scrim()),
        surface = neutral(roles.surface(), if (dark) 3.0 else 1.5),
        surfaceBright = neutral(roles.surfaceBright(), if (dark) 3.0 else 1.5),
        surfaceDim = neutral(roles.surfaceDim(), 3.0),
        surfaceContainerLowest = neutral(roles.surfaceContainerLowest(), 3.0),
        surfaceContainerLow = neutral(roles.surfaceContainerLow(), 3.0),
        surfaceContainer = neutral(roles.surfaceContainer(), 3.0, DarkCardTone),
        surfaceContainerHigh = neutral(roles.surfaceContainerHigh(), 3.0, DarkBlockTone),
        surfaceContainerHighest = neutral(roles.surfaceContainerHighest(), 3.0, DarkInsetTone),
        background = neutral(roles.surface(), if (dark) 3.0 else 1.5), onBackground = onSurface,
    )
}

internal fun ColorScheme.fullPage(dark: Boolean): Color = if (dark) surface else surfaceContainer
internal fun ColorScheme.fullCard(dark: Boolean): Color = if (dark) surfaceContainer else surfaceBright
internal fun ColorScheme.fullBlock(dark: Boolean): Color = if (dark) surfaceContainerHigh else surfaceContainer

internal fun fullHctChroma(argb: Int): Double = Hct.fromInt(argb).chroma
internal fun fullNeutralTone(argb: Int): Int = Hct.from(0.0, 0.0, Hct.fromInt(argb).tone).toInt()

// Standard color.dart lowers each surface by the original surface HCT tone.
internal fun ColorScheme.withPureBlackSurfaces(): ColorScheme {
    val shift = Hct.fromInt(surface.toArgb()).tone
    fun lower(color: Color): Color {
        val hct = Hct.fromInt(color.toArgb())
        return Color(Hct.from(hct.hue, hct.chroma, (hct.tone - shift).coerceAtLeast(0.0)).toInt())
    }
    return copy(surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
        surfaceContainerLow = lower(surfaceContainerLow), surfaceContainer = lower(surfaceContainer),
        surfaceContainerHigh = lower(surfaceContainerHigh), surfaceContainerHighest = lower(surfaceContainerHighest),
        surfaceBright = lower(surfaceBright), background = Color.Black)
}
