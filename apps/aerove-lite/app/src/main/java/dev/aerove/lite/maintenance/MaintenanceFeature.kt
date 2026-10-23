package dev.aerove.lite.maintenance

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MaintenanceOperation { PruneCache, ClearAllData }

/** Counts only: diagnostics and confirmations never expose URLs, paths, tokens, or configuration. */
data class MaintenanceInventory(val revision: String, val profiles: Int, val retainedFiles: Int, val cacheBytes: Long) {
    init { require(revision.isNotBlank() && revision.length <= 256 && profiles >= 0 && retainedFiles >= 0 && cacheBytes >= 0) }
}

data class MaintenanceConfirmation(val id: Long, val operation: MaintenanceOperation, val inventory: MaintenanceInventory)
enum class MaintenanceNotice { Completed, Failed, Changed }
data class MaintenanceState(val busy: Boolean = false, val confirmation: MaintenanceConfirmation? = null,
    val notice: MaintenanceNotice? = null)

interface MaintenanceBackend {
    /** Holds the central maintenance/restore gate, quiesces owners, stops and joins the core and protects leases. */
    suspend fun <T> exclusive(operation: MaintenanceOperation, action: suspend () -> T): T
    suspend fun inspect(): MaintenanceInventory
    /** Use live references from every profile, global library and active lease; no guessed filesystem sweep. */
    suspend fun pruneCache()
    /** Reset the exact settings/index/profile domains owned by existing stores, then exit without re-saving. */
    suspend fun clearAppDataAndExit()
}

class MaintenanceFeature(private val scope: CoroutineScope, private val backend: MaintenanceBackend) {
    private val mutableState = MutableStateFlow(MaintenanceState())
    val state = mutableState.asStateFlow()
    private val sequence = AtomicLong()
    private val operation = Mutex()
    private var job: Job? = null
    private var closed = false
    private val epoch = AtomicLong()

    private fun publish(generation: Long, value: MaintenanceState) {
        if (!closed && epoch.get() == generation) mutableState.value = value
    }

    fun request(value: MaintenanceOperation) {
        if (closed || mutableState.value.busy) return
        val generation = epoch.incrementAndGet()
        mutableState.value = MaintenanceState(busy = true)
        job = scope.launch {
            try {
                val inventory = backend.inspect()
                publish(generation, MaintenanceState(confirmation = MaintenanceConfirmation(sequence.incrementAndGet(), value, inventory)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(generation, MaintenanceState(notice = MaintenanceNotice.Failed)) }
            finally { if (mutableState.value.busy) publish(generation, MaintenanceState()) }
        }
    }

    fun dismiss() { if (!mutableState.value.busy) mutableState.value = MaintenanceState() }

    fun confirm(id: Long) {
        val confirmation = mutableState.value.confirmation?.takeIf { it.id == id } ?: return
        if (closed || mutableState.value.busy) return
        val generation = epoch.incrementAndGet()
        mutableState.value = MaintenanceState(busy = true)
        job = scope.launch {
            try {
                operation.withLock {
                    backend.exclusive(confirmation.operation) {
                        if (backend.inspect() != confirmation.inventory) {
                            publish(generation, MaintenanceState(notice = MaintenanceNotice.Changed))
                        } else {
                            when (confirmation.operation) {
                                MaintenanceOperation.PruneCache -> backend.pruneCache()
                                MaintenanceOperation.ClearAllData -> backend.clearAppDataAndExit()
                            }
                            publish(generation, MaintenanceState(notice = MaintenanceNotice.Completed))
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(generation, MaintenanceState(notice = MaintenanceNotice.Failed)) }
            finally { if (mutableState.value.busy) publish(generation, MaintenanceState()) }
        }
    }

    fun cancel() { epoch.incrementAndGet(); job?.cancel(); mutableState.value = MaintenanceState() }

    fun close() { closed = true; cancel() }
}
