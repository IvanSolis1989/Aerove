package dev.aerove.lite

import android.app.Application
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.data.ResourceDownloader
import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated() || Application.getProcessName() != packageName) return
        dev.aerove.lite.regexp2.Regexp2Filters.install(applicationContext)
    }

    internal val physicalNetworks by lazy { dev.aerove.lite.platform.PhysicalNetworkMonitor(applicationContext, providerResetOwner::network) }

    val usesSharedFlutterClient: Boolean
        get() = packageManager.getLaunchIntentForPackage(packageName)?.component?.className == "dev.aerove.lite.FlutterMainActivity"

    private val externalSharedCommands by lazy {
        dev.aerove.lite.flutter.createMeowFlutterCommandFacade(this,
            canPrepare = { !maintenanceActive }, canConnect = { !maintenanceActive })
    }

    internal fun verifySharedStartSource(snapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot,
        permit: dev.aerove.lite.flutter.ProviderSourcePermit? = null): org.json.JSONObject {
        check(runtimeHandoffs.providerSourceAllowsAdmission(snapshot, permit)) { "provider_source_recovery_required" }
        val phase=dev.aerove.lite.flutter.NativeLiteDataAuthority.phase(filesDir)
        check(phase == "shared" || phase == null && !hasLegacyBusinessSource()) { "native_data_migration_required" }
        val proof=org.json.JSONObject(snapshot.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")
        val config=dev.aerove.lite.flutter.verifyNativeLiteBusinessSource(this,proof)
        check(config.getJSONObject("appSettingProps").getInt("acceptedDisclaimerVersion") >= 1) { "shared_disclaimer_required" }
        check(!config.isNull("currentProfileId")) { "shared_profile_required" }
        return config
    }

    suspend fun startSharedExternal(autoRun: Boolean, expectedStartEpoch: Long): Boolean = startSharedExternalOwned(autoRun, expectedStartEpoch, null)

    private suspend fun startSharedExternalOwned(autoRun: Boolean, expectedStartEpoch: Long, permit: dev.aerove.lite.flutter.ProviderSourcePermit?): Boolean {
        val requested=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            nativeLifecycle.withLock {
                check(usesSharedFlutterClient && !maintenanceActive && startIntentEpoch() == expectedStartEpoch) { "shared_start_superseded" }
                val snapshot=checkNotNull(runtimeHandoffs.readCurrent()) { "shared_runtime_configuration_required" }
                if (autoRun && runtimeHandoffs.providerSourceKeepStopped()) return@withLock null
                val config=verifySharedStartSource(snapshot, permit)
                if(autoRun && !config.getJSONObject("appSettingProps").getBoolean("autoRun")) return@withLock null
                val vpnRequired=org.json.JSONObject(snapshot.handoff.vpnOptionsJson).getBoolean("enable")
                check(!vpnRequired || android.net.VpnService.prepare(this@LiteApplication) == null) { "shared_vpn_authorization_required" }
                check(vpnRequired || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) { "shared_notification_authorization_required" }
                check(startIntentEpoch() == expectedStartEpoch) { "shared_start_superseded" }
                retireParkedGraph()
                runtimeHandoffs.beginStart(snapshot.revision,snapshot.intentEpoch, permit)
            }
        } ?: return false
        var sent=false
        try {
            sent=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                if(startIntentEpoch() != expectedStartEpoch) return@withContext false
                val request=externalSharedCommands.prepareOwnedConnect(dev.aerove.lite.flutter.MeowFlutterRuntimeBinding(requested.revision,requested.intentEpoch, requested.sourcePermit)) ?: return@withContext false
                externalSharedCommands.connectOwned(request)
            }
            return sent
        } finally {
            if(!sent) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val current=runtimeHandoffs.readCurrent()
                if(current?.revision == requested.revision && current.intentEpoch == requested.intentEpoch && current.userRequested) runtimeHandoffs.invalidate(requested.intentEpoch)
            }
        }
    }

    private val legacyMigration by lazy {
        dev.aerove.lite.flutter.MeowLegacyMigrationOwner(filesDir,
            dev.aerove.lite.flutter.MeowLegacyMigrationExport(filesDir, profiles, settings, overrideRepository),
            verifyTarget = { receipt, digest -> dev.aerove.lite.flutter.verifyNativeLiteTarget(this, receipt, digest) })
    }
    private fun hasLegacyBusinessSource() = listOf("profiles/index.json", "settings/settings.json", "settings/overrides.json",
        "settings/builder-resources.yaml", "backups/webdav.json").any { File(filesDir, it).exists() }
    suspend fun beginLegacyMigration(session: String): Map<String, Any?> {
        if (!hasLegacyBusinessSource() && dev.aerove.lite.flutter.NativeLiteDataAuthority.phase(filesDir) == null)
            return mapOf("status" to "absent")
        return withMaintenance { nativeLifecycle.withLock {
            if (dev.aerove.lite.flutter.NativeLiteDataAuthority.phase(filesDir) != "shared") requireMigrationStopped()
            legacyMigration.begin(session)
        } }
    }
    private fun requireMigrationStopped() {
        check(vpn.value.phase == VpnPhase.Stopped && vpn.value.endpoint == null && flutterRuntimeIdentity.value == null) {
            "migration_runtime_owner_active"
        }
    }
    suspend fun validateLegacyMigration(lease: String, digest: String) = withMaintenance { nativeLifecycle.withLock {
        requireMigrationStopped(); legacyMigration.validate(lease, digest)
    } }
    suspend fun commitLegacyMigration(lease: String, receipt: String, digest: String) = withMaintenance { nativeLifecycle.withLock {
        requireMigrationStopped(); legacyMigration.commit(lease, receipt, digest)
    } }
    suspend fun prepareLegacyMigrationFiles(lease: String, plan: String, digest: String) = withMaintenance { nativeLifecycle.withLock { requireMigrationStopped(); legacyMigration.prepareFiles(lease,plan,digest) } }
    suspend fun confirmLegacyMigration(source: String, receipt: String) = legacyMigration.confirmTransferred(source,receipt)
    fun releaseLegacyMigration(lease: String) = legacyMigration.release(lease)
    fun releaseLegacyMigrationSession(session: String) {
        runtimeScope.launch(kotlinx.coroutines.Dispatchers.IO) { withMaintenance { legacyMigration.releaseSession(session) } }
    }

    val runtimeHandoffs by lazy {
        dev.aerove.lite.flutter.RuntimeHandoffStore(filesDir, validateProjection = {
            dev.aerove.lite.flutter.MeowRuntimeSnapshotSettings.validate(it)
        }, validateSource = { handoff ->
            if (usesSharedFlutterClient) {
                val proof = org.json.JSONObject(handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")
                dev.aerove.lite.flutter.verifyNativeLiteBusinessSource(this, proof)
            }
        })
    }
    internal val nativeLifecycle = Mutex()
    private val parkedGraphGate = Mutex()
    private val runtimeScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default) }
    private val parkedGraph by lazy {
        dev.aerove.lite.flutter.MeowParkedGraphOwner(runtimeHandoffs, claimOwner = {
            check(vpn.value.phase == VpnPhase.Stopped && vpn.value.endpoint == null && !maintenanceActive) { "runtime_service_not_stopped" }
            claimGraphOwner()
        }, ownsOwner = ::ownsGraphOwner, backendFactory = { graphOwner, testUrl, current ->
            dev.aerove.lite.vpn.MeowTunnelBackend(dev.aerove.lite.vpn.MeowAndroidHost(null, this,
                capturePhysical = {
                    val manager = getSystemService(android.net.ConnectivityManager::class.java)
                    dev.aerove.lite.vpn.captureMeowPhysical(manager) {
                        manager.allNetworks.firstOrNull { network -> manager.getNetworkCapabilities(network)?.let {
                            it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        } == true }
                    }
                }, initialListening = { false }, captureOwnerIntent = { current }, graphOnly = true, graphTestUrl = { testUrl }, geoOwner = { graphOwner }))
        }, publish = { graphOwner, query ->
            publishFlutterRuntimeIdentity(graphOwner, query?.identity)
        }, verifyMetadata = { previous, params -> dev.aerove.lite.flutter.meowVerifyMetadataSource(this,previous,params) })
    }
    internal suspend fun retireParkedGraph() = parkedGraph.retire()
    internal suspend fun quiesceProviderSourceCandidate() {
        retireParkedGraph()
        val service = synchronized(this) { flutterRuntimeOwner?.takeIf { it.first == owner }?.second }
        service?.quiesceProviderSourceCandidate()
    }
    internal suspend fun restoreProviderSourceGraph(snapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot): dev.aerove.lite.flutter.RuntimeAppliedIdentity? {
        check(runtimeHandoffs.providerSourceAllowsAdmission(snapshot, snapshot.sourcePermit)) { "provider_source_recovery_required" }
        if (!snapshot.userRequested) return ensureParkedGraph(snapshot)
        val service = synchronized(this) { flutterRuntimeOwner?.takeIf { it.first == owner }?.second }
        if (service != null) return service.restoreProviderSourceGraph(snapshot)
        check(startSharedExternalOwned(false, startIntentEpoch(), snapshot.sourcePermit)) { "provider_source_restore_start_failed" }
        return kotlinx.coroutines.withTimeout(45_000) {
            flutterRuntimeIdentity.first { identity -> identity != null && identity.revision == snapshot.revision && runtimeHandoffs.isGraphCurrent(identity) }
        }
    }
    internal suspend fun recoverProviderSourceBeforeAdmission(arguments: Map<String, Any?>): Map<String, Any?> {
        require(arguments["operation"] in setOf("query", "abort", "sourceFinalized"))
        return dev.aerove.lite.flutter.MeowProviderSourceTransactions(filesDir.canonicalFile, runtimeHandoffs,
            verifySource = { proof ->
                val verified = dev.aerove.lite.flutter.verifyNativeLiteBusinessSourceReceipt(this, proof)
                dev.aerove.lite.flutter.nativeLiteProviderOwnerKey(dev.aerove.lite.flutter.nativeLiteCurrentProfileId(verified.config))
            }, actualIdentity = {
                val identity = runtimeHandoffs.readAppliedIdentity()
                val endpoint = flutterQueryGraph()?.endpoint ?: vpn.value.endpoint
                if (identity == null || endpoint == null || !flutterRuntimeIdentityIsCurrent(identity) || !runtimeHandoffs.isGraphCurrent(identity)) null else {
                    val epoch = dev.aerove.lite.data.CoreApiClient(dev.aerove.lite.data.ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot().coreEpoch.toString() }
                    identity.takeIf { epoch == it.proof.coreEpoch && flutterRuntimeIdentityIsCurrent(it) && runtimeHandoffs.isGraphCurrent(it) }
                }
            },
            apply = { _, _, _ -> error("provider_source_apply_requires_attached_setup") },
            quiesce = ::quiesceProviderSourceCandidate, restore = ::restoreProviderSourceGraph).invoke(arguments)
    }
    suspend fun ensureParkedGraph(snapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot): dev.aerove.lite.flutter.RuntimeAppliedIdentity = parkedGraphGate.withLock {
        val existing = parkedGraph.queryGraph()
        if (existing != null && existing.identity.revision == snapshot.revision && existing.identity.intentEpoch == snapshot.intentEpoch &&
            runtimeHandoffs.isGraphCurrent(existing.identity)) return@withLock existing.identity
        parkedGraph.applyStaged(snapshot)
    }
    fun flutterQueryGraph(): dev.aerove.lite.flutter.MeowQueryGraph? = parkedGraph.queryGraph()
    internal fun restoreParkedGraphAfterService(expectedOwner: Long) {
        runtimeScope.launch {
            if (!ownsVpnOwner(expectedOwner) || maintenanceActive || vpn.value.phase != VpnPhase.Stopped) return@launch
            val snapshot = runtimeHandoffs.readCurrent()?.takeIf { !it.userRequested } ?: return@launch
            if (!runtimeHandoffs.providerSourceAllowsAdmission(snapshot)) return@launch
            if (runtimeHandoffs.providerSourceKeepStopped()) return@launch
            try { ensureParkedGraph(snapshot) }
            catch (failure: Exception) { android.util.Log.w("MeowGraph", "Stopped runtime graph could not be restored", failure) }
        }
    }
    internal val ownedStarts = dev.aerove.lite.vpn.OwnedStartRegistry()
    private val pendingStarts = java.util.concurrent.atomic.AtomicLong()
    fun startIntentEpoch(): Long = pendingStarts.get()
    fun invalidatePendingStarts() { pendingStarts.incrementAndGet() }
    private var flutterRuntimeOwner: Pair<Long, dev.aerove.lite.flutter.MeowFlutterRuntime>? = null
    private val mutableFlutterRuntimeIdentity = MutableStateFlow<dev.aerove.lite.flutter.RuntimeAppliedIdentity?>(null)
    val flutterRuntimeIdentity = mutableFlutterRuntimeIdentity.asStateFlow()
    @Synchronized internal fun publishFlutterRuntimeIdentity(owner: Long, identity: dev.aerove.lite.flutter.RuntimeAppliedIdentity?) {
        if (this.owner != owner && graphOwner != owner) return
        val prior = mutableFlutterRuntimeIdentity.value
        if (identity == null && prior?.proof?.serviceOwner != owner) return
        if (identity == null || prior == null || !dev.aerove.lite.flutter.meowSameRuntimeIdentity(prior, identity))
            mutableFlutterRuntimeIdentity.value = identity
    }
    @Synchronized internal fun registerFlutterRuntime(owner: Long, runtime: dev.aerove.lite.flutter.MeowFlutterRuntime) {
        if (this.owner == owner) flutterRuntimeOwner = owner to runtime
    }
    @Synchronized internal fun clearFlutterRuntime(owner: Long) {
        if (flutterRuntimeOwner?.first == owner) {
            flutterRuntimeOwner = null
            publishFlutterRuntimeIdentity(owner, null)
        }
    }
    @Synchronized private fun flutterRuntime(): dev.aerove.lite.flutter.MeowFlutterRuntime =
        parkedGraph.takeIf { it.queryGraph() != null }
            ?: checkNotNull(flutterRuntimeOwner?.takeIf { it.first == owner }?.second) { "runtime_service_owner_unavailable" }
    @Synchronized fun flutterRuntimeIdentityIsCurrent(identity: dev.aerove.lite.flutter.RuntimeAppliedIdentity): Boolean =
        parkedGraph.identityIsCurrent(identity) || flutterRuntimeOwner?.takeIf { it.first == owner }?.second?.identityIsCurrent(identity) == true
    @Synchronized fun flutterHasRequestedIntent(): Boolean =
        flutterRuntimeOwner?.takeIf { it.first == owner }?.second?.hasRequestedIntent() == true
    suspend fun setFlutterRuntimeListening(expected: dev.aerove.lite.flutter.MeowSetupRuntime, enabled: Boolean) = flutterRuntime().setListening(expected, enabled)
    suspend fun applyFlutterRuntimeUpdate(expected: dev.aerove.lite.flutter.MeowSetupRuntime, projection: dev.aerove.lite.flutter.MeowSetupProjection) = flutterRuntime().applyUpdate(expected, projection)
    suspend fun refreshFlutterRuntimeSetup(expected: dev.aerove.lite.flutter.MeowSetupRuntime, setupParamsJson: String) = flutterRuntime().refreshSetup(expected, setupParamsJson)
    suspend fun applyFlutterGeo(expected: dev.aerove.lite.flutter.MeowSetupRuntime, transactionId: String,
        candidate: dev.aerove.lite.data.MeowGeoCandidate, resources: dev.aerove.lite.data.FlutterRuntimeResources.Geo,
        projection: dev.aerove.lite.flutter.RuntimeHandoff, rawSelectedMap: Map<String,String>, admit: suspend () -> Boolean) =
        flutterRuntime().applyGeo(expected,transactionId,candidate,resources,projection,rawSelectedMap,admit)
    suspend fun flutterGeoStatus(expected: dev.aerove.lite.flutter.MeowSetupRuntime, transactionId: String, abort: Boolean) =
        flutterRuntime().geoStatus(expected,transactionId,abort)
    suspend fun flutterRuntimeDefaultTestUrl(expected: dev.aerove.lite.flutter.MeowSetupRuntime) = flutterRuntime().defaultTestUrl(expected)
    suspend fun replaceFlutterRuntimeGraph(snapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot) = flutterRuntime().applyStaged(snapshot)
    private val maintenanceGate = Mutex()
    @Volatile var maintenanceActive: Boolean = false
        private set
    private data class MaintenanceHooks(val owner: Long,
        val stop: suspend () -> dev.aerove.lite.vpn.MaintenanceRuntimeContext,
        val resume: suspend (dev.aerove.lite.vpn.MaintenanceRuntimeContext, String?, LiteSettings?, Boolean) -> Unit)
    private var maintenanceHooks: MaintenanceHooks? = null

    suspend fun <T> withMaintenance(action: suspend () -> T): T = maintenanceGate.withLock {
        maintenanceActive = true
        try { action() } finally { maintenanceActive = false }
    }

    @Synchronized
    internal fun registerMaintenanceStop(owner: Long,
        stop: suspend () -> dev.aerove.lite.vpn.MaintenanceRuntimeContext,
        resume: suspend (dev.aerove.lite.vpn.MaintenanceRuntimeContext, String?, LiteSettings?, Boolean) -> Unit) {
        if (this.owner == owner) maintenanceHooks = MaintenanceHooks(owner, stop, resume)
    }

    @Synchronized
    internal fun clearMaintenanceStop(owner: Long) { if (maintenanceHooks?.owner == owner) maintenanceHooks = null }

    private data class RuleReloadHook(val owner: Long,
        val reload: suspend (dev.aerove.lite.vpn.CoreEndpoint, dev.aerove.lite.data.PreparedProfile, () -> Boolean) -> Boolean)
    private var ruleReloadHook: RuleReloadHook? = null
    @Synchronized internal fun registerRuleReload(owner: Long,
        reload: suspend (dev.aerove.lite.vpn.CoreEndpoint, dev.aerove.lite.data.PreparedProfile, () -> Boolean) -> Boolean) {
        if (this.owner == owner) ruleReloadHook = RuleReloadHook(owner, reload)
    }
    @Synchronized internal fun clearRuleReload(owner: Long) { if (ruleReloadHook?.owner == owner) ruleReloadHook = null }
    suspend fun reloadRules(endpoint: dev.aerove.lite.vpn.CoreEndpoint, next: dev.aerove.lite.data.PreparedProfile,
        current: () -> Boolean): Boolean {
        val hook = synchronized(this) { ruleReloadHook?.takeIf { it.owner == owner } } ?: return false
        fun owns() = !maintenanceActive && vpn.value.endpoint === endpoint && current() && synchronized(this) { ruleReloadHook === hook && owner == hook.owner }
        if (!owns()) return false
        return hook.reload(endpoint, next, ::owns) && owns()
    }

    private data class ModeCleanupHook(val owner: Long,
        val stop: suspend (dev.aerove.lite.vpn.CoreEndpoint, String) -> Boolean)
    private var modeCleanupHook: ModeCleanupHook? = null
    @Synchronized internal fun registerModeCleanup(owner: Long,
        stop: suspend (dev.aerove.lite.vpn.CoreEndpoint, String) -> Boolean) {
        if (this.owner == owner) modeCleanupHook = ModeCleanupHook(owner, stop)
    }
    @Synchronized internal fun clearModeCleanup(owner: Long) { if (modeCleanupHook?.owner == owner) modeCleanupHook = null }
    suspend fun stopModeRuntime(endpoint: dev.aerove.lite.vpn.CoreEndpoint, reason: String): Boolean {
        val hook = synchronized(this) { modeCleanupHook?.takeIf { it.owner == owner } }
        if (hook == null) {
            check(vpn.value.endpoint !== endpoint) { "Mode cleanup owner is unavailable" }
            return false
        }
        return hook.stop(endpoint, reason)
    }

    suspend fun stopForMaintenance(): dev.aerove.lite.vpn.MaintenanceRuntimeContext {
        retireParkedGraph()
        val hooks = synchronized(this) { maintenanceHooks?.takeIf { it.owner == owner } }
        if (hooks != null) return hooks.stop()
        check(vpn.value.phase == VpnPhase.Stopped && vpn.value.endpoint == null) { "VPN owner has not stopped" }
        return dev.aerove.lite.vpn.MaintenanceRuntimeContext(false, null, null)
    }

    suspend fun resumeAfterMaintenance(context: dev.aerove.lite.vpn.MaintenanceRuntimeContext,
        profileId: String?, settings: LiteSettings?, resume: Boolean) {
        val hooks = synchronized(this) { maintenanceHooks?.takeIf { it.owner == owner && it.owner == context.ownerToken } }
        if (hooks == null) {
            check(!resume || !context.userRequested) { "Runtime owner changed during maintenance" }
            return
        }
        hooks.resume(context, profileId, settings, resume)
    }

    val requestPolicy by lazy {
        settings.requestPolicy(dev.aerove.lite.requests.productUserAgent("AEROVE Lite", BuildConfig.VERSION_NAME))
    }
    private val groupIconScope by lazy { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO) }
    private val groupIconOwner = lazy {
        dev.aerove.lite.images.NetworkGroupIconLoader(okhttp3.OkHttpClient.Builder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 8 }).build(), requestPolicy, groupIconScope,
            File(cacheDir, "group-icons"), routeClient = { client, url ->
                dev.aerove.lite.integration.runtimeAssetHttpClient(client, vpn.value, url)
            })
    }
    internal val groupIcons: dev.aerove.lite.images.GroupIconLoader get() = groupIconOwner.value
    suspend fun closeGroupIcons() { if (groupIconOwner.isInitialized()) groupIconOwner.value.closeAndJoin() }

    val subscriptionDownloader by lazy { SubscriptionDownloader(requestPolicy) }
    val resourceDownloader by lazy { ResourceDownloader(requestPolicy) }
    val profileValidator by lazy {
        CompatibilityValidator.forMeow(NativeConfigValidator { yaml ->
            dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.validate(it, yaml) }
        }, File(filesDir, "runtime"),
            dev.aerove.lite.data.NativePreparedConfigValidator { yaml, root ->
                dev.aerove.lite.core.JniResourceFetch.preparedValidator(this).validate(yaml, root)
            }, dev.aerove.lite.data.NativePreparedRuleValidator { path, behavior, format ->
                dev.aerove.lite.data.RuleResourcePreflight.parse(
                    dev.aerove.lite.core.JniResourceFetch.preflightRuleResource(path, behavior, format), behavior, format)
            }, MeowNativeCore::readVerifiedFeatures)
    }
    val rulePayloadInspector by lazy {
        dev.aerove.lite.data.NativeJsonRulePayloadInspector(
            { path -> dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.inspectRuleSet(it, path) } },
            { path, behavior, format -> dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.dumpRuleSet(it, path, behavior, format) } })
    }
    val resourceBundles by lazy { resourceBundleManager(resourceDownloader) }
    private fun resourceBundleManager(downloader: ResourceDownloader) =
        ResourceBundleManager(File(filesDir, "runtime"), profileValidator, NativeResourceValidator { kind, path, behavior, format ->
            dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.validateResource(it, kind, path, behavior, format) }
        }, downloader = downloader, offlineRules = rulePayloadInspector,
            providerDownloads = dev.aerove.lite.data.ProviderDownloadFactory { context ->
                dev.aerove.lite.core.JniResourceFetch.factory(this).open(context)
            })
    val builderResourceLibrary by lazy { dev.aerove.lite.profilebuilder.BuilderResourceLibrary(filesDir, subscriptionDownloader) }
    val flutterResourceSealer by lazy {
        dev.aerove.lite.data.FlutterResourceSealer(resourceBundleManager(ResourceDownloader()), flutterDefaultUserAgent)
    }
    val flutterDefaultUserAgent get() = dev.aerove.lite.requests.productUserAgent("AEROVE Lite", BuildConfig.VERSION_NAME)
    val globalRulePayloads by lazy {
        dev.aerove.lite.data.GlobalRulePayloadRepository(filesDir, builderResourceLibrary, resourceBundles,
            rulePayloadInspector, profiles, resourceDownloader)
    }
    val resourceLibrary by lazy {
        dev.aerove.lite.resources.ResourceManagementLibraryRepository(builderResourceLibrary, resourceBundles, globalRulePayloads)
    }
    val geoResourceCatalog by lazy {
        dev.aerove.lite.resources.GeoResourceCatalog(File(filesDir, "runtime"), profileValidator,
            NativeResourceValidator { kind, path, behavior, format -> dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.validateResource(it, kind, path, behavior, format) } },
            resourceDownloader, { checkNotNull(settings.changes.value) { "Settings have not loaded" }.geoResources })
    }

    val overrideRepository by lazy { dev.aerove.lite.overrides.OverrideRepository(filesDir) {
        assets.open(dev.aerove.lite.overrides.BundledMeowScript.ASSET).use { it.readBytes() }
    } }
    val overridePipeline by lazy {
        dev.aerove.lite.overrides.OverridePipeline(overrideRepository, dev.aerove.lite.overrides.ScriptEngine { script, config ->
            dev.aerove.lite.overrides.NativeScriptEngine().evaluate(script, config)
        })
    }
    val profileSourcePolicy: dev.aerove.lite.data.ProfileSourcePipeline by lazy {
        dev.aerove.lite.data.ProfileSourcePipeline(builderResourceLibrary, overridePipeline, settings::read) { id ->
            dev.aerove.lite.data.ProfileDnsSelection(
                overrideRepository.snapshot().profiles.firstOrNull { it.profileId == id }?.dnsPreferences,
                profiles.profileSummary(id)?.custom == true,
            )
        }
    }
    val profileSourceDecoder by lazy {
        dev.aerove.lite.profilesource.AgeProfileSourceDecoder(dev.aerove.lite.profilesource.AgeDecryptTransport { ciphertext, identities ->
            dev.aerove.lite.regexp2.IsolatedClientHelper.decryptAge(ciphertext, identities)
        })
    }
    private val profilesDelegate = lazy {
        ProfileRepository(filesDir, profileValidator, downloader = subscriptionDownloader, resources = resourceBundles,
            sourcePolicy = profileSourcePolicy, sourceDecoder = profileSourceDecoder, globalResources = builderResourceLibrary)
    }
    val profiles: ProfileRepository get() = profilesDelegate.value
    private val providerResetOwner = ProviderResourceResetOwner(profilesDelegate)
    val settings by lazy { LiteSettingsStore(filesDir) }
    private val mutableVpn = MutableStateFlow(VpnSnapshot())
    private var owner = 0L
    private var graphOwner = 0L
    private var nextRuntimeOwner = 0L
    val vpn = mutableVpn.asStateFlow()

    @Synchronized
    internal fun registerVpnOwner(): Long {
        mutableFlutterRuntimeIdentity.value = null
        graphOwner = 0L
        owner = ++nextRuntimeOwner
        return owner
    }

    @Synchronized private fun claimGraphOwner(): Long {
        check(vpn.value.phase == VpnPhase.Stopped && vpn.value.endpoint == null && !maintenanceActive) { "runtime_service_not_stopped" }
        graphOwner = ++nextRuntimeOwner
        return graphOwner
    }
    @Synchronized private fun ownsGraphOwner(expected: Long): Boolean = graphOwner == expected &&
        vpn.value.phase == VpnPhase.Stopped && vpn.value.endpoint == null && !maintenanceActive

    @Synchronized
    internal fun ownsVpnOwner(expected: Long): Boolean = owner == expected

    @Synchronized
    internal fun publishVpn(owner: Long, snapshot: VpnSnapshot) {
        if (this.owner == owner) {
            providerResetOwner.runtime(owner, snapshot)
            mutableVpn.value = snapshot.copy(serviceOwner = owner)
        }
    }
}

internal class ProviderResourceResetOwner(private val profiles: Lazy<ProfileRepository>) {
    private var networkRevision = 0L
    private data class SessionReceipt(val owner: Long, val generation: Long, val endpoint: dev.aerove.lite.vpn.CoreEndpoint)
    private var session: SessionReceipt? = null
    @Synchronized fun network(revision: Long) {
        if (networkRevision == revision) return
        networkRevision = revision
        if (profiles.isInitialized()) profiles.value.resetResourceBackoff()
    }
    @Synchronized fun runtime(owner: Long, snapshot: VpnSnapshot) {
        val endpoint = snapshot.endpoint ?: return
        if (snapshot.phase != VpnPhase.Running || snapshot.sessionGeneration <= 0L) return
        val next = SessionReceipt(owner, snapshot.sessionGeneration, endpoint)
        if (session == next) return
        session = next
        if (profiles.isInitialized()) profiles.value.resetResourceBackoff()
    }

}
