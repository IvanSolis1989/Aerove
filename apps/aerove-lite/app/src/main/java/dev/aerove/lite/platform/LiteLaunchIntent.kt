package dev.aerove.lite.platform

import android.content.Context
import android.content.Intent

internal object LiteLaunchIntent {
    fun create(context: Context): Intent = checkNotNull(
        context.packageManager.getLaunchIntentForPackage(context.packageName),
    ) { "Application launcher is not registered" }
}
