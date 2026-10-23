package dev.aerove.lite.providers

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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

data class ProviderManagementState(val providers: List<RuntimeProvider> = emptyList(), val busy: Set<String> = emptySet(),
    val failed: Boolean = false)

class ProviderManagementSession(private val repository: ProviderManagementRepository, private val scope: CoroutineScope,
    private val onGroupsChanged: suspend () -> Unit) : Closeable {
    private val mutable = MutableStateFlow(ProviderManagementState())
    val state: StateFlow<ProviderManagementState> = mutable.asStateFlow()
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private var job: Job? = null
    private var revision = 0L
    private var closed = false
    private var quiesced = false

    fun load() = run("*") { repository.snapshot() }
    fun update(kind: ProviderKind, name: String) = run("$kind/$name") { token ->
        val saved = repository.update(kind, name)
        currentCoroutineContext().ensureActive()
        if (!current(token)) return@run mutable.value.providers
        onGroupsChanged()
        mutable.value.providers.filterNot { it.name == name && it.kind == kind } + saved
    }
    fun healthcheck(name: String) = run("${ProviderKind.Proxy}/$name") { token ->
        val saved = repository.healthcheck(name)
        currentCoroutineContext().ensureActive()
        if (!current(token)) return@run mutable.value.providers
        onGroupsChanged()
        mutable.value.providers.map { if (it.kind == ProviderKind.Proxy && it.name == name) saved else it }
    }
    private fun current(token: Long): Boolean = synchronized(gate) { !closed && !quiesced && token == revision }
    private fun publish(token: Long, transform: (ProviderManagementState) -> ProviderManagementState) = synchronized(gate) {
        if (current(token)) mutable.value = transform(mutable.value)
    }
    private fun run(key: String, action: suspend (Long) -> List<RuntimeProvider>): Unit = synchronized(gate) {
        check(!closed)
        if (quiesced) return@synchronized
        cancelCurrent()
        val token = revision
        mutable.value = mutable.value.copy(busy = setOf(key), failed = false)
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = action(token)
                publish(token) { ProviderManagementState(result) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { publish(token) { it.copy(failed = true) } }
            finally { publish(token) { it.copy(busy = emptySet()) } }
        }
        jobs += pending
        pending.invokeOnCompletion { synchronized(gate) { jobs -= pending } }
        job = pending
        pending.start()
    }
    private fun cancelCurrent() { revision++; job?.cancel(); job = null; mutable.value = mutable.value.copy(busy = emptySet()) }
    fun cancel() = synchronized(gate) { cancelCurrent() }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) { quiesced = true; cancelCurrent(); jobs.toList() }
        pending.forEach { it.cancel() }; pending.joinAll()
    } }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock { synchronized(gate) {
        check(!closed && scope.coroutineContext[Job]?.isActive != false) { "Provider session is closed" }
        quiesced = false
    } } }
    suspend fun closeAndJoin() { close(); quiesce() }
    override fun close() = synchronized(gate) {
        closed = true; quiesced = true; cancelCurrent(); jobs.toList().forEach { it.cancel() }; repository.close()
    }
}
