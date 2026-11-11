package dev.aerove.lite.vpn

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.*
import dev.aerove.lite.data.*
import dev.aerove.lite.networksettings.NetworkSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
        var startupReads = 0; var startupReadFailure: Throwable? = null
        var startupSnapshot: MeowStartupSnapshot? = null
        var parked = false;var tunMode = false
        var port = 49152
        val policy = MeowPolicyFixture()
        var onMetadataRenew: ((String) -> String)? = null
        override fun start(planJson: String, fd: Int, protector: SocketProtector?, owner: ConnectionOwnerLookup?): Long {
            starts++; payload=planJson;descriptor=fd;policy.startup(org.json.JSONObject(planJson).getString("hostPolicyYaml"));onStart();return 1
        }
        override fun awaitReady(handle: Long) = onReady()
        override fun startupDiagnostic(handle: Long): MeowStartupSnapshot? {
            startupReads++; startupReadFailure?.let { throw it }; return startupSnapshot
        }
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
        override fun hostPolicy(handle: Long, request: String) =
            if (JSONObject(request).getString("operation") == "renewGeoBinding") checkNotNull(onMetadataRenew)(request)
            else policy.command(request)
        override fun listeners(handle: Long) = MeowListenerStatus(!parked,tunMode && !parked,!parked,if(parked) emptyList() else listOf(7890),policy.epoch(),policy.onlyProxy,policy.probe.unifiedDelay,policy.probe.tcpConcurrent,policy.mode)
    }
    private inner class Fixture(parked: Boolean = false, tunMode: Boolean = false, profileId: String = "profile",
        revision: String = "source-revision", generation: String = "resource-generation", port: Int = 49152,
        settingsOverride: LiteSettings? = null, startupGated: Boolean = false) {
        val settings = settingsOverride ?: LiteSettings(network = NetworkSettings(vpnEnabled = tunMode))
        val plan = VpnPlan(profileId,settings)
        val yaml = "proxies: [{name: direct, type: direct}]\nrules: ['MATCH,direct']"
        val bundle = File(temp.root,"resources/bundles/profile/resource-generation").apply{mkdirs()}
        val prepared = PreparedProfile(profileId,yaml,yaml,revision,generation,
            if (profileId.startsWith("flutter:")) null else bundle,null)
        var config = MeowRuntimeConfigComposer(temp.root,cacheDirectory=File(temp.root,"meow-cache/session").apply{mkdirs()}).compose(prepared,if (startupGated) settings.copy(externalController = settings.externalController.copy(enabled = false)) else settings,if(tunMode && !parked)42 else -1,port,"0123456789abcdef0123456789abcdef",
            initiallyParked=parked,physicalNetworkHandle="42")
        var current = true;var released = 0;var verified = 0;var failRelease = false
        var startupGate = startupGated; var startupResume = true
        var restoreStartup: suspend () -> Unit = {}
        val startupEvents = mutableListOf<String>()
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
            override val startupPolicies = MeowHostPolicyTarget.from(if (startupGated) settings.copy(externalController = settings.externalController.copy(enabled = false)) else settings, "AEROVE-Lite")
            override val startupSettings get() = settings.takeIf { startupGate }
            override suspend fun restoreStartupSelection(endpoint: CoreEndpoint): Boolean {
                assertFalse(native.listeners(1).listening); assertFalse(native.status(1).tunReady)
                startupEvents += "restore"; restoreStartup(); startupEvents += "readback"
                return startupResume
            }
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

    private class StartupCacheResponses(private val epoch: () -> String) {
        private data class Exchange(val method: String?, val authorization: String?, val body: JSONObject?)
        private val exchanges = mutableListOf<Exchange>()
        private var phase = 0
        private var completed = 0
        var advanceIntentBeforePost = false
        var witnessReads = 0; private set

        private fun snapshot(intent: String) = JSONObject().put("graph-token", "19").put("selection-intent", intent)
            .put("requested", JSONObject()).put("groups", JSONObject()).put("startup-consumption", JSONObject())
            .put("route", JSONObject().put("core-epoch", epoch()).put("route-version", "1").put("picks", JSONObject())).toString()

        @Synchronized fun respond(request: RecordedRequest): MockResponse {
            val body = if (request.method == "POST") runCatching { JSONObject(request.body.readUtf8()) }.getOrNull() else null
            exchanges += Exchange(request.method, request.getHeader("Authorization"), body)
            if (request.getHeader("Authorization") != "Bearer 0123456789abcdef0123456789abcdef")
                return MockResponse().setResponseCode(401)
            if (request.method != listOf("GET", "POST", "GET")[phase])
                return MockResponse().setResponseCode(400).setBody("""{"error":"unexpectedSelectionCacheMethod"}""")
            if (phase == 1) {
                val keys = setOf("expectedCoreEpoch", "expectedRouteVersion", "expectedSelectionIntent", "expectedGraphToken", "requested")
                if (body == null || body.keys().asSequence().toSet() != keys || body.optJSONObject("requested")?.length() != 0)
                    return MockResponse().setResponseCode(400).setBody("""{"error":"invalidSelectionCacheMessage"}""")
                val liveIntent = if (advanceIntentBeforePost) "8" else "7"
                if (body.optString("expectedCoreEpoch") != epoch() || body.optString("expectedRouteVersion") != "1" ||
                    body.optString("expectedSelectionIntent") != liveIntent || body.optString("expectedGraphToken") != "19")
                    return MockResponse().setResponseCode(409).setBody("""{"error":"selectionIntentChanged"}""")
            }
            val response = snapshot(if (phase == 0) "7" else "8")
            phase = (phase + 1) % 3
            if (phase == 0) completed++
            return MockResponse().setBody(response)
        }

        @Synchronized fun witness(): MockResponse {
            check(completed > 0 && phase == 0); witnessReads++
            return MockResponse().setBody(JSONObject().put("schemaVersion", 1).put("witnessId", "23").put("complete", true)
                .put("coreEpoch", epoch()).put("routeVersion", "1").put("selectionIntent", "8")
                .put("acknowledgedSelectionIntent", "8").put("acknowledgedCoreEpoch", epoch()).put("initialStartupWitnessId", "23")
                .put("graphVersion", "19").put("rawRequested", JSONObject()).put("normalizedRawRequested", JSONObject())
                .put("groups", JSONObject()).put("ignored", JSONObject()).toString())
        }

        @Synchronized fun assertCompleteRestores(expected: Int) {
            assertEquals(expected, completed); assertEquals(0, phase); assertEquals(expected * 3, exchanges.size)
            exchanges.chunked(3).forEach { batch ->
                assertEquals(listOf("GET", "POST", "GET"), batch.map { it.method })
                val body = checkNotNull(batch[1].body)
                assertEquals("7", body.getString("expectedSelectionIntent"))
                assertEquals("1", body.getString("expectedRouteVersion"))
                assertEquals("19", body.getString("expectedGraphToken"))
                assertEquals(0, body.getJSONObject("requested").length())
            }
        }

        @Synchronized fun assertRejectedRestore() {
            assertEquals(listOf("GET", "POST"), exchanges.map { it.method })
            assertEquals("7", checkNotNull(exchanges[1].body).getString("expectedSelectionIntent"))
            assertEquals(0, completed); assertEquals(1, phase)
        }
    }

    private suspend fun parkedCandidate(store: RuntimeHandoffStore, controllerEnabled: Boolean = false, vpnEnabled: Boolean = false, sourceProof: JSONObject? = null): MeowRuntimeSnapshot {
        val yaml = "proxies: [{name: direct, type: direct}]\nrules: ['MATCH,direct']"
        val record = JSONObject().put("digest", "fixture").put("yaml-md5", "b".repeat(32)).put("groups", 0).put("proxies", 1).put("rules", 1)
        val setup = JSONObject().put("test-url", "https://example.test/probe").put("skip-cert-verify", false)
            .put("selected-map", JSONObject()).put("applied", record)
            .apply { sourceProof?.let { put("data-source-proof", it) } }
        val desired = LiteSettings(network = NetworkSettings(vpnEnabled = vpnEnabled), externalController =
            dev.aerove.lite.controllerprefs.ExternalControllerPreferences(controllerEnabled, if (controllerEnabled) "fixture-controller-secret" else ""))
        val settings = MeowRuntimeSnapshotSettings.encode(desired).put("flutterSetup", setup)
        val options = JSONObject().put("enable", vpnEnabled).put("ipv6", false).put("dnsHijacking", desired.network.dnsHijacking)
            .put("allowBypass", desired.network.allowBypass).put("systemProxy", desired.network.systemProxy)
            .put("port", 0).put("accessControlProps", JSONObject()).put("bypassDomain", org.json.JSONArray()).put("stack", "")
        val notification = JSONObject().put("stopTip", "stop").put("startTip", "start").put("localNetworkTip", "lan")
            .put("currentProfileName", "fixture").put("stopText", "stop")
            .put("showStopAction", desired.backgroundPolicy.showNotificationStopAction).put("onlyStatisticsProxy", desired.backgroundPolicy.onlyStatisticsProxy)
        val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "fixture", "fixture", "a".repeat(64), yaml, runtimeSha256(yaml.toByteArray()),
            record.toString(), settings.toString(), options.toString(), notification.toString())
        val before = store.readCurrent()
        return store.commit(handoff, store.currentEpoch(), before?.revision)
    }

    @Test fun parkedVpnIntentConvergesWithoutOpeningListenersOrChangingCoreConfiguration() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("vpn-intent-graph"), validateProjection = MeowRuntimeSnapshotSettings::validate)
        val candidate = parkedCandidate(store, vpnEnabled = true)
        val server = MockWebServer(); server.start()
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(10, TimeUnit.SECONDS)) }; f.native.onStop = { exit.countDown() }
        val cache = StartupCacheResponses { f.native.policy.epoch() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/route" -> MockResponse().setBody("""{"core-epoch":"${f.native.policy.epoch()}","route-version":"1","picks":{}}""")
                "/proxies" -> MockResponse().setBody("""{"proxies":{"direct":{"type":"Direct"}}}""")
                "/aerove/selection-cache" -> cache.respond(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        var token = 0L
        val owner = MeowParkedGraphOwner(store, { ++token }, { it == token }, { _, _, current ->
            f.admission = current; f.backend
        }, { _, _ -> })
        try {
            var identity = owner.applyStaged(candidate)
            fun expected() = MeowSetupRuntime(identity.revision, identity.intentEpoch, identity.compiledSha256,
                identity.proof.coreEpoch, identity.proof.serviceOwner, identity.proof.sessionGeneration, false)
            val initial = MeowRuntimeSnapshotSettings.decode(candidate.handoff.runtimeSettingsJson)
            // Match the native decoder's enable-dependent fields, including the filtered-app reset.
            for (enabled in listOf(false, true, false)) {
                val next = initial.copy(network = initial.network.copy(vpnEnabled = enabled, systemProxy = enabled,
                    allowBypass = enabled, dnsHijacking = enabled, bypassDomains = if (enabled) listOf("*.local") else emptyList()),
                    appFilterMode = if (enabled) dev.aerove.lite.AppFilterMode.Include else dev.aerove.lite.AppFilterMode.All,
                    packages = if (enabled) setOf("dev.aerove.fixture") else emptySet())
                val options = JSONObject(candidate.handoff.vpnOptionsJson).put("enable", enabled)
                    .put("systemProxy", enabled).put("allowBypass", enabled).put("dnsHijacking", enabled)
                val shared = JSONObject(candidate.handoff.notificationJson).put("vpnOptions", options)
                identity = owner.applyUpdate(expected(), MeowSetupProjection(next, shared.toString(), null, null))
                val applied = store.readLastApplied()!!.handoff
                assertEquals(next, MeowRuntimeSnapshotSettings.decode(applied.runtimeSettingsJson))
                assertEquals(enabled, JSONObject(applied.vpnOptionsJson).getBoolean("enable"))
                assertTrue(JSONObject(applied.runtimeSettingsJson).getJSONObject("flutterGraphPolicy").getBoolean("privateOnly"))
                assertEquals(candidate.handoff.compiledSha256, applied.compiledSha256)
                assertTrue(store.isGraphCurrent(identity)); assertFalse(store.readCurrent()!!.userRequested)
                assertFalse(owner.queryGraph()!!.listening); assertEquals(-1, f.native.descriptor); assertEquals(0, f.stages)
            }
            // Listener, routing and TUN-engine edits still require a new compiled setup.
            val before = store.readLastApplied()!!.handoff
            val current = MeowRuntimeSnapshotSettings.decode(before.runtimeSettingsJson)
            val unsupported = listOf(current.copy(network = current.network.copy(allowLan = true)),
                current.copy(network = current.network.copy(ports = current.network.ports.copy(mixed = 43210))),
                current.copy(network = current.network.copy(mtu = 1400)),
                current.copy(network = current.network.copy(routeAddress = listOf("10.0.0.0/8"))))
            for (next in unsupported) {
                val failure = runCatching { owner.applyUpdate(expected(), MeowSetupProjection(next,
                    JSONObject(before.notificationJson).put("vpnOptions", JSONObject(before.vpnOptionsJson)).toString(), null, null)) }.exceptionOrNull()
                assertTrue(failure is MeowProtocolFailure)
                assertEquals(before.runtimeSettingsJson, store.readLastApplied()!!.handoff.runtimeSettingsJson)
                assertTrue(owner.identityIsCurrent(identity)); assertEquals(0, f.stages)
            }
            cache.assertCompleteRestores(1)
        } finally { owner.retire(); server.shutdown() }
    }

    @Test fun parkedGraphIsolatesEnabledControllerAndRetainsDesiredPolicyForAuthorizedSuccessor() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("controller-graph"))
        val candidate = parkedCandidate(store, controllerEnabled = true, vpnEnabled = true)
        val server = MockWebServer(); server.start()
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(10, TimeUnit.SECONDS)) }; f.native.onStop = { exit.countDown() }
        val cache = StartupCacheResponses { f.native.policy.epoch() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/route" -> MockResponse().setBody("""{"core-epoch":"${f.native.policy.epoch()}","route-version":"1","picks":{}}""")
                "/proxies" -> MockResponse().setBody("""{"proxies":{"direct":{"type":"Direct"}}}""")
                "/aerove/selection-cache" -> cache.respond(request)
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
            cache.assertCompleteRestores(1)
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
            cache.assertCompleteRestores(2)
            assertFalse(f.native.policy.enabled); assertTrue(MeowRuntimeSnapshotSettings.decode(store.readLastApplied()!!.handoff.runtimeSettingsJson).externalController.enabled)
            owner.retire(); assertEquals(2, f.released)
        } finally { owner.retire(); server.shutdown() }
    }

    @Test fun graphOwnerLoadsPrivateProxyAndFailedJoinBlocksSuccessorWithoutReleasingLease() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("graph"))
        val sourceRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "packages/aerove_client/lib/core/method.dart").isFile }
        val metadataProof = JSONObject(File(sourceRoot,
            "apps/aerove-lite/app/src/test/resources/source-proof89/projection-fixture.json").readText()).getJSONObject("proof")
        val candidate = parkedCandidate(store, sourceProof = metadataProof)
        val server = MockWebServer(); server.start()
        var httpEpoch = "1"
        val cache = StartupCacheResponses { httpEpoch }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/route" -> MockResponse().setBody("""{"core-epoch":"$httpEpoch","route-version":"1","picks":{}}""")
                "/proxies" -> MockResponse().setBody("""{"proxies":{"direct":{"type":"Direct"}}}""")
                "/aerove/selection-cache" -> cache.respond(request)
                "/aerove/selection-witness" -> cache.witness()
                else -> MockResponse().setResponseCode(404)
            }
        }
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(10, TimeUnit.SECONDS)) }
        var ownerToken = 0L; var bindingGeneration = 0L; var metadataVerifications = 0; var renewals = 0
        val metadataEngineSha = sha256(f.lease.appliedConfiguration.read())
        f.native.onMetadataRenew = { request ->
            val envelope = JSONObject(request); val value = envelope.getJSONObject("value")
            assertEquals(f.native.policy.epoch(), envelope.getString("expectedCoreEpoch"))
            assertEquals(setOf("expected", "engineSha256", "selectionWitnessId", "rawSelectedMap", "newSourceGuard"), value.keys().asSequence().toSet())
            val expected = value.getJSONObject("expected")
            val binding = checkNotNull(f.config.geoHostBinding)
            assertEquals(binding, MeowGeoHostBinding.decode(expected.getJSONObject("hostBinding")))
            assertEquals(httpEpoch, expected.getString("coreEpoch")); assertEquals("1", expected.getString("routeVersion"))
            assertEquals("8", expected.getString("selectionIntent")); assertEquals("23", value.getString("selectionWitnessId"))
            assertEquals(metadataEngineSha, value.getString("engineSha256"))
            assertEquals(0, value.getJSONObject("rawSelectedMap").length())
            assertEquals(meowGeoSourceGuard(metadataProof), value.getString("newSourceGuard")); renewals++
            val renewed = binding.copy(sourceGuard = value.getString("newSourceGuard"))
            JSONObject().put("requestId", envelope.getString("requestId")).put("coreEpoch", httpEpoch).put("operation", "renewGeoBinding")
                .put("value", JSONObject().put("hostBinding", renewed.json()).put("current", MeowGeoStamp(httpEpoch, "1", "8").json())
                    .put("selectionWitnessId", "23").put("engineSha256", value.getString("engineSha256"))
                    .put("pendingSourceReadsJoined", true)).toString()
        }
        val owner = MeowParkedGraphOwner(store, { ++ownerToken }, { it == ownerToken }, { owner, _, current ->
            f.admission = current
            f.config = f.config.withGeoHostBinding(MeowGeoHostBinding(owner, ++bindingGeneration, candidate.intentEpoch,
                candidate.handoff.compiledSha256, meowGeoSourceGuard(metadataProof)), temp.root, emptyMap())
            f.backend
        }, { _, _ -> }, verifyMetadata = { previous, params ->
            assertEquals(nativeLiteJsonHash(metadataProof), nativeLiteJsonHash(JSONObject(previous.runtimeSettingsJson)
                .getJSONObject("flutterSetup").getJSONObject("data-source-proof")))
            assertEquals(nativeLiteJsonHash(metadataProof), nativeLiteJsonHash(params.getJSONObject("data-source-proof")))
            assertEquals(0, params.getJSONObject("selected-map").length()); metadataVerifications++
            emptyMap()
        })
        try {
            var identity = owner.applyStaged(candidate)
            cache.assertCompleteRestores(1)
            assertTrue(store.isGraphCurrent(identity)); assertTrue(owner.identityIsCurrent(identity))
            assertFalse(store.readCurrent()!!.userRequested); assertFalse(owner.queryGraph()!!.listening)
            assertEquals(-1, f.native.descriptor); assertEquals(0, f.stages)
            val first = identity
            val metadata = JSONObject(candidate.handoff.runtimeSettingsJson).getJSONObject("flutterSetup")
            metadata.getJSONObject("applied").put("digest", "metadata-readback")
            identity = owner.refreshSetup(MeowSetupRuntime(identity.revision, identity.intentEpoch, identity.compiledSha256,
                identity.proof.coreEpoch, identity.proof.serviceOwner, identity.proof.sessionGeneration, false), metadata.toString())
            assertFalse(owner.identityIsCurrent(first)); assertTrue(store.isGraphCurrent(identity))
            assertEquals(3, metadataVerifications); assertEquals(1, renewals); assertEquals(2, cache.witnessReads)
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
            catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("epochs differ"))
                assertTrue(meowFailureLocation("fixture", failure).contains("setupPhase=GRAPH_VERIFY"))
            }
            assertEquals(latest, store.readAppliedIdentity()); assertNull(owner.queryGraph())
            assertEquals(4, f.released)
            cache.assertCompleteRestores(4)
        } finally { f.native.onStop = { exit.countDown() }; owner.retire(); server.shutdown() }
    }

    @Test fun parkedGraphRejectsStaleStartupCasWithoutReadbackOrAppliedPublication() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(temp.newFolder("rejected-startup-cache"))
        val candidate = parkedCandidate(store)
        val server = MockWebServer(); server.start()
        val f = Fixture(true, profileId = candidate.profileId, revision = candidate.revision,
            generation = candidate.handoff.compiledSha256, port = server.port)
        val cache = StartupCacheResponses { f.native.policy.epoch() }.apply { advanceIntentBeforePost = true }
        val exited = CountDownLatch(1)
        f.native.onExit = { check(exited.await(10, TimeUnit.SECONDS)) }
        f.native.onStop = { exited.countDown() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                "/configs" -> MockResponse().setBody("""{"mode":"rule"}""")
                "/aerove/selection-cache" -> cache.respond(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        var token = 0L
        val owner = MeowParkedGraphOwner(store, { ++token }, { it == token }, { _, _, current ->
            f.admission = current; f.backend
        }, { _, _ -> })
        try {
            try { owner.applyStaged(candidate); fail("Stale startup selection CAS was published") }
            catch (failure: CoreApiException) {
                assertEquals(409, failure.statusCode)
                assertTrue(meowFailureLocation("fixture", failure).contains("setupPhase=GRAPH_SELECTION"))
            }
            cache.assertRejectedRestore()
            assertNull(owner.queryGraph()); assertNull(store.readAppliedIdentity())
            val unchanged = checkNotNull(store.readCurrent())
            assertEquals(candidate.revision, unchanged.revision); assertEquals(candidate.intentEpoch, unchanged.intentEpoch)
            assertEquals(candidate.handoff.compiledSha256, unchanged.handoff.compiledSha256)
            assertEquals(candidate.handoff.runtimeSettingsJson, unchanged.handoff.runtimeSettingsJson)
            assertFalse(unchanged.userRequested)
            assertEquals(1, f.native.starts); assertEquals(1, f.native.stops); assertEquals(1, f.released)
            assertEquals(0, f.stages); assertEquals(0, f.commits)
        } finally { owner.retire(); server.shutdown() }
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
        val failure = loading.await().exceptionOrNull()
        assertNotNull(failure)
        assertTrue(meowFailureLocation("fixture", checkNotNull(failure)).contains("setupPhase=GRAPH_READY"))
        assertNull(owner.queryGraph()); assertNull(store.readAppliedIdentity())
        assertEquals(1, f.released); assertEquals(1, f.native.starts)
    }
    @Test fun qaFinalPlanCaptureUsesTheSameObjectAsActualStartAndDoesNotChangeNormalOwnership() = runBlocking<Unit> {
        val f = Fixture(); var captured: String? = null
        MeowTunnelBackend.captureFinalPlansForOwnedQa(MeowFinalPlanQaSink { value, fd, lease ->
            assertEquals(-1, fd); assertSame(f.lease, lease); captured = value
        }).use {
            val session = f.backend.open(f.plan)
            assertSame(captured, f.native.payload)
            assertEquals(f.config.engineYaml, JSONObject(checkNotNull(captured)).getString("engineYaml"))
            assertEquals(1, f.native.starts); session.stop(); assertEquals(1, f.released)
        }
    }
    @Test fun qaCaptureFailureStillReleasesAcquiredLeaseWithoutStartingNative() = runBlocking<Unit> {
        val f = Fixture(); val original = java.io.IOException("controlled capture failure")
        MeowTunnelBackend.captureFinalPlansForOwnedQa(MeowFinalPlanQaSink { _, _, _ -> throw original }).use {
            try { f.backend.open(f.plan); fail("capture must fail explicitly") }
            catch (failure: java.io.IOException) { assertSame(original, generateSequence<Throwable>(failure) { it.cause }.filter { it === original }.single()) }
            assertEquals(0, f.native.starts); assertEquals(1, f.released); assertEquals(0, f.native.stops)
        }
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
            assertSame(original, generateSequence(actual.failure) { it.cause }.filter { it === original }.single())
            assertSame(cleanup, original.suppressed.single())
            assertEquals(MeowState.Running, actual.nativeBeforeStop.getOrThrow().state)
            assertEquals(MeowState.Running, actual.nativeAfterStop.getOrThrow().state)
            assertEquals(1, f.native.stops); assertEquals(0, f.released); assertEquals(2, f.native.statusReads)
        }
        f.native.onStop = {}; f.backend.stopAndJoinRetained()
    }
    @Test fun realReadinessFailureCarriesSafeNativeEvidenceAfterCleanup() = runBlocking<Unit> {
        val f = Fixture(); val original = IllegalStateException("password=private")
        f.native.onReady = { throw original }
        f.native.startupSnapshot = MeowStartupSnapshot("1", MeowStartupPhase.failed, false, false, false,
            MeowStartupStage.configLoad, false, MeowStartupPrimary(MeowStartupStage.configLoad,
                MeowStartupCode.configLoadFailed, MeowStartupIoKind.other, MeowStartupCauseType.dnsBootstrapResolve),
            MeowStartupCleanup.complete)
        val session = f.backend.open(f.plan)
        try { supervisorScope { async(Dispatchers.IO) { session.awaitReady() }.await() }; fail("must preserve failure") }
        catch (failure: IllegalStateException) {
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
            val evidence = generateSequence<Throwable>(failure) { it.cause }.flatMap { it.suppressed.asSequence() }
                .filterIsInstance<MeowStartupFailureEvidence>().first()
            assertSame(evidence, original.suppressed.filterIsInstance<MeowStartupFailureEvidence>().single())
            assertTrue(evidence.label().contains("dnsBootstrapResolve"))
            assertFalse(evidence.label().contains("password"))
        }
        assertEquals(1, f.native.stops); assertEquals(1, f.released)
    }
    @Test fun safeStartupReadbackFailuresDoNotReplaceOriginalReadinessOrCleanupFailures() = runBlocking<Unit> {
        val f = Fixture(); val original = java.io.IOException("password=private https://private.invalid")
        val cleanup = java.io.IOException("private cleanup path")
        f.native.onReady = { throw original }; f.native.onStop = { throw cleanup }
        f.native.startupReadFailure = IllegalStateException("secret diagnostic unavailable")
        val session = f.backend.open(f.plan)
        try { session.awaitReady(); fail("must preserve startup failure") }
        catch (failure: java.io.IOException) {
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === original })
            assertSame(original, generateSequence<Throwable>(failure) { it.cause }.filter { it === original }.single())
            assertSame(cleanup, original.suppressed.single())
        }
        assertEquals(2, f.native.startupReads); assertEquals(0, f.native.statusReads)
        f.native.onStop = {}; f.backend.stopAndJoinRetained()
    }
    @Test fun realAsyncRecoveredCopiesKeepCleanupOnTheUniqueOriginal() = runBlocking<Unit> {
        val original = java.io.IOException("readiness source")
        val cleanup = java.io.IOException("cleanup source")
        suspend fun recovered(source: java.io.IOException): Throwable = supervisorScope {
            val task = async(Dispatchers.IO) { throw source }
            try { task.await(); error("must fail") } catch (failure: java.io.IOException) { failure }
        }
        val observedCopy = recovered(original)
        val cleanupCopy = recovered(cleanup)
        assertNotSame(original, observedCopy)
        assertSame(original, observedCopy.cause)
        assertTrue(observedCopy.stackTrace.any { it.className.startsWith("_COROUTINE.") })
        assertNotSame(cleanup, cleanupCopy)
        suppressMeowStartupCleanup(observedCopy, cleanupCopy)
        suppressMeowStartupCleanup(observedCopy, cleanupCopy)
        assertSame(cleanup, original.suppressed.single())
        assertTrue(observedCopy.suppressed.isEmpty())
        assertSame(original, generateSequence(observedCopy) { it.cause }.filter { it === original }.single())
    }

    @Test fun aNormalSameClassCauseIsNotTreatedAsARecoveryCopy() {
        val cause = java.io.IOException("real nested cause")
        val original = java.io.IOException("real outer failure", cause)
        val cleanup = java.io.IOException("cleanup source")
        suppressMeowStartupCleanup(original, cleanup)
        suppressMeowStartupCleanup(original, cleanup)
        suppressMeowStartupCleanup(original, original)
        assertSame(cause, original.cause)
        assertTrue(cause.suppressed.isEmpty())
        assertSame(cleanup, original.suppressed.single())
    }

    @Test fun aWithContextStartupReadFailureAlsoRetainsItsExactCleanup() = runBlocking<Unit> {
        val f = Fixture()
        val original = java.io.IOException("status source")
        val cleanup = java.io.IOException("cleanup source")
        f.native.statusFailure = original; f.native.onStop = { throw cleanup }
        val session = f.backend.open(f.plan)
        try { session.awaitReady(); fail("must preserve status failure") }
        catch (failure: java.io.IOException) {
            assertSame(original, generateSequence<Throwable>(failure) { it.cause }.filter { it === original }.single())
            assertSame(cleanup, original.suppressed.single())
        }
        assertEquals(1, f.native.stops); assertEquals(0, f.released)
        f.native.onStop = {}; f.backend.stopAndJoinRetained()
    }

    @Test fun externalCancellationStillStopsAndJoinsTheBlockingReadinessOwner() = runBlocking<Unit> {
        val f = Fixture(); val entered = CountDownLatch(1); val exit = CountDownLatch(1)
        f.native.onReady = { entered.countDown(); check(exit.await(5, TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        val session = f.backend.open(f.plan)
        val observed = CompletableDeferred<Throwable>()
        val pending = launch {
            try { session.awaitReady(); fail("must remain cancelled") }
            catch (failure: CancellationException) { observed.complete(failure); throw failure }
        }
        entered.entered()
        pending.cancel(CancellationException("caller cancellation"))
        withTimeout(4_000) { pending.join() }
        assertTrue(observed.await() is CancellationException)
        assertTrue(pending.isCancelled)
        assertEquals(1, f.native.stops); assertEquals(1, f.released)
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
    @Test fun packageEventMutationAndPublicationUseExistingOwnerTransaction() = runBlocking<Unit> {
        val f = Fixture(tunMode = true)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(5, TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        val gate = kotlinx.coroutines.sync.Mutex(locked = true)
        val admitted = CompletableDeferred<Unit>()
        val published = CompletableDeferred<String>()
        var proofEpoch: String? = null
        val coordinator = VpnCoordinator(this, f.backend, onIdentityChanged = { endpoint, invalidate ->
            assertEquals(f.native.port, endpoint.port)
            admitted.complete(Unit)
            gate.lock()
            try {
                invalidate()
                proofEpoch = f.native.policy.epoch()
                published.complete(checkNotNull(proofEpoch))
            } finally { gate.unlock() }
        })
        try {
            coordinator.start(f.plan)
            withTimeout(3_000) { coordinator.state.first { it.phase == dev.aerove.lite.VpnPhase.Running } }
            val before = f.native.policy.epoch()
            proofEpoch = before
            checkNotNull(f.identityCallback).invoke()
            withTimeout(3_000) { admitted.await() }
            assertEquals("Native mutation escaped the publication owner's gate", before, f.native.policy.epoch())
            assertEquals(0, f.identityClears)
            gate.unlock()
            val after = withTimeout(3_000) { published.await() }
            assertTrue(after.toULong() > before.toULong())
            assertEquals(after, proofEpoch)
            assertEquals(dev.aerove.lite.VpnPhase.Running, coordinator.state.value.phase)
            assertNotNull(coordinator.state.value.endpoint)
            assertEquals(1, f.native.starts)
            assertEquals(0, f.native.stops)
        } finally {
            if (gate.isLocked) gate.unlock()
            coordinator.close()
        }
        assertTrue(f.observerClosed)
    }

    @Test fun stoppedPackageCallbackCannotCommitAfterOwnerGateRelease() = runBlocking<Unit> {
        val f = Fixture(tunMode = true)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(5, TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejected = CompletableDeferred<Throwable>()
        var published = false
        val coordinator = VpnCoordinator(this, f.backend, onIdentityChanged = { _, invalidate ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                try { invalidate(); published = true }
                catch (failure: Throwable) { rejected.complete(failure); throw failure }
            }
        })
        try {
            coordinator.start(f.plan)
            withTimeout(3_000) { coordinator.state.first { it.phase == dev.aerove.lite.VpnPhase.Running } }
            val before = f.native.policy.epoch()
            checkNotNull(f.identityCallback).invoke()
            withTimeout(3_000) { entered.await() }
            coordinator.stop()
            release.complete(Unit)
            val failure = withTimeout(3_000) { rejected.await() }
            assertTrue(failure.message.orEmpty().contains("superseded"))
            withTimeout(3_000) { coordinator.state.first { it.phase == dev.aerove.lite.VpnPhase.Stopped } }
            assertEquals(before, f.native.policy.epoch())
            assertEquals(0, f.identityClears)
            assertFalse(published)
            assertEquals(1, f.native.starts)
        } finally { release.complete(Unit); coordinator.close() }
        assertEquals(1, f.native.stops)
    }

    @Test fun packageEpochPublicationFailureRetainsExistingSessionCleanup() = runBlocking<Unit> {
        val f = Fixture(tunMode = true)
        val exit = CountDownLatch(1)
        f.native.onExit = { check(exit.await(5, TimeUnit.SECONDS)) }
        f.native.onStop = { exit.countDown() }
        val coordinator = VpnCoordinator(this, f.backend, onIdentityChanged = { _, invalidate ->
            invalidate()
            error("fixture publication failed")
        })
        try {
            coordinator.start(f.plan)
            withTimeout(3_000) { coordinator.state.first { it.phase == dev.aerove.lite.VpnPhase.Running } }
            checkNotNull(f.identityCallback).invoke()
            val failed = withTimeout(3_000) { coordinator.state.first { it.phase == dev.aerove.lite.VpnPhase.Failed } }
            assertEquals("fixture publication failed", failed.error)
            assertNull(failed.endpoint)
            assertEquals(1, f.native.starts)
        } finally { coordinator.close() }
        assertEquals(1, f.native.stops)
        assertTrue(f.observerClosed)
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

    @Test fun sharedStartupRestoresBeforeAttachAndPublishesTheActualInitialListener() = runBlocking {
        val f = Fixture(parked = true, tunMode = true)
        f.startupGate = true
        f.native.onListener = { action, fd ->
            assertEquals(listOf("restore", "readback"), f.startupEvents)
            assertEquals("attachTun", action); assertEquals(43, fd)
            f.startupEvents += "attach"
        }
        val session = f.backend.open(f.plan)
        session.awaitReady()
        assertEquals(listOf("restore", "readback", "attach"), f.startupEvents)
        assertTrue(session.initialListenerReadback!!.listening)
        assertTrue(session.initialListenerReadback!!.tunReady)
        assertEquals(1, f.stages); assertEquals(1, f.commits); assertEquals(1, f.usable)
        session.stop(); assertEquals(1, f.native.stops); assertEquals(1, f.released)
    }

    @Test fun startupFailureAndSupersededReadbackStopAndJoinBeforeLeaseReleaseWithoutAttach() = runBlocking {
        for (superseded in listOf(false, true)) {
            val f = Fixture(parked = true, tunMode = true); f.startupGate = true
            f.restoreStartup = { if (superseded) f.current = false else error("CAS readback failed") }
            f.native.onStop = { assertEquals(0, f.released); f.startupEvents += "native-joined" }
            val session = f.backend.open(f.plan)
            try { session.awaitReady(); fail("A failed startup was made usable") } catch (_: IllegalStateException) { }
            assertEquals(0, f.stages); assertEquals(0, f.commits); assertEquals(0, f.usable)
            assertEquals(1, f.native.stops); assertEquals(1, f.released)
            assertTrue(f.startupEvents.contains("native-joined"))
            session.stop(); assertEquals(1, f.native.stops)
        }
    }

    @Test fun startupCancellationJoinsBeforeReleaseAndNeverAttaches() = runBlocking {
        val f = Fixture(parked = true, tunMode = true); f.startupGate = true
        val entered = CompletableDeferred<Unit>(); val left = CompletableDeferred<Unit>()
        f.restoreStartup = { try { entered.complete(Unit); awaitCancellation() } finally { left.complete(Unit) } }
        f.native.onStop = { assertTrue(left.isCompleted); assertEquals(0, f.released) }
        val session = f.backend.open(f.plan)
        val ready = launch { session.awaitReady() }
        withTimeout(3000) { entered.await() }
        ready.cancelAndJoin()
        assertEquals(0, f.stages); assertEquals(0, f.usable)
        assertEquals(1, f.native.stops); assertEquals(1, f.released)
    }

    @Test fun sharedOnDemandParkedRestoresButDoesNotAttachAndLegacyDoesNotRunTheGate() = runBlocking {
        val f = Fixture(parked = true, tunMode = true); f.startupGate = true; f.startupResume = false
        val parked = f.backend.open(f.plan); parked.awaitReady()
        assertEquals(listOf("restore", "readback"), f.startupEvents)
        assertFalse(parked.initialListenerReadback!!.listening); assertEquals(0, f.stages); parked.stop()
        val legacy = Fixture(parked = true)
        val old = legacy.backend.open(legacy.plan); old.awaitReady()
        assertTrue(legacy.startupEvents.isEmpty()); assertFalse(old.initialListenerReadback!!.listening); old.stop()
    }

    @Test fun enabledUserControllerIsHeldUntilRestoreReadbackAndEnabledBeforeAttach() = runBlocking {
        val desired = LiteSettings(network = NetworkSettings(vpnEnabled = true), externalController =
            dev.aerove.lite.controllerprefs.ExternalControllerPreferences(true, "saved-secret"))
        val f = Fixture(parked = true, tunMode = true, settingsOverride = desired, startupGated = true)
        f.restoreStartup = { assertFalse("User controller exposed pre-restore", f.native.policy.enabled) }
        f.native.onListener = { _, _ ->
            assertEquals(listOf("restore", "readback"), f.startupEvents)
            assertTrue(f.native.policy.enabled)
        }
        val session = f.backend.open(f.plan); session.awaitReady()
        assertTrue(f.native.policy.enabled); assertTrue(session.initialListenerReadback!!.tunReady)
        assertTrue(f.settings.externalController.enabled); session.stop()
    }

}
