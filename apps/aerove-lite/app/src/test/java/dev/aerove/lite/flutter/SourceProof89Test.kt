package dev.aerove.lite.flutter

import dev.aerove.lite.data.NativeConfigValidator
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SourceProof89Test {
    private fun map(value: JSONObject): Map<String, Any?> = value.keys().asSequence().associateWith { key -> convert(value.get(key)) }
    private fun convert(value: Any?): Any? = when(value) { JSONObject.NULL -> null; is JSONObject -> map(value); is JSONArray -> (0 until value.length()).map { convert(value.get(it)) }; else -> value }
    @Test fun independentNativeProjectionMatchesRealDartSqliteAndIgnoresUnrelatedEdits() {
        for (suffix in listOf("", ".resources")) {
        val external = System.getProperty("sourceProof89.fixture")
        val content = if (external != null) File(external + suffix).readText() else checkNotNull(javaClass.getResourceAsStream("/source-proof89/projection-fixture.json$suffix")).bufferedReader().use { it.readText() }
        val fixture = JSONObject(content)
        val all = map(fixture.getJSONObject("all")).mapValues { (_, value) -> @Suppress("UNCHECKED_CAST") (value as List<Map<String, Any?>>) }
        val config = map(fixture.getJSONObject("config")); val proof = fixture.getJSONObject("proof")
        val rows = nativeLiteSelectedRows(all, 1)
        rows.forEach { (name, values) -> assertEquals(proof.getJSONObject("tables").getJSONObject(name).getString("sha256"), nativeLiteJsonHash(values)) }
        val selected = rows.getValue("profiles").single { (it["id"] as Number).toLong() == 1L }
        val bindings = JSONObject(selected["overrides"] as String).optJSONObject("filterBindings") ?: JSONObject()
        val filters = bindings.keys().asSequence().flatMap { key -> val ids = bindings.getJSONArray(key); (0 until ids.length()).map { ids.getLong(it) }.asSequence() }.toSet()
        val runtime = nativeLiteRuntimeConfig(config, filters)
        assertEquals(proof.getString("configSha256"), nativeLiteJsonHash(runtime))
        assertEquals(proof.getString("setupSha256"), nativeLiteJsonHash(nativeLiteSetupConfig(runtime)))
        assertEquals(proof.getString("liveSha256"), nativeLiteJsonHash(nativeLiteLiveConfig(config)))
        val edited = all + ("profiles" to all.getValue("profiles").map { if ((it["id"] as Number).toLong() == 2L) it + ("label" to "other-edit") else it })
        assertEquals(nativeLiteJsonHash(rows), nativeLiteJsonHash(nativeLiteSelectedRows(edited, 1)))
        val node = all + ("custom_proxies" to all.getValue("custom_proxies").map { if ((it["id"] as Number).toLong() == 10L) it + ("definition" to "changed") else it })
        assertNotEquals(nativeLiteJsonHash(rows), nativeLiteJsonHash(nativeLiteSelectedRows(node, 1)))
        }
    }
    @Test fun resourceCaptureIncludesPrivateFileGeoAndCertificateInputs() {
        listOf("file","http").forEach { type -> listOf("proxy-providers","rule-providers").forEach { kind -> assertTrue(meowNeedsResourceCapture(mapOf(kind to mapOf("p" to mapOf("type" to type))))) } }
        assertFalse(meowNeedsResourceCapture(mapOf("proxy-providers" to mapOf("p" to mapOf("type" to "inline")))))
        listOf("mmdb","asn-mmdb","geosite","mmdb-download-url","asn-mmdb-download-url","geosite-download-url").forEach { key -> assertTrue(meowNeedsResourceCapture(mapOf(key to "source"))) }
        assertTrue(meowNeedsResourceCapture(mapOf("geodata" to mapOf("mmdb-path" to "source"))))
        assertTrue(meowNeedsResourceCapture(mapOf("geodata" to mapOf("url" to mapOf("asn" to "https://example.test/asn")))))
        listOf("tls-cert","tls-key","ca").forEach { key -> assertTrue(meowNeedsResourceCapture(mapOf("proxies" to listOf(mapOf(key to "source"))))) }
        assertFalse(meowNeedsResourceCapture(mapOf("geox-url" to mapOf("mmdb" to "https://example.test/unused"))))
    }
    private fun update(mode: String = "rule") = linkedMapOf<String, Any?>("tun" to mapOf("enable" to false, "stack" to "lwip"),
        "mixed-port" to 7890, "allow-lan" to false, "find-process-mode" to "off", "mode" to mode, "log-level" to "info", "ipv6" to false,
        "tcp-concurrent" to false, "external-controller" to "", "secret" to "", "unified-delay" to true, "authentication" to emptyList<String>(),
        "geo-auto-update" to false, "geo-update-interval" to 24, "geox-url" to emptyMap<String,String>(), "skip-cert-verify" to false)
    private fun proof(mode: String) = JSONObject().put("schema",2).put("databaseSchema",22).put("configSha256",mode)
        .put("setupSha256","static-selected").put("liveSha256",nativeLiteJsonHash(update(mode))).put("tables",JSONObject().put("profiles","selected-profile")).put("files",JSONArray())
    private fun setup(proof: JSONObject) = mapOf("selected-map" to emptyMap<String,String>(), "test-url" to "https://example.test/probe", "skip-cert-verify" to false,
        "applied" to mapOf("digest" to "fixture", "yaml-md5" to "a".repeat(32), "groups" to 0, "proxies" to 0, "rules" to 1), "data-source-proof" to map(proof))
    private fun shared() = mapOf<String,Any?>("setupParams" to null, "vpnOptions" to mapOf("enable" to false,"port" to 7890,"ipv6" to false,"dnsHijacking" to false,
        "allowBypass" to false,"systemProxy" to false,"bypassDomain" to emptyList<String>(),"stack" to "","routeAddress" to emptyList<String>(),"mtu" to 9000,
        "congestionController" to "", "accessControlProps" to mapOf("enable" to false,"mode" to "rejectSelected","acceptList" to emptyList<String>(),"rejectList" to emptyList<String>())),
        "stopTip" to "Stop","startTip" to "Start","localNetworkTip" to "LAN","currentProfileName" to "Fixture","stopText" to "Stop","onlyStatisticsProxy" to true)
    @Test fun liveCommitRefreshesExistingHandoffAndRejectsFailureCancellationAndEpochRaces() = runBlocking<Unit> {
        val root = Files.createTempDirectory("proof89-owner").toFile().canonicalFile
        File(root,"config.yaml").writeText("rules: ['MATCH,DIRECT']\n")
        var current = proof("rule")
        fun verify(value: JSONObject) { check(nativeLiteJsonHash(value) == nativeLiteJsonHash(current)) { "source changed" } }
        val store = RuntimeHandoffStore(root, validateSource = { verify(JSONObject(it.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")) })
        var runtime: MeowSetupRuntime? = null
        var inject: suspend () -> Unit = {}
        val effects = object: MeowSetupEffects {
            override fun isInitialized() = true
            override suspend fun readRuntime() = runtime
            override suspend fun refreshApplied(expected: MeowSetupRuntime, setupParamsJson: String) = false
            override suspend fun applyStaged(snapshot: MeowRuntimeSnapshot) {
                store.commitGraphApplied(snapshot.revision,snapshot.intentEpoch,RuntimeAppliedProof(7,2,"1"),snapshot.handoff)
                runtime = MeowSetupRuntime(snapshot.revision,snapshot.intentEpoch,snapshot.handoff.compiledSha256,"1",7,2,false)
            }
            override suspend fun applyUpdate(expected: MeowSetupRuntime?, projection: MeowSetupProjection) {
                if(expected == null) return
                inject()
                val prior = store.readLastApplied()!!.handoff
                val identity = store.readAppliedIdentity()!!
                val encoded = JSONObject(prior.runtimeSettingsJson).put("flutterSetup",JSONObject(projection.setupParamsJson!!)).put("flutterUpdate",JSONObject(projection.updateParamsJson!!))
                val next = RuntimeHandoff(prior.coreSha,prior.sourceId,prior.sourceRevision,prior.sourceSha256,prior.compiledYaml,prior.compiledSha256,
                    prior.setupRecordJson,encoded.toString(),prior.vpnOptionsJson,prior.notificationJson,prior.resources)
                val applied = store.commitGraphRuntimeProjection(identity,next,identity.proof)
                runtime = expected.copy(coreEpoch=applied.proof.coreEpoch)
            }
        }
        MeowConfigProtocol(root,NativeConfigValidator { null },{emptySet()}).use { config ->
            val api = MeowSetupProtocol(root,store,config,effects,verifiedFeatures={emptySet()},validator=NativeConfigValidator { null },sourceVerifier=::verify)
            api.syncState(shared()); api.setupConfig(setup(current))
            val initial = store.readCurrent()!!
            current = proof("global")
            api.updateConfig(update("global") + ("data-source-proof" to map(current)))
            val committed = store.readCurrent()!!
            assertEquals(initial.revision,committed.revision); assertFalse(committed.userRequested)
            verify(JSONObject(committed.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof"))
            val reopened = RuntimeHandoffStore(root).readCurrent()!!
            assertEquals(committed.handoff.runtimeSettingsJson,reopened.handoff.runtimeSettingsJson); assertFalse(reopened.userRequested)
            current = proof("direct")
            inject = { throw IllegalStateException("failed actual apply") }
            assertTrue(runCatching { api.updateConfig(update("direct") + ("data-source-proof" to map(current))) }.isFailure)
            assertEquals(committed.handoff.runtimeSettingsJson,store.readCurrent()!!.handoff.runtimeSettingsJson)
            inject = { throw CancellationException("cancelled") }
            assertTrue(runCatching { api.updateConfig(update("direct") + ("data-source-proof" to map(current))) }.isFailure)
            assertEquals(committed.handoff.runtimeSettingsJson,store.readCurrent()!!.handoff.runtimeSettingsJson)
            inject = { current = proof("rule") }
            assertTrue(runCatching { api.updateConfig(update("direct") + ("data-source-proof" to map(proof("direct")))) }.isFailure)
            assertEquals(committed.handoff.runtimeSettingsJson,store.readCurrent()!!.handoff.runtimeSettingsJson)
            current=proof("direct"); current.getJSONObject("tables").put("profiles","edited selected profile")
            inject = { error("stale static dependency reached runtime") }
            assertTrue(runCatching { api.updateConfig(update("direct") + ("data-source-proof" to map(current))) }.isFailure)
            assertEquals(committed.handoff.runtimeSettingsJson,store.readCurrent()!!.handoff.runtimeSettingsJson)
            current=proof("direct"); inject = { store.invalidate(committed.intentEpoch) }
            assertTrue(runCatching { api.updateConfig(update("direct") + ("data-source-proof" to map(current))) }.isFailure)
            assertEquals(committed.handoff.runtimeSettingsJson,store.readLastApplied()!!.handoff.runtimeSettingsJson)
            assertFalse(store.readCurrent()!!.userRequested)
        }
    }
}
