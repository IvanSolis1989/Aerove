package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.*
import dev.aerove.lite.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/** Candidate complete policy adapter; Native/ART acceptance is required before production registration. */
class MeowTunnelBackend(private val host: Host, private val commands: Commands = JniCommands) : TunnelBackend {
    internal data class StartupFailure(val nativeGeneration: Long, val intentGeneration: Long?, val coreEpoch: String?,
        val failure: Throwable, val nativeBeforeStop: Result<MeowStatus>, val nativeAfterStop: Result<MeowStatus>)
    companion object {
        private class StartupObserver(val receive: (StartupFailure) -> Unit)
        private val startupObserver = java.util.concurrent.atomic.AtomicReference<StartupObserver?>(null)
        internal fun observeStartupFailures(observer: (StartupFailure) -> Unit): AutoCloseable {
            val registration = StartupObserver(observer)
            check(startupObserver.compareAndSet(null, registration)) { "Startup diagnostics already observed" }
            return AutoCloseable { startupObserver.compareAndSet(registration, null) }
        }
    }
    private class GeoEntry(val token: MeowGeoToken, val expected: MeowGeoExpected,
        val candidate: MeowGeoCandidate, val resources: FlutterRuntimeResources.Geo,
        val persist: suspend (MeowGeoApplied) -> dev.aerove.lite.flutter.RuntimeAppliedIdentity,
        val discard: suspend () -> Unit) {
        var status: MeowGeoStatus? = null
        var identity: dev.aerove.lite.flutter.RuntimeAppliedIdentity? = null
        var resourcesReleased = false
    }

    /** Existing Service responsibilities: lifecycle lock, prepare/retain, Builder FD and applied-file lease. */
    fun interface Host { suspend fun acquire(plan: VpnPlan): Lease }
    interface TunReceipt { val descriptor: Int }
    interface NetworkReceipt { val physical: MeowPhysicalNetwork }
    interface Lease {
        val prepared: PreparedProfile
        val configuration: MeowRuntimeConfig
        val appliedConfiguration: AppliedConfigSnapshot
        val protector: SocketProtector?
        val connectionOwner: ConnectionOwnerLookup?
        val builderDnsEnabled: Boolean
        val coreDnsEnabled: Boolean
        val builderReservedFakeIpRanges: List<String>
        val coreReservedFakeIpRanges: List<String>
        val startupPolicies: MeowHostPolicyTarget
        val startupSettings: LiteSettings? get() = null
        suspend fun restoreStartupSelection(endpoint: CoreEndpoint): Boolean = error("No shared startup selection owner")
        val establishedBuilderSettings: LiteSettings?
        fun currentPolicies(fallback: LiteSettings): MeowHostPolicyTarget
        suspend fun captureNetwork(expectedDns: SystemDnsSnapshot): NetworkReceipt
        fun networkCurrent(receipt: NetworkReceipt): Boolean
        suspend fun commitNetwork(receipt: NetworkReceipt)
        fun observeIdentityChanges(changed: () -> Unit): AutoCloseable?
        suspend fun markUsable()
        fun clearIdentityCache()
        suspend fun stageTun(): TunReceipt
        suspend fun commitTun(receipt: TunReceipt)
        suspend fun discardUnsubmittedTun(receipt: TunReceipt)
        suspend fun parkedTun()
        fun isCurrent(): Boolean
        suspend fun verifyControl(endpoint: CoreEndpoint)
        suspend fun reloadRules(next: PreparedProfile, endpoint: CoreEndpoint, current: () -> Boolean): Boolean = false
        suspend fun commitGeo(candidate: MeowGeoCandidate, binding: MeowGeoHostBinding) {
            error("This lease has no shared Geo runtime projection owner")
        }
        // Must be idempotent. Release FD/file/profile/lifecycle only after confirmed Native join.
        suspend fun release()
    }
    interface Commands {
        fun start(planJson: String, fd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?): Long
        fun awaitReady(handle: Long)
        fun startupDiagnostic(handle: Long): MeowStartupSnapshot? = null
        fun awaitExit(handle: Long)
        fun status(handle: Long): MeowStatus
        fun listeners(handle: Long): MeowListenerStatus
        fun stop(handle: Long)
        fun listener(handle: Long, action: String, fd: Int): MeowListenerStatus
        fun hostPolicy(handle: Long, request: String): String
    }
    private object JniCommands : Commands {
        override fun start(planJson: String, fd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?) =
            MeowNativeCore.startOwned(planJson, fd, protector, owner)
        override fun awaitReady(handle: Long) = MeowNativeCore.awaitReady(handle)
        override fun startupDiagnostic(handle: Long) = MeowStartupCodec.decode(MeowNativeCore.startupDiagnostic(handle))
        override fun awaitExit(handle: Long) = MeowNativeCore.awaitExit(handle)
        override fun status(handle: Long) = MeowNativeCore.readStatus(handle)
        override fun listeners(handle: Long) = MeowNativeCore.readListenerStatus(handle)
        override fun stop(handle: Long) = MeowNativeCore.stop(handle)
        override fun hostPolicy(handle: Long, request: String) = MeowNativeCore.hostPolicyCommand(handle, request)
        override fun listener(handle: Long, action: String, fd: Int) =
            MeowStatusCodec.listeners(MeowNativeCore.listenerCommand(handle, action, fd))
    }
    private val gate = Any()
    private var acquiring = false
    private val retained = mutableSetOf<Session>()
    private val leases = mutableSetOf<Lease>()

    /** Recovery for an open that failed while Native join was unconfirmed; never discards receipts. */
    suspend fun stopAndJoinRetained() {
        val sessions = synchronized(gate) { check(!acquiring) { "Meow acquire is still draining" }; acquiring = true; retained.toList() }
        try {
        var failure: Throwable? = null
        for (session in sessions) try { session.stop() } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        val standalone = synchronized(gate) { leases.filter { lease -> retained.none { it.owns(lease) } } }
        for (lease in standalone) try { withContext(NonCancellable + Dispatchers.IO) { release(lease) } } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
        } finally { synchronized(gate) { acquiring = false } }
    }

    private suspend fun release(lease: Lease) { lease.release(); synchronized(gate) { leases -= lease } }

    override suspend fun open(plan: VpnPlan): Session {
        coroutineContext.ensureActive()
        synchronized(gate) { check(!acquiring && retained.isEmpty() && leases.isEmpty()) { "Previous Meow session has not joined/released" }; acquiring = true }
        var lease: Lease? = null
        var session: Session? = null
        try {
            // Capture receipts inside the non-cancellable boundary, before dispatch-back cancellation.
            withContext(NonCancellable + Dispatchers.IO) {
                val owned = host.acquire(plan).also { lease = it; synchronized(gate) { leases += it } }
                val config = owned.configuration
                check(owned.isCurrent()) { "Meow startup intent was superseded" }
                check(config.profileId == owned.prepared.profileId && config.revision == owned.prepared.revision &&
                    config.resourceGeneration == owned.prepared.generation && config.profileId == plan.profileId) { "Meow prepared lineage mismatch" }
                check(owned.appliedConfiguration.profileId == plan.profileId) { "Meow applied configuration profile mismatch" }
                check(owned.appliedConfiguration.read() == config.engineYaml) { "Meow applied configuration differs from submitted engine YAML" }
                val fd = config.tun?.descriptor ?: -1
                check(config.runtimeMode == if (plan.builderSettings().network.vpnEnabled) NativeCore.RuntimeMode.Tun else NativeCore.RuntimeMode.Proxy) { "Meow runtime mode differs from Builder" }
                check(if (config.runtimeMode == NativeCore.RuntimeMode.Tun && !config.initiallyParked) fd >= 0 && owned.protector != null else fd == -1) { "Invalid Meow FD/protect plan" }
                val handle = commands.start(config.planJson(), fd, owned.protector, owned.connectionOwner)
                check(handle > 0) { "Meow returned an invalid handle" }
                Session(handle, owned, plan.ownerIntentToken).also { session = it; synchronized(gate) { retained += it } }
            }
            coroutineContext.ensureActive()
            check(checkNotNull(lease).isCurrent()) { "Meow startup intent was superseded" }
            return checkNotNull(session)
        } catch (failure: Throwable) {
            try { withContext(NonCancellable + Dispatchers.IO) { session?.stop() ?: lease?.let { release(it) } } }
            catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        } finally { synchronized(gate) { acquiring = false } }
    }

    inner class Session internal constructor(private val handle: Long, private val lease: Lease, private val intentGeneration: Long?) : ListenerPolicySession {
        internal fun owns(candidate: Lease) = lease === candidate
        private val cleanup = Mutex()
        private val listenerChanges = Mutex()
        private val listenerIntent = java.util.concurrent.atomic.AtomicLong()
        @Volatile private var stopping = false
        private var joined = false
        private var released = false
        private var identityObserved = false
        private var identityObserver: AutoCloseable? = null
        private val identityEvents = Channel<Unit>(Channel.CONFLATED)
        private val config get() = lease.configuration
        override val endpoint = CoreEndpoint(config.privateControl.port, config.privateControl.secret,
            lease.appliedConfiguration, lease.prepared.revision, lease.prepared.generation,
            resourceLineage = { val prepared = lease.prepared; prepared.revision to prepared.generation })
        override val builderDnsEnabled get() = lease.builderDnsEnabled
        override val coreDnsEnabled get() = lease.coreDnsEnabled
        override val builderReservedFakeIpRanges get() = lease.builderReservedFakeIpRanges.toList()
        override val coreReservedFakeIpRanges get() = lease.coreReservedFakeIpRanges.toList()
        @Volatile private var geoBinding = config.geoHostBinding
        internal fun currentGeoBinding() = geoBinding
        internal suspend fun renewGeoBinding(expected: dev.aerove.lite.data.MeowGeoExpected,
            witness: dev.aerove.lite.data.MeowSelectionWitness, rawSelectedMap: Map<String,String>,
            newSourceGuard: String, current: () -> Boolean): dev.aerove.lite.data.MeowGeoHostBinding {
            check(!stopping && lease.isCurrent() && current() && geoBinding == expected.hostBinding &&
                witness.stamp == expected.stamp && witness.matchesRaw(rawSelectedMap)) { "Geo metadata is not the actual selected runtime" }
            val engineSha=dev.aerove.lite.data.sha256(lease.appliedConfiguration.read())
            val receipt=policy.renewGeoBinding(expected,engineSha,witness.witnessId,rawSelectedMap,newSourceGuard,
                current = { !stopping && lease.isCurrent() && current() && geoBinding == expected.hostBinding },
                committed = { geoBinding=it })
            return receipt
        }
        private var geoSerial=0L
        private var geoEntry: GeoEntry? = null
        internal fun ownsGeoResources(resources: FlutterRuntimeResources.Geo) = geoEntry?.resources === resources

        internal suspend fun joinPreviousGeo() = withContext(NonCancellable+Dispatchers.IO) { listenerChanges.withLock {
            check(!stopping && lease.isCurrent()) { "Geo session was retired" }
            val previous=geoEntry ?: return@withLock
            val known=previous.status
            if(known?.terminal == true && known.sourceReadsJoined && previous.resourcesReleased &&
                (known.state != "committed" || previous.identity != null))return@withLock
            CoreApiClient(ControlEndpoint(endpoint.port,endpoint.secret)).use { client ->
                val observed=client.abortGeo(previous.token)
                finishGeo(previous,observed)
                check(observed.terminal && observed.sourceReadsJoined) { "Previous Geo transaction has not joined" }
            }
        } }

        internal suspend fun applyGeo(transactionId: String, candidate: MeowGeoCandidate,
            resources: FlutterRuntimeResources.Geo, raw: Map<String,String>, current: () -> Boolean,
            admit: suspend () -> Boolean,
            persist: suspend (MeowGeoApplied) -> dev.aerove.lite.flutter.RuntimeAppliedIdentity,
            discard: suspend () -> Unit): dev.aerove.lite.flutter.MeowGeoRuntimeResult {
            val caller=coroutineContext[Job]
            return withContext(NonCancellable+Dispatchers.IO) { listenerChanges.withLock {
                check(!stopping && lease.isCurrent() && current() && caller?.isActive != false)
                val previous=geoEntry
                check(previous == null || previous.status?.let { it.terminal && it.sourceReadsJoined } == true) { "Previous Geo transaction has not joined" }
                val binding=checkNotNull(geoBinding) { "Trusted Geo binding is missing" }
                CoreApiClient(ControlEndpoint(endpoint.port,endpoint.secret)).use { client ->
                    val witness=client.selectionWitness()
                    check(witness.matchesRaw(raw)) { "Actual Native selection witness differs from the full SQL raw map" }
                    check(current() && admit() && caller?.isActive != false && !stopping)
                    geoSerial=Math.addExact(geoSerial,1)
                    val entry=GeoEntry(MeowGeoToken(transactionId,geoSerial.toString()),MeowGeoExpected(witness.stamp,binding),candidate,resources,persist,discard)
                    resources.nativeOwner=true
                    geoEntry=entry
                    try {
                        entry.status=client.prepareGeo(entry.token,entry.expected,candidate)
                        check(entry.status?.state == "prepared") { "Native Geo preparation did not complete" }
                        check(!stopping && lease.isCurrent() && current() && caller?.isActive != false && admit()) { "Geo source changed before commit" }
                        entry.status=client.commitGeo(entry.token,entry.expected)
                    } catch(failure:Throwable) {
                        val observed=runCatching { client.geoStatus(entry.token) }.getOrNull()
                        entry.status=observed
                        if(observed?.state != "committed") {
                            entry.status=runCatching { client.abortGeo(entry.token) }.getOrNull() ?: observed
                            entry.status?.let { finishGeo(entry,it) }
                            throw failure
                        }
                    }
                    finishGeo(entry,checkNotNull(entry.status))
                }
            } }
        }

        internal suspend fun geoStatus(transactionId: String, abort: Boolean): dev.aerove.lite.flutter.MeowGeoRuntimeResult =
            withContext(NonCancellable+Dispatchers.IO) { listenerChanges.withLock {
                val entry=geoEntry?.takeIf { it.token.transactionId == transactionId } ?: error("Unknown or superseded Geo transaction")
                check(!stopping && lease.isCurrent()) { "Geo session was retired" }
                CoreApiClient(ControlEndpoint(endpoint.port,endpoint.secret)).use { client ->
                    val status=if(abort) client.abortGeo(entry.token) else client.geoStatus(entry.token)
                    finishGeo(entry,status)
                }
            } }

        private suspend fun finishGeo(entry: GeoEntry, status: MeowGeoStatus): dev.aerove.lite.flutter.MeowGeoRuntimeResult {
            require(status.token == entry.token);entry.status=status
            if(status.state == "committed") {
                val applied=checkNotNull(status.applied)
                applied.verify(entry.candidate,entry.expected)
                if(entry.identity == null) {
                    check(!stopping && lease.isCurrent()) { "Committed Geo belongs to a retired session" }
                    val binding=entry.expected.hostBinding.copy(compiledSha256=applied.compiledSha256)
                    lease.commitGeo(entry.candidate,binding)
                    geoBinding=binding
                    entry.identity=entry.persist(applied)
                }
            }
            if(status.terminal && status.sourceReadsJoined && !entry.resourcesReleased) {
                if(status.state != "committed") entry.discard()
                entry.resources.closeAfterNativeJoin();entry.resourcesReleased=true
            }
            return dev.aerove.lite.flutter.MeowGeoRuntimeResult(status,entry.identity)
        }

        private var coreEpoch: String? = null
        private fun acceptEpoch(epoch: String) {
            val actual = MeowHttpPolicyCodec.epoch(epoch)
            check(coreEpoch?.let { actual.toULong() >= it.toULong() } != false) { "Stale Meow session epoch" }
            coreEpoch = actual
            listenerReadback = listenerReadback?.copy(coreEpoch = actual)
        }
        private val policy = MeowHostPolicyClient(handle, listenerChanges, { coreEpoch }, ::acceptEpoch,
            object : MeowHostPolicyClient.Commands {
                override fun epoch() = commands.listeners(handle).coreEpoch
                override fun command(json: String) = commands.hostPolicy(handle, json)
            })
        override val establishedBuilderSettings get() = lease.establishedBuilderSettings
        @Volatile override var initialListenerReadback: ListenerReadback? = null
            private set
        @Volatile var listenerReadback: ListenerReadback? = null
            private set

        override suspend fun awaitReady(): Unit = withTimeout(45_000) { coroutineScope {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!identityObserved) {
                    identityObserver = lease.observeIdentityChanges { identityEvents.trySend(Unit) }
                    identityObserved = true
                }
            }
            val waiting = async(Dispatchers.IO) { commands.awaitReady(handle) }
            try {
                waiting.await()
                withContext(Dispatchers.IO) {
                    check(!stopping && lease.isCurrent()) { "Meow readiness intent was superseded" }
                    val status = commands.status(handle)
                    check(status.state == MeowState.Running && status.coreReady && status.runtimeMode == config.runtimeMode &&
                        status.apiAddress == "127.0.0.1:${endpoint.port}") { status.error ?: "Meow Core/control is not ready" }
                    val actual = commands.listeners(handle)
                    if (config.initiallyParked) {
                        check(!status.listening && !status.tunReady && !status.userListenersReady &&
                            !actual.listening && !actual.tunReady && !actual.userListenersReady && actual.activePorts.isEmpty()) { "Parked Meow opened listeners" }
                    } else {
                        val tun = config.runtimeMode == NativeCore.RuntimeMode.Tun
                        check(status.listening && status.userListenersReady && status.tunReady == tun &&
                            actual.listening && actual.userListenersReady && actual.tunReady == tun) { "Meow listener readiness differs from requested mode" }
                    }
                    acceptEpoch(actual.coreEpoch)
                    val policies = policy.read { !stopping && lease.isCurrent() }
                    val expected = lease.startupPolicies
                    check(policies.http == expected.http && policies.probe == expected.probe && policies.mode == expected.mode &&
                        policies.onlyProxy == expected.onlyProxy && policies.controller.enabled == expected.controller.enabled) { "Meow startup policies differ from persisted plan" }
                    lease.verifyControl(endpoint)
                    while (identityEvents.tryReceive().isSuccess) invalidateIdentity()
                    if (actual.listening) lease.markUsable()
                    synchronized(gate) {
                        check(!stopping && lease.isCurrent()) { "Meow readiness intent was superseded" }
                        initialListenerReadback = ListenerReadback(actual.listening, actual.tunReady, actual.userListenersReady, checkNotNull(coreEpoch), actual.activePorts)
                        listenerReadback = initialListenerReadback
                    }
                }
                lease.startupSettings?.let { settings ->
                    check(config.initiallyParked && !lease.startupPolicies.controller.enabled) { "Shared startup must be private-only" }
                    val resume = lease.restoreStartupSelection(endpoint)
                    coroutineContext.ensureActive()
                    check(!stopping && lease.isCurrent()) { "Shared startup selection was superseded" }
                    configurePolicies(settings)
                    coroutineContext.ensureActive()
                    check(!stopping && lease.isCurrent()) { "Shared startup policy was superseded" }
                    if (resume) {
                        val actual = setListening(true)
                        synchronized(gate) {
                            check(!stopping && lease.isCurrent()) { "Shared startup activation was superseded" }
                            initialListenerReadback = actual
                        }
                    } else lease.parkedTun()
                }
            } catch (failure: Throwable) {
                val observer = startupObserver.get()
                val safeBefore = runCatching { commands.startupDiagnostic(handle) }
                val before = observer?.let { runCatching { commands.status(handle) } }
                try { stop() } catch (cleanup: Throwable) { suppressMeowStartupCleanup(failure, cleanup) }
                val safeAfter = runCatching { commands.startupDiagnostic(handle) }
                runCatching { android.util.Log.w("MeowStartup", meowStartupFailureDiagnostic(handle, safeBefore, safeAfter)) }
                if (observer != null && startupObserver.get() === observer) {
                    val after = runCatching { commands.status(handle) }
                    runCatching { observer.receive(StartupFailure(handle, intentGeneration, coreEpoch, failure, checkNotNull(before), after)) }
                }
                throw failure
            }
        } }

        override suspend fun setListening(enabled: Boolean): ListenerReadback {
            val intent = listenerIntent.incrementAndGet()
            val caller = coroutineContext[Job]
            var submitted = false
            var transactionCompleted = false
            val result = try { withContext(NonCancellable + Dispatchers.IO) { listenerChanges.withLock {
                check(!stopping && lease.isCurrent()) { "Meow listener owner is no longer current" }
                if (intent != listenerIntent.get() || caller?.isActive == false) throw CancellationException("Meow listener intent superseded")
                val actual = if (!enabled) {
                    submitted = true
                    commands.listener(handle,"park",-1).also {
                        check(!it.listening && !it.tunReady && !it.userListenersReady && it.activePorts.isEmpty()) { "Meow park did not confirm listener join" }
                        lease.parkedTun()
                    }
                } else if (config.runtimeMode == NativeCore.RuntimeMode.Proxy) {
                    submitted = true
                    commands.listener(handle,"resumeProxy",-1)
                } else {
                    val existing = commands.listeners(handle)
                    if (existing.listening && existing.tunReady && existing.userListenersReady) existing
                    else {
                        val staged = lease.stageTun()
                        if (intent != listenerIntent.get() || caller?.isActive == false || stopping || !lease.isCurrent()) {
                            lease.discardUnsubmittedTun(staged)
                            throw CancellationException("Meow attach intent superseded before submission")
                        }
                        submitted = true
                        commands.listener(handle,"attachTun",staged.descriptor).also {
                            check(it.listening && it.tunReady && it.userListenersReady) { "Meow attach is not ready" }
                            lease.commitTun(staged)
                        }
                    }
                }
                if (enabled) check(actual.listening && actual.userListenersReady && actual.tunReady == (config.runtimeMode == NativeCore.RuntimeMode.Tun)) { "Meow resumed listener mode mismatch" }
                acceptEpoch(actual.coreEpoch)
                if (actual.listening) lease.markUsable()
                val readback = ListenerReadback(actual.listening,actual.tunReady,actual.userListenersReady,actual.coreEpoch,actual.activePorts)
                synchronized(gate) {
                    if (!stopping && lease.isCurrent() && intent == listenerIntent.get() && caller?.isActive != false) listenerReadback = readback
                }
                transactionCompleted = true
                readback
            } } } catch (failure: Throwable) {
                // A failed accepted attach may still own a dup/driver. Only full stop confirms reclamation.
                if (submitted && !transactionCompleted) try { stop() } catch (join: Throwable) { failure.addSuppressed(join) }
                throw failure
            }
            coroutineContext.ensureActive()
            if (stopping || !lease.isCurrent() || intent != listenerIntent.get()) throw CancellationException("Meow listener receipt superseded")
            return result
        }

        override suspend fun configurePolicies(settings: LiteSettings) {
            val target = lease.currentPolicies(settings)
            policy.apply(target) { !stopping && lease.isCurrent() && lease.currentPolicies(settings) == target }
        }
        suspend fun readGraphReadiness(): ListenerReadback = withContext(Dispatchers.IO) { listenerChanges.withLock {
            check(!stopping && lease.isCurrent()) { "Meow graph owner is no longer current" }
            val status = commands.status(handle)
            val actual = commands.listeners(handle)
            check(status.state == MeowState.Running && status.coreReady && status.runtimeMode == config.runtimeMode &&
                status.apiAddress == "127.0.0.1:${endpoint.port}" && status.listening == actual.listening &&
                status.tunReady == actual.tunReady && status.userListenersReady == actual.userListenersReady) { "Meow graph readiness changed" }
            acceptEpoch(actual.coreEpoch)
            ListenerReadback(actual.listening, actual.tunReady, actual.userListenersReady, actual.coreEpoch, actual.activePorts)
                .also { listenerReadback = it }
        } }
        suspend fun readGraphPolicy() = policy.read { !stopping && lease.isCurrent() }
        override suspend fun configureNetworkDns(snapshot: SystemDnsSnapshot) {
            check(!stopping && lease.isCurrent()) { "Meow network owner is no longer current" }
            val captured = lease.captureNetwork(snapshot)
            policy.network(captured.physical, { !stopping && lease.isCurrent() && lease.networkCurrent(captured) }) {
                // Same session gate: no attach can use old Host DNS after Native network commit.
                check(!stopping && lease.isCurrent()) { "Meow network receipt superseded" }
                lease.commitNetwork(captured)
            }
            coroutineContext.ensureActive()
        }
        suspend fun invalidateIdentity() {
            lease.clearIdentityCache()
            policy.invalidate { !stopping && lease.isCurrent() }
        }

        override suspend fun awaitExit(): Unit = coroutineScope {
            val terminal = async(Dispatchers.IO) { commands.awaitExit(handle) }
            val identityFailure = CompletableDeferred<Throwable>()
            val identityWorker = launch {
                try { for (event in identityEvents) invalidateIdentity() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { identityFailure.complete(failure) }
            }
            try { select<Unit> { terminal.onAwait { }; identityFailure.onAwait { throw it } } }
            catch (failure: Throwable) {
                try { stop() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            } finally { identityWorker.cancelAndJoin() }
        }
        override suspend fun reloadRules(next: PreparedProfile, current: () -> Boolean): Boolean = listenerChanges.withLock {
            fun owns() = !stopping && lease.isCurrent() && current()
            if (!owns()) return@withLock false
            lease.reloadRules(next, endpoint, ::owns)
        }

        override suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
            synchronized(gate) { stopping = true }
            listenerIntent.incrementAndGet()
            cleanup.withLock {
                if (!joined) { commands.stop(handle); joined = true }
                geoEntry?.takeIf { !it.resourcesReleased }?.let { entry ->
                    entry.discard()
                    entry.resources.closeAfterNativeJoin();entry.resourcesReleased=true
                }
                identityObserver?.close(); identityObserver = null; identityEvents.close()
                if (!released) { release(lease); released = true }
                synchronized(gate) { retained -= this@Session }
            }
        }
    }
}

// Recovery-copy recognition: kotlinx.coroutines 1.10.2 unwrapImpl (Apache-2.0), https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/jvm/src/internal/StackTraceRecovery.kt
// Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
private fun unrecoveredMeowStartupFailure(error: Throwable): Throwable {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current = error
    while (seen.add(current)) {
        val cause = current.cause ?: return current
        if (cause.javaClass != current.javaClass || cause in seen ||
            current.stackTrace.none { it.className.startsWith("_COROUTINE.") }) return current
        current = cause
    }
    return current
}

internal fun suppressMeowStartupCleanup(failure: Throwable, cleanup: Throwable) {
    val primary = unrecoveredMeowStartupFailure(failure)
    val secondary = unrecoveredMeowStartupFailure(cleanup)
    if (primary === secondary || primary.suppressed.any { unrecoveredMeowStartupFailure(it) === secondary }) return
    primary.addSuppressed(secondary)
}
