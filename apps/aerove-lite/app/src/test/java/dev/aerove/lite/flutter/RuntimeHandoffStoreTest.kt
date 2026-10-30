package dev.aerove.lite.flutter

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeHandoffStoreTest {
    private fun handoff(yaml: String = "rules: ['MATCH,DIRECT']\n", resources: List<RuntimeHandoffResource> = emptyList()): RuntimeHandoff {
        val settings = JSONObject().put("network", JSONObject()).put("requests", JSONObject()).put("backgroundPolicy", JSONObject())
            .put("externalController", JSONObject()).put("dnsPreferences", JSONObject()).put("geoResources", JSONObject())
            .put("ipv6", false).put("unifiedDelay", true).put("tcpConcurrent", true).put("autoStart", false)
            .put("mode", "Rule").put("appFilterMode", "All").put("findProcessMode", "strict").put("packages", org.json.JSONArray())
        val options = JSONObject().put("enable", true).put("ipv6", false).put("dnsHijacking", true)
            .put("allowBypass", false).put("systemProxy", false).put("port", 0).put("accessControlProps", JSONObject())
            .put("bypassDomain", org.json.JSONArray()).put("stack", "")
        val notification = JSONObject().put("stopTip", "Stop").put("startTip", "Start").put("localNetworkTip", "LAN")
            .put("currentProfileName", "Profile").put("stopText", "Stop").put("showStopAction", true).put("onlyStatisticsProxy", false)
        return RuntimeHandoff(MEOW_RUNTIME_CORE_SHA, "42", "source-revision", "a".repeat(64), yaml, runtimeSha256(yaml.toByteArray()),
            JSONObject().put("digest", "source-digest").put("yaml-md5", "b".repeat(32)).put("groups", 0).put("proxies", 0).put("rules", 1).toString(),
            settings.toString(), options.toString(), notification.toString(), resources)
    }

    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected rejection") } catch (expected: IllegalArgumentException) { } catch (expected: IllegalStateException) { }
        catch (expected: org.json.JSONException) { }
    }

    @Test fun desiredDoesNotReplaceAppliedAndReadyProofIsRequired() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val store = RuntimeHandoffStore(root)
        val first = store.commit(handoff(), 0, null)
        assertNull(store.readLastApplied())
        rejects { store.commitApplied(first.revision, first.intentEpoch, RuntimeAppliedProof(1, 1, "1")) }
        store.requestStart(first.revision, first.intentEpoch)
        rejects { store.commitApplied(first.revision, first.intentEpoch, RuntimeAppliedProof(0, 1, "1")) }
        store.commitApplied(first.revision, first.intentEpoch, RuntimeAppliedProof(1, 2, "3"))
        val next = store.commit(handoff("rules: ['MATCH,REJECT']\n"), first.intentEpoch, first.revision)
        assertEquals(next.revision, store.readCurrent()!!.revision)
        assertEquals(first.revision, RuntimeHandoffStore(root).readLastApplied()!!.revision)
    }

    @Test fun stopRejectsLatePermissionAndSurvivesNoEngineRestore() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val store = RuntimeHandoffStore(root)
        val snapshot = store.commit(handoff(), 0, null)
        store.requestStart(snapshot.revision, snapshot.intentEpoch)
        store.commitApplied(snapshot.revision, snapshot.intentEpoch, RuntimeAppliedProof(1, 1, "9"))
        assertEquals(snapshot.revision, RuntimeHandoffStore(root).admit(snapshot.revision, snapshot.intentEpoch).revision)
        val stoppedEpoch = store.invalidate(snapshot.intentEpoch)
        rejects { RuntimeHandoffStore(root).requestStart(snapshot.revision, snapshot.intentEpoch) }
        rejects { RuntimeHandoffStore(root).restoreLastApplied(stoppedEpoch) }
        assertFalse(RuntimeHandoffStore(root).readCurrent()!!.userRequested)
        assertFalse(store.isCurrent(snapshot.revision, snapshot.intentEpoch))
    }

    @Test fun staleStopCannotInvalidateNewCommitAndCommitCannotCrossStop() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val a = RuntimeHandoffStore(root); val b = RuntimeHandoffStore(root)
        val first = a.commit(handoff(), 0, null)
        val committed = async { b.commit(handoff(), first.intentEpoch, first.revision) }
        val next = committed.await()
        rejects { a.invalidate(first.intentEpoch) }
        assertEquals(next.intentEpoch, a.currentEpoch())
        a.invalidate(next.intentEpoch)
        rejects { b.commit(handoff(), next.intentEpoch, next.revision) }
    }

    @Test fun badCandidateCannotReplaceVerifiedSnapshotAndResourcesAreDefensive() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val asset = File(root, "asset.yaml").apply { writeText("payload") }
        val entries = mutableListOf(RuntimeHandoffResource("rules/asset.yaml", asset.path, runtimeSha256(asset.readBytes())))
        val input = handoff(resources = entries)
        entries.clear()
        val store = RuntimeHandoffStore(root)
        val first = store.commit(input, 0, null)
        assertEquals("payload", File(first.resourceDirectory, "rules/asset.yaml").readText())
        asset.writeText("changed after commit")
        assertNotNull(store.admitAfterStart(first))
        val invalid = RuntimeHandoff("0".repeat(40), "42", "rev", "a".repeat(64), "bad", "0".repeat(64), "{}", "{}", "{}", "{}")
        rejects { store.commit(invalid, first.intentEpoch, first.revision) }
        assertEquals(first.revision, store.readCurrent()!!.revision)
        File(first.resourceDirectory, "rules/asset.yaml").writeText("tampered")
        rejects { RuntimeHandoffStore(root).readCurrent() }
    }

    private suspend fun RuntimeHandoffStore.admitAfterStart(snapshot: MeowRuntimeSnapshot): MeowRuntimeSnapshot {
        requestStart(snapshot.revision, snapshot.intentEpoch)
        return admit(snapshot.revision, snapshot.intentEpoch)
    }

    @Test fun corruptStateIsAnErrorAndNeverFallsBackToLegacy() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val store = RuntimeHandoffStore(root)
        store.commit(handoff(), 0, null)
        val file = File(root, "runtime-handoffs/current.json")
        val row = JSONObject(file.readText()).put("payload", "{}")
        file.writeText(row.toString())
        assertTrue(store.hasAuthority)
        rejects { RuntimeHandoffStore(root).readCurrent() }
    }

    @Test fun failedCandidateKeepsReadyIdentityForEngineAbsentRecovery() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        val store = RuntimeHandoffStore(root)
        val applied = store.commit(handoff(), 0, null)
        store.requestStart(applied.revision, applied.intentEpoch)
        store.commitApplied(applied.revision, applied.intentEpoch, RuntimeAppliedProof(1, 1, "7"))
        val candidate = store.commit(handoff("rules: ['MATCH,REJECT']\n"), applied.intentEpoch, applied.revision)
        store.requestStart(candidate.revision, candidate.intentEpoch)
        val recovered = RuntimeHandoffStore(root).restoreLastApplied(candidate.intentEpoch)
        assertEquals(applied.revision, recovered.revision)
        assertTrue(recovered.intentEpoch > candidate.intentEpoch)
        assertEquals(applied.handoff.compiledYaml, RuntimeHandoffStore(root).admit(recovered.revision, recovered.intentEpoch).handoff.compiledYaml)
        rejects { store.commitApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(2, 2, "8")) }
    }

    @Test fun missingManagedStateCannotReenableLegacyAuthority() = runBlocking {
        val root = Files.createTempDirectory("runtime-handoff").toFile()
        RuntimeHandoffStore(root).commit(handoff(), 0, null)
        assertTrue(File(root, "runtime-handoffs/current.json").delete())
        val reopened = RuntimeHandoffStore(root)
        assertTrue(reopened.hasAuthority)
        rejects { reopened.readCurrent() }
    }

    private val readyProof = RuntimeAppliedProof(11, 12, "13")
    private fun identity(snapshot: MeowRuntimeSnapshot) = RuntimeAppliedIdentity(snapshot.revision, snapshot.intentEpoch,
        snapshot.handoff.compiledSha256, readyProof, snapshot.handoff.setupRecordJson)
    private fun nextRecord(snapshot: MeowRuntimeSnapshot, digest: String = "updated-build-digest") =
        JSONObject(snapshot.handoff.setupRecordJson).put("digest", digest).toString()
    private suspend fun ready(store: RuntimeHandoffStore, snapshot: MeowRuntimeSnapshot): MeowRuntimeSnapshot {
        store.requestStart(snapshot.revision, snapshot.intentEpoch)
        return store.commitApplied(snapshot.revision, snapshot.intentEpoch, readyProof)
    }

    @Test fun metadataRefreshPersistsOnlyRecordAndKeepsSealedContentAndReadyProof() = runBlocking<Unit> {
        val root = Files.createTempDirectory("runtime-refresh").toFile()
        val source = File(root, "rules.yaml").apply { writeText("sealed-rules") }
        val base = handoff(resources = listOf(RuntimeHandoffResource("rules.yaml", source.path, runtimeSha256(source.readBytes()))))
        val setup = JSONObject().put("test-url", "https://example.test/probe").put("selected-map", JSONObject().put("SELECT", "DIRECT"))
            .put("skip-cert-verify", false).put("applied", JSONObject(base.setupRecordJson))
        val runtime = JSONObject(base.runtimeSettingsJson).put("flutterSetup", setup)
        val input = RuntimeHandoff(base.coreSha, base.sourceId, base.sourceRevision, base.sourceSha256, base.compiledYaml,
            base.compiledSha256, base.setupRecordJson, runtime.toString(), base.vpnOptionsJson, base.notificationJson, base.resources)
        val store = RuntimeHandoffStore(root)
        val before = ready(store, store.commit(input, 0, null))
        val sealedFile = File(before.resourceDirectory, "rules.yaml")
        val sealedBytes = sealedFile.readBytes(); val modified = sealedFile.lastModified()
        val record = nextRecord(before)
        val after = store.refreshAppliedMetadata(identity(before), record)
        val reopened = RuntimeHandoffStore(root)
        val persisted = reopened.readLastApplied()!!
        assertEquals(before.revision, persisted.revision); assertEquals(before.intentEpoch, persisted.intentEpoch)
        assertEquals(before.handoff.compiledYaml, persisted.handoff.compiledYaml)
        assertEquals(before.handoff.compiledSha256, persisted.handoff.compiledSha256)
        assertEquals(before.handoff.resources, persisted.handoff.resources)
        assertEquals(before.resourceDirectory, persisted.resourceDirectory)
        assertArrayEquals(sealedBytes, sealedFile.readBytes()); assertEquals(modified, sealedFile.lastModified())
        assertTrue(meowJsonEqual(JSONObject(record), JSONObject(persisted.handoff.setupRecordJson)))
        val mirrored = JSONObject(persisted.handoff.runtimeSettingsJson).getJSONObject("flutterSetup")
        assertTrue(meowJsonEqual(JSONObject(record), mirrored.getJSONObject("applied")))
        assertEquals(setup.getString("test-url"), mirrored.getString("test-url"))
        assertEquals(before.handoff.vpnOptionsJson, after.handoff.vpnOptionsJson)
        assertEquals(before.handoff.notificationJson, after.handoff.notificationJson)
        // The original ready proof still identifies the same run after metadata replacement.
        reopened.refreshAppliedMetadata(identity(persisted), nextRecord(persisted, "second-digest"))
    }

    @Test fun metadataRefreshRejectsEveryMismatchedAppliedIdentityWithoutWriting() = runBlocking {
        val root = Files.createTempDirectory("runtime-refresh").toFile()
        val store = RuntimeHandoffStore(root)
        val snapshot = ready(store, store.commit(handoff(), 0, null))
        val expected = identity(snapshot)
        val wrong = listOf(expected.copy(revision = java.util.UUID.randomUUID().toString()),
            expected.copy(intentEpoch = expected.intentEpoch + 1), expected.copy(compiledSha256 = "0".repeat(64)),
            expected.copy(proof = readyProof.copy(serviceOwner = 99)), expected.copy(proof = readyProof.copy(sessionGeneration = 99)),
            expected.copy(proof = readyProof.copy(coreEpoch = "99")), expected.copy(expectedSetupRecordJson = nextRecord(snapshot, "stale")))
        val state = File(root, "runtime-handoffs/current.json"); val bytes = state.readBytes()
        wrong.forEach { rejects { store.refreshAppliedMetadata(it, nextRecord(snapshot)) }; assertArrayEquals(bytes, state.readBytes()) }
    }

    @Test fun metadataRefreshRejectsStopNewIntentAndNewCandidateIncludingCandidateMasquerade() = runBlocking {
        for (change in 0..2) {
            val root = Files.createTempDirectory("runtime-refresh").toFile()
            val store = RuntimeHandoffStore(root)
            val snapshot = ready(store, store.commit(handoff(), 0, null))
            val expected = identity(snapshot)
            when (change) {
                0 -> store.invalidate(snapshot.intentEpoch)
                1 -> store.restoreLastApplied(snapshot.intentEpoch)
                2 -> {
                    val candidate = store.commit(handoff(), snapshot.intentEpoch, snapshot.revision)
                    store.requestStart(candidate.revision, candidate.intentEpoch)
                    rejects { store.refreshAppliedMetadata(identity(candidate), nextRecord(candidate)) }
                }
            }
            val state = File(root, "runtime-handoffs/current.json"); val bytes = state.readBytes()
            rejects { RuntimeHandoffStore(root).refreshAppliedMetadata(expected, nextRecord(snapshot)) }
            assertArrayEquals(bytes, state.readBytes())
            assertEquals(snapshot.handoff.setupRecordJson, store.readLastApplied()!!.handoff.setupRecordJson)
        }
    }

    @Test fun competingMetadataRefreshesUseOldRecordCasAndRejectLateLoser() = runBlocking {
        val root = Files.createTempDirectory("runtime-refresh").toFile()
        val store = RuntimeHandoffStore(root)
        val snapshot = ready(store, store.commit(handoff(), 0, null))
        val expected = identity(snapshot)
        val attempts = listOf("refresh-a", "refresh-b").map { digest -> async(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { RuntimeHandoffStore(root).refreshAppliedMetadata(expected, nextRecord(snapshot, digest)) }
        } }.map { it.await() }
        assertEquals(1, attempts.count { it.isSuccess })
        assertEquals(1, attempts.count { it.isFailure })
        val winner = attempts.single { it.isSuccess }.getOrThrow()
        assertEquals(winner.handoff.setupRecordJson, store.readLastApplied()!!.handoff.setupRecordJson)
        rejects { store.refreshAppliedMetadata(expected, nextRecord(snapshot, "late")) }
    }

    @Test fun metadataRefreshRejectsDifferentYamlIdentityInvalidRecordAndTamperedResource() = runBlocking {
        val root = Files.createTempDirectory("runtime-refresh").toFile()
        val asset = File(root, "asset").apply { writeText("sealed") }
        val store = RuntimeHandoffStore(root)
        val snapshot = ready(store, store.commit(handoff(resources = listOf(RuntimeHandoffResource("asset", asset.path,
            runtimeSha256(asset.readBytes())))), 0, null))
        val record = JSONObject(nextRecord(snapshot))
        val bad = listOf(JSONObject(record.toString()).put("yaml-md5", "c".repeat(32)),
            JSONObject(record.toString()).put("groups", "1"), JSONObject(record.toString()).put("rules", -1),
            JSONObject(record.toString()).put("proxies", 1.5), JSONObject(record.toString()).put("extra", "value"))
        val state = File(root, "runtime-handoffs/current.json"); val bytes = state.readBytes()
        bad.forEach { rejects { store.refreshAppliedMetadata(identity(snapshot), it.toString()) }; assertArrayEquals(bytes, state.readBytes()) }
        File(snapshot.resourceDirectory, "asset").writeText("tampered")
        rejects { store.refreshAppliedMetadata(identity(snapshot), record.toString()) }
        assertArrayEquals(bytes, state.readBytes())
    }

    @Test fun appliedIdentityIsAbsentForCandidateAndRemainsHistoricalAfterStopAndNewCandidate() = runBlocking {
        val root = Files.createTempDirectory("applied-identity").toFile()
        val store = RuntimeHandoffStore(root)
        assertNull(store.readAppliedIdentity())
        val first = store.commit(handoff(), 0, null)
        store.requestStart(first.revision, first.intentEpoch)
        assertNull(store.readAppliedIdentity())
        ready(store, first)
        val actual = RuntimeHandoffStore(root).readAppliedIdentity()!!
        assertEquals(identity(first), actual)
        val stoppedEpoch = store.invalidate(first.intentEpoch)
        assertEquals(actual, RuntimeHandoffStore(root).readAppliedIdentity())
        assertFalse(store.isCurrent(actual.revision, actual.intentEpoch))
        val candidate = store.commit(handoff("rules: ['MATCH,REJECT']\n"), stoppedEpoch, first.revision)
        store.requestStart(candidate.revision, candidate.intentEpoch)
        assertEquals(actual, RuntimeHandoffStore(root).readAppliedIdentity())
        val newProof = RuntimeAppliedProof(21, 22, "23")
        store.commitApplied(candidate.revision, candidate.intentEpoch, newProof)
        val next = RuntimeHandoffStore(root).readAppliedIdentity()!!
        assertEquals(candidate.revision, next.revision); assertEquals(candidate.intentEpoch, next.intentEpoch)
        assertEquals(candidate.handoff.compiledSha256, next.compiledSha256)
        assertEquals(newProof, next.proof)
        assertEquals(candidate.handoff.setupRecordJson, next.expectedSetupRecordJson)
    }

    @Test fun concurrentAppliedIdentityReadersNeverCombineDifferentReadyReceipts() = runBlocking {
        val root = Files.createTempDirectory("applied-identity").toFile()
        val store = RuntimeHandoffStore(root)
        val first = ready(store, store.commit(handoff(), 0, null))
        val oldIdentity = store.readAppliedIdentity()!!
        val second = store.commit(handoff("rules: ['MATCH,REJECT']\n"), first.intentEpoch, first.revision)
        store.requestStart(second.revision, second.intentEpoch)
        val secondProof = RuntimeAppliedProof(31, 32, "33")
        val newIdentity = RuntimeAppliedIdentity(second.revision, second.intentEpoch, second.handoff.compiledSha256,
            secondProof, second.handoff.setupRecordJson)
        val start = kotlinx.coroutines.CompletableDeferred<Unit>()
        val readers = List(2) { async(kotlinx.coroutines.Dispatchers.Default) {
            start.await()
            List(12) { RuntimeHandoffStore(root).readAppliedIdentity()!! }
        } }
        val writer = async(kotlinx.coroutines.Dispatchers.Default) {
            start.await(); RuntimeHandoffStore(root).commitApplied(second.revision, second.intentEpoch, secondProof)
        }
        start.complete(Unit)
        readers.flatMap { it.await() }.forEach { assertTrue(it == oldIdentity || it == newIdentity) }
        writer.await()
        assertEquals(newIdentity, store.readAppliedIdentity())
    }

    @Test fun appliedIdentityReadsMetadataRefreshRecordAtomicallyWithOriginalProof() = runBlocking {
        val root = Files.createTempDirectory("applied-identity").toFile()
        val store = RuntimeHandoffStore(root)
        val snapshot = ready(store, store.commit(handoff(), 0, null))
        val expected = store.readAppliedIdentity()!!
        val updated = nextRecord(snapshot)
        store.refreshAppliedMetadata(expected, updated)
        assertEquals(expected.copy(expectedSetupRecordJson = updated), RuntimeHandoffStore(root).readAppliedIdentity())
    }

    @Test fun appliedIdentityRejectsCorruptPersistedProofAndCompiledHash() = runBlocking {
        val root = Files.createTempDirectory("applied-identity").toFile()
        val store = RuntimeHandoffStore(root)
        ready(store, store.commit(handoff(), 0, null))
        val file = File(root, "runtime-handoffs/current.json"); val original = file.readText()
        val invalid: List<(JSONObject) -> Unit> = listOf(
            { row -> row.getJSONObject("appliedProof").put("serviceOwner", 0) },
            { row -> row.getJSONObject("appliedProof").put("sessionGeneration", -1) },
            { row -> row.getJSONObject("appliedProof").put("coreEpoch", "013") },
            { row -> row.getJSONObject("lastApplied").getJSONObject("handoff").put("compiledSha256", "0".repeat(64)) },
        )
        invalid.forEach { mutate ->
            val envelope = JSONObject(original)
            val payload = JSONObject(envelope.getString("payload")).also(mutate).toString()
            file.writeText(envelope.put("payload", payload).put("sha256", runtimeSha256(payload.toByteArray())).toString())
            rejects { RuntimeHandoffStore(root).readAppliedIdentity() }
        }
    }

    @Test fun eachEngineStartGetsNewDurableEpochAndOldCancellationCannotWithdrawSuccessor() = runBlocking {
        val root = Files.createTempDirectory("start-epoch").toFile()
        val a = RuntimeHandoffStore(root); val b = RuntimeHandoffStore(root)
        val snapshot = a.commit(handoff(), 0, null)
        val first = a.beginStart(snapshot.revision, snapshot.intentEpoch)
        assertEquals(2L, first.intentEpoch)
        val second = b.beginStart(snapshot.revision, first.intentEpoch)
        assertEquals(3L, second.intentEpoch)
        rejects { a.invalidate(first.intentEpoch) }
        rejects { a.admit(first.revision, first.intentEpoch) }
        val actual = RuntimeHandoffStore(root).admit(second.revision, second.intentEpoch)
        assertTrue(actual.userRequested)
        assertEquals(snapshot.revision, actual.revision)
        assertEquals(snapshot.handoff.compiledYaml, actual.handoff.compiledYaml)
        assertEquals(snapshot.resourceDirectory, actual.resourceDirectory)
        assertNull(a.readAppliedIdentity())
    }

    @Test fun beginStartPreservesHistoricalAppliedProofAndDoesNotChangeLegacyRequestStart() = runBlocking {
        val root = Files.createTempDirectory("start-epoch").toFile()
        val store = RuntimeHandoffStore(root)
        val applied = ready(store, store.commit(handoff(), 0, null))
        val oldIdentity = store.readAppliedIdentity()!!
        val next = store.beginStart(applied.revision, applied.intentEpoch)
        assertEquals(applied.intentEpoch + 1, next.intentEpoch)
        assertEquals(oldIdentity, RuntimeHandoffStore(root).readAppliedIdentity())
        rejects { store.refreshAppliedMetadata(oldIdentity, nextRecord(applied)) }
        val legacy = store.requestStart(next.revision, next.intentEpoch)
        assertEquals(next.intentEpoch, legacy.intentEpoch)
        assertEquals(oldIdentity, store.readAppliedIdentity())
    }

    @Test fun concurrentBeginAndStopHaveOnlyOneSuccessfulCasSequence() = runBlocking {
        val root = Files.createTempDirectory("start-epoch").toFile()
        val store = RuntimeHandoffStore(root)
        val applied = ready(store, store.commit(handoff(), 0, null))
        val oldIdentity = store.readAppliedIdentity()
        val start = kotlinx.coroutines.CompletableDeferred<Unit>()
        val begin = async(kotlinx.coroutines.Dispatchers.Default) {
            start.await(); runCatching { RuntimeHandoffStore(root).beginStart(applied.revision, applied.intentEpoch) }
        }
        val stop = async(kotlinx.coroutines.Dispatchers.Default) {
            start.await(); runCatching { RuntimeHandoffStore(root).invalidate(applied.intentEpoch) }
        }
        start.complete(Unit)
        val begun = begin.await(); val stopped = stop.await()
        assertTrue(begun.isSuccess xor stopped.isSuccess)
        val persisted = RuntimeHandoffStore(root).readCurrent()!!
        assertEquals(applied.intentEpoch + 1, persisted.intentEpoch)
        assertEquals(begun.isSuccess, persisted.userRequested)
        assertEquals(oldIdentity, store.readAppliedIdentity())
        rejects { store.beginStart(applied.revision, applied.intentEpoch) }
    }

    @Test fun liveProjectionCommitsOnlyAfterOwnedReadbackAndPreservesCompiledIdentity() = runBlocking {
        val root = Files.createTempDirectory("live-projection").toFile()
        val store = RuntimeHandoffStore(root)
        val applied = ready(store, store.commit(handoff(), 0, null))
        val expected = store.readAppliedIdentity()!!
        val old = applied.handoff
        val runtime = JSONObject(old.runtimeSettingsJson).put("mode", "Global").put("flutterDefaultTestUrl", "https://probe.test/check")
        val replacement = RuntimeHandoff(old.coreSha, old.sourceId, old.sourceRevision, old.sourceSha256, old.compiledYaml,
            old.compiledSha256, old.setupRecordJson, runtime.toString(), old.vpnOptionsJson, old.notificationJson, old.resources)
        val nextProof = readyProof.copy(coreEpoch = "14")
        val identity = store.commitRuntimeProjection(expected, replacement, nextProof)
        assertEquals(expected.copy(proof = nextProof), identity)
        val readback = RuntimeHandoffStore(root).readLastApplied()!!
        assertEquals(applied.revision, readback.revision); assertEquals(applied.intentEpoch, readback.intentEpoch)
        assertEquals(applied.resourceDirectory, readback.resourceDirectory)
        assertEquals(old.compiledYaml, readback.handoff.compiledYaml)
        assertEquals("Global", JSONObject(readback.handoff.runtimeSettingsJson).getString("mode"))
        assertEquals(identity, store.readAppliedIdentity())
        rejects { store.commitRuntimeProjection(expected, replacement, nextProof) }
    }

    @Test fun liveProjectionRejectsSourceMutationForeignProofAndLateStop() = runBlocking {
        val root = Files.createTempDirectory("live-projection").toFile()
        val store = RuntimeHandoffStore(root)
        val applied = ready(store, store.commit(handoff(), 0, null))
        val expected = store.readAppliedIdentity()!!
        val state = File(root, "runtime-handoffs/current.json"); val bytes = state.readBytes()
        rejects { store.commitRuntimeProjection(expected, handoff("rules: ['MATCH,REJECT']\n"), readyProof) }
        rejects { store.commitRuntimeProjection(expected, applied.handoff, readyProof.copy(serviceOwner = 90)) }
        rejects { store.commitRuntimeProjection(expected, applied.handoff, readyProof.copy(coreEpoch = "12")) }
        assertArrayEquals(bytes, state.readBytes())
        store.invalidate(applied.intentEpoch)
        rejects { store.commitRuntimeProjection(expected, applied.handoff, readyProof.copy(coreEpoch = "14")) }
    }

    @Test fun startupProjectionCannotRebrandCompiledSourceAndKeepsOldAppliedUntilVerifiedCommit() = runBlocking {
        val root = Files.createTempDirectory("startup-projection").toFile()
        val store = RuntimeHandoffStore(root)
        val first = ready(store, store.commit(handoff(), 0, null))
        val candidate = store.commit(handoff(), first.intentEpoch, first.revision)
        store.requestStart(candidate.revision, candidate.intentEpoch)
        val proof = readyProof.copy(sessionGeneration = 14, coreEpoch = "15")
        rejects { store.commitAppliedWithProjection(candidate.revision, candidate.intentEpoch, proof, handoff("rules: ['MATCH,REJECT']\n")) }
        assertEquals(first.revision, store.readLastApplied()!!.revision)
        val old = candidate.handoff
        val runtime = JSONObject(old.runtimeSettingsJson).put("flutterDefaultTestUrl", "https://verified.test/check")
        val projection = RuntimeHandoff(old.coreSha, old.sourceId, old.sourceRevision, old.sourceSha256, old.compiledYaml,
            old.compiledSha256, old.setupRecordJson, runtime.toString(), old.vpnOptionsJson, old.notificationJson, old.resources)
        store.commitAppliedWithProjection(candidate.revision, candidate.intentEpoch, proof, projection)
        val actual = RuntimeHandoffStore(root).readLastApplied()!!
        assertEquals(candidate.revision, actual.revision)
        assertEquals(proof, store.readAppliedIdentity()!!.proof)
        assertEquals("https://verified.test/check", JSONObject(actual.handoff.runtimeSettingsJson).getString("flutterDefaultTestUrl"))
    }
}
