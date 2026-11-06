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
    private val publish: (Long, MeowQueryGraph?) -> Unit,
    private val verifyMetadata: ((RuntimeHandoff, JSONObject) -> Map<String,String>)? = null) : MeowFlutterRuntime {
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
    override suspend fun quiesceProviderSourceCandidate() = retire()
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
        store.admitGraph(snapshot.revision, snapshot.intentEpoch, snapshot.sourcePermit)
        val requested = newIntent()
        return gate.withLock {
            val candidate = store.admitGraph(snapshot.revision, snapshot.intentEpoch, snapshot.sourcePermit)
            release()
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
                val reservedGeneration = ++generation
                val running = owned.open(VpnPlan(candidate.profileId, effective, vpnSettings = proxySettings,
                    ownerIntentToken = requested, runtimeSessionGeneration = reservedGeneration).withProviderSourcePermit(candidate.sourcePermit)).also { held = HeldSession(requested, it) }
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
                val proof = RuntimeAppliedProof(owner, reservedGeneration, epoch)
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
        val requested=intent.get()
        val actual=requireCurrent(expected)
        val before=actual.identity
        val previous=checkNotNull(store.readLastApplied()).handoff
        val params=JSONObject(setupParamsJson)
        val verify=checkNotNull(verifyMetadata) { "Independent current metadata verifier is required" }
        val raw=verify(previous,params)
        val running=checkNotNull(session)
        fun current()=requested == intent.get() && ownsOwner(before.proof.serviceOwner) && store.isGraphCandidate(before.revision,before.intentEpoch) && graph?.identity == before && session === running
        val binding=checkNotNull(running.currentGeoBinding()) { "Trusted initial Geo binding is required" }
        check(binding.serviceOwner == expected.serviceOwner && binding.sessionGeneration == expected.sessionGeneration &&
            binding.intentEpoch == expected.intentEpoch && binding.compiledSha256 == expected.compiledSha256)
        CoreApiClient(ControlEndpoint(actual.endpoint.port,actual.endpoint.secret)).use { client ->
            val witness=client.selectionWitness()
            check(witness.stamp.coreEpoch == expected.coreEpoch && witness.matchesRaw(raw)) { "Actual selected cache differs from SQL" }
            check(current() && verify(previous,params) == raw)
            running.renewGeoBinding(dev.aerove.lite.data.MeowGeoExpected(witness.stamp,binding),witness,raw,
                meowGeoSourceGuard(params.getJSONObject("data-source-proof")),::current)
            check(current() && verify(previous,params) == raw && client.selectionWitness().witnessId == witness.witnessId)
        }
        val settings=MeowRuntimeSnapshotSettings.decode(previous.runtimeSettingsJson)
        val projection=meowServiceProjection(previous,settings,null,null,setupParamsJson,defaultUrl)
        val identity=store.commitGraphRuntimeProjection(before,projection,before.proof)
        synchronized(publications) {
            check(requested == intent.get() && ownsOwner(before.proof.serviceOwner)) { "runtime_graph_superseded" }
            graph=MeowQueryGraph(identity,actual.endpoint,false);publish(ownerToken,graph)
        }
        identity
    }

    override suspend fun applyGeo(expected: MeowSetupRuntime, transactionId: String,
        candidate: dev.aerove.lite.data.MeowGeoCandidate, resources: dev.aerove.lite.data.FlutterRuntimeResources.Geo,
        projection: RuntimeHandoff, rawSelectedMap: Map<String,String>, admit: suspend () -> Boolean): MeowGeoRuntimeResult = gate.withLock {
        checkNotNull(session).joinPreviousGeo()
        val actual=requireCurrent(expected);val before=actual.identity
        val requested=intent.get();val running=checkNotNull(session)
        fun current()=requested == intent.get() && ownsOwner(before.proof.serviceOwner) &&
            store.isGraphCandidate(before.revision,before.intentEpoch) && graph?.identity == before && session === running
        check(current() && admit())
        val publication=withContext(NonCancellable) { store.stageGeoPublication(before,transactionId,projection) }
        try {
            running.applyGeo(transactionId,candidate,resources,rawSelectedMap,::current,admit,
                persist={ applied ->
                    val identity=store.commitGeoPublication(publication,before.proof.copy(coreEpoch=applied.after.coreEpoch))
                    synchronized(publications) {
                        check(requested == intent.get() && ownsOwner(before.proof.serviceOwner))
                        graph=MeowQueryGraph(identity,actual.endpoint,false);publish(ownerToken,graph)
                    }
                    identity
                },discard={store.abortGeoPublication(publication)})
        } catch(failure:Throwable) {
            if(!running.ownsGeoResources(resources)) {
                withContext(NonCancellable) { store.abortGeoPublication(publication);resources.closeAfterNativeJoin() }
            }
            throw failure
        }
    }

    override suspend fun geoStatus(expected: MeowSetupRuntime, transactionId: String, abort: Boolean): MeowGeoRuntimeResult = gate.withLock {
        val actual=checkNotNull(queryGraph()) { "Geo query graph was retired" }
        check(actual.identity.revision == expected.revision && actual.identity.intentEpoch == expected.intentEpoch &&
            actual.identity.proof.serviceOwner == expected.serviceOwner && actual.identity.proof.sessionGeneration == expected.sessionGeneration)
        checkNotNull(session).geoStatus(transactionId,abort)
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
                backgroundPolicy = next.backgroundPolicy, geoResources = next.geoResources) != next ||
            setup.testUrl != defaultUrl && meowMissingGroupTestUrl(ConfigYaml.parse(previous.compiledYaml)))
            throw MeowProtocolFailure("implementation_blocked", "Graph configuration requires setupConfig before it can be applied")
        if (setup.json.getJSONObject("applied").getString("yaml-md5") != JSONObject(previous.setupRecordJson).getString("yaml-md5"))
            throw MeowProtocolFailure("superseded", "Graph YAML identity changed")
        val previousSetup = MeowServiceSetup(JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup").toString(),defaultUrl)
        if (old.copy(geoResources = next.geoResources) == next && setup.selectedMap == previousSetup.selectedMap &&
            setup.testUrl == previousSetup.testUrl && setup.skipCertVerify == previousSetup.skipCertVerify) {
            val epoch = CoreApiClient(ControlEndpoint(actual.endpoint.port,actual.endpoint.secret)).use { it.routeSnapshot().coreEpoch.toString() }
            check(requested == intent.get() && ownsOwner(before.proof.serviceOwner)) { "runtime_graph_superseded" }
            val projection = meowServiceProjection(previous,next,JSONObject(projection.sharedStateJson),projection.updateParamsJson,setup.json.toString(),setup.testUrl)
            val identity = store.commitGraphRuntimeProjection(before,projection,before.proof.copy(coreEpoch=epoch))
            synchronized(publications) {
                check(requested == intent.get() && ownsOwner(before.proof.serviceOwner)) { "runtime_graph_superseded" }
                graph=MeowQueryGraph(identity,actual.endpoint,false);publish(ownerToken,graph)
            }
            return identity
        }
        graph = null
        publish(ownerToken, null)
        try {
            checkNotNull(session).configurePolicies(meowParkedEffectiveSettings(next))
            val epoch = CoreApiClient(ControlEndpoint(actual.endpoint.port, actual.endpoint.secret)).use { client ->
                client.setMode(next.mode)
                check(client.mode() == next.mode) { "mode: Graph policy did not converge" }
                val oldSetup = MeowServiceSetup(JSONObject(previous.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), defaultUrl)
                if (setup.selectedMap != oldSetup.selectedMap) MeowRuntimeSelection(client).apply(setup.selectedMap) {
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
