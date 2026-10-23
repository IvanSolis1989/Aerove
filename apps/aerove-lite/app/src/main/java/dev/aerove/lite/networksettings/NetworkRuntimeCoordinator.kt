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
)

/** The Service owns this actor; saves conflate, but an accepted apply is awaited before its successor. */
class NetworkRuntimeCoordinator(
    parent: CoroutineScope,
    private val apply: suspend (CoreEndpoint, NetworkSettings, Boolean) -> Unit,
    private val onStatus: (NetworkRuntimeStatus) -> Unit = {},
) {
    private class Binding(val endpoint: CoreEndpoint, val startup: Desired, var applied: Desired = startup) {
        var failed: InboundKey? = null
        var parked = false
    }
    private data class InboundKey(val ports: UserProxyPorts, val allowLan: Boolean, val ipv6: Boolean,
        val credentials: List<String>) {
        override fun toString(): String = "InboundKey(ports=$ports, allowLan=$allowLan, ipv6=$ipv6, credentials=<redacted>)"
    }
    private data class Desired(val network: NetworkSettings, val ipv6: Boolean)
    private class Active(val binding: Binding, val desired: Desired, val fields: InboundKey, val task: Deferred<Unit>)
    private val lock = Any()
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var binding: Binding? = null
    private var desired: Desired? = null
    private var active: Active? = null
    private var closed = false
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
                            } else work.binding.failed = work.fields
                            val requested = desired?.let(::fields)
                            val pending = requested != null && requested != fields(work.binding.applied) &&
                                requested != work.binding.failed
                            publish(work.binding, pending,
                                failure?.takeIf { requested == work.fields }?.let { it.message ?: it.javaClass.simpleName })
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
            if (closed || desired == snapshot) return
            desired = snapshot
            signals.trySend(Unit)
        }
    }

    fun retry() = synchronized(lock) {
        if (!closed) {
            binding?.failed = null
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
        if (fields == fields(current.applied) || fields == current.failed) return null
        val task = scope.async(start = CoroutineStart.LAZY) { apply(current.endpoint, target.network, target.ipv6) }
        return Active(current, target, fields, task).also {
            active = it
            publish(current, true)
        }
    }

    private fun fields(value: Desired): InboundKey = InboundKey(value.network.ports, value.network.allowLan,
        value.ipv6 && value.network.allowLan, value.network.authentication.credentials)

    private fun publish(binding: Binding, applying: Boolean, error: String? = null) {
        val applied = binding.applied.network.copy(systemProxy = binding.startup.network.systemProxy,
            allowBypass = binding.startup.network.allowBypass, bypassDomains = binding.startup.network.bypassDomains.toList())
        onStatus(NetworkRuntimeStatus(binding.endpoint, applied, applying, error, binding.applied.ipv6))
    }
}
