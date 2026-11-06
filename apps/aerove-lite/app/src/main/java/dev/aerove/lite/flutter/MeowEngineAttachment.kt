package dev.aerove.lite.flutter

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.NativeRulePayloadInspector
import dev.aerove.lite.data.MAX_RULE_TEXT_BYTES
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class MeowEngineAttachment(homeDir: File, private val store: RuntimeHandoffStore,
    private val vpn: StateFlow<VpnSnapshot>, private val scope: CoroutineScope,
    preparedValidator: NativePreparedConfigValidator, private val verifyCapabilities: () -> Unit,
    private val emit: (MeowEngineAttachment, List<Map<String, Any?>>) -> Unit,
    private val warning: (Throwable) -> Unit, private val actions: MeowFlutterRuntime,
    resourceSealer: dev.aerove.lite.data.FlutterResourceSealer? = null,
    sourceVerifier: ((JSONObject) -> Unit)? = null,
    selectedSourceVerifier: ((JSONObject) -> Map<String, String>)? = null,
    definitionSourceVerifier: ((JSONObject) -> String)? = null,
    geoSourceVerifier: ((JSONObject) -> Triple<JSONObject,Map<String,String>,String>)? = null,
    rulePayloadInspector: NativeRulePayloadInspector = nativeRulePayloadInspector(),
    private val geoCatalog: dev.aerove.lite.resources.GeoResourceCatalog? = null,
    providerOwnerSourceVerifier: ((JSONObject) -> String)? = null,
    providerBindingsSourceVerifier: ((JSONObject) -> Map<String, dev.aerove.lite.data.SealedFlutterResource>)? = null) : MeowSetupEffects {
    private val home = MeowFlutterSpill.checkedHome(homeDir)
    private val config = MeowConfigProtocol(home)
    private val offline = MeowOfflineMethods(home)
    private val ruleSets = MeowRuleSetProtocol(home, rulePayloadInspector)
    private val sessionGate = Mutex()
    private var initialized = false
    @Volatile private var closed = false
    private var uiActive = false
    private var session: Session? = null
    private val providerSources by lazy { MeowProviderSourceTransactions(home, store,
        verifySource = { proof -> checkNotNull(providerOwnerSourceVerifier) { "Independent provider business ownership is required" }(proof) },
        actualIdentity = {
            val actual = readRuntime()
            store.readAppliedIdentity()?.takeIf { actual != null && actual.revision == it.revision && actual.intentEpoch == it.intentEpoch &&
                actual.coreEpoch == it.proof.coreEpoch && actual.serviceOwner == it.proof.serviceOwner && actual.sessionGeneration == it.proof.sessionGeneration }
        },
        apply = { params, id, publication -> setup.applyProviderSource(params, id, publication); Unit },
        quiesce = { actions.quiesceProviderSourceCandidate() }, restore = { actions.restoreProviderSourceGraph(it) }) }
    val setup = MeowSetupProtocol(home, store, config, this, preparedValidator, resourceSealer = resourceSealer, sourceVerifier = sourceVerifier, selectedSourceVerifier = selectedSourceVerifier, definitionSourceVerifier = definitionSourceVerifier, geoSourceVerifier = geoSourceVerifier,
        geoResourceReaderFactory = geoCatalog?.let { catalog -> { settings, current -> catalog.reader(settings.geoResources,settings.requests,current) } },
        providerOwnerSourceVerifier = providerOwnerSourceVerifier, providerBindingsSourceVerifier = providerBindingsSourceVerifier)
    val dispatcher: MeowProtocolDispatcher
    private class GeoRequest(val id:String,val requestJson:String,val intent:MeowGeoResourceIntent) {
        var outcome:MeowGeoRuntimeResult?=null
        var resources:dev.aerove.lite.data.FlutterRuntimeResources.Geo?=null
    }
    @Volatile private var geoRequest:GeoRequest?=null
    @Volatile private var cancelledGeoId:String?=null
    private val geoUpdates=geoCatalog?.let { catalog -> MeowGeoResourceUpdates(scope,catalog,
        capture={setup.captureGeoIntent()},verifyCurrent=setup::geoIntentIsCurrent,
        apply={ value,active ->
            val request=checkNotNull(geoRequest).also { check(it.intent.producer?.transactionId == value.producer?.transactionId) }
            val producer=checkNotNull(value.producer)
            val graph=actions.queryGraph();val endpoint=graph?.endpoint ?: vpn.value.endpoint ?: error("Geo runtime endpoint is missing")
            val actual=checkNotNull(endpoint.configuration) { "Actual engine readback is required" }.read()
            val kind=dev.aerove.lite.resources.GeoResourceKind.entries.firstOrNull { it.wire == producer.type }
            val resources=dev.aerove.lite.data.FlutterRuntimeResources.createGeo(home,value.snapshot,actual,value.settings.geoResources,kind,
                catalog.reader(value.settings.geoResources,value.settings.requests) { active() && setup.geoIntentIsCurrent(value) }) { active() && setup.geoIntentIsCurrent(value) }
            request.resources=resources
            try {
                val catalogSnapshot=catalog.capture(value.settings.geoResources,value.settings.requests,resources.catalogKinds)
                suspend fun admit():Boolean {
                    if(!active() || geoRequest !== request || !setup.geoIntentIsCurrent(value))return false
                    val fresh=setup.captureGeoIntent(JSONObject(request.requestJson))
                    return fresh.producer?.sha256 == producer.sha256 && fresh.proofJson == value.proofJson &&
                        fresh.runtime == value.runtime && catalog.matches(catalogSnapshot)
                }
                check(admit()) { "Geo catalog or source changed before submission" }
                val projection=setup.geoProjection(value,resources)
                val candidate=dev.aerove.lite.data.MeowGeoCandidate(resources.compiledYaml,projection.compiledSha256,
                    resources.engineYaml,resources.readRoot,resources.resourceLeaseId,resources.assets)
                val raw=producer.setupParams.getJSONObject("selected-map").let { row -> row.keys().asSequence().associateWith(row::getString) }
                val outcome=actions.applyGeo(value.runtime,producer.transactionId,candidate,resources,projection,raw,::admit)
                request.outcome=outcome
                check(outcome.status.state == "committed") { "Geo runtime did not return a commit" }
                val identity=checkNotNull(outcome.identity)
                setup.acceptGeoPublication(identity)
                dev.aerove.lite.resources.ResourceApplyReceipt(identity.compiledSha256)
            } finally { resources.closeIfUnsubmitted() }
        },emit={kind,updating,skipped,error ->
            if(!closed) emit(this,listOf(mapOf("type" to "geoUpdate","data" to mapOf(
                "type" to kind.wire,"updating" to updating,"skipped" to skipped,"error" to error))))
        },warning=warning,capturePrepared={request -> setup.captureGeoIntent(request).also { intent ->
            val next=GeoRequest(request.getString("transactionId"),request.toString(),intent)
            geoRequest=next
        } }) }
    private data class Session(val identity: RuntimeAppliedIdentity, val endpoint: CoreEndpoint,
        val client: CoreApiClient, val protocol: MeowProtocolSession)

    init {
        val methods = mutableMapOf<String, MeowProtocolMethod>()
        setOf("getConfig", "getProfileBase", "getProfileOutbounds", "buildProfile", "validateConfig").forEach { method ->
            methods[method] = MeowProtocolMethod { config.invoke(method, it) }
        }
        offline.methods.forEach { method -> methods[method] = MeowProtocolMethod { offline.invoke(method, it) } }
        methods["dumpRuleSet"] = MeowProtocolMethod { ruleSets.dump(it) }
        methods["updateExternalProvider"] = MeowProtocolMethod { providerSources.invoke(meowObject(it)) }
        QUERY_METHODS.forEach { method -> methods[method] = MeowProtocolMethod { query(method, it) } }
        methods["initClash"] = MeowProtocolMethod { args ->
            val row = meowObject(args)
            val requestedHome = (row["home-dir"] as? String)?.let(::File)
            val sameHome = try { requestedHome?.let(MeowFlutterSpill::checkedHome) == home }
                catch (_: Exception) { false }
            if (row.keys != setOf("home-dir", "version") || row["version"] !is Int || !sameHome)
                throw MeowProtocolFailure("bad_argument", "Core initialization must use this application's private home and an integer version")
            verifyCapabilities()
            initialized = true
            true
        }
        methods["getIsInit"] = MeowProtocolMethod { meowNoArguments(it); isInitialized() }
        methods["updateGeoData"] = MeowProtocolMethod { geoMethod(meowObject(it)) }
        methods["updateConfig"] = MeowProtocolMethod {
            geoUpdates?.cancelCurrentAndJoin()
            setup.updateConfig(meowObject(it))
        }
        methods["setupConfig"] = MeowProtocolMethod { setup.setupConfig(meowObject(it)) }
        methods["getAppliedSetup"] = MeowProtocolMethod { meowNoArguments(it); setup.getAppliedSetup() }
        methods["refreshAppliedSetup"] = MeowProtocolMethod { setup.refreshAppliedSetup(meowObject(it)) }
        methods["setUiTelemetryActive"] = MeowProtocolMethod {
            setUiActive(it as? Boolean ?: throw MeowProtocolFailure("bad_argument", "UI telemetry state must be a boolean")); true
        }
        dispatcher = MeowProtocolDispatcher(home, methods, discardResult = { method, result ->
            if (method in setOf("getConfig", "buildProfile") && result is String) config.discard(result)
        }, translateFailure = { failure -> (failure as? MeowQueryException)?.let {
            MeowProtocolFailure(it.code, it.message ?: it.code, it.details, it)
        } }, stringResponseBudgets = mapOf("dumpRuleSet" to MeowStringResponseBudget(MAX_RULE_TEXT_BYTES.toInt())))
    }

    private suspend fun geoMethod(arguments:Map<String,Any?>):Map<String,Any?> {
        checkOpen()
        val request=JSONObject(arguments)
        val operation=request.getString("operation")
        val id=request.getString("transactionId").also { require(java.util.UUID.fromString(it).toString() == it) }
        require(request.get("schemaVersion") == 1)
        val updates=geoUpdates ?: throw MeowProtocolFailure("implementation_blocked","The existing Host Geo catalog is unavailable")
        if(operation == "admit") {
            require(arguments.keys == setOf("operation","schemaVersion","transactionId"))
            val value=runCatching { setup.captureGeoIntent() }.getOrNull()
            val needsSource=value == null || !setup.geoProducerPolicyIsApplied(value) || updates.automaticDue(value)
            return mapOf("schemaVersion" to 1,"transactionId" to id,"state" to if(needsSource) "needs-source" else "skipped","active" to false)
        }
        if(operation == "update") {
            if(cancelledGeoId == id)throw MeowProtocolFailure("superseded","Geo request was cancelled before admission")
            val message=updates.update(request.getString("type"),request)
            if(message.isNotEmpty())throw MeowProtocolFailure("geo_update_failed",message)
        } else {
            require(operation in setOf("status","abort") && arguments.keys == setOf("operation","schemaVersion","transactionId"))
            val owned=geoRequest?.takeIf { it.id == id }
            if(operation == "abort") {
                cancelledGeoId=id
                if(owned == null)return mapOf("schemaVersion" to 1,"transactionId" to id,"state" to "superseded","active" to false)
                updates.cancelCurrentAndJoin()
                if(owned.resources?.nativeOwner != true) {
                    return mapOf("schemaVersion" to 1,"transactionId" to id,"state" to "aborted","active" to false)
                }
            }
            if(owned == null)throw MeowProtocolFailure("superseded","Unknown or superseded Geo request")
            val outcome=actions.geoStatus(owned.intent.runtime,id,operation == "abort")
            owned.outcome=outcome
            outcome.identity?.let { setup.acceptGeoPublication(it) }
        }
        val owned=geoRequest?.takeIf { it.id == id } ?: throw MeowProtocolFailure("superseded","Geo producer was superseded")
        val outcome=owned.outcome ?: throw MeowProtocolFailure("geo_apply_unconfirmed","Host maintenance did not return an actual graph commit")
        val identity=outcome.identity
        val active=identity != null && store.isGraphCurrent(identity) && actions.identityIsCurrent(identity)
        return linkedMapOf<String,Any?>("schemaVersion" to 1,"transactionId" to id,"state" to outcome.status.state,"active" to active).apply {
            if(identity != null)put("applied",JSONObject(identity.expectedSetupRecordJson).let { row -> row.keys().asSequence().associateWith { key -> row.get(key) } })
            put("producerSha256",owned.intent.producer?.sha256)
            put("compiledSha256",identity?.compiledSha256)
            outcome.status.error?.let { put("message",it) }
        }
    }

    override fun isInitialized(): Boolean = initialized && !closed
    override fun hasRequestedIntent(): Boolean = !closed && actions.hasRequestedIntent()

    override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) {
        checkOpen()
        verifyApplied(actions.applyStaged(snapshot))
    }

    override suspend fun readRuntime(): MeowSetupRuntime? {
        if (closed) return null
        val identity = store.readAppliedIdentity() ?: return null
        val snapshot = vpn.value
        val graph = actions.queryGraph()
        val endpoint = graph?.endpoint ?: snapshot.endpoint ?: return null
        if (!store.isGraphCurrent(identity)) return null
        if (!owns(identity, endpoint, snapshot)) return null
        val route = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot() }
        if (!owns(identity, endpoint) || route.coreEpoch.toString() != identity.proof.coreEpoch) return null
        return MeowSetupRuntime(identity.revision, identity.intentEpoch, identity.compiledSha256,
            identity.proof.coreEpoch, identity.proof.serviceOwner, identity.proof.sessionGeneration, graph?.listening ?: snapshot.listening)
    }

    override suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection) {
        checkOpen()
        if (expected == null) {
            if (vpn.value.phase !in setOf(VpnPhase.Stopped, VpnPhase.Failed))
                throw MeowProtocolFailure("superseded", "Runtime is changing; an offline projection cannot be published as applied")
            return
        }
        if (readRuntime() != expected) throw MeowProtocolFailure("superseded", "Runtime changed before policy application")
        val receipt = actions.applyUpdate(expected, projection)
        verifyApplied(receipt)
    }

    override suspend fun requireBuilderCompatible(settings: dev.aerove.lite.LiteSettings, vpnOptionsJson: String) {
        checkOpen()
        val snapshot = vpn.value
        if (snapshot.phase == VpnPhase.Running) {
            val desired = meowFlutterBuilderSettings(settings, vpnOptionsJson)
            if (dev.aerove.lite.vpn.requiresVpnRestart(desired, snapshot))
                throw MeowProtocolFailure("restart_required", "Source TUN changes require an explicit VPN restart", mapOf("field" to "$.tun"))
        } else if (snapshot.phase !in setOf(VpnPhase.Stopped, VpnPhase.Failed)) {
            throw MeowProtocolFailure("superseded", "Android Builder ownership is changing")
        }
    }

    override suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String): Boolean {
        if (readRuntime() != expected) return false
        val receipt = actions.refreshSetup(expected, setupParamsJson)
        verifyApplied(receipt)
        return true
    }

    private suspend fun verifyApplied(identity: RuntimeAppliedIdentity) {
        if (store.readAppliedIdentity() != identity || !actions.identityIsCurrent(identity))
            throw MeowProtocolFailure("superseded", "Service policy receipt is no longer applied")
        val runtime = readRuntime()
        if (runtime == null || runtime.revision != identity.revision || runtime.intentEpoch != identity.intentEpoch ||
            runtime.compiledSha256 != identity.compiledSha256 || runtime.coreEpoch != identity.proof.coreEpoch ||
            runtime.serviceOwner != identity.proof.serviceOwner || runtime.sessionGeneration != identity.proof.sessionGeneration)
            throw MeowProtocolFailure("runtime_receipt_mismatch", "Actual native runtime does not match the service policy receipt")
        updateRuntime(vpn.value)
    }

    suspend fun setListening(enabled: Boolean): Boolean {
        val expected = readRuntime() ?: throw MeowProtocolFailure("not_running", "No applied Meow service is available")
        verifyApplied(actions.setListening(expected, enabled))
        return true
    }

    private fun owns(identity: RuntimeAppliedIdentity, endpoint: CoreEndpoint, snapshot: VpnSnapshot = vpn.value): Boolean =
        !closed && actions.identityIsCurrent(identity) && store.isGraphCandidate(identity.revision, identity.intentEpoch) &&
            (actions.queryGraph()?.let { it.endpoint === endpoint && meowSameRuntimeIdentity(it.identity, identity) } ?: (
                snapshot.phase == VpnPhase.Running && snapshot.endpoint === endpoint &&
                    snapshot.serviceOwner == identity.proof.serviceOwner && snapshot.sessionGeneration == identity.proof.sessionGeneration &&
                    store.isCurrent(identity.revision, identity.intentEpoch)))

    suspend fun updateRuntime(snapshot: VpnSnapshot) {
        if (closed) return
        sessionGate.withLock {
            val existing = session
            if (existing != null && !owns(existing.identity, existing.endpoint, snapshot)) retireSession()
            if (uiActive && (actions.queryGraph() != null || snapshot.phase == VpnPhase.Running && snapshot.endpoint != null)) requireSession()
        }
    }

    suspend fun setUiActive(active: Boolean) = sessionGate.withLock {
        checkOpen()
        uiActive = active
        geoUpdates?.foreground(active)
        if (active && (actions.queryGraph() != null || vpn.value.phase == VpnPhase.Running)) requireSession().protocol.setUiTelemetryActive(true)
        else session?.protocol?.setUiTelemetryActive(false)
    }

    private suspend fun query(method: String, arguments: Any?): Any? {
        val selected = sessionGate.withLock { requireSession() }
        val projected = if (method == "asyncTestDelay") {
            val row = meowObject(arguments)
            if (row["test-url"] == "") {
                val runtime = readRuntime() ?: throw MeowProtocolFailure("superseded", "Runtime changed before resolving its default test URL")
                row + ("test-url" to actions.defaultTestUrl(runtime))
            } else row
        } else arguments
        return selected.protocol.invoke(method, projected)
    }

    private suspend fun requireSession(): Session {
        checkOpen()
        session?.takeIf { owns(it.identity, it.endpoint) }?.let { return it }
        retireSession()
        val identity = store.readAppliedIdentity()
            ?: throw MeowProtocolFailure("not_running", "No actual applied Meow runtime is available")
        val snapshot = vpn.value
        val endpoint = actions.queryGraph()?.endpoint ?: snapshot.endpoint
            ?: throw MeowProtocolFailure("not_running", "Meow private control endpoint is not available")
        if (!owns(identity, endpoint, snapshot) || !store.isGraphCurrent(identity)) throw MeowProtocolFailure("superseded", "Meow runtime identity changed")
        val applied = store.readLastApplied()
            ?: throw MeowProtocolFailure("superseded", "Applied Meow configuration disappeared")
        val client = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret))
        try {
            val route = client.routeSnapshot()
            if (!owns(identity, endpoint) || route.coreEpoch.toString() != identity.proof.coreEpoch)
                throw MeowProtocolFailure("superseded", "Meow core epoch changed")
            val owner = identity.proof.serviceOwner.toString()
            val history = MeowNativeDelayHistory(client, owner, identity.proof.sessionGeneration, route.coreEpoch)
            val source = ConfigYaml.parse(applied.handoff.compiledYaml)
            val queries = object : MeowAppliedQueries {
                override suspend fun proxyGroupOrder(): List<String> = (source["proxy-groups"] as? List<*>)?.map { row ->
                    (row as? Map<*, *>)?.get("name") as? String ?: throw MeowProtocolFailure("invalid_config", "Applied proxy group has no name")
                } ?: emptyList()
                override suspend fun proxyDefinition(name: String): Map<String, Any?>? {
                    val definitions = source["proxies"] as? List<*> ?: return null
                    val found = definitions.filterIsInstance<Map<*, *>>().singleOrNull { it["name"] == name } ?: return null
                    return found.entries.associate { (key, value) -> (key as? String
                        ?: throw MeowProtocolFailure("invalid_config", "Applied proxy key is not a string")) to value }
                }
                override suspend fun latestDelays(): MeowDelayHistorySnapshot = history.read()
                override suspend fun providerMetadata(name: String, kind: String, raw: Map<String, Any?>): MeowAppliedProvider {
                    if (!owns(identity, endpoint) || !store.isGraphCurrent(identity))
                        throw MeowProtocolFailure("superseded", "Provider report runtime was retired")
                    val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val actual = checkNotNull(endpoint.configuration) { "Provider report requires actual engine readback" }.read()
                        meowProviderMetadata(home, applied, name, kind, raw, actual)
                    }
                    if (!owns(identity, endpoint) || !store.isGraphCurrent(identity))
                        throw MeowProtocolFailure("superseded", "Provider report runtime changed during readback")
                    return report
                }
            }
            // The owner publishes only after native policy readback and its Store CAS.
            val onlyProxy = MeowRuntimeSnapshotSettings.decode(applied.handoff.runtimeSettingsJson).backgroundPolicy.onlyStatisticsProxy
            val protocol = MeowProtocolSession(client, owner, identity.proof.sessionGeneration, route.coreEpoch,
                scope, { owns(identity, endpoint) }, { rows -> if (owns(identity, endpoint)) emit(this, rows) },
                warning, queries, onlyProxy)
            try {
                if (uiActive) protocol.setUiTelemetryActive(true)
                return Session(identity, endpoint, client, protocol).also { session = it }
            } catch (failure: Throwable) { protocol.closeAndJoin(); throw failure }
        } catch (failure: Throwable) { client.close(); throw failure }
    }

    suspend fun runTime(): String? {
        if (readRuntime() == null) return null
        val snapshot = vpn.value
        val applied = store.readLastApplied() ?: return null
        if (snapshot.runningSinceNanos <= 0) return null
        val elapsed = ((System.nanoTime() - snapshot.runningSinceNanos) / 1_000_000).coerceAtLeast(0)
        return JSONObject().put("startedAt", System.currentTimeMillis() - elapsed)
            .put("vpnOptions", JSONObject(applied.handoff.vpnOptionsJson)).put("parked", !snapshot.listening).toString()
    }

    private suspend fun retireSession() {
        val previous = session ?: return
        session = null
        try { previous.protocol.closeAndJoin() } finally { previous.client.close() }
    }

    suspend fun closeAndJoin(): Boolean {
        closed = true
        if (!dispatcher.closeAndJoin()) return false
        geoUpdates?.closeAndJoin()
        sessionGate.withLock { retireSession() }
        config.close()
        return true
    }

    private fun checkOpen() { if (closed) throw MeowProtocolFailure("closed", "Meow attachment is closed") }
    private companion object {
        val QUERY_METHODS = setOf("getProxies", "getProxyDefinition", "getConnections", "getConnectionCount",
            "closeConnection", "closeConnections", "resetConnections", "clearDnsCache", "getTraffic", "getTotalTraffic",
            "getMemoryStats", "getLatestDelays", "asyncTestDelay", "changeProxy", "watchRoute", "checkRoutes",
            "getExternalProviders", "getExternalProvider", "startLog", "stopLog", "resetTraffic", "probe", "outboundIp", "serviceCheck")
    }
}
