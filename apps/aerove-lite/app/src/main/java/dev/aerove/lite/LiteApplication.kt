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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (android.os.Process.isIsolated() || Application.getProcessName() != packageName) return
        dev.aerove.lite.regexp2.Regexp2Filters.install(applicationContext)
    }

    internal val physicalNetworks by lazy { dev.aerove.lite.platform.PhysicalNetworkMonitor(applicationContext, providerResetOwner::network) }

    internal val nativeLifecycle = Mutex()
    internal val ownedStarts = dev.aerove.lite.vpn.OwnedStartRegistry()
    private val pendingStarts = java.util.concurrent.atomic.AtomicLong()
    fun startIntentEpoch(): Long = pendingStarts.get()
    fun invalidatePendingStarts() { pendingStarts.incrementAndGet() }
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
    val resourceBundles by lazy {
        ResourceBundleManager(File(filesDir, "runtime"), profileValidator, NativeResourceValidator { kind, path, behavior, format ->
            dev.aerove.lite.core.JniResourceFetch.offlineRead { MeowNativeCore.validateResource(it, kind, path, behavior, format) }
        }, downloader = resourceDownloader, offlineRules = rulePayloadInspector,
            providerDownloads = dev.aerove.lite.data.ProviderDownloadFactory { context ->
                dev.aerove.lite.core.JniResourceFetch.factory(this).open(context)
            })
    }
    val builderResourceLibrary by lazy { dev.aerove.lite.profilebuilder.BuilderResourceLibrary(filesDir, subscriptionDownloader) }
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
    val vpn = mutableVpn.asStateFlow()

    @Synchronized
    internal fun registerVpnOwner(): Long = ++owner

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
