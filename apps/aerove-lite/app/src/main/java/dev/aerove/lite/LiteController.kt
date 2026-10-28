package dev.aerove.lite

import dev.aerove.lite.vpn.uptimeStartNanos

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
import dev.aerove.lite.telemetry.OutboundAnswer
import dev.aerove.lite.telemetry.RouteSnapshotDemand
import dev.aerove.lite.telemetry.withDashboardHistory
import dev.aerove.lite.telemetry.TelemetrySession
import dev.aerove.lite.telemetry.TelemetryVisibility
import dev.aerove.lite.telemetry.sessionBindings
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.platform.LocalNetworkMonitor
import dev.aerove.lite.profiles.AndroidProfileDocuments
import dev.aerove.lite.profiles.ProfileListFeature
import dev.aerove.lite.ipquality.IpQualityFeature
import dev.aerove.lite.ipquality.IpQualityHttp
import dev.aerove.lite.ipquality.IpQualityStore
import dev.aerove.lite.ipquality.lookupIpQuality
import dev.aerove.lite.updates.AndroidUpdatePlatform
import dev.aerove.lite.updates.AndroidUpdateVerifier
import dev.aerove.lite.updates.UpdateFeature
import dev.aerove.lite.updates.UpdateHttpClient
import dev.aerove.lite.updates.UpdateRepository
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.vpn.requiresVpnRestart
import dev.aerove.lite.localization.AndroidLocaleCoordinator
import dev.aerove.lite.vpn.VpnSnapshot
import dev.aerove.lite.integration.withAppAccess
import dev.aerove.lite.integration.withoutDisplay
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LiteController(private val application: LiteApplication) : ViewModel(), LiteActions, LiteFeatureHost {
    val notices = dev.aerove.lite.notices.ClientNotices()
    override fun developerMessageTest() {
        if (!state.value.settings.developerMode || application.maintenanceActive) return
        dev.aerove.lite.notices.NoticeLevel.entries.forEach { level ->
            notices.post("${level.wireName}: ${application.getString(R.string.maintenance_message_test_tip)}", level)
        }
    }
    override fun developerLogsTest() = perform("developer-logs") {
        check(state.value.settings.developerMode) { "Developer mode is disabled" }
        val epoch = application.startIntentEpoch()
        val lines = withContext(Dispatchers.Default) {
            dev.aerove.lite.maintenance.developerLogSamples().map { LogLine(System.currentTimeMillis(), "info", it) }
        }
        observability.appendLocalLogs(lines) { !application.maintenanceActive && application.startIntentEpoch() == epoch && state.value.settings.developerMode }
    }
    private val mutableState = MutableStateFlow(LiteState())
    val state = mutableState.asStateFlow()
    private val displayOwner = dev.aerove.lite.integration.MutableLiteDisplayStreams()
    val display: dev.aerove.lite.integration.LiteDisplayStreams = displayOwner
    private var dashboardTrafficActive = false
    private var statusTrafficActive = false
    private val stateGate = Any()
    private val uiReadSignals = dev.aerove.lite.telemetry.UiReadSignals()
    private val readDemand get() = uiReadSignals.state.value
    private var memoryTelemetryRequested = false
    private var connectionTelemetryRequested = false
    private var routeSyncPending = false
    private val uiPublication = dev.aerove.lite.telemetry.UiTelemetryPublication(viewModelScope, { snapshot ->
        publishState { it.copy(traffic = snapshot.traffic, trafficHistory = snapshot.history,
            trafficHistoryRevision = snapshot.historyRevision, dashboardCounts = snapshot.counts) }
    })
    private fun publishState(change: (LiteState) -> LiteState) = synchronized(stateGate) {
        val next = change(displayOwner.attach(mutableState.value))
        displayOwner.publish(next)
        mutableState.value = next.withoutDisplay()
    }
    override val backup = BackupFeature(application, viewModelScope, ::reloadRestoredData,
        restoreTransaction = { write, refresh -> restoreWithRuntime(write, refresh) })
    private val workJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val sourceApplyOwner = lazy {
        dev.aerove.lite.integration.CoalescedSourceApply(viewModelScope, { !application.maintenanceActive }, { current ->
            val id = state.value.selectedProfile
            if (id == null) dev.aerove.lite.resources.ResourceApplyReceipt(null)
            else applyPreparedResource(application.profiles.prepareForStart(id), current)
        }, ::report).also { it.foreground(visible.value.active) }
    }
    private fun resourceRuntimeApply(current: () -> Boolean = { true }): dev.aerove.lite.integration.ResourceRuntimeApply {
        val startIntent = application.startIntentEpoch()
        return dev.aerove.lite.integration.ResourceRuntimeApply(application.vpn, { state.value.selectedProfile },
            application.profiles::prepare, {
                invalidateOutboundConfiguration()
                ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
                    .setAction(LiteVpnService.ACTION_RECONFIGURE))
            }, { endpoint ->
                CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot() }
            }, { current() && !application.maintenanceActive && startIntent == application.startIntentEpoch() }, application::reloadRules)
    }
    private suspend fun applyPreparedResource(prepared: dev.aerove.lite.data.PreparedProfile,
        current: () -> Boolean = { true }): dev.aerove.lite.resources.ResourceApplyReceipt = resourceRuntimeApply(current).apply(prepared)
    private suspend fun applyManagedResource(prepared: dev.aerove.lite.data.PreparedProfile): dev.aerove.lite.resources.ResourceApplyReceipt {
        val readback = resourceRuntimeApply()
        return readback.verifyReceipt(prepared, sourceApplyOwner.value.requestAndAwait())
    }
    private val resourceRepository by lazy { dev.aerove.lite.resources.ResourceManagementRepository(application.profiles, offlineRules = application.rulePayloadInspector) }
    private val resourceManagementOwner = lazy {
        dev.aerove.lite.resources.ResourceManagementSession(resourceRepository,
            viewModelScope, apply = {}, library = application.resourceLibrary,
            onLibraryChanged = { sourceApplyOwner.value.requestAndAwait() }, geoCatalog = application.geoResourceCatalog,
            applyWithReceipt = { applyManagedResource(it) })
    }
    override val groupIcons: dev.aerove.lite.images.GroupIconLoader get() = application.groupIcons
    override val resourceManagement get() = resourceManagementOwner.value
    private val globalResourcesOwner = lazy {
        dev.aerove.lite.integration.GlobalResourceWorkspace(application.builderResourceLibrary, application.profiles,
            application.globalRulePayloads, { yaml -> dev.aerove.lite.core.JniResourceFetch.offlineRead { dev.aerove.lite.core.MeowNativeCore.validate(it, yaml) } },
            { sourceApplyOwner.value.request() })
    }
    override val globalResources get() = globalResourcesOwner.value


    private val overrideOwner = lazy {
        dev.aerove.lite.integration.OverrideWorkspace(application.overrideRepository, application.subscriptionDownloader,
            viewModelScope, { !application.maintenanceActive }) { before, after ->
            withContext(Dispatchers.Main.immediate) {
                val id = state.value.selectedProfile
                if (id != null && state.value.profiles.firstOrNull { it.id == id }?.custom != true &&
                    dev.aerove.lite.overrides.overridePolicyFingerprint(before, id) != dev.aerove.lite.overrides.overridePolicyFingerprint(after, id)) {
                    requestSourceReapply()
                }
            }
        }
    }
    override val overrides get() = overrideOwner.value
    private val resourceMaintenanceOwner = lazy {
        dev.aerove.lite.resources.ResourceManagementMaintenance(application.geoResourceCatalog,
            application.resourceLibrary, overrides.scripts, { state.value.settings.geoResources },
            { sourceApplyOwner.value.requestAndAwait(); Unit })
    }
    private val resourcesOwner = lazy {
        dev.aerove.lite.integration.ResourcePageWorkspace(resourceManagement, overrides.scripts, resourceMaintenanceOwner.value, resourceRepository,
            viewModelScope, { state.value.selectedProfile }, { !application.maintenanceActive },
            { maintenanceCore() != null }, ::report)
    }
    override val resources get() = resourcesOwner.value
    private var providerTelemetryRequested = false
    private val profileDetailsOwner = lazy {
        dev.aerove.lite.integration.ProfileDetailWorkspace(application.profiles, resourceRepository, viewModelScope,
            { state.value.profiles.firstOrNull { it.id == state.value.selectedProfile } }, { application.vpn.value.endpoint },
            { !application.maintenanceActive }, ::applyManagedResource,
            { withContext(Dispatchers.Main.immediate) { invalidateOutboundConfiguration() } }, application::startIntentEpoch,
            { id -> application.overrideRepository.snapshot().profiles.firstOrNull { it.profileId == id }?.mode
                ?: dev.aerove.lite.overrides.OverrideMode.Standard },
            dev.aerove.lite.integration.ProfileDetailResources(application.profiles, resourceRepository, application.builderResourceLibrary, application.globalRulePayloads)).also { it.foreground(visible.value.active); it.telemetryActive(providerTelemetryRequested && readDemand.active) }
    }
    override val profileDetails get() = profileDetailsOwner.value

    private var resourceMaintenanceEndpoint: CoreEndpoint? = null
    private fun maintenanceCore(): CoreEndpoint? = application.vpn.value.takeIf { it.phase == VpnPhase.Running }?.endpoint
    private fun maintenanceRuntime(snapshot: dev.aerove.lite.vpn.VpnSnapshot) = dev.aerove.lite.integration.maintenanceRuntimeIdentity(snapshot)
    private val initialMaintenanceRuntime = maintenanceRuntime(application.vpn.value)
    private val initialMaintenanceNetworkRevision = application.physicalNetworks.revision.value
    private var maintenanceNetworkLease: dev.aerove.lite.platform.PhysicalNetworkLease? = null
    private val resourceMaintenanceLifecycle: Lazy<dev.aerove.lite.integration.ResourceMaintenanceLifecycle<CoreEndpoint>> = lazy {
        dev.aerove.lite.integration.ResourceMaintenanceLifecycle(viewModelScope, ::maintenanceCore,
            { !application.maintenanceActive },
            { full, current, available ->
                if (full) resourceMaintenanceOwner.value.updateIfDue(current, available)
                else resourceMaintenanceOwner.value.updateSkippedIfDue(current, available)
                Unit
            }, {
                profileMaintenance.resetSubscriptionBackoff()
                if (resourceMaintenanceOwner.isInitialized()) resourceMaintenanceOwner.value.resetAutomaticBackoff()
            }, initialMaintenanceNetworkRevision, initialMaintenanceRuntime,
            { error -> viewModelScope.launch { report(error) } })
    }
    private fun acquireMaintenanceNetwork() {
        if (maintenanceNetworkLease == null) {
            try { maintenanceNetworkLease = application.physicalNetworks.acquire(false) }
            catch (error: Exception) { report(error) }
        }
    }
    private fun releaseMaintenanceNetwork() {
        val owned = maintenanceNetworkLease ?: return
        maintenanceNetworkLease = null
        try { owned.close() } catch (error: Exception) { report(error) }
    }

    @Volatile private var maintenanceRevision: String? = null
    private val maintenanceOwner = lazy {
        dev.aerove.lite.maintenance.MaintenanceFeature(viewModelScope, object : dev.aerove.lite.maintenance.MaintenanceBackend {
            private var resetCommitted = false
            override suspend fun inspect(): dev.aerove.lite.maintenance.MaintenanceInventory = withContext(Dispatchers.IO) {
                application.profiles.inspectMaintenance().let {
                    maintenanceRevision = it.revision
                    dev.aerove.lite.maintenance.MaintenanceInventory(it.revision, it.profiles, it.retainedFiles, it.cacheBytes)
                }
            }
            override suspend fun <T> exclusive(operation: dev.aerove.lite.maintenance.MaintenanceOperation, action: suspend () -> T): T {
                var result: T? = null
                val clearing = operation == dev.aerove.lite.maintenance.MaintenanceOperation.ClearAllData
                resetCommitted = false
                try {
                    restoreWithRuntime({ result = action() }, ::reloadRestoredData, resumeAfterSuccess = !clearing, restoreLocale = clearing)
                    if (clearing && resetCommitted) checkNotNull(exitAfterStop) { "Application exit owner is unavailable" }.invoke()
                    @Suppress("UNCHECKED_CAST")
                    return result as T
                } catch (failure: Throwable) {
                    if (resetCommitted) withContext(NonCancellable + Dispatchers.Main.immediate) {
                        try {
                            settingsUpdates.reload()
                            reloadProfiles()
                            android.widget.Toast.makeText(application, application.getString(R.string.maintenance_failed), android.widget.Toast.LENGTH_LONG).show()
                            checkNotNull(exitAfterStop) { "Application exit owner is unavailable" }.invoke()
                        } catch (exitFailure: Throwable) { failure.addSuppressed(exitFailure) }
                    }
                    throw failure
                }
            }
            override suspend fun pruneCache(): Unit = withContext(Dispatchers.IO) {
                application.profiles.pruneCache(maintenanceRevision)
                Unit
            }
            override suspend fun clearAppDataAndExit() = withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) {
                    application.profiles.resetUserData(maintenanceRevision) { application.settings.reset() }
                }
                resetCommitted = true
                var failure = if (result.cleanupComplete) null else checkNotNull(result.cleanupFailure) { "User data reset cleanup did not finish" }
                suspend fun cleanup(action: suspend () -> Unit) {
                    try { action() } catch (next: Throwable) {
                        if (failure == null) failure = next else failure!!.addSuppressed(next)
                    }
                }
                withContext(NonCancellable) {
                    cleanup { if (ipQualityOwner.isInitialized()) ipQualityOwner.value.clearUserState()
                        else withContext(Dispatchers.IO) { IpQualityStore(File(application.filesDir, "ipquality/last-exit.json")).clearUserState() } }
                    if (updateOwner.isInitialized()) cleanup { updateOwner.value.clearUserState() } else {
                        cleanup { dev.aerove.lite.updates.AndroidUpdatePlatform.clearUserState(application) }
                        cleanup { withContext(Dispatchers.IO) { UpdateRepository.clearCachedUserState(File(application.cacheDir, "updates")) } }
                    }
                    cleanup { LiteVpnService.clearUserState(application) }
                    cleanup { application.closeGroupIcons() }
                }
                failure?.let { throw it }
                Unit
            }
        })
    }
    override val maintenance get() = maintenanceOwner.value
    private var openedAppAccess: dev.aerove.lite.appaccess.AppAccessFeature? = null
    override val appAccess: dev.aerove.lite.appaccess.AppAccessFeature
        get() = openedAppAccess ?: dev.aerove.lite.appaccess.AppAccessFeature(viewModelScope,
            state.value.settings.appAccess, dev.aerove.lite.appaccess.AndroidApplicationLoader(application)) { preferences ->
            check(!application.maintenanceActive) { "Maintenance is active" }
            val saved = settingsUpdates.update(false) { latest ->
                latest.withAppAccess(preferences)
            }
            check(application.settings.read() == saved) { "Application preferences readback changed" }
        }.also { openedAppAccess = it }
    override fun closeAppAccess() { openedAppAccess?.close(); openedAppAccess = null }
    var onDemandLocationRequest: (() -> Unit)? = null
    var batteryExemptionRequest: (() -> Unit)? = null
    var applicationsAuthorization: (() -> Unit)? = null
    var exitAfterStop: (() -> Unit)? = null
    private var pendingExternalInput: String? = null
    private var externalOfferJob: Job? = null
    private var externalOfferGeneration = 0L
    private val platformEntryGate = dev.aerove.lite.integration.PlatformEntryGate()
    private val vpnAuthorizations = dev.aerove.lite.integration.VpnAuthorizationIntents(::currentStartRequest)
    fun offerVpnAuthorization(token: Long, settings: LiteSettings? = null) = vpnAuthorizations.offer(token, settings, state.value.settings)
    fun applyAuthorizedSettings(request: dev.aerove.lite.integration.VpnAuthorizationRequest) {
        val wanted = request.settings ?: return
        if (currentStartRequest(request.token)) queueSettings {
            settingsUpdates.save(checkNotNull(request.baseline), wanted)
        }
    }
    fun nextVpnAuthorization() = vpnAuthorizations.next()
    fun completeVpnAuthorization(granted: Boolean) = vpnAuthorizations.complete(granted)
    fun cancelSettingsAuthorization() = vpnAuthorizations.cancelSettings()
    private var startIntentEpoch = 0L
    private var lastPlatformPermissions: Pair<Boolean, Boolean>? = null
    fun acceptPlatformRequest(id: String): Boolean {
        return platformEntryGate.acceptRequest(id)
    }
    fun beginStartRequest(): Long { startIntentEpoch = application.startIntentEpoch(); return platformEntryGate.beginStart() }
    fun currentStartRequest(token: Long): Boolean = platformEntryGate.currentStart(token) &&
        startIntentEpoch == application.startIntentEpoch() && !application.maintenanceActive
    fun consumeAutoRun(): Boolean = state.value.let { platformEntryGate.consumeAutoRun(it.initialized,
        it.settings.acceptedDisclaimerVersion >= dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION,
        it.settings.backgroundPolicy.autoRun, it.selectedProfile != null, application.vpn.value.phase == VpnPhase.Stopped) }
    fun stopFromPlatform() { platformEntryGate.suppressAutoRun(); disconnect() }
    fun refreshPlatformPermissions() {
        val fine = ContextCompat.checkSelfPermission(application, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(application, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val location = application.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
        val battery = application.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(application.packageName)
        val actual = (fine && background && location) to battery
        val changed = lastPlatformPermissions?.let { it != actual } == true
        lastPlatformPermissions = actual
        publishState { it.copy(locationGranted = actual.first, batteryIgnored = actual.second) }
        openedAppAccess?.reload()
        if (changed && LiteVpnService.wasRequested(application)) application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_REFRESH_POLICY))
    }
    fun exitApplication(onStopped: () -> Unit) {
        if (application.maintenanceActive) return
        viewModelScope.launch {
            try {
                application.withMaintenance {
                    application.invalidatePendingStarts()
                    if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.quiesce()
                    externalOfferJob?.cancelAndJoin()
                    dismissExternalProfile()
                    if (ipQualityOwner.isInitialized()) ipQualityOwner.value.quiesce()
                    if (updateOwner.isInitialized()) updateOwner.value.quiesce()
                    profileMaintenance.quiesce()
                    resourceMaintenanceLifecycle.value.quiesce()
                    if (resourceMaintenanceOwner.isInitialized()) resourceMaintenanceOwner.value.quiesce()
                    if (profileImportsOwner.isInitialized()) profileImportsOwner.value.quiesce()
                    if (profileListOwner.isInitialized()) profileListOwner.value.quiesce()
                    workJobs.values.toList().forEach { it.cancelAndJoin() }
                    if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.quiesce()
                    if (resourcesOwner.isInitialized()) resourcesOwner.value.quiesce()
                    if (overrideOwner.isInitialized()) overrideOwner.value.quiesce()
                    if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.quiesce()
                    if (globalResourcesOwner.isInitialized()) globalResourcesOwner.value.quiesce()
                    if (resourceManagementOwner.isInitialized()) resourceManagementOwner.value.quiesce()
                    settingsUpdates.awaitIdle()
                    val context = application.stopForMaintenance()
                    application.resumeAfterMaintenance(context, null, null, false)
                }
                onStopped()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
        }
    }
    fun offerExternalProfile(input: String) {
        if (application.maintenanceActive) return
        externalOfferJob?.cancel()
        val generation = ++externalOfferGeneration
        externalOfferJob = viewModelScope.launch {
            try {
                val decoded = profileLinks.decode(input)
                val entry = decoded.entries.singleOrNull() as? ProfileLinkEntry.Subscription
                require(decoded.errors.isEmpty() && entry != null) { application.getString(R.string.platform_external_link_invalid) }
                if (generation != externalOfferGeneration || application.maintenanceActive) return@launch
                // Standard's platform link handler imports the approved URL, not unrelated install-link query metadata.
                pendingExternalInput = entry.url
                publishState { it.copy(externalProfile = entry.url) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == externalOfferGeneration) showMessage(application.getString(R.string.platform_external_link_invalid)) }
        }
    }
    override fun acceptExternalProfile() {
        if (application.maintenanceActive || workJobs.containsKey("import")) return
        val input = pendingExternalInput ?: return
        dismissExternalProfile()
        importProfileText("", input)
    }
    override fun dismissExternalProfile() {
        externalOfferGeneration++; externalOfferJob?.cancel()
        pendingExternalInput = null; publishState { it.copy(externalProfile = null) }
    }
    fun pendingExternalProfile(): String? = pendingExternalInput
    override fun requestOnDemandLocation() { onDemandLocationRequest?.invoke() }
    override fun requestBatteryExemption() { batteryExemptionRequest?.invoke() }
    override fun authorizeApplications() { applicationsAuthorization?.invoke() }
    override fun declineDisclaimer() { exitAfterStop?.let(::exitApplication) }
    private var openedBuilder: Pair<String, dev.aerove.lite.profilebuilder.ProfileBuilderSession>? = null
    private var openedEditor: Pair<Triple<String, String, Boolean>, ProfileEditorSession>? = null
    private val proxyPresentationOwner = lazy {
        dev.aerove.lite.integration.ProxyPresentationWorkspace(viewModelScope,
            { state.value.settings.proxyPresentation }, application.profiles::updateProxyGroupUi,
            { saved -> publishState { current -> current.copy(profiles = current.profiles.map {
                if (it.id == saved.id) it.copy(currentProxyGroup = saved.currentProxyGroup,
                    unfoldedProxyGroups = saved.unfoldedProxyGroups) else it
            }) } }, { !application.maintenanceActive }, onDelayPending = { currentGroupRefresh?.testing(it) }).also {
            it.profile(state.value.profiles.find { row -> row.id == state.value.selectedProfile })
            it.groups(state.value.groups)
            bindProxyPresentation(it)
        }
    }
    override val proxyPresentation get() = proxyPresentationOwner.value
    private fun bindProxyPresentation(owner: dev.aerove.lite.integration.ProxyPresentationWorkspace) {
        val endpoint = application.vpn.value.endpoint
        val client = api
        val read: (suspend () -> String)? = endpoint?.configuration?.let { config -> suspend { config.read() } }
        owner.bind(client, endpoint?.configuration?.profileId, client, endpoint?.configuration, read) {
            api === client && visible.value.active && application.vpn.value.endpoint === endpoint &&
                !application.maintenanceActive
        }
        acceptedRoute?.takeIf { it.client === client }?.let { owner.routeEpoch(it.client, it.epoch) }
    }

    private val profileLinks = ProfileLinkDecoder()
    private val visible = MutableStateFlow(TelemetryVisibility())
    private val connectionTelemetryActive = MutableStateFlow(false)
    private var api: CoreApiClient? = null
    private var currentSession: TelemetrySession? = null
    private var currentGroupRefresh: GroupRefresh<List<ProxyGroup>>? = null
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
        }, onAutomaticError = { _, error -> viewModelScope.launch { report(error) } },
        afterAutomaticSweep = { current -> resourceMaintenanceLifecycle.value.afterAutomaticSweep(current) })
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
    private val ipQualityOwner = lazy {
        IpQualityFeature(viewModelScope, { ip -> lookupIpQuality(ip, query = { source, address ->
            val binding = diagnosticsBinding ?: throw java.io.IOException("Core is unavailable")
            val row = binding.client.ipQualityBody(source.name, address, !binding.proxied)
            if (!diagnosticsBindingCurrent(binding)) throw CancellationException("IP quality binding changed")
            when (row.getInt("status")) {
                200 -> dev.aerove.lite.ipquality.parseIpQuality(source, address, org.json.JSONObject(row.getString("body")))
                429 -> throw dev.aerove.lite.ipquality.IpQualitySourceException(dev.aerove.lite.ipquality.IpQualitySourceStatus.RateLimited)
                else -> throw dev.aerove.lite.ipquality.IpQualitySourceException(dev.aerove.lite.ipquality.IpQualitySourceStatus.Failed)
            }
        }) }, {},
            IpQualityStore(File(application.filesDir, "ipquality/last-exit.json"))).also {
            it.readDemand(readDemand.active)
            it.setForeground(visible.value.active)
            it.bind(state.value.selectedProfile, state.value.phase == VpnPhase.Running, outbound.state.value.identity?.address,
                outbound.state.value.identity?.countryCode)
        }
    }
    override val ipQuality: IpQualityFeature get() = ipQualityOwner.value
    private var observationRuntime: CoreEndpoint? = null
    private var observationToken = 0L
    private val observabilityOwner = lazy {
        dev.aerove.lite.observability.ObservabilityFeature(viewModelScope,
            dev.aerove.lite.observability.AndroidHistoryDocuments(application.contentResolver), clearDnsCache = {
                val client = requireApi()
                val endpoint = application.vpn.value.endpoint
                val token = observationToken
                client.clearDnsCache()
                check(api === client && application.vpn.value.endpoint === endpoint && observationToken == token && visible.value.active) {
                    application.getString(R.string.error_api_unavailable)
                }
            }).also {
            it.lease(dev.aerove.lite.observability.HistoryKind.Requests, visible = false)
            it.setForeground(readDemand.active)
            it.bindSession(observationRuntime?.let { observationToken })
            val feature = it
            viewModelScope.launch {
                feature.counts.collect { counts ->
                    if (feature.counts.value === counts) uiPublication.history(counts)
                }
            }
        }
    }
    override val observability get() = observabilityOwner.value
    private fun bindObservability(endpoint: CoreEndpoint?) {
        if (observationRuntime === endpoint) return
        observationRuntime = endpoint
        observationToken++
        uiPublication.reset()
        diagnostics.runtimeChanged()
        publishState { it.resetDashboardRuntime().copy(groups = emptyList(), connections = emptyList(), traffic = Traffic()) }
        if (observabilityOwner.isInitialized()) observabilityOwner.value.bindSession(endpoint?.let { observationToken })
    }
    private data class DiagnosticsBinding(val endpoint: CoreEndpoint, val client: CoreApiClient,
        val generation: Long, val configuration: Long, val proxied: Boolean, val suspended: Boolean)
    private var serviceTelemetryTargets = emptySet<String>()
    private val dashboardConnections = dev.aerove.lite.telemetry.DashboardConnectionSummary<DiagnosticsBinding>(
        viewModelScope, { it.client.connectionCount() }, ::diagnosticsBindingCurrent)
    private val diagnosticsRouteRead = Mutex()
    private val diagnosticsRouteDemand = RouteSnapshotDemand()
    private val diagnostics: DiagnosticsCoordinator<DiagnosticsBinding> = DiagnosticsCoordinator(viewModelScope,
        readServices = ::readDiagnosticServices,
        readMemory = { it.client.memorySnapshot() }, isCurrent = ::diagnosticsBindingCurrent,
        sameSession = { a, b -> a.endpoint === b.endpoint && a.configuration == b.configuration &&
            a.proxied == b.proxied && a.suspended == b.suspended }).also { it.readDemand(readDemand.active) }
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
    private var settingsLoaded = false
    private val localeOwner = lazy {
        AndroidLocaleCoordinator(application, application.settings.changes, viewModelScope,
            onPlatformChoice = { tag ->
                val baseline = state.value.settings
                settingsUpdates.save(baseline, baseline.copy(locale = tag))
            }, onError = { showMessage(application.getString(R.string.editor_error)) })
    }
    private val updateOwner = lazy {
        UpdateFeature(UpdateRepository(UpdateHttpClient(), File(application.cacheDir, "updates"), AndroidUpdateVerifier(application)),
            AndroidUpdatePlatform(application, application.settings), viewModelScope,
            onCloseFailure = { failure -> android.util.Log.e("LiteUpdates", "Owned update shutdown failed", failure) },
            onAutoCheckChanged = { enabled ->
                withContext(Dispatchers.Main.immediate) {
                    val baseline = state.value.settings
                    settingsUpdates.save(baseline, baseline.copy(autoCheckUpdate = enabled))
                }
            }).also { feature ->
            viewModelScope.launch {
                feature.state.collect { update -> publishState { it.copy(updatePromptVisible = update.shouldShowPrompt) } }
            }
        }
    }
    override val updates: UpdateFeature get() = updateOwner.value
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
    private data class OutboundKey(val endpoint: CoreEndpoint, val profileId: String,
        val configuration: Long, val proxied: Boolean)
    private var outboundConfiguration = 0L
    private var outboundBlockedEndpoint: CoreEndpoint? = null
    private data class AcceptedRoute(val client: CoreApiClient, val epoch: ULong, val version: ULong, val picks: Map<String, String>)
    private var acceptedRoute: AcceptedRoute? = null
    private val outbound = OutboundProbe<OutboundKey>(viewModelScope, { key ->
        val answer = CoreApiClient(ControlEndpoint(key.endpoint.port, key.endpoint.secret)).use { it.outboundProbe(forceDirect = !key.proxied) }
        if (readDemand.active) answer else confirmExplicitOutbound(key, answer)
    }, { it == outboundKey() }).also { it.readDemand(readDemand.active) }
    var filePicker: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            dashboardConnections.state.collect { count ->
                if (count != null || (readDemand.active && dashboardTrafficActive) || application.vpn.value.endpoint == null) {
                    uiPublication.connections(count)
                }
            }
        }
        viewModelScope.launch {
            diagnostics.state.collect { snapshot ->
                publishState { it.copy(diagnostics = snapshot) }
                reconcileRouteDemand()
            }
        }
        viewModelScope.launch {
            profileMaintenance.runningProfileIds.collect { ids -> publishState { it.copy(updatingProfileIds = ids) } }
        }
        viewModelScope.launch {
            outbound.state.collect { snapshot ->
                publishState { it.copy(outboundIdentity = snapshot.identity,
                    outboundLoading = snapshot.loading, outboundError = snapshot.error) }
                bindIpQuality(snapshot.identity?.address)
                reconcileRouteDemand()
            }
        }
        perform("load") {
            settingsUpdates.load()
            settingsLoaded = true
            localeOwner.value.foreground()
            autoCheckUpdates()
            reloadProfiles()
            publishState { it.copy(initialized = true) }
            resourceMaintenanceLifecycle.value.ready()
            refreshPlatformPermissions()
            LiteVpnService.consumeStartupFailure(application)?.let(::showMessage)
        }
        viewModelScope.launch {
            application.vpn.collect { snapshot ->
                if (resourceMaintenanceEndpoint !== snapshot.endpoint) {
                    resourceMaintenanceEndpoint = snapshot.endpoint
                    if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.endpointChanged()
                }
                resourceMaintenanceLifecycle.value.runtime(maintenanceRuntime(snapshot), maintenanceCore())
                bindObservability(snapshot.endpoint)
                currentSession?.takeUnless { it.current }?.close()
                publishState { it.copy(phase = snapshot.phase, message = snapshot.error ?: it.message,
                    networkRestartRequired = requiresNetworkRestart(it.settings, snapshot), networkError = snapshot.networkError,
                    listening = snapshot.listening, suspended = snapshot.suspended, listenerError = snapshot.listenerError) }
                if (snapshot.endpoint != null && outboundBlockedEndpoint !== snapshot.endpoint) outboundBlockedEndpoint = null
                bindOutbound()
                diagnosticsBinding?.takeIf { it.endpoint === snapshot.endpoint }?.let { previous ->
                    val next = previous.copy(proxied = outboundKey()?.proxied == true, suspended = snapshot.suspended)
                    if (next != previous) { bindDiagnostics(next); synchronizeDiagnosticsRoute() }
                }
            }
        }
        viewModelScope.launch {
            uiReadSignals.state.collectLatest { foreground ->
                if (foreground.active) LocalNetworkMonitor(application.physicalNetworks, ::report).addresses().collect { address ->
                    publishState { it.copy(localAddress = address) }
                }
            }
        }
        viewModelScope.launch {
            combine(uiReadSignals.state, application.vpn) { foreground, snapshot ->
                snapshot.uptimeStartNanos(foreground.active) to foreground.generation
            }.distinctUntilChanged().collectLatest { (started, _) ->
                if (started == 0L) {
                    if (application.vpn.value.uptimeStartNanos(true) == 0L) publishState { it.copy(uptimeMillis = 0) }
                } else while (true) {
                    val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                    publishState { it.copy(uptimeMillis = elapsed) }
                    delay(1000)
                }
            }
        }
        viewModelScope.launch {
            application.physicalNetworks.revision.collectLatest { revision ->
                resourceMaintenanceLifecycle.value.network(revision)
                delay(300)
                if (application.vpn.value.endpoint != null) {
                    diagnostics.networkChanged(); outbound.networkChanged()
                }
            }
        }
        viewModelScope.launch {
            sessionBindings(visible, application.vpn.map { it.endpoint })
                .collectLatest { binding ->
                    api = null
                    if (proxyPresentationOwner.isInitialized()) bindProxyPresentation(proxyPresentationOwner.value)
                    bindDiagnostics(null)
                    outbound.awaitingGroups()
                    if (binding == null) return@collectLatest
                    val (endpoint, generation) = binding
                    bindObservability(endpoint)
                    val historyToken = observationToken
                    val client = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
                    api = client
                    if (proxyPresentationOwner.isInitialized()) bindProxyPresentation(proxyPresentationOwner.value)
                    bindDiagnostics(DiagnosticsBinding(endpoint, client, generation, outboundConfiguration, outboundKey()?.proxied == true, application.vpn.value.suspended))
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
                                    publishState { it.copy(groups = snapshot) }
                                    if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.groups(snapshot)
                                    val accepted = acceptedRoute?.takeIf { it.client === client }
                                    val version = currentGroupRefresh?.routeVersion
                                    if (accepted != null && version != null && accepted.version == version) {
                                        outbound.replayConfirmedRoute(accepted.picks, accepted.epoch)
                                    } else {
                                        outbound.awaitingGroups()
                                        bindOutbound(awaitingRoute = true)
                                        synchronizeDiagnosticsRoute()
                                    }
                                }, { it.isEmpty() }, healthUpdate = { snapshot ->
                                    publishState { it.copy(groups = snapshot) }
                                    if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.groups(snapshot)
                                    acceptedRoute?.takeIf { it.client === client }?.let {
                                        outbound.replayConfirmedRoute(it.picks, it.epoch)
                                    }
                                })
                                currentGroupRefresh = groups
                                groups.readDemand(readDemand.active)
                                groups.initial()
                                session.observeReadOnly("logs", combine(uiReadSignals.state, mutableState.map { it.settings.openLogs }) { lease, enabled ->
                                    lease.copy(active = lease.active && enabled)
                                }, { client.logs().flowOn(Dispatchers.Default) },
                                    { it.generation == readDemand.generation && readDemand.active && state.value.settings.openLogs }, ::report) { line ->
                                    if (state.value.settings.openLogs) {
                                        observability.acceptLog(historyToken, line)
                                    }
                                }
                                session.launch("observation-demand") {
                                    combine(uiReadSignals.state, observability.observationsEnabled) { lease, enabled -> lease.active && enabled }
                                        .distinctUntilChanged().collect { client.setTelemetryObservations(it) }
                                }
                                session.observeReadOnly("telemetry", uiReadSignals.state, client::telemetry,
                                    { it == readDemand && it.active }, ::report) { event ->
                                    when (event) {
                                        is CoreTelemetry.ProvidersLoaded -> profileDetails.providersLoaded(endpoint,event)
                                        is CoreTelemetry.Observations -> observability.acceptWindow(historyToken, event.value)
                                        is CoreTelemetry.TrafficSample -> if (readDemand.active && (dashboardTrafficActive || statusTrafficActive || connectionTelemetryRequested)) {
                                            uiPublication.traffic(event.value, dashboardTrafficActive)
                                        }
                                        is CoreTelemetry.GroupsChanged -> if (groups.changed(event.revision, event.routeVersion)) {
                                            diagnosticsRouteDemand.invalidate()
                                            diagnosticsRouteRequest++
                                            diagnosticsRouteJob?.cancel()
                                            diagnostics.awaitingRoute()
                                            outbound.awaitingGroups()
                                        }
                                    }
                                }
                                session.observeReadOnly("connections", combine(uiReadSignals.state, connectionTelemetryActive) { lease, enabled ->
                                    lease.copy(active = lease.active && enabled)
                                }, client::connectionUpdates,
                                    { it.generation == readDemand.generation && readDemand.active && connectionTelemetryRequested }, ::report) { connections ->
                                    if (connectionTelemetryActive.value) {
                                        publishState { it.copy(connections = connections) }
                                    }
                                }
                                awaitCancellation()
                            } finally {
                                session.close()
                                if (currentSession === session) { currentSession = null; currentGroupRefresh = null }
                            }
                        }
                    } finally {
                        if (api === client) {
                            api = null
                            if (proxyPresentationOwner.isInitialized()) bindProxyPresentation(proxyPresentationOwner.value)
                        }
                        if (diagnosticsBinding?.client === client) bindDiagnostics(null)
                        client.close()
                    }
                }
        }
    }

    fun setVisible(value: Boolean) {
        visible.update { it.changed(value) }
        uiReadSignals.foreground(value)
        updateReadDemand()
        if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.foreground(value)
        if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.foreground(value)
        outbound.setActive(value)
        diagnostics.foreground(value)
        if (observabilityOwner.isInitialized()) observabilityOwner.value.setForeground(readDemand.active)
        if (ipQualityOwner.isInitialized()) ipQualityOwner.value.setForeground(value)
        if (value) acquireMaintenanceNetwork()
        profileMaintenance.setForeground(value)
        resourceMaintenanceLifecycle.value.foreground(value)
        if (!value) releaseMaintenanceNetwork()
        autoCheckUpdates()
        if (value) localeOwner.value.foreground()
        if (proxyPresentationOwner.isInitialized()) {
            proxyPresentationOwner.value.foreground(value)
            if (!value) proxyPresentationOwner.value.active(false)
        }
        if (!value) bindDiagnostics(null)
        if (!value) currentSession?.close()
    }

    private fun autoCheckUpdates() {
        if (settingsLoaded && visible.value.active) updates.autoCheckOnce()
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
        uiPublication.close()
        backup.close()
        if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.close()
        resourceMaintenanceLifecycle.value.close()
        releaseMaintenanceNetwork()
        if (resourcesOwner.isInitialized()) resourcesOwner.value.close()
        if (globalResourcesOwner.isInitialized()) globalResourcesOwner.value.close()
        if (resourceManagementOwner.isInitialized()) resourceManagementOwner.value.close()
        if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.close()
        if (observabilityOwner.isInitialized()) observabilityOwner.value.close()
        if (profileListOwner.isInitialized()) profileListOwner.value.close()
        if (ipQualityOwner.isInitialized()) ipQualityOwner.value.close()
        if (updateOwner.isInitialized()) updateOwner.value.close()
        if (localeOwner.isInitialized()) localeOwner.value.close()
        profileMaintenance.close()
        if (profileImportsOwner.isInitialized()) profileImportsOwner.value.close()
        diagnostics.close()
        dashboardConnections.close()
        diagnosticsRouteJob?.cancel()
        outbound.close()
        openedEditor = null
        closeAppAccess()
        if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.close()
        if (overrideOwner.isInitialized()) overrideOwner.value.close()
        if (maintenanceOwner.isInitialized()) maintenanceOwner.value.close()
        notices.close()
        aurora.dispose()
        auroraCheckJob?.cancel()
        settingsUpdates.close()
        filePicker = null
        onDemandLocationRequest = null
        batteryExemptionRequest = null
        applicationsAuthorization = null
        exitAfterStop = null
        super.onCleared()
    }

    override fun setProviderTelemetryActive(active: Boolean) {
        providerTelemetryRequested=active
        if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.telemetryActive(active && readDemand.active)
    }
    override fun setUiWindowFocused(focused: Boolean) {
        val previous = readDemand
        uiReadSignals.focused(focused)
        if (previous != readDemand) updateReadDemand()
    }
    override fun setTelemetryInteractionActive(active: Boolean) { uiPublication.interacting(active) }
    private fun updateReadDemand() {
        val active = readDemand.active
        if (!active) { uiPublication.active(false); outbound.awaitingGroups(); diagnostics.awaitingRoute(); routeSyncPending = true }
        if (observabilityOwner.isInitialized()) {
            observabilityOwner.value.setForeground(active)
            uiPublication.history(observabilityOwner.value.counts.value)
        }
        currentGroupRefresh?.readDemand(active)
        outbound.readDemand(active); diagnostics.readDemand(active)
        if (ipQualityOwner.isInitialized()) ipQualityOwner.value.readDemand(active)
        if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.telemetryActive(providerTelemetryRequested && active)
        connectionTelemetryActive.value = connectionTelemetryRequested && active
        val counts = dashboardTrafficActive && active
        uiPublication.countsActive(counts)
        dashboardConnections.active(counts)
        diagnostics.memory(memoryTelemetryRequested && active)
        if (active) uiPublication.active(true)
        reconcileRouteDemand()
    }
    private fun routeReadsAllowed() = readDemand.active || diagnostics.explicitPending || outbound.explicitPending
    private fun reconcileRouteDemand() {
        if (!routeReadsAllowed()) {
            if (diagnosticsRouteJob?.isActive == true) routeSyncPending = true
            diagnosticsRouteRequest++; diagnosticsRouteDemand.invalidate(); diagnosticsRouteJob?.cancel(); diagnosticsRouteJob = null
        } else if (routeSyncPending && !readDemand.active) synchronizeDiagnosticsRoute()
    }
    override fun setConnectionTelemetryActive(active: Boolean) {
        connectionTelemetryRequested = active; connectionTelemetryActive.value = active && readDemand.active
    }
    override fun setDashboardTelemetryActive(active: Boolean) {
        dashboardTrafficActive = active
        val demand = active && readDemand.active
        uiPublication.countsActive(demand)
        dashboardConnections.active(demand)
    }
    override fun setStatusTrafficActive(active: Boolean) { statusTrafficActive = active }
    override fun setMemoryTelemetryActive(active: Boolean) {
        memoryTelemetryRequested = active; diagnostics.memory(active && readDemand.active)
    }
    override fun setServiceTelemetryTargets(ids: Set<String>) { serviceTelemetryTargets = ids.toSet(); diagnostics.watch(serviceTelemetryTargets) }
    override fun refreshServiceChecks() {
        diagnostics.refresh(serviceTelemetryTargets)
        synchronizeDiagnosticsRoute()
    }
    override fun refreshServiceCheck(id: String) {
        diagnostics.refresh(setOf(id))
        synchronizeDiagnosticsRoute()
    }

    override fun connect() {
        if (application.maintenanceActive) return
        if (state.value.settings.network.vpnEnabled && VpnService.prepare(application) != null) {
            showMessage(application.getString(R.string.error_vpn_permission))
            return
        }
        if (state.value.selectedProfile == null) {
            showMessage(application.getString(R.string.error_profile_required))
            return
        }
        ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
            .setAction(LiteVpnService.ACTION_START).putExtra(LiteVpnService.EXTRA_REQUESTED_AT_NANOS, System.nanoTime()))
    }

    override fun disconnect() {
        platformEntryGate.cancelStart()
        application.invalidatePendingStarts()
        if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.cancelPending()
        application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    }

    override fun importFile() { filePicker?.invoke() }

    override suspend fun createProfileBuilder(name: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession {
        check(!application.maintenanceActive) { "Maintenance is active" }
        val session = withContext(Dispatchers.Default) {
            dev.aerove.lite.profilebuilder.ProfileBuilderSession.create(application.profiles, name)
        }
        openedBuilder = session.profileId to session
        return session
    }

    override fun profileBuilder(profileId: String): dev.aerove.lite.profilebuilder.ProfileBuilderSession {
        openedBuilder?.takeIf { it.first == profileId }?.let { return it.second }
        return dev.aerove.lite.profilebuilder.ProfileBuilderSession(application.profiles, profileId).also {
            openedBuilder = profileId to it
        }
    }

    override suspend fun profileBuilderResources() = application.builderResourceLibrary.snapshot().resources
    override fun closeProfileBuilder() { openedBuilder = null }

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

    private val profileImportsOwner = lazy {
        dev.aerove.lite.integration.ProfileImportWorkspace(viewModelScope, application.profiles,
            { !application.maintenanceActive }, { reloadProfiles() },
            { prompt -> publishState { it.copy(profileImportKeyPrompt = prompt) } },
            { publishState { it.copy(profileImportVisible = false) } },
            { issues -> notices.post(if (issues.isEmpty()) application.getString(R.string.age_import_failed)
                else issues.joinToString("\n") { "${it.path}: ${it.reason}" }, dev.aerove.lite.notices.NoticeLevel.Error) })
    }
    override val profileImports get() = profileImportsOwner.value

    override fun importProfileText(name: String, input: String) {
        if (application.maintenanceActive) return
        publishState { it.copy(profileImportVisible = true) }
        profileImports.single(dev.aerove.lite.profilesource.NamedProfileInput(name, input))
    }

    fun importFile(uri: Uri) = perform("import") {
        val name = withContext(Dispatchers.IO) {
            application.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: application.getString(R.string.default_profile_name)
        }
        profileImports.importFile(name) {
            var opened: java.io.InputStream? = null
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(application.contentResolver.openInputStream(uri)) { application.getString(R.string.error_read_file) }
                        .also { opened = it }
                }.also { opened = null }
            } catch (failure: Throwable) { opened?.close(); throw failure }
        }
    }

    override fun addSubscription(name: String, url: String) = importProfileText(name, url)

    override fun updateProfile(id: String) = perform("profile:$id") {
        when (val result = profileMaintenance.refresh(id)) {
            is ProfileMaintenanceResult.Failed -> report(result.error)
            else -> Unit
        }
    }

    override fun cancelProfileUpdate(id: String) { profileMaintenance.cancel(id) }

    override fun updateProfileMetadata(id: String, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long) = perform("profile:$id") {
        val expected = application.profiles.documentSnapshot(id)
        profileMaintenance.editCandidate(expected, name, url, autoUpdate, intervalMillis)
        reloadProfiles()
    }

    override val profileProperties: dev.aerove.lite.integration.ProfilePropertiesAccess by lazy {
        dev.aerove.lite.integration.RepositoryProfilePropertiesAccess(application.profiles, profileMaintenance,
            { !application.maintenanceActive }, { reloadProfiles() })
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
        val changed = state.value.groups.firstOrNull { it.name == group }?.selected != proxy
        val selection = outbound.beginSelection()
        diagnostics.awaitingRoute()
        var succeeded = false
        try {
            client.select(group, proxy)
            succeeded = true
            if (changed) try {
                if (state.value.settings.backgroundPolicy.closeConnections) client.closeAllConnections()
                else client.resetConnections()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                observability.appendLocalLogs(listOf(LogLine(System.currentTimeMillis(), "warning",
                    "changeProxy($group -> $proxy) connection reset failed: ${failure.message}"))) { !application.maintenanceActive }
            }
        } finally {
            outbound.endSelection(selection, group, proxy, succeeded)
            synchronizeDiagnosticsRoute()
        }
    }

    override fun testGroup(group: String) { proxyPresentation.testGroup(group, "") }

    override fun refreshOutbound() {
        outbound.refresh()
        synchronizeDiagnosticsRoute()
        if (ipQualityOwner.isInitialized()) ipQualityOwner.value.retryIfFailed()
    }

    override fun retryNetworkSettings() {
        if (application.vpn.value.phase == VpnPhase.Running) {
            application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_RETRY_NETWORK))
        }
    }

    override fun closeConnection(id: String) = perform("connection:$id") {
        val client = requireApi()
        client.closeConnection(id)
        val connections = withContext(Dispatchers.Default) { client.connections() }
        publishState { it.copy(connections = connections) }
    }

    override fun closeAllConnections() = perform("connections") {
        val client = requireApi()
        client.closeAllConnections()
        val connections = withContext(Dispatchers.Default) { client.connections() }
        publishState { it.copy(connections = connections) }
    }

    override fun shuffleAurora() = queueSettings { settingsUpdates.shuffle() }

    override fun changeHistoryTable(change: dev.aerove.lite.observability.HistoryTableChange) =
        queueSettings { settingsUpdates.historyTable(change) }

    private val modeIntents = dev.aerove.lite.integration.RouteModeIntent()
    private val dnsSaveAttempts = dev.aerove.lite.dnssettings.DnsSaveAttempts { receipt ->
        publishState { it.copy(dnsSaveReceipt = receipt) }
    }

    override fun saveDnsPreferences(preferences: dev.aerove.lite.dnssettings.DnsPreferences): Long {
        val draft = preferences.snapshot().requireValid()
        cancelSettingsAuthorization()
        val token = dnsSaveAttempts.begin()
        queueSettings(onRejected = { dnsSaveAttempts.complete(token, failed = true) }) {
            dnsSaveAttempts.persist(token) { settingsUpdates.update { it.copy(dnsPreferences = draft) } }
        }
        return token
    }

    override fun changeSettings(settings: LiteSettings) {
        val baseline = state.value.settings
        val requested = settings.normalized()
        val intent = modeIntents.reserve(baseline.mode != requested.mode)
        val routeOnly = intent != null && !requiresCoreReconfiguration(baseline, requested)
        queueSettings {
            suspend fun persist() = settingsUpdates.save(baseline, requested,
                isCurrent = { intent == null || modeIntents.current(intent) }, explicitMode = intent != null)
            if (!routeOnly || application.vpn.value.endpoint == null) {
                if (intent == null) persist() else modeIntents.commit(intent, ::persist)
            } else {
                try {
                    val client = requireApi()
                    val endpoint = checkNotNull(application.vpn.value.endpoint)
                    modeIntents.apply(checkNotNull(intent),
                        { api === client && application.vpn.value.endpoint === endpoint && visible.value.active },
                        { client.setMode(requested.mode) }, {
                            persist()
                            outbound.awaitingGroups(); diagnostics.awaitingRoute()
                            synchronizeDiagnosticsRoute()
                        }, { cause -> recoverRouteMode(endpoint, checkNotNull(intent), cause) })
                } finally { intent?.let(modeIntents::finish) }
            }
        }
    }

    private suspend fun recoverRouteMode(endpoint: CoreEndpoint, intent: Long, cause: Exception) {
        dev.aerove.lite.integration.RouteModeRecovery(endpoint, settingsUpdates::savedForModeRecovery,
            { application.vpn.value.endpoint === endpoint },
            { force -> if (force || modeIntents.current(intent)) settingsUpdates.reload() },
            application::stopModeRuntime).recover(cause)
    }

    private fun applySavedSettings(saved: LiteSettings, allowRestart: Boolean) {
        val old = state.value.settings
        val restart = requiresCoreReconfiguration(old, saved)
        if (allowRestart && restart) invalidateOutboundConfiguration()
        publishState { it.copy(settings = saved, networkRestartRequired = requiresNetworkRestart(saved, application.vpn.value)) }
        diagnostics.settings(saved)
        if (allowRestart && !old.developerMode && saved.developerMode) notices.post(application.getString(R.string.maintenance_developer_mode_enable_tip), dev.aerove.lite.notices.NoticeLevel.Success)
        if (allowRestart && restart && state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) {
            ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
                .setAction(LiteVpnService.ACTION_RECONFIGURE))
        }
        if (old.aurora != saved.aurora) checkAuroraTime()
    }

    private fun queueSettings(onRejected: (() -> Unit)? = null, action: suspend () -> Unit) {
        if (!viewModelScope.isActive || application.maintenanceActive) { onRejected?.invoke(); return }
        settingsRequests++
        publishState { it.copy(busy = it.busy + "settings", settingsError = false) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (viewModelScope.isActive) { publishState { it.copy(settingsError = true) }; report(failure) }
            } finally {
                settingsRequests--
                if (settingsRequests == 0) publishState { it.copy(busy = it.busy - "settings") }
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
        publishState { it.copy(installedApps = apps) }
    }

    override fun clearLogs() { publishState { it.copy(logs = emptyList()) } }
    override fun dismissMessage() { publishState { it.copy(message = null) } }
    fun showMessage(message: String) { publishState { it.copy(message = message) } }

    private fun requireApi(): CoreApiClient = checkNotNull(api) { application.getString(R.string.error_api_unavailable) }

    private suspend fun reloadProfiles() {
        val snapshot = profileMaintenance.reload()
        publishState { it.copy(profiles = snapshot.profiles, selectedProfile = snapshot.selectedId) }
        if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.endpointChanged()
        if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.profile(snapshot.profiles.find { it.id == snapshot.selectedId })
        bindOutbound()
    }

    private suspend fun restoreWithRuntime(write: suspend () -> Unit, refresh: suspend () -> Unit,
        resumeAfterSuccess: Boolean = true, restoreLocale: Boolean = true) {
        var maintenanceMayResume = false
        try { application.withMaintenance {
            application.invalidatePendingStarts()
            if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.quiesce()
            externalOfferJob?.cancelAndJoin()
            dismissExternalProfile()
            val continuation = dev.aerove.lite.integration.RestoreRuntimeOwner(
                stop = {
                    if (ipQualityOwner.isInitialized()) ipQualityOwner.value.quiesce()
                    if (updateOwner.isInitialized()) updateOwner.value.quiesce()
                    profileMaintenance.quiesce()
                    resourceMaintenanceLifecycle.value.quiesce()
                    if (resourceMaintenanceOwner.isInitialized()) resourceMaintenanceOwner.value.quiesce()
                    if (profileImportsOwner.isInitialized()) profileImportsOwner.value.quiesce()
                    if (profileListOwner.isInitialized()) profileListOwner.value.quiesce()
                    workJobs.values.toList().forEach { it.cancelAndJoin() }
                    if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.quiesce()
                    if (resourcesOwner.isInitialized()) resourcesOwner.value.quiesce()
                    if (overrideOwner.isInitialized()) overrideOwner.value.quiesce()
                    if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.quiesce()
                    if (globalResourcesOwner.isInitialized()) globalResourcesOwner.value.quiesce()
                    if (resourceManagementOwner.isInitialized()) resourceManagementOwner.value.quiesce()
                    application.stopForMaintenance()
                },
                capture = { stopped ->
                    val settings = application.settings.read()
                    val id = stopped.previousPlan?.profileId
                    dev.aerove.lite.integration.RestoreRuntimeBaseline(
                        dev.aerove.lite.integration.RestoreRuntimeTarget(id, settings),
                        id?.let { application.profiles.documentSnapshot(it) },
                    )
                },
                target = {
                    val actual = application.profiles.snapshot()
                    dev.aerove.lite.integration.RestoreRuntimeTarget(actual.selectedId, application.settings.read())
                },
                resume = { stopped, target, resume ->
                    application.resumeAfterMaintenance(stopped, target?.profileId, target?.settings, resume && target?.profileId != null)
                },
                resumeAfterSuccess = resumeAfterSuccess,
                rollbackSafe = { failure, baseline ->
                    (dev.aerove.lite.data.rollbackVerified(failure) &&
                        application.settings.read() == baseline.target.settings &&
                        (baseline.document?.let { original ->
                            application.profiles.documentSnapshot(original.profile.id) == original
                        } ?: (baseline.target.profileId == null))).also { maintenanceMayResume = it }
                },
            )
            continuation.execute { beforeWrite, afterLoad ->
                    settingsUpdates.restore({
                        beforeWrite()
                        application.builderResourceLibrary.invalidateRemoteRefreshes()
                        application.settings.deferPublication { write() }
                    }) {
                        if (restoreLocale) localeOwner.value.restoreSaved()
                        refresh()
                        afterLoad()
                    }
            }
            maintenanceMayResume = resumeAfterSuccess
        } } finally {
            if (maintenanceMayResume) {
                if (ipQualityOwner.isInitialized()) ipQualityOwner.value.resumeAfterMaintenance()
                if (updateOwner.isInitialized()) updateOwner.value.resumeAfterMaintenance()
                if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.resumeAfterMaintenance()
                if (globalResourcesOwner.isInitialized()) globalResourcesOwner.value.resumeAfterMaintenance()
                if (resourceManagementOwner.isInitialized()) resourceManagementOwner.value.resumeAfterMaintenance()
                if (sourceApplyOwner.isInitialized()) sourceApplyOwner.value.resumeAfterMaintenance()
                if (profileListOwner.isInitialized()) profileListOwner.value.resumeAfterMaintenance()
                if (profileImportsOwner.isInitialized()) profileImportsOwner.value.resumeAfterMaintenance()
                if (overrideOwner.isInitialized()) overrideOwner.value.resumeAfterMaintenance()
                if (resourcesOwner.isInitialized()) resourcesOwner.value.resumeAfterMaintenance()
                if (profileDetailsOwner.isInitialized()) profileDetailsOwner.value.resumeAfterMaintenance()
                if (resourceMaintenanceOwner.isInitialized()) resourceMaintenanceOwner.value.resumeAfterMaintenance()
                resourceMaintenanceLifecycle.value.resumeAfterMaintenance()
                profileMaintenance.resumeAfterMaintenance(visible.value.active && !application.maintenanceActive)
            }
        }
    }

    private suspend fun reloadRestoredData() {
        reloadProfiles()
        invalidateOutboundConfiguration()
        checkAuroraTime()
    }

    private fun outboundKey(): OutboundKey? {
        val endpoint = application.vpn.value.endpoint ?: return null
        if (outboundBlockedEndpoint === endpoint) return null
        return OutboundKey(endpoint, state.value.selectedProfile ?: return null,
            outboundConfiguration, application.vpn.value.let { it.phase == VpnPhase.Running && it.listening && !it.suspended })
    }

    private fun bindOutbound(awaitingRoute: Boolean = false) {
        outbound.bind(outboundKey())
        if (!awaitingRoute && diagnosticsBinding?.let(::diagnosticsBindingCurrent) == true) {
            acceptedRoute?.takeIf { it.client === api }?.let { outbound.replayConfirmedRoute(it.picks, it.epoch) }
        }
        bindIpQuality(outbound.state.value.identity?.address)
    }

    private fun bindIpQuality(ip: String?) {
        if (ipQualityOwner.isInitialized()) ipQualityOwner.value.bind(state.value.selectedProfile,
            state.value.phase == VpnPhase.Running, ip, outbound.state.value.identity?.countryCode?.takeIf { ip != null })
    }

    private fun invalidateOutboundConfiguration() {
        outboundConfiguration++
        bindDiagnostics(null)
        outboundBlockedEndpoint = application.vpn.value.endpoint
        outbound.bind(null)
        bindIpQuality(null)
    }

    private fun requestSourceReapply() { sourceApplyOwner.value.request() }

    private fun reconnectSelected(id: String) {
        if (!application.maintenanceActive && state.value.selectedProfile == id &&
            state.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)) {
            ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
                .setAction(LiteVpnService.ACTION_RECONFIGURE))
        }
    }

    private fun diagnosticsBindingCurrent(binding: DiagnosticsBinding): Boolean =
        visible.value.active && visible.value.generation == binding.generation && api === binding.client &&
            application.vpn.value.endpoint === binding.endpoint && binding.configuration == outboundConfiguration &&
            binding.proxied == (outboundKey()?.proxied == true) && binding.suspended == application.vpn.value.suspended

    private suspend fun confirmExplicitOutbound(key: OutboundKey, answer: OutboundAnswer): OutboundAnswer {
        val binding = diagnosticsBinding ?: throw CancellationException("Diagnostic binding changed")
        if (key != outboundKey() || !diagnosticsBindingCurrent(binding)) throw CancellationException("Diagnostic binding changed")
        return diagnosticsRouteRead.withLock {
            val route = binding.client.routeSnapshot()
            val services = diagnostics.routeHits()
            val verdict = binding.client.checkRoutes(services.values.toList() + listOf(answer.hits))
            if (key != outboundKey() || !diagnosticsBindingCurrent(binding)) throw CancellationException("Diagnostic binding changed")
            if (verdict.coreEpoch != route.coreEpoch || verdict.routeVersion != route.routeVersion ||
                answer.coreEpoch != verdict.coreEpoch || !verdict.held.last())
                throw java.io.IOException("Outbound route changed during lookup")
            acceptDiagnosticsRoute(binding, route.coreEpoch, route.routeVersion, route.picks,
                services.keys.zip(verdict.held).toMap(), verifiedOutbound = true)
            answer.copy(coreEpoch = verdict.coreEpoch, routeVersion = verdict.routeVersion)
        }
    }

    private suspend fun readDiagnosticServices(binding: DiagnosticsBinding, targets: List<ServiceTarget>): List<ServiceCheck> {
        val checks = binding.client.serviceChecks(targets)
        if (!diagnosticsBindingCurrent(binding)) throw CancellationException("Diagnostic binding changed")
        return diagnosticsRouteRead.withLock {
            // A user operation can finish while UI telemetry is paused. Confirm it once against
            // the current route without starting an observer or canceling this completing task.
            val route = if (!readDemand.active) binding.client.routeSnapshot() else null
            val previous = if (route != null) diagnostics.routeHits() else emptyMap()
            val hits = checks.map { it.hits } + previous.values.toList() + if (route != null) listOf(outbound.routeHits()) else emptyList()
            val verdict = binding.client.checkRoutes(hits)
            if (!diagnosticsBindingCurrent(binding)) throw CancellationException("Diagnostic binding changed")
            if (checks.indices.any { !verdict.held[it] || checks[it].coreEpoch != verdict.coreEpoch } ||
                (route != null && (route.coreEpoch != verdict.coreEpoch || route.routeVersion != verdict.routeVersion)))
                throw java.io.IOException("Service route changed during check")
            if (route != null) acceptDiagnosticsRoute(binding, route.coreEpoch, route.routeVersion, route.picks,
                previous.keys.zip(verdict.held.drop(checks.size)).toMap(), verdict.held.last(),
                verifiedServices = targets.mapTo(linkedSetOf()) { it.id })
            checks.map { it.copy(coreEpoch = verdict.coreEpoch, routeVersion = verdict.routeVersion) }
        }
    }

    private fun bindDiagnostics(binding: DiagnosticsBinding?) {
        diagnosticsRouteDemand.invalidate()
        if (binding?.client !== diagnosticsBinding?.client) acceptedRoute = null
        diagnosticsBinding = binding
        diagnosticsRouteRequest++
        diagnosticsRouteJob?.cancel()
        diagnostics.bind(binding)
        dashboardConnections.bind(binding)
    }

    private fun acceptDiagnosticsRoute(binding: DiagnosticsBinding, epoch: ULong, version: ULong,
        picks: Map<String, String>, held: Map<String, Boolean> = emptyMap(), outboundHeld: Boolean? = null,
        verifiedOutbound: Boolean = false, verifiedServices: Set<String> = emptySet()) {
        if (diagnosticsBinding != binding || !diagnosticsBindingCurrent(binding)) return
        val previous = acceptedRoute?.takeIf { it.client === binding.client }
        if (previous != null && (epoch < previous.epoch || (epoch == previous.epoch && version < previous.version))) return
        acceptedRoute = AcceptedRoute(binding.client, epoch, version, picks.toMap())
        diagnostics.route(epoch, version, picks, held, verifiedServices)
        if (verifiedOutbound) outbound.confirmRoute(picks, epoch, version)
        else outbound.synchronize(picks, epoch, version, outboundHeld)
        if (proxyPresentationOwner.isInitialized()) proxyPresentationOwner.value.routeEpoch(binding.client, epoch)
    }

    private fun synchronizeDiagnosticsRoute() {
        if (!routeReadsAllowed()) { routeSyncPending = true; return }
        routeSyncPending = false
        val binding = diagnosticsBinding ?: return
        if (!diagnosticsBindingCurrent(binding)) return
        val request = ++diagnosticsRouteRequest
        diagnosticsRouteJob?.cancel()
        diagnosticsRouteJob = viewModelScope.launch {
            var failures = 0
            val retryDelays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
            while (isActive && request == diagnosticsRouteRequest && diagnosticsBindingCurrent(binding)) {
                try {
                    diagnosticsRouteRead.withLock {
                        val route = binding.client.routeSnapshot()
                        val services = diagnostics.routeHits()
                        val allHits = services.values.toList() + listOf(outbound.routeHits())
                        val verdict = binding.client.checkRoutes(allHits)
                        if (verdict.coreEpoch != route.coreEpoch || verdict.routeVersion != route.routeVersion)
                            throw java.io.IOException("Route changed during recheck")
                        if (request == diagnosticsRouteRequest) acceptDiagnosticsRoute(binding, route.coreEpoch, route.routeVersion,
                            route.picks, services.keys.zip(verdict.held).toMap(), verdict.held.last())
                    }
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
        publishState { it.copy(busy = it.busy + key) }
        val job = session.launch(key, { publishState { it.copy(busy = it.busy - key) } }) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                session.accept { report(failure) }
            }
        }
        if (job == null) publishState { it.copy(busy = it.busy - key) }
    }

    private fun perform(key: String, action: suspend () -> Unit) {
        if (application.maintenanceActive || workJobs.containsKey(key)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally {
                workJobs.remove(key)
                publishState { it.copy(busy = it.busy - key) }
            }
        }
        if (workJobs.putIfAbsent(key, job) != null) { job.cancel(); return }
        publishState { it.copy(busy = it.busy + key) }
        job.start()
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
    private val intentGate = Any()
    private var generation = 0L
    private var restoring = 0

    private fun intent(): Long? = synchronized(intentGate) { generation.takeIf { restoring == 0 } }
    private fun currentIntent(token: Long): Boolean = synchronized(intentGate) { restoring == 0 && token == generation }

    suspend fun restore(write: suspend () -> Unit, afterLoaded: suspend () -> Unit) {
        synchronized(intentGate) { generation++; restoring++ }
        try {
            mutex.withLock {
                check(!closed) { "Settings owner is closed" }
                try {
                    write()
                    latest = null
                    deferredWallpaper = false
                    publishVisible(loadLatest(), false)
                    afterLoaded()
                } catch (failure: Throwable) {
                    latest = null
                    withContext(NonCancellable) {
                        try { publishVisible(loadLatest(), false) }
                        catch (recovery: Throwable) { failure.addSuppressed(recovery) }
                    }
                    throw failure
                }
            }
        } finally { synchronized(intentGate) { generation++; restoring-- } }
    }

    suspend fun load(): Unit = mutex.withLock {
        if (!closed) publishVisible(loadLatest(), false)
    }
    suspend fun awaitIdle(): Unit = mutex.withLock { check(!closed) { "Settings owner is closed" } }

    suspend fun reload(): Unit = mutex.withLock {
        if (closed) return@withLock
        latest = null
        deferredWallpaper = false
        publishVisible(loadLatest(), false)
    }

    suspend fun savedForModeRecovery(): LiteSettings = mutex.withLock { latest = null; loadLatest() }

    suspend fun save(baseline: LiteSettings, requested: LiteSettings,
        isCurrent: () -> Boolean = { true }, explicitMode: Boolean = false) {
        val token = intent() ?: if (explicitMode) throw CancellationException("Settings restore is active") else return
        mutex.withLock {
            if (closed || !currentIntent(token)) {
                if (explicitMode) throw CancellationException("Mode settings intent changed") else return@withLock
            }
            fun checkCurrent(requireRestoreIntent: Boolean = true) {
                if (!isCurrent() || closed || (requireRestoreIntent && !currentIntent(token)))
                    throw CancellationException("Mode settings intent changed")
            }
            checkCurrent()
            val merged = mergeSettingsChanges(baseline, requested, loadLatest())
            val settings = if (explicitMode) merged.copy(mode = requested.mode) else merged
            val saved = commit { checkCurrent(); saveSettings(settings) }
            checkCurrent(requireRestoreIntent = explicitMode)
            if (baseline.auroraSeed != requested.auroraSeed || baseline.auroraRotationDay != requested.auroraRotationDay) {
                deferredWallpaper = false
            }
            publishVisible(saved, true)
        }
    }

    suspend fun update(restart: Boolean = true, transform: (LiteSettings) -> LiteSettings): LiteSettings {
        val token = checkNotNull(intent()) { "Settings restore is active" }
        return mutex.withLock {
            check(!closed && currentIntent(token)) { "Settings intent was superseded" }
            val saved = commit { saveSettings(transform(loadLatest())) }
            publishVisible(saved, restart)
            saved
        }
    }

    suspend fun historyTable(change: dev.aerove.lite.observability.HistoryTableChange) {
        val token = intent() ?: return
        mutex.withLock {
            if (closed || !currentIntent(token)) return@withLock
            val settings = loadLatest()
            val saved = commit { saveSettings(settings.copy(historyTables = settings.historyTables.apply(change))) }
            publishVisible(saved, true)
        }
    }

    suspend fun shuffle() {
        val token = intent() ?: return
        mutex.withLock {
            if (closed || !currentIntent(token) || !loadLatest().aurora) return@withLock
            val saved = commit { shuffleSettings() }
            deferredWallpaper = false
            publishVisible(saved, false)
        }
    }

    suspend fun rotate(now: ZonedDateTime, eligible: () -> Boolean) {
        val token = intent() ?: return
        mutex.withLock {
            if (closed || !currentIntent(token) || !eligible()) return@withLock
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
        developerMode = choose(baseline.developerMode, requested.developerMode, current.developerMode),
        acceptedDisclaimerVersion = choose(baseline.acceptedDisclaimerVersion, requested.acceptedDisclaimerVersion, current.acceptedDisclaimerVersion),
        unifiedDelay = choose(baseline.unifiedDelay, requested.unifiedDelay, current.unifiedDelay),
        tcpConcurrent = choose(baseline.tcpConcurrent, requested.tcpConcurrent, current.tcpConcurrent),
        findProcessMode = choose(baseline.findProcessMode, requested.findProcessMode, current.findProcessMode),
        externalController = current.externalController.copy(
            enabled = choose(baseline.externalController.enabled, requested.externalController.enabled, current.externalController.enabled),
            secret = choose(baseline.externalController.secret, requested.externalController.secret, current.externalController.secret),
        ),
        autoCheckUpdate = choose(baseline.autoCheckUpdate, requested.autoCheckUpdate, current.autoCheckUpdate),
        network = mergeNetworkChanges(baseline.network, requested.network, current.network),
        requests = current.requests.copy(
            globalUserAgent = choose(baseline.requests.globalUserAgent, requested.requests.globalUserAgent, current.requests.globalUserAgent),
            userAgents = choose(baseline.requests.userAgents, requested.requests.userAgents, current.requests.userAgents),
            checkCertificate = choose(baseline.requests.checkCertificate, requested.requests.checkCertificate, current.requests.checkCertificate),
        ),
        historyTables = dev.aerove.lite.observability.mergeHistoryTables(baseline.historyTables, requested.historyTables, current.historyTables),
        appAccess = current.appAccess.copy(
            enabled = choose(baseline.appAccess.enabled, requested.appAccess.enabled, current.appAccess.enabled),
            mode = choose(baseline.appAccess.mode, requested.appAccess.mode, current.appAccess.mode),
            includePackages = choose(baseline.appAccess.includePackages, requested.appAccess.includePackages, current.appAccess.includePackages),
            excludePackages = choose(baseline.appAccess.excludePackages, requested.appAccess.excludePackages, current.appAccess.excludePackages),
            sort = choose(baseline.appAccess.sort, requested.appAccess.sort, current.appAccess.sort),
            hideSystem = choose(baseline.appAccess.hideSystem, requested.appAccess.hideSystem, current.appAccess.hideSystem),
            hideNoInternet = choose(baseline.appAccess.hideNoInternet, requested.appAccess.hideNoInternet, current.appAccess.hideNoInternet),
        ),
        dnsPreferences = mergeDnsPreferences(baseline.dnsPreferences, requested.dnsPreferences, current.dnsPreferences),
        geoResources = current.geoResources.copy(
            autoUpdate = choose(baseline.geoResources.autoUpdate, requested.geoResources.autoUpdate, current.geoResources.autoUpdate),
            intervalHours = choose(baseline.geoResources.intervalHours, requested.geoResources.intervalHours, current.geoResources.intervalHours),
            urls = dev.aerove.lite.resources.GeoResourceKind.entries.associateWith { kind ->
                choose(baseline.geoResources.urls.getValue(kind), requested.geoResources.urls.getValue(kind), current.geoResources.urls.getValue(kind))
            },
        ),
        backgroundPolicy = current.backgroundPolicy.copy(
            autoRun = choose(baseline.backgroundPolicy.autoRun, requested.backgroundPolicy.autoRun, current.backgroundPolicy.autoRun),
            minimizeOnExit = choose(baseline.backgroundPolicy.minimizeOnExit, requested.backgroundPolicy.minimizeOnExit, current.backgroundPolicy.minimizeOnExit),
            hidden = choose(baseline.backgroundPolicy.hidden, requested.backgroundPolicy.hidden, current.backgroundPolicy.hidden),
            onlyStatisticsProxy = choose(baseline.backgroundPolicy.onlyStatisticsProxy, requested.backgroundPolicy.onlyStatisticsProxy, current.backgroundPolicy.onlyStatisticsProxy),
            closeConnections = choose(baseline.backgroundPolicy.closeConnections, requested.backgroundPolicy.closeConnections, current.backgroundPolicy.closeConnections),
            excludeSSIDs = choose(baseline.backgroundPolicy.excludeSSIDs, requested.backgroundPolicy.excludeSSIDs, current.backgroundPolicy.excludeSSIDs),
            showNotificationStopAction = choose(baseline.backgroundPolicy.showNotificationStopAction, requested.backgroundPolicy.showNotificationStopAction, current.backgroundPolicy.showNotificationStopAction),
        ),
        proxyPresentation = current.proxyPresentation.copy(
            type = choose(baseline.proxyPresentation.type, requested.proxyPresentation.type, current.proxyPresentation.type),
            sort = choose(baseline.proxyPresentation.sort, requested.proxyPresentation.sort, current.proxyPresentation.sort),
            layout = choose(baseline.proxyPresentation.layout, requested.proxyPresentation.layout, current.proxyPresentation.layout),
            iconStyle = choose(baseline.proxyPresentation.iconStyle, requested.proxyPresentation.iconStyle, current.proxyPresentation.iconStyle),
            cardType = choose(baseline.proxyPresentation.cardType, requested.proxyPresentation.cardType, current.proxyPresentation.cardType),
            hideTimeoutProxies = choose(baseline.proxyPresentation.hideTimeoutProxies, requested.proxyPresentation.hideTimeoutProxies, current.proxyPresentation.hideTimeoutProxies),
            testUrl = choose(baseline.proxyPresentation.testUrl, requested.proxyPresentation.testUrl, current.proxyPresentation.testUrl),
            timeoutMillis = choose(baseline.proxyPresentation.timeoutMillis, requested.proxyPresentation.timeoutMillis, current.proxyPresentation.timeoutMillis),
        ),
        fontFamily = choose(baseline.fontFamily, requested.fontFamily, current.fontFamily),
        textScaleEnabled = choose(baseline.textScaleEnabled, requested.textScaleEnabled, current.textScaleEnabled),
        textScale = choose(baseline.textScale, requested.textScale, current.textScale),
        pureBlack = choose(baseline.pureBlack, requested.pureBlack, current.pureBlack),
        locale = choose(baseline.locale, requested.locale, current.locale),
        floatingNavigationBar = choose(baseline.floatingNavigationBar, requested.floatingNavigationBar, current.floatingNavigationBar),
        tabAnimation = choose(baseline.tabAnimation, requested.tabAnimation, current.tabAnimation),
        colorStyle = choose(baseline.colorStyle, requested.colorStyle, current.colorStyle),
        auroraColors = choose(baseline.auroraColors, requested.auroraColors, current.auroraColors),
        auroraSeed = choose(baseline.auroraSeed, requested.auroraSeed, current.auroraSeed),
        auroraRotationDay = choose(baseline.auroraRotationDay, requested.auroraRotationDay, current.auroraRotationDay),
        dashboardWidgets = choose(baseline.dashboardWidgets, requested.dashboardWidgets, current.dashboardWidgets),
        serviceOrder = choose(baseline.serviceOrder, requested.serviceOrder, current.serviceOrder),
        disabledServices = choose(baseline.disabledServices, requested.disabledServices, current.disabledServices),
    )
}

private fun mergeNetworkChanges(baseline: NetworkSettings, requested: NetworkSettings, current: NetworkSettings): NetworkSettings {
    fun <T> choose(before: T, after: T, latest: T): T = if (after == before) latest else after
    return current.copy(
        systemProxy = choose(baseline.systemProxy, requested.systemProxy, current.systemProxy),
        allowBypass = choose(baseline.allowBypass, requested.allowBypass, current.allowBypass),
        bypassDomains = choose(baseline.bypassDomains, requested.bypassDomains, current.bypassDomains),
        ports = choose(baseline.ports, requested.ports, current.ports),
        allowLan = choose(baseline.allowLan, requested.allowLan, current.allowLan),
        authentication = choose(baseline.authentication, requested.authentication, current.authentication),
        vpnEnabled = choose(baseline.vpnEnabled, requested.vpnEnabled, current.vpnEnabled),
        dnsHijacking = choose(baseline.dnsHijacking, requested.dnsHijacking, current.dnsHijacking),
        mtu = choose(baseline.mtu, requested.mtu, current.mtu),
        bypassPrivateRoute = choose(baseline.bypassPrivateRoute, requested.bypassPrivateRoute, current.bypassPrivateRoute),
        routeAddress = choose(baseline.routeAddress, requested.routeAddress, current.routeAddress),
        routeExcludeAddress = choose(baseline.routeExcludeAddress, requested.routeExcludeAddress, current.routeExcludeAddress),
    )
}

internal fun requiresCoreReconfiguration(before: LiteSettings, after: LiteSettings): Boolean =
    before.ipv6 != after.ipv6 || before.overrideDns != after.overrideDns ||
        before.dnsServers != after.dnsServers ||
        before.requests.globalUserAgent != after.requests.globalUserAgent ||
        before.dnsPreferences != after.dnsPreferences || before.geoResources.urls != after.geoResources.urls

private fun requiresNetworkRestart(settings: LiteSettings, snapshot: VpnSnapshot): Boolean =
    requiresVpnRestart(settings, snapshot)

private fun mergeDnsPreferences(before: dev.aerove.lite.dnssettings.DnsPreferences,
    requested: dev.aerove.lite.dnssettings.DnsPreferences, current: dev.aerove.lite.dnssettings.DnsPreferences): dev.aerove.lite.dnssettings.DnsPreferences {
    fun <T> choose(old: T, next: T, latest: T): T = if (old == next) latest else next
    val fields = dev.aerove.lite.dnssettings.DnsField.entries
    val hosts = before.hosts.keys + requested.hosts.keys + current.hosts.keys
    return current.copy(
        values = fields.mapNotNull { field -> choose(before.values[field], requested.values[field], current.values[field])?.let { field to it } }.toMap(),
        overridden = fields.filterTo(linkedSetOf()) { choose(it in before.overridden, it in requested.overridden, it in current.overridden) },
        hosts = hosts.mapNotNull { host -> choose(before.hosts[host], requested.hosts[host], current.hosts[host])?.let { host to it } }.toMap(),
        legacyFallbackServers = choose(before.legacyFallbackServers, requested.legacyFallbackServers, current.legacyFallbackServers),
    )
}

internal fun LiteState.acceptDashboardTraffic(sample: Traffic): LiteState = copy(
    traffic = sample,
    trafficHistory = dev.aerove.lite.telemetry.appendDashboardTraffic(trafficHistory, sample),
    trafficHistoryRevision = Math.addExact(trafficHistoryRevision, 1L),
)

internal fun LiteState.resetDashboardRuntime(): LiteState = copy(
    trafficHistory = emptyList(), dashboardCounts = DashboardCounts(), trafficHistoryRevision = 0,
)
