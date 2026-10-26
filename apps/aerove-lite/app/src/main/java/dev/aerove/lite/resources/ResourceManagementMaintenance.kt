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
class ResourceManagementMaintenance(private val geo: GeoResourceCatalog, private val library: ResourceManagementLibraryRepository,
    private val scripts: ScriptLibrarySession, private val preferences: () -> GeoResourcePreferences,
    private val onChanged: suspend () -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
    private val gate = Any()
    private val backoff = MaintenanceBackoff(clock)
    fun resetAutomaticBackoff(key: String? = null) = backoff.reset(key)
    private val maintenance = Mutex()
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

    private suspend fun update(manual: Boolean, isCurrent: () -> Boolean, coreAvailable: () -> Boolean, coreOnly: Boolean = false): ResourceManagementMaintenanceResult = coroutineScope {
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
        if (!isCurrent() || (!manual && !settings.autoUpdate)) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
        fun current() = synchronized(gate) { !quiesced && token == revision && operation.isActive } &&
            isCurrent() && preferences() == settings
        fun due(time: Long?) = time == null || clock() - time > settings.intervalHours * 3_600_000L
        val interval = settings.intervalHours * 3_600_000L
        val updated = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        suspend fun scriptUpdate(id: String, ticket: MaintenanceBackoff.Ticket = backoff.ticket("script:$id", backoffEpoch)): Pair<String, Boolean>? {
            val script = scripts.state.value.scripts.firstOrNull { it.id == id } ?: return null
            val key = "script:$id"
            val source = script.url.orEmpty()
            if (!manual && !backoff.ready(key, source)) return null
            val receipt = scripts.updateWithReceipt(id)
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
            val geoEntries = geo.snapshot()
            val libraryEntries = library.snapshot().entries
            scripts.refresh()
            val scriptEntries = scripts.state.value.scripts.filter { it.url != null && it.id !in scripts.state.value.busyIds }
            val work = geoEntries.map { entry -> async {
                if (!current()) return@async null
                try { geo.sync(entry.kind); "geo:${entry.kind.wire}" to true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "geo:${entry.kind.wire}" to false }
            } } + libraryEntries.map { entry -> async {
                if (!current()) return@async null
                try { library.sync(entry.id); entry.id to true }
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
            if (current() && updated.isNotEmpty()) onChanged()
            return@coroutineScope ResourceManagementMaintenanceResult(updated, failed)
        }
        for (entry in geo.snapshot()) {
            currentCoroutineContext().ensureActive()
            if (!current()) break
            val key = "geo:${entry.kind.wire}"
            if (!due(entry.saved?.updatedAt) || (coreOnly && synchronized(gate) { key !in skippedCore })) continue
            if (!coreAvailable()) { synchronized(gate) { skippedCore += key }; continue }
            val ticket = backoff.ticket(key, backoffEpoch)
            if (!backoff.ready(key, entry.url)) continue
            synchronized(gate) { skippedCore -= key }
            try { geo.sync(entry.kind); if (current()) { backoff.succeededIfCurrent(key, ticket); updated += key } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) { backoff.failedIfCurrent(key, entry.url, interval, ticket); failed += key } }
        }
        val libraryEntries = library.snapshot().entries
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
                try { library.sync(entry.id); if (current()) { backoff.succeededIfCurrent(entry.id, ticket); updated += entry.id } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (current()) { backoff.failedIfCurrent(entry.id, identity, interval, ticket); failed += entry.id } }
            }
        }
        // Standard automatic maintenance remains ordered: icon sets, scripts, then core-visible providers.
        if (!coreOnly) updateLibrary(ResourceManagementRemoteKind.IconSet)
        if (!coreOnly && current()) {
            scripts.refresh()
            for (script in scripts.state.value.scripts) {
                currentCoroutineContext().ensureActive()
                if (!current()) break
                if (script.url == null || !due(script.updatedAt) || script.id in scripts.state.value.busyIds) continue
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
        if (current() && updated.isNotEmpty()) onChanged()
        ResourceManagementMaintenanceResult(updated, failed)
    }

    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) { quiesced = true; revision++; operations.toList() }
        pending.forEach { it.cancel() }
        pending.joinAll()
    } }

    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock {
        synchronized(gate) { quiesced = false }
    } }
}
