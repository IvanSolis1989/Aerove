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

data class GeoCatalogSnapshotEntry(val resource: ManagedRuleResource, val generationFile: String)
class GeoCatalogSnapshot internal constructor(val settings: GeoResourcePreferences,
    val requests: dev.aerove.lite.requests.RequestPreferences,
    entries: Map<GeoResourceKind,GeoCatalogSnapshotEntry>) {
    val entries: Map<GeoResourceKind,GeoCatalogSnapshotEntry> = entries.toMap()
}

/** Called only by existing visible maintenance events; never owns a timer or background poll. */
class GeoResourceCatalog(runtimeDirectory: File, validator: CompatibilityValidator, native: NativeResourceValidator,
    downloader: ResourceDownloader, private val preferences: () -> GeoResourcePreferences,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val cache = ResourceManagementRuleCache(File(runtimeDirectory, "resources/library"), validator, native, downloader, clock)
    private val maintenance = Mutex()
    private val updates = ConcurrentHashMap<Triple<GeoResourceKind, String, dev.aerove.lite.requests.RequestPreferences?>, CompletableDeferred<List<GeoResourceEntry>>>()

    suspend fun snapshot(): List<GeoResourceEntry> = snapshot(preferences().validate())

    suspend fun snapshot(settings: GeoResourcePreferences): List<GeoResourceEntry> {
        settings.validate()
        return GeoResourceKind.entries.map { kind ->
            val url = settings.urls.getValue(kind)
            GeoResourceEntry(kind, url, cache.snapshot(kind.wire, url, "", "", kind.assetKind))
        }
    }

    /** Short cache lock only; every RPC boundary must independently recapture this identity. */
    suspend fun capture(settings: GeoResourcePreferences, requests: dev.aerove.lite.requests.RequestPreferences,
        kinds: Set<GeoResourceKind>): GeoCatalogSnapshot {
        settings.validate()
        val sources=kinds.map { kind -> LibraryRuleSource(kind.wire,settings.urls.getValue(kind),"","",kind.assetKind) }
        return cache.withSnapshot(sources) { cached ->
            val entries=kinds.associateWith { kind ->
                val saved=cached.single { it.resource.name == kind.wire }
                check(cache.policyMatches(kind.wire,settings.urls.getValue(kind),kind.assetKind,requests)) { "Geo catalog policy receipt changed" }
                GeoCatalogSnapshotEntry(saved.resource,saved.file.path)
            }
            GeoCatalogSnapshot(settings,requests,entries)
        }
    }

    suspend fun matches(snapshot: GeoCatalogSnapshot): Boolean =
        capture(snapshot.settings,snapshot.requests,snapshot.entries.keys).entries == snapshot.entries

    suspend fun sync(kind: GeoResourceKind): List<GeoResourceEntry> {
        val settings = preferences().validate()
        return sync(kind, settings) { preferences() == settings }
    }

    suspend fun sync(kind: GeoResourceKind, settings: GeoResourcePreferences, requests: dev.aerove.lite.requests.RequestPreferences? = null, isCurrent: () -> Boolean): List<GeoResourceEntry> {
        val url = settings.validate().urls.getValue(kind)
        val key = Triple(kind,url,requests)
        val result = CompletableDeferred<List<GeoResourceEntry>>()
        updates.putIfAbsent(key, result)?.let {
            it.await()
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Geo resource source changed")
            return snapshot(settings)
        }
        try {
            cache.syncWithPolicy(kind.wire, url, "", "", assetKind = kind.assetKind, isCurrent = isCurrent, requests = requests)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw CancellationException("Geo resource source changed")
            return snapshot(settings).also { result.complete(it) }
        } catch (failure: Throwable) { result.completeExceptionally(failure); throw failure }
        finally { updates.remove(key, result) }
    }


    fun reader(settings: GeoResourcePreferences, requests: dev.aerove.lite.requests.RequestPreferences? = null, isCurrent: () -> Boolean): dev.aerove.lite.data.HostGeoResourceReader {
        settings.validate()
        return dev.aerove.lite.data.HostGeoResourceReader { key, url, destination, maxBytes ->
            val kind = GeoResourceKind.entries.firstOrNull { "$.${it.sourceKey}" == key }
            if (kind == null) null
            else {
                if (settings.urls.getValue(kind) != url) throw CancellationException("Geo resource URL differs from current Host policy")
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw CancellationException("Geo resource source changed")
                val snapshot=cache.snapshot(kind.wire,url,"","",kind.assetKind)
                val stale=snapshot != null && settings.autoUpdate && (snapshot.updatedAt > clock() || clock()-snapshot.updatedAt > settings.intervalHours*3_600_000L)
                val wrongPolicy=snapshot != null && requests != null && !cache.policyMatches(kind.wire,url,kind.assetKind,requests)
                if (stale || wrongPolicy) sync(kind,settings,requests,isCurrent)
                suspend fun copy() = cache.copyInto(kind.wire,url,"","",destination,maxBytes,kind.assetKind)
                val saved = copy() ?: run { sync(kind,settings,requests,isCurrent); copy() }
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) throw CancellationException("Geo resource source changed")
                checkNotNull(saved) { "Geo catalog publication was not readable" }.let {
                    dev.aerove.lite.data.HostGeoResourceCopy(it.sha256,it.bytes,it.sourceUpdatedAt)
                }
            }
        }
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
