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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
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
import dev.aerove.lite.data.SystemDnsSnapshot
import dev.aerove.lite.networksettings.NetworkRuntimeCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LiteVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: VpnCoordinator
    private lateinit var networkRuntime: NetworkRuntimeCoordinator
    private lateinit var connectivity: ConnectivityManager
    private lateinit var listenerPolicy: BackgroundPolicyActor
    private val graph get() = application as LiteApplication
    @Volatile private var intentGeneration = 0L
    @Volatile private var ownerAdmissionAllowed = false
    private var owner = 0L
    private var lastPlan: VpnPlan? = null
    private var maintenanceHold = false
    private var heldMaintenance: MaintenanceRuntimeContext? = null
    private var policyPreferences = BackgroundPolicyPreferences()
    private var policyObservedEndpoint: CoreEndpoint? = null
    private val physical = kotlinx.coroutines.flow.MutableStateFlow(CapturedMeowPhysical(null, SystemDnsSnapshot(), null))
    private val network: Network? get() = physical.value.network
    private var networkKey: String? = null
    private val physicalNetworks = linkedSetOf<Network>()
    private val capabilitiesByNetwork = mutableMapOf<Network, NetworkCapabilities>()
    private val propertiesByNetwork = mutableMapOf<Network, LinkProperties>()
    private var callbackRegistered = false
    private val networkObservations = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val startupIntentChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var notificationStopPreference = true
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshNetworkPermissions() }
    }
    private val notificationUpdates = VpnNotificationUpdates({ phase ->
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(phase))
    }, ::createChannel)
    private lateinit var callback: ConnectivityManager.NetworkCallback
    private fun makeNetworkCallback(includeLocation: Boolean) = object : ConnectivityManager.NetworkCallback(
        if (includeLocation) FLAG_INCLUDE_LOCATION_INFO else 0) {
        override fun onAvailable(network: Network) {
            physicalNetworks += network
            updateUnderlying()
        }
        override fun onLost(network: Network) {
            physicalNetworks -= network
            capabilitiesByNetwork.remove(network)
            networkObservations.value++
            propertiesByNetwork.remove(network)
            updateUnderlying()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            capabilitiesByNetwork[network] = capabilities
            networkObservations.value++
            updateUnderlying()
        }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            propertiesByNetwork[network] = properties
            updateUnderlying()
        }
    }

    override fun onCreate() {
        super.onCreate()
        owner = graph.registerVpnOwner()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(VpnPhase.Starting))
        notificationUpdates.started(VpnPhase.Starting, resources.configuration.locales.toLanguageTags())
        connectivity = getSystemService(ConnectivityManager::class.java)
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
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        policyPreferences = graph.settings.changes.value?.backgroundPolicy ?: BackgroundPolicyPreferences()
        callback = makeNetworkCallback(needsSsid())
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        callbackRegistered = true
        registerReceiver(locationReceiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), RECEIVER_NOT_EXPORTED)
        scope.launch {
            physical.map { it.takeIf { captured -> captured.network != null } }.filterNotNull()
                .distinctUntilChanged { before, after -> before.networkHandle == after.networkHandle && before.dns.json() == after.dns.json() }
                .collectLatest { captured ->
                try { coordinator.configureNetworkDns(captured.dns) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName) }
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
                notificationUpdates.phase(snapshot.phase)
                val endpoint = snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running }
                if (policyObservedEndpoint !== endpoint) {
                    policyObservedEndpoint = endpoint
                    if (endpoint != null) syncListenerPolicy()
                }
                if (snapshot.phase == VpnPhase.Stopped && lastPlan == null && !maintenanceHold && intentGeneration > 0) {
                    removeNotification()
                    stopSelf()
                }
            }
        }
        scope.launch {
            coordinator.state.map { snapshot -> snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running } }
                .distinctUntilChanged().collectLatest { endpoint ->
                    if (endpoint != null) lastPlan?.let { refreshResources(it, endpoint) }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restore owns the complete stop/join/write transaction, including non-UI entry points.
        if (graph.maintenanceActive && intent?.action != ACTION_STOP) {
            if (lastPlan == null && !wasRequested(this)) {
                removeNotification()
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
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RETRY_NETWORK) {
            if (wasRequested(this)) {
                networkRuntime.retry()
                return START_STICKY
            } else {
                removeNotification()
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val generation = ++intentGeneration
        if (intent?.action == ACTION_STOP) {
            graph.invalidatePendingStarts()
            maintenanceHold = false
            heldMaintenance = null
            requested(false)
            lastPlan = null
            syncListenerPolicy()
            coordinator.stop()
            finishStop(startId)
            return START_NOT_STICKY
        }
        if (intent == null && !wasRequested(this)) {
            lastPlan = null
            coordinator.stop()
            finishStop(startId)
            return START_NOT_STICKY
        }
        val reconfigure = intent?.action == ACTION_RECONFIGURE
        if (reconfigure && coordinator.requestedBuilderSettings() == null) {
            removeNotification()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        requested(true)
        scope.launch {
            try {
                val profile = checkNotNull(graph.profiles.snapshot().selectedId) { "Import and select a configuration first" }
                val settings = graph.settings.read()
                if (generation != intentGeneration) return@launch
                val plan = VpnPlan(profile, settings)
                val actual = if (reconfigure) coordinator.requestedBuilderSettings() else settings
                check(canStartRuntime(checkNotNull(actual).network.vpnEnabled, intent?.action == SERVICE_INTERFACE) {
                    prepare(this@LiteVpnService) == null
                }) { "VPN authorization and TUN mode are required for a system VPN request" }
                lastPlan = plan.copy(ownerIntentToken = generation)
                syncListenerPolicy()
                if (reconfigure) coordinator.reconfigure(checkNotNull(lastPlan)) else coordinator.start(checkNotNull(lastPlan))
            } catch (failure: Exception) {
                if (generation == intentGeneration) {
                    graph.publishVpn(owner, VpnSnapshot(VpnPhase.Failed, error = failure.message))
                    requested(false)
                    lastPlan = null
                    removeNotification()
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun updateUnderlying() {
        val available = physicalNetworks.filter { candidate ->
            connectivity.getNetworkCapabilities(candidate)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
        val preferred = available.firstOrNull { it == connectivity.activeNetwork }
            ?: available.firstOrNull {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: available.firstOrNull()
        val captured = try { captureMeowPhysical(connectivity) { preferred } }
        catch (failure: Exception) {
            coordinator.recordListenerError(failure.message ?: failure.javaClass.simpleName)
            return
        }
        val key = captured.networkHandle?.let { "$it:${captured.dns.json()}" }
        physical.value = captured
        syncListenerPolicy()
        if (networkKey == key) return
        networkKey = key
        // The Host changes Builder underlying networks only after Native network/old-DNS join ACK.
        if (lastPlan != null && coordinator.state.value.phase == VpnPhase.Running && !coordinator.state.value.suspended) coordinator.reconnect()
    }

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
                    kotlinx.coroutines.flow.combine(networkObservations, startupIntentChanges) { _, _ -> Unit }.first {
                        requireCurrent()
                        val wifi = connectivity.allNetworks.filter { candidate ->
                            connectivity.getNetworkCapabilities(candidate)?.let { caps ->
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            } == true
                        }
                        wifi.all { capabilitiesByNetwork.containsKey(it) }
                    }
                    true
                }
                check(observed == true) { "Android did not deliver Wi-Fi capabilities for the on-demand policy" }
            }
            connectivity.allNetworks.forEach { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.takeIf { caps ->
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }?.let {
                    physicalNetworks += candidate
                    connectivity.getLinkProperties(candidate)?.let { properties -> propertiesByNetwork[candidate] = properties }
                }
            }
            updateUnderlying()
            if (needsSsid() && network?.let(capabilitiesByNetwork::get)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                check(AndroidSsidPolicy.read(network?.let(capabilitiesByNetwork::get), policyPreferences,
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
        val ssid = AndroidSsidPolicy.read(network?.let(capabilitiesByNetwork::get), policyPreferences, AndroidSsidPolicy.access(this))
        listenerPolicy.update(policyPreferences, lastPlan != null && wasRequested(this) && !maintenanceHold, false, ssid)
    }

    private fun needsSsid(): Boolean = policyPreferences.excludeSSIDs.isNotEmpty() && AndroidSsidPolicy.access(this).granted

    private fun refreshNetworkPermissions() {
        if (!callbackRegistered) return
        val includeLocation = needsSsid()
        capabilitiesByNetwork.clear()
        // Re-register the same observer so a newly granted location permission receives unredacted capabilities.
        connectivity.unregisterNetworkCallback(callback)
        callback = makeNetworkCallback(includeLocation)
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        updateUnderlying()
    }

    private fun finishStop(startId: Int) {
        scope.launch {
            coordinator.state.first { it.phase == VpnPhase.Stopped }
            if (lastPlan == null && !maintenanceHold) {
                removeNotification()
                stopSelf(startId)
            }
        }
    }

    private suspend fun stopForMaintenance(): MaintenanceRuntimeContext = withContext(Dispatchers.Main.immediate) {
        val previous = wasRequested(this@LiteVpnService)
        val context = coordinator.maintenanceContext(previous)
        ++intentGeneration
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
            removeNotification()
            stopSelf()
            return@withContext
        }
        val baseline = context.builderSettings ?: checkNotNull(context.previousPlan).builderSettings()
        check(canStartRuntime(baseline.network.vpnEnabled) { prepare(this@LiteVpnService) == null }) { "VPN authorization is required" }
        val restored = checkNotNull(settings) { "Restored runtime settings are unavailable" }
        val plan = VpnPlan(checkNotNull(profileId) { "Restored profile is unavailable" }, restored, vpnSettings = baseline,
            vpnReservedFakeIpRanges = context.previousPlan?.vpnReservedFakeIpRanges, vpnDnsEnabled = context.previousPlan?.vpnDnsEnabled)
        ++intentGeneration
        lastPlan = plan.copy(ownerIntentToken = intentGeneration)
        requested(true)
        syncListenerPolicy()
        startForeground(NOTIFICATION_ID, notification(VpnPhase.Starting))
        notificationUpdates.started(VpnPhase.Starting, resources.configuration.locales.toLanguageTags())
        coordinator.start(checkNotNull(lastPlan))
        val expected = intentGeneration
        val resumed = coordinator.state.first {
            intentGeneration != expected || it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed
        }
        check(intentGeneration == expected) { "Runtime restore was superseded by a newer user intent" }
        check(resumed.phase == VpnPhase.Running) { resumed.error ?: "Restored runtime failed to become ready" }
    }

    private suspend fun refreshResources(plan: VpnPlan, endpoint: CoreEndpoint) {
        var retry = 60_000L
        fun current() = lastPlan == plan && coordinator.state.value.endpoint === endpoint
        while (current()) {
            try {
                val prepared = graph.profiles.prepare(plan.profileId)
                val due = prepared.nextRefreshAt ?: return
                delay((due - System.currentTimeMillis()).coerceAtLeast(1000))
                if (!current()) return
                val updated = graph.profiles.refreshIfDue(plan.profileId)
                if (updated != null && current()) {
                    coordinator.reconnect()
                    return
                }
                retry = 60_000L
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!current()) return
                coordinator.recordResourceError(endpoint, failure.message ?: failure.javaClass.simpleName)
                delay(retry)
                retry = (retry * 2).coerceAtMost(900_000)
            }
        }
    }

    override fun onRevoke() {
        graph.invalidatePendingStarts()
        if ((coordinator.requestedBuilderSettings()?.network?.vpnEnabled
                ?: heldMaintenance?.builderSettings?.network?.vpnEnabled
                ?: heldMaintenance?.previousPlan?.builderSettings()?.network?.vpnEnabled) != true) return
        ++intentGeneration
        maintenanceHold = false
        heldMaintenance = null
        requested(false)
        lastPlan = null
        coordinator.stop()
        super.onRevoke()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::coordinator.isInitialized) notificationUpdates.configurationChanged(
            resources.configuration.locales.toLanguageTags(), coordinator.state.value.phase)
    }

    override fun onDestroy() {
        ++intentGeneration
        startupIntentChanges.value++
        maintenanceHold = false
        heldMaintenance = null
        graph.clearMaintenanceStop(owner)
        if (::listenerPolicy.isInitialized) listenerPolicy.close()
        notificationUpdates.close()
        unregisterReceiver(locationReceiver)
        if (callbackRegistered) {
            callbackRegistered = false
            connectivity.unregisterNetworkCallback(callback)
        }
        scope.launch {
            try {
                try {
                    coordinator.close()
                } finally {
                    networkRuntime.close()
                }
                val snapshot = coordinator.state.value
                graph.publishVpn(owner, if (snapshot.phase == VpnPhase.Failed) snapshot else VpnSnapshot())
            } finally {
                coreScope.cancel()
                scope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(CHANNEL) ?: NotificationChannel(CHANNEL,
            getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        channel.name = getString(R.string.vpn_channel_name)
        manager.createNotificationChannel(channel)
    }

    private fun removeNotification() {
        notificationUpdates.withdraw()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(phase: VpnPhase): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiteVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val label = when (phase) {
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
            .setContentIntent(open).setOngoing(true)
            .apply {
                if (notificationShowsStopAction(vpnEnabled, notificationStopPreference))
                    addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stop).build())
            }.build()
    }

    private fun captureOwnerIntent(plan: VpnPlan): () -> Boolean {
        return capturedOwnerIntent(plan.ownerIntentToken, { intentGeneration }, { ownerAdmissionAllowed })
    }

    private fun requested(value: Boolean) {
        ownerAdmissionAllowed = value
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("requested", value).commit()
        startupIntentChanges.value++
    }

    companion object {
        const val ACTION_START = "dev.aerove.lite.START"
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
