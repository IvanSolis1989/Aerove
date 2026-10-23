package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.AppliedConfigSnapshot
import dev.aerove.lite.networksettings.NetworkRuntimeStatus
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.effectiveVpnNetworkOptions
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VpnPlan(val profileId: String, val settings: LiteSettings,
    val vpnNetwork: NetworkSettings? = null, val vpnIpv6: Boolean? = null,
    val vpnSettings: LiteSettings? = null, val vpnReservedFakeIpRanges: List<String>? = null,
    val vpnDnsEnabled: Boolean? = null, val ownerIntentToken: Long? = null)
class CoreEndpoint @JvmOverloads constructor(
    val port: Int,
    val secret: String,
    val configuration: AppliedConfigSnapshot? = null,
    val profileRevision: String? = null,
    val resourceGeneration: String? = null,
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
    val initialSettings: LiteSettings? = null,
    val listening: Boolean = false,
    val suspended: Boolean = false,
    val listenerError: String? = null,
    val initialReservedFakeIpRanges: List<String> = emptyList(),
    val appliedReservedFakeIpRanges: List<String> = emptyList(),
    val initialDnsEnabled: Boolean? = null,
    val appliedDnsEnabled: Boolean? = null,
    val plannedBuilderSettings: LiteSettings? = null,
)

interface TunnelSession {
    val endpoint: CoreEndpoint
    suspend fun awaitReady()
    suspend fun awaitExit()
    suspend fun stop()
    val builderReservedFakeIpRanges: List<String> get() = emptyList()
    val coreReservedFakeIpRanges: List<String> get() = emptyList()
    val builderDnsEnabled: Boolean? get() = null
    val coreDnsEnabled: Boolean? get() = null
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
    private val beforeListenerPark: suspend (CoreEndpoint) -> Unit = {},
    private val afterListenerResume: (CoreEndpoint) -> Unit = {},
    private val latestNetworkDns: () -> dev.aerove.lite.data.SystemDnsSnapshot? = { null },
    private val onSessionReady: (VpnSnapshot) -> Unit = {},
) {
    private data class Request(val generation: Long, val plan: VpnPlan?, val internalReconnect: Boolean = false)
    private val generation = AtomicLong()
    private val requests = MutableStateFlow(Request(0, null))
    private val mutableState = MutableStateFlow(VpnSnapshot())
    val state = mutableState.asStateFlow()
    private var unconfirmedStop: Throwable? = null
    private var closed = false
    private data class OwnedSession(val generation: Long, val session: TunnelSession)
    private var activeSession: OwnedSession? = null
    private val policyLock = Mutex()
    @Volatile private var desiredListening = true
    @Volatile private var desiredPolicyCurrent: () -> Boolean = { true }
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
                if (running is ListenerPolicySession) latestSavedSettings()?.let { running.configurePolicies(it) }
                if (running is ListenerPolicySession) latestNetworkDns()?.let { running.configureNetworkDns(it) }
                synchronized(this@VpnCoordinator) {
                    if (request.generation == generation.get()) activeSession = OwnedSession(request.generation, running)
                }
                val builderSettings = openedPlan.builderSettings()
                val initialListener = (running as? ListenerPolicySession)?.initialListenerReadback
                val initiallyParked = initialListener?.listening == false
                val actualBuilder = builderSettings.takeUnless { initiallyParked }
                val startupNetwork = actualBuilder?.network?.snapshot()
                val ready = VpnSnapshot(VpnPhase.Running, running.endpoint, runningSinceNanos = System.nanoTime(),
                    initialNetwork = startupNetwork, initialIpv6 = actualBuilder?.ipv6, initialSettings = actualBuilder,
                    plannedBuilderSettings = builderSettings,
                    appliedNetwork = openedPlan.settings.network.snapshot(), appliedIpv6 = openedPlan.settings.ipv6,
                    listening = !initiallyParked, suspended = initiallyParked,
                    initialReservedFakeIpRanges = if (initiallyParked) emptyList() else running.builderReservedFakeIpRanges.toList(),
                    appliedReservedFakeIpRanges = running.coreReservedFakeIpRanges.toList(),
                    initialDnsEnabled = running.builderDnsEnabled.takeUnless { initiallyParked }, appliedDnsEnabled = running.coreDnsEnabled)
                if (request.generation == generation.get()) onSessionReady(ready)
                publish(request, ready)
                if (desiredListening != ready.listening) convergeListenerPolicy { request.generation == generation.get() }
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
                        synchronized(this@VpnCoordinator) {
                            if (activeSession?.session === owned) activeSession = null
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

    /** Stopped is published only after the previous session's stop/join barrier has completed. */
    suspend fun stopAndJoin() {
        stop()
        val stopped = state.first { it.phase == VpnPhase.Stopped || it.phase == VpnPhase.Failed }
        check(stopped.phase == VpnPhase.Stopped) { stopped.error ?: "Runtime termination was not confirmed" }
    }

    @Synchronized
    fun reconnect() {
        if (closed) return
        val plan = requests.value.plan ?: return
        val current = mutableState.value
        val baseline = preserveBuilder(plan, current)
        val next = generation.incrementAndGet()
        mutableState.value = VpnSnapshot(VpnPhase.Starting)
        requests.value = Request(next, baseline, internalReconnect = true)
    }

    /** Refresh Core configuration without accepting pending Android Builder or mode changes. */
    @Synchronized
    fun reconfigure(plan: VpnPlan) {
        if (closed || requests.value.plan == null) return
        val currentPlan = checkNotNull(requests.value.plan)
        val preserved = preserveBuilder(currentPlan, mutableState.value)
        val baseline = preserved.builderSettings()
        val next = generation.incrementAndGet()
        mutableState.value = VpnSnapshot(VpnPhase.Starting)
        requests.value = Request(next, plan.copy(vpnNetwork = baseline.network, vpnIpv6 = baseline.ipv6, vpnSettings = baseline,
            vpnReservedFakeIpRanges = preserved.vpnReservedFakeIpRanges, vpnDnsEnabled = preserved.vpnDnsEnabled))
    }

    private fun preserveBuilder(plan: VpnPlan, current: VpnSnapshot): VpnPlan {
        val baseline = current.initialSettings ?: plan.builderSettings()
        return plan.copy(vpnNetwork = current.initialNetwork ?: baseline.network.snapshot(),
            vpnIpv6 = current.initialIpv6 ?: baseline.ipv6, vpnSettings = baseline,
            vpnReservedFakeIpRanges = if (current.initialSettings != null) current.initialReservedFakeIpRanges.toList()
                else plan.vpnReservedFakeIpRanges?.toList(),
            vpnDnsEnabled = current.initialDnsEnabled ?: plan.vpnDnsEnabled)
    }

    @Synchronized
    fun requestedBuilderSettings(): LiteSettings? = requests.value.plan?.builderSettings()

    @Synchronized
    fun maintenanceContext(userRequested: Boolean): MaintenanceRuntimeContext {
        val plan = requests.value.plan?.let { preserveBuilder(it, mutableState.value) }
        val baseline = mutableState.value.initialSettings
        return MaintenanceRuntimeContext(userRequested, plan, baseline)
    }

    suspend fun setListenerPolicy(listening: Boolean, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        desiredListening = listening
        desiredPolicyCurrent = isCurrent
        convergeListenerPolicy(isCurrent)
    }

    private suspend fun convergeListenerPolicy(isCurrent: () -> Boolean) {
        policyLock.withLock {
            val listening = desiredListening
            val owned = synchronized(this) { activeSession } ?: return
            fun current() = !closed && owned.generation == generation.get() && isCurrent() && desiredPolicyCurrent()
            if (!current()) return
            val prior = state.value
            if (prior.listening == listening && prior.listenerError == null) return
            val session = owned.session as? ListenerPolicySession ?: error("Session does not implement listener policy")
            try {
                if (!listening) beforeListenerPark(session.endpoint)
                if (!current()) {
                    if (!closed && owned.generation == generation.get() && !listening) afterListenerResume(session.endpoint)
                    return
                }
                val actual = session.setListening(listening)
                check(actual.listening == listening && actual.userListenersReady == listening) { "Core listener state did not converge" }
                val tun = requestedBuilderSettings()?.network?.vpnEnabled == true
                check(actual.tunReady == (tun && listening)) { "Core TUN state did not converge" }
                if (closed || owned.generation != generation.get()) return
                synchronized(this) {
                    val snapshot = mutableState.value
                    if (snapshot.endpoint === session.endpoint && snapshot.phase == VpnPhase.Running)
                        mutableState.value = snapshot.copy(listening = actual.listening, suspended = !actual.listening, listenerError = null,
                            initialSettings = snapshot.initialSettings ?: session.establishedBuilderSettings,
                            initialNetwork = snapshot.initialNetwork ?: session.establishedBuilderSettings?.network?.snapshot(),
                            initialIpv6 = snapshot.initialIpv6 ?: session.establishedBuilderSettings?.ipv6,
                            initialDnsEnabled = snapshot.initialDnsEnabled ?: session.builderDnsEnabled.takeIf { actual.listening },
                            initialReservedFakeIpRanges = if (snapshot.initialSettings == null && session.establishedBuilderSettings != null)
                                session.builderReservedFakeIpRanges.toList() else snapshot.initialReservedFakeIpRanges)
                }
                if (listening) afterListenerResume(session.endpoint)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current()) synchronized(this) {
                    mutableState.value = mutableState.value.copy(listenerError = failure.message ?: failure.javaClass.simpleName)
                }
                throw failure
            }
        }
    }

    suspend fun configurePolicies(settings: LiteSettings) = policyLock.withLock {
        val owned = synchronized(this) { activeSession } ?: return@withLock
        if (closed || owned.generation != generation.get()) return@withLock
        (owned.session as? ListenerPolicySession ?: error("Session does not implement runtime policy")).configurePolicies(settings)
    }

    suspend fun configureNetworkDns(snapshot: dev.aerove.lite.data.SystemDnsSnapshot) = policyLock.withLock {
        val owned = synchronized(this) { activeSession } ?: return@withLock
        if (closed || owned.generation != generation.get()) return@withLock
        (owned.session as? ListenerPolicySession ?: error("Session does not implement network DNS")).configureNetworkDns(snapshot)
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
    fun recordListenerError(message: String) {
        if (!closed && mutableState.value.phase == VpnPhase.Running)
            mutableState.value = mutableState.value.copy(listenerError = message)
    }

    @Synchronized
    private fun publish(request: Request, snapshot: VpnSnapshot) {
        if (request.generation == generation.get()) mutableState.value = snapshot
    }

    private fun reconnectPlan(plan: VpnPlan): VpnPlan {
        val saved = latestSavedSettings() ?: return plan
        return plan.copy(settings = plan.settings.copy(network = saved.network.snapshot(), ipv6 = saved.ipv6,
            mode = saved.mode, requests = saved.requests, backgroundPolicy = saved.backgroundPolicy, unifiedDelay = saved.unifiedDelay, tcpConcurrent = saved.tcpConcurrent,
            externalController = saved.externalController, findProcessMode = saved.findProcessMode))
    }
}

/** A pending Core configuration must never become an implicit Android policy change. */
internal fun VpnPlan.builderSettings(): LiteSettings {
    val baseline = vpnSettings ?: settings
    return baseline.copy(network = (vpnNetwork ?: baseline.network).snapshot(),
        ipv6 = vpnIpv6 ?: baseline.ipv6, packages = baseline.packages.toSet())
}

/** Installed Builder policy, or a parked Core's fixed first-attach plan, changes only after an explicit restart. */
fun requiresVpnRestart(desired: LiteSettings, snapshot: VpnSnapshot): Boolean {
    val actual = snapshot.initialSettings ?: snapshot.plannedBuilderSettings ?: return false
    if (actual.network.vpnEnabled != desired.network.vpnEnabled) return true
    if (!actual.network.vpnEnabled) return false
    if (snapshot.initialDnsEnabled != null && snapshot.appliedDnsEnabled != null &&
        snapshot.initialDnsEnabled != snapshot.appliedDnsEnabled) return true
    return effectiveVpnNetworkOptions(actual.network, actual.ipv6,
        if (snapshot.initialSettings == null) snapshot.appliedReservedFakeIpRanges else snapshot.initialReservedFakeIpRanges) !=
        effectiveVpnNetworkOptions(desired.network, desired.ipv6, snapshot.appliedReservedFakeIpRanges) ||
        actual.ipv6 != desired.ipv6 || actual.appFilterMode != desired.appFilterMode ||
        (actual.appFilterMode != dev.aerove.lite.AppFilterMode.All && actual.packages.toSet() != desired.packages.toSet())
}

internal fun canStartRuntime(vpnEnabled: Boolean, systemVpnRequest: Boolean = false,
    isVpnAuthorized: () -> Boolean): Boolean =
    (!systemVpnRequest || vpnEnabled) && (!vpnEnabled || isVpnAuthorized())
