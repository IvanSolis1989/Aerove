package dev.aerove.lite.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.aerove.lite.ui.colorutilities.DynamicColor
import dev.aerove.lite.ui.colorutilities.DynamicScheme
import dev.aerove.lite.ui.colorutilities.Hct
import dev.aerove.lite.ui.colorutilities.MaterialDynamicColors
import dev.aerove.lite.ui.colorutilities.SchemeContent
import dev.aerove.lite.ui.colorutilities.SchemeMonochrome
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlin.math.min

// apps/aerove/lib/ui/color.dart and aurora.dart; MCU is package-isolated at the pinned 1.13.0 source.
internal fun fullColorScheme(seedArgb: Int, dark: Boolean, aurora: Boolean): ColorScheme {
    val seed = Hct.fromInt(if (aurora) 0xff808080.toInt() else seedArgb)
    val scheme: DynamicScheme = if (aurora) SchemeMonochrome(seed, dark, 0.0) else SchemeContent(seed, dark, 0.0)
    val roles = MaterialDynamicColors()
    fun role(color: DynamicColor) = Color(color.getArgb(scheme))
    fun neutral(color: DynamicColor, chroma: Double): Color {
        val palette = color.palette.apply(scheme)
        return Color(Hct.from(palette.hue, min(palette.chroma, chroma), color.getTone(scheme)).toInt())
    }
    val tone = if (dark) (seed.tone + 3).coerceIn(50.0, 75.0) else seed.tone.coerceIn(35.0, 60.0)
    val primary = if (aurora) role(roles.primary()) else if (tone == seed.tone) Color(seedArgb) else Color(Hct.from(seed.hue, seed.chroma, tone).toInt())
    val onPrimary = if (aurora) role(roles.onPrimary()) else if (tone <= 60) Color.White else Color(TonalPalette.fromHueAndChroma(seed.hue, seed.chroma).tone(10))
    val container = TonalPalette.fromHueAndChroma(scheme.primaryPalette.hue, min(scheme.primaryPalette.chroma, 48.0))
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
        onSurface = role(roles.onSurface()), onSurfaceVariant = role(roles.onSurfaceVariant()),
        inverseSurface = role(roles.inverseSurface()), inverseOnSurface = role(roles.inverseOnSurface()),
        outline = role(roles.outline()), outlineVariant = neutral(roles.outlineVariant(), 4.0),
        scrim = role(roles.scrim()),
        surface = neutral(roles.surface(), if (dark) 3.0 else 1.5),
        surfaceBright = neutral(roles.surfaceBright(), if (dark) 3.0 else 1.5),
        surfaceDim = neutral(roles.surfaceDim(), 3.0),
        surfaceContainerLowest = neutral(roles.surfaceContainerLowest(), 3.0),
        surfaceContainerLow = neutral(roles.surfaceContainerLow(), 3.0),
        surfaceContainer = neutral(roles.surfaceContainer(), 3.0),
        surfaceContainerHigh = neutral(roles.surfaceContainerHigh(), 3.0),
        surfaceContainerHighest = neutral(roles.surfaceContainerHighest(), 3.0),
        background = neutral(roles.surface(), if (dark) 3.0 else 1.5), onBackground = role(roles.onSurface()),
    )
}

internal fun ColorScheme.fullPage(dark: Boolean): Color = if (dark) surface else surfaceContainer
internal fun ColorScheme.fullCard(dark: Boolean): Color = if (dark) surfaceContainer else surfaceBright
internal fun ColorScheme.fullBlock(dark: Boolean): Color = if (dark) surfaceContainerHigh else surfaceContainer

internal fun fullHctChroma(argb: Int): Double = Hct.fromInt(argb).chroma
internal fun fullNeutralTone(argb: Int): Int = Hct.from(0.0, 0.0, Hct.fromInt(argb).tone).toInt()
