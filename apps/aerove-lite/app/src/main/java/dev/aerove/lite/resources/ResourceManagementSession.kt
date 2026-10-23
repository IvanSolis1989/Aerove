package dev.aerove.lite.resources

import dev.aerove.lite.data.PreparedProfile
import java.io.Closeable
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class ResourceManagementFailure { Load, Update, Apply }
data class ResourceApplyReceipt(val appliedGeneration: String?)
data class ResourceManagementState(val profileId: String? = null, val snapshot: ResourceManagementSnapshot? = null,
    val library: List<ResourceManagementRemoteResource> = emptyList(), val busy: Set<String> = emptySet(),
    val failure: ResourceManagementFailure? = null, val appliedGeneration: String? = null, val geo: List<GeoResourceEntry> = emptyList())

/** Scope/lifecycle supplied by the feature host; applying means the real owner completed reconfiguration and readback. */
class ResourceManagementSession(private val repository: ResourceManagementRepository, private val scope: CoroutineScope,
    private val apply: suspend (PreparedProfile) -> Unit, private val library: ResourceManagementLibraryRepository? = null,
    private val onLibraryChanged: suspend () -> Unit = {}, private val geoCatalog: GeoResourceCatalog? = null,
    private val applyWithReceipt: (suspend (PreparedProfile) -> ResourceApplyReceipt)? = null) : Closeable {
    private val mutable = MutableStateFlow(ResourceManagementState())
    val state: StateFlow<ResourceManagementState> = mutable.asStateFlow()
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val cleanups = mutableSetOf<CompletableDeferred<Unit>>()
    private val cleanupScope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var revision = 0L
    private var closed = false
    private var quiesced = false

    fun load(profileId: String?) {
        begin("*", ResourceManagementFailure.Load, prepare = {
            mutable.value = mutable.value.copy(profileId = profileId, snapshot = null, appliedGeneration = null)
        }) { token ->
            val saved = profileId?.let { repository.snapshot(it) }
            val remote = library?.snapshot()?.entries.orEmpty()
            val geo = geoCatalog?.snapshot().orEmpty()
            publish(token) { ResourceManagementState(profileId, saved, remote, geo = geo) }
        }
    }
    fun refresh(key: String? = null) {
        val id = mutable.value.profileId ?: return
        begin(key ?: "*", ResourceManagementFailure.Update) { token ->
            val saved = if (key == null) repository.refresh(id) else repository.refresh(id, key)
            currentCoroutineContext().ensureActive()
            if (!current(token)) return@begin
            publish(token) { it.copy(snapshot = saved) }
            val receipt = try { applyWithReceipt?.invoke(saved.prepared) ?: run { apply(saved.prepared); ResourceApplyReceipt(null) } }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { publish(token) { it.copy(failure = ResourceManagementFailure.Apply) }; return@begin }
            publish(token) { it.copy(appliedGeneration = receipt.appliedGeneration) }
        }
    }
    fun sideload(key: String, input: InputStream) {
        val id = synchronized(gate) { mutable.value.profileId.takeUnless { closed || quiesced } }
        if (id == null) { input.close(); return }
        begin(key, ResourceManagementFailure.Update, onAbandoned = { input.close() }) { token ->
            val saved = input.use { repository.sideload(id, key, it) }
            currentCoroutineContext().ensureActive()
            if (!current(token)) return@begin
            publish(token) { it.copy(snapshot = saved) }
            val receipt = try { applyWithReceipt?.invoke(saved.prepared) ?: run { apply(saved.prepared); ResourceApplyReceipt(null) } }
            catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { publish(token) { it.copy(failure = ResourceManagementFailure.Apply) }; return@begin }
            publish(token) { it.copy(appliedGeneration = receipt.appliedGeneration) }
        }
    }
    fun syncLibrary(id: String) {
        val remote = library ?: return
        begin(id, ResourceManagementFailure.Update) { token ->
            val saved = remote.sync(id)
            currentCoroutineContext().ensureActive()
            if (!current(token)) return@begin
            onLibraryChanged()
            publish(token) { it.copy(library = saved.entries) }
        }
    }
    fun syncGeo(kind: GeoResourceKind) {
        val catalog = geoCatalog ?: return
        begin("geo:${kind.wire}", ResourceManagementFailure.Update) { token ->
            val saved = catalog.sync(kind)
            currentCoroutineContext().ensureActive()
            if (!current(token)) return@begin
            onLibraryChanged()
            publish(token) { it.copy(geo = saved) }
        }
    }
    private fun current(token: Long): Boolean = synchronized(gate) { !closed && !quiesced && token == revision }
    private fun publish(token: Long, transform: (ResourceManagementState) -> ResourceManagementState) = synchronized(gate) {
        if (current(token)) mutable.value = transform(mutable.value)
    }

    private fun begin(key: String, failure: ResourceManagementFailure, onAbandoned: () -> Unit = {},
        prepare: () -> Unit = {}, action: suspend (Long) -> Unit) = synchronized(gate) {
        check(!closed)
        if (quiesced) { onAbandoned(); return@synchronized }
        cancelCurrent()
        prepare()
        val token = revision
        mutable.value = mutable.value.copy(busy = setOf(key), failure = null)
        val entered = AtomicBoolean(false)
        val cleanup = CompletableDeferred<Unit>()
        cleanups += cleanup
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            entered.set(true)
            try { action(token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(token) { it.copy(failure = failure) } }
            finally { publish(token) { it.copy(busy = emptySet()) } }
        }
        jobs += pending
        pending.invokeOnCompletion {
            fun finishCleanup() {
                var failure: Throwable? = null
                try { if (!entered.get()) onAbandoned() }
                catch (error: Throwable) { failure = error }
                if (failure == null) cleanup.complete(Unit) else cleanup.completeExceptionally(failure)
                synchronized(gate) {
                    jobs -= pending
                    // Keep failed receipts until the maintenance owner can observe the actual failure.
                    if (failure == null) cleanups -= cleanup
                }
            }
            // Job.join can finish before this handler returns. Closing an abandoned input also must not
            // block cancellation/admission while the caller holds gate, nor inherit a cancelled scope.
            if (entered.get()) finishCleanup() else cleanupScope.launch { finishCleanup() }
        }
        job = pending
        pending.start()
    }
    private fun cancelCurrent() { revision++; job?.cancel(); job = null; mutable.value = mutable.value.copy(busy = emptySet()) }
    fun cancel() = synchronized(gate) { cancelCurrent() }

    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val (pending, receipts) = synchronized(gate) { quiesced = true; cancelCurrent(); jobs.toList() to cleanups.toList() }
        pending.forEach { it.cancel() }
        pending.joinAll()
        var failure: Throwable? = null
        receipts.forEach { receipt ->
            try { receipt.await() }
            catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error) }
        }
        failure?.let { throw it }
    } }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock { synchronized(gate) {
        check(!closed && scope.coroutineContext[Job]?.isActive != false) { "Resource session is closed" }
        quiesced = false
    } } }
    suspend fun closeAndJoin() { close(); quiesce() }
    override fun close() = synchronized(gate) {
        closed = true; quiesced = true; cancelCurrent(); jobs.toList().forEach { it.cancel() }
    }
}
