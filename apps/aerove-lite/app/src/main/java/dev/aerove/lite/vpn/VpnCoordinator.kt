package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.AppliedConfigSnapshot
import dev.aerove.lite.networksettings.NetworkRuntimeStatus
import dev.aerove.lite.networksettings.NetworkSettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VpnPlan(val profileId: String, val settings: LiteSettings,
    val vpnNetwork: NetworkSettings? = null, val vpnIpv6: Boolean? = null)
class CoreEndpoint @JvmOverloads constructor(
    val port: Int,
    val secret: String,
    val configuration: AppliedConfigSnapshot? = null,
) {
    override fun toString(): String = "CoreEndpoint(127.0.0.1:$port, secret=<redacted>)"
}
data class VpnSnapshot(
    val phase: VpnPhase = VpnPhase.Stopped,
    val endpoint: CoreEndpoint? = null,
    val error: String? = null,
    val runningSinceNanos: Long = 0,
    val initialNetwork: NetworkSettings? = null,
    val initialIpv6: Boolean? = null,
    val appliedNetwork: NetworkSettings? = null,
    val appliedIpv6: Boolean? = null,
    val networkApplying: Boolean = false,
    val networkError: String? = null,
)

interface TunnelSession {
    val endpoint: CoreEndpoint
    suspend fun awaitReady()
    suspend fun awaitExit()
    suspend fun stop()
}

fun interface TunnelBackend {
    suspend fun open(plan: VpnPlan): TunnelSession
}

internal suspend fun stopNativeSession(join: () -> Unit, cleanup: suspend () -> Unit, warn: (Exception) -> Unit) {
    join()
    try {
        cleanup()
    } catch (failure: Exception) {
        warn(failure)
    }
}

class VpnCoordinator(scope: CoroutineScope, private val backend: TunnelBackend,
    private val beforeSessionStop: suspend (CoreEndpoint) -> Unit = {},
    private val latestSavedSettings: () -> LiteSettings? = { null },
) {
    private data class Request(val generation: Long, val plan: VpnPlan?, val internalReconnect: Boolean = false)
    private val generation = AtomicLong()
    private val requests = MutableStateFlow(Request(0, null))
    private val mutableState = MutableStateFlow(VpnSnapshot())
    val state = mutableState.asStateFlow()
    private var unconfirmedStop: Throwable? = null
    private var closed = false
    private val worker = scope.launch {
        requests.collectLatest { request ->
            var session: TunnelSession? = null
            try {
                if (request.generation != generation.get()) return@collectLatest
                unconfirmedStop?.let { throw IllegalStateException("Previous core termination was not confirmed", it) }
                if (request.plan == null) {
                    publish(request, VpnSnapshot())
                    return@collectLatest
                }
                publish(request, VpnSnapshot(VpnPhase.Starting))
                val requestedPlan = checkNotNull(request.plan)
                var openedPlan = requestedPlan
                withContext(NonCancellable) {
                    // Read after the previous session's join barrier, immediately before opening its successor.
                    openedPlan = if (request.internalReconnect) reconnectPlan(requestedPlan) else requestedPlan
                    session = backend.open(openedPlan)
                }
                val running = checkNotNull(session)
                running.awaitReady()
                val startupNetwork = (openedPlan.vpnNetwork ?: openedPlan.settings.network).snapshot()
                publish(request, VpnSnapshot(VpnPhase.Running, running.endpoint, runningSinceNanos = System.nanoTime(),
                    initialNetwork = startupNetwork, initialIpv6 = openedPlan.vpnIpv6 ?: openedPlan.settings.ipv6,
                    appliedNetwork = openedPlan.settings.network.snapshot(), appliedIpv6 = openedPlan.settings.ipv6))
                running.awaitExit()
                error("The core exited while the VPN was requested")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publish(request, VpnSnapshot(VpnPhase.Failed, error = failure.message ?: failure.javaClass.simpleName))
            } finally {
                session?.let { owned ->
                    withContext(NonCancellable) {
                        try {
                            try {
                                beforeSessionStop(owned.endpoint)
                            } finally {
                                owned.stop()
                            }
                        } catch (failure: Exception) {
                            unconfirmedStop = failure
                            mutableState.value = VpnSnapshot(VpnPhase.Failed, error = failure.message)
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun start(plan: VpnPlan) {
        if (closed) return
        val next = generation.incrementAndGet()
        mutableState.value = VpnSnapshot(VpnPhase.Starting)
        requests.value = Request(next, plan)
    }

    @Synchronized
    fun stop() {
        if (closed) return
        val next = generation.incrementAndGet()
        if (mutableState.value.phase != VpnPhase.Stopped) mutableState.value = VpnSnapshot(VpnPhase.Stopping)
        requests.value = Request(next, null)
    }

    @Synchronized
    fun reconnect() {
        if (closed) return
        val plan = requests.value.plan ?: return
        val current = mutableState.value
        val baseline = plan.copy(vpnNetwork = current.initialNetwork ?: plan.vpnNetwork ?: plan.settings.network.snapshot(),
            vpnIpv6 = current.initialIpv6 ?: plan.vpnIpv6 ?: plan.settings.ipv6)
        val next = generation.incrementAndGet()
        mutableState.value = VpnSnapshot(VpnPhase.Starting)
        requests.value = Request(next, baseline, internalReconnect = true)
    }

    suspend fun close() {
        synchronized(this) {
            closed = true
            generation.incrementAndGet()
            if (mutableState.value.phase !in setOf(VpnPhase.Stopped, VpnPhase.Failed))
                mutableState.value = VpnSnapshot(VpnPhase.Stopping)
        }
        worker.cancelAndJoin()
        synchronized(this) {
            if (mutableState.value.phase != VpnPhase.Failed) mutableState.value = VpnSnapshot()
        }
    }

    @Synchronized
    fun recordNetworkStatus(status: NetworkRuntimeStatus) {
        val current = mutableState.value
        if (!closed && current.phase == VpnPhase.Running && current.endpoint === status.endpoint) {
            mutableState.value = current.copy(appliedNetwork = status.appliedNetwork.snapshot(),
                appliedIpv6 = status.appliedIpv6 ?: current.appliedIpv6,
                networkApplying = status.applying, networkError = status.error)
        }
    }

    @Synchronized
    fun recordResourceError(endpoint: CoreEndpoint, message: String) {
        val current = mutableState.value
        if (!closed && current.phase == VpnPhase.Running && current.endpoint === endpoint)
            mutableState.value = current.copy(error = message)
    }

    @Synchronized
    private fun publish(request: Request, snapshot: VpnSnapshot) {
        if (request.generation == generation.get()) mutableState.value = snapshot
    }

    private fun reconnectPlan(plan: VpnPlan): VpnPlan {
        val saved = latestSavedSettings() ?: return plan
        return plan.copy(settings = plan.settings.copy(network = saved.network.snapshot(), ipv6 = saved.ipv6))
    }
}
