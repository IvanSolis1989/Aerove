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
        // Must be idempotent. Release FD/file/profile/lifecycle only after confirmed Native join.
        suspend fun release()
    }
    interface Commands {
        fun start(planJson: String, fd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?): Long
        fun awaitReady(handle: Long)
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
                Session(handle, owned).also { session = it; synchronized(gate) { retained += it } }
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

    inner class Session internal constructor(private val handle: Long, private val lease: Lease) : ListenerPolicySession {
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
            lease.appliedConfiguration, lease.prepared.revision, lease.prepared.generation)
        override val builderDnsEnabled get() = lease.builderDnsEnabled
        override val coreDnsEnabled get() = lease.coreDnsEnabled
        override val builderReservedFakeIpRanges get() = lease.builderReservedFakeIpRanges.toList()
        override val coreReservedFakeIpRanges get() = lease.coreReservedFakeIpRanges.toList()
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
            } catch (failure: Throwable) {
                try { stop() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
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
        override suspend fun stop() = withContext(NonCancellable + Dispatchers.IO) {
            synchronized(gate) { stopping = true }
            listenerIntent.incrementAndGet()
            cleanup.withLock {
                if (!joined) { commands.stop(handle); joined = true }
                identityObserver?.close(); identityObserver = null; identityEvents.close()
                if (!released) { release(lease); released = true }
                synchronized(gate) { retained -= this@Session }
            }
        }
    }
}
