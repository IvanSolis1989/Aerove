package dev.aerove.lite

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aerove.lite.backup.BackupFeature
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.ProfileEditorSession
import dev.aerove.lite.data.ProfileLinkDecoder
import dev.aerove.lite.data.ProfileLinkEntry
import dev.aerove.lite.data.ProfileMaintenanceFacade
import dev.aerove.lite.data.ProfileMaintenanceResult
import dev.aerove.lite.data.ServiceCheck
import dev.aerove.lite.data.ServiceTarget
import dev.aerove.lite.telemetry.CoreTelemetry
import dev.aerove.lite.telemetry.GroupRefresh
import dev.aerove.lite.telemetry.OutboundProbe
import dev.aerove.lite.telemetry.TelemetrySession
import dev.aerove.lite.telemetry.TelemetryVisibility
import dev.aerove.lite.telemetry.sessionBindings
import dev.aerove.lite.telemetry.routePicks
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.platform.LocalNetworkMonitor
import dev.aerove.lite.profiles.AndroidProfileDocuments
import dev.aerove.lite.profiles.ProfileListFeature
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class LiteController(private val application: LiteApplication) : ViewModel(), LiteActions, LiteFeatureHost {
    private val mutableState = MutableStateFlow(LiteState())
    val state = mutableState.asStateFlow()
    override val backup = BackupFeature(application, viewModelScope, ::reloadRestoredData)
    private var openedEditor: Pair<Triple<String, String, Boolean>, ProfileEditorSession>? = null
    private val profileLinks = ProfileLinkDecoder()
    private val visible = MutableStateFlow(TelemetryVisibility())
    private val connectionTelemetryActive = MutableStateFlow(false)
    private var api: CoreApiClient? = null
    private var currentSession: TelemetrySession? = null
    private val profileMaintenance = ProfileMaintenanceFacade(application.profiles, viewModelScope,
        onUpdated = { profile, request ->
            withContext(Dispatchers.Main.immediate) {
                if (application.profiles.isCurrentSource(profile.id, request.sourceIdentity) && request.isCurrent()) {
                    reloadProfiles()
                    if (application.profiles.isCurrentSource(profile.id, request.sourceIdentity) && request.isCurrent() &&
                        state.value.selectedProfile == profile.id) {
                        invalidateOutboundConfiguration()
                        reconnectSelected(profile.id)
                    }
                }
            }
        }, onAutomaticError = { _, error -> viewModelScope.launch { report(error) } })
    private val profileListOwner = lazy {
        ProfileListFeature(application.profiles, profileMaintenance, viewModelScope,
            AndroidProfileDocuments(application.contentResolver),
            onChanged = { withContext(Dispatchers.Main.immediate) { reloadProfiles() } },
            onReplaced = { profile, isCurrent ->
                withContext(Dispatchers.Main.immediate) {
                    if (application.profiles.isCurrentSource(profile.id, profile.sourceIdentity) && isCurrent() &&
                        state.value.selectedProfile == profile.id) {
                        invalidateOutboundConfiguration()
                        reconnectSelected(profile.id)
                    }
                }
            })
    }
    override val profileList: ProfileListFeature get() = profileListOwner.value
    private data class DiagnosticsBinding(val endpoint: CoreEndpoint, val client: CoreApiClient,
        val generation: Long, val configuration: Long)
    private val diagnosticsRouteRead = Mutex()
    private val diagnostics: DiagnosticsCoordinator<DiagnosticsBinding> = DiagnosticsCoordinator(viewModelScope,
        readServices = ::readDiagnosticServices,
        readMemory = { it.client.memorySnapshot() }, isCurrent = ::diagnosticsBindingCurrent)
    private var diagnosticsRouteJob: Job? = null
    private var diagnosticsRouteRequest = 0L
    private var diagnosticsBinding: DiagnosticsBinding? = null
    private val auroraHandler = Handler(Looper.getMainLooper())
    private var auroraCheckJob: Job? = null
    private var settingsRequests = 0
    private val settingsUpdates = ControllerSettingsUpdates(
        read = application.settings::read,
        save = { application.settings.save(it, VpnService.prepare(application) == null) },
        rotate = { application.settings.rotateAuroraForDay(it) },
        shuffle = { application.settings.shuffleAuroraWallpaper() },
        current = { state.value.settings },
        publish = ::applySavedSettings,
    )
    private val aurora: ControllerAuroraRotation = ControllerAuroraRotation(
        settings = { state.value.settings },
        onCheck = { now, eligible ->
            auroraCheckJob?.cancel()
            auroraCheckJob = viewModelScope.launch {
                try {
                    settingsUpdates.rotate(now, eligible)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (eligible()) report(failure)
                }
            }
        },
        schedule = { delay, callback ->
            val runnable = Runnable { callback() }
            auroraHandler.postDelayed(runnable, auroraHandlerDelayMillis(delay))
            AuroraRotationHandle { auroraHandler.removeCallbacks(runnable) }
        },
    )
    private data class OutboundKey(val endpoint: CoreEndpoint, val profileId: String, val mode: ProxyMode,
        val configuration: Long)
    private var outboundConfiguration = 0L
    private var outboundBlockedEndpoint: CoreEndpoint? = null
    private var outboundGroupEndpoint: CoreEndpoint? = null
    private val outbound = OutboundProbe<OutboundKey>(viewModelScope, { key ->
        CoreApiClient(ControlEndpoint(key.endpoint.port, key.endpoint.secret)).use { it.outboundProbe() }
    }, { it == outboundKey() })
    var filePicker: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            diagnostics.state.collect { snapshot -> mutableState.update { it.copy(diagnostics = snapshot) } }
        }
        viewModelScope.launch {
            profileMaintenance.runningProfileIds.collect { ids -> mutableState.update { it.copy(updatingProfileIds = ids) } }
        }
        viewModelScope.launch {
            outbound.state.collect { snapshot ->
                mutableState.update { it.copy(outboundIdentity = snapshot.identity,
                    outboundLoading = snapshot.loading, outboundError = snapshot.error) }
            }
        }
        perform("load") {
            settingsUpdates.load()
            reloadProfiles()
        }
        viewModelScope.launch {
            application.vpn.collect { snapshot ->
                currentSession?.takeUnless { it.current }?.close()
                mutableState.update { it.copy(phase = snapshot.phase, message = snapshot.error ?: it.message) }
                if (outboundGroupEndpoint !== snapshot.endpoint) outboundGroupEndpoint = null
                if (snapshot.endpoint != null && outboundBlockedEndpoint !== snapshot.endpoint) outboundBlockedEndpoint = null
                bindOutbound()
            }
        }
        viewModelScope.launch {
            visible.collectLatest { foreground ->
                if (foreground.active) LocalNetworkMonitor(application).addresses().collect { address ->
                    mutableState.update { it.copy(localAddress = address) }
                }
            }
        }
        viewModelScope.launch {
            combine(visible, application.vpn) { foreground, snapshot ->
                if (foreground.active && snapshot.phase == VpnPhase.Running) snapshot.runningSinceNanos else 0L
            }.distinctUntilChanged().collectLatest { started ->
                if (started == 0L) {
                    if (application.vpn.value.phase != VpnPhase.Running) mutableState.update { it.copy(uptimeMillis = 0) }
                } else while (true) {
                    val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                    mutableState.update { it.copy(uptimeMillis = elapsed) }
                    delay(1000)
                }
            }
        }
        viewModelScope.launch {
            sessionBindings(visible, application.vpn.map { it.endpoint })
                .collectLatest { binding ->
                    api = null
                    bindDiagnostics(null)
                    mutableState.update { it.copy(groups = emptyList(), connections = emptyList(), traffic = Traffic()) }
                    outboundGroupEndpoint = null
                    outbound.awaitingGroups()
                    if (binding == null) return@collectLatest
                    val (endpoint, generation) = binding
                    val client = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
                    api = client
                    bindDiagnostics(DiagnosticsBinding(endpoint, client, generation, outboundConfiguration))
                    try {
                        supervisorScope {
                            val session = TelemetrySession(this) {
                                visible.value.active && visible.value.generation == generation &&
                                    api === client && application.vpn.value.endpoint == endpoint
                            }
                            currentSession = session
                            try {
                                val groups = GroupRefresh(session, client::groups, { failure ->
                                    outbound.groupsFailed(failure)
                                    report(failure)
                                }, { snapshot ->
                                    mutableState.update { it.copy(groups = snapshot) }
                                    outboundGroupEndpoint = endpoint
                                    val picks = snapshot.routePicks()
                                    bindOutbound(picks)
                                    outbound.synchronize(picks)
                                    synchronizeDiagnosticsRoute()
                                }, { it.isEmpty() })
                                session.observe("logs", mutableState.map { it.settings.openLogs }, {
                                    client.logs().flowOn(Dispatchers.Default)
                                }, ::report) { line ->
                                    if (state.value.settings.openLogs) {
                                        mutableState.update { it.copy(logs = (it.logs + line).takeLast(2000)) }
                                    }
                                }
                                session.observe("telemetry", flowOf(true), client::telemetry, ::report) { event ->
                                    when (event) {
                                        is CoreTelemetry.TrafficSample -> mutableState.update { it.copy(traffic = event.value) }
                                        is CoreTelemetry.GroupsChanged -> if (groups.changed(event.revision)) {
                                            diagnostics.awaitingRoute()
                                            outboundGroupEndpoint = null
                                            outbound.awaitingGroups()
                                        }
                                    }
                                }
                                session.observe("connections", connectionTelemetryActive, client::connectionUpdates, ::report) { connections ->
                                    if (connectionTelemetryActive.value) {
                                        mutableState.update { it.copy(connections = connections) }
                                    }
                                }
                                awaitCancellation()
                            } finally {
                                session.close()
                                if (currentSession === session) currentSession = null
                            }
                        }
                    } finally {
                        if (api === client) api = null
                        if (diagnosticsBinding?.client === client) bindDiagnostics(null)
                        client.close()
                    }
                }
        }
    }

    fun setVisible(value: Boolean) {
        visible.update { it.changed(value) }
        outbound.setActive(value)
        profileMaintenance.setForeground(value)
        if (!value) bindDiagnostics(null)
        if (!value) currentSession?.close()
    }

    fun initializeAurora(state: AuroraLifecycleState?) { aurora.initialize(state) }

    fun setAuroraLifecycle(state: AuroraLifecycleState?) {
        auroraCheckJob?.cancel()
        aurora.setLifecycle(state)
    }

    fun checkAuroraTime() {
        auroraCheckJob?.cancel()
        aurora.check()
    }

    override fun onCleared() {
        backup.close()
        if (profileListOwner.isInitialized()) profileListOwner.value.close()
        profileMaintenance.close()
        diagnostics.close()
        diagnosticsRouteJob?.cancel()
        outbound.close()
        openedEditor = null
        aurora.dispose()
        auroraCheckJob?.cancel()
        settingsUpdates.close()
        filePicker = null
        super.onCleared()
    }

    override fun setConnectionTelemetryActive(active: Boolean) { connectionTelemetryActive.value = active }
    override fun setMemoryTelemetryActive(active: Boolean) { diagnostics.memory(active) }
    override fun setServiceTelemetryTargets(ids: Set<String>) { diagnostics.watch(ids) }
    override fun refreshServiceChecks() {
        diagnostics.refresh(state.value.settings.enabledServiceIds().toSet())
        synchronizeDiagnosticsRoute()
    }
    override fun refreshServiceCheck(id: String) {
        diagnostics.refresh(setOf(id))
        synchronizeDiagnosticsRoute()
    }

    override fun connect() {
        if (VpnService.prepare(application) != null) {
            showMessage(application.getString(R.string.error_vpn_permission))
            return
        }
        if (state.value.selectedProfile == null) {
            showMessage(application.getString(R.string.error_profile_required))
            return
        }
        ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
            .setAction(LiteVpnService.ACTION_START))
    }

    override fun disconnect() {
        application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    }

    override fun importFile() { filePicker?.invoke() }

    override fun editor(profileId: String, title: String, readOnly: Boolean): ProfileEditorSession {
        val key = Triple(profileId, title, readOnly)
        openedEditor?.takeIf { it.first == key }?.let { return it.second }
        val session = if (readOnly) ProfileEditorSession.preview(title) {
            val endpoint = checkNotNull(application.vpn.value.endpoint) {
                application.getString(R.string.error_api_unavailable)
            }
            val configuration = checkNotNull(endpoint.configuration) {
                application.getString(R.string.error_api_unavailable)
            }
            check(configuration.profileId == profileId) {
                application.getString(R.string.integration_preview_not_running)
            }
            val yaml = configuration.read()
            check(application.vpn.value.endpoint === endpoint) {
                application.getString(R.string.integration_preview_changed)
            }
            yaml
        } else ProfileEditorSession.local(application.profiles, application.filesDir, profileId, title)
        openedEditor = key to session
        return session
    }

    override fun closeEditor() { openedEditor = null }

    override fun editorSaved(profileId: String) = perform("profile:$profileId") {
        reloadProfiles()
        if (state.value.selectedProfile == profileId) invalidateOutboundConfiguration()
        reconnectSelected(profileId)
    }

    override fun importProfileText(name: String, input: String) = perform("import") {
        val existing = application.profiles.snapshot().profiles.mapTo(mutableSetOf()) { it.name }
        val batch = profileLinks.decode(input, existing)
        val failures = batch.errors.map { "${it.source.inputIndex + 1}:${it.source.line} ${it.field}: ${it.reason}" }.toMutableList()
        for (entry in batch.entries) {
            try {
                val title = name.trim().takeIf { it.isNotEmpty() && batch.entries.size == 1 } ?: entry.name
                when (entry) {
                    is ProfileLinkEntry.Subscription -> application.profiles.addSubscription(title, entry.url)
                    is ProfileLinkEntry.Inline -> application.profiles.importText(title, entry.config)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failures += failure.message ?: failure.javaClass.simpleName
            }
        }
        reloadProfiles()
        if (failures.isNotEmpty()) showMessage(failures.joinToString("\n"))
    }

    fun importFile(uri: Uri) = perform("import") {
        val name = withContext(Dispatchers.IO) {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: application.getString(R.string.default_profile_name)
        }
        val input = withContext(Dispatchers.IO) {
            checkNotNull(application.contentResolver.openInputStream(uri)) { application.getString(R.string.error_read_file) }
        }
        application.profiles.importFile(name, input)
        reloadProfiles()
    }

    override fun addSubscription(name: String, url: String) = perform("import") {
        application.profiles.addSubscription(name, url)
        reloadProfiles()
    }

    override fun updateProfile(id: String) = perform("profile:$id") {
        when (val result = profileMaintenance.refresh(id)) {
            is ProfileMaintenanceResult.Failed -> report(result.error)
            else -> Unit
        }
    }

    override fun cancelProfileUpdate(id: String) { profileMaintenance.cancel(id) }

    override fun updateProfileMetadata(id: String, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long) = perform("profile:$id") {
        val before = state.value.profiles.firstOrNull { it.id == id }
        val saved = profileMaintenance.editMetadata(id, name, url, autoUpdate, intervalMillis)
        reloadProfiles()
        if (before?.url != saved.url && saved.url != null) {
            when (val result = profileMaintenance.refresh(id)) {
                is ProfileMaintenanceResult.Failed -> report(result.error)
                else -> Unit
            }
        }
    }

    override fun selectProfile(id: String) = perform("profile:$id") {
        application.profiles.select(id)
        invalidateOutboundConfiguration()
        reloadProfiles()
        reconnectSelected(id)
    }

    override fun deleteProfile(id: String) = perform("profile:$id") {
        if (state.value.selectedProfile == id) {
            invalidateOutboundConfiguration()
            disconnect()
        }
        application.profiles.delete(id)
        reloadProfiles()
    }

    override fun selectProxy(group: String, proxy: String) = perform("group:$group") {
        val client = requireApi()
        val selection = outbound.beginSelection()
        diagnostics.awaitingRoute()
        var succeeded = false
        try {
            client.select(group, proxy)
            succeeded = true
        } finally {
            outbound.endSelection(selection, group, proxy, succeeded)
            synchronizeDiagnosticsRoute()
        }
    }

    override fun testGroup(group: String) {
        val session = currentSession
        val client = api
        if (session == null || client == null || !session.current) {
            showMessage(application.getString(R.string.error_api_unavailable))
            return
        }
        val nodes = state.value.groups.firstOrNull { it.name == group }?.nodes ?: return
        performInSession("group:$group", session) {
            val limit = Semaphore(4)
            coroutineScope {
                nodes.map { node -> async {
                    limit.withPermit {
                        val measured = try {
                            client.delay(node.name)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IOException) {
                            -1
                        }
                        session.accept {
                            mutableState.update { state -> state.copy(groups = state.groups.map { current ->
                                if (current.name != group) current else current.copy(nodes = current.nodes.map {
                                    if (it.name == node.name) it.copy(delayMillis = measured) else it
                                })
                            }) }
                        }
                    }
                } }.awaitAll()
            }
        }
    }

    override fun refreshOutbound() { outbound.refresh() }

    override fun closeConnection(id: String) = perform("connection:$id") {
        val client = requireApi()
        client.closeConnection(id)
        val connections = withContext(Dispatchers.Default) { client.connections() }
        mutableState.update { it.copy(connections = connections) }
    }

    override fun closeAllConnections() = perform("connections") {
        val client = requireApi()
        client.closeAllConnections()
        val connections = withContext(Dispatchers.Default) { client.connections() }
        mutableState.update { it.copy(connections = connections) }
    }

    override fun shuffleAurora() = queueSettings { settingsUpdates.shuffle() }

    override fun changeSettings(settings: LiteSettings) {
        val baseline = state.value.settings
        val requested = settings.normalized()
        queueSettings { settingsUpdates.save(baseline, requested) }
    }

    private fun applySavedSettings(saved: LiteSettings, allowRestart: Boolean) {
        val old = state.value.settings
        val restart = old.ipv6 != saved.ipv6 || old.overrideDns != saved.overrideDns ||
            old.dnsServers != saved.dnsServers || old.appFilterMode != saved.appFilterMode ||
            old.packages != saved.packages || old.mode != saved.mode
        if (allowRestart && restart) invalidateOutboundConfiguration()
        mutableState.update { it.copy(settings = saved) }
        diagnostics.settings(saved)
        if (allowRestart && restart && state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) connect()
        if (old.aurora != saved.aurora) checkAuroraTime()
    }

    private fun queueSettings(action: suspend () -> Unit) {
        if (!viewModelScope.isActive) return
        settingsRequests++
        mutableState.update { it.copy(busy = it.busy + "settings") }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (viewModelScope.isActive) report(failure)
            } finally {
                settingsRequests--
                if (settingsRequests == 0) mutableState.update { it.copy(busy = it.busy - "settings") }
            }
        }
    }

    override fun loadInstalledApps() = perform("applications") {
        val apps = withContext(Dispatchers.IO) {
            application.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filterNot { it.packageName == application.packageName }
                .map { InstalledApp(it.packageName, application.packageManager.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }
        mutableState.update { it.copy(installedApps = apps) }
    }

    override fun clearLogs() { mutableState.update { it.copy(logs = emptyList()) } }
    override fun dismissMessage() { mutableState.update { it.copy(message = null) } }
    fun showMessage(message: String) { mutableState.update { it.copy(message = message) } }

    private fun requireApi(): CoreApiClient = checkNotNull(api) { application.getString(R.string.error_api_unavailable) }

    private suspend fun reloadProfiles() {
        val snapshot = profileMaintenance.reload()
        mutableState.update { it.copy(profiles = snapshot.profiles, selectedProfile = snapshot.selectedId) }
        bindOutbound()
    }

    private suspend fun reloadRestoredData() {
        settingsUpdates.reload()
        reloadProfiles()
        invalidateOutboundConfiguration()
        checkAuroraTime()
    }

    private fun outboundKey(): OutboundKey? {
        val endpoint = application.vpn.value.endpoint ?: return null
        if (outboundBlockedEndpoint === endpoint) return null
        return OutboundKey(endpoint, state.value.selectedProfile ?: return null, state.value.settings.mode,
            outboundConfiguration)
    }

    private fun bindOutbound(snapshotPicks: Map<String, String>? = null) {
        val key = outboundKey()
        val picks = if (key != null && outboundGroupEndpoint === key.endpoint && currentSession?.current == true) {
            snapshotPicks ?: state.value.groups.routePicks()
        } else null
        outbound.bind(key, picks)
    }

    private fun invalidateOutboundConfiguration() {
        outboundConfiguration++
        bindDiagnostics(null)
        outboundBlockedEndpoint = application.vpn.value.endpoint
        outbound.bind(null)
    }

    private fun reconnectSelected(id: String) {
        if (state.value.selectedProfile == id && state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) connect()
    }

    private fun diagnosticsBindingCurrent(binding: DiagnosticsBinding): Boolean =
        visible.value.active && visible.value.generation == binding.generation && api === binding.client &&
            application.vpn.value.endpoint === binding.endpoint && binding.configuration == outboundConfiguration

    private suspend fun readDiagnosticServices(binding: DiagnosticsBinding, targets: List<ServiceTarget>): List<ServiceCheck> {
        val checks = binding.client.serviceChecks(targets)
        val stamp = checks.first()
        if (diagnostics.needsRouteSync(stamp.coreEpoch, stamp.picksVersion)) diagnosticsRouteRead.withLock {
            if (diagnosticsBindingCurrent(binding) && diagnostics.needsRouteSync(stamp.coreEpoch, stamp.picksVersion)) {
                val route = binding.client.routeSnapshot()
                acceptDiagnosticsRoute(binding, route.coreEpoch, route.picksVersion, route.picks)
            }
        }
        return checks
    }

    private fun bindDiagnostics(binding: DiagnosticsBinding?) {
        diagnosticsBinding = binding
        diagnosticsRouteRequest++
        diagnosticsRouteJob?.cancel()
        diagnostics.bind(binding)
    }

    private fun acceptDiagnosticsRoute(binding: DiagnosticsBinding, epoch: ULong, version: ULong,
        picks: Map<String, String>) {
        if (diagnosticsBinding == binding && diagnosticsBindingCurrent(binding)) diagnostics.route(epoch, version, picks)
    }

    private fun synchronizeDiagnosticsRoute() {
        val binding = diagnosticsBinding ?: return
        if (!diagnosticsBindingCurrent(binding)) return
        val request = ++diagnosticsRouteRequest
        diagnosticsRouteJob?.cancel()
        diagnosticsRouteJob = viewModelScope.launch {
            var failures = 0
            val retryDelays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
            while (isActive && request == diagnosticsRouteRequest && diagnosticsBindingCurrent(binding)) {
                try {
                    val route = diagnosticsRouteRead.withLock { binding.client.routeSnapshot() }
                    if (request == diagnosticsRouteRequest) acceptDiagnosticsRoute(binding, route.coreEpoch, route.picksVersion, route.picks)
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (request != diagnosticsRouteRequest || !diagnosticsBindingCurrent(binding)) return@launch
                    diagnostics.routeFailed(failure)
                    // Standard RouteTracker retries a failed synchronization; healthy routes have no timer.
                    delay(retryDelays[failures.coerceAtMost(retryDelays.lastIndex)])
                    if (failures < retryDelays.lastIndex) failures++
                }
            }
        }
    }

    private fun performInSession(key: String, session: TelemetrySession, action: suspend CoroutineScope.() -> Unit) {
        if (!session.current || key in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + key) }
        val job = session.launch(key, { mutableState.update { it.copy(busy = it.busy - key) } }) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                session.accept { report(failure) }
            }
        }
        if (job == null) mutableState.update { it.copy(busy = it.busy - key) }
    }

    private fun perform(key: String, action: suspend () -> Unit) {
        if (key in state.value.busy) return
        mutableState.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(failure)
            } finally {
                mutableState.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private fun report(failure: Throwable) { showMessage(failure.message ?: failure.javaClass.simpleName) }
}

internal fun auroraHandlerDelayMillis(delay: Duration): Long =
    (delay.toMillis() + if (delay.nano % 1_000_000 == 0) 0L else 1L).coerceAtLeast(1L)

internal class ControllerAuroraRotation(
    private val settings: () -> LiteSettings,
    private val onCheck: (ZonedDateTime, () -> Boolean) -> Unit,
    schedule: (Duration, () -> Unit) -> AuroraRotationHandle,
    clock: Clock? = null,
) {
    private var initialized = false
    private var disposed = false
    private var background = false
    private var generation = 0L
    private val rotation = AuroraDailyRotation(
        onCheck = { now ->
            val stamp = generation
            if (!disposed && background && settings().aurora) onCheck(now) {
                !disposed && background && generation == stamp && settings().aurora
            }
        },
        schedule = schedule,
        clock = clock,
    )

    fun initialize(state: AuroraLifecycleState?) {
        if (disposed || initialized) return
        initialized = true
        updateLifecycle(state)
        rotation.initialize(state)
    }

    fun setLifecycle(state: AuroraLifecycleState?) {
        if (disposed) return
        if (!initialized) {
            initialize(state)
            return
        }
        updateLifecycle(state)
        rotation.setLifecycle(state)
    }

    fun check() {
        if (disposed || !initialized) return
        generation++
        rotation.check()
    }

    fun dispose() {
        disposed = true
        background = false
        generation++
        rotation.dispose()
    }

    private fun updateLifecycle(state: AuroraLifecycleState?) {
        generation++
        background = state == AuroraLifecycleState.Hidden || state == AuroraLifecycleState.Paused
    }
}

internal class ControllerSettingsUpdates(
    read: suspend () -> LiteSettings,
    save: suspend (LiteSettings) -> LiteSettings,
    rotate: suspend (ZonedDateTime) -> LiteSettings,
    shuffle: suspend () -> LiteSettings,
    private val current: () -> LiteSettings,
    private val publish: (LiteSettings, Boolean) -> Unit,
) {
    private val readSettings = read
    private val saveSettings = save
    private val rotateSettings = rotate
    private val shuffleSettings = shuffle
    private val mutex = Mutex()
    private var latest: LiteSettings? = null
    private var deferredWallpaper = false
    private var closed = false

    suspend fun load(): Unit = mutex.withLock {
        if (!closed) publishVisible(loadLatest(), false)
    }

    suspend fun reload(): Unit = mutex.withLock {
        if (closed) return@withLock
        latest = null
        deferredWallpaper = false
        publishVisible(loadLatest(), false)
    }

    suspend fun save(baseline: LiteSettings, requested: LiteSettings): Unit = mutex.withLock {
        if (closed) return@withLock
        val settings = mergeSettingsChanges(baseline, requested, loadLatest())
        val saved = commit { saveSettings(settings) }
        if (baseline.auroraSeed != requested.auroraSeed || baseline.auroraRotationDay != requested.auroraRotationDay) {
            deferredWallpaper = false
        }
        publishVisible(saved, true)
    }

    suspend fun shuffle(): Unit = mutex.withLock {
        if (closed || !loadLatest().aurora) return@withLock
        val saved = commit { shuffleSettings() }
        deferredWallpaper = false
        publishVisible(saved, false)
    }

    suspend fun rotate(now: ZonedDateTime, eligible: () -> Boolean): Unit = mutex.withLock {
        if (closed || !eligible()) return@withLock
        val settings = loadLatest()
        if (!settings.aurora || closed || !eligible()) return@withLock
        val due = auroraRotationDayOf(now) > settings.auroraRotationDay
        val saved = if (due) commit { rotateSettings(now) } else settings
        if (closed) return@withLock
        if (eligible()) {
            if (due || deferredWallpaper) {
                deferredWallpaper = false
                publishVisible(saved, false)
            }
        } else if (saved.auroraSeed != current().auroraSeed || saved.auroraRotationDay != current().auroraRotationDay) {
            deferredWallpaper = true
        }
    }

    fun close() { closed = true }

    private suspend fun loadLatest(): LiteSettings = latest ?: readSettings().also { latest = it }

    // Finish an accepted atomic write and remember its result even after cancellation.
    private suspend fun commit(write: suspend () -> LiteSettings): LiteSettings = withContext(NonCancellable) {
        try {
            write().also { latest = it }
        } catch (failure: Throwable) {
            latest = null
            throw failure
        }
    }

    private fun publishVisible(saved: LiteSettings, restart: Boolean) {
        if (closed) return
        val shown = current()
        val visible = if (deferredWallpaper) saved.copy(
            auroraSeed = shown.auroraSeed,
            auroraRotationDay = shown.auroraRotationDay,
        ) else saved
        publish(visible, restart)
    }
}

private fun mergeSettingsChanges(baseline: LiteSettings, requested: LiteSettings, current: LiteSettings): LiteSettings {
    fun <T> choose(before: T, after: T, latest: T): T = if (after == before) latest else after
    return current.copy(
        material = choose(baseline.material, requested.material, current.material),
        aurora = choose(baseline.aurora, requested.aurora, current.aurora),
        colorMode = choose(baseline.colorMode, requested.colorMode, current.colorMode),
        themeColor = choose(baseline.themeColor, requested.themeColor, current.themeColor),
        themeColors = choose(baseline.themeColors, requested.themeColors, current.themeColors),
        followSystemColor = choose(baseline.followSystemColor, requested.followSystemColor, current.followSystemColor),
        editorFontSize = choose(baseline.editorFontSize, requested.editorFontSize, current.editorFontSize),
        editorLineWrap = choose(baseline.editorLineWrap, requested.editorLineWrap, current.editorLineWrap),
        mode = choose(baseline.mode, requested.mode, current.mode),
        ipv6 = choose(baseline.ipv6, requested.ipv6, current.ipv6),
        overrideDns = choose(baseline.overrideDns, requested.overrideDns, current.overrideDns),
        dnsServers = choose(baseline.dnsServers, requested.dnsServers, current.dnsServers),
        appFilterMode = choose(baseline.appFilterMode, requested.appFilterMode, current.appFilterMode),
        packages = choose(baseline.packages, requested.packages, current.packages),
        autoStart = choose(baseline.autoStart, requested.autoStart, current.autoStart),
        openLogs = choose(baseline.openLogs, requested.openLogs, current.openLogs),
        colorStyle = choose(baseline.colorStyle, requested.colorStyle, current.colorStyle),
        auroraColors = choose(baseline.auroraColors, requested.auroraColors, current.auroraColors),
        auroraSeed = choose(baseline.auroraSeed, requested.auroraSeed, current.auroraSeed),
        auroraRotationDay = choose(baseline.auroraRotationDay, requested.auroraRotationDay, current.auroraRotationDay),
        dashboardWidgets = choose(baseline.dashboardWidgets, requested.dashboardWidgets, current.dashboardWidgets),
        serviceOrder = choose(baseline.serviceOrder, requested.serviceOrder, current.serviceOrder),
        disabledServices = choose(baseline.disabledServices, requested.disabledServices, current.disabledServices),
    )
}
