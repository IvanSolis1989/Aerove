package dev.aerove.lite.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LiteVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var coordinator: VpnCoordinator
    private lateinit var connectivity: ConnectivityManager
    private val graph get() = application as LiteApplication
    private var intentGeneration = 0L
    private var owner = 0L
    private var lastPlan: VpnPlan? = null
    @Volatile private var network: Network? = null
    private var networkKey: String? = null
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateUnderlying()
        override fun onLost(network: Network) = updateUnderlying()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = updateUnderlying()
    }

    override fun onCreate() {
        super.onCreate()
        owner = graph.registerVpnOwner()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(VpnPhase.Starting))
        connectivity = getSystemService(ConnectivityManager::class.java)
        coordinator = VpnCoordinator(coreScope, AndroidTunnelBackend(this, graph) { network })
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        callbackRegistered = true
        scope.launch {
            coordinator.state.collectLatest { snapshot ->
                graph.publishVpn(owner, snapshot)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(snapshot.phase))
                if (snapshot.phase == VpnPhase.Stopped && lastPlan == null && intentGeneration > 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_STICKY
    }

    private fun updateUnderlying() {
        val available = connectivity.allNetworks.filter { candidate ->
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
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

    override fun onDestroy() {
        if (callbackRegistered) connectivity.unregisterNetworkCallback(callback)
        scope.launch {
            try {
                coordinator.close()
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
        val channel = NotificationChannel(CHANNEL, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
        private const val CHANNEL = "aerove-lite-vpn"
        private const val NOTIFICATION_ID = 1
        private const val PREFERENCES = "vpn-intent"

        fun wasRequested(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean("requested", false)
    }
}
