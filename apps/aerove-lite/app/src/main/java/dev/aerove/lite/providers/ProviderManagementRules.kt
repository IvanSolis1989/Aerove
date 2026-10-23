package dev.aerove.lite.providers

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuleProviderPreviewState(val name: String? = null, val rules: List<String> = emptyList(),
    val ruleCount: Long? = null, val coreEpoch: String? = null, val nextOffset: Long? = null,
    val complete: Boolean = false, val busy: Boolean = false, val failed: Boolean = false,
    val changed: Boolean = false)

/** User-driven preview only: one page per action, same endpoint/epoch as the provider feature. */
class RuleProviderPreviewSession(private val repository: ProviderManagementRepository,
    private val scope: CoroutineScope) : Closeable {
    private val mutable = MutableStateFlow(RuleProviderPreviewState())
    val state: StateFlow<RuleProviderPreviewState> = mutable.asStateFlow()
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = mutableSetOf<Job>()
    private var job: Job? = null
    private var revision = 0L
    private var closed = false
    private var quiesced = false

    fun load(name: String) = synchronized(gate) {
        check(!closed)
        if (quiesced) return@synchronized
        cancelCurrent()
        mutable.value = RuleProviderPreviewState(name = name)
        requestNext()
    }

    fun next() = synchronized(gate) {
        check(!closed)
        if (quiesced) return@synchronized
        if (!mutable.value.busy && !mutable.value.complete && !mutable.value.changed) requestNext()
    }

    private fun current(token: Long): Boolean = !closed && !quiesced && token == revision
    private fun publish(token: Long, transform: (RuleProviderPreviewState) -> RuleProviderPreviewState) = synchronized(gate) {
        if (current(token)) mutable.value = transform(mutable.value)
    }

    private fun requestNext() {
        val before = mutable.value
        val name = before.name ?: return
        val token = revision
        mutable.value = before.copy(busy = true, failed = false)
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val page = repository.rulePage(name, before.nextOffset ?: 0, coreEpoch = before.coreEpoch)
                if (before.ruleCount != null && before.ruleCount != page.ruleCount) throw RuleProviderChangedException()
                publish(token) { before.copy(rules = before.rules + page.rules,
                    ruleCount = page.ruleCount, coreEpoch = page.coreEpoch, nextOffset = page.nextOffset,
                    complete = page.complete) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: RuleProviderChangedException) {
                publish(token) { before.copy(changed = true, failed = true) }
            } catch (_: Exception) {
                publish(token) { before.copy(failed = true) }
            } finally { publish(token) { it.copy(busy = false) } }
        }
        jobs += pending
        pending.invokeOnCompletion { synchronized(gate) { jobs -= pending } }
        job = pending
        pending.start()
    }

    private fun cancelCurrent() { revision++; job?.cancel(); job = null; mutable.value = mutable.value.copy(busy = false) }
    fun cancel() = synchronized(gate) { cancelCurrent() }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) { quiesced = true; cancelCurrent(); jobs.toList() }
        pending.forEach { it.cancel() }; pending.joinAll()
    } }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) { maintenance.withLock { synchronized(gate) {
        check(!closed && scope.coroutineContext[Job]?.isActive != false) { "Rule preview session is closed" }
        quiesced = false
    } } }
    suspend fun closeAndJoin() { close(); quiesce() }
    // The encompassing provider feature owns and closes the shared repository.
    override fun close() = synchronized(gate) { closed = true; quiesced = true; cancelCurrent(); jobs.toList().forEach { it.cancel() } }
}
