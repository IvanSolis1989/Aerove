package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.vpn.MeowTunnelBackend
import dev.aerove.lite.vpn.VpnPlan
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun meowParkedEffectiveSettings(desired: dev.aerove.lite.LiteSettings) = desired.copy(
    externalController = desired.externalController.copy(enabled = false, secret = ""))

internal class MeowParkedGraphOwner(private val store: RuntimeHandoffStore,
    private val claimOwner: () -> Long, private val ownsOwner: (Long) -> Boolean,
    private val backendFactory: (Long, String, () -> Boolean) -> MeowTunnelBackend,
    private val publish: (Long, MeowQueryGraph?) -> Unit) : MeowFlutterRuntime {
    private val gate = Mutex()
    private val publications = Any()
    private val intent = AtomicLong()
    @Volatile private var graph: MeowQueryGraph? = null
    private var backend: MeowTunnelBackend? = null
    private data class HeldSession(val intent: Long, val session: MeowTunnelBackend.Session)
    @Volatile private var held: HeldSession? = null
    private val session get() = held?.session
    private var generation = 0L
    private var ownerToken = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exitJob: Job? = null
    private var defaultUrl = MEOW_DEFAULT_TEST_URL

    override fun queryGraph(): MeowQueryGraph? = graph?.takeIf { identityIsCurrent(it.identity) }
    override fun identityIsCurrent(identity: RuntimeAppliedIdentity): Boolean = graph?.let {
        it.identity == identity && ownsOwner(identity.proof.serviceOwner) &&
            store.isGraphCandidate(identity.revision, identity.intentEpoch)
    } == true

    private fun newIntent(): Long = synchronized(publications) {
        val requested = intent.incrementAndGet()
        graph = null
        publish(ownerToken, null)
        requested
    }

    suspend fun retire() {
        val requested = newIntent()
        held?.takeIf { it.intent < requested }?.session?.stop()
        gate.withLock { if (intent.get() == requested) release() }
    }

    private suspend fun release() = withContext(NonCancellable + Dispatchers.IO) {
        graph = null
        publish(ownerToken, null)
        backend?.stopAndJoinRetained()
        exitJob?.cancelAndJoin()
        exitJob = null
        held = null
        backend = null
    }

    override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot): RuntimeAppliedIdentity {
        val requested = newIntent()
        return gate.withLock {
            release()
            val candidate = store.admitGraph(snapshot.revision, snapshot.intentEpoch)
            val owner = claimOwner()
            ownerToken = owner
            val settings = MeowRuntimeSnapshotSettings.decode(candidate.handoff.runtimeSettingsJson)
            val effective = meowParkedEffectiveSettings(settings)
            val runtime = JSONObject(candidate.handoff.runtimeSettingsJson)
            val setup = MeowServiceSetup(runtime.getJSONObject("flutterSetup").toString(),
                runtime.optString("flutterDefaultTestUrl", defaultUrl))
            check(settings.requests.checkCertificate == !setup.skipCertVerify) { "skip-cert-verify: Graph policy differs from compiled setup" }
            fun current() = requested == intent.get() && ownsOwner(owner) &&
                store.isGraphCandidate(candidate.revision, candidate.intentEpoch)
            check(current()) { "runtime_graph_superseded" }
            val owned = backendFactory(owner, setup.testUrl, ::current).also { backend = it }
            try {
                val proxySettings = effective.copy(network = effective.network.copy(vpnEnabled = false))
                val running = owned.open(VpnPlan(candidate.profileId, effective, vpnSettings = proxySettings,
                    ownerIntentToken = requested)).also { held = HeldSession(requested, it) }
                running.awaitReady()
                running.configurePolicies(effective)
                val endpoint = running.endpoint
                val epoch = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
                    client.setMode(settings.mode)
                    check(client.mode() == settings.mode) { "mode: Graph policy did not converge" }
                    MeowRuntimeSelection(client).apply(setup.selectedMap, ::current)
                    client.routeSnapshot().coreEpoch.toString()
                }
                val listeners = running.readGraphReadiness()
                val policy = running.readGraphPolicy()
                check(epoch == listeners.coreEpoch && policy.coreEpoch == epoch) { "Meow graph HTTP and native epochs differ" }
                check(!policy.controller.enabled && policy.controller.address == null && !policy.controller.authenticated) {
                    "Query graph user controller is not isolated"
                }
                check(!listeners.listening && !listeners.tunReady && !listeners.userListenersReady && listeners.activePorts.isEmpty()) {
                    "Query graph opened public listeners"
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                check(current()) { "runtime_graph_superseded" }
                val proof = RuntimeAppliedProof(owner, ++generation, epoch)
                val projection = meowServiceProjection(candidate.handoff, settings, null, null, null, setup.testUrl, policy.controller)
                store.commitGraphApplied(candidate.revision, candidate.intentEpoch, proof, projection)
                val identity = checkNotNull(store.readAppliedIdentity())
                check(current() && store.isGraphCurrent(identity)) { "runtime_graph_superseded" }
                defaultUrl = setup.testUrl
                synchronized(publications) {
                    check(current()) { "runtime_graph_superseded" }
                    graph = MeowQueryGraph(identity, endpoint, false)
                    publish(ownerToken, graph)
                }
                exitJob = scope.launch {
                    try { running.awaitExit() }
                    finally {
                        gate.withLock {
                            if (session === running) {
                                exitJob = null
                                release()
                            }
                        }
                    }
                }
                identity
            } catch (failure: Throwable) {
                try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private suspend fun requireCurrent(expected: MeowSetupRuntime): MeowQueryGraph {
        val actual = queryGraph() ?: throw MeowProtocolFailure("superseded", "Query graph was retired")
        val identity = actual.identity
        check(identity.revision == expected.revision && identity.intentEpoch == expected.intentEpoch &&
            identity.compiledSha256 == expected.compiledSha256 && identity.proof.coreEpoch == expected.coreEpoch &&
            identity.proof.serviceOwner == expected.serviceOwner && identity.proof.sessionGeneration == expected.sessionGeneration &&
            store.isGraphCurrent(identity)) { "runtime_graph_superseded" }
        return actual
    }

    override suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity = gate.withLock {
        val actual = requireCurrent(expected)
        check(!enabled) { "A private query graph must be replaced through the authorized VPN start owner" }
        actual.identity
    }

    override suspend fun defaultTestUrl(expected: MeowSetupRuntime): String = gate.withLock {
        requireCurrent(expected); defaultUrl
    }

    override suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity = gate.withLock {
        update(expected, projection)
    }

    override suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String): RuntimeAppliedIdentity = gate.withLock {
        val previous = checkNotNull(store.readLastApplied()).handoff
        val settings = MeowRuntimeSnapshotSettings.decode(previous.runtimeSettingsJson)
        val setup = MeowServiceSetup(setupParamsJson, defaultUrl)
        update(expected, MeowSetupProjection(settings.copy(requests = settings.requests.copy(checkCertificate = !setup.skipCertVerify)),
            previous.notificationJson, null, setupParamsJson))
    }

    private suspend fun update(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity {
        val requested = intent.get()
        val actual = requireCurrent(expected)
        val before = actual.identity
        val previous = checkNotNull(store.readLastApplied()).handoff
        val old = MeowRuntimeSnapshotSettings.decode(previous.runtimeSettingsJson)
        val next = projection.settings
        val setup = MeowServiceSetup(projection.setupParamsJson ?: JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), defaultUrl)
        if (old.copy(mode = next.mode, unifiedDelay = next.unifiedDelay, tcpConcurrent = next.tcpConcurrent,
                requests = next.requests, externalController = next.externalController, findProcessMode = next.findProcessMode,
                backgroundPolicy = next.backgroundPolicy) != next ||
            setup.testUrl != defaultUrl && meowMissingGroupTestUrl(ConfigYaml.parse(previous.compiledYaml)))
            throw MeowProtocolFailure("implementation_blocked", "Graph configuration requires setupConfig before it can be applied")
        if (setup.json.getJSONObject("applied").getString("yaml-md5") != JSONObject(previous.setupRecordJson).getString("yaml-md5"))
            throw MeowProtocolFailure("superseded", "Graph YAML identity changed")
        graph = null
        publish(ownerToken, null)
        try {
            checkNotNull(session).configurePolicies(meowParkedEffectiveSettings(next))
            val epoch = CoreApiClient(ControlEndpoint(actual.endpoint.port, actual.endpoint.secret)).use { client ->
                client.setMode(next.mode)
                check(client.mode() == next.mode) { "mode: Graph policy did not converge" }
                MeowRuntimeSelection(client).apply(setup.selectedMap) {
                    requested == intent.get() && ownsOwner(before.proof.serviceOwner) && store.isGraphCandidate(before.revision, before.intentEpoch)
                }
                client.routeSnapshot().coreEpoch.toString()
            }
            val listeners = checkNotNull(session).readGraphReadiness()
            val policy = checkNotNull(session).readGraphPolicy()
            check(!policy.controller.enabled && policy.controller.address == null && !policy.controller.authenticated &&
                policy.controller.coreEpoch == epoch) { "Query graph user controller is not isolated" }
            val handoff = meowServiceProjection(previous, next, JSONObject(projection.sharedStateJson), projection.updateParamsJson,
                setup.json.toString(), setup.testUrl, policy.controller)
            check(epoch == listeners.coreEpoch && !listeners.listening && !listeners.tunReady &&
                !listeners.userListenersReady && listeners.activePorts.isEmpty()) { "Query graph readiness changed" }
            check(requested == intent.get() && ownsOwner(before.proof.serviceOwner)) { "runtime_graph_superseded" }
            val identity = store.commitGraphRuntimeProjection(before, handoff, before.proof.copy(coreEpoch = epoch))
            check(requested == intent.get() && ownsOwner(before.proof.serviceOwner) && store.isGraphCurrent(identity)) { "runtime_graph_superseded" }
            defaultUrl = setup.testUrl
            synchronized(publications) {
                check(requested == intent.get() && ownsOwner(before.proof.serviceOwner)) { "runtime_graph_superseded" }
                graph = MeowQueryGraph(identity, actual.endpoint, false)
                publish(ownerToken, graph)
            }
            return identity
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
