package dev.aerove.lite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyActor
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences
import dev.aerove.lite.backgroundpolicy.AndroidSsidPolicy
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.PreparedProfile
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.SystemDnsSnapshot
import dev.aerove.lite.platform.PhysicalNetworkLease
import dev.aerove.lite.networksettings.NetworkRuntimeCoordinator
import dev.aerove.lite.flutter.*
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

class LiteVpnService : VpnService() {
    private val lifecycleInstance = VpnServiceLifecycleTrace.nextInstance()
    private var latestReceivedStartId = 0
    private var latestReceivedCommand = VpnServiceLifecycleTrace.Command.None
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: VpnCoordinator
    private lateinit var networkRuntime: NetworkRuntimeCoordinator
    private var networkLease: PhysicalNetworkLease? = null
    private var physicalObservation: Job? = null
    private var physicalSwitching: Job? = null
    private lateinit var listenerPolicy: BackgroundPolicyActor
    private val graph get() = application as LiteApplication
    @Volatile private var intentGeneration = 0L
    @Volatile private var ownerAdmissionAllowed = false
    private var owner = 0L
    private var runtimeSnapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot? = null
    private var runtimeSettings: LiteSettings? = null
    private val flutterRuntimeGate = Mutex()
    private var flutterListeningRequested = true
    private var flutterTestUrl = MEOW_DEFAULT_TEST_URL
    private data class FlutterPublication(val identity: RuntimeAppliedIdentity, val endpoint: CoreEndpoint, val intent: Long)
    @Volatile private var flutterPublication: FlutterPublication? = null
    private fun publishFlutterRuntime(publication: FlutterPublication?) {
        flutterPublication = publication
        graph.publishFlutterRuntimeIdentity(owner, publication?.identity)
    }
    internal fun startupFlutterTestUrl(): String = flutterTestUrl
    private fun currentSettings(): LiteSettings? = runtimeSettings ?: if (graph.runtimeHandoffs.hasAuthority) null else graph.settings.changes.value
    @Volatile private var lastPlan: VpnPlan? = null
    @Volatile private var ownedStart: OwnedStartServiceReceipt? = null
    private var ownedPreparation: Job? = null
    private var maintenanceHold = false
    private var heldMaintenance: MaintenanceRuntimeContext? = null
    private var policyPreferences = BackgroundPolicyPreferences()
    private var policyObservedEndpoint: CoreEndpoint? = null
    private val physical = kotlinx.coroutines.flow.MutableStateFlow(CapturedMeowPhysical(null, SystemDnsSnapshot(), null))
    private val network: Network? get() = physical.value.network
    private var networkKey: String? = null
    @Volatile private var builderDns: List<String> = emptyList()
    private var networkListenerError: String? = null
    private val startupIntentChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var notificationStopPreference = true
    private var foregroundPosted = false
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshNetworkPermissions() }
    }
    private val notificationUpdates = VpnNotificationUpdates({ phase ->
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(phase))
    }, ::createChannel)

    override fun onCreate() {
        super.onCreate()
        owner = graph.registerVpnOwner()
        traceLifecycle(VpnServiceLifecycleTrace.Event.Created)
        showForeground(VpnPhase.Starting)
        networkRuntime = NetworkRuntimeCoordinator(coreScope, { endpoint, settings, ipv6 ->
            CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client -> client.applyNetwork(settings, ipv6) }
        }, { status -> coordinator.recordNetworkStatus(status) })
        coordinator = VpnCoordinator(coreScope, MeowTunnelBackend(MeowAndroidHost(this, graph, { physical.value }, ::initialListening, ::captureOwnerIntent)), networkRuntime::detachAndJoin,
            latestSavedSettings = { currentSettings() },
            beforeListenerPark = networkRuntime::parkBarrier, afterListenerResume = networkRuntime::resume,
            latestNetworkDns = { physical.value.takeIf { it.network != null }?.dns }, onSessionReady = { snapshot ->
                networkRuntime.bind(snapshot.endpoint, snapshot.initialNetwork ?: snapshot.appliedNetwork, snapshot.initialIpv6 ?: snapshot.appliedIpv6,
                    snapshot.appliedNetwork, snapshot.appliedIpv6, initiallyParked = snapshot.suspended)
            })
        listenerPolicy = BackgroundPolicyActor(coreScope, ::applyBackgroundListenerPolicy,
            { failure -> coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName) })
        graph.registerMaintenanceStop(owner, ::stopForMaintenance, ::resumeAfterMaintenance)
        graph.registerRuleReload(owner, coordinator::reloadRules)
        graph.registerModeCleanup(owner, ::stopForModeFailure)
        graph.registerFlutterRuntime(owner, object : MeowFlutterRuntime {
            override fun hasRequestedIntent() = graph.ownsVpnOwner(owner) && ownerAdmissionAllowed && !maintenanceHold
            override fun identityIsCurrent(identity: RuntimeAppliedIdentity): Boolean {
                val publication = flutterPublication ?: return false
                val actual = coordinator.state.value
                return meowSameRuntimeIdentity(publication.identity, identity) && publication.intent == intentGeneration &&
                    publication.endpoint === actual.endpoint && actual.phase == VpnPhase.Running &&
                    actual.sessionGeneration == identity.proof.sessionGeneration && identity.proof.serviceOwner == owner &&
                    graph.ownsVpnOwner(owner) && graph.runtimeHandoffs.isCurrent(identity.revision, identity.intentEpoch)
            }
            override suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean) = flutterSetListening(expected, enabled)
            override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) = flutterReplaceGraph(snapshot)
            override suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection) = flutterApplyUpdate(expected, projection)
            override suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String) = flutterRefreshSetup(expected, setupParamsJson)
            override suspend fun defaultTestUrl(expected: MeowSetupRuntime): String = flutterRuntimeGate.withLock {
                requireFlutterRuntime(expected); flutterTestUrl
            }
        })
        policyPreferences = graph.settings.changes.value?.backgroundPolicy ?: BackgroundPolicyPreferences()
        networkLease = graph.physicalNetworks.acquire(needsSsid())
        physicalObservation = scope.launch { graph.physicalNetworks.snapshots.collect { updateUnderlying() } }
        registerReceiver(locationReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), RECEIVER_NOT_EXPORTED)
        physicalSwitching = scope.launch {
            var previous: CapturedMeowPhysical? = null
            physical.distinctUntilChanged { before, after -> before.networkHandle == after.networkHandle && before.dns == after.dns }
                .collectLatest { captured ->
                    val old = previous
                    if (captured.network != null) previous = captured
                    val snapshot = coordinator.state.value
                    val action = networkChangeAction(snapshot, builderDns, old?.networkHandle,
                        captured.networkHandle, captured.dns)
                    if (action == NetworkChangeAction.None) return@collectLatest
                    val endpoint = snapshot.endpoint
                    val generation = intentGeneration
                    fun current() = generation == intentGeneration && lastPlan != null && physical.value == captured && coordinator.state.value.endpoint === endpoint
                    applyPhysicalNetworkChange(action, { current() && networkHotFailureReconnect(coordinator.state.value, captured.networkHandle) }, hot = {
                        if (runtimeSnapshot != null) publishFlutterRuntime(null)
                        coordinator.configureNetworkDns(captured.dns)
                        if (!current()) return@applyPhysicalNetworkChange
                        if (runtimeSnapshot != null && endpoint != null) flutterRuntimeGate.withLock {
                            val identity = graph.runtimeHandoffs.readAppliedIdentity()
                            if (identity != null && current() && graph.runtimeHandoffs.isCurrent(identity.revision, identity.intentEpoch))
                                persistFlutterProjection(identity, endpoint, checkNotNull(graph.runtimeHandoffs.readLastApplied()).handoff)
                        }
                        networkListenerError?.let { coordinator.clearListenerError(it) }
                        networkListenerError = null
                        if (old?.networkHandle != null && old.networkHandle != captured.networkHandle && endpoint != null) {
                            withContext(Dispatchers.IO) {
                                CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.closeAllConnections() }
                            }
                        }
                    }, reconnect = coordinator::reconnect, failed = { failure ->
                        val message = failure.message ?: failure.javaClass.simpleName
                        networkListenerError = message
                        coordinator.recordListenerError(message)
                    })
                }
        }
        scope.launch {
            graph.settings.changes.filterNotNull().collect { settings ->
                if (graph.runtimeHandoffs.hasAuthority) return@collect
                networkRuntime.settings(settings.network, settings.ipv6)
                val rulesChanged = policyPreferences.excludeSSIDs != settings.backgroundPolicy.excludeSSIDs
                val ssidGateChanged = policyPreferences.excludeSSIDs.isEmpty() != settings.backgroundPolicy.excludeSSIDs.isEmpty()
                policyPreferences = settings.backgroundPolicy
                if (ssidGateChanged) refreshNetworkPermissions() else if (rulesChanged) syncListenerPolicy()
                if (notificationStopPreference != settings.backgroundPolicy.showNotificationStopAction) {
                    notificationStopPreference = settings.backgroundPolicy.showNotificationStopAction
                    notificationUpdates.contentChanged(coordinator.state.value.phase)
                }
            }
        }
        scope.launch {
            graph.settings.changes.filterNotNull().map { settings ->
                listOf(settings.backgroundPolicy.onlyStatisticsProxy, settings.requests, settings.unifiedDelay,
                    settings.tcpConcurrent, settings.externalController, settings.findProcessMode)
            }
                .distinctUntilChanged().collectLatest {
                    if (graph.runtimeHandoffs.hasAuthority) return@collectLatest
                    try { currentSettings()?.let { settings -> coordinator.configurePolicies(settings) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName) }
                }
        }
        scope.launch {
            var appliedGeneration = 0L
            coordinator.state.collect { snapshot ->
                if (snapshot.phase != VpnPhase.Running) publishFlutterRuntime(null)
                val runtime = runtimeSnapshot
                val endpointForProof = snapshot.endpoint
                if (runtime != null && snapshot.phase == VpnPhase.Running && endpointForProof != null && snapshot.sessionGeneration != appliedGeneration) {
                    val capturedIntent = intentGeneration
                    try {
                        val setup = startupFlutterSetup(runtime)
                        coordinator.configurePolicies(checkNotNull(runtimeSettings))
                        applyFlutterSelections(endpointForProof, setup)
                        val route = CoreApiClient(ControlEndpoint(endpointForProof.port, endpointForProof.secret)).use { it.routeSnapshot() }
                        check(capturedIntent == intentGeneration && coordinator.state.value.endpoint === endpointForProof) { "runtime_apply_superseded" }
                        graph.runtimeHandoffs.commitAppliedWithProjection(runtime.revision, runtime.intentEpoch,
                            dev.aerove.lite.flutter.RuntimeAppliedProof(owner, snapshot.sessionGeneration, route.coreEpoch.toString()),
                            meowServiceProjection(runtime.handoff, checkNotNull(runtimeSettings), null, null, null, flutterTestUrl))
                        runtimeSnapshot = graph.runtimeHandoffs.readCurrent()
                        val identity = checkNotNull(graph.runtimeHandoffs.readAppliedIdentity())
                        check(capturedIntent == intentGeneration && graph.runtimeHandoffs.isCurrent(identity.revision, identity.intentEpoch)) { "runtime_apply_superseded" }
                        publishFlutterRuntime(FlutterPublication(identity, endpointForProof, capturedIntent))
                        appliedGeneration = snapshot.sessionGeneration
                    } catch (failure: Exception) {
                        if (capturedIntent == intentGeneration && graph.runtimeHandoffs.isCurrent(runtime.revision, runtime.intentEpoch)) {
                            try {
                                graph.runtimeHandoffs.invalidate(runtime.intentEpoch)
                                if (capturedIntent == intentGeneration) {
                                    requested(false)
                                    recordStartupFailure(this@LiteVpnService, failure)
                                }
                            } catch (superseded: Exception) { failure.addSuppressed(superseded) }
                        }
                        coordinator.stopIfCurrent(endpointForProof, "Runtime apply receipt failed: ${failure.message}")
                        return@collect
                    }
                }
                networkRuntime.bind(snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running },
                    snapshot.initialNetwork, snapshot.initialIpv6, snapshot.appliedNetwork, snapshot.appliedIpv6)
                graph.publishVpn(owner, snapshot)
                traceLifecycle(VpnServiceLifecycleTrace.Event.StatePublished)
                if (snapshot.phase == VpnPhase.Failed && lastPlan?.ownerIntentToken == intentGeneration && !maintenanceHold) {
                    val startId = latestReceivedStartId
                    requested(false)
                    retireOwnedStart()
                    lastPlan = null
                    syncListenerPolicy()
                    removeNotification()
                    traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId)
                    if (startId > 0) stopSelf(startId)
                    return@collect
                }
                syncNotification(snapshot)
                val endpoint = snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running }
                if (policyObservedEndpoint !== endpoint) {
                    policyObservedEndpoint = endpoint
                    if (endpoint != null) syncListenerPolicy()
                }
            }
        }
        scope.launch {
            if (graph.runtimeHandoffs.hasAuthority) return@launch
            collectResourceRefreshes(
                coordinator.state.map { snapshot -> snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running && !graph.runtimeHandoffs.hasAuthority } },
                graph.profiles,
            ) { endpoint -> lastPlan?.let { refreshResources(it, endpoint) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestReceivedStartId = startId
        latestReceivedCommand = when (intent?.action) {
            ACTION_START -> VpnServiceLifecycleTrace.Command.Start
            ACTION_STOP -> VpnServiceLifecycleTrace.Command.Stop
            ACTION_RECONFIGURE -> VpnServiceLifecycleTrace.Command.Reconfigure
            ACTION_RETRY_NETWORK -> VpnServiceLifecycleTrace.Command.RetryNetwork
            ACTION_REFRESH_POLICY -> VpnServiceLifecycleTrace.Command.RefreshPolicy
            SERVICE_INTERFACE -> VpnServiceLifecycleTrace.Command.SystemStart
            null -> VpnServiceLifecycleTrace.Command.Restore
            else -> VpnServiceLifecycleTrace.Command.Other
        }
        val command = latestReceivedCommand
        traceLifecycle(VpnServiceLifecycleTrace.Event.CommandReceived, startId, command)
        val hasOwnedId = intent?.action == ACTION_START && intent?.hasExtra(EXTRA_OWNED_START_ID) == true
        val ownedId = if (hasOwnedId) intent?.getStringExtra(EXTRA_OWNED_START_ID) else null
        if (hasOwnedId && ownedId == null) return rejectOwnedStart(startId)
        if (intent?.action == ACTION_RECONFIGURE && intent.hasExtra(EXTRA_REPLACEMENT_OWNER)) {
            val revision = intent.getStringExtra(EXTRA_RUNTIME_REVISION)
            val epoch = intent.getLongExtra(EXTRA_RUNTIME_EPOCH, -1)
            if (!meowAdmitGraphReplacement(intent.getLongExtra(EXTRA_REPLACEMENT_OWNER, -1),
                    intent.getLongExtra(EXTRA_REPLACEMENT_INTENT, -1), owner, intentGeneration,
                    wasRequested(this), revision != null && graph.runtimeHandoffs.isCurrent(revision, epoch)))
                return rejectOwnedStart(startId)
        }
        // Restore owns the complete stop/join/write transaction, including non-UI entry points.
        if (graph.maintenanceActive && intent?.action != ACTION_STOP) {
            ownedId?.let(graph.ownedStarts::reject)
            traceLifecycle(VpnServiceLifecycleTrace.Event.MaintenanceIgnored, startId, command)
            if (lastPlan == null && !wasRequested(this)) {
                removeNotification()
                traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId, command)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_POLICY) {
            if (wasRequested(this)) {
                refreshNetworkPermissions()
                return START_STICKY
            }
            removeNotification()
            traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId, command)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RETRY_NETWORK) {
            if (wasRequested(this)) {
                networkRuntime.retry()
                return START_STICKY
            } else {
                removeNotification()
                traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId, command)
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        var nextOwned: OwnedStartServiceReceipt? = null
        if (hasOwnedId) {
            val admitted = graph.ownedStarts.admit(checkNotNull(ownedId)) { request ->
                val receipt = OwnedStartServiceReceipt(request, owner, intentGeneration + 1, startId)
                nextOwned = receipt
                val cancel: () -> Unit = { cancelOwnedStart(receipt) }
                cancel
            }
            if (admitted == null) return rejectOwnedStart(startId)
        }
        if (intent?.action in setOf(ACTION_START, ACTION_RECONFIGURE, SERVICE_INTERFACE) || intent == null)
            showForeground(if (::coordinator.isInitialized) coordinator.state.value.phase else VpnPhase.Starting)
        val generation = ++intentGeneration
        publishFlutterRuntime(null)
        val previousPreparation = retireOwnedStart()
        ownedStart = nextOwned
        previousPreparation?.cancel()
        traceLifecycle(VpnServiceLifecycleTrace.Event.IntentAccepted, startId, command, generation)
        if (intent?.action == ACTION_STOP) {
            graph.invalidatePendingStarts()
            maintenanceHold = false
            heldMaintenance = null
            requested(false)
            lastPlan = null
            syncListenerPolicy()
            val stoppedRuntime = graph.runtimeHandoffs.peekCurrent()
            scope.launch(NonCancellable) {
                try {
                    val snapshot = stoppedRuntime ?: graph.runtimeHandoffs.readCurrent()
                    if (snapshot != null && generation == intentGeneration) graph.runtimeHandoffs.invalidate(snapshot.intentEpoch)
                } catch (failure: Exception) {
                    android.util.Log.e("MeowRuntime", "Runtime stop snapshot invalidation failed", failure)
                } finally {
                    if (generation == intentGeneration) { coordinator.stop(); finishStop(startId, generation) }
                }
            }
            return START_NOT_STICKY
        }
        if (intent == null && !wasRequested(this) && !graph.runtimeHandoffs.hasAuthority) {
            lastPlan = null
            coordinator.stop()
            finishStop(startId, generation)
            return START_NOT_STICKY
        }
        val reconfigure = intent?.action == ACTION_RECONFIGURE
        traceLifecycle(VpnServiceLifecycleTrace.Event.StartAdmission, startId, command, generation)
        if (reconfigure && coordinator.requestedBuilderSettings() == null) {
            removeNotification()
            traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId, command, generation)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        requested(true)
        val now = System.nanoTime()
        val requestedAt = intent?.getLongExtra(EXTRA_REQUESTED_AT_NANOS, 0L)?.takeIf { it != 0L && now - it >= 0L } ?: now
        traceLifecycle(VpnServiceLifecycleTrace.Event.StartQueued, startId, command, generation)
        val preparation = scope.launch {
            traceLifecycle(VpnServiceLifecycleTrace.Event.StartEntered, startId, command, generation)
            try {
                val explicitSnapshot = intent?.hasExtra(EXTRA_RUNTIME_REVISION) == true
                val snapshot = if (explicitSnapshot || graph.runtimeHandoffs.hasAuthority) {
                    var saved = checkNotNull(graph.runtimeHandoffs.readCurrent()) { "runtime_snapshot_missing" }
                    if (!explicitSnapshot && (intent == null || intent.action == SERVICE_INTERFACE) &&
                        graph.runtimeHandoffs.readLastApplied() != null) {
                        saved = graph.runtimeHandoffs.restoreLastApplied(saved.intentEpoch)
                    }
                    val revision = intent?.getStringExtra(EXTRA_RUNTIME_REVISION)
                    if (intent?.action == ACTION_START) check(revision != null) { "runtime_snapshot_receipt_required" }
                    graph.runtimeHandoffs.admit(revision ?: saved.revision,
                        if (explicitSnapshot) intent!!.getLongExtra(EXTRA_RUNTIME_EPOCH, -1) else saved.intentEpoch)
                } else null
                val profile = snapshot?.profileId ?: checkNotNull(graph.profiles.snapshot().selectedId) { "Import and select a configuration first" }
                val settings = snapshot?.let { dev.aerove.lite.flutter.MeowRuntimeSnapshotSettings.decode(it.handoff.runtimeSettingsJson) }
                    ?: graph.settings.read()
                runtimeSnapshot = snapshot
                runtimeSettings = settings.takeIf { snapshot != null }
                flutterListeningRequested = snapshot?.let { JSONObject(it.handoff.runtimeSettingsJson).optBoolean("flutterListeningRequested", true) } ?: true
                if (snapshot != null) {
                    val previous = graph.runtimeHandoffs.readLastApplied()?.handoff?.runtimeSettingsJson?.let(::JSONObject)
                    val retained = previous?.optString("flutterDefaultTestUrl")?.takeIf { it.isNotEmpty() } ?: MEOW_DEFAULT_TEST_URL
                    val row = JSONObject(snapshot.handoff.runtimeSettingsJson)
                    flutterTestUrl = MeowServiceSetup(row.getJSONObject("flutterSetup").toString(),
                        row.optString("flutterDefaultTestUrl").takeIf { it.isNotEmpty() } ?: retained).testUrl
                }
                policyPreferences = settings.backgroundPolicy
                notificationStopPreference = snapshot?.let { org.json.JSONObject(it.handoff.notificationJson).getBoolean("showStopAction") }
                    ?: settings.backgroundPolicy.showNotificationStopAction
                traceLifecycle(VpnServiceLifecycleTrace.Event.StartRead, startId, command, generation)
                if (generation != intentGeneration || nextOwned?.request?.cancelling == true) return@launch
                val builder = snapshot?.let { meowFlutterBuilderSettings(settings, it.handoff.vpnOptionsJson) }
                val plan = VpnPlan(profile, settings, vpnIpv6 = builder?.ipv6, vpnSettings = builder)
                val actual = if (reconfigure) coordinator.requestedBuilderSettings() else settings
                check(canStartRuntime(checkNotNull(actual).network.vpnEnabled, intent?.action == SERVICE_INTERFACE) {
                    prepare(this@LiteVpnService) == null
                }) { "VPN authorization and TUN mode are required for a system VPN request" }
                lastPlan = plan.copy(ownerIntentToken = generation)
                syncListenerPolicy()
                if (reconfigure) coordinator.reconfigure(checkNotNull(lastPlan)) else coordinator.start(checkNotNull(lastPlan), requestedAt)
                traceLifecycle(VpnServiceLifecycleTrace.Event.StartSubmitted, startId, command, generation)
            } catch (failure: Exception) {
                traceLifecycle(if (failure is CancellationException) VpnServiceLifecycleTrace.Event.StartCancelled else VpnServiceLifecycleTrace.Event.StartRejected, startId, command, generation)
                if (generation == intentGeneration) {
                    requested(false)
                    retireOwnedStart()
                    graph.publishVpn(owner, VpnSnapshot(VpnPhase.Failed, error = failure.message))
                    lastPlan = null
                    removeNotification()
                    traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId, command, generation)
                    stopSelf(startId)
                }
            }
        }
        if (nextOwned != null && ownedStart === nextOwned) ownedPreparation = preparation
        return START_STICKY
    }

    private fun rejectOwnedStart(startId: Int): Int {
        if (wasRequested(this)) return START_STICKY
        if (lastPlan == null && !maintenanceHold &&
            coordinator.state.value.phase in setOf(VpnPhase.Stopped, VpnPhase.Stopping)) finishStop(startId, intentGeneration)
        return START_NOT_STICKY
    }

    /** Invalidate generation or admission first: a first Native capture may otherwise see an unowned gap. */
    private fun retireOwnedStart(): Job? {
        val previous = ownedStart
        val preparation = ownedPreparation
        ownedStart = null
        ownedPreparation = null
        previous?.let { graph.ownedStarts.retire(it.request) }
        return preparation
    }

    private fun cancelOwnedStart(expected: OwnedStartServiceReceipt) {
        scope.launch(Dispatchers.Default + NonCancellable) {
            val result = cancelOwnedStartForService(expected,
                current = { ownedStart.takeIf { graph.ownsVpnOwner(owner) && intentGeneration == expected.intentGeneration } },
                currentGeneration = { if (graph.ownsVpnOwner(owner)) intentGeneration else Long.MIN_VALUE },
                latestStartId = { latestReceivedStartId },
                onMain = { action -> withContext(Dispatchers.Main.immediate) { action() } },
                withdraw = {
                    val generation = ++intentGeneration
                    val preparation = retireOwnedStart()
                    preparation?.cancel()
                    requested(false)
                    lastPlan = null
                    syncListenerPolicy()
                    OwnedStartStop(generation, preparation, coordinator.stopAndCapture())
                },
                finish = { startId, generation ->
                    requestVpnServiceStop(startId, generation, intentGeneration, lastPlan != null, maintenanceHold) { capturedId ->
                        removeNotification()
                        traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, capturedId, causeIntentGeneration = generation)
                        stopSelf(capturedId)
                    }
                })
            expected.request.completeCancellation(result)
        }
    }

    private fun updateUnderlying() {
        val observed = graph.physicalNetworks.snapshots.value
        val preferred = observed.preferred
        val captured = if (preferred == null) CapturedMeowPhysical(null, SystemDnsSnapshot(), null) else {
            val links = observed.links[preferred] ?: return
            if (links.dnsServers.isEmpty() || links.interfaceName.isNullOrBlank()) return
            try {
                CapturedMeowPhysical(preferred, capturedMeowDns(links.interfaceName, links.dnsServers.mapNotNull { it.hostAddress }),
                    preferred.networkHandle.toULong().toString())
            } catch (failure: Exception) {
                coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName)
                return
            }
        }
        val key = captured.networkHandle?.let { "$it:${captured.dns.json()}" }
        physical.value = captured
        syncListenerPolicy()
        if (networkKey == key) return
        networkKey = key
        mutableHostNetworkGeneration.value++
    }

    internal fun recordBuilderDns(dns: List<String>) { builderDns = dns.toList() }

    private suspend fun requireFlutterRuntime(expected: MeowSetupRuntime): Pair<VpnSnapshot, RuntimeAppliedIdentity> {
        val actual = coordinator.state.value
        val identity = graph.runtimeHandoffs.readAppliedIdentity()
        check(graph.ownsVpnOwner(owner) && actual.phase == VpnPhase.Running && actual.endpoint != null &&
            actual.sessionGeneration == expected.sessionGeneration && owner == expected.serviceOwner &&
            identity != null && identity.revision == expected.revision && identity.intentEpoch == expected.intentEpoch &&
            identity.compiledSha256 == expected.compiledSha256 && identity.proof.coreEpoch == expected.coreEpoch &&
            identity.proof.serviceOwner == owner && identity.proof.sessionGeneration == actual.sessionGeneration &&
            graph.runtimeHandoffs.isCurrent(expected.revision, expected.intentEpoch)) { "runtime_service_superseded" }
        val epoch = CoreApiClient(ControlEndpoint(actual.endpoint.port, actual.endpoint.secret)).use { it.routeSnapshot().coreEpoch.toString() }
        check(epoch == expected.coreEpoch && coordinator.state.value.endpoint === actual.endpoint) { "runtime_service_superseded" }
        return actual to identity
    }

    private suspend fun flutterReplaceGraph(snapshot: MeowRuntimeSnapshot): RuntimeAppliedIdentity {
        withContext(Dispatchers.Main.immediate) {
            check(graph.ownsVpnOwner(owner) && wasRequested(this@LiteVpnService) &&
                graph.runtimeHandoffs.isCurrent(snapshot.revision, snapshot.intentEpoch) &&
                coordinator.requestedBuilderSettings() != null && !maintenanceHold) { "runtime_replacement_superseded" }
            startService(Intent(this@LiteVpnService, LiteVpnService::class.java).setAction(ACTION_RECONFIGURE)
                .putExtra(EXTRA_RUNTIME_REVISION, snapshot.revision).putExtra(EXTRA_RUNTIME_EPOCH, snapshot.intentEpoch)
                .putExtra(EXTRA_REPLACEMENT_OWNER, owner).putExtra(EXTRA_REPLACEMENT_INTENT, intentGeneration))
        }
        val receipt = kotlinx.coroutines.withTimeout(45_000) {
            combine(graph.flutterRuntimeIdentity, graph.vpn, startupIntentChanges) { identity, state, _ -> identity to state }
                .first { (identity, state) ->
                    identity?.let { it.revision == snapshot.revision && it.intentEpoch == snapshot.intentEpoch } == true ||
                        !graph.ownsVpnOwner(owner) || !wasRequested(this@LiteVpnService) ||
                        !graph.runtimeHandoffs.isCurrent(snapshot.revision, snapshot.intentEpoch) || state.phase == VpnPhase.Failed
                }.first
        }
        check(receipt != null && receipt.revision == snapshot.revision && receipt.intentEpoch == snapshot.intentEpoch &&
            graph.runtimeHandoffs.isGraphCurrent(receipt) && graph.flutterRuntimeIdentityIsCurrent(receipt)) { "runtime_replacement_superseded" }
        return receipt
    }

    private fun startupFlutterSetup(snapshot: MeowRuntimeSnapshot): MeowServiceSetup {
        val setup = MeowServiceSetup(JSONObject(snapshot.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), flutterTestUrl)
        check(checkNotNull(runtimeSettings).requests.checkCertificate == !setup.skipCertVerify) { "skip-cert-verify: Startup policy differs from compiled setup" }
        return setup
    }

    private suspend fun applyFlutterSelections(endpoint: CoreEndpoint, setup: MeowServiceSetup) {
        val runtime = checkNotNull(runtimeSnapshot)
        CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
            MeowRuntimeSelection(client).apply(setup.selectedMap) {
                graph.runtimeHandoffs.isCurrent(runtime.revision, runtime.intentEpoch) &&
                    lastPlan?.ownerIntentToken == intentGeneration && graph.ownsVpnOwner(owner)
            }
        }
    }

    private suspend fun persistFlutterProjection(before: RuntimeAppliedIdentity, endpoint: CoreEndpoint,
        handoff: RuntimeHandoff): RuntimeAppliedIdentity {
        val actual = coordinator.state.value
        check(actual.endpoint === endpoint && actual.phase == VpnPhase.Running &&
            actual.sessionGeneration == before.proof.sessionGeneration && graph.ownsVpnOwner(owner) &&
            graph.runtimeHandoffs.isCurrent(before.revision, before.intentEpoch)) { "runtime_service_superseded" }
        val epoch = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot().coreEpoch.toString() }
        val identity = graph.runtimeHandoffs.commitRuntimeProjection(before, handoff,
            RuntimeAppliedProof(owner, actual.sessionGeneration, epoch))
        runtimeSnapshot = graph.runtimeHandoffs.readCurrent()
        runtimeSettings = MeowRuntimeSnapshotSettings.decode(handoff.runtimeSettingsJson)
        lastPlan = lastPlan?.copy(settings = checkNotNull(runtimeSettings))
        policyPreferences = checkNotNull(runtimeSettings).backgroundPolicy
        notificationStopPreference = JSONObject(handoff.notificationJson).getBoolean("showStopAction")
        notificationUpdates.contentChanged(actual.phase)
        publishFlutterRuntime(FlutterPublication(identity, endpoint, intentGeneration))
        graph.publishVpn(owner, coordinator.state.value)
        return identity
    }

    private suspend fun applyBackgroundListenerPolicy(policy: dev.aerove.lite.backgroundpolicy.ListenerPolicyIntent): Unit = flutterRuntimeGate.withLock {
        if (!listenerPolicy.isCurrent(policy) || !graph.ownsVpnOwner(owner)) return@withLock
        val actual = coordinator.state.value
        val endpoint = actual.endpoint
        val identity = if (runtimeSnapshot != null && endpoint != null && actual.phase == VpnPhase.Running)
            graph.runtimeHandoffs.readAppliedIdentity()?.takeIf {
                it.proof.serviceOwner == owner && it.proof.sessionGeneration == actual.sessionGeneration &&
                    graph.runtimeHandoffs.isCurrent(it.revision, it.intentEpoch)
            } else null
        if (runtimeSnapshot != null) publishFlutterRuntime(null)
        coordinator.setListenerPolicy(policy.listening && flutterListeningRequested) {
            listenerPolicy.isCurrent(policy) && graph.ownsVpnOwner(owner)
        }
        if (identity != null && listenerPolicy.isCurrent(policy) && coordinator.state.value.endpoint === endpoint &&
            graph.runtimeHandoffs.isCurrent(identity.revision, identity.intentEpoch))
            persistFlutterProjection(identity, checkNotNull(endpoint), checkNotNull(graph.runtimeHandoffs.readLastApplied()).handoff)
    }

    private suspend fun flutterSetListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity {
        try {
            return meowWithListenerPolicyDecision(flutterRuntimeGate, request = {
                requireFlutterRuntime(expected)
                intentGeneration to syncListenerPolicy()
            }, decision = listenerPolicy::latestDecision, isCurrent = listenerPolicy::isCurrent) { admittedIntent, policy ->
                meowRequireListenerPolicyCurrent(admittedIntent == intentGeneration && graph.ownsVpnOwner(owner))
                val (actual, before) = requireFlutterRuntime(expected)
                val endpoint = checkNotNull(actual.endpoint)
                val listening = enabled && policy.listening
                val previousListeningRequested = flutterListeningRequested
                fun current() = admittedIntent == intentGeneration && graph.ownsVpnOwner(owner) &&
                    coordinator.state.value.endpoint === endpoint &&
                    graph.runtimeHandoffs.isCurrent(before.revision, before.intentEpoch) && listenerPolicy.isCurrent(policy)
                try {
                    meowRequireListenerPolicyCurrent(current())
                    publishFlutterRuntime(null)
                    flutterListeningRequested = enabled
                    coordinator.setListenerPolicy(listening, ::current)
                    meowRequireListenerPolicyCurrent(current())
                    val readback = coordinator.state.value
                    check(readback.endpoint === endpoint && readback.listening == listening &&
                        readback.suspended == !listening && readback.listenerError == null) { "runtime_listener_readback_failed" }
                    val previous = checkNotNull(graph.runtimeHandoffs.readLastApplied()).handoff
                    val runtime = JSONObject(previous.runtimeSettingsJson).put("flutterListeningRequested", enabled)
                    val projection = RuntimeHandoff(previous.coreSha, previous.sourceId, previous.sourceRevision, previous.sourceSha256,
                        previous.compiledYaml, previous.compiledSha256, previous.setupRecordJson, runtime.toString(),
                        previous.vpnOptionsJson, previous.notificationJson, previous.resources)
                    meowRequireListenerPolicyCurrent(current())
                    persistFlutterProjection(before, endpoint, projection)
                } catch (failure: Throwable) {
                    if (meowListenerFailureRequiresStop(failure, current())) {
                        coordinator.stopIfCurrent(endpoint, "Listener transaction failed: ${failure.message}")
                    } else {
                        flutterListeningRequested = previousListeningRequested
                        // Reconcile via the same owner callback; a cancelled caller cannot strand a null publication.
                        coreScope.launch { applyBackgroundListenerPolicy(listenerPolicy.latestDecision()) }
                    }
                    throw failure
                }
            }
        } catch (superseded: MeowListenerPolicySuperseded) {
            throw MeowProtocolFailure("superseded", "Listener policy request was superseded")
        }
    }
    private suspend fun flutterApplyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity = flutterRuntimeGate.withLock {
        val (actual, before) = requireFlutterRuntime(expected)
        val endpoint = checkNotNull(actual.endpoint)
        val old = checkNotNull(runtimeSettings)
        val next = projection.settings
        val previous = checkNotNull(graph.runtimeHandoffs.readLastApplied()).handoff
        val vpnOptions = JSONObject(projection.sharedStateJson).optJSONObject("vpnOptions")?.toString() ?: previous.vpnOptionsJson
        val desiredBuilder = meowFlutterBuilderSettings(next, vpnOptions)
        fun blocked(field: String): Nothing = throw MeowProtocolFailure("implementation_blocked", "Setting requires a new compiled runtime or explicit VPN restart", mapOf("field" to field))
        if (requiresVpnRestart(desiredBuilder, actual)) blocked("vpnOptions")
        if (old.ipv6 != next.ipv6) blocked("ipv6")
        if (old.dnsPreferences != next.dnsPreferences) blocked("dnsPreferences")
        if (old.geoResources != next.geoResources) blocked("geox-url")
        if (old.autoStart != next.autoStart) blocked("autoStart")
        if (old.backgroundPolicy.copy(onlyStatisticsProxy = next.backgroundPolicy.onlyStatisticsProxy,
                showNotificationStopAction = next.backgroundPolicy.showNotificationStopAction) != next.backgroundPolicy) blocked("backgroundPolicy")
        if (old.appAccess != next.appAccess || old.packages != next.packages || old.appFilterMode != next.appFilterMode) blocked("accessControlProps")
        if (!actual.listening && (old.network != next.network || old.ipv6 != next.ipv6)) blocked("network.parked")
        val previousUpdate = JSONObject(previous.runtimeSettingsJson).optJSONObject("flutterUpdate")
        val requestedUpdate = projection.updateParamsJson?.let(::JSONObject)
        if (requestedUpdate != null && requestedUpdate.opt("log-level") != previousUpdate?.opt("log-level")) blocked("log-level")
        if (requestedUpdate != null && !meowJsonEqual(requestedUpdate.opt("tun"), previousUpdate?.opt("tun"))) blocked("tun")
        val oldSetup = MeowServiceSetup(JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), flutterTestUrl)
        val setupJson = projection.setupParamsJson?.let(::JSONObject) ?: JSONObject(oldSetup.json.toString())
        if (requestedUpdate != null && !meowJsonEqual(requestedUpdate, previousUpdate))
            setupJson.put("skip-cert-verify", !next.requests.checkCertificate)
        val setup = MeowServiceSetup(setupJson.toString(), flutterTestUrl)
        if (next.requests.checkCertificate != !setup.skipCertVerify) blocked("skip-cert-verify")
        if (setup.testUrl != flutterTestUrl && meowMissingGroupTestUrl(dev.aerove.lite.data.ConfigYaml.parse(previous.compiledYaml))) blocked("test-url")
        if (setup.json.getJSONObject("applied").getString("yaml-md5") != JSONObject(previous.setupRecordJson).getString("yaml-md5")) blocked("applied.yaml-md5")
        try {
            publishFlutterRuntime(null)
            coordinator.configurePolicies(next)
            CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
                if (old.mode != next.mode) client.setMode(next.mode)
                check(client.mode() == next.mode) { "mode: Native mode did not converge" }
            }
            if (old.network != next.network || old.ipv6 != next.ipv6) {
                val requestBefore = coordinator.state.value.networkRequest?.id ?: 0
                networkRuntime.settings(next.network, next.ipv6)
                val readback = kotlinx.coroutines.withTimeout(25_000) { coordinator.state.first { state ->
                    state.endpoint !== endpoint || state.phase != VpnPhase.Running || state.networkRequest?.let {
                        it.id > requestBefore && it.network == next.network && it.ipv6 == next.ipv6 &&
                            it.outcome != dev.aerove.lite.networksettings.NetworkRuntimeOutcome.Applying
                    } == true
                } }
                check(readback.endpoint === endpoint && readback.networkError == null &&
                    readback.networkRequest?.outcome == dev.aerove.lite.networksettings.NetworkRuntimeOutcome.Applied) { readback.networkError ?: "network: Native apply was not confirmed" }
            }
            if (setup.selectedMap != oldSetup.selectedMap) applyFlutterSelections(endpoint, setup)
            val handoff = meowServiceProjection(previous, next, JSONObject(projection.sharedStateJson), projection.updateParamsJson,
                setup.json.toString(), setup.testUrl)
            val result = persistFlutterProjection(before, endpoint, handoff)
            flutterTestUrl = setup.testUrl
            refreshNetworkPermissions()
            result
        } catch (failure: Throwable) {
            coordinator.stopIfCurrent(endpoint, "Live update failed: ${failure.message}")
            throw failure
        }
    }

    private suspend fun flutterRefreshSetup(expected: MeowSetupRuntime, params: String): RuntimeAppliedIdentity = flutterRuntimeGate.withLock {
        val (actual, before) = requireFlutterRuntime(expected)
        val endpoint = checkNotNull(actual.endpoint)
        val previous = checkNotNull(graph.runtimeHandoffs.readLastApplied()).handoff
        val setup = MeowServiceSetup(params, flutterTestUrl)
        if (JSONObject(previous.setupRecordJson).getString("yaml-md5") != setup.json.getJSONObject("applied").getString("yaml-md5"))
            throw MeowProtocolFailure("superseded", "Setup YAML identity changed")
        if (setup.testUrl != flutterTestUrl && meowMissingGroupTestUrl(dev.aerove.lite.data.ConfigYaml.parse(previous.compiledYaml)))
            throw MeowProtocolFailure("implementation_blocked", "Existing default-URL health groups require a new compiled session", mapOf("field" to "test-url"))
        val oldSetup = MeowServiceSetup(JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), flutterTestUrl)
        try {
            val next = checkNotNull(runtimeSettings).copy(requests = checkNotNull(runtimeSettings).requests.copy(checkCertificate = !setup.skipCertVerify))
            if (oldSetup.skipCertVerify != setup.skipCertVerify || oldSetup.selectedMap != setup.selectedMap) {
                publishFlutterRuntime(null)
                coordinator.configurePolicies(next)
                applyFlutterSelections(endpoint, setup)
            }
            val update = JSONObject(previous.runtimeSettingsJson).optJSONObject("flutterUpdate")?.put("skip-cert-verify", setup.skipCertVerify)?.toString()
            val handoff = meowServiceProjection(previous, next, null, update, params, setup.testUrl)
            val result = persistFlutterProjection(before, endpoint, handoff)
            flutterTestUrl = setup.testUrl
            result
        } catch (failure: Throwable) {
            coordinator.stopIfCurrent(endpoint, "Setup refresh failed: ${failure.message}")
            throw failure
        }
    }

    private suspend fun initialListening(plan: VpnPlan): Boolean {
        val token = withContext(Dispatchers.Main.immediate) {
            val expected = checkNotNull(plan.ownerIntentToken) { "Runtime owner intent is unavailable" }
            fun requireCurrent() {
                if (expected != intentGeneration || lastPlan == null || !wasRequested(this@LiteVpnService) || maintenanceHold)
                    throw CancellationException("Startup intent was superseded")
            }
            requireCurrent()
            val current = checkNotNull(currentSettings()) { "Settings are not loaded" }.backgroundPolicy
            if (current.excludeSSIDs != policyPreferences.excludeSSIDs) {
                policyPreferences = current
                refreshNetworkPermissions()
            }
            if (needsSsid()) {
                val observed = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    kotlinx.coroutines.flow.combine(graph.physicalNetworks.snapshots, startupIntentChanges) { _, _ -> Unit }.first {
                        requireCurrent()
                        val snapshot = graph.physicalNetworks.snapshots.value
                        val wifi = snapshot.networks.filter { candidate ->
                            snapshot.capabilities[candidate]?.let { caps ->
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                            } == true
                        }
                        wifi.all { it in snapshot.observedCapabilities }
                    }
                    true
                }
                check(observed == true) { "Android did not deliver Wi-Fi capabilities for the on-demand policy" }
            }
            graph.physicalNetworks.refresh()
            updateUnderlying()
            if (needsSsid() && network?.let(graph.physicalNetworks.snapshots.value.capabilities::get)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                check(AndroidSsidPolicy.read(network?.let(graph.physicalNetworks.snapshots.value.capabilities::get), policyPreferences,
                    AndroidSsidPolicy.access(this@LiteVpnService)) != null) { "Android did not provide the current Wi-Fi SSID despite location authorization" }
            }
            syncListenerPolicy()
            requireCurrent()
            expected
        }
        val decision = checkNotNull(kotlinx.coroutines.withTimeoutOrNull(10_000) { listenerPolicy.latestDecision() }) {
            "On-demand policy did not resolve before startup"
        }
        return withContext(Dispatchers.Main.immediate) {
            if (token != intentGeneration || lastPlan == null || !wasRequested(this@LiteVpnService) || maintenanceHold)
                throw CancellationException("Startup intent was superseded")
            decision.listening && flutterListeningRequested
        }
    }

    private fun syncListenerPolicy(): Long {
        check(::listenerPolicy.isInitialized) { "Listener policy is not initialized" }
        val ssid = if (policyPreferences.excludeSSIDs.isEmpty()) null else
            AndroidSsidPolicy.read(network?.let(graph.physicalNetworks.snapshots.value.capabilities::get), policyPreferences, AndroidSsidPolicy.access(this))
        return listenerPolicy.update(policyPreferences, lastPlan != null && wasRequested(this) && !maintenanceHold, false, ssid)
    }

    private fun needsSsid(): Boolean = policyPreferences.excludeSSIDs.isNotEmpty() && AndroidSsidPolicy.access(this).granted

    private fun refreshNetworkPermissions() {
        val lease = networkLease ?: return
        lease.updateLocation(needsSsid())
        graph.physicalNetworks.refresh()
        updateUnderlying()
    }

    private fun finishStop(startId: Int, generation: Long) {
        traceLifecycle(VpnServiceLifecycleTrace.Event.StopJoinQueued, startId, causeIntentGeneration = generation)
        scope.launch {
            coordinator.state.first { it.phase == VpnPhase.Stopped }
            traceLifecycle(VpnServiceLifecycleTrace.Event.StopJoined, startId, causeIntentGeneration = generation)
            requestVpnServiceStop(startId, generation, intentGeneration, lastPlan != null, maintenanceHold) { capturedId ->
                removeNotification()
                traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, capturedId, causeIntentGeneration = generation)
                stopSelf(capturedId)
            }
        }
    }

    private suspend fun stopForModeFailure(endpoint: CoreEndpoint, reason: String): Boolean =
        stopModeSessionForService(endpoint, reason,
            capture = { ModeServiceIntentReceipt(coordinator.state.value.endpoint, lastPlan?.ownerIntentToken, intentGeneration) },
            onMain = { action -> withContext(Dispatchers.Main.immediate) { action() } },
            stop = coordinator::stopIfCurrent,
            clear = {
                val startId = latestReceivedStartId
                ++intentGeneration
                val preparation = retireOwnedStart()
                preparation?.cancel()
                requested(false)
                lastPlan = null
                syncListenerPolicy()
                removeNotification()
                traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId)
                if (startId > 0) stopSelf(startId)
            })

    private suspend fun stopForMaintenance(): MaintenanceRuntimeContext = withContext(Dispatchers.Main.immediate) {
        val previous = wasRequested(this@LiteVpnService)
        val context = coordinator.maintenanceContext(previous)
        ++intentGeneration
        val preparation = retireOwnedStart()
        preparation?.cancel()
        maintenanceHold = true
        val receipt = context.copy(ownerToken = owner, intentGeneration = intentGeneration)
        heldMaintenance = receipt
        requested(false)
        lastPlan = null
        syncListenerPolicy()
        coordinator.stopAndJoin()
        receipt
    }

    private suspend fun resumeAfterMaintenance(context: MaintenanceRuntimeContext, profileId: String?, settings: LiteSettings?,
        resume: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (!maintenanceHold || context.ownerToken != owner || context.intentGeneration != intentGeneration) return@withContext
        maintenanceHold = false
        heldMaintenance = null
        if (!resume || !context.userRequested) {
            val startId = latestReceivedStartId
            removeNotification()
            traceLifecycle(VpnServiceLifecycleTrace.Event.StopSelfById, startId)
            if (startId > 0) stopSelf(startId)
            return@withContext
        }
        val baseline = context.builderSettings ?: checkNotNull(context.previousPlan).builderSettings()
        check(canStartRuntime(baseline.network.vpnEnabled) { prepare(this@LiteVpnService) == null }) { "VPN authorization is required" }
        val restored = checkNotNull(settings) { "Restored runtime settings are unavailable" }
        val plan = VpnPlan(checkNotNull(profileId) { "Restored profile is unavailable" }, restored, vpnSettings = baseline,
            vpnReservedFakeIpRanges = context.previousPlan?.vpnReservedFakeIpRanges, vpnDnsEnabled = context.previousPlan?.vpnDnsEnabled)
        ++intentGeneration
        val preparation = retireOwnedStart()
        preparation?.cancel()
        lastPlan = plan.copy(ownerIntentToken = intentGeneration)
        requested(true)
        syncListenerPolicy()
        showForeground(VpnPhase.Starting)
        coordinator.start(checkNotNull(lastPlan))
        val expected = intentGeneration
        val resumed = coordinator.state.first {
            intentGeneration != expected || it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed
        }
        check(intentGeneration == expected) { "Runtime restore was superseded by a newer user intent" }
        check(resumed.phase == VpnPhase.Running) { resumed.error ?: "Restored runtime failed to become ready" }
    }

    private suspend fun refreshResources(plan: VpnPlan, endpoint: CoreEndpoint) {
        if (plan.profileId.startsWith("flutter:")) return
        fun current() = lastPlan == plan && intentGeneration == plan.ownerIntentToken &&
            coordinator.state.value.endpoint === endpoint
        refreshRuntimeResources(graph.profiles, plan.profileId, endpoint, ::current,
            reload = { next, owns -> graph.reloadRules(endpoint, next, owns) },
            reconnect = coordinator::reconnect,
            failed = { failure -> coordinator.recordResourceError(endpoint, failure.message ?: failure.javaClass.simpleName) })
    }

    override fun onRevoke() {
        val revokedGeneration = intentGeneration
        scope.launch {
            if (revokedGeneration != intentGeneration) return@launch
            graph.invalidatePendingStarts()
            if ((coordinator.requestedBuilderSettings()?.network?.vpnEnabled
                    ?: heldMaintenance?.builderSettings?.network?.vpnEnabled
                    ?: heldMaintenance?.previousPlan?.builderSettings()?.network?.vpnEnabled) != true) return@launch
            val generation = ++intentGeneration
            val preparation = retireOwnedStart()
            preparation?.cancel()
            val startId = latestReceivedStartId
            maintenanceHold = false
            heldMaintenance = null
            requested(false)
            lastPlan = null
            val revokedRuntime = graph.runtimeHandoffs.peekCurrent()
            withContext(NonCancellable) {
                try {
                    (revokedRuntime ?: graph.runtimeHandoffs.readCurrent())?.let {
                        if (generation == intentGeneration) graph.runtimeHandoffs.invalidate(it.intentEpoch)
                    }
                } finally {
                    if (generation == intentGeneration) {
                        coordinator.stop()
                        finishStop(startId, generation)
                    }
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::coordinator.isInitialized) notificationUpdates.configurationChanged(
            resources.configuration.locales.toLanguageTags(), coordinator.state.value.phase)
    }

    override fun onDestroy() {
        publishFlutterRuntime(null)
        traceLifecycle(VpnServiceLifecycleTrace.Event.DestroyEntered)
        ++intentGeneration
        retireOwnedStart()?.cancel()
        startupIntentChanges.value++
        maintenanceHold = false
        heldMaintenance = null
        physicalObservation?.cancel()
        physicalSwitching?.cancel()
        graph.clearMaintenanceStop(owner)
        graph.clearRuleReload(owner)
        graph.clearModeCleanup(owner)
        graph.clearFlutterRuntime(owner)
        if (::listenerPolicy.isInitialized) listenerPolicy.close()
        removeNotification()
        notificationUpdates.close()
        unregisterReceiver(locationReceiver)
        try { networkLease?.close() } catch (failure: Exception) {
            coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName)
        } finally { networkLease = null }
        scope.launch {
            try {
                try {
                    physicalObservation?.join()
                    physicalSwitching?.join()
                    traceLifecycle(VpnServiceLifecycleTrace.Event.CoordinatorCloseBegin)
                    coordinator.close()
                    traceLifecycle(VpnServiceLifecycleTrace.Event.CoordinatorClosed)
                } finally {
                    networkRuntime.close()
                    traceLifecycle(VpnServiceLifecycleTrace.Event.NetworkRuntimeClosed)
                }
                val snapshot = coordinator.state.value
                graph.publishVpn(owner, if (snapshot.phase == VpnPhase.Failed ||
                    (snapshot.phase == VpnPhase.Stopped && !snapshot.error.isNullOrBlank())) snapshot else VpnSnapshot())
                graph.restoreParkedGraphAfterService(owner)
            } finally {
                traceLifecycle(VpnServiceLifecycleTrace.Event.ScopesCancelBegin)
                coreScope.cancel()
                scope.cancel()
                traceLifecycle(VpnServiceLifecycleTrace.Event.ScopesCancelRequested)
            }
        }
        super.onDestroy()
        traceLifecycle(VpnServiceLifecycleTrace.Event.DestroyReturned)
    }

    private fun traceLifecycle(event: VpnServiceLifecycleTrace.Event, causeStartId: Int? = null,
        causeCommand: VpnServiceLifecycleTrace.Command? = null, causeIntentGeneration: Long? = null) {
        runCatching {
            val snapshot = if (::coordinator.isInitialized) coordinator.state.value else null
            android.util.Log.i("VpnServiceLifecycle", VpnServiceLifecycleTrace.format(event = event,
                processInstance = lifecycleInstance, latestReceivedStartId = latestReceivedStartId,
                latestReceivedCommand = latestReceivedCommand, causeStartId = causeStartId,
                causeCommand = causeCommand, causeIntentGeneration = causeIntentGeneration, owner = owner,
                intentGeneration = intentGeneration, sessionGeneration = snapshot?.sessionGeneration, phase = snapshot?.phase,
                visiblePhase = graph.vpn.value.phase, planIntentGeneration = lastPlan?.ownerIntentToken,
                scopeActive = scope.coroutineContext[Job]?.isActive == true, coreScopeActive = coreScope.coroutineContext[Job]?.isActive == true))
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL) ?: NotificationChannel(CHANNEL,
            getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        val name = getString(R.string.vpn_channel_name)
        if (manager.getNotificationChannel(CHANNEL) == null || channel.name.toString() != name) {
            channel.name = name
            manager.createNotificationChannel(channel)
        }
    }

    private fun showForeground(phase: VpnPhase) {
        if (foregroundPosted) { notificationUpdates.phase(phase); return }
        createChannel()
        startForeground(NOTIFICATION_ID, notification(phase))
        foregroundPosted = true
        notificationUpdates.started(phase, resources.configuration.locales.toLanguageTags())
    }

    private fun syncNotification(snapshot: VpnSnapshot) {
        if (vpnForegroundRequired(snapshot)) showForeground(snapshot.phase) else removeNotification()
    }

    private fun removeNotification() {
        notificationUpdates.withdraw()
        if (foregroundPosted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundPosted = false
        }
    }

    private fun notification(phase: VpnPhase): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiteVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val label = if (::coordinator.isInitialized && coordinator.state.value.suspended) getString(R.string.integration_suspended) else when (phase) {
            VpnPhase.Running -> getString(R.string.vpn_running)
            VpnPhase.Starting -> getString(R.string.vpn_starting)
            VpnPhase.Stopping -> getString(R.string.vpn_stopping)
            VpnPhase.Failed -> getString(R.string.vpn_failed)
            VpnPhase.Stopped -> getString(R.string.vpn_stopped)
        }
        val vpnEnabled = if (::coordinator.isInitialized) {
            coordinator.state.value.initialSettings?.network?.vpnEnabled
                ?: coordinator.requestedBuilderSettings()?.network?.vpnEnabled
                ?: currentSettings()?.network?.vpnEnabled ?: true
        } else currentSettings()?.network?.vpnEnabled ?: true
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(runtimeSnapshot?.let { org.json.JSONObject(it.handoff.notificationJson).getString("currentProfileName") } ?: "AEROVE Lite").setContentText(label)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null)
            .apply {
                if (notificationShowsStopAction(vpnEnabled, notificationStopPreference))
                    addAction(Notification.Action.Builder(null, runtimeSnapshot?.let { JSONObject(it.handoff.notificationJson).getString("stopText") }
                        ?: getString(R.string.action_disconnect), stop).build())
            }.build()
    }

    private fun captureOwnerIntent(plan: VpnPlan): () -> Boolean {
        return capturedOwnedStartIntent(plan.ownerIntentToken, ownedStart, owner, { intentGeneration },
            { ownerAdmissionAllowed }, { graph.ownsVpnOwner(owner) })
    }

    private fun requested(value: Boolean) {
        if (!value) publishFlutterRuntime(null)
        ownerAdmissionAllowed = value
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("requested", value).commit()
        startupIntentChanges.value++
    }

    companion object {
        private val mutableHostNetworkGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val hostNetworkGeneration: kotlinx.coroutines.flow.StateFlow<Long> = mutableHostNetworkGeneration
        const val ACTION_START = "dev.aerove.lite.START"
        const val EXTRA_REQUESTED_AT_NANOS = "dev.aerove.lite.REQUESTED_AT_NANOS"
        const val EXTRA_RUNTIME_REVISION = "runtimeRevision"
        const val EXTRA_RUNTIME_EPOCH = "runtimeEpoch"
        private const val EXTRA_REPLACEMENT_OWNER = "replacementOwner"
        private const val EXTRA_REPLACEMENT_INTENT = "replacementIntent"
        const val EXTRA_OWNED_START_ID = "dev.aerove.lite.OWNED_START_ID"
        const val ACTION_STOP = "dev.aerove.lite.STOP"
        const val ACTION_RECONFIGURE = "dev.aerove.lite.RECONFIGURE"
        const val ACTION_RETRY_NETWORK = "dev.aerove.lite.RETRY_NETWORK"
        const val ACTION_REFRESH_POLICY = "dev.aerove.lite.REFRESH_POLICY"
        private const val CHANNEL = "aerove-lite-vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES = "vpn-intent"

        fun wasRequested(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("requested", false)

        /** Called only as secondary cleanup after the confirmed primary reset and owned-session join. */
        suspend fun clearUserState(context: Context) = withContext(Dispatchers.IO) {
            val application = context.applicationContext as LiteApplication
            check(application.maintenanceActive) { "VPN state reset requires the maintenance gate" }
            val actual = application.vpn.value
            check(actual.phase == VpnPhase.Stopped && actual.endpoint == null) { "VPN termination is not confirmed" }
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            check(preferences.edit().remove("requested").remove("startupFailure").commit()) { "VPN state reset could not be persisted" }
            check(!preferences.contains("requested") && !preferences.contains("startupFailure")) { "VPN state reset readback did not match" }
        }

        internal fun recordStartupFailure(context: Context, failure: Exception) {
            android.util.Log.e("AEROVE Lite", "Background startup was rejected", failure)
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString("startupFailure", failure.message ?: failure.javaClass.simpleName).commit()
        }

        fun consumeStartupFailure(context: Context): String? {
            val preferences = context.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            val failure = preferences.getString("startupFailure", null)
            if (failure != null) preferences.edit().remove("startupFailure").commit()
            return failure
        }
    }
}

internal fun requestVpnServiceStop(startId: Int, commandGeneration: Long, currentGeneration: Long,
    hasPlan: Boolean, maintenanceHold: Boolean, stop: (Int) -> Unit): Boolean {
    if (startId <= 0 || commandGeneration != currentGeneration || hasPlan || maintenanceHold) return false
    stop(startId)
    return true
}

internal class VpnNotificationUpdates(
    private val publish: (VpnPhase) -> Unit,
    private val refreshChannel: () -> Unit,
) {
    private var posted = false
    private var closed = false
    private var lastPhase: VpnPhase? = null
    private var locales: String? = null

    fun started(phase: VpnPhase, languageTags: String) {
        if (closed) return
        posted = true
        lastPhase = phase
        locales = languageTags
    }

    fun phase(phase: VpnPhase) {
        if (!posted || closed || lastPhase == phase) return
        lastPhase = phase
        publish(phase)
    }

    fun configurationChanged(languageTags: String, currentPhase: VpnPhase) {
        if (!posted || closed || locales == languageTags) return
        locales = languageTags
        lastPhase = currentPhase
        refreshChannel()
        publish(currentPhase)
    }

    fun contentChanged(currentPhase: VpnPhase) {
        if (!posted || closed) return
        lastPhase = currentPhase
        publish(currentPhase)
    }

    fun withdraw() { posted = false; lastPhase = null }
    fun close() { closed = true; withdraw() }
}

internal fun notificationShowsStopAction(vpnEnabled: Boolean, preference: Boolean): Boolean = !vpnEnabled && preference

internal fun vpnForegroundRequired(snapshot: VpnSnapshot): Boolean =
    !(snapshot.phase == VpnPhase.Running && snapshot.initialSettings?.network?.vpnEnabled == true &&
        snapshot.listening && !snapshot.suspended && snapshot.initialDnsEnabled != null)

internal data class ModeServiceIntentReceipt(val endpoint: CoreEndpoint?, val planOwner: Long?, val currentIntent: Long)

internal suspend fun stopModeSessionForService(endpoint: CoreEndpoint, reason: String,
    capture: () -> ModeServiceIntentReceipt, onMain: suspend (() -> Unit) -> Unit,
    stop: suspend (CoreEndpoint, String) -> Boolean, clear: () -> Unit): Boolean {
    var captured: ModeServiceIntentReceipt? = null
    onMain { captured = capture() }
    val before = captured ?: return false
    if (before.endpoint !== endpoint) return false
    val expected = before.planOwner ?: return false
    val stopped = stop(endpoint, reason)
    if (stopped) onMain {
        val latest = capture()
        if (expected == latest.currentIntent && expected == latest.planOwner &&
            (latest.endpoint == null || latest.endpoint === endpoint)) clear()
    }
    return stopped
}

// The Service collectLatest coroutine remains the sole automatic resource loop owner.
internal suspend fun refreshRuntimeResources(
    profiles: ProfileRepository,
    profileId: String,
    endpoint: CoreEndpoint,
    current: () -> Boolean,
    reload: suspend (PreparedProfile, () -> Boolean) -> Boolean,
    reconnect: () -> Unit,
    failed: (Exception) -> Unit,
    clock: () -> Long = System::currentTimeMillis,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    var retrySchedule: PreparedProfile? = null
    var previousAttempt: Triple<String, String?, Long?>? = null
    fun stamp(prepared: PreparedProfile) = Triple(prepared.revision, prepared.generation, prepared.nextRefreshAt)
    while (current()) {
        var cycleSchedule: Triple<String, String?, Long?>? = null
        var refreshAttempted = false
        try {
            val scheduled = retrySchedule ?: withContext(Dispatchers.IO) { profiles.refreshSchedule(profileId) }
            retrySchedule = null
            val prepared = if (scheduled.generation == endpoint.resourceGeneration) scheduled else
                withContext(Dispatchers.IO) { profiles.prepare(profileId) }
            val due = prepared.nextRefreshAt ?: return
            cycleSchedule = stamp(prepared)
            if (due <= clock() && cycleSchedule == previousAttempt) {
                failed(IllegalStateException("Automatic resource refresh did not advance its schedule"))
                return
            }
            wait((due - clock()).coerceAtLeast(0))
            currentCoroutineContext().ensureActive()
            if (!current()) return
            previousAttempt = cycleSchedule
            refreshAttempted = true
            val updated = withContext(Dispatchers.IO) { profiles.refreshIfDue(profileId) }
            if (updated != null && current()) {
                val installed = try {
                    reload(updated, current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (current()) failed(failure)
                    false
                }
                currentCoroutineContext().ensureActive()
                if (!current()) return
                if (!installed) {
                    reconnect()
                    return
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!current()) return
            failed(failure)
            if (!refreshAttempted) return
            // Another key may already be due after a slow provider failure.
            retrySchedule = try {
                withContext(Dispatchers.IO) { profiles.refreshSchedule(profileId) }
                    .takeIf { next -> next.nextRefreshAt?.let { due ->
                        due > clock() || stamp(next) != cycleSchedule
                    } == true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (retrySchedule == null) return
            if (checkNotNull(retrySchedule.nextRefreshAt) <= clock()) yield()
        }
    }
}

internal suspend fun collectResourceRefreshes(
    endpoints: Flow<CoreEndpoint?>,
    profiles: ProfileRepository,
    refresh: suspend (CoreEndpoint) -> Unit,
) {
    combine(endpoints, profiles.resourceScheduleRevision) { endpoint, revision -> endpoint to revision }
        .distinctUntilChanged().collectLatest { (endpoint, _) ->
            if (endpoint != null) refresh(endpoint)
        }
}
