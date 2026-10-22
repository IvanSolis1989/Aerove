package dev.aerove.lite.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal class LocalNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun addresses() = callbackFlow<String?> {
        val networks = linkedSetOf<Network>()
        fun publish() {
            val ordered = networks.sortedBy { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                when {
                    network == connectivity.activeNetwork -> 0
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 1
                    else -> 2
                }
            }
            val address = ordered.firstNotNullOfOrNull { network ->
                connectivity.getLinkProperties(network)?.linkAddresses?.asSequence()?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
            }
            trySend(address)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { networks += network; publish() }
            override fun onLost(network: Network) { networks -= network; publish() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = publish()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
        trySend(null)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
