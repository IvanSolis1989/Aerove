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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class MeowSetupProtocolTest {
    @Test fun sharedFileTransactionCommitsOnceAndRealAbortReappliesPriorGraphAfterFailureOrCancellation() = runBlocking<Unit> {
        val home = home(); val runtime = File(home, "runtime")
        val original = File(home, "profiles/providers/1/proxies/${"a".repeat(32)}").apply {
            parentFile.mkdirs(); writeText("proxies: [{name: original,type: direct}]\n")
        }
        val configFile = File(home, "config.yaml")
        fun source(mode: String) = "mode: $mode\nproxy-providers: {feed: {type: file,path: '${original.path.replace('\\', '/')}'}}\n" +
            "proxy-groups: [{name: Pick,type: select,use: [feed]}]\nrules: ['MATCH,Pick']\n"
        configFile.writeText(source("rule"))
        val entered = CompletableDeferred<Unit>(); val block = java.util.concurrent.atomic.AtomicBoolean()
        val prepared = NativePreparedConfigValidator { _, _ -> if (block.get()) { entered.complete(Unit); awaitCancellation() }; null }
        val schema = dev.aerove.lite.data.CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime, prepared) { emptySet() }
        val manager = dev.aerove.lite.data.ResourceBundleManager(runtime, schema, dev.aerove.lite.data.NativeResourceValidator { _, _, _, _ -> null })
        val store = RuntimeHandoffStore(home); val effects = Effects(); var applications = 0L; var joined = 0
        effects.onStaged = { snapshot ->
            val proof = RuntimeAppliedProof(1, ++applications, applications.toString())
            store.commitGraphApplied(snapshot.revision, snapshot.intentEpoch, proof, snapshot.handoff)
            effects.runtime = MeowSetupRuntime(snapshot.revision, snapshot.intentEpoch, snapshot.handoff.compiledSha256,
                proof.coreEpoch, proof.serviceOwner, proof.sessionGeneration, false)
        }
        config(home).use { config ->
            val owner = dev.aerove.lite.data.sha256("verified-profile-1")
            fun proof() = JSONObject().put("sourceHash", dev.aerove.lite.data.sha256(original))
            fun verify(proof: JSONObject): String { require(proof.getString("sourceHash") == dev.aerove.lite.data.sha256(original)); return owner }
            fun params(digest: String) = setup().apply {
                put("selected-map", emptyMap<String, String>()); put("data-source-proof", protocolObject(proof().toString())); put("applied", record(digest))
            }
            val runtimeApi = MeowSetupProtocol(home, store, config, effects, prepared, verifiedFeatures = { emptySet() },
                validator = NativeConfigValidator { null }, resourceSealer = dev.aerove.lite.data.FlutterResourceSealer(manager, "AEROVE"),
                providerOwnerSourceVerifier = ::verify)
            runtimeApi.syncState(shared())
            assertEquals("", runtimeApi.setupConfig(params("initial")))
            val transactions = MeowProviderSourceTransactions(home, store, ::verify,
                actualIdentity = { store.readAppliedIdentity()?.takeIf { effects.runtime?.revision == it.revision && store.isGraphCurrent(it) } },
                apply = { args, id, publication -> runtimeApi.applyProviderSource(args, id, publication); Unit },
                quiesce = { joined++; effects.runtime = null }, restore = { snapshot -> effects.onStaged(snapshot); store.readAppliedIdentity() })
            suspend fun raw(): Pair<JSONObject, File> {
                val current = store.readLastApplied()!!
                val rows = JSONObject(File(current.resourceDirectory, "manifest.json").readText()).getJSONArray("records")
                val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("kind") == "source-proxy" }
                return row to File(current.resourceDirectory, row.getString("file"))
            }
            suspend fun begin(after: String): Pair<MutableMap<String, Any?>, JSONObject> {
                val current = store.readLastApplied()!!; val id = java.util.UUID.randomUUID().toString()
                val sourcePlan = JSONObject().put("beforeStateSha256", nativeLiteJsonHash(proof())).put("afterSha256", dev.aerove.lite.data.sha256(after))
                val plan = JSONObject().put("ownerSha256", owner).put("namespace", "proxy-providers").put("name", "feed")
                    .put("expectedRevision", current.revision).put("expected", JSONObject(current.handoff.setupRecordJson)).put("sourcePlanSha256", nativeLiteJsonHash(sourcePlan))
                val journal = File(home, "provider-source-transactions/$id/source-journal.json").apply {
                    parentFile.mkdirs(); writeText(JSONObject().put("schema", 1).put("id", id).put("sourcePlan", sourcePlan).put("sourcePlanSha256", nativeLiteJsonHash(sourcePlan)).toString())
                }
                val descriptor = JSONObject().put("relativePath", "provider-source-transactions/$id/source-journal.json").put("sha256", dev.aerove.lite.data.sha256(journal))
                val response = transactions.invoke(mapOf("schemaVersion" to 1, "operation" to "begin", "transactionId" to id,
                    "plan" to protocolObject(plan.toString()), "sourceJournal" to protocolObject(descriptor.toString()), "proof" to protocolObject(proof().toString())))
                assertEquals("UNDECIDED", response["decision"])
                return mutableMapOf<String, Any?>("schemaVersion" to 1, "transactionId" to id, "planSha256" to nativeLiteJsonHash(plan)) to descriptor
            }
            fun applyRequest(base: Map<String, Any?>, descriptor: JSONObject, digest: String) = base + mapOf("operation" to "apply",
                "publication" to mapOf("sourceJournalSha256" to descriptor.getString("sha256"), "afterStateSha256" to nativeLiteJsonHash(proof())), "setupParams" to params(digest))
            val edited = "proxies: [{name: edited,type: direct}]\n"
            val (commit, commitJournal) = begin(edited)
            original.writeText(edited)
            val committed = transactions.invoke(applyRequest(commit, commitJournal, "edited"))
            assertEquals("COMMITTED", committed["decision"]); assertEquals(committed["plannedRevision"], committed["revision"])
            val lostReply = transactions.invoke(commit + ("operation" to "query"))
            assertEquals("COMMITTED", lostReply["decision"])
            assertEquals("COMMITTED", transactions.invoke(commit + ("operation" to "abort"))["decision"]); assertEquals(0, joined)
            transactions.invoke(commit + mapOf("operation" to "query", "sourceFinalized" to true))
            configFile.writeText(source("global"))
            assertEquals("", runtimeApi.setupConfig(params("unrelated")))
            assertEquals(edited, raw().second.readText()); assertFalse(raw().first.has("manualOverride"))
            for ((cancel, replacement) in listOf(false to "proxies: [{name: invalid,type: unknown}]\n", true to "proxies: [{name: cancelled,type: direct}]\n")) {
                val before = store.readAppliedIdentity()!!; val beforeProof = proof(); val beforeBytes = original.readText()
                val (pending, descriptor) = begin(replacement)
                original.writeText(replacement)
                if (cancel) {
                    block.set(true)
                    val request = async { transactions.invoke(applyRequest(pending, descriptor, "cancelled")) }
                    try { withTimeout(5000) { entered.await() } } finally { request.cancel(); withTimeout(5000) { request.join() }; block.set(false) }
                    assertTrue(request.isCancelled)
                } else {
                    try { transactions.invoke(applyRequest(pending, descriptor, "invalid")); fail("Invalid source was published") }
                    catch (_: dev.aerove.lite.data.ConfigValidationException) { }
                }
                assertEquals(before, store.readAppliedIdentity())
                assertEquals("restore-old", transactions.invoke(pending + ("operation" to "query"))["sourceAction"])
                original.writeText(beforeBytes)
                val restored = transactions.invoke(pending + mapOf("operation" to "abort", "restored" to mapOf(
                    "sourceJournalSha256" to descriptor.getString("sha256"), "beforeStateSha256" to nativeLiteJsonHash(beforeProof), "proof" to protocolObject(beforeProof.toString()))))
                assertEquals("ABORTED", restored["decision"]); assertEquals(before.revision, restored["revision"])
                assertNotEquals(before.proof, store.readAppliedIdentity()!!.proof)
                assertEquals(edited, raw().second.readText())
                transactions.invoke(pending + mapOf("operation" to "query", "sourceFinalized" to true))
            }
            assertEquals(5L, applications); assertEquals(4, joined)
        }
    }
    @Test fun providerCancellationJoinsTheActualCandidateWriterBeforeCleanupAndKeepsAppliedLeaseUntilRecovery() = runBlocking<Unit> {
        val home = home(); val runtime = File(home, "runtime")
        File(home, "config.yaml").writeText("proxy-providers: {feed: {type: http,url: 'https://example.invalid/feed',path: feed.yaml}}\n" +
            "proxy-groups: [{name: Pick,type: select,use: [feed]}]\nrules: ['MATCH,Pick']\n")
        val downloads = java.util.concurrent.atomic.AtomicInteger()
        val writerEntered = CompletableDeferred<Unit>(); val writerJoining = CompletableDeferred<Unit>(); val releaseWriter = CompletableDeferred<Unit>()
        val writerJoined = java.util.concurrent.atomic.AtomicBoolean()
        val prepareEntered = CompletableDeferred<Unit>(); val prepareCancelled = CompletableDeferred<Unit>()
        val blockPrepared = java.util.concurrent.atomic.AtomicBoolean()
        val transportsJoined = java.util.concurrent.atomic.AtomicInteger()
        var cancelledCandidate: File? = null
        val prepared = NativePreparedConfigValidator { _, _ ->
            if (blockPrepared.get()) {
                prepareEntered.complete(Unit)
                try { awaitCancellation() } finally { prepareCancelled.complete(Unit) }
            }
            null
        }
        val schema = dev.aerove.lite.data.CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime, prepared) { emptySet() }
        val factory = dev.aerove.lite.data.ProviderDownloadFactory { context ->
            val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            object : dev.aerove.lite.data.ProviderDownloadScope {
                private var writer: Job? = null
                override suspend fun download(request: dev.aerove.lite.data.ProviderDownloadRequest): dev.aerove.lite.data.ProviderDownloadResult {
                    val attempt = downloads.incrementAndGet()
                    if (attempt == 2) {
                        cancelledCandidate = context.stagingRoot.parentFile
                        writer = writers.launch {
                            request.destination.outputStream().use { output ->
                                output.write("proxies: [".toByteArray()); output.flush(); writerEntered.complete(Unit)
                                try { awaitCancellation() } finally {
                                    withContext(NonCancellable) { writerJoining.complete(Unit); releaseWriter.await() }
                                }
                            }
                        }
                        awaitCancellation()
                    }
                    val yaml = "proxies: [{name: ${if (attempt == 1) "old" else "new"},type: direct}]\n"
                    val digest = yaml.byteInputStream().use { dev.aerove.lite.data.copyResource(it, request.destination, request.maxBytes) }
                    return dev.aerove.lite.data.ProviderDownloadResult(digest.bytes, digest.sha256)
                }
                override suspend fun closeAndJoin() {
                    try { writer?.cancelAndJoin(); if (writer != null) writerJoined.set(true); transportsJoined.incrementAndGet() } finally { writers.cancel() }
                }
            }
        }
        val manager = dev.aerove.lite.data.ResourceBundleManager(runtime, schema, dev.aerove.lite.data.NativeResourceValidator { _, _, _, _ -> null },
            dev.aerove.lite.data.ResourceDownloader(), providerDownloads = factory)
        val store = RuntimeHandoffStore(home); val effects = Effects(); var applications = 0L
        effects.onStaged = { snapshot ->
            val proof = RuntimeAppliedProof(1, ++applications, applications.toString())
            store.commitGraphApplied(snapshot.revision, snapshot.intentEpoch, proof, snapshot.handoff)
            effects.runtime = MeowSetupRuntime(snapshot.revision, snapshot.intentEpoch, snapshot.handoff.compiledSha256,
                proof.coreEpoch, proof.serviceOwner, proof.sessionGeneration, false)
        }
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, effects, prepared, verifiedFeatures = { emptySet() },
                validator = NativeConfigValidator { null }, resourceSealer = dev.aerove.lite.data.FlutterResourceSealer(manager, "AEROVE"))
            api.syncState(shared())
            val initial = setup().apply { put("selected-map", emptyMap<String, String>()) }
            assertEquals("", api.setupConfig(initial))
            val oldIdentity = store.readAppliedIdentity()!!; val oldSnapshot = store.readLastApplied()!!; val oldRuntime = effects.runtime
            val oldFiles = oldSnapshot.handoff.resources.associate { it.relativePath to dev.aerove.lite.data.sha256(File(oldSnapshot.resourceDirectory, it.relativePath)) }
            val lease = dev.aerove.lite.data.FlutterRuntimeResources.create(home, oldSnapshot) { store.isGraphCandidate(oldSnapshot.revision, oldSnapshot.intentEpoch) }
            try {
                val leaseFiles = lease.readRoot.walkTopDown().filter(File::isFile).associate { it.relativeTo(lease.readRoot).path to dev.aerove.lite.data.sha256(it) }
                val appliedYaml = lease.yaml
                fun request() = initial.toMutableMap().apply {
                    put("applied", record("provider-refresh"))
                    put("provider-update", mapOf("namespace" to "proxy-providers", "name" to "feed", "expected" to record(), "expectedRevision" to oldIdentity.revision))
                }
                val update = async { api.setupConfig(request()) }
                try {
                    withTimeout(5000) { writerEntered.await() }
                    update.cancel()
                    withTimeout(5000) { writerJoining.await() }
                    assertFalse(update.isCompleted); assertFalse(writerJoined.get()); assertTrue(cancelledCandidate!!.isDirectory)
                    assertEquals(oldIdentity, store.readAppliedIdentity()); assertEquals(oldSnapshot.revision, store.readCurrent()!!.revision)
                    assertEquals(oldRuntime, effects.runtime); assertEquals(1L, applications)
                    oldFiles.forEach { (path, hash) -> assertEquals(hash, dev.aerove.lite.data.sha256(File(oldSnapshot.resourceDirectory, path))) }
                    assertEquals(appliedYaml, lease.yaml)
                    leaseFiles.forEach { (path, hash) -> assertEquals(hash, dev.aerove.lite.data.sha256(File(lease.readRoot, path))) }
                } finally { releaseWriter.complete(Unit); update.cancel(); withTimeout(5000) { update.join() } }
                assertTrue(update.isCancelled); assertTrue(writerJoined.get()); assertFalse(cancelledCandidate!!.exists())
                assertEquals(oldIdentity.revision, api.getAppliedSetup()["revision"])
                assertEquals("", api.setupConfig(request()))
                assertEquals(3, downloads.get()); assertEquals(2L, applications)
                val recovered = store.readLastApplied()!!
                assertNotEquals(oldIdentity.revision, recovered.revision)
                assertEquals(recovered.revision, api.getAppliedSetup()["revision"])
                assertEquals(record("provider-refresh"), api.getAppliedSetup()["applied"])
                assertFalse(JSONObject(recovered.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").has("provider-update"))
                val recoveredIdentity = store.readAppliedIdentity()!!
                val recoveredRuntime = effects.runtime
                val recoveredFiles = recovered.handoff.resources.associate { it.relativePath to dev.aerove.lite.data.sha256(File(recovered.resourceDirectory, it.relativePath)) }
                fun postDownloadRequest() = initial.toMutableMap().apply {
                    put("applied", record("post-download-refresh"))
                    put("provider-update", mapOf("namespace" to "proxy-providers", "name" to "feed", "expected" to record("provider-refresh"), "expectedRevision" to recovered.revision))
                }
                blockPrepared.set(true)
                val beforeCommit = async { api.setupConfig(postDownloadRequest()) }
                try {
                    withTimeout(5000) { prepareEntered.await() }
                    assertEquals(4, downloads.get()); assertEquals(4, transportsJoined.get())
                    assertEquals(recoveredIdentity, store.readAppliedIdentity())
                } finally { beforeCommit.cancel(); withTimeout(5000) { beforeCommit.join() }; blockPrepared.set(false) }
                withTimeout(5000) { prepareCancelled.await() }
                assertTrue(beforeCommit.isCancelled); assertEquals(2L, applications)
                assertEquals(recoveredIdentity, store.readAppliedIdentity()); assertEquals(recoveredRuntime, effects.runtime)
                assertEquals(recovered.revision, store.readCurrent()!!.revision)
                recoveredFiles.forEach { (path, hash) -> assertEquals(hash, dev.aerove.lite.data.sha256(File(recovered.resourceDirectory, path))) }
                assertEquals("", api.setupConfig(postDownloadRequest()))
                assertEquals(5, downloads.get()); assertEquals(3L, applications)
                assertNotEquals(recovered.revision, api.getAppliedSetup()["revision"])
                assertTrue(lease.readRoot.isDirectory)
                leaseFiles.forEach { (path, hash) -> assertEquals(hash, dev.aerove.lite.data.sha256(File(lease.readRoot, path))) }
            } finally { lease.closeAfterNativeJoin() }
        }
    }

    @Test fun preinitClientStateIsValidatedCopiedAndLatestIsAppliedOnlyWhenAttachmentExists() = runBlocking<Unit> {
        val home = home(); val effects = Effects()
        val pending = MeowServiceSharedState(Mutex()) { true }
        var current: MeowSetupProtocol? = null
        val first = shared().apply { this["currentProfileName"] = "first" }
        val latest = shared().apply { this["currentProfileName"] = "latest"; this["onlyStatisticsProxy"] = false }
        pending.syncState(pending.nextRevision(), first) { current?.syncState(it) }
        pending.syncState(pending.nextRevision(), latest) { current?.syncState(it) }
        latest["currentProfileName"] = "mutated after reply"
        expect("bad_argument", "$.unexpected") {
            pending.syncState(pending.nextRevision(), shared().apply { this["unexpected"] = true }) { error("Invalid state was applied") }
        }
        assertNull(effects.projection)
        assertTrue(home.listFiles()!!.isEmpty())
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            pending.withLatest { api.syncState(checkNotNull(it)) }
            current = api
            assertEquals("latest", JSONObject(effects.projection!!.sharedStateJson).getString("currentProfileName"))
            assertFalse(effects.projection!!.settings.backgroundPolicy.onlyStatisticsProxy)
            val bad = shared(true).apply {
                this["vpnOptions"] = (this["vpnOptions"] as Map<*, *>).entries.associate { it.key as String to it.value } + ("stack" to "system")
            }
            expect("implementation_blocked", "$.vpnOptions.stack") {
                pending.syncState(pending.nextRevision(), bad) { current?.syncState(it) }
            }
            pending.withLatest { assertEquals("latest", it!!.copyRow().getString("currentProfileName")) }
            assertEquals("latest", JSONObject(effects.projection!!.sharedStateJson).getString("currentProfileName"))
        }
    }

    @Test fun initAndSharedUpdatesUseOneGateAndLateOlderRequestsCannotOverwriteLatest() = runBlocking<Unit> {
        val home = home(); val effects = Effects(); val pending = MeowServiceSharedState(Mutex()) { true }
        pending.syncState(pending.nextRevision(), shared().apply { this["currentProfileName"] = "initial" }) { }
        config(home).use { config ->
            val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val initializing = async(start = CoroutineStart.UNDISPATCHED) {
                pending.withLatest { state -> entered.complete(Unit); release.await(); api.syncState(checkNotNull(state)) }
            }
            entered.await()
            val newer = async(start = CoroutineStart.UNDISPATCHED) {
                pending.syncState(pending.nextRevision(), shared().apply { this["currentProfileName"] = "during init" }) { api.syncState(it) }
            }
            assertFalse(newer.isCompleted)
            release.complete(Unit); initializing.await(); newer.await()
            assertEquals("during init", JSONObject(effects.projection!!.sharedStateJson).getString("currentProfileName"))
            val olderRevision = pending.nextRevision(); val newestRevision = pending.nextRevision()
            pending.syncState(newestRevision, shared().apply { this["currentProfileName"] = "newest" }) { api.syncState(it) }
            pending.syncState(olderRevision, shared().apply { this["currentProfileName"] = "delayed old" }) { error("Old request overwrote latest") }
            assertEquals("newest", JSONObject(effects.projection!!.sharedStateJson).getString("currentProfileName"))
            pending.withLatest { assertEquals("newest", it!!.copyRow().getString("currentProfileName")) }
        }
    }
    @Test fun cancelledOrFailedApplyKeepsLastAcceptedStateAndDetachClearsEngineOwnership() = runBlocking<Unit> {
        val home = home(); val effects = Effects(); var attached = true
        val pending = MeowServiceSharedState(Mutex()) { attached }
        pending.syncState(pending.nextRevision(), shared().apply { this["currentProfileName"] = "accepted" }) { }
        config(home).use { config ->
            val store = RuntimeHandoffStore(home)
            var api = MeowSetupProtocol(home, store, config, effects,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            val initEntered = CompletableDeferred<Unit>(); val initRelease = CompletableDeferred<Unit>()
            effects.onApply = { initEntered.complete(Unit); initRelease.await() }
            val cancelledInit = async(start = CoroutineStart.UNDISPATCHED) {
                pending.withLatest { api.syncState(checkNotNull(it)) }
            }
            initEntered.await(); cancelledInit.cancelAndJoin()
            assertNull(effects.projection)
            effects.onApply = {}
            pending.withLatest { api.syncState(checkNotNull(it)) }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            effects.onApply = { entered.complete(Unit); release.await() }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                pending.syncState(pending.nextRevision(), shared().apply { this["currentProfileName"] = "cancelled" }) { api.syncState(it) }
            }
            entered.await(); cancelled.cancelAndJoin()
            effects.onApply = { throw MeowProtocolFailure("superseded", "Runtime changed before application") }
            try {
                pending.syncState(pending.nextRevision(), shared().apply { this["currentProfileName"] = "failed" }) { api.syncState(it) }
                fail("Failed runtime application was accepted")
            } catch (failure: MeowProtocolFailure) { assertEquals("superseded", failure.code) }
            assertEquals("accepted", JSONObject(effects.projection!!.sharedStateJson).getString("currentProfileName"))
            effects.onApply = {}
            val restarted = Effects()
            api = MeowSetupProtocol(home, store, config, restarted,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            pending.withLatest { api.syncState(checkNotNull(it)) }
            assertEquals("accepted", JSONObject(restarted.projection!!.sharedStateJson).getString("currentProfileName"))
            val oldEngineRevision = pending.nextRevision()
            attached = false; pending.clear()
            try { pending.withLatest { fail("Detached engine replayed state") }; fail("Detached engine was admitted") }
            catch (failure: MeowProtocolFailure) { assertEquals("detached", failure.code) }
            attached = true
            pending.withLatest { assertNull(it) }
            pending.syncState(oldEngineRevision, shared()) { error("Old engine update crossed detach") }
            pending.withLatest { assertNull(it) }
        }
    }
    @Test fun nullableOrOmittedOptionsRetainAcceptedStateAcrossPreinitAndReinit() = runBlocking<Unit> {
        val home = home(); val pending = MeowServiceSharedState(Mutex()) { true }
        val options = ((shared(true)["vpnOptions"] as Map<*, *>).entries.associate { it.key as String to it.value } + ("mtu" to 1280)).toMutableMap()
        val first = shared(true).apply { this["vpnOptions"] = options; this["setupParams"] = setup() }
        pending.syncState(pending.nextRevision(), first) { }
        options["mtu"] = 1500
        pending.syncState(pending.nextRevision(), shared().apply {
            this["vpnOptions"] = null; this["setupParams"] = null; this["currentProfileName"] = "notification only"
        }) { }
        config(home).use { config ->
            repeat(2) { generation ->
                val effects = Effects()
                val api = MeowSetupProtocol(home, RuntimeHandoffStore(home), config, effects,
                    verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
                pending.withLatest { api.syncState(checkNotNull(it)) }
                val projection = effects.projection!!
                assertEquals(1280, projection.settings.network.mtu)
                assertTrue(projection.settings.network.vpnEnabled)
                assertEquals("notification only", JSONObject(projection.sharedStateJson).getString("currentProfileName"))
                assertEquals("local", JSONObject(projection.setupParamsJson!!).getJSONObject("selected-map").getString("Pick"))
                if (generation == 0) pending.syncState(pending.nextRevision(), shared().apply {
                    remove("vpnOptions"); remove("setupParams"); this["currentProfileName"] = "notification only"
                }) { api.syncState(it) }
            }
        }
    }

    @Test fun appliedTunSourceReconcilesRawMtuBeforeFinalValidationThroughSharedOwner() = runBlocking<Unit> {
        val home = home(); val effects = Effects(); val store = RuntimeHandoffStore(home)
        File(home, "config.yaml").writeText("mode: rule\ntun: {enable: true, stack: lwip, mtu: 1400}\nrules: ['MATCH,DIRECT']\n")
        val compatible = mutableListOf<Int>()
        val checkedEffects = object : MeowSetupEffects by effects {
            override suspend fun requireBuilderCompatible(settings: dev.aerove.lite.LiteSettings, vpnOptionsJson: String) {
                compatible.add(settings.network.mtu)
                assertEquals(1400, settings.network.mtu)
                assertEquals(1400, JSONObject(vpnOptionsJson).getInt("mtu"))
            }
        }
        fun state(mtu: Int) = shared(true).apply {
            this["vpnOptions"] = (this["vpnOptions"] as Map<*, *>).entries.associate { it.key as String to it.value } + ("mtu" to mtu)
        }
        config(home).use { config ->
            val api = MeowSetupProtocol(home, store, config, checkedEffects,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(state(1400)); api.setupConfig(setup())
            val staged = store.readCurrent()!!
            assertEquals(1400, (ConfigYaml.parse(staged.handoff.compiledYaml)["tun"] as Map<*, *>)["mtu"])
            store.requestStart(staged.revision, staged.intentEpoch)
            store.commitApplied(staged.revision, staged.intentEpoch, RuntimeAppliedProof(1, 2, "4"))
            effects.runtime = MeowSetupRuntime(staged.revision, staged.intentEpoch, staged.handoff.compiledSha256, "4", 1, 2)
            compatible.clear()
            api.syncState(state(1000))
            val pending = MeowServiceSharedState(Mutex()) { true }
            pending.syncState(pending.nextRevision(), state(1000)) { api.syncState(it) }
            assertEquals(listOf(1400, 1400), compatible)
            assertEquals(1400, effects.projection!!.settings.network.mtu)
        }
    }
    @Test fun boundedWireRequestsMayRetainLargerEffectiveStateAcrossPreinitAndReinit() = runBlocking<Unit> {
        val selected = "a".repeat(80_000)
        val domains = List(160) { "a".repeat(500) }
        val first = shared().apply {
            this["vpnOptions"] = null
            this["setupParams"] = mapOf("selected-map" to mapOf("Pick" to selected), "test-url" to "", "skip-cert-verify" to false)
        }
        val second = shared(true).apply {
            this["setupParams"] = null
            this["vpnOptions"] = (this["vpnOptions"] as Map<*, *>).entries.associate { it.key as String to it.value } +
                mapOf("systemProxy" to true, "bypassDomain" to domains)
        }
        assertTrue(JSONObject(first).toString().toByteArray(Charsets.UTF_8).size < 128 * 1024)
        assertTrue(JSONObject(second).toString().toByteArray(Charsets.UTF_8).size < 128 * 1024)
        val baselineEffects = Effects(); val baselineHome = home()
        config(baselineHome).use { config ->
            val api = MeowSetupProtocol(baselineHome, RuntimeHandoffStore(baselineHome), config, baselineEffects,
                verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
            api.syncState(first); api.syncState(second)
            assertTrue((baselineEffects.projection!!.sharedStateJson + baselineEffects.projection!!.setupParamsJson).toByteArray(Charsets.UTF_8).size > 128 * 1024)
        }
        val pending = MeowServiceSharedState(Mutex()) { true }
        pending.syncState(pending.nextRevision(), first) { }
        pending.syncState(pending.nextRevision(), second) { }
        val tooLarge = shared().apply { this["currentProfileName"] = "x".repeat(128 * 1024) }
        expect("bad_argument", "$") {
            pending.syncState(pending.nextRevision(), tooLarge) { error("Oversized raw request was applied") }
        }
        val replayHome = home()
        config(replayHome).use { config ->
            repeat(2) {
                val effects = Effects()
                val api = MeowSetupProtocol(replayHome, RuntimeHandoffStore(replayHome), config, effects,
                    verifiedFeatures = { emptySet() }, validator = NativeConfigValidator { null })
                expect("bad_argument", "$") { api.syncState(tooLarge) }
                pending.withLatest { api.syncState(checkNotNull(it)) }
                val actual = effects.projection!!
                assertEquals(baselineEffects.projection!!.settings, actual.settings)
                assertEquals(baselineEffects.projection!!.setupParamsJson, actual.setupParamsJson)
                assertEquals(selected, JSONObject(actual.setupParamsJson!!).getJSONObject("selected-map").getString("Pick"))
                assertEquals(domains, actual.settings.network.bypassDomains)
                assertTrue(actual.sharedStateJson.toByteArray(Charsets.UTF_8).size > 128 * 1024)
            }
        }
    }
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
            assertEquals(mapOf("init" to true, "running" to false, "revision" to null, "applied" to null), api.getAppliedSetup())
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
            assertEquals(staged.revision, api.getAppliedSetup()["revision"])
            assertEquals(record(), api.getAppliedSetup()["applied"])
            effects.runtime = effects.runtime!!.copy(listening = false)
            assertEquals(mapOf("init" to true, "running" to false, "revision" to staged.revision, "applied" to record()), api.getAppliedSetup())
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
            fun snapshot(pick: String, version: String, intent: String, requested: Boolean = false) =
                JSONObject().put("graph-token", "19").put("selection-intent", intent)
                    .put("requested", if (requested) JSONObject().put("Pick", "removed") else JSONObject())
                    .put("route", JSONObject().put("core-epoch", "2").put("route-version", version).put("picks", JSONObject().put("Pick", pick)))
                    .put("groups", JSONObject().put("Pick", JSONObject().put("type", "Selector").put("current", pick)
                        .put("fixed", JSONObject.NULL).put("members", org.json.JSONArray(listOf("first", "second")))))
                    .put("startup-consumption", JSONObject()).toString()
            listOf(snapshot("second", "1", "7"), snapshot("first", "2", "8", true), snapshot("first", "2", "8"))
                .forEach { server.enqueue(MockResponse().setBody(it)) }
            CoreApiClient(ControlEndpoint(server.port, "x".repeat(32))).use { client ->
                val receipt = MeowRuntimeSelection(client).apply(mapOf("Pick" to "removed")) { true }
                assertEquals("first", receipt.selected["Pick"])
                assertEquals(2uL, receipt.coreEpoch)
                val before = server.takeRequest()
                assertEquals("GET", before.method)
                assertEquals("/aerove/selection-cache", before.path)
                val selection = server.takeRequest()
                assertEquals("POST", selection.method)
                assertEquals("/aerove/selection-cache", selection.path)
                val request = JSONObject(selection.body.readUtf8())
                assertEquals("removed", request.getJSONObject("requested").getString("Pick"))
                assertEquals("2", request.getString("expectedCoreEpoch"))
                assertEquals("1", request.getString("expectedRouteVersion"))
                assertEquals("7", request.getString("expectedSelectionIntent"))
                assertEquals("19", request.getString("expectedGraphToken"))
                val readback = server.takeRequest()
                assertEquals("GET", readback.method)
                assertEquals("/aerove/selection-cache", readback.path)
                assertEquals(3, server.requestCount)
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
