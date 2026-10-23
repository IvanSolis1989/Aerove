package dev.aerove.lite.integration

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.proxies.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProxyPresentationState(val profileId: String? = null, val currentGroup: String? = null,
    val unfolded: Set<String> = emptySet(), val groupUrls: Map<String, String> = emptyMap(),
    val delay: ProxyDelayProgress = ProxyDelayProgress(), val ready: Boolean = false, val failed: Boolean = false,
    val groupIcons: Map<String, String> = emptyMap())

/** Presentation state reads the same applied source and uses one real D delay queue per Core binding. */
class ProxyPresentationWorkspace(private val scope: CoroutineScope,
    private val preferences: () -> ProxyPresentationPreferences,
    private val saveUi: suspend (String, String?, Set<String>) -> ProfileSummary,
    private val onSavedUi: (ProfileSummary) -> Unit,
    private val canWork: () -> Boolean) {
    private data class Binding(val key: Any, val profileId: String, val client: CoreApiClient,
        val read: suspend () -> String, val current: () -> Boolean)
    private data class UiIntent(val id: String, val group: String?, val unfolded: Set<String>)
    private val mutable = MutableStateFlow(ProxyPresentationState())
    val state = mutable.asStateFlow()
    private val gate = Any()
    private val maintenance = Mutex()
    private val jobs = ConcurrentHashMap.newKeySet<Job>()
    private val pendingUi = linkedMapOf<String, UiIntent>()
    private var saving: UiIntent? = null
    private var uiJob: Job? = null
    private var binding: Binding? = null
    private var loadedBinding: Binding? = null
    private var owner: ProxyPresentationDelayTests? = null
    private var bindJob: Job? = null
    private var observeJob: Job? = null
    private var groups = emptyList<ProxyGroup>()
    private var active = false
    @Volatile private var paused = false
    @Volatile private var closed = false

    private fun start(action: suspend CoroutineScope.() -> Unit): Job? {
        val job = synchronized(gate) {
            if (paused || closed || !canWork()) return null
            scope.launch(start = CoroutineStart.LAZY, block = action).also(::track)
        }
        job.start()
        return job
    }
    private fun track(job: Job) { jobs += job; job.invokeOnCompletion { jobs -= job } }
    fun profile(profile: ProfileSummary?) = synchronized(gate) {
        val changed = state.value.profileId != profile?.id
        if (changed) mutable.value = ProxyPresentationState(profile?.id, profile?.currentProxyGroup, profile?.unfoldedProxyGroups.orEmpty())
        else if (profile != null && pendingUi[profile.id] == null && saving?.id != profile.id)
            mutable.update { it.copy(currentGroup = profile.currentProxyGroup, unfolded = profile.unfoldedProxyGroups.toSet()) }
        if (changed) { owner?.cancel(true); restartBinding() }
    }
    fun groups(value: List<ProxyGroup>) { groups = value }
    fun bind(key: Any?, profileId: String?, client: CoreApiClient?, read: (suspend () -> String)?, current: () -> Boolean) = synchronized(gate) {
        if (binding?.key === key && binding?.client === client) return@synchronized
        owner?.close(); owner = null; loadedBinding = null; bindJob?.cancel(); observeJob?.cancel()
        binding = if (key == null || profileId == null || client == null || read == null) null else Binding(key, profileId, client, read, current)
        mutable.update { it.copy(groupUrls = emptyMap(), groupIcons = emptyMap(), delay = ProxyDelayProgress(), ready = false, failed = false) }
        restartBinding()
    }
    fun active(value: Boolean) = synchronized(gate) {
        active = value
        if (!value) { owner?.cancel(); bindJob?.cancel(); mutable.update { it.copy(ready = false) } }
        else restartBinding()
    }
    private fun current(value: Binding): Boolean = !closed && !paused && active && canWork() &&
        binding === value && value.current() && value.profileId == state.value.profileId
    private fun restartBinding() {
        val value = binding ?: return
        if (!current(value) || bindJob?.isActive == true) return
        if (owner == null) {
            val queue = ProxyPresentationDelayTests(value.client, scope)
            owner = queue
            observeJob = start { queue.state.collect { delay -> if (binding === value && !paused && !closed)
                mutable.update { it.copy(delay = delay) } } }
        }
        if (loadedBinding === value) {
            mutable.update { it.copy(ready = true, failed = false) }
            return
        }
        bindJob = start {
            try {
                val source = withContext(Dispatchers.Default) {
                    val rows = (dev.aerove.lite.data.ConfigYaml.parse(value.read())["proxy-groups"] as? List<*>).orEmpty()
                    fun field(key: String): Map<String, String> = rows.mapNotNull { raw ->
                        val row = raw as? Map<*, *> ?: return@mapNotNull null
                        val name = row["name"] as? String ?: return@mapNotNull null
                        (row[key] as? String)?.takeIf(String::isNotBlank)?.let { name to it }
                    }.toMap()
                    field("url") to field("icon")
                }
                synchronized(gate) { if (current(value)) {
                    loadedBinding = value
                    mutable.update { it.copy(groupUrls = source.first, groupIcons = source.second, ready = true, failed = false) }
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(value)) mutable.update { it.copy(ready = false, failed = true) } }
        }
    }
    fun retry() = synchronized(gate) { restartBinding() }
    fun cancelTests() = synchronized(gate) { owner?.cancel() }
    fun testGroup(group: String, query: String) = synchronized(gate) {
        val value = binding ?: return@synchronized
        if (!current(value) || !state.value.ready) return@synchronized
        val prefs = preferences()
        owner?.start(proxyDelayTargets(groups, prefs, group, query, state.value.groupUrls), prefs.timeoutMillis)?.let(::track)
    }
    fun testNode(group: String, node: String) = synchronized(gate) {
        val value = binding ?: return@synchronized
        if (!current(value) || !state.value.ready) return@synchronized
        val prefs = preferences()
        val target = resolveProxyDelayTarget(node, groups, state.value.groupUrls, state.value.groupUrls[group] ?: prefs.testUrl) ?: return@synchronized
        owner?.start(listOf(target), prefs.timeoutMillis)?.let(::track)
    }
    fun selectGroup(group: String) = changeUi { it.copy(currentGroup = group) }
    fun toggleGroup(group: String) = changeUi { it.copy(unfolded = if (group in it.unfolded) it.unfolded - group else it.unfolded + group) }
    private fun changeUi(change: (ProxyPresentationState) -> ProxyPresentationState) = synchronized(gate) {
        if (paused || closed || !canWork()) return@synchronized
        val next = change(state.value)
        val id = next.profileId ?: return@synchronized
        if (next.currentGroup == state.value.currentGroup && next.unfolded == state.value.unfolded) return@synchronized
        mutable.value = next.copy(failed = false)
        pendingUi[id] = UiIntent(id, next.currentGroup, next.unfolded.toSet())
        if (uiJob?.isActive != true) uiJob = start {
            while (true) {
                val intent = synchronized(gate) {
                    pendingUi.values.firstOrNull()?.also { pendingUi.remove(it.id); saving = it }
                        ?: run { uiJob = null; null }
                } ?: break
                try { onSavedUi(saveUi(intent.id, intent.group, intent.unfolded)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(failed = true) } }
                finally { synchronized(gate) { saving = null } }
            }
        }
    }
    suspend fun quiesce() = withContext(NonCancellable) { maintenance.withLock {
        val pending = synchronized(gate) { paused = true; pendingUi.clear(); owner?.cancel(); jobs.toList() }
        pending.forEach(Job::cancel); pending.joinAll()
        mutable.update { it.copy(ready = false, delay = ProxyDelayProgress()) }
    } }
    suspend fun resumeAfterMaintenance() = maintenance.withLock { synchronized(gate) {
        check(!closed); paused = false; owner?.close(); owner = null; restartBinding()
    } }
    suspend fun closeAndJoin() { quiesce(); synchronized(gate) { closed = true; owner?.close(); owner = null; binding = null } }
    fun close() = synchronized(gate) { closed = true; pendingUi.clear(); owner?.close(); jobs.toList().forEach(Job::cancel) }
}
