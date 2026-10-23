package dev.aerove.lite

import android.app.Application
import dev.aerove.lite.core.NativeCore
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

    internal val nativeLifecycle = Mutex()
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
        CompatibilityValidator(NativeConfigValidator { NativeCore.validate(it) }, File(filesDir, "runtime"))
    }
    val rulePayloadInspector by lazy {
        dev.aerove.lite.data.NativeJsonRulePayloadInspector(
            { path -> NativeCore.inspectRuleSet(path) },
            { path, behavior, format -> NativeCore.dumpRuleSet(path, behavior, format) })
    }
    val resourceBundles by lazy {
        ResourceBundleManager(File(filesDir, "runtime"), profileValidator, NativeResourceValidator { kind, path, behavior, format ->
            NativeCore.validateResource(kind, path, behavior, format)
        }, downloader = resourceDownloader, offlineRules = rulePayloadInspector)
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
            NativeResourceValidator { kind, path, behavior, format -> NativeCore.validateResource(kind, path, behavior, format) },
            resourceDownloader, { checkNotNull(settings.changes.value) { "Settings have not loaded" }.geoResources })
    }

    val overrideRepository by lazy { dev.aerove.lite.overrides.OverrideRepository(filesDir) }
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
    val profiles: ProfileRepository by lazy {
        ProfileRepository(filesDir, profileValidator, downloader = subscriptionDownloader, resources = resourceBundles,
            sourcePolicy = profileSourcePolicy, sourceDecoder = profileSourceDecoder, globalResources = builderResourceLibrary)
    }
    val settings by lazy { LiteSettingsStore(filesDir) }
    private val mutableVpn = MutableStateFlow(VpnSnapshot())
    private var owner = 0L
    val vpn = mutableVpn.asStateFlow()

    @Synchronized
    internal fun registerVpnOwner(): Long = ++owner

    @Synchronized
    internal fun publishVpn(owner: Long, snapshot: VpnSnapshot) {
        if (this.owner == owner) mutableVpn.value = snapshot
    }
}
