package dev.aerove.lite.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.aerove.lite.LiteFeatureHost

internal val LocalLiteFeatures = staticCompositionLocalOf<() -> LiteFeatureHost> {
    { error("The activity must provide its feature owner") }
}
