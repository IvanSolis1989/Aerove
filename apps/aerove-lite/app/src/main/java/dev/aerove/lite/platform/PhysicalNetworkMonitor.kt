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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ObservedPhysicalNetworks<N, C, L>(
    val networks: Set<N> = emptySet(),
    val capabilities: Map<N, C> = emptyMap(),
    val links: Map<N, L> = emptyMap(),
    val observedCapabilities: Set<N> = emptySet(),
    val preferred: N? = null,
    val address: String? = null,
)

internal interface PhysicalNetworkLease : AutoCloseable {
    fun updateLocation(includeLocation: Boolean)
}

internal interface PhysicalNetworkEvents<N, C, L> {
    fun available(network: N)
    fun lost(network: N)
    fun capabilities(network: N, value: C)
    fun links(network: N, value: L)
}

internal interface PhysicalNetworkDriver<N, C, L> {
    val activeNetwork: N?
    fun seed(): ObservedPhysicalNetworks<N, C, L>
    fun register(includeLocation: Boolean, events: PhysicalNetworkEvents<N, C, L>): AutoCloseable
}

internal class LeasedPhysicalNetworks<N, C, L>(
    private val driver: PhysicalNetworkDriver<N, C, L>,
    private val eligible: (C) -> Boolean,
    private val wifi: (C) -> Boolean,
    private val fingerprint: (N, L) -> String?,
    private val address: (List<L>) -> String?,
    private val onRevision: (Long) -> Unit = {},
) {
    private val mutableSnapshots = MutableStateFlow(ObservedPhysicalNetworks<N, C, L>())
    val snapshots: StateFlow<ObservedPhysicalNetworks<N, C, L>> = mutableSnapshots.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()
    private val leases = linkedMapOf<Long, Boolean>()
    private var leaseSequence = 0L
    private var callbackGeneration = 0L
    private var registration: AutoCloseable? = null
    private var pendingRetirement: AutoCloseable? = null
    private var registeredLocation = false
    private var physicalKey: String? = null
    private val networks = linkedSetOf<N>()
    private val capabilities = linkedMapOf<N, C>()
    private val links = linkedMapOf<N, L>()
    private val observed = linkedSetOf<N>()

    inner class Lease internal constructor(private val token: Long) : PhysicalNetworkLease {
        override fun updateLocation(includeLocation: Boolean) = update(token, includeLocation)
        override fun close() = release(token)
    }

    @Synchronized fun acquire(includeLocation: Boolean = false): Lease {
        val token = ++leaseSequence
        leases[token] = includeLocation
        try { reconcile() } catch (failure: Exception) {
            leases.remove(token)
            runCatching { reconcile() }
            throw failure
        }
        return Lease(token)
    }

    @Synchronized private fun update(token: Long, includeLocation: Boolean) {
        val previous = leases[token] ?: return
        if (previous == includeLocation) {
            if (registration == null || pendingRetirement != null) reconcile()
            return
        }
        leases[token] = includeLocation
        try { reconcile() } catch (failure: Exception) {
            leases[token] = previous
            runCatching { reconcile() }
            throw failure
        }
    }

    @Synchronized private fun release(token: Long) {
        if (leases.remove(token) != null || pendingRetirement != null) reconcile()
    }

    @Synchronized fun refresh() {
        if (leases.isEmpty()) return
        if (registration == null || pendingRetirement != null) { reconcile(); return }
        seed()
        publish()
    }

    private fun seed() {
        val seed = driver.seed()
        networks.clear(); networks += seed.networks
        val retained = observed.mapNotNull { network -> capabilities[network]?.let { network to it } }.toMap()
        capabilities.clear(); capabilities.putAll(seed.capabilities)
        retained.filterKeys { it in seed.networks }.forEach { (network, value) -> capabilities[network] = value }
        links.clear(); links.putAll(seed.links)
        observed.retainAll(networks)
    }

    private fun reconcile() {
        val location = leases.values.any { it }
        if (registration != null && pendingRetirement == null && leases.isNotEmpty() && location == registeredLocation) return
        val generation = ++callbackGeneration
        registration?.let { active ->
            check(pendingRetirement == null)
            pendingRetirement = active
            registration = null
        }
        pendingRetirement?.let { pending ->
            pending.close()
            pendingRetirement = null
        }
        if (leases.isEmpty()) return
        observed.clear()
        seed()
        val events = object : PhysicalNetworkEvents<N, C, L> {
            private fun accept(action: () -> Unit) = synchronized(this@LeasedPhysicalNetworks) {
                if (generation != callbackGeneration || leases.isEmpty()) return@synchronized
                action()
                publish()
            }
            override fun available(network: N) = accept { networks += network }
            override fun lost(network: N) = accept {
                networks -= network; capabilities.remove(network); links.remove(network); observed -= network
            }
            override fun capabilities(network: N, value: C) = accept {
                networks += network; capabilities[network] = value; observed += network
            }
            override fun links(network: N, value: L) = accept { networks += network; links[network] = value }
        }
        registration = driver.register(location, events)
        registeredLocation = location
        publish()
    }

    private fun publish() {
        val available = networks.filter { capabilities[it]?.let(eligible) == true }
        val active = driver.activeNetwork
        val preferred = available.firstOrNull { it == active }
            ?: available.firstOrNull { capabilities[it]?.let(wifi) == true } ?: available.firstOrNull()
        val ordered = available.sortedBy { when { it == preferred -> 0; capabilities[it]?.let(wifi) == true -> 1; else -> 2 } }
        mutableSnapshots.value = ObservedPhysicalNetworks(networks.toSet(), capabilities.toMap(), links.toMap(),
            observed.toSet(), preferred, address(ordered.mapNotNull(links::get)))
        val key = if (preferred == null) null else links[preferred]?.let { fingerprint(preferred, it) } ?: return
        if (key != physicalKey) {
            physicalKey = key
            val next = mutableRevision.value + 1
            onRevision(next)
            mutableRevision.value = next
        }
    }
}

internal typealias PhysicalNetworkSnapshot = ObservedPhysicalNetworks<Network, NetworkCapabilities, LinkProperties>

internal class PhysicalNetworkMonitor(context: Context, onRevision: (Long) -> Unit = {}) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
    private val owner = LeasedPhysicalNetworks(object : PhysicalNetworkDriver<Network, NetworkCapabilities, LinkProperties> {
        override val activeNetwork get() = connectivity.activeNetwork
        override fun seed(): PhysicalNetworkSnapshot {
            val capabilities = linkedMapOf<Network, NetworkCapabilities>()
            val links = linkedMapOf<Network, LinkProperties>()
            connectivity.allNetworks.forEach { network ->
                connectivity.getNetworkCapabilities(network)?.takeIf(::eligible)?.let {
                    capabilities[network] = NetworkCapabilities(it)
                    connectivity.getLinkProperties(network)?.let { value -> links[network] = value }
                }
            }
            return ObservedPhysicalNetworks(capabilities.keys.toSet(), capabilities, links)
        }
        override fun register(includeLocation: Boolean, events: PhysicalNetworkEvents<Network, NetworkCapabilities, LinkProperties>): AutoCloseable {
            val callback = object : ConnectivityManager.NetworkCallback(if (includeLocation) FLAG_INCLUDE_LOCATION_INFO else 0) {
                override fun onAvailable(network: Network) = events.available(network)
                override fun onLost(network: Network) = events.lost(network)
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = events.capabilities(network, NetworkCapabilities(capabilities))
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = events.links(network, properties)
            }
            connectivity.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper()))
            return AutoCloseable { connectivity.unregisterNetworkCallback(callback) }
        }
    }, ::eligible, { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }, { network, links ->
        val name = links.interfaceName
        if (name.isNullOrBlank() || links.dnsServers.isEmpty()) null else
            "${network.networkHandle.toULong()}:$name:${links.dnsServers.mapNotNull { it.hostAddress }.distinct()}"
    }, { all -> all.asSequence().flatMap { it.linkAddresses.asSequence() }.map { it.address }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress }, onRevision)

    val snapshots: StateFlow<PhysicalNetworkSnapshot> get() = owner.snapshots
    val revision: StateFlow<Long> get() = owner.revision
    fun acquire(includeLocation: Boolean = false): PhysicalNetworkLease = owner.acquire(includeLocation)
    fun refresh() = owner.refresh()

    private fun eligible(capabilities: NetworkCapabilities) = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
}
