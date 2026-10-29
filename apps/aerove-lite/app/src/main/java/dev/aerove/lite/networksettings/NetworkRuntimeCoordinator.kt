package dev.aerove.lite.networksettings

import dev.aerove.lite.vpn.CoreEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NetworkRuntimeStatus(
    val endpoint: CoreEndpoint,
    val appliedNetwork: NetworkSettings,
    val applying: Boolean,
    val error: String? = null,
    val appliedIpv6: Boolean? = null,
    val request: NetworkRuntimeRequest? = null,
)

enum class NetworkRuntimeOutcome { Applying, Applied, Failed }

/** Identifies the actor's adopted target even when StateFlow conflates its applying frame. */
data class NetworkRuntimeRequest(val id: Long, val network: NetworkSettings, val ipv6: Boolean,
    val outcome: NetworkRuntimeOutcome)

/** The Service owns this actor; saves conflate, but an accepted apply is awaited before its successor. */
class NetworkRuntimeCoordinator(
    parent: CoroutineScope,
    private val apply: suspend (CoreEndpoint, NetworkSettings, Boolean) -> Unit,
    private val onStatus: (NetworkRuntimeStatus) -> Unit = {},
) {
    private class Binding(val endpoint: CoreEndpoint, val startup: Desired, var applied: Desired = startup) {
        var failed: InboundKey? = null
        var failedError: String? = null
        var parked = false
    }
    private data class InboundKey(val ports: UserProxyPorts, val allowLan: Boolean, val ipv6: Boolean,
        val credentials: List<String>) {
        override fun toString(): String = "InboundKey(ports=$ports, allowLan=$allowLan, ipv6=$ipv6, credentials=<redacted>)"
    }
    private data class Desired(val network: NetworkSettings, val ipv6: Boolean, val requestId: Long = 0)
    private class Active(val binding: Binding, val desired: Desired, val fields: InboundKey, val task: Deferred<Unit>)
    private val lock = Any()
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var binding: Binding? = null
    private var desired: Desired? = null
    private var active: Active? = null
    private var closed = false
    private var requestSequence = 0L
    private val worker = scope.launch {
        for (ignored in signals) {
            while (isActive) {
                val work = synchronized(lock) { next() } ?: break
                var failure: Exception? = null
                try {
                    work.task.start()
                    work.task.await()
                } catch (cancelled: CancellationException) {
                    if (!scope.isActive) throw cancelled
                    synchronized(lock) { if (!closed && binding === work.binding) failure = cancelled }
                } catch (error: Exception) {
                    failure = error
                } finally {
                    synchronized(lock) {
                        if (active === work) active = null
                        if (!closed && scope.isActive && binding === work.binding && (failure != null || !work.task.isCancelled)) {
                            if (failure == null) {
                                work.binding.applied = work.desired
                                work.binding.failed = null
                                work.binding.failedError = null
                            } else {
                                work.binding.failed = work.fields
                                work.binding.failedError = failure?.let { it.message ?: it.javaClass.simpleName }
                            }
                            val requested = desired?.let(::fields)
                            val pending = requested != null && requested != fields(work.binding.applied) &&
                                requested != work.binding.failed
                            publish(work.binding, pending, work.desired,
                                if (failure == null) NetworkRuntimeOutcome.Applied else NetworkRuntimeOutcome.Failed,
                                work.binding.failedError?.takeIf { requested == work.fields })
                        }
                    }
                }
            }
        }
    }

    fun bind(endpoint: CoreEndpoint?, initialNetwork: NetworkSettings? = null, initialIpv6: Boolean? = null,
        appliedNetwork: NetworkSettings? = null, appliedIpv6: Boolean? = null, initiallyParked: Boolean = false) {
        val cancelled = synchronized(lock) {
            if (closed || (binding?.endpoint === endpoint && endpoint != null)) return
            binding = if (endpoint != null && initialNetwork != null && initialIpv6 != null)
                Binding(endpoint, Desired(initialNetwork.snapshot().requireValid(), initialIpv6),
                    Desired((appliedNetwork ?: initialNetwork).snapshot().requireValid(), appliedIpv6 ?: initialIpv6)).also { it.parked = initiallyParked } else null
            signals.trySend(Unit)
            active?.task
        }
        cancelled?.cancel()
    }

    fun settings(network: NetworkSettings, ipv6: Boolean) {
        val snapshot = Desired(network.snapshot().requireValid(), ipv6)
        synchronized(lock) {
            if (closed) return
            check(requestSequence < Long.MAX_VALUE)
            desired = snapshot.copy(requestId = ++requestSequence)
            signals.trySend(Unit)
        }
    }

    fun retry() = synchronized(lock) {
        if (!closed) {
            binding?.failed = null
            binding?.failedError = null
            signals.trySend(Unit)
        }
    }

    /** Excludes new POSTs and waits for the already accepted POST+GET transaction before Native park. */
    suspend fun parkBarrier(endpoint: CoreEndpoint) {
        val accepted = synchronized(lock) {
            val current = binding?.takeIf { it.endpoint === endpoint } ?: return
            current.parked = true
            active?.takeIf { it.binding === current }?.task
        }
        try { accepted?.await() } catch (cancelled: CancellationException) {
            if (synchronized(lock) { binding?.endpoint === endpoint }) throw cancelled
        } catch (_: Exception) {
        }
    }

    fun resume(endpoint: CoreEndpoint) = synchronized(lock) {
        binding?.takeIf { it.endpoint === endpoint }?.let {
            it.parked = false
            signals.trySend(Unit)
        }
    }

    suspend fun detachAndJoin(endpoint: CoreEndpoint) {
        val task = synchronized(lock) {
            if (binding?.endpoint === endpoint) binding = null
            active?.takeIf { it.binding.endpoint === endpoint }?.task
        }
        task?.cancelAndJoin()
    }

    suspend fun close() {
        val task = synchronized(lock) {
            closed = true
            binding = null
            signals.close()
            active?.task
        }
        task?.cancelAndJoin()
        worker.cancelAndJoin()
        scope.cancel()
    }

    private fun next(): Active? {
        if (closed || active != null) return null
        val current = binding ?: return null
        if (current.parked) return null
        val target = desired ?: return null
        val fields = fields(target)
        if (fields == fields(current.applied)) {
            publish(current, false, target, NetworkRuntimeOutcome.Applied)
            return null
        }
        if (fields == current.failed) {
            publish(current, false, target, NetworkRuntimeOutcome.Failed, current.failedError)
            return null
        }
        val task = scope.async(start = CoroutineStart.LAZY) { apply(current.endpoint, target.network, target.ipv6) }
        return Active(current, target, fields, task).also {
            active = it
            publish(current, true, target, NetworkRuntimeOutcome.Applying)
        }
    }

    private fun fields(value: Desired): InboundKey = InboundKey(value.network.ports, value.network.allowLan,
        value.ipv6 && value.network.allowLan, value.network.authentication.credentials)

    private fun publish(binding: Binding, applying: Boolean, target: Desired, outcome: NetworkRuntimeOutcome,
        error: String? = null) {
        val applied = binding.applied.network.copy(systemProxy = binding.startup.network.systemProxy,
            allowBypass = binding.startup.network.allowBypass, bypassDomains = binding.startup.network.bypassDomains.toList())
        onStatus(NetworkRuntimeStatus(binding.endpoint, applied, applying, error, binding.applied.ipv6,
            NetworkRuntimeRequest(target.requestId, target.network, target.ipv6, outcome)))
    }
}
