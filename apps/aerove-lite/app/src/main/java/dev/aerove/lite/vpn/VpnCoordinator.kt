package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.AppliedConfigSnapshot
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

data class VpnPlan(val profileId: String, val settings: LiteSettings)
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

class VpnCoordinator(scope: CoroutineScope, private val backend: TunnelBackend) {
    private data class Request(val generation: Long, val plan: VpnPlan?)
    private val generation = AtomicLong()
    private val requests = MutableStateFlow(Request(0, null))
    private val mutableState = MutableStateFlow(VpnSnapshot())
    val state = mutableState.asStateFlow()
    private var unconfirmedStop: Throwable? = null
    private val worker = scope.launch {
        requests.collectLatest { request ->
            var session: TunnelSession? = null
            try {
                unconfirmedStop?.let { throw IllegalStateException("Previous core termination was not confirmed", it) }
                if (request.plan == null) {
                    publish(request, VpnSnapshot())
                    return@collectLatest
                }
                publish(request, VpnSnapshot(VpnPhase.Starting))
                withContext(NonCancellable) {
                    session = backend.open(request.plan)
                }
                val running = checkNotNull(session)
                running.awaitReady()
                publish(request, VpnSnapshot(VpnPhase.Running, running.endpoint, runningSinceNanos = System.nanoTime()))
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
                            owned.stop()
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
        val next = generation.incrementAndGet()
        mutableState.value = VpnSnapshot(VpnPhase.Starting)
        requests.value = Request(next, plan)
    }

    @Synchronized
    fun stop() {
        val next = generation.incrementAndGet()
        if (mutableState.value.phase != VpnPhase.Stopped) mutableState.value = VpnSnapshot(VpnPhase.Stopping)
        requests.value = Request(next, null)
    }

    @Synchronized
    fun reconnect() {
        val plan = requests.value.plan ?: return
        start(plan)
    }

    suspend fun close() {
        worker.cancelAndJoin()
    }

    @Synchronized
    private fun publish(request: Request, snapshot: VpnSnapshot) {
        if (request.generation == generation.get()) mutableState.value = snapshot
    }
}
