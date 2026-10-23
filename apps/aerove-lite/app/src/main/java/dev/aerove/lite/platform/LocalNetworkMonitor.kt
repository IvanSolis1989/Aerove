package dev.aerove.lite.platform

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dev.aerove.lite.backgroundpolicy.AndroidSsidPolicy
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences
import dev.aerove.lite.data.SystemDnsSnapshot
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class LocalNetworkMonitor(private val context: Context,
    private val policy: () -> BackgroundPolicyPreferences = { BackgroundPolicyPreferences() },
    private val onSsid: (String?) -> Unit = {},
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun addresses() = callbackFlow<String?> {
        val networks = linkedSetOf<Network>()
        val capabilitiesByNetwork = mutableMapOf<Network, NetworkCapabilities>()
        fun publish() {
            val ordered = networks.sortedBy { network ->
                val capabilities = capabilitiesByNetwork[network]
                when {
                    network == connectivity.activeNetwork -> 0
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                    else -> 2
                }
            }
            val selected = ordered.firstOrNull()
            onSsid(AndroidSsidPolicy.read(selected?.let(capabilitiesByNetwork::get), policy(), AndroidSsidPolicy.access(context)))
            val address = ordered.firstNotNullOfOrNull { network ->
                connectivity.getLinkProperties(network)?.linkAddresses?.asSequence()?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
            }
            trySend(address)
        }
        fun makeCallback() = object : ConnectivityManager.NetworkCallback(
            if (policy().excludeSSIDs.isNotEmpty() && AndroidSsidPolicy.access(context).granted) FLAG_INCLUDE_LOCATION_INFO else 0) {
            override fun onAvailable(network: Network) { networks += network; publish() }
            override fun onLost(network: Network) { networks -= network; capabilitiesByNetwork.remove(network); publish() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                capabilitiesByNetwork[network] = capabilities
                publish()
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = publish()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        var callback = makeCallback()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        fun refreshAccess() {
            if (!active.get()) return
            capabilitiesByNetwork.clear()
            publish()
            connectivity.unregisterNetworkCallback(callback)
            callback = makeCallback()
            connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        }
        val location = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refreshAccess()
        }
        context.registerReceiver(location, IntentFilter(LocationManager.MODE_CHANGED_ACTION), Context.RECEIVER_NOT_EXPORTED)
        trySend(null)
        awaitClose {
            active.set(false)
            context.unregisterReceiver(location)
            connectivity.unregisterNetworkCallback(callback)
            onSsid(null)
        }
    }.distinctUntilChanged()
}

/** The same physical-network callback supplies resolver inputs; it never invents a fallback server. */
internal fun systemDnsSnapshot(preferred: Network?, properties: Map<Network, LinkProperties>): SystemDnsSnapshot {
    val interfaces = properties.values.mapNotNull { links ->
        links.interfaceName?.takeIf(String::isNotBlank)?.let { name -> name to links.dnsServers.mapNotNull { it.hostAddress }.distinct() }
    }.toMap()
    val selected = preferred?.let(properties::get)?.interfaceName?.takeIf { it in interfaces }
    return SystemDnsSnapshot(selected, interfaces)
}
