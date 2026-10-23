package dev.aerove.lite.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.Network
import android.os.ParcelFileDescriptor
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.MainActivity
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.core.SocketProtector
import dev.aerove.lite.core.ConnectionOwnerResolver
import dev.aerove.lite.appaccess.AndroidApplicationLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.aerove.lite.data.AppliedConfigSnapshot
import dev.aerove.lite.data.ProfileConflictException
import dev.aerove.lite.data.PreparedProfile
import dev.aerove.lite.data.ProfileLease
import dev.aerove.lite.data.RuntimeConfigComposer
import dev.aerove.lite.data.SystemDnsSnapshot
import dev.aerove.lite.data.effectiveProfileDnsEnabled
import dev.aerove.lite.data.TunRuntimeOptions
import dev.aerove.lite.networksettings.applyVpnNetworkPolicy
import dev.aerove.lite.networksettings.applyVpnTunPolicy
import dev.aerove.lite.networksettings.planNetwork
import dev.aerove.lite.networksettings.BoundControlPort
import dev.aerove.lite.networksettings.NetworkRuntimePlan
import dev.aerove.lite.networksettings.selectPrivateControlPort
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AndroidTunnelBackend(
    private val service: LiteVpnService,
    private val application: LiteApplication,
    private val underlying: () -> Network?,
    private val networkDns: () -> SystemDnsSnapshot = { SystemDnsSnapshot() },
    private val initialListening: suspend (VpnPlan) -> Boolean,
    private val captureOwnerIntent: (VpnPlan) -> (() -> Boolean),
    private val eventScope: CoroutineScope,
) : TunnelBackend {
    override suspend fun open(plan: VpnPlan): TunnelSession = withContext(Dispatchers.IO) {
        val lease = Any()
        val ownerAdmission = TunOwnerAdmission(captureOwnerIntent(plan))
        var ownerResolver: ConnectionOwnerResolver? = null
        var acquired = false
        var config: File? = null
        var tun: ParcelFileDescriptor? = null
        var handle: Long? = null
        var profileLease: ProfileLease? = null
        try {
            withTimeout(45_000) {
                application.nativeLifecycle.lock(lease)
                acquired = true
            }
            val prepared = application.profiles.prepareForStart(plan.profileId)
            profileLease = application.profiles.retain(prepared)
            val runtime = File(application.filesDir, "runtime").apply { mkdirs() }
            val directory = File(runtime, "profiles/${prepared.profileId}").apply { check(isDirectory || mkdirs()) }
            val port = selectTunnelControlPort(plan) {
                val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                object : BoundControlPort {
                    override val port: Int get() = socket.localPort
                    override fun close() = socket.close()
                }
            }
            val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            val runtimeMode = runtimeMode(plan)
            if (runtimeMode == NativeCore.RuntimeMode.Tun) ownerResolver = ConnectionOwnerResolver.forTun(service, ownerAdmission::admitted)
            config = File(directory, "${UUID.randomUUID()}.yaml")
            val dnsEnabled = effectiveProfileDnsEnabled(prepared)
            val builderDnsEnabled = plan.vpnDnsEnabled ?: dnsEnabled
            val profileRanges = profileFakeIpRanges(prepared.yaml)
            val coreRanges = vpnReservedRanges(profileRanges, dnsEnabled, vpnBuilderIpv6(plan))
            val builderRanges = plan.vpnReservedFakeIpRanges ?: vpnReservedRanges(profileRanges, builderDnsEnabled, vpnBuilderIpv6(plan))
            val baseline = plan.builderSettings()
            val initiallyParked = !initialListening(plan)
            val initialDns = networkDns()
            check(ownerAdmission.current()) { "Runtime startup intent was superseded" }
            if (!initiallyParked && runtimeMode == NativeCore.RuntimeMode.Tun) {
                tun = establishTun(plan, port, builderDnsEnabled, initialDns, builderRanges)
                ownerAdmission.established()
            }
            val startupSettings = runtimePoliciesForStart(plan.settings, application.settings.changes.value)
            config.writeText(RuntimeConfigComposer(runtime, application.requestPolicy.defaultUserAgent)
                .compose(prepared, startupSettings, tun?.fd ?: -1, port, secret, runtimeMode, initialDns,
                    tunOptions = TunRuntimeOptions(baseline.network.mtu, baseline.network.dnsHijacking, baseline.ipv6),
                    initiallyParked = initiallyParked))
            config.setReadable(false, false)
            config.setReadable(true, true)
            val endpoint = CoreEndpoint(port, secret, AppliedConfigSnapshot(prepared.profileId, config),
                profileRevision = prepared.revision, resourceGeneration = prepared.generation)
            check(ownerAdmission.current()) { "Runtime startup intent was superseded" }
            handle = NativeCore.startRuntimeOwned(config.path, directory.path, runtimeMode.wireValue, tun?.fd ?: -1,
                if (runtimeMode == NativeCore.RuntimeMode.Tun) SocketProtector(service::protect) else null,
                ownerResolver, initiallyParked)
            Session(checkNotNull(handle), tun, config, endpoint, prepared, checkNotNull(profileLease), lease, runtimeMode, plan.copy(settings = startupSettings),
                builderDnsEnabled, initialDns, builderRanges.toList(), coreRanges.toList(), dnsEnabled, initiallyParked, ownerAdmission, ownerResolver)
        } catch (failure: Throwable) {
            ownerAdmission.close()
            ownerResolver?.clearPackageCache()
            stopNativeSession(
                join = { handle?.let(NativeCore::stop) },
                cleanup = { cleanupStoppedResources(tun, config, profileLease, lease, acquired) },
                warn = ::warnStoppedCleanup,
            )
            throw failure
        }
    }

    private fun establishTun(plan: VpnPlan, port: Int, dnsEnabled: Boolean,
        dnsSnapshot: SystemDnsSnapshot = networkDns(), reservedRanges: List<String> = emptyList()): ParcelFileDescriptor {
        return establishAndroidTun(service, plan, port, dnsEnabled, dnsSnapshot, reservedRanges, underlying)
    }

    private inner class Session(
        private val handle: Long,
        private var tun: ParcelFileDescriptor?,
        private val config: File,
        override val endpoint: CoreEndpoint,
        private val prepared: PreparedProfile,
        private val profileLease: ProfileLease,
        private val lease: Any,
        private val runtimeMode: NativeCore.RuntimeMode,
        private val plan: VpnPlan,
        override val builderDnsEnabled: Boolean,
        initialDns: SystemDnsSnapshot,
        override val builderReservedFakeIpRanges: List<String>,
        override val coreReservedFakeIpRanges: List<String>,
        override val coreDnsEnabled: Boolean,
        private val initiallyParked: Boolean,
        private val ownerAdmission: TunOwnerAdmission,
        private val ownerResolver: ConnectionOwnerResolver?,
    ) : ListenerPolicySession {
        private val changes = kotlinx.coroutines.sync.Mutex()
        private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
        private val policyOwner = RuntimePolicyOwner(object : RuntimePolicyCommands {
            override fun statistics(enabled: Boolean): String {
                NativeCore.setOnlyStatisticsProxy(handle, enabled)
                val actual = NativeCore.listenerStatus(handle)
                coreEpoch = requireConfigEpoch(actual.coreEpoch, coreEpoch)
                check(actual.onlyStatisticsProxy == enabled) { "Core statistics policy did not converge" }
                return actual.coreEpoch
            }
            override fun http(userAgent: String, checkCertificate: Boolean) = NativeCore.configureHttp(handle, userAgent, checkCertificate)
            override fun probe(unifiedDelay: Boolean, tcpConcurrent: Boolean) = NativeCore.configureProbeDial(handle, unifiedDelay, tcpConcurrent)
            override fun probeStatus(): ProbeDialReadback = NativeCore.probeDialStatus(handle).let {
                ProbeDialReadback(it.unifiedDelay, it.tcpConcurrent, it.coreEpoch)
            }
            override fun controller(enabled: Boolean, secret: String): UserControllerReadback =
                NativeCore.configureUserController(handle, enabled, secret).readback()
            override fun controllerStatus(): UserControllerReadback = NativeCore.userControllerStatus(handle).readback()
            override fun processMode(mode: String) = NativeCore.configureFindProcessMode(handle, mode)
            override fun processStatus(): ProcessModeReadback = NativeCore.listenerStatus(handle).let {
                ProcessModeReadback(it.findProcessMode, it.coreEpoch)
            }
        })
        @Volatile private var coreEpoch: String? = null
        private var packageChangePending = false
        private var networkDnsFingerprint: String? = initialDns.json()
        private val listenerFailure = CompletableDeferred<Exception>()
        private var packageLoader: AndroidApplicationLoader? = null
        private var packageObserver: AutoCloseable? = null
        private var profileMarkedUsable = false
        override var initialListenerReadback: ListenerReadback? = null
            private set
        override var establishedBuilderSettings: dev.aerove.lite.LiteSettings? = plan.builderSettings().takeUnless { initiallyParked }
            private set

        init {
            if (ownerResolver != null) {
                packageLoader = AndroidApplicationLoader(application)
                packageObserver = packageLoader?.observeChanges {
                    eventScope.launch {
                        try { invalidatePackageIdentity() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { if (!stopping.get()) listenerFailure.complete(failure) }
                    }
                }
            }
        }

        override suspend fun awaitReady() = withTimeout(45_000) {
            while (true) {
                val snapshot = NativeCore.status(handle)
                when (snapshot.state) {
                    NativeCore.State.Running -> {
                        if (initiallyParked) {
                            check(snapshot.coreReady && snapshot.runtimeMode == runtimeMode) { "Parked Core API is not ready" }
                            check(snapshot.apiAddress == "127.0.0.1:${endpoint.port}") { "Parked Core controller differs from the owned endpoint" }
                            check(!snapshot.listening && !snapshot.tunReady && !snapshot.userListenersReady) { "Parked runtime unexpectedly opened listeners" }
                        } else requireRuntimeReady(snapshot, runtimeMode, endpoint)
                        break
                    }
                    NativeCore.State.Starting -> delay(50)
                    else -> error(snapshot.error ?: "Core stopped before becoming ready")
                }
            }
            verifyApi(endpoint)
            val actual = NativeCore.listenerStatus(handle)
            check(actual.findProcessMode == plan.settings.findProcessMode.wireName) { "Core startup process matching mode did not match the prepared policy" }
            check(!initiallyParked || (!actual.listening && !actual.tunReady && !actual.userListenersReady && actual.activePorts.isEmpty())) { "Initial parked runtime opened listeners" }
            coreEpoch = actual.coreEpoch
            initialListenerReadback = ListenerReadback(actual.listening, actual.tunReady, actual.userListenersReady, actual.coreEpoch, actual.activePorts)
            changes.withLock {
                if (packageChangePending && !stopping.get() && ownerAdmission.current()) {
                    NativeCore.invalidateProcessIdentity(handle)
                    packageChangePending = false
                }
            }
            configurePolicies(application.settings.changes.value ?: plan.settings)
            if (actual.listening) markProfileUsable()
        }

        private suspend fun markProfileUsable() {
            if (profileMarkedUsable) return
            try {
                application.profiles.markUsable(prepared)
            } catch (_: ProfileConflictException) {
                // A concurrent subscription update must not mark its newer revision as tested.
            }
            profileMarkedUsable = true
        }

        override suspend fun awaitExit() {
            while (true) {
                withTimeoutOrNull(500) { listenerFailure.await() }?.let { throw it }
                val snapshot = NativeCore.status(handle)
                check(snapshot.state == NativeCore.State.Running) { snapshot.error ?: "Core exited" }
            }
        }

        override suspend fun setListening(enabled: Boolean): ListenerReadback = withContext(Dispatchers.IO) {
        try {
            changes.withLock {
                check(!stopping.get()) { "Runtime is stopping" }
                val actual = if (!enabled) {
                    ownerAdmission.park()
                    NativeCore.park(handle).also { parked ->
                        check(!parked.listening && !parked.tunReady && !parked.userListenersReady && parked.activePorts.isEmpty()) {
                            "Native listeners did not park"
                        }
                        tun?.close()
                        tun = null
                    }
                } else if (runtimeMode == NativeCore.RuntimeMode.Proxy) NativeCore.resumeProxy(handle) else {
                    val replacement = establishTun(plan, endpoint.port, builderDnsEnabled, reservedRanges = builderReservedFakeIpRanges)
                    try {
                        ownerAdmission.established()
                        NativeCore.attachTun(handle, replacement.fd).also { ready ->
                            check(ready.listening && ready.tunReady && ready.userListenersReady) { "Native TUN did not become ready" }
                            check(!stopping.get()) { "Runtime stopped while attaching TUN" }
                            tun = replacement
                            establishedBuilderSettings = plan.builderSettings()
                        }
                    } catch (failure: Throwable) { ownerAdmission.park(); replacement.close(); throw failure }
                }
                if (actual.listening && runtimeMode == NativeCore.RuntimeMode.Proxy) establishedBuilderSettings = plan.builderSettings()
                coreEpoch = requireConfigEpoch(actual.coreEpoch, coreEpoch)
                if (actual.listening) markProfileUsable()
                ListenerReadback(actual.listening, actual.tunReady, actual.userListenersReady, actual.coreEpoch, actual.activePorts)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!stopping.get()) listenerFailure.complete(failure)
            throw failure
        }
        }

        private fun policyTarget(settings: dev.aerove.lite.LiteSettings) = RuntimePolicyTarget(
            settings.backgroundPolicy.onlyStatisticsProxy,
            settings.requests.globalUserAgent ?: application.requestPolicy.defaultUserAgent,
            settings.requests.checkCertificate, settings.unifiedDelay, settings.tcpConcurrent,
            settings.externalController.enabled, settings.externalController.secret.takeIf { settings.externalController.enabled }.orEmpty(),
            settings.findProcessMode.wireName,
        )

        override suspend fun configurePolicies(settings: dev.aerove.lite.LiteSettings) = withContext(Dispatchers.IO) {
            changes.withLock {
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    check(!stopping.get()) { "Runtime is stopping" }
                    val target = policyTarget(application.settings.changes.value ?: settings)
                    val applied = policyOwner.apply(target, checkNotNull(coreEpoch) { "Core policy identity is unavailable" }) {
                        !stopping.get() && target == policyTarget(application.settings.changes.value ?: settings)
                    }
                    policyOwner.lastEpoch?.let { coreEpoch = requireConfigEpoch(it, coreEpoch) }
                    if (applied) break
                }
            }
        }

        override suspend fun configureNetworkDns(snapshot: SystemDnsSnapshot) = withContext(Dispatchers.IO) {
            changes.withLock {
                check(!stopping.get()) { "Runtime is stopping" }
                val encoded = snapshot.json()
                if (networkDnsFingerprint != encoded) {
                    NativeCore.updateNetworkDns(handle, encoded)
                    networkDnsFingerprint = encoded
                }
            }
        }

        private suspend fun invalidatePackageIdentity() = changes.withLock {
            if (stopping.get() || !ownerAdmission.current()) return@withLock
            ownerResolver?.clearPackageCache()
            if (coreEpoch == null) packageChangePending = true
            else NativeCore.invalidateProcessIdentity(handle)
        }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            stopping.set(true)
            ownerAdmission.close()
            // Native stop must cancel an accepted policy transaction before waiting on its host mutex.
            stopNativeSession(join = { NativeCore.stop(handle) }, cleanup = {
                changes.withLock {
                    try { packageObserver?.close() } catch (failure: Exception) { warnStoppedCleanup(failure) }
                    packageObserver = null
                    packageLoader?.close()
                    packageLoader = null
                    ownerResolver?.clearPackageCache()
                    cleanupStoppedResources(tun, config, profileLease, lease)
                    tun = null
                }
            }, warn = ::warnStoppedCleanup)
        }
    }

    private suspend fun cleanupStoppedResources(
        tun: ParcelFileDescriptor?, config: File?, profileLease: ProfileLease?, lease: Any, acquired: Boolean = true,
    ) {
        releaseAndroidTunResources(application, tun, config, profileLease, ::warnStoppedCleanup) {
            if (acquired) application.nativeLifecycle.unlock(lease)
        }
    }

    private fun warnStoppedCleanup(failure: Exception) {
        android.util.Log.w("AEROVE Lite", "Core joined; private-file cleanup was incomplete", failure)
    }

    private fun verifyApi(endpoint: CoreEndpoint) {
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${endpoint.port}/version")
                .header("Authorization", "Bearer ${endpoint.secret}").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Authenticated core controller is not ready" }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}

internal fun vpnBuilderIpv6(plan: VpnPlan): Boolean = plan.builderSettings().ipv6

/** Core settings may advance while a previously established Builder policy must stay fixed. */
internal fun vpnBuilderNetworkPlan(plan: VpnPlan, controlPort: Int): NetworkRuntimePlan =
    vpnBuilderNetworkPlan(plan, controlPort, emptyList())

internal fun vpnBuilderNetworkPlan(plan: VpnPlan, controlPort: Int, reservedRanges: List<String>): NetworkRuntimePlan =
    planNetwork(plan.builderSettings().network, controlPort, vpnBuilderIpv6(plan), reservedRanges)

internal fun profileFakeIpRanges(yaml: String): List<String> {
    val dns = dev.aerove.lite.data.ConfigYaml.parse(yaml)["dns"] as? Map<*, *> ?: return emptyList()
    if (dns["enable"] != true || dns["enhanced-mode"] != "fake-ip") return emptyList()
    return listOfNotNull(dns["fake-ip-range"] as? String, dns["fake-ip-range6"] as? String)
}

internal fun vpnReservedRanges(profileRanges: List<String>, dnsEnabled: Boolean, ipv6: Boolean): List<String> =
    (profileRanges + if (dnsEnabled && ipv6) listOf("fdfe:dcba:9876::2/128") else emptyList()).distinct()

internal fun selectTunnelControlPort(plan: VpnPlan, bind: () -> BoundControlPort): Int {
    val current = plan.settings.network.ports.let { setOf(it.mixed, it.http, it.socks) - 0 }
    val baseline = plan.builderSettings().network.ports.let { setOf(it.mixed, it.http, it.socks) - 0 }
    val additional = (baseline - current) + dev.aerove.lite.controllerprefs.ExternalControllerPreferences.PORT
    val held = mutableListOf<BoundControlPort>()
    fun bindOutsideBaseline(): BoundControlPort {
        repeat(additional.size + 1) {
            val reservation = bind()
            if (reservation.port !in additional) return reservation
            held += reservation
        }
        error("Could not reserve a private controller port distinct from the running VPN policy")
    }
    var selected: Int? = null
    var failure: Throwable? = null
    try { selected = selectPrivateControlPort(plan.settings.network, ::bindOutsideBaseline) }
    catch (error: Throwable) { failure = error }
    for (reservation in held.asReversed()) {
        try { reservation.close() } catch (error: Exception) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
    return checkNotNull(selected)
}

internal fun runtimeMode(plan: VpnPlan): NativeCore.RuntimeMode =
    if (plan.builderSettings().network.vpnEnabled) NativeCore.RuntimeMode.Tun else NativeCore.RuntimeMode.Proxy

internal data class VpnApplicationPolicy(val allowed: Set<String>?, val disallowed: Set<String>)
internal fun vpnApplicationPolicy(mode: AppFilterMode, packages: Set<String>, ownPackage: String): VpnApplicationPolicy = when (mode) {
    AppFilterMode.All -> VpnApplicationPolicy(null, emptySet())
    AppFilterMode.Include -> VpnApplicationPolicy(packages + ownPackage, emptySet())
    AppFilterMode.Exclude -> VpnApplicationPolicy(null, packages - ownPackage)
}

internal fun requireRuntimeReady(status: NativeCore.Status, mode: NativeCore.RuntimeMode, endpoint: CoreEndpoint) {
    check(status.state == NativeCore.State.Running) { status.error ?: "Core is not running" }
    check(status.runtimeMode == mode) { "Core runtime mode differs from the requested mode" }
    check(status.userListenersReady) { "Core user listeners are not ready" }
    check(status.tunReady == (mode == NativeCore.RuntimeMode.Tun)) { "Core TUN readiness differs from the runtime mode" }
    check(status.apiAddress == "127.0.0.1:${endpoint.port}") { "Core controller differs from the owned endpoint" }
}

private fun NativeCore.UserControllerStatus.readback() = UserControllerReadback(enabled, address, authenticated,
    privateRoutesExcluded, coreEpoch)

/** Refresh only hot Core policies before the first packet; pending Builder policy remains fixed. */
internal fun runtimePoliciesForStart(planned: dev.aerove.lite.LiteSettings, saved: dev.aerove.lite.LiteSettings?): dev.aerove.lite.LiteSettings =
    if (saved == null) planned else planned.copy(backgroundPolicy = saved.backgroundPolicy, requests = saved.requests,
        unifiedDelay = saved.unifiedDelay, tcpConcurrent = saved.tcpConcurrent, externalController = saved.externalController,
        findProcessMode = saved.findProcessMode)
