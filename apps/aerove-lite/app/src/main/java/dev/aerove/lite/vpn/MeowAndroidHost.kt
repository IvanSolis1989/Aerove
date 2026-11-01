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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Native lease adapter; only a Service-backed TUN plan may establish a VPN. */
class MeowAndroidHost(private val service: LiteVpnService?, private val application: LiteApplication,
    private val capturePhysical: () -> CapturedMeowPhysical,
    private val initialListening: suspend (VpnPlan) -> Boolean,
    private val captureOwnerIntent: (VpnPlan) -> (() -> Boolean),
    private val verifiedNativeFeatures: () -> Set<String> = { MeowNativeCore.readVerifiedFeatures() },
    private val graphOnly: Boolean = false,
    private val graphTestUrl: () -> String = { dev.aerove.lite.flutter.MEOW_DEFAULT_TEST_URL },
    private val geoOwner: () -> Long = { 0 },
) : MeowTunnelBackend.Host {
    private val gate = Any()
    private val retained = mutableSetOf<Resources>()

    override suspend fun acquire(plan: VpnPlan): MeowTunnelBackend.Lease = withContext(Dispatchers.IO) {
        val resources = Resources(TunOwnerAdmission(captureOwnerIntent(plan)))
        synchronized(gate) { check(retained.isEmpty()) { "Previous Meow Host resources remain owned" }; retained += resources }
        try {
            if (!graphOnly) application.retireParkedGraph()
            acquireMeowLifecycleLock(application.nativeLifecycle, resources) { resources.locked = true }
            application.runtimeHandoffs.discardAbandonedGeoAfterNativeJoin()
            if (plan.profileId.startsWith("flutter:")) {
                val snapshot = checkNotNull(application.runtimeHandoffs.readCurrent()) { "runtime_snapshot_missing" }
                check(snapshot.profileId == plan.profileId) { "runtime_start_superseded" }
                resources.runtimeSnapshot = if (graphOnly) application.runtimeHandoffs.admitGraph(snapshot.revision, snapshot.intentEpoch)
                    else application.runtimeHandoffs.admit(snapshot.revision, snapshot.intentEpoch)
                val relocated = FlutterRuntimeResources.create(application.filesDir, snapshot, resources::isCurrent)
                resources.flutterResources = relocated
                val projected = dev.aerove.lite.flutter.meowProjectGroupTestUrl(ConfigYaml.parse(relocated.yaml),
                    if (graphOnly) graphTestUrl() else checkNotNull(service).startupFlutterTestUrl())
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
            val initial = initialListening(plan)
            val startupGate = resources.runtimeSnapshot != null && !graphOnly
            val parked = startupGate || !initial
            val captured = capturePhysical()
            resources.physicalValue = captured
            val physical = captured.network
            val dns = captured.dns
            resources.coreDnsEnabled = effectiveProfileDnsEnabled(resources.prepared)
            resources.builderDnsEnabled = plan.vpnDnsEnabled ?: resources.coreDnsEnabled
            val sourceRanges = MeowTunReconciliation.reservedRanges(resources.prepared.yaml, vpnBuilderIpv6(plan))
            resources.coreReservedFakeIpRanges = sourceRanges.takeIf { resources.coreDnsEnabled } ?: emptyList()
            resources.builderReservedFakeIpRanges = plan.vpnReservedFakeIpRanges?.toList()
                ?: sourceRanges.takeIf { resources.builderDnsEnabled } ?: emptyList()
            check(resources.isCurrent()) { "Meow Host intent was superseded" }
            check(!graphOnly || mode == NativeCore.RuntimeMode.Proxy && parked) { "Query graph must be private-only Proxy" }
            val actualBuilder = if (graphOnly && resources.runtimeSnapshot != null)
                dev.aerove.lite.flutter.meowFlutterBuilderSettings(plan.settings, checkNotNull(resources.runtimeSnapshot).handoff.vpnOptionsJson)
                else plan.builderSettings()
            val actualTun = TunRuntimeOptions(actualBuilder.network.mtu, actualBuilder.network.dnsHijacking, actualBuilder.ipv6,
                actualBuilder.network.tunInet4Address, actualBuilder.network.tunInet6Address, actualBuilder.network.tunDnsHijack, actualBuilder.network)
            val requestedTun = plan.settings.copy(ipv6 = actualBuilder.ipv6)
            MeowTunReconciliation.requireActual(resources.prepared.yaml, requestedTun, actualTun, resources.builderReservedFakeIpRanges)
            val tunReconciliation = MeowTunReconciliation.reconcile(resources.prepared.yaml, requestedTun)
            if (mode == NativeCore.RuntimeMode.Tun) resources.resolver = ConnectionOwnerResolver.forTun(checkNotNull(service), resources.admission::admitted)
            if (mode == NativeCore.RuntimeMode.Tun && initial) {
                val fd = establishAndroidTun(checkNotNull(service), plan, port, resources.builderDnsEnabled, dns, resources.builderReservedFakeIpRanges) { physical }
                if (startupGate) {
                    // Retain this same FD until the private startup CAS/readback permits attach.
                    resources.initialAttachment = resources.Attachment(fd).also { resources.staged += it }
                } else { resources.tun = fd; resources.admission.established() }
            }
            val cache = File(runtime, "meow-cache/${UUID.randomUUID()}").canonicalFile
            check(cache.toPath().startsWith(File(runtime,"meow-cache").canonicalFile.toPath()) && cache.mkdirs()) { "Could not create private Meow cache" }
            resources.cache = cache
            val desiredStartup = if (resources.runtimeSnapshot != null) plan.settings else runtimePoliciesForStart(plan.settings, application.settings.read())
            val startup = if (startupGate) desiredStartup.copy(externalController = desiredStartup.externalController.copy(enabled = false)) else desiredStartup
            resources.startupPolicies = MeowHostPolicyTarget.from(startup, resources.requestUserAgent)
            val composer = MeowRuntimeConfigComposer(runtime, resources.requestUserAgent, cache)
            val composed = if (resources.runtimeSnapshot != null) composer.composeFlutter(resources.prepared,
                checkNotNull(resources.flutterResources), startup, resources.tun?.fd ?: -1, port, secret, mode, dns,
                actualTun, parked,
                captured.networkHandle, verifiedNativeFeatures().toSet(), tunReconciliation)
            else composer.compose(resources.prepared, startup, resources.tun?.fd ?: -1, port, secret, mode, dns,
                    actualTun, parked,
                    captured.networkHandle, verifiedNativeFeatures().toSet(), tunReconciliation)
            resources.configValue = resources.ruleResources?.configure(composed) ?: composed.withResources(
                composed.engineYaml, checkNotNull(resources.prepared.resourceDirectory),
                resources.prepared.revision, resources.prepared.generation)
            resources.runtimeSnapshot?.let { snapshot ->
                MeowNativeCore.requireGeoInterfaces()
                val setup = JSONObject(snapshot.handoff.runtimeSettingsJson).getJSONObject("flutterSetup")
                val proof = setup.getJSONObject("data-source-proof")
                var raw: Map<String, String>? = null
                dev.aerove.lite.flutter.verifyNativeLiteBusinessSource(application, proof) { raw = it }
                val selected = checkNotNull(raw)
                val requested = setup.getJSONObject("selected-map").let { row -> row.keys().asSequence().associateWith(row::getString) }
                check(selected == requested) { "shared_source_selected_map_changed" }
                check(resources.isCurrent()) { "Meow Host intent was superseded" }
                val binding = MeowGeoHostBinding(geoOwner(), checkNotNull(plan.runtimeSessionGeneration), snapshot.intentEpoch,
                    snapshot.handoff.compiledSha256, dev.aerove.lite.flutter.meowGeoSourceGuard(proof))
                resources.configValue = resources.configValue.withGeoHostBinding(binding,
                    checkNotNull(resources.flutterResources).geoReadRootAuthority, selected)
            }
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
        var initialAttachment: Attachment? = null
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
        override val protector: SocketProtector? get() = if(configuration.runtimeMode == NativeCore.RuntimeMode.Tun) SocketProtector(checkNotNull(service)::protect) else null
        override val connectionOwner: ConnectionOwnerLookup? get() = resolver
        override var builderDnsEnabled = false
        override var coreDnsEnabled = false
        override var builderReservedFakeIpRanges: List<String> = emptyList()
        override var coreReservedFakeIpRanges: List<String> = emptyList()
        lateinit var physicalValue: CapturedMeowPhysical
        override lateinit var startupPolicies: MeowHostPolicyTarget
        override val startupSettings get() = startupPlan.settings.takeIf { runtimeSnapshot != null && !graphOnly }
        override suspend fun restoreStartupSelection(endpoint: CoreEndpoint): Boolean {
            check(startupSettings != null && configuration.initiallyParked && isCurrent()) { "Shared startup owner is unavailable" }
            val runtime = checkNotNull(runtimeSnapshot)
            val setup = dev.aerove.lite.flutter.MeowServiceSetup(
                JSONObject(runtime.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").toString(), checkNotNull(service).startupFlutterTestUrl())
            check(startupPlan.settings.requests.checkCertificate == !setup.skipCertVerify) { "skip-cert-verify: Startup policy differs from compiled setup" }
            val requested = setup.selectedMap
            CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
                dev.aerove.lite.flutter.MeowRuntimeSelection(client).apply(requested, ::isCurrent)
            }
            currentCoroutineContext().ensureActive()
            check(isCurrent()) { "Shared startup selection was superseded" }
            return initialListening(startupPlan)
        }
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
            if (tun != null) check(checkNotNull(service).setUnderlyingNetworks(arrayOf(checkNotNull(captured.network)))) { "Android underlying network binding failed" }
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
            initialAttachment?.let { attachment ->
                check(attachment in staged) { "Initial Meow TUN receipt was lost" }
                admission.established()
                initialAttachment = null
                return@withLock attachment
            }
            val fd = establishAndroidTun(checkNotNull(service),startupPlan,controlPort,builderDnsEnabled,captured.dns,builderReservedFakeIpRanges) { captured.network }
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
            initialAttachment = null
            tun?.close();tun = null
            staged.toList().forEach { it.file.close();staged -= it }
        } }
        override suspend fun commitGeo(candidate: MeowGeoCandidate, binding: MeowGeoHostBinding) = withContext(NonCancellable+Dispatchers.IO) { cleanup.withLock {
            check(runtimeSnapshot != null && ruleResources == null && !sharedCleaned && isCurrent()) { "Geo receipt belongs to another runtime lease" }
            val target=checkNotNull(file)
            require(target.toPath() == target.toPath().toRealPath() && Files.isRegularFile(target.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            PrivateStorage(checkNotNull(target.parentFile)).write(target.name,candidate.engineYaml)
            require(sha256(target) == sha256(candidate.engineYaml)) { "Applied Geo engine readback differs" }
            preparedValue=preparedValue.copy(generation=candidate.compiledSha256)
            configValue=configValue.withAppliedGeo(candidate,binding)
        } }
        override suspend fun reloadRules(next: PreparedProfile, endpoint: CoreEndpoint, current: () -> Boolean): Boolean =
            ruleResources?.reload(next, endpoint, current) ?: false
        override fun isCurrent() = admission.current() && (runtimeSnapshot?.let {
            if (graphOnly) application.runtimeHandoffs.isGraphCandidate(it.revision, it.intentEpoch)
                else application.runtimeHandoffs.isCurrent(it.revision, it.intentEpoch)
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
                initialAttachment = null
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
