package dev.aerove.lite.flutter

import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.FindProcessMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.controllerprefs.ExternalControllerPreferences
import dev.aerove.lite.core.JniResourceFetch
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.HostGeoResourceReader
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowProfileValidator
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.validateSettings
import dev.aerove.lite.networksettings.ProxyAuthentication
import dev.aerove.lite.resources.GeoResourceKind
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class MeowSetupRuntime(val revision: String, val intentEpoch: Long, val compiledSha256: String, val coreEpoch: String,
    val serviceOwner: Long, val sessionGeneration: Long, val listening: Boolean = true)

class MeowSetupProjection(val settings: LiteSettings, val sharedStateJson: String, val updateParamsJson: String?,
    val setupParamsJson: String?)

interface MeowSetupEffects {
    fun isInitialized(): Boolean
    fun hasRequestedIntent(): Boolean = false
    suspend fun readRuntime(): MeowSetupRuntime?
    suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection)
    suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String): Boolean
    suspend fun requireBuilderCompatible(settings: LiteSettings, vpnOptionsJson: String) {}
    suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) {
        throw MeowConfigProtocolException("implementation_blocked", "A runtime graph owner must apply the staged configuration")
    }
}

class MeowSetupProtocol(homeDir: File, private val store: RuntimeHandoffStore, private val config: MeowConfigProtocol,
    private val effects: MeowSetupEffects, private val preparedValidator: NativePreparedConfigValidator? = null,
    private val verifiedFeatures: () -> Set<String> = { MeowNativeCore.readVerifiedFeatures() },
    private val validator: NativeConfigValidator = NativeConfigValidator { yaml -> JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) } },
    private val resourceSealer: dev.aerove.lite.data.FlutterResourceSealer? = null,
    private val sourceVerifier: ((JSONObject) -> Unit)? = null,
    private val geoResourceReaderFactory: ((LiteSettings, () -> Boolean) -> HostGeoResourceReader)? = null,
    private val selectedSourceVerifier: ((JSONObject) -> Map<String, String>)? = null,
    private val definitionSourceVerifier: ((JSONObject) -> String)? = null,
    private val geoSourceVerifier: ((JSONObject) -> Triple<JSONObject,Map<String,String>,String>)? = null) {
    private val home = MeowFlutterSpill.checkedHome(homeDir)
    private val gate = Mutex()
    private var settings = LiteSettings()
    private var shared: JSONObject? = null
    private var update: JSONObject? = null
    private var setup: JSONObject? = null
    private var geoOrigin: dev.aerove.lite.data.MeowGeoOrigin? = null

    suspend fun syncState(arguments: Map<String, Any?>): String = gate.withLock {
        val row = json(arguments)
        val previousReceipt = if (shared == null) store.readLastApplied() else null
        val previousRuntime = previousReceipt?.handoff?.runtimeSettingsJson?.let(::JSONObject)
        val baseline = previousReceipt?.handoff?.runtimeSettingsJson?.let(MeowRuntimeSnapshotSettings::decode) ?: settings
        val retainedGeoOrigin = previousRuntime?.optJSONObject("flutterGeoOrigin")?.let(dev.aerove.lite.data.MeowGeoOrigin::stored) ?: geoOrigin
        val previousUpdate = update ?: previousRuntime?.optJSONObject("flutterUpdate")
        val previousSetup = setup ?: previousRuntime?.optJSONObject("flutterSetup")
        exact(row, setOf("setupParams", "vpnOptions", "stopTip", "startTip", "localNetworkTip", "currentProfileName", "stopText", "onlyStatisticsProxy", "showStopAction"), "$", setOf("setupParams", "vpnOptions", "showStopAction"))
        listOf("stopTip", "startTip", "localNetworkTip", "currentProfileName", "stopText").forEach { text(row, it, "$.$it") }
        val onlyProxy = flag(row, "onlyStatisticsProxy", "$.onlyStatisticsProxy")
        val showStop = if (row.has("showStopAction")) flag(row, "showStopAction", "$.showStopAction") else true
        var next = baseline.copy(backgroundPolicy = baseline.backgroundPolicy.copy(onlyStatisticsProxy = onlyProxy, showNotificationStopAction = showStop))
        if (!row.isNull("vpnOptions")) next = vpn(objectAt(row, "vpnOptions", "$.vpnOptions"), next)
        val nextSetup = if (row.isNull("setupParams")) previousSetup else setupParams(objectAt(row, "setupParams", "$.setupParams"))
        if (sourceVerifier != null && nextSetup != null && previousSetup != null) {
            listOf("applied", "base", "data-source-proof").forEach { key ->
                if (!nextSetup.has(key) && previousSetup.has(key)) nextSetup.put(key, previousSetup.get(key))
            }
        }
        if (row.isNull("vpnOptions")) {
            val retained = shared?.optJSONObject("vpnOptions") ?: previousReceipt?.handoff?.vpnOptionsJson?.let(::JSONObject)
            if (retained != null) row.put("vpnOptions", JSONObject(retained.toString()))
        }
        val appliedSource = store.readLastApplied()?.handoff?.compiledYaml
        val reconciled = if (appliedSource != null && !row.isNull("vpnOptions")) meowReconcileFlutterTun(appliedSource, next, row) else null
        next = reconciled?.settings ?: next
        val effectiveRow = reconciled?.shared ?: row
        validateSettings(next)
        effects.requireBuilderCompatible(next, effectiveRow.optJSONObject("vpnOptions")?.toString() ?: previousReceipt?.handoff?.vpnOptionsJson ?: "{}")
        val projection = MeowSetupProjection(next, effectiveRow.toString(), previousUpdate?.toString(), nextSetup?.toString())
        effects.applyUpdate(effects.readRuntime(), projection)
        settings = next; shared = effectiveRow; setup = nextSetup; update = previousUpdate; geoOrigin = retainedGeoOrigin
        ""
    }

    suspend fun updateConfig(arguments: Map<String, Any?>): String = gate.withLock {
        val row = json(arguments)
        val active = effects.readRuntime()
        val previous = shared ?: bad("$.sharedState", "syncState must precede updateConfig")
        val proof = row.optJSONObject("data-source-proof")
        row.remove("data-source-proof")
        val nextSetup = setup?.let { JSONObject(it.toString()) }
        if (sourceVerifier != null) {
            val source = proof ?: bad("$.data-source-proof", "Current shared business source proof is required")
            sourceVerifier.invoke(source)
            if (nativeLiteJsonHash(row) != source.getString("liveSha256"))
                bad("$.data-source-proof", "Live parameters do not match current business settings")
            if (active != null) {
                val prior = nextSetup?.optJSONObject("data-source-proof") ?: bad("$.data-source-proof", "Applied source is missing")
                if (listOf("setupSha256", "tables", "files").any { nativeLiteJsonHash(prior.get(it)) != nativeLiteJsonHash(source.get(it)) })
                    throw MeowConfigProtocolException("superseded", "Selected setup dependencies changed; setupConfig is required")
                nextSetup.put("data-source-proof", source)
            }
        }
        val decoded = decodeUpdate(row, active)
        val origin = if (active == null) geoOrigin else store.readLastApplied()?.handoff?.runtimeSettingsJson?.let(::JSONObject)
            ?.optJSONObject("flutterGeoOrigin")?.let(dev.aerove.lite.data.MeowGeoOrigin::stored)
        if (active != null && sourceVerifier != null && origin == null)
            blocked("$.aerove-geo-origin", "The actual producer's sparse Geo origin is required before changing global fallback policy")
        val next = decoded.copy(geoResources = origin?.apply(decoded.geoResources) ?: decoded.geoResources)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        effects.applyUpdate(active, MeowSetupProjection(next, previous.toString(), row.toString(), nextSetup?.toString()))
        proof?.let { sourceVerifier?.invoke(it) }
        settings = next; update = row; setup = nextSetup; geoOrigin = origin
        ""
    }

    private fun decodeUpdate(row: JSONObject, active: MeowSetupRuntime?): LiteSettings {
        exact(row, updateKeys, "$")
        val tun = objectAt(row, "tun", "$.tun")
        exact(tun, setOf("enable", "stack"), "$.tun")
        val tunEnabled = flag(tun, "enable", "$.tun.enable")
        val stack = text(tun, "stack", "$.tun.stack")
        if (stack != "lwip") blocked("$.tun.stack", "Meow uses lwIP; Mihomo TUN stacks cannot be substituted")
        if (tunEnabled && shared?.optJSONObject("vpnOptions")?.optBoolean("enable") != true)
            bad("$.tun.enable", "Android TUN requires enabled SharedState VPN options; the service owns its FD and routes")
        val ipv6 = flag(row, "ipv6", "$.ipv6")
        val log = text(row, "log-level", "$.log-level")
        if (log !in setOf("silent", "error", "warning", "info", "debug")) bad("$.log-level", "Unknown log level")
        if (active != null && log != update?.optString("log-level")) blocked("$.log-level", "Live native log-level application/readback is not connected")
        val credentials = strings(row, "authentication", "$.authentication")
        if (credentials.size > 1) blocked("$.authentication", "Native network projection supports one account; multiple credentials cannot be discarded")
        val auth = credentials.singleOrNull()?.let {
            val split = it.indexOf(':')
            if (split <= 0) bad("$.authentication[0]", "Expected username:password")
            ProxyAuthentication(true, it.substring(0, split), it.substring(split + 1))
        } ?: ProxyAuthentication()
        val controller = text(row, "external-controller", "$.external-controller")
        if (controller !in setOf("", ExternalControllerPreferences.ADDRESS)) blocked("$.external-controller", "Only the authenticated native loopback controller is implemented")
        val secret = text(row, "secret", "$.secret")
        if (controller.isNotEmpty() && (secret.isEmpty() || secret != secret.trim() || secret.length > 512 || secret.any { it.code < 32 || it.code == 127 }))
            bad("$.secret", "Enabled native controller requires a canonical non-empty Bearer secret")
        val geo = objectAt(row, "geox-url", "$.geox-url")
        val urls = dev.aerove.lite.resources.GeoResourcePreferences().urls.toMutableMap()
        geo.keys().forEach { key ->
            val kind = GeoResourceKind.entries.firstOrNull { it.wire == key } ?: blocked("$.geox-url.$key", "No equivalent native geo-resource format")
            urls[kind] = text(geo, key, "$.geox-url.$key")
        }
        val next = settings.copy(mode = when (text(row, "mode", "$.mode")) {
            "rule" -> ProxyMode.Rule; "global" -> ProxyMode.Global; "direct" -> ProxyMode.Direct; else -> bad("$.mode", "Unknown mode") },
            ipv6 = ipv6, findProcessMode = FindProcessMode.entries.firstOrNull { it.wireName == text(row, "find-process-mode", "$.find-process-mode") }
                ?: bad("$.find-process-mode", "Unknown process mode"),
            unifiedDelay = flag(row, "unified-delay", "$.unified-delay"), tcpConcurrent = flag(row, "tcp-concurrent", "$.tcp-concurrent"),
            network = settings.network.copy(allowLan = flag(row, "allow-lan", "$.allow-lan"),
                ports = settings.network.ports.copy(mixed = integer(row, "mixed-port", "$.mixed-port", 0, 65535)), authentication = auth),
            externalController = ExternalControllerPreferences(controller.isNotEmpty(), secret),
            requests = settings.requests.copy(checkCertificate = !flag(row, "skip-cert-verify", "$.skip-cert-verify")),
            geoResources = settings.geoResources.copy(autoUpdate = flag(row, "geo-auto-update", "$.geo-auto-update"),
                intervalHours = integer(row, "geo-update-interval", "$.geo-update-interval", 1, Int.MAX_VALUE), urls = urls))
        validateSettings(next)
        return next
    }

    suspend fun setupConfig(arguments: Map<String, Any?>): String = withContext(Dispatchers.IO) { stage(arguments) }

    private suspend fun stage(arguments: Map<String, Any?>): String = gate.withLock {
        if (!effects.isInitialized()) throw MeowConfigProtocolException("not_initialized", "Meow offline protocol is not initialized")
        val params = setupParams(json(arguments))
        val state = shared ?: bad("$.sharedState", "syncState must precede setupConfig")
        if (state.isNull("vpnOptions")) bad("$.vpnOptions", "A complete runtime VPN projection is required")
        val applied = objectAt(params, "applied", "$.applied")
        record(applied)
        val expectedEpoch = store.currentEpoch()
        val before = store.readCurrent()
        if (before != null && before.intentEpoch != expectedEpoch) throw MeowConfigProtocolException("superseded", "Runtime intent changed before staging")
        var temporary: String? = null
        var resourceLease: dev.aerove.lite.data.FlutterResourceLease? = null
        try {
            val sourceFile: File
            val sourceBytes: ByteArray
            val path = if (params.isNull("base")) {
                sourceFile = checkedFile(File(home, "config.yaml"))
                sourceBytes = Files.newInputStream(sourceFile.toPath(), READ, NOFOLLOW_LINKS).use { it.readBounded(4 * 1024 * 1024) }
                sourceFile.path
            } else {
                val base = params.getJSONObject("base")
                sourceFile = checkedFile(File(base.getString("path")))
                sourceBytes = Files.newInputStream(sourceFile.toPath(), READ, NOFOLLOW_LINKS).use { it.readBounded(4 * 1024 * 1024) }
                config.buildProfile(sourceFile.path, base.getString("overlay")).also { temporary = it }
            }
            val input = read(checkedFile(File(path)), 4 * 1024 * 1024)
            val sourceSha = runtimeSha256(sourceBytes)
            fun checkSource() {
                sourceVerifier?.invoke(params.optJSONObject("data-source-proof") ?: bad("$.data-source-proof", "Current shared business source proof is required"))
                if (read(checkedFile(File(path)), 4 * 1024 * 1024) != input)
                    throw MeowConfigProtocolException("superseded", "Compiled input changed while sealing resources")
                if (runtimeSha256(Files.newInputStream(checkedFile(sourceFile).toPath(), READ, NOFOLLOW_LINKS).use { it.readBounded(4 * 1024 * 1024) }) != sourceSha)
                    throw MeowConfigProtocolException("superseded", "Compiled source changed while staging")
            }
            val sourceContext = kotlinx.coroutines.currentCoroutineContext()
            val setupUpdate = updateFromYaml(input, params)
            val decoded = decodeUpdate(setupUpdate, null)
            val inputRoot = ConfigYaml.parse(input)
            val inputGeoOrigin = dev.aerove.lite.data.MeowGeoOrigin.read(inputRoot)
            if (sourceVerifier != null && inputGeoOrigin == null)
                blocked("$.aerove-geo-origin", "The actual shared producer's sparse Geo origin is required")
            val sourceUa = inputRoot["global-ua"]?.let {
                if (it !is String || !dev.aerove.lite.requests.RequestPreferences.validUserAgent(it)) bad("$.global-ua", "Invalid HTTP User-Agent")
                it
            }
            val decodedPolicies = decoded.copy(requests = decoded.requests.copy(globalUserAgent = sourceUa))
            val tun = meowReconcileFlutterTun(input, decodedPolicies, state)
            val projected = tun.settings
            val effectiveState = tun.shared
            effects.requireBuilderCompatible(projected, effectiveState.getJSONObject("vpnOptions").toString())
            val selections = params.getJSONObject("selected-map").let { row -> row.keys().asSequence().associateWith { row.getString(it) } }
            val geoReader = geoResourceReaderFactory?.invoke(projected) {
                if (!sourceContext.isActive) false else {
                    checkSource()
                    val current = store.peekCurrent()
                    current?.revision == before?.revision && current?.intentEpoch == before?.intentEpoch
                }
            }
            if (meowNeedsResourceCapture(inputRoot) || resourceSealer?.requiresGeoCapture(input) == true) resourceLease = resourceSealer?.prepare(input, projected.requests.checkCertificate, selections,
                tunReconciliation = tun.reconciliation, geoResourceReader = geoReader)
            val yaml = resourceLease?.yaml ?: input
            val existing = if (resourceLease == null) resources(yaml) else null
            if (resourceLease == null && existing?.second != null) resourceLease = resourceSealer?.borrow(yaml, checkNotNull(existing.second))
            val resources = resourceLease?.let { lease ->
                lease.resources.map { RuntimeHandoffResource(it.relativePath, it.sourcePath, it.sha256) } to lease.directory
            } ?: checkNotNull(existing)
            val prepared = preparedValidator ?: NativePreparedConfigValidator { _, _ -> blocked("$.resources", "Prepared native validation is required for sealed resources") }
            MeowProfileValidator(validator, prepared, home, verifiedFeatures, tunReconciliation = tun.reconciliation).validate(yaml, resources.second != null, resources.second)
            val runtimeSettings = MeowRuntimeSnapshotSettings.encode(projected).put("flutterSetup", params).put("flutterUpdate", setupUpdate)
            inputGeoOrigin?.let { runtimeSettings.put("flutterGeoOrigin", it.json()) }
            val producer = RuntimeHandoffResource("flutter-producer.yaml", path, runtimeSha256(input.toByteArray()))
            runtimeSettings.put("flutterProducer", JSONObject().put("file", producer.relativePath)
                .put("sha256", producer.sha256).put("bytes", input.toByteArray().size))
            definitionSourceVerifier?.let { verify ->
                runtimeSettings.put("flutterDefinitionSha256", verify(params.getJSONObject("data-source-proof")))
            }
            if (before?.userRequested == true && effects.hasRequestedIntent()) {
                val prior = JSONObject(before.handoff.runtimeSettingsJson)
                if (prior.has("flutterListeningRequested"))
                    runtimeSettings.put("flutterListeningRequested", prior.getBoolean("flutterListeningRequested"))
            }
            checkSource()
            val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, sourceFile.path, sourceSha, sourceSha,
                yaml, runtimeSha256(yaml.toByteArray()), applied.toString(), runtimeSettings.toString(), effectiveState.getJSONObject("vpnOptions").toString(),
                notification(effectiveState).toString(), resources.first + producer)
            MeowRuntimeSnapshotSettings.validate(handoff)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            effects.requireBuilderCompatible(projected, effectiveState.getJSONObject("vpnOptions").toString())
            val staged = store.commitForSetup(handoff, expectedEpoch, before?.revision, effects::hasRequestedIntent, ::checkSource)
            effects.applyStaged(staged)
            setup = params; settings = projected; shared = effectiveState; update = setupUpdate; geoOrigin = inputGeoOrigin
            ""
        } finally {
            try { resourceLease?.closeAndJoin() } finally { temporary?.let(config::discard) }
        }
    }

    internal suspend fun captureGeoIntent(): MeowGeoResourceIntent = gate.withLock {
        val runtime=effects.readRuntime() ?: throw MeowConfigProtocolException("not_running","An actually applied graph is required for Geo maintenance")
        val snapshot=store.readLastApplied() ?: throw MeowConfigProtocolException("superseded","Applied source disappeared")
        if (!matches(runtime,snapshot)) throw MeowConfigProtocolException("superseded","Applied Geo source changed")
        val proof=JSONObject(snapshot.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").optJSONObject("data-source-proof")
            ?: bad("$.data-source-proof","Current shared business proof is required")
        val verify=sourceVerifier ?: blocked("$.data-source-proof","An independent Native business source verifier is required")
        verify(proof)
        MeowGeoResourceIntent(MeowRuntimeSnapshotSettings.decode(snapshot.handoff.runtimeSettingsJson),runtime,snapshot,proof.toString())
    }

    internal suspend fun captureGeoIntent(request: JSONObject): MeowGeoResourceIntent = gate.withLock {
        val runtime=effects.readRuntime() ?: throw MeowConfigProtocolException("not_running","Geo maintenance requires an actually applied graph")
        val snapshot=store.readLastApplied() ?: throw MeowConfigProtocolException("superseded","Applied Geo source disappeared")
        if (!matches(runtime,snapshot) || !store.isGraphCurrent(checkNotNull(store.readAppliedIdentity())))
            throw MeowConfigProtocolException("superseded","Applied Geo owner changed")
        val verify=geoSourceVerifier ?: blocked("$.data-source-proof","Independent complete business source admission is required")
        val producer=MeowGeoProducer.read(home,request,snapshot,::setupParams,verify)
        val next=MeowRuntimeSnapshotSettings.decode(snapshot.handoff.runtimeSettingsJson).copy(
            geoResources=dev.aerove.lite.data.MeowGeoPolicy.read(ConfigYaml.parse(producer.body),settings.geoResources))
        effects.requireBuilderCompatible(next,snapshot.handoff.vpnOptionsJson)
        MeowGeoResourceIntent(next,runtime,snapshot,producer.setupParams.getJSONObject("data-source-proof").toString(),producer)
    }

    internal suspend fun geoProjection(intent: MeowGeoResourceIntent, resources: dev.aerove.lite.data.FlutterRuntimeResources.Geo): RuntimeHandoff = gate.withLock {
        check(geoIntentIsCurrent(intent)) { "Geo producer was superseded" }
        val producer=checkNotNull(intent.producer) { "A fresh producer request is required" }
        val previous=intent.snapshot.handoff
        val metadata=JSONObject(previous.runtimeSettingsJson)
        val encoded=MeowRuntimeSnapshotSettings.encode(intent.settings)
        encoded.keys().forEach { key -> metadata.put(key,encoded.get(key)) }
        val producerFile="geo/${resources.resourceLeaseId}/producer.yaml"
        val oldProducer=metadata.getJSONObject("flutterProducer").getString("file")
        metadata.put("flutterSetup",producer.setupParams)
            .put("flutterUpdate",updateFromYaml(producer.body,producer.setupParams))
            .put("flutterProducer",JSONObject().put("file",producerFile).put("sha256",producer.sha256).put("bytes",producer.body.toByteArray().size))
            .put("flutterDefinitionSha256",producer.definitionSha256).put("flutterGeoAssets",resources.records)
        val preserved=previous.resources.filter { !it.relativePath.startsWith("geo/") && it.relativePath != oldProducer }
        val all=preserved+resources.resources+RuntimeHandoffResource(producerFile,producer.file.path,producer.sha256)
        RuntimeHandoff(previous.coreSha,previous.sourceId,previous.sourceRevision,previous.sourceSha256,
            resources.compiledYaml,runtimeSha256(resources.compiledYaml.toByteArray()),producer.setupParams.getJSONObject("applied").toString(),
            metadata.toString(),previous.vpnOptionsJson,previous.notificationJson,all).also(MeowRuntimeSnapshotSettings::validate)
    }

    internal suspend fun acceptGeoPublication(identity: RuntimeAppliedIdentity) = gate.withLock {
        check(store.isGraphCurrent(identity)) { "Geo receipt was superseded" }
        val applied=checkNotNull(store.readLastApplied())
        val runtime=JSONObject(applied.handoff.runtimeSettingsJson)
        settings=MeowRuntimeSnapshotSettings.decode(applied.handoff.runtimeSettingsJson)
        setup=runtime.getJSONObject("flutterSetup");update=runtime.getJSONObject("flutterUpdate")
        geoOrigin=runtime.optJSONObject("flutterGeoOrigin")?.let(dev.aerove.lite.data.MeowGeoOrigin::stored)
    }

    internal fun geoProducerPolicyIsApplied(intent: MeowGeoResourceIntent): Boolean {
        val metadata=JSONObject(intent.snapshot.handoff.runtimeSettingsJson)
        val producer=metadata.getJSONObject("flutterProducer")
        val file=File(intent.snapshot.resourceDirectory,producer.getString("file"))
        val body=read(checkedFile(file),4*1024*1024)
        check(runtimeSha256(body.toByteArray()) == producer.getString("sha256"))
        return dev.aerove.lite.data.MeowGeoPolicy.read(ConfigYaml.parse(body),settings.geoResources) == intent.settings.geoResources
    }

    internal fun geoIntentIsCurrent(intent: MeowGeoResourceIntent): Boolean {
        if (!store.isGraphCandidate(intent.runtime.revision,intent.runtime.intentEpoch) ||
            store.peekCurrent()?.handoff?.compiledSha256 != intent.runtime.compiledSha256) return false
        val verify=sourceVerifier ?: return false
        verify(JSONObject(intent.proofJson))
        return true
    }

    suspend fun staged(): MeowRuntimeSnapshot? = store.readCurrent()

    suspend fun getAppliedSetup(): Map<String, Any?> = gate.withLock {
        val last = store.readLastApplied()
        val live = effects.readRuntime()
        val running = last != null && matches(live, last) && live?.listening == true
        mapOf("init" to effects.isInitialized(), "running" to running, "applied" to last?.handoff?.setupRecordJson?.let { map(JSONObject(it)) })
    }

    suspend fun refreshAppliedSetup(arguments: Map<String, Any?>): Boolean = gate.withLock {
        val params = setupParams(json(arguments))
        if (sourceVerifier != null) {
            val proof = params.optJSONObject("data-source-proof") ?: bad("$.data-source-proof", "Current shared business source proof is required")
            val verify = selectedSourceVerifier ?: blocked("$.selected-map", "Independent current selected source verification is required")
            if (verify(proof) != map(params.getJSONObject("selected-map"))) return@withLock false
        }
        val next = objectAt(params, "applied", "$.applied").also(::record)
        val last = store.readLastApplied() ?: return@withLock false
        val live = effects.readRuntime() ?: return@withLock false
        if (store.currentEpoch() != live.intentEpoch) return@withLock false
        if (!matches(live, last) || JSONObject(last.handoff.setupRecordJson).getString("yaml-md5") != next.getString("yaml-md5")) return@withLock false
        if (sourceVerifier != null) {
            val previousProof = JSONObject(last.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")
            if (!meowMetadataResourcesUnchanged(previousProof, params.getJSONObject("data-source-proof"))) return@withLock false
        }
        if (!effects.refreshApplied(live, params.toString())) return@withLock false
        var readback = store.readLastApplied() ?: return@withLock false
        definitionSourceVerifier?.let { verify ->
            val definition = verify(params.getJSONObject("data-source-proof"))
            val identity = store.readAppliedIdentity() ?: return@withLock false
            if (!matches(effects.readRuntime(), readback)) return@withLock false
            val old = readback.handoff
            val metadata = JSONObject(old.runtimeSettingsJson).put("flutterDefinitionSha256", definition)
            val projection = RuntimeHandoff(old.coreSha, old.sourceId, old.sourceRevision, old.sourceSha256,
                old.compiledYaml, old.compiledSha256, old.setupRecordJson, metadata.toString(),
                old.vpnOptionsJson, old.notificationJson, old.resources)
            if (readback.userRequested) store.commitRuntimeProjection(identity, projection, identity.proof)
            else store.commitGraphRuntimeProjection(identity, projection, identity.proof)
            readback = store.readLastApplied() ?: return@withLock false
        }
        matches(effects.readRuntime(), readback) && map(JSONObject(readback.handoff.setupRecordJson)) == map(next)
    }

    private fun vpn(row: JSONObject, previous: LiteSettings): LiteSettings {
        exact(row, setOf("enable", "port", "ipv6", "dnsHijacking", "accessControlProps", "allowBypass", "systemProxy", "bypassDomain", "stack", "routeAddress", "mtu", "congestionController", "routeAuto", "routeDefaultsPerFamily", "routeExcludeAddress", "inet4Address", "inet6Address", "dnsHijackTargets", "rawRouteAddress", "rawRouteExcludeAddress", "bypassPrivateRoute"), "$.vpnOptions", setOf("routeAddress", "mtu", "congestionController", "routeAuto", "routeDefaultsPerFamily", "routeExcludeAddress", "inet4Address", "inet6Address", "dnsHijackTargets", "rawRouteAddress", "rawRouteExcludeAddress", "bypassPrivateRoute"))
        val rawKeys = setOf("rawRouteAddress", "rawRouteExcludeAddress", "bypassPrivateRoute")
        if (rawKeys.any(row::has)) rawKeys.forEach { if (!row.has(it) || row.isNull(it)) bad("$.vpnOptions.$it", "A complete original route policy is required") }
        val enabled = flag(row, "enable", "$.vpnOptions.enable")
        listOf("ipv6", "dnsHijacking", "allowBypass", "systemProxy").forEach { flag(row, it, "$.vpnOptions.$it") }
        integer(row, "port", "$.vpnOptions.port", 0, 65535)
        val stack = text(row, "stack", "$.vpnOptions.stack")
        if ((enabled || stack.isNotEmpty()) && stack != "lwip")
            blocked("$.vpnOptions.stack", "Meow uses lwIP; Mihomo TUN stacks cannot be substituted")
        val congestion = if (row.has("congestionController")) text(row, "congestionController", "$.vpnOptions.congestionController") else ""
        if ((enabled || congestion.isNotEmpty()) && congestion != "builtin")
            blocked("$.vpnOptions.congestionController", "lwIP uses its built-in congestion control; selectable Mihomo algorithms are unsupported")
        val access = objectAt(row, "accessControlProps", "$.vpnOptions.accessControlProps")
        exact(access, setOf("enable", "mode", "acceptList", "rejectList", "sort", "isFilterSystemApp", "isFilterNonInternetApp"), "$.vpnOptions.accessControlProps", setOf("sort", "isFilterSystemApp", "isFilterNonInternetApp"))
        val accessEnabled = flag(access, "enable", "$.vpnOptions.accessControlProps.enable")
        val mode = text(access, "mode", "$.vpnOptions.accessControlProps.mode")
        if (mode !in setOf("acceptSelected", "rejectSelected")) bad("$.vpnOptions.accessControlProps.mode", "Unknown access mode")
        val accepts = strings(access, "acceptList", "$.vpnOptions.accessControlProps.acceptList")
        val rejects = strings(access, "rejectList", "$.vpnOptions.accessControlProps.rejectList")
        if (access.has("sort") && text(access, "sort", "$.vpnOptions.accessControlProps.sort") !in setOf("none", "name", "time")) bad("$.vpnOptions.accessControlProps.sort", "Unknown access sort")
        listOf("isFilterSystemApp", "isFilterNonInternetApp").forEach { if (access.has(it)) flag(access, it, "$.vpnOptions.accessControlProps.$it") }
        val domains = strings(row, "bypassDomain", "$.vpnOptions.bypassDomain")
        val routes = if (row.has("routeAddress")) strings(row, "routeAddress", "$.vpnOptions.routeAddress") else emptyList()
        val mtu = if (row.has("mtu")) integer(row, "mtu", "$.vpnOptions.mtu", 0, 65535) else 0
        return previous.copy(network = previous.network.copy(vpnEnabled = enabled, systemProxy = enabled && row.getBoolean("systemProxy"),
            allowBypass = enabled && row.getBoolean("allowBypass"), dnsHijacking = enabled && row.getBoolean("dnsHijacking"),
            bypassDomains = if (enabled && row.getBoolean("systemProxy")) domains else emptyList(), routeAddress = if (row.has("rawRouteAddress")) strings(row,"rawRouteAddress","$.vpnOptions.rawRouteAddress") else routes,
            bypassPrivateRoute = if (row.has("bypassPrivateRoute")) flag(row,"bypassPrivateRoute","$.vpnOptions.bypassPrivateRoute") else false,
            mtu = mtu.takeIf { it > 0 } ?: 9000,
            routeDefaultsPerFamily = if (row.has("routeDefaultsPerFamily")) flag(row,"routeDefaultsPerFamily","$.vpnOptions.routeDefaultsPerFamily") else row.has("rawRouteAddress"),
            routeAuto = if (row.has("routeAuto")) flag(row,"routeAuto","$.vpnOptions.routeAuto") else row.has("rawRouteAddress") || routes.isEmpty(),
            routeExcludeAddress = if (row.has("rawRouteExcludeAddress")) strings(row,"rawRouteExcludeAddress","$.vpnOptions.rawRouteExcludeAddress") else if (row.has("routeExcludeAddress")) strings(row,"routeExcludeAddress","$.vpnOptions.routeExcludeAddress") else emptyList(),
            tunInet4Address = if(row.has("inet4Address")) text(row,"inet4Address","$.vpnOptions.inet4Address") else "198.18.0.1/30",
            tunInet6Address = if(row.has("inet6Address")) text(row,"inet6Address","$.vpnOptions.inet6Address") else "fdfe:dcba:9876::1/126",
            tunDnsHijack = if(row.has("dnsHijackTargets") && !row.isNull("dnsHijackTargets")) strings(row,"dnsHijackTargets","$.vpnOptions.dnsHijackTargets") else null),
            appFilterMode = if (!enabled || !accessEnabled) AppFilterMode.All else if (mode == "acceptSelected") AppFilterMode.Include else AppFilterMode.Exclude,
            packages = if (!enabled || !accessEnabled) emptySet() else (if (mode == "acceptSelected") accepts else rejects).toSet())
    }

    private fun setupParams(row: JSONObject): JSONObject {
        exact(row, setOf("selected-map", "test-url", "skip-cert-verify", "base", "applied", "data-source-proof"), "$.setupParams", setOf("skip-cert-verify", "base", "applied", "data-source-proof"))
        val selected = objectAt(row, "selected-map", "$.selected-map")
        selected.keys().forEach { text(selected, it, "$.selected-map.$it") }
        val url = text(row, "test-url", "$.test-url")
        if (url.isNotEmpty()) {
            val parsed = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { bad("$.test-url", "Expected an HTTP(S) probe URL") }
            if (parsed.scheme !in setOf("http", "https") || parsed.host == null || parsed.rawUserInfo != null) bad("$.test-url", "Expected an HTTP(S) probe URL")
        }
        if (row.has("skip-cert-verify")) flag(row, "skip-cert-verify", "$.skip-cert-verify") else row.put("skip-cert-verify", false)
        if (!row.isNull("base")) {
            val base = objectAt(row, "base", "$.base")
            exact(base, setOf("path", "overlay"), "$.base")
            text(base, "path", "$.base.path"); text(base, "overlay", "$.base.overlay")
        }
        return row
    }

    private fun updateFromYaml(yaml: String, params: JSONObject): JSONObject {
        val root = ConfigYaml.parse(yaml)
        val baseline = update?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("tun", JSONObject().put("enable", settings.network.vpnEnabled).put("stack", "lwip"))
            .put("mixed-port", settings.network.ports.mixed).put("allow-lan", settings.network.allowLan)
            .put("find-process-mode", settings.findProcessMode.wireName).put("mode", settings.mode.name.lowercase(java.util.Locale.ROOT))
            .put("log-level", "info").put("ipv6", settings.ipv6).put("tcp-concurrent", settings.tcpConcurrent)
            .put("external-controller", if (settings.externalController.enabled) ExternalControllerPreferences.ADDRESS else "")
            .put("secret", settings.externalController.secret).put("unified-delay", settings.unifiedDelay)
            .put("authentication", JSONArray(settings.network.authentication.credentials)).put("geo-auto-update", settings.geoResources.autoUpdate)
            .put("geo-update-interval", settings.geoResources.intervalHours).put("geox-url", JSONObject())
        (updateKeys - setOf("tun", "skip-cert-verify")).forEach { key -> if (root.containsKey(key)) baseline.put(key, JSONObject.wrap(root[key])) }
        if (root.containsKey("geodata") || GeoResourceKind.entries.any { root.containsKey("${it.sourceKey}-download-url") }) {
            val geo = dev.aerove.lite.data.MeowGeoPolicy.read(root, settings.geoResources)
            baseline.put("geo-auto-update", geo.autoUpdate).put("geo-update-interval", geo.intervalHours)
                .put("geox-url", JSONObject().apply { geo.urls.forEach { (kind, url) -> put(kind.wire, url) } })
        }
        baseline.put("skip-cert-verify", params.getBoolean("skip-cert-verify"))
        return baseline
    }

    private fun record(row: JSONObject) {
        exact(row, setOf("digest", "yaml-md5", "groups", "proxies", "rules"), "$.applied")
        if (text(row, "digest", "$.applied.digest").isBlank()) bad("$.applied.digest", "Expected the original shared build digest")
        if (!text(row, "yaml-md5", "$.applied.yaml-md5").matches(Regex("[0-9a-f]{32}"))) bad("$.applied.yaml-md5", "Expected the original shared YAML identity")
        listOf("groups", "proxies", "rules").forEach { integer(row, it, "$.applied.$it", 0, Int.MAX_VALUE) }
    }

    private suspend fun resources(yaml: String): Pair<List<RuntimeHandoffResource>, File?> = withContext(Dispatchers.IO) {
        val root = ConfigYaml.parse(yaml)
        val paths = linkedSetOf<String>()
        listOf("proxy-providers", "rule-providers").forEach { kind -> (root[kind] as? Map<*, *>)?.forEach { (name, raw) ->
            val row = raw as? Map<*, *> ?: bad("$.$kind.$name", "Expected provider object")
            if (row["type"] == "http") blocked("$.$kind.$name", "Remote providers must be sealed before setup")
            (row["path"] as? String)?.let(paths::add)
        } }
        (root["geodata"] as? Map<*, *>)?.let { geo -> listOf("mmdb-path", "asn-path", "geosite-path").forEach { (geo[it] as? String)?.let(paths::add) } }
        (root["proxies"] as? List<*>)?.forEach { raw -> (raw as? Map<*, *>)?.let { row -> listOf("tls-cert", "tls-key", "ca").forEach { (row[it] as? String)?.let(paths::add) } } }
        if (paths.isEmpty()) return@withContext emptyList<RuntimeHandoffResource>() to null
        val bundles = File(home, "runtime/resources/bundles").toPath()
        var generation: File? = null
        var checkedManifest: JSONObject? = null
        val assets = paths.map { path ->
            val file = checkedFile(File(path))
            if (!file.toPath().startsWith(bundles)) bad("$.resources", "Only an existing sealed bundle grants resource access")
            var parent = file.parentFile
            while (parent.toPath() != bundles && !File(parent, "manifest.json").isFile) parent = parent.parentFile
            if (parent.toPath() == bundles) bad("$.resources", "Sealed manifest is missing")
            if (generation != null && generation != parent) blocked("$.resources", "Multiple sealed generations require an owned combined preparation")
            if (generation == null) { checkedManifest = manifest(parent); generation = parent }
            val manifest = checkNotNull(checkedManifest)
            val relative = parent.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            val records = manifest.getJSONArray("records")
            val record = (0 until records.length()).map { records.getJSONObject(it) }.singleOrNull { it.getString("file") == relative }
                ?: bad("$.resources", "Resource is not in the sealed manifest")
            val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { it.readBounded(64 * 1024 * 1024) }
            val hash = runtimeSha256(bytes)
            if (record.getLong("bytes") != bytes.size.toLong() || record.getString("sha256") != hash) bad("$.resources", "Sealed resource content changed")
            RuntimeHandoffResource(relative, file.path, hash)
        }
        assets to generation
    }

    private fun manifest(directory: File): JSONObject {
        val manifest = JSONObject(read(checkedFile(File(directory, "manifest.json")), 1024 * 1024))
        if (manifest.getInt("schema") != 1 || manifest.getString("coreVersion") != "meow-rs/0.26.1/$MEOW_RUNTIME_CORE_SHA" ||
            manifest.getString("profileId") != directory.parentFile.name || manifest.getString("generation") != directory.name ||
            !manifest.getString("sourceSha256").matches(Regex("[0-9a-f]{64}"))) bad("$.resources", "Sealed source identity mismatch")
        for ((name, key) in listOf("config.yaml" to "configSha256", "input.yaml" to "compiledInputSha256")) {
            val file = checkedFile(File(directory, name))
            val hash = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { runtimeSha256(it.readBounded(4 * 1024 * 1024)) }
            if (manifest.getString(key) != hash) bad("$.resources", "Sealed configuration lineage changed")
        }
        val records = manifest.getJSONArray("records")
        if (records.length() > 4096) bad("$.resources", "Sealed manifest exceeds file budget")
        var total = 0L
        val names = mutableSetOf<String>()
        repeat(records.length()) { index ->
            val row = records.getJSONObject(index)
            val name = row.getString("file")
            if (name.startsWith('/') || name.split('/').any { it.isEmpty() || it == "." || it == ".." } || !names.add(name)) bad("$.resources", "Unsafe or duplicate manifest path")
            val file = checkedFile(File(directory, name))
            if (!file.toPath().startsWith(directory.toPath())) bad("$.resources", "Manifest path leaves sealed generation")
            val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { it.readBounded(64 * 1024 * 1024) }
            total += bytes.size
            if (total > 256L * 1024 * 1024 || row.getLong("bytes") != bytes.size.toLong() || row.getString("sha256") != runtimeSha256(bytes)) bad("$.resources", "Sealed resource content changed")
        }
        return manifest
    }

    private fun checkedFile(file: File): File {
        if (!file.isAbsolute || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.absoluteFile.toPath() != file.toPath().toRealPath() ||
            !file.toPath().startsWith(home.toPath())) bad("$.path", "Expected a canonical private-home file without redirects")
        return file
    }

    private fun read(file: File, max: Int) = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
        val bytes = input.readBounded(max)
        Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
    private fun matches(runtime: MeowSetupRuntime?, snapshot: MeowRuntimeSnapshot) = runtime != null && runtime.revision == snapshot.revision &&
        runtime.intentEpoch == snapshot.intentEpoch && runtime.compiledSha256 == snapshot.handoff.compiledSha256 &&
        runtime.serviceOwner > 0 && runtime.sessionGeneration > 0 && runtime.coreEpoch.toULongOrNull()?.let { it > 0uL && it.toString() == runtime.coreEpoch } == true
    private fun notification(row: JSONObject) = JSONObject().apply { listOf("stopTip", "startTip", "localNetworkTip", "currentProfileName", "stopText", "onlyStatisticsProxy", "showStopAction").forEach { put(it, row.opt(it) ?: if (it == "showStopAction") true else JSONObject.NULL) } }
    private fun json(value: Map<String, Any?>) = JSONObject(value).also { if (it.toString().toByteArray().size > 128 * 1024) bad("$", "Projection exceeds byte budget") }
    private fun map(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key -> when (val child = value.get(key)) { JSONObject.NULL -> null; is JSONObject -> map(child); else -> child } }
    private fun exact(row: JSONObject, allowed: Set<String>, path: String, optional: Set<String> = emptySet()) {
        row.keys().forEach { if (it !in allowed) bad("$path.$it", "Unknown field") }
        (allowed - optional).forEach { if (!row.has(it) || row.isNull(it)) bad("$path.$it", "Missing field") }
    }
    private fun objectAt(row: JSONObject, key: String, path: String) = row.opt(key) as? JSONObject ?: bad(path, "Expected object")
    private fun text(row: JSONObject, key: String, path: String) = row.opt(key) as? String ?: bad(path, "Expected string")
    private fun flag(row: JSONObject, key: String, path: String) = row.opt(key) as? Boolean ?: bad(path, "Expected boolean")
    private fun strings(row: JSONObject, key: String, path: String): List<String> { val list = row.opt(key) as? JSONArray ?: bad(path, "Expected list"); return List(list.length()) { list.opt(it) as? String ?: bad("$path[$it]", "Expected string") } }
    private fun integer(row: JSONObject, key: String, path: String, min: Int, max: Int): Int { val value = row.opt(key); if (value !is Int && value !is Long) bad(path, "Expected integer"); return (value as Number).toLong().takeIf { it in min.toLong()..max.toLong() }?.toInt() ?: bad(path, "Integer out of range") }
    private fun bad(path: String, reason: String): Nothing = throw MeowConfigProtocolException("bad_argument", "$path: $reason", mapOf("field" to path))
    private fun blocked(path: String, reason: String): Nothing = throw MeowConfigProtocolException("implementation_blocked", "$path: $reason", mapOf("field" to path, "coreSha" to MEOW_RUNTIME_CORE_SHA))
    companion object { private val updateKeys = setOf("tun", "mixed-port", "allow-lan", "find-process-mode", "mode", "log-level", "ipv6", "tcp-concurrent", "external-controller", "secret", "unified-delay", "authentication", "geo-auto-update", "geo-update-interval", "geox-url", "skip-cert-verify") }
}

internal fun meowNeedsResourceCapture(root: Map<*, *>): Boolean {
    fun source(value: Any?) = value is String && value.isNotEmpty()
    if (source(root["aerove-native-resource-manifest"])) return true
    if (listOf("proxy-providers", "rule-providers").any { namespace ->
        (root[namespace] as? Map<*, *>)?.values?.any { (it as? Map<*, *>)?.get("type") in setOf("file", "http") } == true
    }) return true
    val geoKeys = setOf("mmdb", "asn-mmdb", "geosite", "mmdb-download-url", "asn-mmdb-download-url", "geosite-download-url")
    if (geoKeys.any { source(root[it]) }) return true
    val geo = root["geodata"] as? Map<*, *>
    if (geo != null && (listOf("mmdb-path", "asn-path", "geosite-path").any { source(geo[it]) } ||
        (geo["url"] as? Map<*, *>)?.let { urls -> listOf("mmdb", "asn", "geosite").any { source(urls[it]) } } == true)) return true
    return (root["proxies"] as? List<*>)?.any { raw ->
        (raw as? Map<*, *>)?.let { row -> listOf("tls-cert", "tls-key", "ca").any { source(row[it]) } } == true
    } == true
}
