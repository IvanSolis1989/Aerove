package dev.aerove.lite.resources

import dev.aerove.lite.overrides.ScriptLibrarySession
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
    private val maintenance = Mutex()
    private val operations = mutableSetOf<Job>()
    private var active: Job? = null
    private var quiesced = false
    private var revision = 0L

    suspend fun updateIfDue(isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult =
        update(false, isCurrent, coreAvailable)

    suspend fun updateAll(isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult =
        update(true, isCurrent, coreAvailable)

    private suspend fun update(manual: Boolean, isCurrent: () -> Boolean, coreAvailable: () -> Boolean): ResourceManagementMaintenanceResult = coroutineScope {
        val operation = checkNotNull(currentCoroutineContext()[Job])
        val token = synchronized(gate) {
            if (quiesced) throw CancellationException("Resource maintenance is paused")
            if (active?.isActive == true) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
            operations += operation
            active = operation
            operation.invokeOnCompletion { synchronized(gate) {
                operations -= operation
                if (active === operation) active = null
            } }
            revision
        }
        val settings = preferences().validate()
        if (!isCurrent() || (!manual && !settings.autoUpdate)) return@coroutineScope ResourceManagementMaintenanceResult(emptySet(), emptySet())
        fun current() = synchronized(gate) { !quiesced && token == revision && operation.isActive } &&
            isCurrent() && preferences() == settings
        fun due(time: Long?) = time == null || clock() - time > settings.intervalHours * 3_600_000L
        val updated = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        suspend fun scriptUpdate(id: String): Pair<String, Boolean>? {
            val receipt = scripts.updateWithReceipt(id)
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
        if (coreAvailable()) {
            val results = geo.updateIfDue(::current)
            updated += results.updated.map { "geo:${it.wire}" }
            failed += results.failures.map { "geo:${it.wire}" }
        }
        val libraryEntries = library.snapshot().entries
        suspend fun updateLibrary(kind: ResourceManagementRemoteKind) {
            for (entry in libraryEntries.filter { it.kind == kind }) {
                currentCoroutineContext().ensureActive()
                if (!current()) break
                if (!due(entry.updatedAt) || (kind == ResourceManagementRemoteKind.RuleSet && !coreAvailable())) continue
                try { library.sync(entry.id); updated += entry.id }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += entry.id }
            }
        }
        // Standard automatic maintenance remains ordered: icon sets, scripts, then core-visible providers.
        updateLibrary(ResourceManagementRemoteKind.IconSet)
        if (current()) {
            scripts.refresh()
            for (script in scripts.state.value.scripts) {
                currentCoroutineContext().ensureActive()
                if (!current()) break
                if (script.url == null || !due(script.updatedAt) || script.id in scripts.state.value.busyIds) continue
                scriptUpdate(script.id)?.let { (id, success) -> if (success) updated += id else failed += id }
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
