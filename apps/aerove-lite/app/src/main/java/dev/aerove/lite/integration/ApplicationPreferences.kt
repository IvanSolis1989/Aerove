package dev.aerove.lite.integration

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.appaccess.AppAccessMode
import dev.aerove.lite.appaccess.AppAccessPreferences

/** Keep both editing collections; the VPN Builder consumes only the explicitly enabled active collection. */
fun LiteSettings.withAppAccess(preferences: AppAccessPreferences): LiteSettings = copy(
    appAccess = preferences,
    appFilterMode = when {
        !preferences.enabled -> AppFilterMode.All
        preferences.mode == AppAccessMode.Include -> AppFilterMode.Include
        else -> AppFilterMode.Exclude
    },
    packages = if (preferences.enabled) preferences.selected.toSet() else emptySet(),
)
