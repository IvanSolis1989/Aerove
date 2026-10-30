package dev.aerove.lite.vpn

import android.net.Network
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.ParcelFileDescriptor
import dev.aerove.lite.appaccess.AndroidApplicationLoader
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.core.*
import dev.aerove.lite.data.*
import dev.aerove.lite.networksettings.BoundControlPort
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.File
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Files
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Service adapter; construction alone neither establishes a VPN nor starts Native. */
class MeowAndroidHost(private val service: LiteVpnService, private val application: LiteApplication,
    private val capturePhysical: () -> CapturedMeowPhysical,
    private val initialListening: suspend (VpnPlan) -> Boolean,
    private val captureOwnerIntent: (VpnPlan) -> (() -> Boolean),
    private val verifiedNativeFeatures: () -> Set<String> = { MeowNativeCore.readVerifiedFeatures() },
) : MeowTunnelBackend.Host {
    private val gate = Any()
    private val retained = mutableSetOf<Resources>()

    override suspend fun acquire(plan: VpnPlan): MeowTunnelBackend.Lease = withContext(Dispatchers.IO) {
        val resources = Resources(TunOwnerAdmission(captureOwnerIntent(plan)))
        synchronized(gate) { check(retained.isEmpty()) { "Previous Meow Host resources remain owned" }; retained += resources }
        try {
            acquireMeowLifecycleLock(application.nativeLifecycle, resources) { resources.locked = true }
            if (plan.profileId.startsWith("flutter:")) {
                val snapshot = checkNotNull(application.runtimeHandoffs.readCurrent()) { "runtime_snapshot_missing" }
                check(snapshot.profileId == plan.profileId) { "runtime_start_superseded" }
                resources.runtimeSnapshot = application.runtimeHandoffs.admit(snapshot.revision, snapshot.intentEpoch)
                val relocated = FlutterRuntimeResources.create(application.filesDir, snapshot, resources::isCurrent)
                resources.flutterResources = relocated
                val projected = dev.aerove.lite.flutter.meowProjectGroupTestUrl(ConfigYaml.parse(relocated.yaml), service.startupFlutterTestUrl())
                val yaml = ConfigYaml.dump(projected)
                resources.preparedValue = PreparedProfile(plan.profileId, yaml, yaml, snapshot.revision,
                    snapshot.handoff.compiledSha256, relocated.readRoot, null)
            } else {
                check(!application.runtimeHandoffs.hasAuthority) { "runtime_snapshot_required" }
                resources.preparedValue = application.profiles.prepareForStart(plan.profileId)
                resources.profileLease = application.profiles.retain(resources.prepared)
            }
            val runtime = File(application.filesDir, "runtime").canonicalFile.apply { check(isDirectory || mkdirs()) }
            resources.prepared.resourceDirectory?.takeIf { resources.runtimeSnapshot == null }?.let { bundle ->
                check(bundle.canonicalFile.toPath().startsWith(File(runtime,"resources/bundles").toPath())) { "Meow resource root escapes private sealed bundles" }
            }
            if (resources.runtimeSnapshot == null)
                resources.ruleResources = SessionRuleResources.create(application.filesDir, resources.prepared, application.profiles, resources::isCurrent)
            val directory = File(runtime, "profiles/${resources.prepared.profileId}").canonicalFile
            check(directory.toPath().startsWith(File(runtime, "profiles").toPath())) { "Invalid runtime profile directory" }
            check(directory.isDirectory || directory.mkdirs())
            val port = selectTunnelControlPort(plan) {
                val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                object : BoundControlPort { override val port get() = socket.localPort; override fun close() = socket.close() }
            }
            val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val mode = runtimeMode(plan)
            resources.startupPlan = plan
            resources.controlPort = port
            val parked = !initialListening(plan)
            val captured = capturePhysical()
            resources.physicalValue = captured
            val physical = captured.network
            val dns = captured.dns
            resources.coreDnsEnabled = effectiveProfileDnsEnabled(resources.prepared)
            resources.builderDnsEnabled = plan.vpnDnsEnabled ?: resources.coreDnsEnabled
            val profileRanges = profileFakeIpRanges(resources.prepared.yaml)
            resources.coreReservedFakeIpRanges = vpnReservedRanges(profileRanges, resources.coreDnsEnabled, vpnBuilderIpv6(plan))
            resources.builderReservedFakeIpRanges = plan.vpnReservedFakeIpRanges?.toList()
                ?: vpnReservedRanges(profileRanges, resources.builderDnsEnabled, vpnBuilderIpv6(plan))
            check(resources.isCurrent()) { "Meow Host intent was superseded" }
            if (mode == NativeCore.RuntimeMode.Tun) resources.resolver = ConnectionOwnerResolver.forTun(service, resources.admission::admitted)
            if (mode == NativeCore.RuntimeMode.Tun && !parked) {
                resources.tun = establishAndroidTun(service, plan, port, resources.builderDnsEnabled, dns, resources.builderReservedFakeIpRanges) { physical }
                resources.admission.established()
            }
            val cache = File(runtime, "meow-cache/${UUID.randomUUID()}").canonicalFile
            check(cache.toPath().startsWith(File(runtime,"meow-cache").canonicalFile.toPath()) && cache.mkdirs()) { "Could not create private Meow cache" }
            resources.cache = cache
            val baseline = plan.builderSettings()
            val startup = if (resources.runtimeSnapshot != null) plan.settings else runtimePoliciesForStart(plan.settings, application.settings.read())
            resources.startupPolicies = MeowHostPolicyTarget.from(startup, resources.requestUserAgent)
            val composed = MeowRuntimeConfigComposer(runtime, resources.requestUserAgent, cache)
                .compose(resources.prepared, startup, resources.tun?.fd ?: -1, port, secret, mode, dns,
                    TunRuntimeOptions(baseline.network.mtu, baseline.network.dnsHijacking, baseline.ipv6), parked,
                    captured.networkHandle, verifiedNativeFeatures().toSet())
            resources.configValue = resources.ruleResources?.configure(composed) ?: composed.withResources(
                composed.engineYaml, checkNotNull(resources.prepared.resourceDirectory),
                resources.prepared.revision, resources.prepared.generation)
            val appliedFile = File(directory, "meow-${UUID.randomUUID()}.yaml")
            resources.file = appliedFile
            appliedFile.apply {
                writeText(resources.configuration.engineYaml)
                check(setReadable(false,false) && setReadable(true,true)) { "Could not protect Meow runtime configuration" }
            }
            check(resources.isCurrent()) { "Meow Host intent was superseded" }
            resources.transferred = true
            resources
        } catch (failure: Throwable) {
            resources.failedAcquire = true
            try { withContext(NonCancellable) { resources.release() } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    /** Only failed acquire receipts: a transferred lease must first join via MeowTunnelBackend. */
    suspend fun recoverFailedAcquires() {
        val pending = synchronized(gate) { retained.filter { it.failedAcquire && !it.transferred } }
        var failure: Throwable? = null
        for (item in pending) try { item.release() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private inner class Resources(val admission: TunOwnerAdmission) : MeowTunnelBackend.Lease {
        val cleanup = Mutex()
        val staged = mutableSetOf<Attachment>()
        inner class Attachment(val file: ParcelFileDescriptor) : MeowTunnelBackend.TunReceipt {
            override val descriptor get() = file.fd
        }
        var locked = false
        var transferred = false
        @Volatile var failedAcquire = false
        var sharedCleaned = false
        var runtimeSnapshot: dev.aerove.lite.flutter.MeowRuntimeSnapshot? = null
        val requestUserAgent get() = if (runtimeSnapshot != null) application.flutterDefaultUserAgent else application.requestPolicy.defaultUserAgent
        var profileLease: ProfileLease? = null
        var ruleResources: SessionRuleResources? = null
        var flutterResources: FlutterRuntimeResources? = null
        var tun: ParcelFileDescriptor? = null
        var file: File? = null
        var cache: File? = null
        var resolver: ConnectionOwnerResolver? = null
        var packageLoader: AndroidApplicationLoader? = null
        var markedUsable = false
        lateinit var preparedValue: PreparedProfile
        lateinit var configValue: MeowRuntimeConfig
        override val prepared get() = ruleResources?.prepared ?: preparedValue
        override val configuration get() = ruleResources?.configuration ?: configValue
        override val appliedConfiguration get() = AppliedConfigSnapshot(prepared.profileId, checkNotNull(file))
        override val protector: SocketProtector? get() = if(configuration.runtimeMode == NativeCore.RuntimeMode.Tun) SocketProtector(service::protect) else null
        override val connectionOwner: ConnectionOwnerLookup? get() = resolver
        override var builderDnsEnabled = false
        override var coreDnsEnabled = false
        override var builderReservedFakeIpRanges: List<String> = emptyList()
        override var coreReservedFakeIpRanges: List<String> = emptyList()
        lateinit var physicalValue: CapturedMeowPhysical
        override lateinit var startupPolicies: MeowHostPolicyTarget
        override val establishedBuilderSettings get() = startupPlan.builderSettings().takeIf { configuration.runtimeMode == NativeCore.RuntimeMode.Proxy || tun != null }
        override fun currentPolicies(fallback: dev.aerove.lite.LiteSettings) = MeowHostPolicyTarget.from(
            if (runtimeSnapshot != null) fallback else application.settings.changes.value ?: fallback,
            requestUserAgent)
        private inner class PhysicalReceipt(val captured: CapturedMeowPhysical) : MeowTunnelBackend.NetworkReceipt {
            val owner = this@Resources
            override val physical = MeowPhysicalNetwork(captured.networkHandle, captured.dns)
        }
        override suspend fun captureNetwork(expectedDns: SystemDnsSnapshot): MeowTunnelBackend.NetworkReceipt = withContext(Dispatchers.IO) {
            check(isCurrent() && !sharedCleaned) { "Meow network owner is not current" }
            val captured = capturePhysical()
            check(captured.network != null && captured.networkHandle != null) { "No captured physical network" }
            check(captured.dns.json() == expectedDns.json()) { "Physical Network and supplied DNS are from different captures" }
            check(captured.dns.interfaces.values.any { it.isNotEmpty() }) { "No captured physical DNS" }
            PhysicalReceipt(captured)
        }
        override fun networkCurrent(receipt: MeowTunnelBackend.NetworkReceipt): Boolean {
            val owned = receipt as? PhysicalReceipt ?: return false
            if (owned.owner !== this) return false
            val latest = capturePhysical()
            return latest.networkHandle == owned.captured.networkHandle && latest.dns.json() == owned.captured.dns.json()
        }
        override suspend fun commitNetwork(receipt: MeowTunnelBackend.NetworkReceipt) { cleanup.withLock {
            val owned = receipt as? PhysicalReceipt ?: error("Foreign Meow network receipt")
            check(owned.owner === this) { "Foreign Meow network owner" }
            val captured = owned.captured
            check(isCurrent() && !sharedCleaned) { "Meow network owner is not current" }
            if (tun != null) check(service.setUnderlyingNetworks(arrayOf(checkNotNull(captured.network)))) { "Android underlying network binding failed" }
            physicalValue = captured
        } }
        override fun observeIdentityChanges(changed: () -> Unit): AutoCloseable? {
            if (resolver == null) return null
            return AndroidApplicationLoader(application).also { packageLoader = it }.observeChanges(changed)
        }
        override suspend fun markUsable() { cleanup.withLock {
            if (!markedUsable) {
                try { if (runtimeSnapshot == null) application.profiles.markUsable(prepared) }
                catch (_: ProfileConflictException) { /* Never mark a newer revision usable. */ }
                markedUsable = true
            }
        } }
        override fun clearIdentityCache() { resolver?.clearPackageCache() }
        lateinit var startupPlan: VpnPlan
        var controlPort = 0
        override suspend fun stageTun(): MeowTunnelBackend.TunReceipt = withContext(Dispatchers.IO) { cleanup.withLock {
            check(isCurrent() && !sharedCleaned && tun == null) { "Meow TUN cannot be staged in this state" }
            val captured = capturePhysical()
            check(captured.networkHandle == physicalValue.networkHandle && captured.dns.json() == physicalValue.dns.json()) {
                "Physical network changed; Meow network policy update is required before TUN attach"
            }
            val fd = establishAndroidTun(service,startupPlan,controlPort,builderDnsEnabled,captured.dns,builderReservedFakeIpRanges) { captured.network }
            try { admission.established();Attachment(fd).also { staged += it } }
            catch(failure:Throwable) { try { fd.close() } catch(cleanup:Throwable) { failure.addSuppressed(cleanup) };throw failure }
        } }
        override suspend fun commitTun(receipt: MeowTunnelBackend.TunReceipt) { cleanup.withLock {
            val attachment = staged.singleOrNull { it === receipt } ?: error("Foreign or released Meow TUN receipt")
            check(tun == null && !sharedCleaned) { "Meow TUN is already established or stopped" }
            tun = attachment.file
            staged -= attachment
        } }
        override suspend fun discardUnsubmittedTun(receipt: MeowTunnelBackend.TunReceipt) { cleanup.withLock {
            val attachment = staged.singleOrNull { it === receipt } ?: error("Foreign Meow TUN receipt")
            attachment.file.close(); staged -= attachment; if(tun == null) admission.park()
        } }
        override suspend fun parkedTun() { cleanup.withLock {
            admission.park()
            tun?.close();tun = null
            staged.toList().forEach { it.file.close();staged -= it }
        } }
        override suspend fun reloadRules(next: PreparedProfile, endpoint: CoreEndpoint, current: () -> Boolean): Boolean =
            ruleResources?.reload(next, endpoint, current) ?: false
        override fun isCurrent() = admission.current() && (runtimeSnapshot?.let {
            application.runtimeHandoffs.isCurrent(it.revision, it.intentEpoch)
        } ?: true)
        override suspend fun verifyControl(endpoint: CoreEndpoint) = verifyMeowControl(endpoint)
        override suspend fun release() = withContext(NonCancellable + Dispatchers.IO) { cleanup.withLock {
            admission.close()
            ruleResources?.closeAfterNativeJoin()
            flutterResources?.closeAfterNativeJoin(); flutterResources = null
            resolver?.clearPackageCache()
            packageLoader?.close(); packageLoader = null
            try {
                staged.toList().forEach { it.file.close();staged -= it }
                cache?.let { root ->
                    // This generated private session directory is owned by this receipt; never follow child symlinks.
                    if (root.exists()) Files.walk(root.toPath()).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
                }
                cache = null
            } finally {
                if (!sharedCleaned) {
                    releaseAndroidTunResources(application, tun, file, profileLease, { throw it }) {
                        if (locked) { application.nativeLifecycle.unlock(this@Resources); locked = false }
                    }
                    sharedCleaned = true
                }
            }
            synchronized(gate) { retained -= this@Resources }
        } }
    }
}

data class CapturedMeowPhysical internal constructor(val network: Network?, val dns: SystemDnsSnapshot, val networkHandle: String?)

/** Select once; all following Android queries refer to that same Network, never another current supplier. */
fun captureMeowPhysical(manager: ConnectivityManager, selected: () -> Network?): CapturedMeowPhysical {
    val network = selected() ?: return CapturedMeowPhysical(null, SystemDnsSnapshot(), null)
    val capabilities = checkNotNull(manager.getNetworkCapabilities(network)) { "Selected physical network is unavailable" }
    check(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "Selected underlying network is itself a VPN" }
    val properties = checkNotNull(manager.getLinkProperties(network)) { "Selected physical network properties are unavailable" }
    val snapshot = capturedMeowDns(properties.interfaceName, properties.dnsServers.map { checkNotNull(it.hostAddress) })
    return CapturedMeowPhysical(network, snapshot, network.networkHandle.toULong().toString())
}

internal fun capturedMeowDns(interfaceName: String?, addresses: List<String>): SystemDnsSnapshot {
    val name = checkNotNull(interfaceName) { "Physical network has no interface identity" }
    return SystemDnsSnapshot(name, mapOf(name to addresses.toList())).also { it.config() }
}

internal suspend fun acquireMeowLifecycleLock(mutex: Mutex, owner: Any, acquired: () -> Unit) {
    withTimeout(45_000) {
        mutex.lock(owner)
        acquired()
    }
}

internal suspend fun verifyMeowControl(endpoint: CoreEndpoint) = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS).build()
    val call = client.newCall(Request.Builder().url("http://127.0.0.1:${endpoint.port}/version").header("Authorization","Bearer ${endpoint.secret}").build())
    try { suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { if (continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call, response: Response) { response.use {
                if (continuation.isActive) {
                    if (it.isSuccessful) continuation.resume(Unit)
                    else continuation.resumeWithException(IllegalStateException("Authenticated Meow controller is not ready"))
                }
            } }
        })
    } }
    finally { client.connectionPool.evictAll();client.dispatcher.executorService.shutdown() }
}
