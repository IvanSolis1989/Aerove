package dev.aerove.lite.presentation

internal const val MinimumTextScale = 0.8f
internal const val MaximumTextScale = 1.4f
internal const val TextScaleStep = 0.05f

internal fun effectiveTextScale(systemScale: Float, enabled: Boolean, customScale: Float): Float {
    val selected = if (enabled) customScale else systemScale
    return (if (selected.isFinite()) selected else 1f).coerceIn(MinimumTextScale, MaximumTextScale)
}

internal fun effectivePureBlack(pureBlack: Boolean, dark: Boolean) = pureBlack && dark
internal fun presentationOpaque(pureBlack: Boolean, dark: Boolean, highContrast: Boolean) =
    highContrast || effectivePureBlack(pureBlack, dark)
internal fun effectiveAurora(aurora: Boolean, pureBlack: Boolean, dark: Boolean, highContrast: Boolean) =
    aurora && !presentationOpaque(pureBlack, dark, highContrast)
