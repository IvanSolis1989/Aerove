package dev.aerove.lite.platformqa

import android.content.Context
import android.net.ConnectivityManager
import android.net.InetAddresses
import android.net.NetworkCapabilities
import android.net.RouteInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal data class CoreStamp(val owner: Long, val generation: Long, val coreEpoch: ULong, val revision: String)

internal data class VpnRoute(val destination: String, val bits: Int, val type: String)
internal data class VpnNetwork(val handle: Long, val uid: Int, val matching: List<VpnRoute>)
internal data class CoreReadback(val stamp: CoreStamp, val findProcess: Boolean, val bypassPrivate: Boolean, val filter: String)
internal data class StoppedReadback(val phase: String, val endpointGone: Boolean, val listening: Boolean)

internal class AndroidPhysicalVpnReader(context: Context) {
    private val connectivity = checkNotNull(context.getSystemService(ConnectivityManager::class.java))

    // Foreground, dedicated-lab runner only. Do not assert absence across unrelated Android users.
    @Suppress("DEPRECATION")
    fun visibleVpn(host: String): List<VpnNetwork> {
        val address = InetAddresses.parseNumericAddress(host)
        return connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) return@mapNotNull null
            val links = checkNotNull(connectivity.getLinkProperties(network)) { "VPN changed during readback" }
            check(connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == caps.ownerUid
            } == true) { "VPN owner changed during readback" }
            VpnNetwork(network.networkHandle, caps.ownerUid, links.routes.filter { it.matches(address) }.map {
                VpnRoute(it.destination.toString(), it.destination.prefixLength, when (it.type) {
                    RouteInfo.RTN_UNICAST -> "unicast"
                    RouteInfo.RTN_THROW -> "throw"
                    RouteInfo.RTN_UNREACHABLE -> "unreachable"
                    else -> "unknown"
                })
            }.sortedWith(compareBy({ it.destination }, { it.type })))
        }
    }
}

internal class PhysicalVpnScenario(
    private val appUid: Int,
    private val vpn: (String) -> List<VpnNetwork>,
    private val startFromUi: suspend () -> Unit,
    private val stopFromUiAndJoin: suspend () -> StoppedReadback,
    private val awaitRunningCore: suspend () -> CoreReadback,
    private val currentCore: suspend () -> CoreReadback,
    private val snapshotForCore: suspend (CoreStamp) -> String,
    private val sample: suspend (PhysicalSocketDriver.Challenge, Boolean, (suspend () -> String)?) -> PhysicalSocketDriver.Capture
) {
    data class Result(val records: JSONArray, val nativeJson: String)

    suspend fun run(challenges: List<PhysicalSocketDriver.Challenge>): Result {
        require(challenges.map { it.stage } == listOf("off-before", "on", "off-after"))
        val first = challenges.first()
        require(challenges.all { it.host == first.host && it.port == first.port } &&
            challenges.map { it.nonce }.distinct().size == 3)
        val checkOff = { check(vpn(first.host).isEmpty()) { "Unexpected visible VPN in off phase" } }
        checkOff()
        val before = sample(first, false, null)
        checkOff()
        var stopRequired = true
        var failure: Throwable? = null
        val on: PhysicalSocketDriver.Capture
        try {
            withTimeout(35_000) { startFromUi() }
            val runtime = withTimeout(20_000) { awaitRunningCore() }
            val network = requireActive(first.host, runtime)
            on = sample(challenges[1], runtime.findProcess) {
                check(currentCore() == runtime && requireActive(first.host, runtime) == network) {
                    "Core or Android VPN replaced before Native readback"
                }
                val data = snapshotForCore(runtime.stamp)
                check(currentCore() == runtime && requireActive(first.host, runtime) == network) {
                    "Core or Android VPN replaced during Native readback"
                }
                data
            }
            check(currentCore() == runtime && requireActive(first.host, runtime) == network)
            on.row.put("vpnHandles", JSONArray().put(network.handle)).put("vpnOwnerUid", network.uid)
                .put("phase", "Running").put("privateRouteBypass", runtime.bypassPrivate)
                .put("appFilterMode", runtime.filter).put("routes", JSONArray(network.matching.map {
                    JSONObject().put("destination", it.destination).put("type", it.type)
                }))
            withContext(NonCancellable) { requireStopped(withTimeout(25_000) { stopFromUiAndJoin() }) }
            stopRequired = false
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            if (stopRequired) {
                try {
                    withContext(NonCancellable) { requireStopped(withTimeout(25_000) { stopFromUiAndJoin() }); checkOff() }
                } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
        checkOff()
        val after = sample(challenges[2], false, null)
        checkOff()
        val records = listOf(before.row.put("vpnHandles", JSONArray()), on.row,
            after.row.put("vpnHandles", JSONArray()).put("stopJoined", true))
        check(before.nativeJson == null && after.nativeJson == null)
        check(records.zipWithNext().all { (a, b) -> a.getLong("endNs") < b.getLong("startNs") })
        return Result(JSONArray(records), checkNotNull(on.nativeJson))
    }

    private fun requireActive(host: String, core: CoreReadback): VpnNetwork {
        check(core.stamp.owner > 0 && core.stamp.generation > 0 && core.stamp.revision.isNotBlank() && core.stamp.coreEpoch > 0uL)
        check(core.findProcess && !core.bypassPrivate && core.filter == "All") {
            "Find process or VPN app/private-route settings do not match the lab test"
        }
        val network = vpn(host).singleOrNull()
        check(network != null && network.uid == appUid && network.handle > 0) { "No single AEROVE-owned visible VPN" }
        val bits = network.matching.maxOfOrNull { it.bits }
        check(bits != null && network.matching.filter { it.bits == bits }.all { it.type == "unicast" }) {
            "Controlled destination is not covered by a most-specific unicast VPN route"
        }
        return network
    }

    private fun requireStopped(result: StoppedReadback) {
        check(result.phase == "Stopped" && result.endpointGone && !result.listening) {
            "Production joined stop did not reach an idle Core state"
        }
    }
}
