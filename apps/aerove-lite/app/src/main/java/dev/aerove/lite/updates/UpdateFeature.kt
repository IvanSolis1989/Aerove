package dev.aerove.lite.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UpdatePhase { Idle, Checking, Available, Current, Downloading, Ready, Installing, Failed }
enum class UpdateInstallKind { UnknownSources, Install }
data class UpdateInstallRequest(val id: Long, val kind: UpdateInstallKind, val uri: String, val dispatched: Boolean = false)
data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: LiteRelease? = null,
    val automaticPrompt: Boolean = false,
    val visible: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val installRequest: UpdateInstallRequest? = null,
    val error: String? = null,
) {
    val shouldShowPrompt: Boolean get() = visible || installRequest != null
    val busy: Boolean get() = phase in setOf(UpdatePhase.Checking, UpdatePhase.Downloading, UpdatePhase.Installing)
}

class UpdateFeature(
    private val repository: UpdateRepository,
    private val platform: UpdatePlatform,
    scope: CoroutineScope,
    private val onAutoCheckChanged: suspend (Boolean) -> Unit,
) : AutoCloseable {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner + Dispatchers.IO)
    private val gate = Any()
    private val maintenance = Mutex()
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    private var worker: Job? = null
    private var once = false
    private var closed = false
    private var generation = 0L
    private var serial = 0L
    private var ready: VerifiedUpdate? = null
    private var quiesced = false

    fun autoCheckOnce() = synchronized(gate) {
        if (once || closed || quiesced) return@synchronized
        once = true
        check(automatic = true)
    }
    fun check() = check(automatic = false)
    private fun check(automatic: Boolean) = start(quiet = automatic) { token ->
        if (automatic && !platform.automaticEnabled()) return@start
        publish(token) { it.copy(phase = UpdatePhase.Checking, visible = !automatic, automaticPrompt = automatic, error = null) }
        val release = repository.check(platform.identity())
        if (automatic && release != null) {
            if (platform.promptedTag() == release.tag) { publish(token) { it.copy(phase = UpdatePhase.Idle) }; return@start }
            platform.savePromptedTag(release.tag)
        }
        publish(token) { it.copy(phase = if (release == null) UpdatePhase.Current else UpdatePhase.Available,
            release = release, visible = !automatic || release != null) }
    }

    fun download() = start { token ->
        val release = state.value.release ?: return@start
        publish(token) { it.copy(phase = UpdatePhase.Downloading, visible = true, error = null, downloadedBytes = 0, totalBytes = release.bytes) }
        val verified = repository.download(release) { bytes, total ->
            publish(token) { it.copy(downloadedBytes = bytes, totalBytes = total) }
        }
        synchronized(gate) { if (current(token)) ready = verified }
        publish(token) { it.copy(phase = UpdatePhase.Ready) }
    }

    fun requestInstall() = start { token -> prepareInstall(token) }
    private suspend fun prepareInstall(token: Long) {
        val verified = synchronized(gate) { ready } ?: error("No verified update is available")
        require(state.value.release?.tag == verified.release.tag) { "Update selection changed" }
        repository.validate(verified)
        val allowed = platform.canInstall()
        val uri = if (allowed) platform.installUri(verified.file) else "package:${platform.packageName}"
        synchronized(gate) { if (current(token)) worker = null }
        publish(token) { it.copy(phase = UpdatePhase.Ready, visible = true,
            installRequest = UpdateInstallRequest(++serial, if (allowed) UpdateInstallKind.Install else UpdateInstallKind.UnknownSources, uri)) }
    }

    fun platformLaunched(id: Long): Boolean = synchronized(gate) {
        val request = state.value.installRequest
        if (closed || quiesced || request?.id != id || request.dispatched) false else {
            mutableState.value = state.value.copy(installRequest = request.copy(dispatched = true), phase = UpdatePhase.Installing)
            true
        }
    }
    fun platformResult(id: Long) = synchronized(gate) {
        if (closed || quiesced) return@synchronized
        val request = state.value.installRequest?.takeIf { it.id == id && it.dispatched } ?: return@synchronized
        mutableState.value = state.value.copy(installRequest = null, phase = UpdatePhase.Ready)
        if (request.kind == UpdateInstallKind.UnknownSources) start { token ->
            if (platform.canInstall()) prepareInstall(token)
            else publish(token) { it.copy(phase = UpdatePhase.Ready, error = "permission") }
        }
    }
    fun platformLaunchFailed(id: Long) = synchronized(gate) {
        if (closed || quiesced) return@synchronized
        if (state.value.installRequest?.id == id) mutableState.value = state.value.copy(installRequest = null,
            phase = UpdatePhase.Ready, error = "installer")
    }
    fun disableAutomatic() = start { token ->
        onAutoCheckChanged(false)
        publish(token) { it.copy(visible = false, automaticPrompt = false) }
    }
    fun dismiss() = synchronized(gate) {
        if (closed || quiesced) return@synchronized
        if (state.value.busy || state.value.installRequest != null) return@synchronized
        mutableState.value = state.value.copy(visible = false)
    }
    fun cancel() = synchronized(gate) {
        if (closed || quiesced) return@synchronized
        generation++
        worker?.cancel()
        worker = null
        mutableState.value = state.value.copy(visible = false, installRequest = null, phase =
            if (ready != null && ready?.release?.tag == state.value.release?.tag) UpdatePhase.Ready else UpdatePhase.Idle)
    }

    private fun current(token: Long): Boolean = !closed && !quiesced && generation == token && owner.isActive
    private fun publish(token: Long, transform: (UpdateState) -> UpdateState) = synchronized(gate) {
        if (current(token)) {
            val next = transform(state.value)
            if (!next.busy) worker = null
            mutableState.value = next
        }
    }
    private fun start(quiet: Boolean = false, action: suspend (Long) -> Unit): Unit = synchronized(gate) {
        if (closed || quiesced || worker?.isCompleted == false || state.value.installRequest != null) return@synchronized
        val token = ++generation
        val task = jobs.launch(start = CoroutineStart.LAZY) {
            try { action(token) }
            catch (_: CancellationException) { }
            catch (_: Exception) { publish(token) { it.copy(phase = if (quiet) UpdatePhase.Idle else UpdatePhase.Failed,
                visible = !quiet, error = if (quiet) null else "update") } }
        }
        worker = task
        task.start()
    }

    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock { quiesceOwned() } }

    private suspend fun quiesceOwned() {
        val pending = synchronized(gate) {
            quiesced = true
            generation++
            worker = null
            mutableState.value = state.value.copy(visible = false, installRequest = null,
                phase = if (ready != null) UpdatePhase.Ready else UpdatePhase.Idle)
            owner.children.toList()
        }
        pending.forEach { it.cancel() }
        pending.joinAll()
    }

    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        maintenance.withLock { synchronized(gate) {
            check(!closed && owner.isActive) { "Update owner must be replaced after clear" }
            quiesced = false
        } }
    }

    /** Secondary cleanup after primary commit: terminal even if cleanup fails; errors propagate. */
    suspend fun clearUserState(): Unit = withContext(NonCancellable) {
        maintenance.withLock {
            quiesceOwned()
            val wasClosed = synchronized(gate) {
                val previous = closed
                closed = true; generation++; owner.cancel(); ready = null
                mutableState.value = UpdateState()
                previous
            }
            var failure: Throwable? = null
            suspend fun attempt(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            attempt { platform.resetPromptedTag() }
            attempt { repository.clearUserState() }
            if (!wasClosed) attempt { repository.close() }
            failure?.let { throw it }
        }
    }
    override fun close() = synchronized(gate) {
        if (!closed) { closed = true; generation++; owner.cancel(); repository.close(); ready = null; mutableState.value = UpdateState() }
    }
}
