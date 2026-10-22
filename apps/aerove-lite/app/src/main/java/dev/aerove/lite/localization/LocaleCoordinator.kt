package dev.aerove.lite.localization

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

// null StateFlow value means not loaded; LocalePreference(null) means the saved system choice.
internal data class LocalePreference(val tag: String?)

internal interface LocalePlatform {
    fun selected(): String?
    fun apply(tag: String?)
}

// A wrapper distinguishes an applied system choice (tag=null) from no cache yet.
internal data class AppliedLocale(val tag: String?)
internal interface LocaleAppliedCache {
    suspend fun read(): AppliedLocale?
    suspend fun write(choice: AppliedLocale)
}

/** One event owner reconciles saved intent, Android choice and the last successfully applied choice. */
internal class LocaleCoordinator(
    private val saved: StateFlow<LocalePreference?>,
    scope: CoroutineScope,
    private val platform: LocalePlatform,
    private val cache: LocaleAppliedCache,
    private val onPlatformChoice: suspend (String?) -> Unit,
    private val onError: (Exception) -> Unit,
    private val latestPreference: () -> LocalePreference? = { saved.value },
) : AutoCloseable {
    private val events = Channel<Unit>(Channel.CONFLATED)
    private val restoring = AtomicBoolean(false)
    private var initialized = false
    private var previousSaved: String? = null
    private var applied: String? = null
    private val worker = scope.launch {
        for (event in events) {
            if (latestPreference() == null) continue
            try { reconcile() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onError(failure) }
        }
    }
    private val subscription = scope.launch { saved.collect { events.trySend(Unit) } }

    fun foreground() { events.trySend(Unit) }
    fun restoreSaved() { restoring.set(true); events.trySend(Unit) }

    private suspend fun reconcile() {
        val last = if (!initialized) cache.read() else null
        val settings = latestPreference() ?: return
        val choice = canonicalLocale(settings.tag)
        val nativeTag = platform.selected()
        val native = canonicalLocale(nativeTag)
        if (restoring.getAndSet(false)) {
            applySaved(choice, nativeTag)
            initialized = true
        } else if (!initialized) {
            val restored = last != null && choice != canonicalLocale(last.tag)
            val external = if (last == null) native != null else native != canonicalLocale(last.tag)
            if (!restored && external) acceptPlatform(native, nativeTag)
            else applySaved(choice, nativeTag)
            initialized = true
        } else if (choice != previousSaved) applySaved(choice, nativeTag)
        else if (native != applied) acceptPlatform(native, nativeTag)
        else if (nativeTag != localePlatformTag(applied)) platform.apply(localePlatformTag(applied))
    }

    private suspend fun applySaved(choice: String?, nativeTag: String?) {
        if (nativeTag != localePlatformTag(choice)) platform.apply(localePlatformTag(choice))
        cache.write(AppliedLocale(choice))
        applied = choice
        previousSaved = choice
    }

    private suspend fun acceptPlatform(choice: String?, observedTag: String?) {
        onPlatformChoice(choice)
        // Saving may suspend while Android receives a newer external choice; never overwrite it.
        if (observedTag != localePlatformTag(choice) && platform.selected() == observedTag)
            platform.apply(localePlatformTag(choice))
        cache.write(AppliedLocale(choice))
        applied = choice
        previousSaved = choice
    }

    override fun close() {
        subscription.cancel()
        worker.cancel()
        events.close()
    }
}
