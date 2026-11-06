package dev.aerove.lite.flutter

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeHandoffStoreTest {
    @Test fun trustedPrivateRootAliasRelocatesResourcesAndKeepsTheStoreAfterTheRuntimeCloses() = runBlocking<Unit> {
        val root = Files.createTempDirectory("runtime-trusted-alias").toRealPath().toFile()
        val alias = File(root, "system-root-alias").toPath()
        Files.createSymbolicLink(alias, root.toPath())
        try {
            val source = File(root, "rules.yaml").apply { writeText("payload: [example.com]\n") }
            val row = RuntimeHandoffResource("assets/rules.yaml", source.path, runtimeSha256(source.readBytes()))
            val store = RuntimeHandoffStore(root)
            val snapshot = store.commit(handoff(resources = listOf(row)), 0, null)
            val lease = dev.aerove.lite.data.FlutterRuntimeResources.create(alias.toFile(), snapshot) { true }
            assertTrue(lease.readRoot.toPath().startsWith(File(root, "runtime/meow-read").toPath()))
            assertEquals(source.readText(), File(lease.readRoot, row.relativePath).readText())
            lease.closeAfterNativeJoin()
            assertFalse(lease.readRoot.exists())
            assertEquals(snapshot.revision, store.readCurrent()!!.revision)
            assertEquals(source.readText(), File(snapshot.resourceDirectory, row.relativePath).readText())
        } finally { Files.deleteIfExists(alias) }
    }

    @Test fun trustedPrivateRootAliasStillRejectsARedirectedRuntimeParent() = runBlocking<Unit> {
        val root = Files.createTempDirectory("runtime-alias-redirect").toRealPath().toFile()
        val alias = File(root, "system-root-alias").toPath()
        val external = Files.createTempDirectory("runtime-alias-outside").toRealPath()
        val parent = File(root, "runtime/meow-read").apply { parentFile.mkdirs() }.toPath()
        Files.createSymbolicLink(alias, root.toPath())
        Files.createSymbolicLink(parent, external)
        try {
            val store = RuntimeHandoffStore(root)
            val snapshot = store.commit(handoff(), 0, null)
            val failure = runCatching { dev.aerove.lite.data.FlutterRuntimeResources.create(alias.toFile(), snapshot) { true } }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("Redirected runtime resource parent", failure?.message)
            assertTrue(external.toFile().listFiles().orEmpty().isEmpty())
            assertEquals(snapshot.revision, store.readCurrent()!!.revision)
        } finally {
            Files.deleteIfExists(parent)
            Files.deleteIfExists(alias)
        }
    }

    @Test fun sourcePermitIsNotBorrowedByAnIndependentOrdinaryStartAndRollbackIsBoundToOneSnapshotAndEpoch() = runBlocking<Unit> {
        val root = Files.createTempDirectory("provider-source-permit").toFile(); val store = RuntimeHandoffStore(root)
        val business = JSONObject().put("source", "same")
        fun projection(marker: JSONObject? = null): RuntimeHandoff {
            val base = handoff(); val metadata = JSONObject(base.runtimeSettingsJson).put("flutterSetup", JSONObject().put("data-source-proof", business))
            marker?.let { metadata.put("providerSourceTransaction", it) }
            return RuntimeHandoff(base.coreSha, base.sourceId, base.sourceRevision, base.sourceSha256, base.compiledYaml, base.compiledSha256,
                base.setupRecordJson, metadata.toString(), base.vpnOptionsJson, base.notificationJson, base.resources)
        }
        suspend fun begin(): Pair<String, JSONObject> {
            val expected = store.readAppliedIdentity()!!; val id = java.util.UUID.randomUUID().toString()
            val plan = JSONObject().put("ownerSha256", "a".repeat(64)).put("namespace", "proxy-providers").put("name", "feed")
                .put("expectedRevision", expected.revision).put("expected", JSONObject(expected.expectedSetupRecordJson)).put("sourcePlanSha256", "b".repeat(64))
            store.beginProviderSourceTransaction(id, plan, JSONObject().put("relativePath", "provider-source-transactions/$id/source-journal.json").put("sha256", "c".repeat(64)), expected)
            return id to plan
        }
        val initial = store.commit(projection(), 0, null)
        store.requestStart(initial.revision, initial.intentEpoch)
        store.commitApplied(initial.revision, initial.intentEpoch, RuntimeAppliedProof(1, 1, "1"))
        val (id, plan) = begin(); val permit = store.providerSourceApplyPermit(id, nativeLiteJsonHash(plan))
        assertTrue(store.providerSourceLeaseAllows(initial, null))
        assertFalse(store.providerSourceAllowsAdmission(initial))
        val admitted = CompletableDeferred<MeowRuntimeSnapshot>(); val release = CompletableDeferred<Unit>()
        val source = async {
            val marker = JSONObject().put("transactionId", id).put("planSha256", nativeLiteJsonHash(plan)).put("afterStateSha256", nativeLiteJsonHash(business))
            val candidate = store.commitForSetup(projection(marker), initial.intentEpoch, initial.revision, { true }, {}, permit)
            admitted.complete(candidate); release.await()
            assertSame(permit, store.admit(candidate.revision, candidate.intentEpoch, candidate.sourcePermit).sourcePermit)
            store.commitApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(2, 2, "2"))
        }
        val candidate = withTimeout(5000) { admitted.await() }
        try {
            val ordinary = async {
                val epoch = store.currentEpoch()
                rejects { store.beginStart(candidate.revision, candidate.intentEpoch) }
                rejects { store.requestStart(candidate.revision, candidate.intentEpoch) }
                rejects { store.admit(candidate.revision, candidate.intentEpoch) }
                rejects { store.restoreLastApplied(candidate.intentEpoch) }
                assertEquals(epoch, store.currentEpoch())
                assertNull(store.readCurrent()!!.sourcePermit); assertNull(store.peekCurrent()!!.sourcePermit)
                val independentStore = RuntimeHandoffStore(root)
                assertFalse(independentStore.providerSourceAllowsAdmission(independentStore.readCurrent()!!, permit))
                val copied = dev.aerove.lite.vpn.VpnPlan(candidate.profileId, dev.aerove.lite.LiteSettings()).withProviderSourcePermit(permit)
                assertNull(copied.copy(runtimeSessionGeneration = 17).providerSourcePermit)
            }
            withTimeout(5000) { ordinary.await() }
        } finally { release.complete(Unit); withTimeout(5000) { source.await() } }
        rejects { store.admit(candidate.revision, candidate.intentEpoch, permit) }
        assertNull(store.admit(candidate.revision, candidate.intentEpoch).sourcePermit)
        store.finalizeProviderSourceTransaction(id, nativeLiteJsonHash(plan))
        val normal = store.beginStart(candidate.revision, candidate.intentEpoch)
        assertNull(normal.sourcePermit)
        store.commitApplied(normal.revision, normal.intentEpoch, RuntimeAppliedProof(3, 3, "3"))
        val (rollbackId, rollbackPlan) = begin()
        val wrongPhase = store.providerSourceApplyPermit(rollbackId, nativeLiteJsonHash(rollbackPlan))
        store.failProviderSourceTransaction(rollbackId, quiesced = true)
        val rollback = store.stageProviderSourceRollback(rollbackId, business)
        val rollbackPermit = checkNotNull(rollback.sourcePermit)
        val wrong = MeowRuntimeSnapshot(java.util.UUID.randomUUID().toString(), rollback.intentEpoch, true, rollback.handoff, rollback.resourceDirectory)
        assertFalse(store.providerSourceAllowsAdmission(wrong, rollbackPermit))
        assertFalse(store.providerSourceAllowsAdmission(rollback, wrongPhase))
        assertFalse(store.providerSourceAllowsAdmission(rollback, permit))
        rejects { store.admit(rollback.revision, rollback.intentEpoch, permit) }
        rejects { store.beginStart(rollback.revision, rollback.intentEpoch) }
        assertEquals(rollback.intentEpoch, store.currentEpoch())
        val restarted = store.beginStart(rollback.revision, rollback.intentEpoch, rollbackPermit)
        assertFalse(store.providerSourceAllowsAdmission(rollback, rollbackPermit))
        rejects { store.admit(restarted.revision, restarted.intentEpoch, rollbackPermit) }
        assertNotSame(rollbackPermit, restarted.sourcePermit)
        assertSame(restarted.sourcePermit, store.admit(restarted.revision, restarted.intentEpoch, restarted.sourcePermit).sourcePermit)
    }

    @Test fun providerSourceDecisionSurvivesReloadAndStopPreventsALateCandidateCommit() = runBlocking<Unit> {
        val root = Files.createTempDirectory("provider-source-decision").toFile()
        fun projection(proof: JSONObject, marker: JSONObject? = null): RuntimeHandoff {
            val base = handoff()
            val metadata = JSONObject(base.runtimeSettingsJson).put("flutterSetup", JSONObject().put("data-source-proof", proof))
            marker?.let { metadata.put("providerSourceTransaction", it) }
            return RuntimeHandoff(base.coreSha, base.sourceId, base.sourceRevision, base.sourceSha256, base.compiledYaml, base.compiledSha256,
                base.setupRecordJson, metadata.toString(), base.vpnOptionsJson, base.notificationJson, base.resources)
        }
        fun plan(expected: RuntimeAppliedIdentity) = JSONObject().put("ownerSha256", "a".repeat(64)).put("namespace", "proxy-providers")
            .put("name", "feed").put("expectedRevision", expected.revision).put("expected", JSONObject(expected.expectedSetupRecordJson)).put("sourcePlanSha256", "b".repeat(64))
        val oldProof = JSONObject().put("source", "old")
        val nextProof = JSONObject().put("source", "new")
        val store = RuntimeHandoffStore(root)
        val old = store.commit(projection(oldProof), 0, null)
        store.commitGraphApplied(old.revision, old.intentEpoch, RuntimeAppliedProof(1, 1, "1"), old.handoff)
        val id = java.util.UUID.randomUUID().toString(); val initialIdentity = store.readAppliedIdentity()!!
        val sourcePlan = plan(initialIdentity)
        val journal = JSONObject().put("relativePath", "provider-source-transactions/$id/source-journal.json").put("sha256", "c".repeat(64))
        val pending = store.beginProviderSourceTransaction(id, sourcePlan, journal, initialIdentity)
        assertEquals("PREPARED", pending.getString("state")); assertTrue(old.resourceDirectory.isDirectory)
        rejects { store.commit(projection(nextProof), old.intentEpoch, old.revision) }
        val marker = JSONObject().put("transactionId", id).put("planSha256", nativeLiteJsonHash(sourcePlan)).put("afterStateSha256", nativeLiteJsonHash(nextProof))
        val permit = store.providerSourceApplyPermit(id, nativeLiteJsonHash(sourcePlan))
        val candidate = store.commitForSetup(projection(nextProof, marker), old.intentEpoch, old.revision, { false }, {}, permit)
        assertEquals(pending.getString("plannedRevision"), candidate.revision)
        val beforeDecision = RuntimeHandoffStore(root)
        assertEquals("UNDECIDED", beforeDecision.readProviderSourceTransaction(id)!!.getString("decision"))
        assertEquals(initialIdentity, beforeDecision.readAppliedIdentity())
        beforeDecision.commitGraphApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(2, 1, "2"), candidate.handoff)
        val afterDecision = RuntimeHandoffStore(root)
        assertEquals("COMMITTED", afterDecision.readProviderSourceTransaction(id)!!.getString("decision"))
        afterDecision.failProviderSourceTransaction(id)
        assertEquals("COMMITTED", afterDecision.readProviderSourceTransaction(id)!!.getString("decision"))
        afterDecision.finalizeProviderSourceTransaction(id, nativeLiteJsonHash(sourcePlan))
        assertFalse(old.resourceDirectory.exists()); assertTrue(candidate.resourceDirectory.isDirectory)
        val currentIdentity = afterDecision.readAppliedIdentity()!!
        val stopId = java.util.UUID.randomUUID().toString(); val stopPlan = plan(currentIdentity)
        val stopJournal = JSONObject(journal.toString()).put("relativePath", "provider-source-transactions/$stopId/source-journal.json")
        afterDecision.beginProviderSourceTransaction(stopId, stopPlan, stopJournal, currentIdentity)
        afterDecision.invalidate()
        val recovering = RuntimeHandoffStore(root)
        assertTrue(recovering.readProviderSourceTransaction(stopId)!!.getBoolean("stopObserved"))
        recovering.failProviderSourceTransaction(stopId, quiesced = true)
        val stopped = recovering.stageProviderSourceRollback(stopId, nextProof)
        assertFalse(stopped.userRequested)
        assertFalse(recovering.providerSourceAllowsAdmission(stopped, stopped.sourcePermit))
        recovering.finishStoppedProviderSourceAbort(stopId)
        assertEquals("ABORTED_STOPPED", recovering.readProviderSourceTransaction(stopId)!!.getString("state"))
        assertNull(recovering.readAppliedIdentity())
        rejects { recovering.commitGraphApplied(candidate.revision, candidate.intentEpoch, RuntimeAppliedProof(9, 9, "9"), candidate.handoff) }
        assertEquals("ABORTED", RuntimeHandoffStore(root).readProviderSourceTransaction(stopId)!!.getString("decision"))
    }

    @Test fun flutterComposerRequiresLiveOwnedReadLeaseAndRejectsTamperedResourcesOrReceipt() = runBlocking<Unit> {
        val root = Files.createTempDirectory("flutter-composer-lease").toRealPath().toFile()
        val asset = File(root, "rules.yaml").apply { writeText("payload: [example.test]\n") }
        val yaml = "rule-providers: {r: {type: file, path: '${asset.path}', behavior: domain, format: yaml}}\nrules: ['RULE-SET,r,DIRECT']\n"
        val store = RuntimeHandoffStore(root)
        val snapshot = store.commit(handoff(yaml, listOf(RuntimeHandoffResource("assets/rules.yaml", asset.path, runtimeSha256(asset.readBytes())))), 0, null)
        val lease = dev.aerove.lite.data.FlutterRuntimeResources.create(root, snapshot) { true }
        val prepared = dev.aerove.lite.data.PreparedProfile(snapshot.profileId, lease.yaml, lease.yaml, snapshot.revision,
            snapshot.handoff.compiledSha256, lease.readRoot, null)
        val runtime = File(root, "runtime")
        val cache = File(runtime, "meow-cache/session").apply { mkdirs() }
        val composer = dev.aerove.lite.data.MeowRuntimeConfigComposer(runtime, cacheDirectory = cache)
        val settings = dev.aerove.lite.LiteSettings(network = dev.aerove.lite.networksettings.NetworkSettings(vpnEnabled = false))
        fun compose(profile: dev.aerove.lite.data.PreparedProfile = prepared) = composer.composeFlutter(profile, lease, settings,
            -1, 49152, "x".repeat(32), dev.aerove.lite.core.NativeCore.RuntimeMode.Proxy,
            dev.aerove.lite.data.SystemDnsSnapshot(), dev.aerove.lite.data.TunRuntimeOptions(1500, false, false), true, null, emptySet())
        val config = compose()
        assertEquals(snapshot.profileId, config.profileId); assertEquals(snapshot.revision, config.revision)
        assertEquals(lease.readRoot, config.resourceReadRoot); assertTrue(config.initiallyParked)
        assertTrue(config.engineYaml.contains("rules.yaml"))
        assertTrue(runCatching { composer.compose(prepared, settings, -1, 49152, "x".repeat(32), initiallyParked = true) }.isFailure)
        assertTrue(runCatching { compose(prepared.copy(generation = "foreign")) }.isFailure)
        assertTrue(runCatching { compose(prepared.copy(yaml = "rules: ['MATCH,REJECT']")) }.isFailure)
        val relocated = File(lease.readRoot, "assets/rules.yaml")
        val bytes = relocated.readBytes()
        relocated.writeText("payload: [changed.test]\n")
        assertTrue(runCatching { compose() }.isFailure)
        relocated.writeBytes(bytes)
        compose()
        val receipt = File(lease.readRoot, "relocation-receipt.json")
        val receiptBytes = receipt.readBytes()
        receipt.writeText(JSONObject(String(receiptBytes)).put("records", org.json.JSONArray()).toString())
        assertTrue(runCatching { compose() }.isFailure)
        receipt.writeBytes(receiptBytes)
        compose()
        lease.closeAfterNativeJoin()
        assertTrue(runCatching { compose() }.isFailure)
    }

    @Test fun parkedGraphReceiptDoesNotAuthorizeVpnAndCannotSurviveStopOrSuccessor() = runBlocking<Unit> {
        val store = RuntimeHandoffStore(Files.createTempDirectory("parked-graph").toFile())
        val candidate = store.commit(handoff(), 0, null)
        val proof = RuntimeAppliedProof(7, 2, "4")
        store.admitGraph(candidate.revision, candidate.intentEpoch)
        store.commitGraphApplied(candidate.revision, candidate.intentEpoch, proof, candidate.handoff)
        val identity = store.readAppliedIdentity()!!
        assertTrue(store.isGraphCurrent(identity))
        assertFalse(store.isCurrent(identity.revision, identity.intentEpoch))
        assertFalse(store.readCurrent()!!.userRequested)
        rejects { store.admit(candidate.revision, candidate.intentEpoch) }
        rejects { store.commitApplied(candidate.revision, candidate.intentEpoch, proof) }
        assertFalse(store.isGraphCurrent(identity.copy(proof = proof.copy(serviceOwner = 8))))
        store.invalidate(candidate.intentEpoch)
        assertFalse(store.isGraphCurrent(identity))
        rejects { store.commitGraphApplied(candidate.revision, candidate.intentEpoch, proof, candidate.handoff) }
        val stopped = store.readCurrent()!!
        store.commitGraphApplied(stopped.revision, stopped.intentEpoch, proof.copy(sessionGeneration = 3), stopped.handoff)
        val parked = store.readAppliedIdentity()!!
        store.beginStart(stopped.revision, stopped.intentEpoch)
        assertFalse(store.isGraphCurrent(parked))
        rejects { store.admitGraph(stopped.revision, stopped.intentEpoch) }
        assertEquals(parked, store.readAppliedIdentity())
    }

    @Test fun sessionRelocationRewritesNestedTlsPathsWithoutMutatingStoreOrNeedingProducerGeneration() = runBlocking<Unit> {
        val root = Files.createTempDirectory("flutter-relocation").toRealPath().toFile()
        val ca = File(root, "original-ca.pem").apply { writeText("certificate fixture bytes") }
        val payload = File(root, "original-provider.yaml").apply {
            writeText("proxies: [{name: private, type: socks5, server: 127.0.0.1, port: 1080, ca: '${ca.path.replace('\\', '/')}'}]\n")
        }
        val rows = listOf(RuntimeHandoffResource("assets/ca.pem", ca.path.replace('\\', '/'), runtimeSha256(ca.readBytes())),
            RuntimeHandoffResource("assets/proxies.yaml", payload.path.replace('\\', '/'), runtimeSha256(payload.readBytes())))
        // Use identical canonical spelling in payload/YAML and receipt, as the producer does.
        val yaml = "proxy-providers: {p: {type: file, path: '${rows[1].sourcePath}'}}\nrules: ['MATCH,DIRECT']\n"
        val store = RuntimeHandoffStore(root)
        val snapshot = store.commit(handoff(yaml, rows), 0, null)
        val storedPayload = File(snapshot.resourceDirectory, rows[1].relativePath).readBytes()
        assertTrue(ca.delete()); assertTrue(payload.delete())
        val lease = dev.aerove.lite.data.FlutterRuntimeResources.create(root, snapshot) { true }
        try {
            val provider = ((dev.aerove.lite.data.ConfigYaml.parse(lease.yaml)["proxy-providers"] as Map<*, *>)["p"] as Map<*, *>)["path"] as String
            assertTrue(File(provider).toPath().startsWith(lease.readRoot.toPath()))
            val nodes = dev.aerove.lite.data.ConfigYaml.parse(File(provider).readText())["proxies"] as List<*>
            val relocatedCa = (nodes.single() as Map<*, *>)["ca"] as String
            assertEquals(File(lease.readRoot, "assets/ca.pem").path, relocatedCa)
            assertEquals("certificate fixture bytes", File(relocatedCa).readText())
            assertArrayEquals(storedPayload, File(snapshot.resourceDirectory, rows[1].relativePath).readBytes())
            assertEquals(snapshot.handoff.compiledSha256, JSONObject(File(lease.readRoot, "relocation-receipt.json").readText()).getString("compiledSha256"))
        } finally { lease.closeAfterNativeJoin() }
        assertFalse(lease.readRoot.exists())
        var checks = 0
        rejects { dev.aerove.lite.data.FlutterRuntimeResources.create(root, snapshot) { ++checks < 2 } }
        assertTrue(File(root, "runtime/meow-read").listFiles().orEmpty().isEmpty())
        assertEquals(snapshot.revision, store.readCurrent()!!.revision)
    }

    @Test fun finalSourceGuardFailureRollsBackCopiedCandidateAndRetainsLastGood() = runBlocking<Unit> {
        val root = Files.createTempDirectory("handoff-final-source").toRealPath().toFile()
        val store = RuntimeHandoffStore(root)
        val old = store.commit(handoff(), 0, null)
        val resource = File(root, "resource.yaml").apply { writeText("payload: [example.com]\n") }
        val next = handoff(resources = listOf(RuntimeHandoffResource("assets/r.yaml", resource.path, runtimeSha256(resource.readBytes()))))
        rejects { store.commit(next, old.intentEpoch, old.revision) { error("source changed during Store copy") } }
        assertEquals(old.revision, store.readCurrent()!!.revision)
        assertEquals(listOf(old.resourceDirectory.name), File(root, "runtime-handoffs/resources").listFiles()!!.map { it.name })
    }

    @Test fun cancellationAfterCopyBeforePublishRetainsLastGoodAndRemovesCandidateFiles() = runBlocking<Unit> {
        val root = Files.createTempDirectory("handoff-copy-cancel").toRealPath().toFile()
        val store = RuntimeHandoffStore(root); val old = store.commit(handoff(), 0, null)
        val asset = File(root, "resource.yaml").apply { writeText("payload: [example.com]\n") }
        val next = handoff(resources = listOf(RuntimeHandoffResource("assets/r.yaml", asset.path, runtimeSha256(asset.readBytes()))))
        val writer = async {
            val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            store.commit(next, old.intentEpoch, old.revision) { owner.cancel(kotlinx.coroutines.CancellationException("cancel after verified copy")) }
        }
        try { writer.await(); fail("cancelled copy published") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(old.revision, store.readCurrent()!!.revision)
        assertEquals(listOf(old.resourceDirectory.name), File(root, "runtime-handoffs/resources").listFiles()!!.map { it.name })
    }

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
