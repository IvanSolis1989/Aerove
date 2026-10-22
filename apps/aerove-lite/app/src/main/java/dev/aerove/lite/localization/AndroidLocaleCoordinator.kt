package dev.aerove.lite.localization

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import dev.aerove.lite.LiteSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal class AndroidLocaleCoordinator(
    context: Context,
    saved: StateFlow<LiteSettings?>,
    scope: CoroutineScope,
    onPlatformChoice: suspend (String?) -> Unit,
    onError: (Exception) -> Unit,
) : AutoCloseable {
    private val application = context.applicationContext
    private val manager = application.getSystemService(LocaleManager::class.java)
    private val coordinatorScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Main.immediate)
    private val preference = saved.map { settings -> settings?.let { LocalePreference(canonicalLocale(it.locale)) } }
        .stateIn(coordinatorScope, SharingStarted.Eagerly, null)
    private val coordinator = LocaleCoordinator(preference, coordinatorScope,
        object : LocalePlatform {
            override fun selected(): String? = manager.applicationLocales.toLanguageTags().split(',')
                .firstOrNull { canonicalLocale(it) != null }
            override fun apply(tag: String?) {
                // LocaleManager updates Application/service resources and recreates affected activities itself.
                manager.applicationLocales = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            }
        }, AndroidLocaleCache(application), onPlatformChoice, onError,
        latestPreference = { saved.value?.let { LocalePreference(canonicalLocale(it.locale)) } })
    private val callbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) { coordinator.foreground() }
        override fun onLowMemory() = Unit
    }
    init { application.registerComponentCallbacks(callbacks) }
    fun foreground() = coordinator.foreground()
    fun restoreSaved() = coordinator.restoreSaved()
    override fun close() {
        application.unregisterComponentCallbacks(callbacks)
        coordinator.close()
        coordinatorScope.cancel()
    }
}

private class AndroidLocaleCache(private val context: Context) : LocaleAppliedCache {
    private fun preferences() = context.getSharedPreferences("locale-coordinator", Context.MODE_PRIVATE)
    override suspend fun read(): AppliedLocale? = withContext(Dispatchers.IO) {
        val value = preferences().getString("last-applied-tag", null) ?: return@withContext null
        AppliedLocale(if (value == "system") null else canonicalLocale(value))
    }
    override suspend fun write(choice: AppliedLocale) = withContext(Dispatchers.IO) {
        check(preferences().edit().putString("last-applied-tag", choice.tag ?: "system").commit()) {
            "Unable to save applied application locale"
        }
    }
}
