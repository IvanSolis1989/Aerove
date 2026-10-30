package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson
import dev.aerove.lite.controllerprefs.encodeExternalController
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.NativeJsonRulePayloadInspector
import dev.aerove.lite.data.NativeRulePayloadInspector
import dev.aerove.lite.core.ownedOfflineResourceRead
import dev.aerove.lite.dnssettings.DnsPreferencesJson
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.NetworkSettingsJson
import dev.aerove.lite.requests.RequestPreferencesJson
import dev.aerove.lite.resources.GeoResourcePreferencesJson
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowEngineAttachmentTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder().canonicalFile

    @Test fun parkedGraphQueriesUseActualEndpointWhileVpnRemainsStoppedAndRejectRetiredIdentity() = runBlocking<Unit> {
        val home = home(); val seeded = seed(home)
        seeded.store.invalidate()
        val candidate = seeded.store.readCurrent()!!
        seeded.store.commitGraphApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(9, 3, "5"), candidate.handoff)
        val identity = seeded.store.readAppliedIdentity()!!
        val server = MockWebServer(); server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/aerove/route" -> route()
                "/proxies" -> MockResponse().setBody("""{"proxies":{"local":{"name":"local","type":"Direct"}}}""")
                "/providers/proxies", "/providers/rules" -> MockResponse().setBody("""{"providers":{}}""")
                "/aerove/delay-history" -> MockResponse().setBody("""{"complete":true,"core-epoch":"5","route-version":"2","history-revision":"1","delays":[{"name":"local","url":"https://example.test/probe","value":23}]}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val vpn = MutableStateFlow(VpnSnapshot())
        var published: MeowQueryGraph? = MeowQueryGraph(identity, CoreEndpoint(server.port, "x".repeat(32)), false)
        val owner = object : MeowFlutterRuntime by UnavailableOwner {
            override fun queryGraph() = published
            override fun identityIsCurrent(identity: RuntimeAppliedIdentity) = published?.identity == identity &&
                seeded.store.isGraphCandidate(identity.revision, identity.intentEpoch)
        }
        val engine = attachment(home, seeded.store, vpn, this, owner)
        try {
            assertFalse(engine.readRuntime()!!.listening)
            assertTrue(rpc(engine, "getProxies").getJSONObject("result").getJSONObject("proxies").has("local"))
            assertFalse(rpc(engine, "getExternalProviders").has("error"))
            val delays = rpc(engine, "getLatestDelays")
            assertFalse(delays.toString(), delays.has("error"))
            assertTrue(delays.toString().contains("23"))
            assertEquals(VpnSnapshot(), vpn.value)
            assertFalse(seeded.store.readCurrent()!!.userRequested)
            assertFalse(engine.setup.getAppliedSetup()["running"] as Boolean)
            published = null
            assertTrue(rpc(engine, "getProxies").has("error"))
            assertNull(engine.readRuntime())
        } finally { assertTrue(engine.closeAndJoin()); server.shutdown() }
    }

    @Test fun ruleSetPreviewValidatesTheSharedPathWithoutRequiringARunningCore() = runBlocking<Unit> {
        val home = home()
        val store = RuntimeHandoffStore(home)
        val engine = attachment(home, store, MutableStateFlow(VpnSnapshot()), this)
        try {
            val failure = rpc(engine, "dumpRuleSet", "relative.mrs").getJSONObject("error")
            assertEquals("path_error", failure.getString("code"))
            assertFalse(store.hasAuthority)
        } finally { assertTrue(engine.closeAndJoin()) }
    }

    @Test fun sharedRuleSetPathReturnsTheActualReaderTextWithoutBorrowingTheVpnEpoch() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "provider.mrs").apply { writeText("opaque Native callback fixture") }
        val store = RuntimeHandoffStore(home)
        val vpn = MutableStateFlow(VpnSnapshot())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val inspector = NativeJsonRulePayloadInspector({ """{"format":"mrs","behavior":"domain","count":1}""" }, { _, behavior, format ->
            assertEquals("domain", behavior); assertEquals("mrs", format)
            entered.complete(Unit); release.await(); "+.example.test\n"
        })
        val engine = attachment(home, store, vpn, this, rules = inspector)
        try {
            val pending = async { rpc(engine, "dumpRuleSet", source.path) }
            withTimeout(4000) { entered.await() }
            val nextEpoch = store.invalidate()
            vpn.value = VpnSnapshot(phase = VpnPhase.Failed, sessionGeneration = 12, serviceOwner = 9)
            release.complete(Unit)
            val response = withTimeout(4000) { pending.await() }
            assertEquals("test-dumpRuleSet", response.getString("id"))
            assertEquals("+.example.test\n", response.getString("result"))
            assertEquals(nextEpoch, store.currentEpoch())
            assertNull(store.readAppliedIdentity())
            assertEquals(VpnPhase.Failed, vpn.value.phase)
        } finally { release.complete(Unit); assertTrue(engine.closeAndJoin()) }
    }

    @Test fun attachmentCloseWaitsForTheOwnedRuleReaderBeforeRemovingItsSnapshot() = runBlocking<Unit> {
        val home = home()
        val source = File(home, "provider.mrs").apply { writeText("opaque Native callback fixture") }
        val store = RuntimeHandoffStore(home)
        val vpn = MutableStateFlow(VpnSnapshot())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val snapshot = AtomicReference<File>()
        var joined = false
        val inspector = NativeJsonRulePayloadInspector({ """{"format":"mrs","behavior":"domain","count":1}""" }, { path, _, _ ->
            ownedOfflineResourceRead(create = { 1L }, cancel = { cancelled.complete(Unit) }, closeJoin = {
                assertEquals(0L, release.count); assertTrue(snapshot.get().exists()); joined = true
            }, work = {
                snapshot.set(File(path)); entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS)); "+.example.test\n"
            })
        })
        val engine = attachment(home, store, vpn, this, rules = inspector)
        val pending = async { rpc(engine, "dumpRuleSet", source.path) }
        try {
            withTimeout(4000) { entered.await() }
            val closing = async { engine.closeAndJoin() }
            withTimeout(4000) { cancelled.await() }
            assertFalse(closing.isCompleted); assertFalse(joined)
            assertTrue(snapshot.get().exists())
            release.countDown()
            assertTrue(withTimeout(4000) { closing.await() }); assertTrue(joined)
            assertEquals("cancelled", withTimeout(4000) { pending.await() }.getJSONObject("error").getString("code"))
            assertFalse(snapshot.get().exists())
            assertEquals(VpnSnapshot(), vpn.value)
        } finally { release.countDown(); assertTrue(engine.closeAndJoin()); pending.join() }
    }

    private fun attachment(home: File, store: RuntimeHandoffStore, vpn: MutableStateFlow<VpnSnapshot>, scope: CoroutineScope,
        owner: MeowFlutterRuntime = UnavailableOwner,
        rules: NativeRulePayloadInspector = nativeRulePayloadInspector(), verify: () -> Unit = {}) = MeowEngineAttachment(home, store, vpn, scope,
        NativePreparedConfigValidator { _, _ -> error("Native prepared validation is outside this attachment test") }, verify,
        { _, _ -> error("No telemetry producer is requested in this test") }, { throw AssertionError("Unexpected asynchronous warning", it) }, owner,
        rulePayloadInspector = rules)

    private object UnavailableOwner : MeowFlutterRuntime {
        override fun identityIsCurrent(identity: RuntimeAppliedIdentity) = false
        override suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity = error("No synthetic service owner was installed")
        override suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity = error("No synthetic service owner was installed")
        override suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String): RuntimeAppliedIdentity = error("No synthetic service owner was installed")
        override suspend fun defaultTestUrl(expected: MeowSetupRuntime): String = error("No synthetic service owner was installed")
    }

    /** Synthetic native acknowledgement only; persistence still uses the production Store CAS. */
    private class Owner(val seed: Seed, val vpn: MutableStateFlow<VpnSnapshot>, var identity: RuntimeAppliedIdentity,
        val epoch: AtomicReference<String> = AtomicReference(identity.proof.coreEpoch)) : MeowFlutterRuntime {
        var admitted = true
        var rejectEffects = false
        var acknowledgeWithoutStore = false
        var allowModeChange = false
        val defaultUrl = "https://synthetic-owner.example/health"
        override fun identityIsCurrent(identity: RuntimeAppliedIdentity): Boolean = admitted && this.identity == identity &&
            seed.store.isCurrent(identity.revision, identity.intentEpoch) && vpn.value.serviceOwner == identity.proof.serviceOwner &&
            vpn.value.sessionGeneration == identity.proof.sessionGeneration && epoch.get() == identity.proof.coreEpoch
        private suspend fun checkExpected(expected: MeowSetupRuntime) {
            if (rejectEffects) throw MeowProtocolFailure("owner_rejected", "Synthetic owner refused the effect")
            check(identityIsCurrent(identity) && seed.store.readAppliedIdentity() == identity && expected == identity.runtime()) { "Synthetic owner is stale" }
        }
        override suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity {
            checkExpected(expected)
            val handoff = seed.store.readLastApplied()!!.handoff
            identity = seed.store.commitRuntimeProjection(identity, handoff, identity.proof)
            vpn.value = vpn.value.copy(listening = enabled)
            return identity
        }
        override suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity {
            checkExpected(expected)
            val previous = seed.store.readLastApplied()!!.handoff
            val currentSettings = MeowRuntimeSnapshotSettings.decode(previous.runtimeSettingsJson)
            if (projection.settings != currentSettings) {
                if (!allowModeChange || projection.settings != currentSettings.copy(mode = projection.settings.mode))
                    throw MeowProtocolFailure("implementation_blocked", "Synthetic owner does not acknowledge this native policy")
                val settings = JSONObject(previous.runtimeSettingsJson).put("mode", projection.settings.mode.name)
                val replacement = replace(previous, runtime = settings.toString())
                val proof = identity.proof.copy(coreEpoch = (identity.proof.coreEpoch.toLong() + 1).toString())
                identity = seed.store.commitRuntimeProjection(identity, replacement, proof)
                epoch.set(proof.coreEpoch)
            }
            return identity
        }
        override suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String): RuntimeAppliedIdentity {
            checkExpected(expected)
            val next = JSONObject(setupParamsJson)
            val previous = JSONObject(seed.store.readLastApplied()!!.handoff.runtimeSettingsJson).getJSONObject("flutterSetup")
            for (field in listOf("test-url", "skip-cert-verify", "selected-map")) {
                if (!meowJsonEqual(previous.get(field), next.get(field)))
                    throw MeowProtocolFailure("implementation_blocked", "Synthetic native owner did not acknowledge changed setup policy", mapOf("field" to field))
            }
            val record = next.getJSONObject("applied").toString()
            if (acknowledgeWithoutStore) return identity.copy(expectedSetupRecordJson = record)
            seed.store.refreshAppliedMetadata(identity, record)
            identity = seed.store.readAppliedIdentity()!!
            return identity
        }
        override suspend fun defaultTestUrl(expected: MeowSetupRuntime): String { checkExpected(expected); return defaultUrl }
        private fun RuntimeAppliedIdentity.runtime() = MeowSetupRuntime(revision, intentEpoch, compiledSha256, proof.coreEpoch, proof.serviceOwner, proof.sessionGeneration, vpn.value.listening)
        private fun replace(previous: RuntimeHandoff, runtime: String) = RuntimeHandoff(previous.coreSha, previous.sourceId,
            previous.sourceRevision, previous.sourceSha256, previous.compiledYaml, previous.compiledSha256, previous.setupRecordJson,
            runtime, previous.vpnOptionsJson, previous.notificationJson, previous.resources)
    }

    @Test fun valuesRejectMalformedObjectsAndPreserveNestedDtoTypes() {
        for (input in listOf<Any?>("{broken", "[]", null, mapOf(1 to "wrong"))) {
            try { meowObject(input); fail("Accepted $input") }
            catch (failure: MeowProtocolFailure) { assertEquals("bad_argument", failure.code) }
        }
        assertEquals(mapOf("nested" to mapOf("flags" to listOf(true, null, mapOf("number" to 3)))),
            meowObject("""{"nested":{"flags":[true,null,{"number":3}]}}"""))
    }

    @Test fun initRequiresExactPrivateHomeAndSuccessfulCapabilityVerification() = runBlocking {
        val home = home(); val store = RuntimeHandoffStore(home); val vpn = MutableStateFlow(VpnSnapshot())
        var calls = 0; var capabilitiesAvailable = false
        val engine = attachment(home, store, vpn, this) { calls++; check(capabilitiesAvailable) { "Synthetic unavailable capability boundary" } }
        try {
            assertEquals(false, rpc(engine, "getIsInit").get("result"))
            val child = File(home, "child").apply { mkdir() }
            val alias = File(child, "..").path
            for (args in listOf(mapOf("home-dir" to home().path, "version" to 1), mapOf("home-dir" to alias, "version" to 1),
                mapOf("home-dir" to home.path, "version" to "1"), mapOf("home-dir" to home.path, "version" to 2147483648L))) {
                assertEquals("bad_argument", rpc(engine, "initClash", args).getJSONObject("error").getString("code"))
            }
            assertEquals(0, calls)
            assertTrue(rpc(engine, "initClash", mapOf("home-dir" to home.path, "version" to 1)).has("error"))
            assertFalse(engine.isInitialized())
            capabilitiesAvailable = true
            assertEquals(true, rpc(engine, "initClash", mapOf("home-dir" to home.path, "version" to 1)).get("result"))
            assertEquals(true, rpc(engine, "getIsInit").get("result"))
            assertEquals(2, calls)
            assertEquals("bad_argument", rpc(engine, "getIsInit", emptyMap<String, Any>()).getJSONObject("error").getString("code"))
        } finally { assertTrue(engine.closeAndJoin()) }
        assertFalse(engine.isInitialized())
        assertEquals("closed", rpc(engine, "getIsInit").getJSONObject("error").getString("code"))
        assertEquals(0L, store.currentEpoch())
    }

    @Test fun redirectedConstructorHomeIsRejectedBeforeAnyCapabilityWork() = runBlocking {
        val home = home(); val child = File(home, "child").apply { mkdir() }
        try { attachment(File(child, ".."), RuntimeHandoffStore(home), MutableStateFlow(VpnSnapshot()), this); fail() }
        catch (failure: MeowConfigProtocolException) { assertEquals("path_error", failure.code) }
    }

    @Test fun closeJoinsInflightInitWithoutChangingTheRunningIntentOrParentScope() = runBlocking {
        val home = home(); val seed = seed(home)
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(7899, "x".repeat(32))))
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        val engine = attachment(home, seed.store, vpn, this) {
            entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "Test release was not delivered" }
        }
        val stateFile = File(home, "runtime-handoffs/current.json")
        val originalBytes = stateFile.readBytes()
        val originalState = vpn.value
        val request = async(Dispatchers.Default) { rpc(engine, "initClash", mapOf("home-dir" to home.path, "version" to 1)) }
        try {
            entered.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { engine.closeAndJoin() }
            assertFalse("close must join the actual request", closing.isCompleted)
            release.countDown()
            assertTrue(closing.await())
            assertTrue(request.await().getJSONObject("error").getString("code") in setOf("cancelled", "closed"))
            assertArrayEquals(originalBytes, stateFile.readBytes())
            assertEquals(seed.snapshot.intentEpoch, seed.store.currentEpoch())
            assertTrue(seed.store.readCurrent()!!.userRequested)
            assertEquals(seed.snapshot.revision, seed.store.readLastApplied()!!.revision)
            assertSame(originalState, vpn.value)
            assertTrue(coroutineContext[Job]!!.isActive)
        } finally { release.countDown(); engine.closeAndJoin() }
    }

    @Test fun sameYamlMetadataUsesStoreCasAndPreservesOwnerPolicyRefusal() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val endpoint = CoreEndpoint(server.port, "x".repeat(32))
        val vpn = MutableStateFlow(running(seed, endpoint))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!)
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            repeat(4) { server.enqueue(route()) }
            val next = JSONObject(seed.setup.toString()).put("applied", record("refreshed-inputs", seed.md5))
            assertTrue(engine.refreshApplied(runtime(seed), next.toString()))
            assertEquals("refreshed-inputs", JSONObject(RuntimeHandoffStore(home).readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            assertEquals(seed.snapshot.handoff.compiledSha256, seed.store.readLastApplied()!!.handoff.compiledSha256)
            server.enqueue(route())
            val changedPolicy = JSONObject(next.toString()).put("test-url", "https://different.example/probe")
            try { engine.refreshApplied(runtime(seed), changedPolicy.toString()); fail() }
            catch (failure: MeowProtocolFailure) { assertEquals("implementation_blocked", failure.code); assertEquals("test-url", failure.details["field"]) }
            assertEquals("refreshed-inputs", JSONObject(seed.store.readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            assertEquals(seed.snapshot.intentEpoch, seed.store.currentEpoch())
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun changedYamlAndOldRuntimeOwnersCannotRefreshAppliedReceipt() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val endpoint = CoreEndpoint(server.port, "x".repeat(32))
        val vpn = MutableStateFlow(running(seed, endpoint)); val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!)
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            server.enqueue(route())
            val next = JSONObject(seed.setup.toString()).put("applied", record("other-yaml", md5("mode: direct")))
            try { engine.refreshApplied(runtime(seed), next.toString()); fail() }
            catch (failure: IllegalArgumentException) { assertEquals("runtime_refresh_yaml_changed", failure.message) }
            vpn.value = vpn.value.copy(sessionGeneration = 12)
            assertFalse(engine.refreshApplied(runtime(seed), seed.setup.toString()))
            assertEquals("original-inputs", JSONObject(seed.store.readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            assertEquals(1, server.requestCount)
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun unchangedLiveProjectionHonorsDefaultStopActionAndPreservesOwnerPolicyRefusal() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val endpoint = CoreEndpoint(server.port, "x".repeat(32)); val vpn = MutableStateFlow(running(seed, endpoint))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!)
        val engine = attachment(home, seed.store, vpn, this, owner)
        val stateFile = File(home, "runtime-handoffs/current.json"); val original = stateFile.readBytes()
        try {
            repeat(3) { server.enqueue(route()) }
            val shared = JSONObject(seed.notification.toString()).apply { remove("showStopAction") }
            val settings = MeowRuntimeSnapshotSettings.decode(seed.snapshot.handoff.runtimeSettingsJson)
            engine.applyUpdate(runtime(seed), MeowSetupProjection(settings, shared.toString(), null, null))
            try { engine.applyUpdate(runtime(seed), MeowSetupProjection(settings.copy(mode = ProxyMode.Direct), shared.toString(), null, null)); fail() }
            catch (failure: MeowProtocolFailure) { assertEquals("implementation_blocked", failure.code) }
            assertArrayEquals(original, stateFile.readBytes())
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun explicitOwnerRefusalAndRevocationCannotPublishOrServeAnAppliedRuntime() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(server.port, "x".repeat(32))))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!).apply { rejectEffects = true }
        server.dispatcher = routeDispatcher(owner)
        val engine = attachment(home, seed.store, vpn, this, owner)
        val file = File(home, "runtime-handoffs/current.json"); val original = file.readBytes()
        try {
            for (work in listOf<suspend () -> Any?>(
                { engine.setListening(false) },
                { engine.applyUpdate(runtime(seed), MeowSetupProjection(seed.settings, shared(seed).toString(), null, null)) },
                { engine.refreshApplied(runtime(seed), seed.setup.toString()) })) {
                try { work(); fail("An owner refusal was ignored") }
                catch (failure: MeowProtocolFailure) { assertEquals("owner_rejected", failure.code) }
            }
            assertArrayEquals(original, file.readBytes()); assertTrue(vpn.value.listening)
            owner.admitted = false
            assertNull(engine.readRuntime())
            assertEquals("superseded", rpc(engine, "getConnectionCount").getJSONObject("error").getString("code"))
            assertEquals(seed.snapshot.revision, seed.store.readLastApplied()!!.revision)
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun metadataAcknowledgementWithoutDurableStoreReadbackIsRejected() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(server.port, "x".repeat(32))))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!).apply { acknowledgeWithoutStore = true }
        server.dispatcher = routeDispatcher(owner)
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            val next = JSONObject(seed.setup.toString()).put("applied", record("only-synthetic-native-ack", seed.md5))
            try { engine.refreshApplied(runtime(seed), next.toString()); fail("A native ack is not Store persistence") }
            catch (failure: MeowProtocolFailure) { assertEquals("superseded", failure.code) }
            assertEquals("original-inputs", JSONObject(RuntimeHandoffStore(home).readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            assertEquals(owner.identity, seed.store.readAppliedIdentity())
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun advancedOwnerEpochRetiresOldQuerySessionAndUsesItsActualDefaultUrl() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(server.port, "x".repeat(32))))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!).apply { allowModeChange = true }
        val probes = java.util.Collections.synchronizedList(mutableListOf<Pair<String?, String?>>())
        server.dispatcher = routeDispatcher(owner, probes)
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            val args = mapOf("proxy-name" to "local", "test-url" to "", "timeout" to 2000)
            val first = rpc(engine, "asyncTestDelay", args).getJSONObject("result")
            assertEquals(25, first.getInt("value")); assertEquals(owner.defaultUrl, first.getString("url"))
            engine.applyUpdate(runtime(seed), MeowSetupProjection(seed.settings.copy(mode = ProxyMode.Direct), shared(seed).toString(), update().toString(), null))
            val second = rpc(engine, "asyncTestDelay", args).getJSONObject("result")
            assertEquals(25, second.getInt("value")); assertEquals(owner.defaultUrl, second.getString("url"))
            assertEquals(listOf("5" to owner.defaultUrl, "6" to owner.defaultUrl), probes.toList())
            assertEquals("6", seed.store.readAppliedIdentity()!!.proof.coreEpoch)
            assertEquals(ProxyMode.Direct, MeowRuntimeSnapshotSettings.decode(seed.store.readLastApplied()!!.handoff.runtimeSettingsJson).mode)
            assertFalse(engine.refreshApplied(runtime(seed), seed.setup.toString()))
            assertTrue(engine.setListening(false)); assertFalse(vpn.value.listening)
            assertTrue(seed.store.readCurrent()!!.userRequested)
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun lawfulLwipDtoDoesNotAdmitLegacyStacksAlgorithmsOrDesktopTunFields() = runBlocking {
        val home = home(); val seed = seed(home)
        val engine = attachment(home, seed.store, MutableStateFlow(VpnSnapshot()), this)
        try {
            val state = shared(seed)
            engine.setup.syncState(meowObject(state.toString()))
            assertEquals("", engine.setup.updateConfig(meowObject(update().toString())))
            val legacy = JSONObject(state.toString()).apply { getJSONObject("vpnOptions").put("stack", "mips") }
            expectConfig("implementation_blocked", "$.vpnOptions.stack") { engine.setup.syncState(meowObject(legacy.toString())) }
            val cubic = JSONObject(state.toString()).apply { getJSONObject("vpnOptions").put("congestionController", "cubic") }
            expectConfig("implementation_blocked", "$.vpnOptions.congestionController") { engine.setup.syncState(meowObject(cubic.toString())) }
            val desktop = update().apply { getJSONObject("tun").put("device", "") }
            expectConfig("bad_argument", "$.tun.device") { engine.setup.updateConfig(meowObject(desktop.toString())) }
            assertEquals("original-inputs", JSONObject(seed.store.readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
        } finally { engine.closeAndJoin() }
    }

    @Test fun parkedOwnerKeepsInitAndAppliedMetadataAndCanRefreshUpdateAndResume() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(server.port, "x".repeat(32))))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!)
        server.dispatcher = routeDispatcher(owner)
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            assertEquals(true, rpc(engine, "initClash", mapOf("home-dir" to home.path, "version" to 1)).get("result"))
            assertTrue(engine.setup.getAppliedSetup()["running"] as Boolean)
            assertTrue(engine.setListening(false))
            val parked = engine.readRuntime()!!
            assertFalse(parked.listening)
            val applied = engine.setup.getAppliedSetup()
            assertEquals(true, applied["init"]); assertEquals(false, applied["running"])
            assertNotNull(applied["applied"])
            val next = JSONObject(seed.setup.toString()).put("applied", record("refreshed-while-parked", seed.md5))
            assertTrue(engine.refreshApplied(parked, next.toString()))
            engine.applyUpdate(engine.readRuntime(), MeowSetupProjection(seed.settings, shared(seed).toString(), update().toString(), null))
            assertFalse(engine.readRuntime()!!.listening)
            assertEquals("refreshed-while-parked", JSONObject(seed.store.readLastApplied()!!.handoff.setupRecordJson).getString("digest"))
            assertTrue(engine.setListening(true))
            assertTrue(engine.readRuntime()!!.listening)
            assertEquals(true, engine.setup.getAppliedSetup()["running"])
            assertEquals(seed.snapshot.revision, seed.store.readLastApplied()!!.revision)
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    @Test fun sharedServiceCheckEnvelopeReachesTheOwnedHttpSessionAndReturnsItsDto() = runBlocking {
        val home = home(); val seed = seed(home); val server = MockWebServer(); server.start()
        val vpn = MutableStateFlow(running(seed, CoreEndpoint(server.port, "x".repeat(32))))
        val owner = Owner(seed, vpn, seed.store.readAppliedIdentity()!!)
        val serviceBodies = mutableListOf<JSONObject>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                "/aerove/route" -> route()
                "/aerove/service-check" -> {
                    serviceBodies += JSONObject(request.body.readUtf8())
                    MockResponse().setBody("""[{"name":"google","status":"available","region":"HK","delay":23,"chains":["local"],"checked-at":1790000000000,"core-epoch":5,"route-version":2,"hits":[{"host":"google.example","exit":"local"}]}]""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        val engine = attachment(home, seed.store, vpn, this, owner)
        try {
            val response = rpc(engine, "serviceCheck", mapOf("names" to listOf("google"), "proxy-name" to "DIRECT", "timeout" to 1000))
            assertEquals("test-serviceCheck", response.getString("id"))
            assertFalse(response.has("error"))
            val result = response.getJSONArray("result").getJSONObject(0)
            assertEquals("google", result.getString("name")); assertEquals("available", result.getString("status"))
            assertEquals(23, result.getInt("delay")); assertEquals(5, result.getInt("core-epoch"))
            assertEquals("DIRECT", serviceBodies.single().getString("proxy-name"))
            assertEquals(seed.snapshot.revision, seed.store.readAppliedIdentity()!!.revision)
        } finally { engine.closeAndJoin(); server.shutdown() }
    }

    private fun routeDispatcher(owner: Owner, probes: MutableList<Pair<String?, String?>>? = null) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/aerove/route" -> route(owner.epoch.get())
            "/proxies/local/delay" -> {
                probes?.add(request.requestUrl?.queryParameter("coreEpoch") to request.requestUrl?.queryParameter("url"))
                MockResponse().setHeader("x-aerove-core-epoch", owner.epoch.get()).setHeader("x-aerove-route-version", "2").setBody("{\"delay\":25}")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private suspend fun expectConfig(code: String, field: String, work: suspend () -> Any?) {
        try { work(); fail("Accepted incompatible fixture") }
        catch (failure: MeowConfigProtocolException) { assertEquals(code, failure.code); assertEquals(field, failure.details["field"]) }
    }

    private fun shared(seed: Seed) = JSONObject(seed.notification.toString()).put("setupParams", JSONObject.NULL)
        .put("vpnOptions", JSONObject(seed.snapshot.handoff.vpnOptionsJson))

    private fun update() = JSONObject().put("tun", JSONObject().put("enable", true).put("stack", "lwip"))
        .put("mixed-port", 7890).put("allow-lan", false).put("find-process-mode", "strict").put("mode", "rule")
        .put("log-level", "info").put("ipv6", false).put("tcp-concurrent", false).put("external-controller", "")
        .put("secret", "").put("unified-delay", false).put("authentication", JSONArray()).put("geo-auto-update", false)
        .put("geo-update-interval", 24).put("geox-url", JSONObject()).put("skip-cert-verify", false)

    private data class Seed(val store: RuntimeHandoffStore, val snapshot: MeowRuntimeSnapshot,
        val settings: LiteSettings, val setup: JSONObject, val notification: JSONObject, val md5: String)

    private suspend fun seed(home: File): Seed {
        val source = File(home, "input.yaml").apply { writeText("{}") }
        val hash = runtimeSha256(source.readBytes()); val md5 = md5(source.readText())
        val record = record("original-inputs", md5)
        val setup = JSONObject().put("selected-map", JSONObject()).put("test-url", "https://example.com/probe")
            .put("skip-cert-verify", false).put("applied", record)
        val settings = LiteSettings(network = NetworkSettings(vpnEnabled = true))
        val projection = JSONObject().put("network", NetworkSettingsJson.encode(settings.network)).put("mode", settings.mode.name)
            .put("ipv6", settings.ipv6).put("appFilterMode", settings.appFilterMode.name).put("packages", JSONArray())
            .put("requests", RequestPreferencesJson.encode(settings.requests)).put("backgroundPolicy", BackgroundPolicyJson.encode(settings.backgroundPolicy))
            .put("unifiedDelay", settings.unifiedDelay).put("tcpConcurrent", settings.tcpConcurrent).put("findProcessMode", settings.findProcessMode.wireName)
            .put("externalController", encodeExternalController(settings.externalController)).put("dnsPreferences", DnsPreferencesJson.encode(settings.dnsPreferences))
            .put("geoResources", GeoResourcePreferencesJson.encode(settings.geoResources)).put("autoStart", settings.autoStart).put("flutterSetup", setup)
        val vpnOptions = JSONObject().put("enable", true).put("port", settings.network.ports.mixed).put("ipv6", false).put("dnsHijacking", false)
            .put("accessControlProps", JSONObject().put("enable", false).put("mode", "rejectSelected").put("acceptList", JSONArray()).put("rejectList", JSONArray()))
            .put("allowBypass", settings.network.allowBypass).put("systemProxy", settings.network.systemProxy)
            .put("bypassDomain", JSONArray(settings.network.bypassDomains)).put("stack", "lwip").put("congestionController", "builtin")
            .put("mtu", settings.network.mtu).put("routeAddress", JSONArray())
        val notification = JSONObject().put("stopTip", "stop tip").put("startTip", "start tip").put("localNetworkTip", "local tip")
            .put("currentProfileName", "test profile").put("stopText", "stop").put("onlyStatisticsProxy", true).put("showStopAction", true)
        val handoff = RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, source.path, hash, hash, source.readText(), hash,
            record.toString(), projection.toString(), vpnOptions.toString(), notification.toString())
        val store = RuntimeHandoffStore(home, validateProjection = MeowRuntimeSnapshotSettings::validate)
        val staged = store.commit(handoff, 0, null)
        store.requestStart(staged.revision, staged.intentEpoch)
        val applied = store.commitApplied(staged.revision, staged.intentEpoch, RuntimeAppliedProof(7, 11, "5"))
        return Seed(store, applied, settings, setup, notification, md5)
    }
    private fun running(seed: Seed, endpoint: CoreEndpoint) = VpnSnapshot(phase = VpnPhase.Running, endpoint = endpoint,
        initialSettings = seed.settings, listening = true, sessionGeneration = 11, serviceOwner = 7)
    private fun runtime(seed: Seed) = MeowSetupRuntime(seed.snapshot.revision, seed.snapshot.intentEpoch, seed.snapshot.handoff.compiledSha256, "5", 7, 11, listening = true)
    private fun route(epoch: String = "5") = MockResponse().setBody("""{"core-epoch":"$epoch","route-version":"2","picks":{}}""")
    private fun record(digest: String, md5: String) = JSONObject().put("digest", digest).put("yaml-md5", md5).put("groups", 0).put("proxies", 0).put("rules", 0)
    private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private suspend fun rpc(engine: MeowEngineAttachment, method: String, args: Any? = null): JSONObject =
        JSONObject(engine.dispatcher.invoke(JSONObject().put("id", "test-$method").put("method", method).put("arguments", args ?: JSONObject.NULL).toString()).toString(Charsets.UTF_8))
}
