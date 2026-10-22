package dev.aerove.lite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
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

class LiteVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: VpnCoordinator
    private lateinit var networkRuntime: NetworkRuntimeCoordinator
    private lateinit var connectivity: ConnectivityManager
    private val graph get() = application as LiteApplication
    private var intentGeneration = 0L
    private var owner = 0L
    private var lastPlan: VpnPlan? = null
    @Volatile private var network: Network? = null
    private var networkKey: String? = null
    private val physicalNetworks = linkedSetOf<Network>()
    private var callbackRegistered = false
    private val notificationUpdates = VpnNotificationUpdates({ phase ->
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(phase))
    }, ::createChannel)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            physicalNetworks += network
            updateUnderlying()
        }
        override fun onLost(network: Network) {
            physicalNetworks -= network
            updateUnderlying()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateUnderlying()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = updateUnderlying()
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
        coordinator = VpnCoordinator(coreScope, AndroidTunnelBackend(this, graph) { network }, networkRuntime::detachAndJoin,
            latestSavedSettings = { graph.settings.changes.value })
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        callbackRegistered = true
        scope.launch {
            graph.settings.changes.filterNotNull().collect { settings -> networkRuntime.settings(settings.network, settings.ipv6) }
        }
        scope.launch {
            coordinator.state.collect { snapshot ->
                networkRuntime.bind(snapshot.endpoint.takeIf { snapshot.phase == VpnPhase.Running },
                    snapshot.initialNetwork, snapshot.initialIpv6, snapshot.appliedNetwork, snapshot.appliedIpv6)
                graph.publishVpn(owner, snapshot)
                notificationUpdates.phase(snapshot.phase)
                if (snapshot.phase == VpnPhase.Stopped && lastPlan == null && intentGeneration > 0) {
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
            requested(false)
            lastPlan = null
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
        requested(true)
        scope.launch {
            try {
                val profile = checkNotNull(graph.profiles.snapshot().selectedId) { "Import and select a configuration first" }
                val settings = graph.settings.read()
                if (generation != intentGeneration) return@launch
                lastPlan = VpnPlan(profile, settings)
                coordinator.start(checkNotNull(lastPlan))
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
        val dns = preferred?.let(connectivity::getLinkProperties)?.dnsServers?.joinToString { it.hostAddress ?: "" }
        val key = preferred?.let { "${it.networkHandle}:$dns" }
        if (networkKey == key) return
        networkKey = key
        network = preferred
        setUnderlyingNetworks(preferred?.let { arrayOf(it) })
        if (lastPlan != null) coordinator.reconnect()
    }

    private fun finishStop(startId: Int) {
        scope.launch {
            coordinator.state.first { it.phase == VpnPhase.Stopped }
            if (lastPlan == null) {
                removeNotification()
                stopSelf(startId)
            }
        }
    }

    private suspend fun refreshResources(plan: VpnPlan, endpoint: CoreEndpoint) {
        var retry = 60_000L
        fun current() = lastPlan == plan && coordinator.state.value.endpoint === endpoint
        while (current()) {
            try {
                val prepared = graph.profiles.prepareForStart(plan.profileId)
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
        ++intentGeneration
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
        notificationUpdates.close()
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
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
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("AEROVE Lite").setContentText(label)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.action_disconnect), stop).build()).build()
    }

    private fun requested(value: Boolean) {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean("requested", value).commit()
    }

    companion object {
        const val ACTION_START = "dev.aerove.lite.START"
        const val ACTION_STOP = "dev.aerove.lite.STOP"
        const val ACTION_RETRY_NETWORK = "dev.aerove.lite.RETRY_NETWORK"
        private const val CHANNEL = "aerove-lite-vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES = "vpn-intent"

        fun wasRequested(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("requested", false)
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

    fun withdraw() { posted = false; lastPhase = null }
    fun close() { closed = true; withdraw() }
}
