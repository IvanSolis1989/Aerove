package dev.aerove.lite.flutter

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.RouteSnapshot
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.ControlEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class MeowSetupProtocolTest {
    @Test fun coldSetupDoesNotTreatHistoricalRequestedFlagAsLiveStartAuthority() = runBlocking<Unit> {
        val home = home(); val input = File(home, "config.yaml").apply { writeText("rules: ['MATCH,DIRECT']\n") }
        val store = RuntimeHandoffStore(home)
        val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.setupConfig(setup())
            val original = store.readCurrent()!!
            store.requestStart(original.revision, original.intentEpoch)
            store.commitApplied(original.revision, original.intentEpoch, RuntimeAppliedProof(7, 1, "1"))
            assertFalse(effects.hasRequestedIntent())
            effects.onStaged = { candidate -> assertFalse(candidate.userRequested) }
            input.writeText("rules: ['MATCH,REJECT']\n")
            api.setupConfig(setup())
            assertFalse(store.readCurrent()!!.userRequested)
            assertEquals(original.revision, store.readLastApplied()!!.revision)
        }
    }

    @Test fun ordinarySetupReplacesRequestedGraphWithoutStopAndLaterStopRejectsQueuedReplacement() = runBlocking<Unit> {
        val home = home(); val input = File(home, "config.yaml").apply { writeText("rules: ['MATCH,DIRECT']\n") }
        val store = RuntimeHandoffStore(home)
        val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.setupConfig(setup())
            val original = store.readCurrent()!!
            store.requestStart(original.revision, original.intentEpoch)
            store.commitApplied(original.revision, original.intentEpoch, RuntimeAppliedProof(7, 1, "1"))
            effects.requestedIntent = true
            var requested = true; var generation = 4L; var replacements = 0; var parkedLoads = 0
            effects.onStaged = { staged ->
                meowApplyStagedGraph(staged, store, replaceRequested = { next ->
                    assertTrue(requested); assertTrue(next.userRequested)
                    assertTrue(meowAdmitGraphReplacement(7, generation, 7, generation, requested,
                        store.isCurrent(next.revision, next.intentEpoch)))
                    replacements++
                    store.commitApplied(next.revision, next.intentEpoch, RuntimeAppliedProof(7, 2, "2"))
                    store.readAppliedIdentity()!!
                }, parked = { parkedLoads++; error("Ordinary setup stopped the requested runtime") })
            }
            input.writeText("rules: ['MATCH,REJECT']\n")
            api.setupConfig(setup())
            assertEquals(1, replacements); assertEquals(0, parkedLoads); assertTrue(requested)
            assertTrue(store.readCurrent()!!.userRequested)
            val active = store.readAppliedIdentity()!!
            assertEquals(store.readCurrent()!!.revision, active.revision)
            effects.onStaged = { staged ->
                val captured = generation
                requested = false; generation++
                effects.requestedIntent = false
                store.invalidate(staged.intentEpoch)
                assertFalse(meowAdmitGraphReplacement(7, captured, 7, generation, requested,
                    store.isCurrent(staged.revision, staged.intentEpoch)))
                meowApplyStagedGraph(staged, store, replaceRequested = { error("Late setup revived a stopped runtime") },
                    parked = { error("Old candidate bypassed its stop epoch") })
            }
            input.writeText("rules: ['MATCH,DIRECT']\n")
            try { api.setupConfig(setup()); fail("Superseded setup succeeded") }
            catch (failure: MeowProtocolFailure) { assertEquals("superseded", failure.code) }
            assertFalse(requested); assertFalse(store.readCurrent()!!.userRequested)
            assertEquals(active, store.readAppliedIdentity())
        }
    }

    @Test fun setupWaitsForGraphOwnerAndOwnerFailureLeavesCandidateUnapplied() = runBlocking<Unit> {
        val home = home(); File(home, "config.yaml").writeText("rules: ['MATCH,DIRECT']\n")
        val store = RuntimeHandoffStore(home)
        val effects = Effects()
        var candidate: MeowRuntimeSnapshot? = null
        effects.onStaged = { candidate = it; throw MeowConfigProtocolException("graph_failed", "actual graph failed readiness") }
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared())
            expect("graph_failed", null) { api.setupConfig(setup()) }
            assertEquals(candidate!!.revision, store.readCurrent()!!.revision)
            assertFalse(store.readCurrent()!!.userRequested)
            assertNull(store.readLastApplied())
            assertNull(store.readAppliedIdentity())
        }
    }

    @get:Rule val temporary = TemporaryFolder()
    private class Effects : MeowSetupEffects {
        var initialized = true
        var requestedIntent = false
        override fun hasRequestedIntent() = requestedIntent
        override fun isInitialized() = initialized
        var runtime: MeowSetupRuntime? = null
        var projection: MeowSetupProjection? = null
        var onApply: suspend () -> Unit = {}
        var refreshCalls = 0
        var onStaged: suspend (MeowRuntimeSnapshot) -> Unit = {}
        override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) = onStaged(snapshot)
        override suspend fun readRuntime() = runtime
        override suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection) { onApply(); this.projection = projection }
        override suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String): Boolean { refreshCalls++; return true }
    }
    private fun home() = temporary.newFolder().canonicalFile
    private fun shared(enabled: Boolean = false) = linkedMapOf<String, Any?>(
        "setupParams" to null, "vpnOptions" to mapOf("enable" to enabled, "port" to 7890, "ipv6" to false,
            "dnsHijacking" to false, "allowBypass" to false, "systemProxy" to false, "bypassDomain" to emptyList<String>(),
            "stack" to if (enabled) "lwip" else "", "routeAddress" to emptyList<String>(), "mtu" to 9000,
            "congestionController" to if (enabled) "builtin" else "", "accessControlProps" to mapOf("enable" to false, "mode" to "rejectSelected",
                "acceptList" to emptyList<String>(), "rejectList" to emptyList<String>(), "sort" to "none", "isFilterSystemApp" to true, "isFilterNonInternetApp" to true)),
        "stopTip" to "stop tip", "startTip" to "start tip", "localNetworkTip" to "local tip", "currentProfileName" to "profile",
        "stopText" to "stop", "onlyStatisticsProxy" to true, "showStopAction" to false)
    private fun update() = linkedMapOf<String, Any?>("tun" to mapOf("enable" to false, "stack" to "lwip"),
        "mixed-port" to 7890, "allow-lan" to true, "find-process-mode" to "always", "mode" to "global", "log-level" to "info", "ipv6" to true,
        "tcp-concurrent" to true, "external-controller" to "127.0.0.1:9090", "secret" to "actual-controller-secret", "unified-delay" to true,
        "authentication" to listOf("user:pass:word"), "geo-auto-update" to false, "geo-update-interval" to 12,
        "geox-url" to mapOf("mmdb" to "https://example.com/mmdb", "asn" to "https://example.com/asn", "geosite" to "https://example.com/site"), "skip-cert-verify" to true)
    private fun record(digest: String = "original-shared-build-digest") = linkedMapOf<String, Any?>("digest" to digest, "yaml-md5" to "a".repeat(32), "groups" to 7, "proxies" to 9, "rules" to 13)
    private fun setup() = linkedMapOf<String, Any?>("selected-map" to mapOf("Pick" to "local"), "test-url" to "https://example.com/probe", "skip-cert-verify" to true, "applied" to record())
    private fun config(home: File) = MeowConfigProtocol(home, NativeConfigValidator { null }, { emptySet() })

    @Test fun fullSharedAndUpdateAreExplicitNativeRuntimeProjection() = runBlocking {
        val home = home(); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            assertEquals("", api.syncState(shared()))
            assertEquals("", api.updateConfig(update()))
            val projection = effects.projection!!
            assertEquals(ProxyMode.Global, projection.settings.mode)
            assertTrue(projection.settings.ipv6)
            assertTrue(projection.settings.network.allowLan)
            assertEquals("pass:word", projection.settings.network.authentication.password)
            assertFalse(projection.settings.requests.checkCertificate)
            assertEquals(12, projection.settings.geoResources.intervalHours)
            assertTrue(projection.settings.externalController.enabled)
            assertEquals("always", projection.settings.findProcessMode.wireName)
            assertTrue(projection.settings.backgroundPolicy.onlyStatisticsProxy)
            assertFalse(projection.settings.backgroundPolicy.showNotificationStopAction)
            assertEquals(update().keys, JSONObject(projection.updateParamsJson!!).keys().asSequence().toSet())
            assertFalse(File(home, "settings").exists())
            assertFalse(File(home, "profiles").exists())
        }
    }

    @Test fun setupStagesRealYamlAndPreservesSharedRecordWithoutClaimingApplied() = runBlocking {
        val home = home(); val file = File(home, "config.yaml").apply { writeText("proxies: [{name: local, type: direct}]\nrules: ['MATCH,local']\n") }
        val original = file.readBytes(); val effects = Effects(); val store = RuntimeHandoffStore(home)
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.updateConfig(update()); assertEquals("", api.setupConfig(setup()))
            val snapshot = api.staged()!!
            assertEquals(runtimeSha256(original), snapshot.handoff.compiledSha256)
            assertEquals(runtimeSha256(original), snapshot.handoff.sourceSha256)
            val record = JSONObject(snapshot.handoff.setupRecordJson)
            assertEquals("original-shared-build-digest", record.getString("digest"))
            assertEquals("a".repeat(32), record.getString("yaml-md5"))
            assertEquals(13, record.getInt("rules"))
            val runtime = JSONObject(snapshot.handoff.runtimeSettingsJson)
            assertEquals("local", runtime.getJSONObject("flutterSetup").getJSONObject("selected-map").getString("Pick"))
            assertEquals("https://example.com/probe", runtime.getJSONObject("flutterSetup").getString("test-url"))
            assertFalse(runtime.getJSONObject("requests").getBoolean("checkCertificate"))
            assertFalse(snapshot.userRequested)
            assertNull(store.readLastApplied())
            assertEquals(mapOf("init" to true, "running" to false, "applied" to null), api.getAppliedSetup())
            assertArrayEquals(original, file.readBytes())
            assertFalse(File(home, "settings").exists())
        }
    }

    @Test fun firstSharedSetupDoesNotRequireEarlierUpdateRpc() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("mode: direct\nlog-level: warning\nipv6: true\n")
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, Effects(), verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.setupConfig(setup())
            val runtime = JSONObject(api.staged()!!.handoff.runtimeSettingsJson)
            assertEquals("Direct", runtime.getString("mode"))
            assertTrue(runtime.getBoolean("ipv6"))
            assertEquals("warning", runtime.getJSONObject("flutterUpdate").getString("log-level"))
        }
    }

    @Test fun onlyRealReadyStoreReceiptCanAppearAsApplied() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val effects = Effects(); val store = RuntimeHandoffStore(home)
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.updateConfig(update()); api.setupConfig(setup())
            val staged = store.readCurrent()!!
            store.requestStart(staged.revision, staged.intentEpoch)
            effects.runtime = MeowSetupRuntime(staged.revision, staged.intentEpoch, staged.handoff.compiledSha256, "4", 1, 2)
            assertFalse(api.getAppliedSetup()["running"] as Boolean)
            store.commitApplied(staged.revision, staged.intentEpoch, RuntimeAppliedProof(1, 2, "4"))
            assertTrue(api.getAppliedSetup()["running"] as Boolean)
            assertEquals(record(), api.getAppliedSetup()["applied"])
            effects.runtime = effects.runtime!!.copy(listening = false)
            assertEquals(mapOf("init" to true, "running" to false, "applied" to record()), api.getAppliedSetup())
            assertEquals(staged.revision, store.readLastApplied()!!.revision)
            val changed = setup().apply { put("applied", record("new metadata")) }
            assertFalse(api.refreshAppliedSetup(changed))
            assertEquals(1, effects.refreshCalls)
            api.updateConfig(update())
            assertNotNull(effects.projection)
            effects.runtime = effects.runtime!!.copy(listening = true)
            assertTrue(api.getAppliedSetup()["running"] as Boolean)
            store.invalidate()
            assertFalse(api.refreshAppliedSetup(setup()))
            assertEquals(1, effects.refreshCalls)
        }
    }

    @Test fun reattachedProtocolStartsFromAppliedProjectionAndPreservesProxyIpv6() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val store = RuntimeHandoffStore(home); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.updateConfig(update()); api.setupConfig(setup())
            val candidate = api.staged()!!
            store.requestStart(candidate.revision, candidate.intentEpoch)
            store.commitApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(1, 2, "4"))
            effects.runtime = MeowSetupRuntime(candidate.revision, candidate.intentEpoch, candidate.handoff.compiledSha256, "4", 1, 2)
            val attached = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            attached.syncState(shared())
            assertEquals(ProxyMode.Global, effects.projection!!.settings.mode)
            assertTrue(effects.projection!!.settings.ipv6)
            assertTrue(effects.projection!!.settings.unifiedDelay)
            assertTrue(effects.projection!!.settings.tcpConcurrent)
            assertEquals("actual-controller-secret", effects.projection!!.settings.externalController.secret)
        }
    }

    @Test fun initializationAndStartupPolicyAreRealRequiredGates() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val effects = Effects(); val store = RuntimeHandoffStore(home)
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            effects.initialized = false
            assertEquals(false, api.getAppliedSetup()["init"])
            expect("not_initialized", null) { api.setupConfig(setup()) }
            effects.initialized = true
            api.syncState(shared()); api.setupConfig(setup())
            val selection = MeowRuntimeSelection({ error("Must not query an unconfirmed setup") }, { _, _ -> }, { error("Must not query an unconfirmed setup") }, Unit)
            expect("implementation_blocked", "$.test-url") { selection.apply(api.staged()!!.handoff) { true } }
        }
    }

    @Test fun allUnimplementedAndUnknownFieldsFailBeforeEffectsOrStoreMutation() = runBlocking {
        val home = home(); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            val legacy = shared(true).apply { put("vpnOptions", (getValue("vpnOptions") as Map<String, Any?>) + ("stack" to "mips")) }
            expect("implementation_blocked", "$.vpnOptions.stack") { api.syncState(legacy) }
            assertNull(effects.projection)
            api.syncState(shared())
            expect("bad_argument", "$.unknown") { api.updateConfig(update().apply { put("unknown", true) }) }
            expect("implementation_blocked", "$.authentication") { api.updateConfig(update().apply { put("authentication", listOf("u:p", "x:y")) }) }
            expect("implementation_blocked", "$.geox-url.geoip") { api.updateConfig(update().apply { put("geox-url", mapOf("geoip" to "https://example.com/geoip.dat")) }) }
            assertNull(api.staged())
        }
    }

    @Test fun enabledLwipProjectsActualHostFieldsAndStagesWithoutApplying() = runBlocking {
        val home = home(); val store = RuntimeHandoffStore(home); val effects = Effects()
        val file = File(home, "config.yaml").apply { writeText("proxies: [{name: local, type: direct}]\nrules: ['MATCH,local']\n") }
        val original = file.readBytes()
        val state = shared(true)
        val vpn = (state.getValue("vpnOptions") as Map<String, Any?>).toMutableMap().apply {
            put("ipv6", true); put("dnsHijacking", true); put("allowBypass", true); put("systemProxy", true)
            put("bypassDomain", listOf("*.local")); put("routeAddress", listOf("0.0.0.0/0", "::/0")); put("mtu", 1280)
            put("accessControlProps", mapOf("enable" to true, "mode" to "acceptSelected", "acceptList" to listOf("dev.example.app"), "rejectList" to emptyList<String>()))
        }
        state["vpnOptions"] = vpn
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(state)
            api.updateConfig(update().apply { put("tun", mapOf("enable" to true, "stack" to "lwip")) })
            val value = effects.projection!!.settings
            assertTrue(value.network.vpnEnabled); assertTrue(value.ipv6); assertTrue(value.network.dnsHijacking)
            assertTrue(value.network.allowBypass); assertTrue(value.network.systemProxy)
            assertEquals(listOf("*.local"), value.network.bypassDomains)
            assertEquals(listOf("0.0.0.0/0", "::/0"), value.network.routeAddress)
            assertEquals(1280, value.network.mtu)
            assertEquals(dev.aerove.lite.AppFilterMode.Include, value.appFilterMode)
            assertEquals(setOf("dev.example.app"), value.packages)
            api.setupConfig(setup())
            val staged = store.readCurrent()!!
            assertTrue(JSONObject(staged.handoff.vpnOptionsJson).getBoolean("enable"))
            assertEquals("lwip", JSONObject(staged.handoff.vpnOptionsJson).getString("stack"))
            assertEquals("builtin", JSONObject(staged.handoff.vpnOptionsJson).getString("congestionController"))
            assertEquals(1280, MeowRuntimeSnapshotSettings.decode(staged.handoff.runtimeSettingsJson).network.mtu)
            assertEquals(runtimeSha256(original), staged.handoff.compiledSha256)
            assertEquals("original-shared-build-digest", JSONObject(staged.handoff.setupRecordJson).getString("digest"))
            assertFalse(staged.userRequested); assertNull(store.readLastApplied())
            assertArrayEquals(original, file.readBytes())
            assertFalse(File(home, "settings").exists()); assertFalse(File(home, "profiles").exists())
        }
    }

    @Test fun enabledFirstSetupUsesLwipFallbackAndZeroMtuResetsToAndroidDefault() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            val state = shared(true)
            val vpn = (state.getValue("vpnOptions") as Map<String, Any?>).toMutableMap()
            vpn["mtu"] = 1280; state["vpnOptions"] = vpn; api.syncState(state)
            vpn["mtu"] = 0; api.syncState(state)
            assertEquals(9000, effects.projection!!.settings.network.mtu)
            api.setupConfig(setup())
            val tun = JSONObject(api.staged()!!.handoff.runtimeSettingsJson).getJSONObject("flutterUpdate").getJSONObject("tun")
            assertEquals(setOf("enable", "stack"), tun.keys().asSequence().toSet())
            assertTrue(tun.getBoolean("enable")); assertEquals("lwip", tun.getString("stack"))
        }
    }

    @Test fun enabledLwipRejectsLegacyAlgorithmsAndDesktopTunFieldsBeforeEffects() = runBlocking {
        val home = home(); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            val state = shared(true)
            val vpn = (state.getValue("vpnOptions") as Map<String, Any?>).toMutableMap()
            for (stack in listOf("mips", "gvisor", "mixed", "system")) {
                vpn["stack"] = stack; state["vpnOptions"] = vpn
                expect("implementation_blocked", "$.vpnOptions.stack") { api.syncState(state) }
                assertNull(effects.projection)
            }
            vpn["stack"] = "lwip"
            for (algorithm in listOf("cubic", "reno", "bbr", "bbr3", "")) {
                vpn["congestionController"] = algorithm
                expect("implementation_blocked", "$.vpnOptions.congestionController") { api.syncState(state) }
                assertNull(effects.projection)
            }
            api.syncState(shared())
            val before = effects.projection
            expect("bad_argument", "$.tun.enable") { api.updateConfig(update().apply { put("tun", mapOf("enable" to true, "stack" to "lwip")) }) }
            for (field in listOf("device", "auto-route")) {
                expect("bad_argument", "$.tun.$field") { api.updateConfig(update().apply { put("tun", mapOf("enable" to false, "stack" to "lwip", field to if (field == "device") "" else true)) }) }
            }
            expect("implementation_blocked", "$.tun.stack") { api.updateConfig(update().apply { put("tun", mapOf("enable" to false, "stack" to "system")) }) }
            assertSame(before, effects.projection)
            api.syncState(shared(true))
            val enabledProjection = effects.projection
            expect("bad_argument", "$.ipv6") { api.updateConfig(update().apply { put("ipv6", "true") }) }
            val invalidVpn = shared(true).apply { put("vpnOptions", (getValue("vpnOptions") as Map<String, Any?>) + ("ipv6" to "false")) }
            expect("bad_argument", "$.vpnOptions.ipv6") { api.syncState(invalidVpn) }
            assertSame(enabledProjection, effects.projection)
        }
    }

    @Test fun independentCoreAndVpnIpv6SurviveUpdateResyncAndRealYamlStaging() = runBlocking {
        for ((coreIpv6, vpnIpv6) in listOf(false to true, true to false)) {
            val home = home(); val effects = Effects(); val store = RuntimeHandoffStore(home)
            val file = File(home, "config.yaml").apply { writeText("ipv6: $coreIpv6\ndns: {enable: true}\n") }
            val original = file.readBytes()
            val state = shared(true).apply {
                put("vpnOptions", (getValue("vpnOptions") as Map<String, Any?>) + mapOf("ipv6" to vpnIpv6, "mtu" to 1500, "dnsHijacking" to true))
            }
            config(home).use { config ->
                val api = MeowSetupProtocol(home, store, config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
                api.syncState(state)
                api.updateConfig(update().apply { put("ipv6", coreIpv6); put("tun", mapOf("enable" to true, "stack" to "lwip")) })
                assertEquals(coreIpv6, effects.projection!!.settings.ipv6)
                api.syncState(state)
                assertEquals(coreIpv6, effects.projection!!.settings.ipv6)
                api.setupConfig(setup())
                val staged = store.readCurrent()!!
                MeowRuntimeSnapshotSettings.validate(staged.handoff)
                val core = MeowRuntimeSnapshotSettings.decode(staged.handoff.runtimeSettingsJson)
                val builder = meowFlutterBuilderSettings(core, staged.handoff.vpnOptionsJson)
                assertEquals(coreIpv6, core.ipv6)
                assertEquals(vpnIpv6, builder.ipv6)
                assertEquals(1500, builder.network.mtu); assertTrue(builder.network.dnsHijacking)
                assertEquals(coreIpv6, ConfigYaml.parse(staged.handoff.compiledYaml)["ipv6"])
                assertEquals(coreIpv6, JSONObject(staged.handoff.runtimeSettingsJson).getJSONObject("flutterUpdate").getBoolean("ipv6"))
                assertEquals(vpnIpv6, JSONObject(staged.handoff.vpnOptionsJson).getBoolean("ipv6"))
                assertFalse(staged.userRequested); assertNull(store.readLastApplied())
                assertArrayEquals(original, file.readBytes())
            }
        }
    }

    @Test fun failedRuntimeApplicationDoesNotPublishNewProjection() = runBlocking {
        val home = home(); val effects = Effects()
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects, verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared())
            val previous = effects.projection
            effects.onApply = { error("actual runtime rejected update") }
            try { api.updateConfig(update()); fail() } catch (failure: IllegalStateException) { assertEquals("actual runtime rejected update", failure.message) }
            assertSame(previous, effects.projection)
        }
    }

    @Test fun arbitraryPrivateResourcePathDoesNotGrantManifestAuthority() = runBlocking {
        val home = home(); val resource = File(home, "unsealed-ca.pem").apply { writeText("not a sealed resource") }
        File(home, "config.yaml").writeText("proxies: [{name: x, type: direct, ca: '${resource.path.replace('\\', '/')}'}]")
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, Effects(), verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(shared()); api.updateConfig(update())
            expect("bad_argument", "$.resources") { api.setupConfig(setup()) }
            assertNull(api.staged())
        }
    }

    @Test fun stopDuringRealStageValidationPreventsOldIntentCommit() = runBlocking {
        val home = home(); File(home, "config.yaml").writeText("{}")
        val store = RuntimeHandoffStore(home)
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, Effects(), verifiedFeatures = { emptySet() },
                validator = NativeConfigValidator { store.invalidate(); null })
            api.syncState(shared())
            try { api.setupConfig(setup()); fail() } catch (failure: IllegalStateException) { assertEquals("runtime_commit_superseded", failure.message) }
            assertNull(store.readCurrent())
            assertNull(store.readLastApplied())
            assertEquals(1L, store.currentEpoch())
        }
    }

    @Test fun changedSourceDuringValidationCannotPublishAnotherSourceHash() = runBlocking {
        val home = home(); val source = File(home, "config.yaml").apply { writeText("{}") }
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, Effects(), verifiedFeatures = { emptySet() },
                validator = NativeConfigValidator { yaml ->
                    val normalized = ConfigYaml.parse(yaml)
                    assertEquals(true, normalized["strict"])
                    assertFalse(normalized.containsKey("mode"))
                    source.writeText("mode: direct")
                    null
                })
            api.syncState(shared())
            expect("superseded", null) { api.setupConfig(setup()) }
            assertNull(api.staged())
        }
    }

    @Test fun sealedManifestHashIsReadBackBeforePreparedValidationAndStaging() = runBlocking {
        val home = home(); val bundle = File(home, "runtime/resources/bundles/id/generation").apply { mkdirs() }
        val payload = File(bundle, "domains.txt").apply { writeText("example.com\n") }
        File(bundle, "input.yaml").writeText("{}")
        File(bundle, "config.yaml").writeText("{}")
        File(bundle, "manifest.json").writeText(JSONObject().put("schema", 1).put("coreVersion", "meow-rs/0.26.1/$MEOW_RUNTIME_CORE_SHA")
            .put("profileId", "id").put("generation", "generation").put("sourceSha256", runtimeSha256("{}".toByteArray()))
            .put("compiledInputSha256", runtimeSha256("{}".toByteArray())).put("configSha256", runtimeSha256("{}".toByteArray()))
            .put("records", org.json.JSONArray().put(JSONObject().put("file", payload.name).put("bytes", payload.length()).put("sha256", runtimeSha256(payload.readBytes())))).toString())
        File(home, "config.yaml").writeText("rule-providers: {domains: {type: file, behavior: domain, format: text, path: '${payload.path.replace('\\', '/')}'} }\nrules: ['RULE-SET,domains,DIRECT', 'MATCH,DIRECT']")
        var actualRoot: File? = null
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, Effects(),
                NativePreparedConfigValidator { _, root -> actualRoot = root; null }, { emptySet() }, NativeConfigValidator { null })
            api.syncState(shared()); api.updateConfig(update()); api.setupConfig(setup())
            assertEquals(bundle, actualRoot)
            val snapshot = api.staged()!!
            assertEquals(payload.readText(), File(snapshot.resourceDirectory, payload.name).readText())
            payload.writeText("changed")
            expect("bad_argument", "$.resources") { api.setupConfig(setup()) }
            assertEquals(snapshot.revision, api.staged()!!.revision)
        }
    }

    @Test fun selectorUsesRealReadbackAndStandardStaleFirstMemberBehavior() = runBlocking {
        val nodes = listOf(ProxyNode("first", "Direct", null), ProxyNode("second", "Direct", null))
        var selected = "second"; val calls = mutableListOf<Pair<String, String>>()
        val selection = MeowRuntimeSelection({ listOf(ProxyGroup("Pick", "Selector", selected, nodes)) },
            { group, proxy -> calls.add(group to proxy); selected = proxy }, { RouteSnapshot(2u, 3u, mapOf("Pick" to selected)) }, Unit)
        val receipt = selection.apply(mapOf("missing" to "whatever", "Pick" to "removed")) { true }
        assertEquals(listOf("Pick" to "first"), calls)
        assertEquals(mapOf("Pick" to "first"), receipt.selected)
    }

    @Test fun selectionRejectsSupersededOrUnconfirmedRuntime() = runBlocking {
        val node = ProxyNode("first", "Direct", null)
        val selection = MeowRuntimeSelection({ listOf(ProxyGroup("Pick", "Selector", "wrong", listOf(node))) }, { _, _ -> },
            { RouteSnapshot(2u, 3u, mapOf("Pick" to "wrong")) }, Unit)
        expect("superseded", null) { selection.apply(mapOf("Pick" to "first")) { false } }
        expect("selection_error", "selected-map.Pick") { selection.apply(mapOf("Pick" to "first")) { true } }
    }

    @Test fun selectionUsesActualCoreApiHttpAndReadsBackTheChosenRoute() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            fun route(pick: String, version: String) = """{"core-epoch":"2","route-version":"$version","picks":{"Pick":"$pick"}}"""
            fun proxies(pick: String) = """{"proxies":{"Pick":{"type":"Selector","now":"$pick","all":["first","second"]},"first":{"type":"Direct"},"second":{"type":"Direct"}}}"""
            listOf(route("second", "1"), proxies("second"), "{}", proxies("first"), route("first", "2")).forEach { server.enqueue(MockResponse().setBody(it)) }
            CoreApiClient(ControlEndpoint(server.port, "x".repeat(32))).use { client ->
                val receipt = MeowRuntimeSelection(client).apply(mapOf("Pick" to "removed")) { true }
                assertEquals("first", receipt.selected["Pick"])
                assertEquals(2uL, receipt.coreEpoch)
                assertEquals("/aerove/route", server.takeRequest().path)
                assertEquals("/proxies", server.takeRequest().path)
                val selection = server.takeRequest()
                assertEquals("PUT", selection.method)
                assertEquals("/proxies/Pick", selection.path)
                assertEquals("first", JSONObject(selection.body.readUtf8()).getString("name"))
                assertEquals("/proxies", server.takeRequest().path)
                assertEquals("/aerove/route", server.takeRequest().path)
            }
        } finally { server.shutdown() }
    }

    @Test fun sharedHttpInputSealsBeforeValidationAndStoreOwnsLineageBeforeLeaseRelease() = runBlocking<Unit> {
        val home = home(); val runtime = File(home, "runtime")
        val file = File(home, "config.yaml").apply { writeText("global-ua: shared-exact-UA\nrule-providers: {r: {type: http, url: 'https://example.invalid/r', path: rules/r.yaml, behavior: domain, proxy: DIRECT, header: {X-Origin: [one, two]}}}\nrules: ['MATCH,DIRECT']\n") }
        val original = file.readBytes()
        val prepared = NativePreparedConfigValidator { yaml, directory ->
            val row = (ConfigYaml.parse(yaml)["rule-providers"] as Map<*, *>)["r"] as Map<*, *>
            assertEquals("file", row["type"]); assertTrue(File(row["path"] as String).isFile)
            assertTrue(directory.isDirectory); null
        }
        val schema = dev.aerove.lite.data.CompatibilityValidator.forMeow(NativeConfigValidator { error("unsealed native conversion") }, runtime, prepared) { emptySet() }
        var transportRoot: File? = null
        val manager = dev.aerove.lite.data.ResourceBundleManager(runtime, schema, dev.aerove.lite.data.NativeResourceValidator { _, _, _, _ -> null },
            dev.aerove.lite.data.ResourceDownloader(), providerDownloads = dev.aerove.lite.data.ProviderDownloadFactory { context ->
                transportRoot = context.stagingRoot.parentFile
                assertEquals("shared-exact-UA", context.http!!.userAgent); assertFalse(context.http!!.checkCertificate)
                object : dev.aerove.lite.data.ProviderDownloadScope {
                    override suspend fun download(request: dev.aerove.lite.data.ProviderDownloadRequest): dev.aerove.lite.data.ProviderDownloadResult {
                        assertEquals("DIRECT", request.proxy); assertEquals(listOf("one", "two"), request.headers["X-Origin"])
                        request.destination.writeText("payload: [example.com]\n")
                        return dev.aerove.lite.data.ProviderDownloadResult(request.destination.length(), runtimeSha256(request.destination.readBytes()))
                    }
                    override suspend fun closeAndJoin() = Unit
                }
            })
        var sawProtectedGeneration = false
        val store = RuntimeHandoffStore(home, validateProjection = { handoff ->
            manager.prune(emptyMap(), emptySet())
            if (handoff.resources.isNotEmpty() && File(handoff.resources.first().sourcePath).isFile) sawProtectedGeneration = true
        })
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, Effects(), prepared, { emptySet() }, NativeConfigValidator { null },
                dev.aerove.lite.data.FlutterResourceSealer(manager, "fallback-UA"))
            api.syncState(shared()); api.setupConfig(setup())
            val next = api.staged()!!
            assertTrue(sawProtectedGeneration); assertFalse(transportRoot!!.exists())
            assertEquals(runtimeSha256(original), next.handoff.sourceSha256)
            assertArrayEquals(original, file.readBytes())
            assertEquals(record()["digest"], JSONObject(next.handoff.setupRecordJson).getString("digest"))
            assertEquals("shared-exact-UA", JSONObject(next.handoff.runtimeSettingsJson).getJSONObject("requests").getString("globalUserAgent"))
            assertEquals(file.readText(), File(next.resourceDirectory, "input.yaml").readText())
            val manifest = JSONObject(File(next.resourceDirectory, "manifest.json").readText())
            assertEquals(runtimeSha256(original), manifest.getString("compiledInputSha256"))
            next.handoff.resources.forEach { asset -> assertEquals(asset.sha256, runtimeSha256(File(next.resourceDirectory, asset.relativePath).readBytes())) }
            manager.prune(emptyMap(), emptySet())
            assertTrue(File(next.resourceDirectory, "manifest.json").isFile)
            assertNull(store.readLastApplied()); assertEquals(false, api.getAppliedSetup()["running"])
            assertFalse(File(home, "profiles").exists()); assertFalse(File(home, "settings").exists())
        }
    }

    @Test fun sourceAndEpochChangesDuringSealingKeepPreviousCandidate() = runBlocking<Unit> {
        for (changeEpoch in listOf(false, true)) {
            val home = home(); val runtime = File(home, "runtime"); val store = RuntimeHandoffStore(home)
            val file = File(home, "config.yaml").apply { writeText("rules: ['MATCH,DIRECT']\n") }
            config(home).use { config ->
                val initial = MeowSetupProtocol(home, store, config, Effects(), verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
                initial.syncState(shared()); initial.setupConfig(setup())
                val old = store.readCurrent()!!
                file.writeText("rule-providers: {r: {type: http, url: 'https://example.invalid/r', path: r.yaml, behavior: domain, proxy: DIRECT}}\nrules: ['MATCH,DIRECT']\n")
                val schema = dev.aerove.lite.data.CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
                    NativePreparedConfigValidator { _, _ -> if (changeEpoch) store.invalidate() else file.appendText("# source changed\n"); null }) { emptySet() }
                val manager = dev.aerove.lite.data.ResourceBundleManager(runtime, schema, dev.aerove.lite.data.NativeResourceValidator { _, _, _, _ -> null },
                    dev.aerove.lite.data.ResourceDownloader(), providerDownloads = dev.aerove.lite.data.ProviderDownloadFactory {
                        object : dev.aerove.lite.data.ProviderDownloadScope {
                            override suspend fun download(request: dev.aerove.lite.data.ProviderDownloadRequest): dev.aerove.lite.data.ProviderDownloadResult {
                                request.destination.writeText("payload: [example.com]\n")
                                return dev.aerove.lite.data.ProviderDownloadResult(request.destination.length(), runtimeSha256(request.destination.readBytes()))
                            }
                            override suspend fun closeAndJoin() = Unit
                        }
                    })
                val api = MeowSetupProtocol(home, store, config, Effects(), NativePreparedConfigValidator { _, _ -> null }, { emptySet() }, NativeConfigValidator { null },
                    dev.aerove.lite.data.FlutterResourceSealer(manager, "fallback-UA"))
                api.syncState(shared())
                try { api.setupConfig(setup()); fail("superseded source was committed") } catch (failure: Exception) {
                    assertTrue(failure.message.orEmpty().contains(if (changeEpoch) "superseded" else "changed"))
                }
                assertEquals(old.revision, store.readCurrent()!!.revision)
                manager.prune(emptyMap(), emptySet())
                assertTrue(File(runtime, "resources/bundles").walkTopDown().none { it.name == "manifest.json" })
            }
        }
    }

    @Test fun existingSealedFileInputBorrowsGenerationWithoutRedownloadOrLegacyDatabase() = runBlocking<Unit> {
        val home = home(); val runtime = File(home, "runtime")
        var downloads = 0
        val prepared = NativePreparedConfigValidator { _, _ -> null }
        val schema = dev.aerove.lite.data.CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime, prepared) { emptySet() }
        val manager = dev.aerove.lite.data.ResourceBundleManager(runtime, schema, dev.aerove.lite.data.NativeResourceValidator { _, _, _, _ -> null },
            dev.aerove.lite.data.ResourceDownloader(), providerDownloads = dev.aerove.lite.data.ProviderDownloadFactory {
                object : dev.aerove.lite.data.ProviderDownloadScope {
                    override suspend fun download(request: dev.aerove.lite.data.ProviderDownloadRequest): dev.aerove.lite.data.ProviderDownloadResult {
                        downloads++; request.destination.writeText("payload: [example.com]\n")
                        return dev.aerove.lite.data.ProviderDownloadResult(request.destination.length(), runtimeSha256(request.destination.readBytes()))
                    }
                    override suspend fun closeAndJoin() = Unit
                }
            })
        val sealer = dev.aerove.lite.data.FlutterResourceSealer(manager, "fallback-UA")
        val original = sealer.prepare("rule-providers: {r: {type: http, url: 'https://example.invalid/r', path: r.yaml, behavior: domain, proxy: DIRECT}}\nrules: ['MATCH,DIRECT']", true)
        manager.committed(original.directory.parentFile.name, original.directory.name)
        File(home, "config.yaml").writeText(original.yaml)
        var protectedDuringCopy = false
        val store = RuntimeHandoffStore(home, validateProjection = { manager.prune(emptyMap(), emptySet()); protectedDuringCopy = original.directory.isDirectory })
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, Effects(), prepared, { emptySet() }, NativeConfigValidator { null }, sealer)
            api.syncState(shared()); api.setupConfig(setup())
            assertEquals(1, downloads); assertTrue(protectedDuringCopy)
            val snapshot = api.staged()!!
            assertTrue(File(snapshot.resourceDirectory, "manifest.json").isFile)
            manager.prune(emptyMap(), emptySet())
            assertFalse(original.directory.exists())
            assertTrue(File(snapshot.resourceDirectory, "input.yaml").isFile)
            assertFalse(File(home, "profiles").exists()); assertFalse(File(home, "settings").exists())
        }
        original.closeAndJoin()
    }

    private suspend fun expect(code: String, field: String?, work: suspend () -> Any?) {
        try { work(); fail("Expected $code") } catch (failure: MeowConfigProtocolException) {
            assertEquals(code, failure.code); if (field != null) assertEquals(field, failure.details["field"])
        }
    }
}
