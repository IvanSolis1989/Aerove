package dev.aerove.lite.resources

import dev.aerove.lite.overrides.ScriptLibrarySession
import dev.aerove.lite.data.MaintenanceBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ResourceManagementMaintenanceResult(val updated: Set<String>, val failed: Set<String>)

/** The host's existing visible maintenance trigger owns scheduling and cancellation. */
class ResourceManagementMaintenance(private val geo: GeoResourceCatalog, private val library: ResourceManagementLibraryRepository?,
    private val scripts: ScriptLibrarySession?, private val preferences: () -> GeoResourcePreferences,
    private val onChanged: suspend () -> Unit, private val clock: () -> Long = System::currentTimeMillis,
    private val geoOnly: Boolean = false,
    private val requestPreferences: () -> dev.aerove.lite.requests.RequestPreferences? = { null },
    private val onGeoStarted: (GeoResourceKind) -> Unit = {},
    private val hasPendingGeoApply: () -> Boolean = { false }) {
    private val gate = Any()
    private val backoff = MaintenanceBackoff(clock)
    fun resetAutomaticBackoff(key: String? = null) = backoff.reset(key)
    private val maintenance = Mutex()
    private val geoSerial = Mutex()
    private val operations = mutableSetOf<Job>()
    private var active: Job? = null
    private val skippedCore = mutableSetOf<String>()
    private var quiesced = false
    private var revision = 0L

    suspend fun updateIfDue(isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult =
        update(false, isCurrent, coreAvailable)

    suspend fun updateSkippedIfDue(isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult =
        update(false, isCurrent, coreAvailable, coreOnly = true)

    suspend fun updateAll(isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult =
        update(true, isCurrent, coreAvailable)

    suspend fun automaticGeoDue(settings: GeoResourcePreferences): Boolean {
        if(!settings.autoUpdate)return false
        val interval=settings.intervalHours*3_600_000L
        return geo.snapshot(settings).any { entry ->
            (entry.saved == null || clock()-entry.saved.updatedAt > interval) && backoff.ready("geo:${entry.kind.wire}",entry.url)
        }
    }

    suspend fun updateGeoData(kind: GeoResourceKind?, automatic: Boolean, isCurrent: () -> Boolean): ResourceManagementMaintenanceResult =
        updateGeoDataCaptured(kind,automatic) { isCurrent }

    suspend fun updateGeoDataCaptured(kind: GeoResourceKind?, automatic: Boolean,
        onCompleted: (ResourceManagementMaintenanceResult?, Throwable?) -> Unit = { _, _ -> },
        capture: suspend () -> (() -> Boolean)): ResourceManagementMaintenanceResult = geoSerial.withLock {
        check(geoOnly) { "The shared Geo entry must use the existing maintenance owner's geo-only mode" }
        var result:ResourceManagementMaintenanceResult?=null
        var failure:Throwable?=null
        try {
            maintenance.withLock { if(quiesced)throw CancellationException("Resource maintenance is paused") }
            val current=capture()
            update(!automatic,current,{true},geoKind=kind).also { result=it }
        } catch(problem:Throwable) { failure=problem;throw problem }
        finally { onCompleted(result,failure) }
    }

    private suspend fun update(manual: Boolean, isCurrent: () -> Boolean, coreAvailable: () -> Boolean, coreOnly: Boolean = false, geoKind: GeoResourceKind? = null): ResourceManagementMaintenanceResult = coroutineScope {
        val operation = checkNotNull(currentCoroutineContext()[Job])
        val admission = synchronized(gate) {
            if (coreOnly && skippedCore.isEmpty()) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
            if (quiesced) throw CancellationException("Resource maintenance is paused")
            if (active?.isActive == true) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
            if (manual) backoff.reset()
            operations += operation
            active = operation
            operation.invokeOnCompletion { synchronized(gate) {
                operations -= operation
                if (active === operation) active = null
            } }
            revision to backoff.epoch()
        }
        val token = admission.first
        val backoffEpoch = admission.second
        val settings = preferences().validate()
        val requests = requestPreferences()
        if (!isCurrent()) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
        fun current() = synchronized(gate) { !quiesced && token == revision && operation.isActive } &&
            isCurrent() && preferences() == settings && requestPreferences() == requests
        if(!manual && !settings.autoUpdate) {
            if(geoOnly && hasPendingGeoApply() && current())onChanged()
            return@coroutineScope ResourceManagementMaintenanceResult(emptySet(),emptySet())
        }
        fun due(time: Long?) = time == null || clock() - time > settings.intervalHours * 3_600_000L
        val interval = settings.intervalHours * 3_600_000L
        val updated = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        suspend fun scriptUpdate(id: String, ticket: MaintenanceBackoff.Ticket = backoff.ticket("script:$id", backoffEpoch)): Pair<String, Boolean>? {
            val owner = scripts ?: return null
            val script = owner.state.value.scripts.firstOrNull { it.id == id } ?: return null
            val key = "script:$id"
            val source = script.url.orEmpty()
            if (!manual && !backoff.ready(key, source)) return null
            val receipt = owner.updateWithReceipt(id)
            if (!current()) return null
            if (receipt.committed) backoff.succeededIfCurrent(key, ticket)
            else if (!manual && receipt.errorCode != null) backoff.failedIfCurrent(key, source, interval, ticket)
            return when {
                receipt.errorCode != null -> "script:$id" to false
                receipt.committed -> "script:$id" to true
                else -> null
            }
        }
        if (manual) {
            // Standard ResourcesAction.updateAll queries its libraries, then starts all independent resources.
            val geoEntries = geo.snapshot(settings).filter { geoKind == null || it.kind == geoKind }
            val libraryEntries = if (geoOnly) emptyList() else library?.snapshot()?.entries.orEmpty()
            if (!geoOnly) scripts?.refresh()
            val scriptEntries = if (geoOnly) emptyList() else scripts?.state?.value?.let { state -> state.scripts.filter { it.url != null && it.id !in state.busyIds } }.orEmpty()
            val work = geoEntries.map { entry -> async {
                if (!current()) return@async null
                try { if (geoOnly) onGeoStarted(entry.kind); geo.sync(entry.kind,settings,requests,::current); "geo:${entry.kind.wire}" to true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "geo:${entry.kind.wire}" to false }
            } } + libraryEntries.map { entry -> async {
                if (!current()) return@async null
                try { checkNotNull(library).sync(entry.id); entry.id to true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { entry.id to false }
            } } + scriptEntries.map { script -> async {
                if (!current()) return@async null
                try { scriptUpdate(script.id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "script:${script.id}" to false }
            } }
            // The registered ScopeCoroutine owns these children until every non-cancellable tail returns.
            work.awaitAll().filterNotNull().forEach { (id, success) -> if (success) updated += id else failed += id }
            currentCoroutineContext().ensureActive()
            if (current() && updated.isNotEmpty() && (!geoOnly || failed.isEmpty())) onChanged()
            return@coroutineScope ResourceManagementMaintenanceResult(updated, failed)
        }
        for (entry in geo.snapshot(settings).filter { geoKind == null || it.kind == geoKind }) {
            currentCoroutineContext().ensureActive()
            if (!current()) break
            val key = "geo:${entry.kind.wire}"
            if (!due(entry.saved?.updatedAt) || (coreOnly && synchronized(gate) { key !in skippedCore })) continue
            if (!coreAvailable()) { synchronized(gate) { skippedCore += key }; continue }
            val ticket = backoff.ticket(key, backoffEpoch)
            if (!backoff.ready(key, entry.url)) continue
            synchronized(gate) { skippedCore -= key }
            try { if (geoOnly) onGeoStarted(entry.kind); geo.sync(entry.kind,settings,requests,::current); if (current()) { backoff.succeededIfCurrent(key, ticket); updated += key } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) { backoff.failedIfCurrent(key, entry.url, interval, ticket); failed += key } }
        }
        if (geoOnly) {
            currentCoroutineContext().ensureActive()
            if (current() && (updated.isNotEmpty() || hasPendingGeoApply()) && failed.isEmpty()) onChanged()
            return@coroutineScope ResourceManagementMaintenanceResult(updated,failed)
        }
        val libraryEntries = if (geoOnly) emptyList() else library?.snapshot()?.entries.orEmpty()
        suspend fun updateLibrary(kind: ResourceManagementRemoteKind) {
            for (entry in libraryEntries.filter { it.kind == kind }) {
                currentCoroutineContext().ensureActive()
                if (!current()) break
                if (!due(entry.updatedAt) || (coreOnly && synchronized(gate) { entry.id !in skippedCore })) continue
                if (kind == ResourceManagementRemoteKind.RuleSet && !coreAvailable()) {
                    synchronized(gate) { skippedCore += entry.id }; continue
                }
                synchronized(gate) { skippedCore -= entry.id }
                val identity = "${entry.kind}:${entry.url}"
                val ticket = backoff.ticket(entry.id, backoffEpoch)
                if (!backoff.ready(entry.id, identity)) continue
                try { checkNotNull(library).sync(entry.id); if (current()) { backoff.succeededIfCurrent(entry.id, ticket); updated += entry.id } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (current()) { backoff.failedIfCurrent(entry.id, identity, interval, ticket); failed += entry.id } }
            }
        }
        // Standard automatic maintenance remains ordered: icon sets, scripts, then core-visible providers.
        if (!coreOnly) updateLibrary(ResourceManagementRemoteKind.IconSet)
        if (!coreOnly && current()) {
            scripts?.refresh()
            for (script in scripts?.state?.value?.scripts.orEmpty()) {
                currentCoroutineContext().ensureActive()
                if (!current()) break
                if (script.url == null || !due(script.updatedAt) || script.id in scripts?.state?.value?.busyIds.orEmpty()) continue
                val ticket = backoff.ticket("script:${script.id}", backoffEpoch)
                try { scriptUpdate(script.id, ticket)?.let { (id, success) -> if (success) updated += id else failed += id } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    val key = "script:${script.id}"
                    if (current()) {
                        backoff.failedIfCurrent(key, script.url.orEmpty(), interval, ticket)
                        failed += key
                    }
                }
            }
        }
        updateLibrary(ResourceManagementRemoteKind.RuleSet)
        currentCoroutineContext().ensureActive()
        if (current() && updated.isNotEmpty() && (!geoOnly || failed.isEmpty())) onChanged()
        ResourceManagementMaintenanceResult(updated, failed)
    }

    suspend fun cancelGeoAndJoin(afterJoin: suspend () -> Unit) = withContext(NonCancellable) { maintenance.withLock {
        val pending=synchronized(gate) { quiesced=true;revision++;operations.toList() }
        pending.forEach { it.cancel() };pending.joinAll()
        try { afterJoin() } finally { synchronized(gate) { quiesced=false } }
    } }

    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) { quiesced = true; revision++; operations.toList() }
        pending.forEach { it.cancel() }
        pending.joinAll()
    } }

    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock {
        synchronized(gate) { quiesced = false }
    } }
}
