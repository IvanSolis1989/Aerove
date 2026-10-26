package dev.aerove.lite.platform

import android.net.LinkProperties
import android.net.Network
import dev.aerove.lite.data.SystemDnsSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal class LocalNetworkMonitor(private val physical: PhysicalNetworkMonitor,
    private val failure: (Exception) -> Unit = {}) {
    fun addresses(): Flow<String?> = leasedNetworkAddresses({ physical.acquire(false) },
        physical.snapshots.map { it.address }, failure)
}

internal fun leasedNetworkAddresses(acquire: () -> AutoCloseable, values: Flow<String?>,
    failure: (Exception) -> Unit = {}): Flow<String?> = flow {
    val lease = acquire()
    try { values.distinctUntilChanged().collect { emit(it) } }
    finally { try { lease.close() } catch (error: Exception) { failure(error) } }
}

/** The same physical-network callback supplies resolver inputs; it never invents a fallback server. */
internal fun systemDnsSnapshot(preferred: Network?, properties: Map<Network, LinkProperties>): SystemDnsSnapshot {
    val interfaces = properties.values.mapNotNull { links ->
        links.interfaceName?.takeIf(String::isNotBlank)?.let { name -> name to links.dnsServers.mapNotNull { it.hostAddress }.distinct() }
    }.toMap()
    val selected = preferred?.let(properties::get)?.interfaceName?.takeIf { it in interfaces }
    return SystemDnsSnapshot(selected, interfaces)
}

internal fun <T> orderLocalNetworks(networks: Set<T>, preferred: T?, wifi: (T) -> Boolean): List<T> =
    networks.sortedBy { network -> when { network == preferred -> 0; wifi(network) -> 1; else -> 2 } }
