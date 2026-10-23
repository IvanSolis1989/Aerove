package dev.aerove.lite.resources

import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ResourceDownloader
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GeoResourceEntry(val kind: GeoResourceKind, val url: String, val saved: ManagedRuleResource?)
data class GeoResourceUpdateResult(val updated: List<GeoResourceKind>, val failures: Set<GeoResourceKind>)

/** Called only by existing visible maintenance events; never owns a timer or background poll. */
class GeoResourceCatalog(runtimeDirectory: File, validator: CompatibilityValidator, native: NativeResourceValidator,
    downloader: ResourceDownloader, private val preferences: () -> GeoResourcePreferences,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val cache = ResourceManagementRuleCache(File(runtimeDirectory, "resources/library"), validator, native, downloader, clock)
    private val maintenance = Mutex()
    private val updates = ConcurrentHashMap<Pair<GeoResourceKind, String>, CompletableDeferred<List<GeoResourceEntry>>>()

    suspend fun snapshot(): List<GeoResourceEntry> {
        val settings = preferences().validate()
        return GeoResourceKind.entries.map { kind ->
            val url = settings.urls.getValue(kind)
            GeoResourceEntry(kind, url, cache.snapshot(kind.wire, url, "", "", kind.assetKind))
        }
    }

    suspend fun sync(kind: GeoResourceKind): List<GeoResourceEntry> {
        val url = preferences().validate().urls.getValue(kind)
        val key = kind to url
        val result = CompletableDeferred<List<GeoResourceEntry>>()
        updates.putIfAbsent(key, result)?.let { return it.await() }
        try {
            cache.sync(kind.wire, url, "", "", assetKind = kind.assetKind, isCurrent = { preferences().urls[kind] == url })
            currentCoroutineContext().ensureActive()
            if (preferences().urls[kind] != url) throw CancellationException("Geo resource source changed")
            return snapshot().also { result.complete(it) }
        } catch (failure: Throwable) { result.completeExceptionally(failure); throw failure }
        finally { updates.remove(key, result) }
    }

    suspend fun updateAll(isCurrent: () -> Boolean = { true }): GeoResourceUpdateResult = update(false, isCurrent)
    suspend fun updateIfDue(isCurrent: () -> Boolean): GeoResourceUpdateResult = update(true, isCurrent)

    private suspend fun update(onlyDue: Boolean, isCurrent: () -> Boolean): GeoResourceUpdateResult {
        if (!maintenance.tryLock()) return GeoResourceUpdateResult(emptyList(), emptySet())
        try {
            val settings = preferences().validate()
            if (!isCurrent() || (onlyDue && !settings.autoUpdate)) return GeoResourceUpdateResult(emptyList(), emptySet())
            val updated = mutableListOf<GeoResourceKind>()
            val failures = mutableSetOf<GeoResourceKind>()
            val now = clock()
            for (entry in snapshot()) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent() || preferences() != settings) break
                val elapsed = entry.saved?.let { now - it.updatedAt }
                if (onlyDue && elapsed != null && elapsed <= settings.intervalHours * 3_600_000L) continue
                try { sync(entry.kind); updated += entry.kind }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failures += entry.kind }
            }
            return GeoResourceUpdateResult(updated, failures)
        } finally { maintenance.unlock() }
    }

    fun sources(): List<LibraryRuleSource> = preferences().validate().let { value ->
        GeoResourceKind.entries.map { LibraryRuleSource(it.wire, value.urls.getValue(it), "", "", it.assetKind) }
    }
}
