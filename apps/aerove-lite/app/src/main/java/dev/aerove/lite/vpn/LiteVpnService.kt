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
            latestSavedSettings = { graph.settings.changes.value },
            beforeListenerPark = networkRuntime::parkBarrier, afterListenerResume = networkRuntime::resume,
            latestNetworkDns = { physical.value.takeIf { it.network != null }?.dns }, onSessionReady = { snapshot ->
                networkRuntime.bind(snapshot.endpoint, snapshot.initialNetwork ?: snapshot.appliedNetwork, snapshot.initialIpv6 ?: snapshot.appliedIpv6,
                    snapshot.appliedNetwork, snapshot.appliedIpv6, initiallyParked = snapshot.suspended)
            })
        listenerPolicy = BackgroundPolicyActor(coreScope, { intent ->
            coordinator.setListenerPolicy(intent.listening) { listenerPolicy.isCurrent(intent) }
        }, { failure -> coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName) })
        graph.registerMaintenanceStop(owner, ::stopForMaintenance, ::resumeAfterMaintenance)
        graph.registerRuleReload(owner, coordinator::reloadRules)
        graph.registerModeCleanup(owner, ::stopForModeFailure)
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
                        coordinator.configureNetworkDns(captured.dns)
                        if (!current()) return@applyPhysicalNetworkChange
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
                    try { graph.settings.changes.value?.let { settings -> coordinator.configurePolicies(settings) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName) }
                }
        }
        scope.launch {
            coordinator.state.collect { snapshot ->
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
            collectResourceRefreshes(
                coordinator.state.map { snapshot -> snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running } },
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
            coordinator.stop()
            finishStop(startId, generation)
            return START_NOT_STICKY
        }
        if (intent == null && !wasRequested(this)) {
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
                val profile = checkNotNull(graph.profiles.snapshot().selectedId) { "Import and select a configuration first" }
                val settings = graph.settings.read()
                traceLifecycle(VpnServiceLifecycleTrace.Event.StartRead, startId, command, generation)
                if (generation != intentGeneration || nextOwned?.request?.cancelling == true) return@launch
                val plan = VpnPlan(profile, settings)
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

    private suspend fun initialListening(plan: VpnPlan): Boolean {
        val token = withContext(Dispatchers.Main.immediate) {
            val expected = checkNotNull(plan.ownerIntentToken) { "Runtime owner intent is unavailable" }
            fun requireCurrent() {
                if (expected != intentGeneration || lastPlan == null || !wasRequested(this@LiteVpnService) || maintenanceHold)
                    throw CancellationException("Startup intent was superseded")
            }
            requireCurrent()
            val current = checkNotNull(graph.settings.changes.value) { "Settings are not loaded" }.backgroundPolicy
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
            decision.listening
        }
    }

    private fun syncListenerPolicy() {
        if (!::listenerPolicy.isInitialized) return
        val ssid = if (policyPreferences.excludeSSIDs.isEmpty()) null else
            AndroidSsidPolicy.read(network?.let(graph.physicalNetworks.snapshots.value.capabilities::get), policyPreferences, AndroidSsidPolicy.access(this))
        listenerPolicy.update(policyPreferences, lastPlan != null && wasRequested(this) && !maintenanceHold, false, ssid)
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
            coordinator.stop()
            finishStop(startId, generation)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::coordinator.isInitialized) notificationUpdates.configurationChanged(
            resources.configuration.locales.toLanguageTags(), coordinator.state.value.phase)
    }

    override fun onDestroy() {
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
                ?: graph.settings.changes.value?.network?.vpnEnabled ?: true
        } else graph.settings.changes.value?.network?.vpnEnabled ?: true
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("AEROVE Lite").setContentText(label)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSound(null).setVibrate(null)
            .apply {
                if (notificationShowsStopAction(vpnEnabled, notificationStopPreference))
                    addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stop).build())
            }.build()
    }

    private fun captureOwnerIntent(plan: VpnPlan): () -> Boolean {
        return capturedOwnedStartIntent(plan.ownerIntentToken, ownedStart, owner, { intentGeneration },
            { ownerAdmissionAllowed }, { graph.ownsVpnOwner(owner) })
    }

    private fun requested(value: Boolean) {
        ownerAdmissionAllowed = value
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("requested", value).commit()
        startupIntentChanges.value++
    }

    companion object {
        private val mutableHostNetworkGeneration = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val hostNetworkGeneration: kotlinx.coroutines.flow.StateFlow<Long> = mutableHostNetworkGeneration
        const val ACTION_START = "dev.aerove.lite.START"
        const val EXTRA_REQUESTED_AT_NANOS = "dev.aerove.lite.REQUESTED_AT_NANOS"
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
