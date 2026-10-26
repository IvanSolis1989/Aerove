package dev.aerove.lite.integration

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.data.*
import dev.aerove.lite.providers.*
import dev.aerove.lite.resources.ResourceManagementRepository
import dev.aerove.lite.resources.ResourceApplyReceipt
import dev.aerove.lite.resources.ManagedResource
import dev.aerove.lite.overrides.OverrideMode
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.telemetry.ProviderLoaded
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Parsed from the actual applied file, with provider leaf counts from the actual API. */
data class ProfileConfigurationCounts(val groups: Int, val proxies: Int, val rules: Int)
fun profileConfigurationCounts(yaml: String): ProfileConfigurationCounts {
    val value = ConfigYaml.parse(yaml)
    return ProfileConfigurationCounts((value["proxy-groups"] as? List<*>)?.size ?: 0,
        (value["proxies"] as? List<*>)?.size ?: 0, (value["rules"] as? List<*>)?.size ?: 0)
}
class ProviderPageBinding internal constructor(internal val endpoint: CoreEndpoint, internal val intentEpoch: Long,
    internal val repository: ProviderManagementRepository)
class ProviderEditorDocument(val title: String, val session: ProfileEditorSession)
data class ProviderUploadRequest(val id: String, val name: String, val dispatched: Boolean = false)
data class ProfileDetailState(val counts: ProfileConfigurationCounts? = null, val loading: Boolean = false,
    val failed: Boolean = false, val error: String? = null, val document: ProviderEditorDocument? = null,
    val upload: ProviderUploadRequest? = null, val resources: List<ManagedResource> = emptyList(),
    val mode: OverrideMode? = null, val providersReady: Boolean = false, val providers: List<RuntimeProvider> = emptyList(), val runtimeAvailable: Boolean = false, val busy: Set<String> = emptySet(), val failures: Set<String> = emptySet())

/** Resolves only actual shared-library path identities; it never creates another library/store. */
class ProfileDetailResources(private val profiles: ProfileRepository, private val resources: ResourceManagementRepository,
    private val library: BuilderResourceLibrary, private val rules: GlobalRulePayloadRepository) {
    suspend fun snapshot(profileId: String): Pair<PreparedProfile, List<ManagedResource>> {
        val actual = resources.snapshot(profileId)
        val definitions = library.snapshot().resources.ruleProviders
        return actual.prepared to actual.entries.map { resource ->
            val entry = resource.localPath?.let { path -> definitions.singleOrNull { it.fields["path"] == path } }
            val url = entry?.fields?.get("url") as? String
            if (resource.kind == "ruleset" && url != null) resource.copy(url = url) else resource
        }
    }
    suspend fun refresh(profileId: String, resource: ManagedResource): PreparedProfile {
        val expected = library.snapshot()
        val entry = resource.localPath?.let { path -> expected.resources.ruleProviders.singleOrNull { it.fields["path"] == path } }
        return if (resource.kind == "ruleset" && entry?.fields?.get("url") is String) {
            rules.sync(expected, checkNotNull(entry.id))
            profiles.prepareForStart(profileId)
        } else resources.refresh(profileId, resource.key).prepared
    }
}

class ProfileDetailWorkspace(private val profiles: ProfileRepository, private val resources: ResourceManagementRepository,
    private val scope: CoroutineScope, private val selected: () -> ProfileSummary?, private val endpoint: () -> CoreEndpoint?,
    private val canWork: () -> Boolean, private val apply: suspend (PreparedProfile) -> ResourceApplyReceipt,
    private val groupsChanged: suspend () -> Unit, private val startEpoch: () -> Long, private val readMode: suspend (String) -> OverrideMode,
    private val managed: ProfileDetailResources) {
    private val mutable = MutableStateFlow(ProfileDetailState())
    val state = mutable.asStateFlow()
    private val currentBinding = MutableStateFlow<ProviderPageBinding?>(null)
    val binding = currentBinding.asStateFlow()
    private val gate = Any()
    private val lifecycle = Mutex()
    private val providerRead = Mutex()
    private val jobs = mutableSetOf<Job>()
    private val protected = mutableSetOf<Job>()
    private val reads = mutableSetOf<Job>()
    @Volatile private var foreground = false
    @Volatile private var paused = false
    @Volatile private var closed = false
    @Volatile private var visible = false
    private var staleDocument = false
    private var generation = 0L
    private var countsGeneration = 0L
    private var documentGeneration = 0L
    private var uploadGeneration = 0L
    @Volatile private var providerSnapshotLoaded = false
    @Volatile private var countsCache: Pair<AppliedConfigSnapshot, ProfileConfigurationCounts>? = null
    private var pendingUpload: Pair<ProviderPageBinding, ProfileResourceEditSnapshot>? = null
    private var telemetryActive = false
    private val loaded = ProviderLoadedRefresh(scope, { providerSnapshotLoaded }, { kind,name -> tracked {
        val active=checkNotNull(bind());check(current(active));providerRead.withLock { active.repository.read(kind,name) }
    } }, { tracked {
        val active=checkNotNull(bind());check(current(active));providerRead.withLock { active.repository.snapshot() }
    } }, { result,all ->
        currentBinding.value?.takeIf(::current)?.let {
            providerSnapshotLoaded=true
            mutable.update { state -> state.copy(providersReady=true, providers=if(all) result else state.providers.map { old ->
                result.singleOrNull { row -> row.kind==old.kind && row.name==old.name } ?: old
            } + result.filter { row -> state.providers.none { old -> old.kind==row.kind && old.name==row.name } }) }
        }
    }, { error -> mutable.update { it.copy(failed=true,error=error.message) } })
    fun providersLoaded(owner:CoreEndpoint, event:dev.aerove.lite.telemetry.CoreTelemetry) {
        if (event !is dev.aerove.lite.telemetry.CoreTelemetry.ProvidersLoaded) return
        if (endpoint() !== owner) return
        if (currentBinding.value?.endpoint !== owner) providerSnapshotLoaded=false
        loaded.session(owner);loaded.accept(owner,event)
    }
    fun telemetryActive(value:Boolean) {
        val changed=telemetryActive!=value
        telemetryActive=value
        if(!value){ val pending=synchronized(gate){countsGeneration++;reads.toList()};pending.forEach{it.cancel()} }
        updateLoadedActivity()
        if(value && changed && visible && foreground) refresh(force=false)
    }
    private fun updateLoadedActivity() = loaded.active(foreground && telemetryActive && !paused && !closed && mutable.value.document==null)
    private suspend fun providerManual(active:ProviderPageBinding, action:suspend()->RuntimeProvider):RuntimeProvider {
        loaded.manualStarted(active.endpoint)
        try { return action().also { loaded.acknowledge(active.endpoint,listOf(it)) } }
        finally { loaded.manualFinished(active.endpoint) }
    }
    private fun current(value: ProviderPageBinding): Boolean = !paused && !closed && canWork() &&
        endpoint() === value.endpoint && startEpoch() == value.intentEpoch && selected()?.id == value.endpoint.configuration?.profileId
    fun visible(value: Boolean) {
        var changed = false
        val pending = synchronized(gate) {
            changed = visible != value
            visible = value
            if (!value) {
                uploadGeneration++; documentGeneration++; countsGeneration++; pendingUpload = null
                mutable.update { it.copy(upload = null, document = null) }
                reads.toList()
            } else emptyList()
        }
        pending.forEach { it.cancel() }
        updateLoadedActivity()
        if (value && changed) refresh(force = false)
    }
    fun foreground(value: Boolean) {
        val pending = synchronized(gate) {
            if (foreground == value) return
            foreground = value
            if (!value) { countsGeneration++; reads.toList() } else emptyList()
        }
        pending.forEach { it.cancel() }
        updateLoadedActivity()
        if (value && visible) refresh(force = false)
    }
    fun endpointChanged() { launch(read = true) {
        val active = bind()
        if (visible && foreground && telemetryActive && mutable.value.document == null) { if (active != null && !providerSnapshotLoaded) loadProviders(active); loadCounts() }
        else if (mutable.value.document != null) staleDocument = true
        loaded.refreshPending()
    } }
    fun refresh() = refresh(force = true)
    private fun refresh(force: Boolean) { if (!visible || !foreground || !telemetryActive) return; launch(read = true) {
        val active = bind()
        coroutineScope { if (active != null && (force || (!providerSnapshotLoaded && !loaded.hasPending()))) this.launch { loadProviders(active, force) }; this.launch { loadCounts() } }
    } }
    private suspend fun bind(): ProviderPageBinding? = lifecycle.withLock {
        val target = endpoint()?.takeIf { it.configuration?.profileId != null && it.configuration?.profileId == selected()?.id }
        val previous = currentBinding.value
        loaded.session(target)
        if (previous?.endpoint === target && previous?.intentEpoch == startEpoch()) return@withLock previous
        currentBinding.value = null
        synchronized(gate) { generation++; providerSnapshotLoaded = false; pendingUpload = null; mutable.update { it.copy(upload = null, counts = null, resources = emptyList(), mode = null, providersReady = false, providers = emptyList(), runtimeAvailable = false, busy = emptySet()) } }
        if (previous != null) {
            previous.repository.close()
            val caller = currentCoroutineContext()[Job]
            val retiring = synchronized(gate) { jobs.filter { it !== caller && it !in protected } }
            retiring.forEach { it.cancel() }; retiring.joinAll()
        }
        currentCoroutineContext().ensureActive()
        if (target == null || paused || closed || !canWork()) return@withLock null
        val repository = ProviderManagementRepository(ControlEndpoint(target.port, target.secret))
        ProviderPageBinding(target, startEpoch(), repository).also { currentBinding.value = it; mutable.update { it.copy(runtimeAvailable = true) } }
    }
    private suspend fun loadCounts() {
        val token = synchronized(gate) { ++countsGeneration }
        mutable.update { it.copy(loading = true, failed = false, error = null) }
        val active = currentBinding.value
        fun latest() = synchronized(gate) { token == countsGeneration && !paused && !closed && visible && foreground && telemetryActive } && currentBinding.value === active
        try {
            val profileId = checkNotNull(selected()).id
            val mode = readMode(profileId)
            if (latest() && selected()?.id == profileId) mutable.update { it.copy(mode = mode) }
            val applied = checkNotNull(active?.endpoint?.configuration)
            val counts = countsCache?.takeIf { it.first === applied }?.second ?: run {
                val yaml = applied.read()
                withContext(Dispatchers.Default) { profileConfigurationCounts(yaml) }.also { countsCache = applied to it }
            }
            if (active != null && current(active) && latest()) mutable.update { it.copy(counts = counts) }
            val saved = managed.snapshot(profileId)
            if (active != null && current(active) && latest() &&
                (active.endpoint.resourceGeneration == null || active.endpoint.resourceGeneration == saved.first.generation))
                mutable.update { it.copy(resources = saved.second) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (latest()) mutable.update { it.copy(counts = null, failed = true) } }
        finally { if (latest()) mutable.update { it.copy(loading = false) } }
    }
    private suspend fun loadProviders(active: ProviderPageBinding, force: Boolean = false) = providerRead.withLock {
        if (!current(active) || (!force && providerSnapshotLoaded)) return@withLock
        mutable.update { it.copy(busy = it.busy + "*") }
        try {
            val result = active.repository.snapshot()
            loaded.acknowledge(active.endpoint,result)
            if (current(active) && visible && foreground) { providerSnapshotLoaded = true; mutable.update { it.copy(providersReady = true, providers = result) } }
        } finally { if (current(active)) mutable.update { it.copy(busy = it.busy - "*") } }
    }
    private fun updateOne(provider: RuntimeProvider, healthcheck: Boolean) {
        val active = currentBinding.value?.takeIf(::current) ?: return
        val key = "${provider.kind}/${provider.name}"
        synchronized(gate) { if (paused || closed || !canWork() || key in mutable.value.busy || "*" in mutable.value.busy) return; mutable.update { it.copy(busy = it.busy + key) } }
        launch(protect = true) {
            try {
                val resource = mutable.value.resources.singleOrNull { it.key == key(provider) }
                val target = if (!healthcheck && resource?.url != null) {
                    val saved = managed.refresh(checkNotNull(selected()).id, resource)
                    check(current(active)) { "Resource application was superseded" }
                    val receipt = apply(saved)
                    if (receipt.appliedGeneration == null) return@launch
                    checkNotNull(bind()).also { check(current(it)) }
                } else active
                val result = providerManual(target) { if (healthcheck) target.repository.healthcheck(provider.name) else target.repository.update(provider.kind, provider.name) }
                if (current(target)) {
                    if (target !== active) { loadProviders(target); if (visible && foreground) loadCounts() }
                    mutable.update { it.copy(providers = it.providers.map { old -> if (old.kind == result.kind && old.name == result.name) result else old }) }
                    groupsChanged()
                }
            } finally { mutable.update { it.copy(busy = it.busy - key) } }
        }
    }
    fun update(provider: RuntimeProvider) = updateOne(provider, false)
    fun healthcheck(provider: RuntimeProvider) = updateOne(provider, true)
    fun updateAll() = launch(protect = true) {
        val active = checkNotNull(currentBinding.value); check(current(active))
        if (mutable.value.busy.isNotEmpty()) return@launch
        val providers = mutable.value.providers
        val keys = providers.map { "${it.kind}/${it.name}" }.toSet()
        mutable.update { it.copy(busy = it.busy + providers.map { row -> "${row.kind}/${row.name}" }, failures = emptySet()) }
        try {
            val managedResources = mutable.value.resources.filter { it.url != null }.associateBy { it.key }
            val sourceFailures = mutableSetOf<String>()
            var prepared: PreparedProfile? = null
            for (provider in providers) {
                val resource = managedResources[key(provider)] ?: continue
                check(current(active)) { "Resource synchronization was superseded" }
                try { prepared = managed.refresh(checkNotNull(selected()).id, resource) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { sourceFailures += provider.name }
            }
            val target = if (prepared != null) {
                check(current(active)) { "Resource application was superseded" }
                val receipt = apply(checkNotNull(prepared))
                if (receipt.appliedGeneration == null) return@launch
                checkNotNull(bind()).also { check(current(it)) }
            } else active
            val results = coroutineScope { providers.map { provider -> async {
                try {
                    if (provider.name in sourceFailures) provider.name to null
                    else provider.name to providerManual(target) { target.repository.update(provider.kind, provider.name) }
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { provider.name to null }
            } }.awaitAll() }
            if (current(target)) {
                if (target !== active) { loadProviders(target); if (visible && foreground) loadCounts() }
                mutable.update { state -> state.copy(providers = state.providers.map { old ->
                    results.firstNotNullOfOrNull { (_, value) -> value?.takeIf { it.name == old.name && it.kind == old.kind } } ?: old
                }, failures = results.filter { it.second == null }.map { it.first }.toSet()) }
                groupsChanged()
            }
        } finally { mutable.update { it.copy(busy = it.busy - keys) } }
    }
    private fun key(provider: RuntimeProvider) = "$.${if (provider.kind == ProviderKind.Proxy) "proxy-providers" else "rule-providers"}.${provider.name}"
    private suspend fun snapshot(active: ProviderPageBinding, provider: RuntimeProvider, textLimit: Int = MAX_PROFILE_BYTES): ProfileResourceEditSnapshot {
        check(current(active)) { "Running configuration changed" }
        val saved = profiles.resourceEditSnapshot(checkNotNull(selected()).id, key(provider), textLimit)
        check(current(active) && (active.endpoint.profileRevision == null || active.endpoint.profileRevision == saved.revision) &&
            (active.endpoint.resourceGeneration == null || active.endpoint.resourceGeneration == saved.generation)) { "Running resource generation changed" }
        return saved
    }
    fun open(provider: RuntimeProvider, editable: Boolean) {
        val token = synchronized(gate) { if (!visible || !foreground) return; ++documentGeneration }
        launch {
        val active = checkNotNull(currentBinding.value)
        val editorLimit = if (provider.kind == ProviderKind.Proxy) MAX_PROFILE_BYTES else MAX_RULE_PREVIEW_BYTES
        val saved = snapshot(active, provider, if (editable) editorLimit else -1)
        val original = if (provider.format == "mrs" || !editable) resources.preview(saved.profileId, saved.key)
            else if (saved.editorOversize) null else saved.bytes.strictUtf8()
        check(current(active))
        val editor = if (!editable) ProfileEditorSession.preview(provider.name, MAX_RULE_PREVIEW_BYTES) { original ?: editorSizeFailure(MAX_RULE_PREVIEW_BYTES) }
        else {
            require(provider.format != "mrs") { "Binary rule sets are read-only" }
            ProfileEditorSession.editable(provider.name, object : ProfileEditorSource {
                override suspend fun read() = original ?: editorSizeFailure(editorLimit)
                override suspend fun replace(expected: String, replacement: String) = tracked(protect = true) {
                    check(expected == original && current(active)) { "Running resource changed" }
                    val prepared = replacement.byteInputStream().use { profiles.replaceResource(saved, it) }
                    check(current(active)) { "Resource application was superseded" }
                    apply(prepared)
                    groupsChanged()
                }
            }, maxBytes = editorLimit)
        }
        synchronized(gate) { if (visible && token == documentGeneration && current(active)) mutable.update { it.copy(document = ProviderEditorDocument(provider.name, editor)) } }
        updateLoadedActivity()
        }
    }
    fun closeDocument() {
        val reload = synchronized(gate) { documentGeneration++; mutable.update { it.copy(document = null) }; staleDocument.also { staleDocument = false } }
        updateLoadedActivity()
        if (reload && visible) refresh()
    }
    fun requestUpload(provider: RuntimeProvider) {
        val token = synchronized(gate) { if (!visible || !foreground) return; ++uploadGeneration }
        launch {
        val active = checkNotNull(currentBinding.value)
        val saved = snapshot(active, provider)
        synchronized(gate) {
            if (visible && current(active) && token == uploadGeneration) { pendingUpload = active to saved; mutable.update { it.copy(upload = ProviderUploadRequest(UUID.randomUUID().toString(), provider.name)) } }
        }
        }
    }
    fun documentLaunched(id: String): Boolean = synchronized(gate) {
        val request = mutable.value.upload ?: return@synchronized false
        if (request.id != id || request.dispatched || paused || closed || !visible || !foreground) return@synchronized false
        mutable.update { it.copy(upload = request.copy(dispatched = true)) }; true
    }
    fun documentResult(id: String, open: (suspend () -> InputStream)?) {
        val target = synchronized(gate) {
            val request = mutable.value.upload
            if (request?.id != id || !request.dispatched || paused || closed) return@synchronized null
            val saved = pendingUpload; pendingUpload = null; mutable.update { it.copy(upload = null) }; saved
        } ?: return
        if (open != null) launch(protect = true) {
            check(current(target.first)) { "Running resource changed" }
            var input: InputStream? = null
            try {
                withContext(Dispatchers.IO) { input = open() }
                currentCoroutineContext().ensureActive()
                check(current(target.first)) { "Running resource changed" }
                val ownedInput = checkNotNull(input); input = null
                val prepared = ownedInput.use { profiles.replaceResource(target.second, it) }
                check(current(target.first)) { "Resource application was superseded" }
                apply(prepared); groupsChanged()
                currentBinding.value?.takeIf(::current)?.let { loadProviders(it, force = true) }
            } finally { input?.close() }
        }
    }
    private fun launch(read: Boolean = false, protect: Boolean = false, action: suspend () -> Unit): Job? = synchronized(gate) {
        if (paused || closed || !canWork()) return@synchronized null
        val ownerEndpoint = endpoint()
        val ownerProfile = selected()?.id
        val ownerIntent = startEpoch()
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { synchronized(gate) { if (endpoint() === ownerEndpoint && selected()?.id == ownerProfile && startEpoch() == ownerIntent && !paused && !closed) mutable.update { it.copy(failed = true, error = failure.message) } } }
        }
        jobs += pending; if (read) reads += pending; if (protect) protected += pending
        pending.invokeOnCompletion { synchronized(gate) { jobs -= pending; reads -= pending; protected -= pending } }; pending.start(); pending
    }
    private suspend fun <T> tracked(protect: Boolean = false, action: suspend () -> T): T = coroutineScope {
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(gate) { check(!closed && !paused && canWork()); jobs += job; if (protect) protected += job }
        job.invokeOnCompletion { synchronized(gate) { jobs -= job; protected -= job } }; action()
    }
    fun cancel() { synchronized(gate) { jobs.toList().forEach { it.cancel() } } }
    suspend fun quiesce() = withContext(NonCancellable) {
        val pending = synchronized(gate) { paused = true; generation++; countsGeneration++; documentGeneration++; uploadGeneration++; pendingUpload = null; mutable.update { it.copy(upload = null) }; jobs.toList() }
        loaded.active(false)
        pending.forEach { it.cancel() }; pending.joinAll()
    }
    suspend fun resumeAfterMaintenance() = withContext(NonCancellable) {
        synchronized(gate) { check(!closed); paused = false }
        updateLoadedActivity()
        if (visible) refresh()
    }
    fun close() { loaded.close(); synchronized(gate) { closed = true; paused = true; pendingUpload = null; jobs.toList().forEach { it.cancel() } }; currentBinding.value?.repository?.close() }
}
