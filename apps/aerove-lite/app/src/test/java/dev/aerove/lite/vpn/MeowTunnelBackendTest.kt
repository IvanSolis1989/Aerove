package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.*
import dev.aerove.lite.data.*
import dev.aerove.lite.networksettings.NetworkSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.aerove.lite.flutter.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

class MeowTunnelBackendTest {
    @get:Rule val temp = TemporaryFolder()
    private class Native : MeowTunnelBackend.Commands {
        var onStart: () -> Unit = {}; var onReady: () -> Unit = {}; var onStop: () -> Unit = {}; var onExit: () -> Unit = {}
        var onListener: (String,Int) -> Unit = {_,_->}
        var starts = 0; var stops = 0; var payload = ""; var descriptor = 0
        var statusReads = 0; var statusFailure: Throwable? = null
        var parked = false;var tunMode = false
        var port = 49152
        val policy = MeowPolicyFixture()
        override fun start(planJson: String, fd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?): Long {
            starts++; payload=planJson;descriptor=fd;policy.startup(org.json.JSONObject(planJson).getString("hostPolicyYaml"));onStart();return 1
        }
        override fun awaitReady(handle: Long) = onReady()
        override fun awaitExit(handle: Long) = onExit()
        override fun stop(handle: Long) { stops++;onStop() }
        override fun listener(handle:Long,action:String,fd:Int):MeowListenerStatus {
            onListener(action,fd)
            parked = action == "park"
            return listeners(handle)
        }
        override fun status(handle: Long): MeowStatus {
            statusReads++; statusFailure?.let { throw it }
            return MeowStatus(MeowState.Running, null, "127.0.0.1:$port", if(tunMode) NativeCore.RuntimeMode.Tun else NativeCore.RuntimeMode.Proxy,
                tunMode && !parked, !parked, !parked, true)
        }
        override fun hostPolicy(handle: Long, request: String) = policy.command(request)
        override fun listeners(handle: Long) = MeowListenerStatus(!parked,tunMode && !parked,!parked,if(parked) emptyList() else listOf(7890),policy.epoch(),policy.onlyProxy,policy.probe.unifiedDelay,policy.probe.tcpConcurrent,policy.mode)
    }
    private inner class Fixture(parked: Boolean = false, tunMode: Boolean = false, profileId: String = "profile",
        revision: String = "source-revision", generation: String = "resource-generation", port: Int = 49152,
        settingsOverride: LiteSettings? = null) {
        val settings = settingsOverride ?: LiteSettings(network = NetworkSettings(vpnEnabled = tunMode))
        val plan = VpnPlan(profileId,settings)
        val yaml = "proxies: [{name: direct, type: direct}]\nrules: ['MATCH,direct']"
        val bundle = File(temp.root,"resources/bundles/profile/resource-generation").apply{mkdirs()}
        val prepared = PreparedProfile(profileId,yaml,yaml,revision,generation,
            if (profileId.startsWith("flutter:")) null else bundle,null)
        val config = MeowRuntimeConfigComposer(temp.root,cacheDirectory=File(temp.root,"meow-cache/session").apply{mkdirs()}).compose(prepared,settings,if(tunMode && !parked)42 else -1,port,"0123456789abcdef0123456789abcdef",
            initiallyParked=parked,physicalNetworkHandle="42")
        var current = true;var released = 0;var verified = 0;var failRelease = false
        var admission: () -> Boolean = { true }
        var stages=0;var commits=0;var parkedFds=0;var discarded=0;var networkCommits=0;var identityClears=0
        var identityCallback: (() -> Unit)? = null; var observerClosed = false; var usable = 0
        val pending=mutableSetOf<MeowTunnelBackend.TunReceipt>()
        val native = Native().also { it.parked=parked;it.tunMode=tunMode;it.port=port }
        val lease = object : MeowTunnelBackend.Lease {
            override val prepared get() = this@Fixture.prepared
            override val configuration get() = config
            override val appliedConfiguration = AppliedConfigSnapshot(profileId,File(temp.root,"applied-${System.nanoTime()}.yaml").apply{writeText(config.engineYaml)})
            override val protector: SocketProtector? = if(tunMode) SocketProtector { true } else null
            override val connectionOwner: ConnectionOwnerLookup? = null
            override val builderDnsEnabled = false
            override val coreDnsEnabled = false
            override val builderReservedFakeIpRanges = emptyList<String>()
            override val coreReservedFakeIpRanges = emptyList<String>()
            override val startupPolicies = MeowHostPolicyTarget.from(settings, "AEROVE-Lite")
            override val establishedBuilderSettings get() = settings.takeIf { !tunMode || commits > 0 || !parked }
            override fun currentPolicies(fallback: LiteSettings) = MeowHostPolicyTarget.from(fallback, "AEROVE-Lite")
            override suspend fun captureNetwork(expectedDns: SystemDnsSnapshot) = object : MeowTunnelBackend.NetworkReceipt {
                override val physical = MeowPhysicalNetwork("43", expectedDns)
            }
            override fun networkCurrent(receipt: MeowTunnelBackend.NetworkReceipt) = current
            override suspend fun commitNetwork(receipt: MeowTunnelBackend.NetworkReceipt) { networkCommits++ }
            override fun observeIdentityChanges(changed: () -> Unit): AutoCloseable { identityCallback = changed; return AutoCloseable { observerClosed = true } }
            override suspend fun markUsable() { usable++ }
            override fun clearIdentityCache() { identityClears++ }
            override suspend fun stageTun():MeowTunnelBackend.TunReceipt {
                stages++;return object:MeowTunnelBackend.TunReceipt{override val descriptor=43}.also{pending+=it}
            }
            override suspend fun commitTun(receipt:MeowTunnelBackend.TunReceipt){assertTrue(pending.remove(receipt));commits++}
            override suspend fun discardUnsubmittedTun(receipt:MeowTunnelBackend.TunReceipt){assertTrue(pending.remove(receipt));discarded++}
            override suspend fun parkedTun(){parkedFds++;pending.clear()}
            override fun isCurrent() = current && admission()
            override suspend fun verifyControl(endpoint: CoreEndpoint) { verified++ }
            override suspend fun release() { check(!failRelease) { "lease cleanup pending" };pending.clear();released++ }
        }
        val backend = MeowTunnelBackend({lease},native)
    }
    private suspend fun CountDownLatch.entered() { assertTrue(withContext(Dispatchers.IO){await(3,TimeUnit.SECONDS)}) }

    private suspend fun parkedCandidate(store: RuntimeHandoffStore, controllerEnabled: Boolean = false, vpnEnabled: Boolean = false): MeowRuntimeSnapshot {
        val yaml = "proxies: [{name: direct, type: direct}]\nrules: ['MATCH,direct']"
        val record = JSONObject().put("digest", "fixture").put("yaml-md5", "b".repeat(32)).put("groups", 0).put("proxies", 1).put("rules", 1)
        val setup = JSONObject().put("test-url", "https://example.test/probe").put("skip-cert-verify", false)
            .put("selected-map", JSONObject()).put("applied", record)
        val desired = LiteSettings(network = NetworkSettings(vpnEnabled = vpnEnabled), externalController =
            dev.aerove.lite.controllerprefs.ExternalControllerPreferences(controllerEnabled, if (controllerEnabled) "fixture-controller-secret" else ""))
        val settings = MeowRuntimeSnapshotSettings.encode(desired).put("flutterSetup", setup)
        val options = JSONObject().put("enable", vpnEnabled).put("ipv6", false).put("dnsHijacking", desired.network.dnsHijacking)
            .put("allowBypass", desired.network.allowBypass).put("systemProxy", desired.network.systemProxy)
            .put("port", 0).put("accessControlProps", JSONObject()).put("bypassDomain", org.json.JSONArray()).put("stack", "")
        val notification = JSONObject().put("stopTip", "stop").put("startTip", "start").put("localNetworkTip", "lan")
            .put("currentProfileName", "fixture").put("stopText", "stop").put("showStopAction", true).put("onlyStatisticsProxy", false)
        val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "fixture", "fixture", "a".repeat(64), yaml, runtimeSha256(yaml.toByteArray()),
            record.toString(), settings.toString(), options.toString(), notification.toString())
        val before = store.readCurrent()
        return store.commit(handoff, store.currentEpoch(), before?.revision)
    }

    @Test fun parkedGraphIsolatesEnabledControllerAndRetainsDesiredPolicyForAuthorizedSuccessor() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("controller-graph"))
        val candidate = parkedCandidate(store, controllerEnabled = true, vpnEnabled = true)
        val server = MockWebServer(); server.start()
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(10, TimeUnit.SECONDS)) }; f.native.onStop = { exit.countDown() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/route" -> MockResponse().setBody("""{"core-epoch":"${f.native.policy.epoch()}","route-version":"1","picks":{}}""")
                "/proxies" -> MockResponse().setBody("""{"proxies":{"direct":{"type":"Direct"}}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        var token = 0L; var submittedSettings: LiteSettings? = null
        val owner = MeowParkedGraphOwner(store, { ++token }, { it == token }, { _, _, current ->
            f.admission = current
            MeowTunnelBackend({ plan -> submittedSettings = plan.settings; f.lease }, f.native)
        }, { _, _ -> })
        try {
            val identity = owner.applyStaged(candidate)
            assertFalse("A parked graph enabled the independent native user controller", f.native.policy.enabled)
            assertFalse(submittedSettings!!.externalController.enabled)
            val desired = MeowRuntimeSnapshotSettings.decode(store.readLastApplied()!!.handoff.runtimeSettingsJson)
            assertTrue(desired.externalController.enabled); assertEquals("fixture-controller-secret", desired.externalController.secret)
            val applied = JSONObject(store.readLastApplied()!!.handoff.runtimeSettingsJson).getJSONObject("flutterGraphPolicy")
            assertTrue(applied.getBoolean("privateOnly")); assertFalse(applied.getJSONObject("userController").getBoolean("enabled"))
            assertTrue(applied.getJSONObject("userController").isNull("address"))
            val runtime = MeowSetupRuntime(identity.revision, identity.intentEpoch, identity.compiledSha256,
                identity.proof.coreEpoch, identity.proof.serviceOwner, identity.proof.sessionGeneration, false)
            owner.applyUpdate(runtime, MeowSetupProjection(desired, candidate.handoff.notificationJson, null, null))
            assertFalse("A live update reopened the parked user controller", f.native.policy.enabled)
            owner.retire(); assertEquals(1, f.released)
            val serviceProjection = meowServiceProjection(store.readLastApplied()!!.handoff, desired, null, null, null, "https://example.test/probe")
            assertTrue(MeowRuntimeSnapshotSettings.decode(serviceProjection.runtimeSettingsJson).externalController.enabled)
            assertFalse(JSONObject(serviceProjection.runtimeSettingsJson).has("flutterGraphPolicy"))
            val requested = store.beginStart(candidate.revision, candidate.intentEpoch)
            val tun = Fixture(tunMode = true, profileId = requested.profileId, revision = requested.revision,
                generation = requested.handoff.compiledSha256, settingsOverride = desired)
            val tunnel = tun.backend.open(tun.plan); tunnel.awaitReady()
            assertTrue(tun.native.policy.enabled); assertTrue(tunnel.listenerReadback!!.tunReady)
            store.commitAppliedWithProjection(requested.revision, requested.intentEpoch, RuntimeAppliedProof(33, 7, "1"), serviceProjection)
            tunnel.stop(); assertEquals(1, tun.released)
            store.invalidate(requested.intentEpoch)
            val afterStop = store.readCurrent()!!
            val restoredExit = CountDownLatch(1)
            f.native.onExit = { check(restoredExit.await(10, TimeUnit.SECONDS)) }; f.native.onStop = { restoredExit.countDown() }
            owner.applyStaged(afterStop)
            assertFalse(f.native.policy.enabled); assertTrue(MeowRuntimeSnapshotSettings.decode(store.readLastApplied()!!.handoff.runtimeSettingsJson).externalController.enabled)
            owner.retire(); assertEquals(2, f.released)
        } finally { owner.retire(); server.shutdown() }
    }

    @Test fun graphOwnerLoadsPrivateProxyAndFailedJoinBlocksSuccessorWithoutReleasingLease() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("graph"))
        val candidate = parkedCandidate(store)
        val server = MockWebServer(); server.start()
        var httpEpoch = "1"
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/route" -> MockResponse().setBody("""{"core-epoch":"$httpEpoch","route-version":"1","picks":{}}""")
                "/proxies" -> MockResponse().setBody("""{"proxies":{"direct":{"type":"Direct"}}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(10, TimeUnit.SECONDS)) }
        var ownerToken = 0L
        val owner = MeowParkedGraphOwner(store, { ++ownerToken }, { it == ownerToken }, { _, _, current ->
            f.admission = current; f.backend
        }, { _, _ -> })
        try {
            var identity = owner.applyStaged(candidate)
            assertTrue(store.isGraphCurrent(identity)); assertTrue(owner.identityIsCurrent(identity))
            assertFalse(store.readCurrent()!!.userRequested); assertFalse(owner.queryGraph()!!.listening)
            assertEquals(-1, f.native.descriptor); assertEquals(0, f.stages)
            val first = identity
            val metadata = JSONObject(candidate.handoff.runtimeSettingsJson).getJSONObject("flutterSetup")
            metadata.getJSONObject("applied").put("digest", "metadata-readback")
            identity = owner.refreshSetup(MeowSetupRuntime(identity.revision, identity.intentEpoch, identity.compiledSha256,
                identity.proof.coreEpoch, identity.proof.serviceOwner, identity.proof.sessionGeneration, false), metadata.toString())
            assertFalse(owner.identityIsCurrent(first)); assertTrue(store.isGraphCurrent(identity))
            assertEquals("metadata-readback", JSONObject(store.readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            f.native.onStop = { error("fixture native join unconfirmed") }
            try { owner.retire(); fail("unconfirmed join was accepted") } catch (_: IllegalStateException) { }
            assertNull(owner.queryGraph()); assertEquals(0, f.released)
            try { owner.applyStaged(candidate); fail("successor started before join") } catch (_: IllegalStateException) { }
            assertEquals(1, f.native.starts); assertEquals(identity, store.readAppliedIdentity())
            f.native.onStop = { exit.countDown() }
            owner.retire(); assertEquals(1, f.released)
            val tun = Fixture(tunMode = true)
            val tunnel = tun.backend.open(tun.plan)
            tunnel.awaitReady(); assertTrue(tunnel.listenerReadback!!.tunReady)
            assertNull(owner.queryGraph()); assertEquals(1, f.released)
            tunnel.stop(); assertEquals(1, tun.released)
            store.invalidate()
            val successor = store.readCurrent()!!
            val successorExit = CountDownLatch(1)
            f.native.onExit = { check(successorExit.await(10, TimeUnit.SECONDS)) }
            f.native.onStop = { successorExit.countDown() }
            val restored = owner.applyStaged(successor)
            assertTrue(owner.identityIsCurrent(restored)); assertFalse(owner.queryGraph()!!.listening)
            assertNotEquals(identity.proof.serviceOwner, restored.proof.serviceOwner)
            val retiringEntered = CountDownLatch(1); val retireRelease = CountDownLatch(1)
            f.native.onStop = { successorExit.countDown(); retiringEntered.countDown(); check(retireRelease.await(5, TimeUnit.SECONDS)) }
            val retiring = async(Dispatchers.Default) { owner.retire() }
            retiringEntered.entered()
            val latestExit = CountDownLatch(1)
            f.native.onExit = { check(latestExit.await(10, TimeUnit.SECONDS)) }
            f.native.onStop = { latestExit.countDown() }
            val replacing = async(start = CoroutineStart.UNDISPATCHED) { owner.applyStaged(successor) }
            retireRelease.countDown()
            val latest = withTimeout(4_000) { replacing.await() }
            withTimeout(4_000) { retiring.await() }
            assertTrue(owner.identityIsCurrent(latest)); assertEquals(2, f.released)
            owner.retire(); assertEquals(3, f.released)
            httpEpoch = "2"
            try { owner.applyStaged(successor); fail("foreign HTTP epoch was applied") }
            catch (failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("epochs differ")) }
            assertEquals(latest, store.readAppliedIdentity()); assertNull(owner.queryGraph())
            assertEquals(4, f.released)
        } finally { f.native.onStop = { exit.countDown() }; owner.retire(); server.shutdown() }
    }

    @Test fun graphRetirementUnblocksLateReadyAndNeverPublishesApplied() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("late-graph")); val candidate = parkedCandidate(store)
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision, generation = candidate.handoff.compiledSha256)
        val ready = CountDownLatch(1); val exit = CountDownLatch(1)
        f.native.onReady = { ready.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        var token = 0L
        val owner = MeowParkedGraphOwner(store, { ++token }, { it == token }, { _, _, current -> f.admission = current; f.backend }, { _, _ -> })
        val loading = async(Dispatchers.Default) { runCatching { owner.applyStaged(candidate) } }
        ready.entered()
        withTimeout(4_000) { owner.retire() }
        assertTrue(loading.await().isFailure); assertNull(owner.queryGraph()); assertNull(store.readAppliedIdentity())
        assertEquals(1, f.released); assertEquals(1, f.native.starts)
    }
    @Test fun startupFailureCaptureKeepsOriginalAndSuppressedWithoutAnotherStop() = runBlocking<Unit> {
        val f = Fixture(); val original = java.io.IOException("fixture EOF"); val cleanup = java.io.IOException("fixture cleanup failed")
        f.native.onReady = { throw original }; f.native.onStop = { throw cleanup }
        var observed: MeowTunnelBackend.StartupFailure? = null
        MeowTunnelBackend.observeStartupFailures { observed = it }.use {
            val session = f.backend.open(f.plan.copy(ownerIntentToken = 17))
            try { session.awaitReady(); fail("must preserve startup failure") } catch (failure: java.io.IOException) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original }) }
            val actual = checkNotNull(observed)
            assertEquals(1L, actual.nativeGeneration); assertEquals(17L, actual.intentGeneration)
            assertTrue(generateSequence(actual.failure) { it.cause }.any { it === original })
            assertTrue(generateSequence(actual.failure.suppressed.single()) { it.cause }.any { it === cleanup })
            assertEquals(MeowState.Running, actual.nativeBeforeStop.getOrThrow().state)
            assertEquals(MeowState.Running, actual.nativeAfterStop.getOrThrow().state)
            assertEquals(1, f.native.stops); assertEquals(0, f.released); assertEquals(2, f.native.statusReads)
        }
        f.native.onStop = {}; f.backend.stopAndJoinRetained()
    }
    @Test fun absentStartupObserverDoesNotReadAdditionalNativeStatus() = runBlocking<Unit> {
        val f = Fixture(); val original = java.io.IOException("fixture EOF"); f.native.onReady = { throw original }
        val session = f.backend.open(f.plan)
        try { session.awaitReady(); fail("must preserve startup failure") } catch (failure: java.io.IOException) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original }) }
        assertEquals(0, f.native.statusReads); assertEquals(1, f.native.stops); assertEquals(1, f.released)
    }
    @Test fun staleObserverCloseAndDiagnosticErrorsCannotReplaceOriginalFailure() = runBlocking<Unit> {
        val f = Fixture(); val original = java.io.IOException("fixture EOF"); f.native.onReady = { throw original }
        f.native.statusFailure = java.io.IOException("fixture status unavailable")
        var observed: MeowTunnelBackend.StartupFailure? = null
        val receiver: (MeowTunnelBackend.StartupFailure) -> Unit = { observed = it; error("fixture save failed") }
        val old = MeowTunnelBackend.observeStartupFailures(receiver); old.close()
        MeowTunnelBackend.observeStartupFailures(receiver).use {
            old.close()
            assertTrue(runCatching { MeowTunnelBackend.observeStartupFailures(receiver) }.isFailure)
            val session = f.backend.open(f.plan)
            try { session.awaitReady(); fail("must preserve startup failure") } catch (failure: java.io.IOException) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original }) }
            val actual = checkNotNull(observed)
            assertTrue(generateSequence(actual.failure) { it.cause }.any { it === original })
            assertSame(f.native.statusFailure, actual.nativeBeforeStop.exceptionOrNull())
            assertSame(f.native.statusFailure, actual.nativeAfterStop.exceptionOrNull())
            assertEquals(1, f.native.stops); assertEquals(1, f.released)
        }
    }
    @Test fun packageEventUsesSameSessionIdentityCommandAndObserverClosesAfterJoin()=runBlocking<Unit>{
        val f = Fixture(); val session = f.backend.open(f.plan); session.awaitReady()
        val exit = CountDownLatch(1); val invalidated = CompletableDeferred<Unit>()
        f.native.onExit = { check(exit.await(3,TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        f.native.policy.onOperation = { if (it == "invalidateIdentity") invalidated.complete(Unit) }
        val waiting = launch { session.awaitExit() }
        checkNotNull(f.identityCallback).invoke()
        withTimeout(3_000) { invalidated.await() }
        assertEquals(1, f.identityClears); assertEquals(1, f.native.starts)
        session.stop(); waiting.join()
        assertTrue(f.observerClosed); assertEquals(1, f.released)
    }
    @Test fun actualPreparedPlanAndReadyReceiptKeepOwnedEndpointLineage()=runBlocking<Unit>{
        val f=Fixture();val session=f.backend.open(f.plan)
        session.awaitReady();assertEquals(1,f.verified)
        assertEquals("source-revision",session.endpoint.profileRevision);assertEquals("resource-generation",session.endpoint.resourceGeneration)
        assertEquals(-1,f.native.descriptor)
        val json=org.json.JSONObject(f.native.payload);assertEquals("42",json.getJSONObject("physicalNetwork").getString("handle"))
        assertEquals(f.config.engineYaml,json.getString("engineYaml"));assertTrue(session.initialListenerReadback!!.listening)
        assertEquals(1,f.usable)
        session.stop();session.stop();assertEquals(1,f.native.stops);assertEquals(1,f.released);assertTrue(f.observerClosed)
    }
    @Test fun cancellationDuringLateStartStopsReceiptBeforeReleasingResources()=runBlocking<Unit>{
        val f=Fixture();val entered=CountDownLatch(1);val exit=CountDownLatch(1)
        f.native.onStart={entered.countDown();check(exit.await(3,TimeUnit.SECONDS))}
        val opening=async(Dispatchers.Default){f.backend.open(f.plan)}
        entered.entered();opening.cancel();assertEquals(0,f.released);exit.countDown()
        withTimeout(3000){opening.join()};assertTrue(opening.isCancelled)
        assertEquals(1,f.native.stops);assertEquals(1,f.released)
    }
    @Test fun cancellationWhileNativeReadyIsBlockedStopsAndJoinsBeforeLeaseRelease()=runBlocking<Unit>{
        val f=Fixture();val entered=CountDownLatch(1);val exit=CountDownLatch(1)
        f.native.onReady={entered.countDown();check(exit.await(3,TimeUnit.SECONDS))}
        f.native.onStop={exit.countDown()}
        val session=f.backend.open(f.plan);val ready=launch(Dispatchers.Default){session.awaitReady()}
        entered.entered();ready.cancel();withTimeout(3000){ready.join()}
        assertEquals(1,f.native.stops);assertEquals(1,f.released);assertNull(session.initialListenerReadback)
    }
    @Test fun unconfirmedJoinRetainsLeaseAndBlocksReplacementUntilRetrySucceeds()=runBlocking<Unit>{
        val f=Fixture();val session=f.backend.open(f.plan)
        f.native.onStop={throw java.io.IOException("join unconfirmed")}
        try{session.stop();fail("stop must propagate")}catch(expected:java.io.IOException){assertEquals("join unconfirmed",expected.message)}
        assertEquals(0,f.released)
        try{f.backend.open(f.plan);fail("must block replacement")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("not joined"))}
        f.native.onStop={};session.stop();assertEquals(1,f.released)
    }
    @Test fun supersededAndInvalidParkedReadyCannotPublishSuccess()=runBlocking<Unit>{
        val f=Fixture(true);val session=f.backend.open(f.plan)
        f.native.parked=false
        try{session.awaitReady();fail("parked listener mismatch must fail")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("Parked"))}
        assertNull(session.initialListenerReadback);assertEquals(1,f.released)
        val next=Fixture();next.native.onStart={next.current=false}
        try{next.backend.open(next.plan);fail("late stale handle must stop")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("superseded"))}
        assertEquals(1,next.native.stops);assertEquals(1,next.released)
    }
    @Test fun failedOpenKeepsRecoveryReceiptAndTerminalWaitUsesRealCompletionEvent()=runBlocking<Unit>{
        val f=Fixture();f.native.onStart={f.current=false};f.native.onStop={throw java.io.IOException("join pending")}
        try{f.backend.open(f.plan);fail("stale open must fail")}catch(expected:IllegalStateException){
            assertEquals("join pending",expected.suppressed.single().message)
        }
        assertEquals(0,f.released);f.native.onStop={};f.backend.stopAndJoinRetained();assertEquals(1,f.released)
        val next=Fixture();val session=next.backend.open(next.plan);val entered=CountDownLatch(1);val exited=CountDownLatch(1)
        next.native.onExit={entered.countDown();check(exited.await(3,TimeUnit.SECONDS))}
        next.native.onStop={exited.countDown()}
        val terminal=launch(Dispatchers.Default){session.awaitExit()};entered.entered();assertFalse(terminal.isCompleted)
        session.stop();withTimeout(3000){terminal.join()};assertEquals(1,next.released)
    }
    @Test fun preHandleFailureRetainsFailedCleanupUntilExplicitRecovery()=runBlocking<Unit>{
        val f=Fixture();f.native.onStart={throw java.io.IOException("start rejected")};f.failRelease=true
        try{f.backend.open(f.plan);fail("must reject start")}catch(expected:java.io.IOException){assertEquals("lease cleanup pending",expected.suppressed.single().message)}
        assertEquals(0,f.native.stops);assertEquals(0,f.released)
        try{f.backend.open(f.plan);fail("must retain failed cleanup")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("not joined"))}
        f.failRelease=false;f.backend.stopAndJoinRetained();assertEquals(1,f.released)
    }
    @Test fun successfulParkedReadyReportsNoListenersAndNoDescriptor()=runBlocking<Unit>{
        val f=Fixture(true);val session=f.backend.open(f.plan);session.awaitReady()
        assertEquals(-1,f.native.descriptor);assertFalse(session.initialListenerReadback!!.listening)
        assertTrue(session.initialListenerReadback!!.activePorts.isEmpty());session.stop();assertEquals(1,f.released)
    }
    @Test fun tunDescriptorStaysSeparateFromSerializedPlanAtCommandBoundary()=runBlocking<Unit>{
        val f=Fixture(tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        assertEquals(42,f.native.descriptor);assertFalse(f.native.payload.contains("fd://"))
        assertFalse(org.json.JSONObject(f.native.payload).getJSONObject("tun").has("descriptor"))
        assertTrue(session.initialListenerReadback!!.tunReady);session.stop();assertEquals(1,f.released)
    }
    @Test fun parkDoesNotReleaseBorrowedDescriptorBeforeNativeJoinReceipt()=runBlocking<Unit>{
        val f=Fixture(tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        val entered=CountDownLatch(1);val joined=CountDownLatch(1)
        f.native.onListener={action,fd->assertEquals("park",action);assertEquals(-1,fd);entered.countDown();check(joined.await(3,TimeUnit.SECONDS))}
        val parking=async(Dispatchers.Default){session.setListening(false)};entered.entered()
        assertEquals(0,f.parkedFds);joined.countDown();assertFalse(withTimeout(3000){parking.await()}.listening)
        assertEquals(1,f.parkedFds);assertEquals(0,f.released);session.stop()
    }
    @Test fun cancelledAcceptedAttachKeepsCommittedDescriptorWithoutPublishingLateReceipt()=runBlocking<Unit>{
        val f=Fixture(parked=true,tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        val entered=CountDownLatch(1);val ready=CountDownLatch(1)
        f.native.onListener={action,fd->assertEquals("attachTun",action);assertEquals(43,fd);entered.countDown();check(ready.await(3,TimeUnit.SECONDS))}
        val attach=async(Dispatchers.Default){session.setListening(true)};entered.entered();attach.cancel()
        assertEquals(1,f.pending.size);assertEquals(0,f.released);ready.countDown();withTimeout(3000){attach.join()}
        assertEquals(1,f.commits);assertFalse(session.listenerReadback!!.listening);assertEquals(0,f.released)
        session.stop();assertEquals(1,f.released)
    }
    @Test fun failedAttachAndUnconfirmedStopKeepStagedDescriptorUntilRecoveryJoin()=runBlocking<Unit>{
        val f=Fixture(parked=true,tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        f.native.onListener={_,_->throw java.io.IOException("driver failure")};f.native.onStop={throw java.io.IOException("join pending")}
        try{session.setListening(true);fail("attach must fail")}catch(expected:java.io.IOException){assertEquals("driver failure",expected.message);assertEquals("join pending",expected.suppressed.single().message)}
        assertEquals(1,f.pending.size);assertEquals(0,f.released)
        f.native.onStop={};session.stop();assertTrue(f.pending.isEmpty());assertEquals(1,f.released)
    }
    @Test fun proxyResumeUsesNoTunAndPreservesSamePrivateEndpoint()=runBlocking<Unit>{
        val f=Fixture(parked=true);val session=f.backend.open(f.plan);session.awaitReady()
        val endpoint=session.endpoint
        f.native.onListener={action,fd->assertEquals("resumeProxy",action);assertEquals(-1,fd)}
        assertTrue(session.setListening(true).listening);assertSame(endpoint,session.endpoint);assertEquals(0,f.stages);session.stop()
    }
    @Test fun newerParkConvergesAfterAcceptedAttachAndSuppressesItsStaleReceipt()=runBlocking<Unit>{
        val f=Fixture(parked=true,tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        val entered=CountDownLatch(1);val ready=CountDownLatch(1)
        f.native.onListener={action,_->if(action=="attachTun"){entered.countDown();check(ready.await(3,TimeUnit.SECONDS))}}
        val attach=async(Dispatchers.Default){session.setListening(true)};entered.entered()
        val park=async(start=CoroutineStart.UNDISPATCHED){session.setListening(false)}
        ready.countDown();assertFalse(withTimeout(3000){park.await()}.listening);withTimeout(3000){attach.join()}
        assertTrue(attach.isCancelled);assertEquals(1,f.commits);assertEquals(1,f.parkedFds)
        assertFalse(session.listenerReadback!!.listening);assertEquals(0,f.released);session.stop()
    }
    @Test fun fullSessionPolicyAndNetworkUseSameNativeOwnerAndKeepFdLineage()=runBlocking<Unit>{
        val f=Fixture(parked=true,tunMode=true);val session=f.backend.open(f.plan);session.awaitReady()
        assertTrue(session is ListenerPolicySession)
        val saved=f.settings.copy(unifiedDelay=false,tcpConcurrent=false,
            requests=f.settings.requests.copy(globalUserAgent="AEROVE/hot",checkCertificate=false),
            backgroundPolicy=f.settings.backgroundPolicy.copy(onlyStatisticsProxy=false),
            externalController=dev.aerove.lite.controllerprefs.ExternalControllerPreferences(true,"persisted-secret"))
        session.configurePolicies(saved)
        assertFalse(f.native.policy.onlyProxy);assertFalse(f.native.policy.probe.unifiedDelay)
        assertTrue(f.native.policy.enabled);assertFalse(session.listenerReadback!!.listening)
        val before=session.endpoint
        session.configureNetworkDns(SystemDnsSnapshot("wlan0",mapOf("wlan0" to listOf("8.8.8.8"))))
        assertEquals(1,f.networkCommits);assertEquals("43",f.native.policy.networkHandle)
        session.invalidateIdentity();assertEquals(1,f.identityClears)
        session.setListening(true);assertEquals(1,f.stages);assertEquals(1,f.commits)
        assertSame(before,session.endpoint);assertEquals(1,f.native.starts);assertEquals(0,f.released)
        session.stop();assertEquals(1,f.released)
    }
    @Test fun unconfirmedNetworkResolverJoinCannotUpdateHostOrClaimSuccess()=runBlocking<Unit>{
        val f=Fixture();val session=f.backend.open(f.plan);session.awaitReady();f.native.policy.joinConfirmed=false
        val failure=runCatching { session.configureNetworkDns(SystemDnsSnapshot("wlan0",mapOf("wlan0" to listOf("8.8.8.8")))) }.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message!!.contains("unconfirmed"))
        assertEquals(0,f.networkCommits);assertEquals(0,f.released);assertEquals(0,f.native.stops)
        session.stop();assertEquals(1,f.released)
    }

}
