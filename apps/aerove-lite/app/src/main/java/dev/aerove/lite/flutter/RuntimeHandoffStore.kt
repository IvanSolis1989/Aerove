package dev.aerove.lite.flutter

import dev.aerove.lite.data.PrivateStorage
import dev.aerove.lite.data.readBounded
import dev.aerove.lite.data.deletePrivateTree
import org.json.JSONObject
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** One atomic state file arbitrates commit, stop and background restore across all store instances. */
class RuntimeHandoffStore(privateDirectory: File, private val expectedCoreSha: String = MEOW_RUNTIME_CORE_SHA,
    private val validateProjection: (RuntimeHandoff) -> Unit = {},
    private val validateSource: (RuntimeHandoff) -> Unit = {}) {
    private val privateRoot = privateDirectory.canonicalFile
    private val storage = PrivateStorage(File(privateRoot, "runtime-handoffs"))
    private var current: MeowRuntimeSnapshot?
        get() = currents[storage.root.path]
        set(value) { if (value == null) currents.remove(storage.root.path) else currents[storage.root.path] = value }
    @Volatile private var managed = false
    private var lastApplied: MeowRuntimeSnapshot? = null
    private var appliedProof: JSONObject? = null
    private var providerSourceTransaction: JSONObject? = null
    private var providerSourceReceipts = JSONObject()
    private val providerPermitAuthority = Any()

    private fun sourcePermitMatches(permit: ProviderSourcePermit?, row: JSONObject, phase: ProviderSourcePhase, revision: String, epoch: Long): Boolean =
        permit != null && permit.belongsTo(providerPermitAuthority) && permit.transactionId == row.getString("transactionId") &&
            permit.planSha256 == row.getString("planSha256") && permit.phase == phase && permit.revision == revision && permit.intentEpoch == epoch && !row.optBoolean("stopObserved")
    private fun mintSourcePermit(row: JSONObject, phase: ProviderSourcePhase, revision: String, epoch: Long): ProviderSourcePermit =
        ProviderSourcePermit.mint(providerPermitAuthority, row.getString("transactionId"), row.getString("planSha256"), phase, revision, epoch)

    internal suspend fun providerSourceApplyPermit(id: String, planSha256: String): ProviderSourcePermit = storage.transaction {
        val (epoch, desired) = load(); val row = checkNotNull(providerSourceTransaction)
        require(row.getString("transactionId") == id && row.getString("planSha256") == planSha256 && row.getString("state") == "PREPARED" &&
            row.getString("decision") == "UNDECIDED" && !row.optBoolean("stopObserved"))
        val prior = row.getJSONObject("prior")
        require(desired?.revision == prior.getString("revision") && epoch == prior.getLong("intentEpoch")) { "provider_source_apply_superseded" }
        mintSourcePermit(row, ProviderSourcePhase.Applying, row.getString("plannedRevision"), Math.addExact(epoch, 1))
    }
    internal fun providerSourceAllowsAdmission(snapshot: MeowRuntimeSnapshot, permit: ProviderSourcePermit? = null): Boolean {
        if (current?.let { it.revision == snapshot.revision && it.intentEpoch == snapshot.intentEpoch } != true) return false
        val row = providerSourceTransaction
        if (row == null || row.optString("decision") != "UNDECIDED") return permit == null
        val phase = when (row.optString("state")) {
            "APPLYING" -> ProviderSourcePhase.Applying
            "ROLLING_BACK" -> ProviderSourcePhase.RollingBack
            else -> return false
        }
        val revision = if (phase == ProviderSourcePhase.Applying) row.getString("plannedRevision") else row.getJSONObject("prior").getString("revision")
        return snapshot.revision == revision && sourcePermitMatches(permit, row, phase, snapshot.revision, snapshot.intentEpoch)
    }
    internal fun providerSourceLeaseAllows(snapshot: MeowRuntimeSnapshot, permit: ProviderSourcePermit?): Boolean {
        val row = providerSourceTransaction
        if (row?.optString("decision") != "UNDECIDED") return providerSourceAllowsAdmission(snapshot)
        val prior = row.optJSONObject("prior")
        // Keeping an already admitted prior lease live never grants a new graph admission.
        if (prior != null && prior.optString("revision") == snapshot.revision && prior.optLong("intentEpoch") == snapshot.intentEpoch &&
            current?.let { it.revision == snapshot.revision && it.intentEpoch == snapshot.intentEpoch } == true) return true
        return providerSourceAllowsAdmission(snapshot, permit)
    }
    internal fun providerSourceKeepStopped(): Boolean = providerSourceTransaction?.optBoolean("stopObserved") == true && current?.userRequested != true

    private fun encodeProviderSnapshot(value: MeowRuntimeSnapshot): JSONObject = JSONObject().put("revision", value.revision)
        .put("intentEpoch", value.intentEpoch).put("userRequested", value.userRequested).put("handoff", value.handoff.json())
    private fun decodeProviderSnapshot(row: JSONObject): MeowRuntimeSnapshot {
        val revision = row.getString("revision").also { require(UUID.fromString(it).toString() == it) }
        return MeowRuntimeSnapshot(revision, row.getLong("intentEpoch"), row.getBoolean("userRequested"),
            RuntimeHandoff.fromJson(row.getJSONObject("handoff")), File(storage.root, "resources/$revision"))
    }
    internal suspend fun beginProviderSourceTransaction(id: String, plan: JSONObject, journal: JSONObject,
        expected: RuntimeAppliedIdentity): JSONObject = storage.transaction {
        val (epoch, desired) = load()
        val digest = nativeLiteJsonHash(plan)
        require(UUID.fromString(id).toString() == id && journal.getString("relativePath") == "provider-source-transactions/$id/source-journal.json" &&
            journal.getString("sha256").matches(Regex("[0-9a-f]{64}")))
        check(!storage.file("geo-pending.json").exists()) { "provider_source_geo_transaction_active" }
        providerSourceTransaction?.let { previous ->
            if (previous.getString("transactionId") == id) {
                require(previous.getString("planSha256") == digest && meowJsonEqual(previous.getJSONObject("sourceJournal"), journal)) { "provider_source_id_reused" }
                return@transaction JSONObject(previous.toString())
            }
            check(previous.optBoolean("sourceFinalized")) { "provider_source_recovery_required" }
            val receipt = JSONObject()
            listOf("schemaVersion", "transactionId", "planSha256", "plannedRevision", "state", "decision", "actualRevision", "sourceFinalized").forEach { key ->
                if (previous.has(key)) receipt.put(key, previous.get(key))
            }
            providerSourceReceipts.put(previous.getString("transactionId"), receipt)
            while (providerSourceReceipts.length() > 128) providerSourceReceipts.remove(providerSourceReceipts.keys().next())
        }
        val prior = checkNotNull(lastApplied)
        requireGeoExpected(expected, epoch, desired, prior)
        require(plan.getString("expectedRevision") == expected.revision && meowJsonEqual(plan.getJSONObject("expected"), JSONObject(expected.expectedSetupRecordJson)))
        providerSourceTransaction = JSONObject().put("schemaVersion", 1).put("transactionId", id).put("plan", plan)
            .put("planSha256", digest).put("sourceJournal", journal).put("plannedRevision", UUID.randomUUID().toString())
            .put("prior", encodeProviderSnapshot(prior)).put("priorProof", JSONObject(appliedProof.toString()))
            .put("decision", "UNDECIDED").put("state", "PREPARED").put("stopObserved", false).put("sourceFinalized", false).put("quiesced", false)
        save(epoch, desired)
        JSONObject(checkNotNull(providerSourceTransaction).toString())
    }
    internal suspend fun readProviderSourceTransaction(id: String? = null): JSONObject? = storage.transaction {
        load()
        val row = if (id == null || providerSourceTransaction?.optString("transactionId") == id) providerSourceTransaction else providerSourceReceipts.optJSONObject(id)
        require(id == null || row != null) { "provider_source_unknown_transaction" }
        row?.let { JSONObject(it.toString()) }
    }
    internal suspend fun failProviderSourceTransaction(id: String, quiesced: Boolean = false) = storage.transaction {
        val (epoch, desired) = load(); val row = checkNotNull(providerSourceTransaction)
        require(row.getString("transactionId") == id)
        if (row.getString("decision") == "UNDECIDED") {
            row.put("state", "ABORT_REQUIRED")
            if (quiesced) row.put("quiesced", true)
            save(epoch, desired)
        }
    }
    internal suspend fun stageProviderSourceRollback(id: String, proof: JSONObject): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, _) = load(); val row = checkNotNull(providerSourceTransaction)
        require(row.getString("transactionId") == id && row.getString("decision") == "UNDECIDED")
        check(row.optBoolean("quiesced")) { "provider_source_pre_restore_join_required" }
        val prior = decodeProviderSnapshot(row.getJSONObject("prior")); verify(prior)
        val metadata = JSONObject(prior.handoff.runtimeSettingsJson)
        val oldProof = metadata.getJSONObject("flutterSetup").getJSONObject("data-source-proof")
        require(nativeLiteJsonHash(oldProof) == nativeLiteJsonHash(proof)) { "provider_source_restore_proof_differs" }
        metadata.getJSONObject("flutterSetup").put("data-source-proof", proof)
        metadata.put("providerSourceRollback", id)
        val original = prior.handoff
        val restored = RuntimeHandoff(original.coreSha, original.sourceId, original.sourceRevision, original.sourceSha256,
            original.compiledYaml, original.compiledSha256, original.setupRecordJson, metadata.toString(), original.vpnOptionsJson, original.notificationJson, original.resources)
        validateSource(restored)
        val recoveryEpoch = Math.addExact(epoch, 1)
        val next = MeowRuntimeSnapshot(prior.revision, recoveryEpoch, prior.userRequested && !row.optBoolean("stopObserved"), restored, prior.resourceDirectory,
            mintSourcePermit(row, ProviderSourcePhase.RollingBack, prior.revision, recoveryEpoch))
        row.put("state", "ROLLING_BACK"); save(next.intentEpoch, next); next
    }
    internal suspend fun finishStoppedProviderSourceAbort(id: String): Unit = storage.transaction {
        val (epoch, desired) = load(); val row = checkNotNull(providerSourceTransaction)
        require(row.getString("transactionId") == id && row.getString("state") == "ROLLING_BACK" && row.optBoolean("stopObserved") && desired?.userRequested == false)
        row.put("state", "ABORTED_STOPPED").put("decision", "ABORTED")
        lastApplied = null; appliedProof = null; save(epoch, desired)
    }
    internal suspend fun finalizeProviderSourceTransaction(id: String, digest: String) = storage.transaction {
        val (epoch, desired) = load(); val row = checkNotNull(providerSourceTransaction)
        if (row.getString("transactionId") != id) {
            require(providerSourceReceipts.optJSONObject(id)?.optString("planSha256") == digest) { "provider_source_unknown_transaction" }
            return@transaction
        }
        require(row.getString("transactionId") == id && row.getString("planSha256") == digest && row.getString("decision") != "UNDECIDED")
        val retired = listOfNotNull(row.optJSONObject("prior"), row.optJSONObject("candidate")).map(::decodeProviderSnapshot)
        row.put("sourceFinalized", true).remove("prior"); row.remove("candidate"); row.remove("priorProof")
        save(epoch, desired)
        val retained = listOfNotNull(desired, lastApplied).map { it.resourceDirectory }.toSet()
        retired.map { it.resourceDirectory }.distinct().filter { it !in retained }.forEach { deletePrivateTree(storage.root, it) }
    }

    val hasAuthority: Boolean get() = managed || storage.file("authority").exists() || storage.file("current.json").exists()
    fun isCurrent(revision: String, epoch: Long): Boolean = current?.let {
        it.revision == revision && it.intentEpoch == epoch && it.userRequested
    } == true

    fun isGraphCandidate(revision: String, epoch: Long): Boolean = current?.let {
        it.revision == revision && it.intentEpoch == epoch
    } == true

    suspend fun isGraphCurrent(identity: RuntimeAppliedIdentity): Boolean = storage.transaction {
        val (epoch, desired) = load()
        val applied = lastApplied
        val proof = appliedProof
        epoch == identity.intentEpoch && desired?.revision == identity.revision &&
            desired.handoff.compiledSha256 == identity.compiledSha256 && applied?.revision == identity.revision &&
            applied.intentEpoch == epoch && applied.handoff.compiledSha256 == identity.compiledSha256 && proof != null &&
            proof.getLong("serviceOwner") == identity.proof.serviceOwner &&
            proof.getLong("sessionGeneration") == identity.proof.sessionGeneration &&
            proof.getString("coreEpoch") == identity.proof.coreEpoch &&
            meowJsonEqual(JSONObject(applied.handoff.setupRecordJson), JSONObject(identity.expectedSetupRecordJson))
    }

    suspend fun admitGraph(snapshotRevision: String, expectedEpoch: Long, permit: ProviderSourcePermit? = null): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision && !snapshot.userRequested) { "runtime_graph_superseded" }
        check(providerSourceAllowsAdmission(checkNotNull(snapshot), permit)) { "provider_source_recovery_required" }
        MeowRuntimeSnapshot(snapshot.revision, snapshot.intentEpoch, snapshot.userRequested, snapshot.handoff, snapshot.resourceDirectory, permit)
    }

    suspend fun commitGraphApplied(snapshotRevision: String, expectedEpoch: Long, proof: RuntimeAppliedProof,
        projection: RuntimeHandoff): MeowRuntimeSnapshot = commitAppliedWithProjection(snapshotRevision, expectedEpoch, proof, projection, false)

    suspend fun currentEpoch(): Long = storage.transaction { load().first }
    internal fun peekCurrent(): MeowRuntimeSnapshot? = current
    suspend fun readCurrent(): MeowRuntimeSnapshot? = storage.transaction { load().second }
    suspend fun readLastApplied(): MeowRuntimeSnapshot? = storage.transaction { load(); lastApplied }

    /** A verified historical receipt; callers still need a live owner check before runtime effects. */
    suspend fun readAppliedIdentity(): RuntimeAppliedIdentity? = storage.transaction {
        load()
        val applied = lastApplied ?: return@transaction null
        val proof = checkNotNull(appliedProof) { "runtime_apply_proof_missing" }
        RuntimeAppliedIdentity(applied.revision, applied.intentEpoch, applied.handoff.compiledSha256,
            RuntimeAppliedProof(proof.getLong("serviceOwner"), proof.getLong("sessionGeneration"), proof.getString("coreEpoch")),
            applied.handoff.setupRecordJson)
    }

    /** Refreshes only the build record of the exact ready run, never a staged successor or its files. */
    suspend fun refreshAppliedMetadata(expected: RuntimeAppliedIdentity, setupRecordJson: String): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, desired) = load()
        check(providerSourceTransaction?.optString("decision") != "UNDECIDED") { "provider_source_recovery_required" }
        val applied = lastApplied
        val persistedProof = appliedProof
        check(epoch == expected.intentEpoch && desired != null && applied != null && persistedProof != null &&
            desired.revision == expected.revision && desired.userRequested &&
            applied.revision == expected.revision && applied.intentEpoch == expected.intentEpoch && applied.userRequested &&
            desired.handoff.compiledSha256 == expected.compiledSha256 && applied.handoff.compiledSha256 == expected.compiledSha256 &&
            persistedProof.getLong("serviceOwner") == expected.proof.serviceOwner &&
            persistedProof.getLong("sessionGeneration") == expected.proof.sessionGeneration &&
            persistedProof.getString("coreEpoch") == expected.proof.coreEpoch &&
            meowJsonEqual(JSONObject(applied.handoff.setupRecordJson), JSONObject(expected.expectedSetupRecordJson)) &&
            meowJsonEqual(JSONObject(desired.handoff.setupRecordJson), JSONObject(expected.expectedSetupRecordJson))) { "runtime_refresh_superseded" }
        require(setupRecordJson.toByteArray().size <= 128 * 1024) { "runtime_setup_record_invalid" }
        val record = JSONObject(setupRecordJson)
        require(record.keys().asSequence().toSet() == setOf("digest", "yaml-md5", "groups", "proxies", "rules")) { "runtime_setup_record_invalid" }
        require(record.get("digest") is String && record.getString("digest").isNotBlank()) { "runtime_setup_record_invalid" }
        require(record.get("yaml-md5") is String && record.getString("yaml-md5") == JSONObject(applied.handoff.setupRecordJson).getString("yaml-md5")) { "runtime_refresh_yaml_changed" }
        listOf("groups", "proxies", "rules").forEach { key ->
            val value = record.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() in 0..Int.MAX_VALUE.toLong()) { "runtime_setup_record_invalid" }
        }
        val handoff = applied.handoff.withSetupRecord(record.toString()).also { it.validate(expectedCoreSha); validateProjection(it) }
        val refreshed = MeowRuntimeSnapshot(applied.revision, applied.intentEpoch, true, handoff, applied.resourceDirectory)
        lastApplied = refreshed
        save(epoch, refreshed)
        val readback = checkNotNull(load().second) { "runtime_refresh_readback_missing" }
        check(readback.revision == expected.revision && readback.intentEpoch == expected.intentEpoch &&
            meowJsonEqual(JSONObject(readback.handoff.setupRecordJson), record) &&
            lastApplied?.handoff?.setupRecordJson == readback.handoff.setupRecordJson) { "runtime_refresh_readback_mismatch" }
        readback
    }

    suspend fun commitApplied(snapshotRevision: String, expectedEpoch: Long, proof: RuntimeAppliedProof): MeowRuntimeSnapshot =
        commitAppliedWithProjection(snapshotRevision, expectedEpoch, proof, null)

    suspend fun commitAppliedWithProjection(snapshotRevision: String, expectedEpoch: Long, proof: RuntimeAppliedProof,
        projection: RuntimeHandoff?): MeowRuntimeSnapshot = commitAppliedWithProjection(snapshotRevision, expectedEpoch, proof, projection, true)

    private suspend fun commitAppliedWithProjection(snapshotRevision: String, expectedEpoch: Long, proof: RuntimeAppliedProof,
        projection: RuntimeHandoff?, userRequested: Boolean): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision && snapshot.userRequested == userRequested) { "runtime_apply_superseded" }
        require(proof.serviceOwner > 0 && proof.sessionGeneration > 0 &&
            proof.coreEpoch.toULongOrNull()?.let { it > 0uL && it.toString() == proof.coreEpoch } == true) { "runtime_apply_proof_invalid" }
        val applied = if (projection == null) checkNotNull(snapshot) else {
            validateReplacement(checkNotNull(snapshot).handoff, projection)
            MeowRuntimeSnapshot(snapshotRevision, epoch, userRequested, projection, snapshot.resourceDirectory)
        }
        validateSource(applied.handoff)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        lastApplied = applied
        appliedProof = JSONObject().put("serviceOwner", proof.serviceOwner).put("sessionGeneration", proof.sessionGeneration).put("coreEpoch", proof.coreEpoch)
        providerSourceTransaction?.takeIf { it.optString("plannedRevision") == applied.revision && it.optString("decision") == "UNDECIDED" }?.let { transaction ->
            check(transaction.getString("state") == "APPLYING" && !transaction.optBoolean("stopObserved")) { "provider_source_apply_cancelled" }
            val marker = JSONObject(applied.handoff.runtimeSettingsJson).getJSONObject("providerSourceTransaction")
            require(marker.getString("transactionId") == transaction.getString("transactionId") && marker.getString("planSha256") == transaction.getString("planSha256"))
            require(marker.getString("afterStateSha256") == nativeLiteJsonHash(JSONObject(applied.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")))
            transaction.put("decision", "COMMITTED").put("state", "COMMITTED").put("actualRevision", applied.revision)
        }
        providerSourceTransaction?.takeIf { it.optString("state") == "ROLLING_BACK" && it.optString("decision") == "UNDECIDED" }?.let { transaction ->
            require(JSONObject(applied.handoff.runtimeSettingsJson).getString("providerSourceRollback") == transaction.getString("transactionId"))
            transaction.put("decision", "ABORTED").put("state", "ABORTED").put("actualRevision", applied.revision)
        }
        save(epoch, applied)
        checkNotNull(load().second)
    }

    suspend fun commitRuntimeProjection(expected: RuntimeAppliedIdentity, projection: RuntimeHandoff,
        proof: RuntimeAppliedProof): RuntimeAppliedIdentity = commitRuntimeProjection(expected, projection, proof, true)

    suspend fun commitGraphRuntimeProjection(expected: RuntimeAppliedIdentity, projection: RuntimeHandoff,
        proof: RuntimeAppliedProof): RuntimeAppliedIdentity = commitRuntimeProjection(expected, projection, proof, false)

    private suspend fun commitRuntimeProjection(expected: RuntimeAppliedIdentity, projection: RuntimeHandoff,
        proof: RuntimeAppliedProof, userRequested: Boolean): RuntimeAppliedIdentity = storage.transaction {
        val (epoch, desired) = load()
        check(providerSourceTransaction?.optString("decision") != "UNDECIDED") { "provider_source_recovery_required" }
        val previous = lastApplied
        val before = appliedProof
        check(epoch == expected.intentEpoch && desired != null && previous != null && before != null &&
            desired.userRequested == userRequested && previous.userRequested == userRequested && desired.revision == expected.revision && previous.revision == expected.revision &&
            previous.intentEpoch == expected.intentEpoch && previous.handoff.compiledSha256 == expected.compiledSha256 &&
            before.getLong("serviceOwner") == expected.proof.serviceOwner && before.getLong("sessionGeneration") == expected.proof.sessionGeneration &&
            before.getString("coreEpoch") == expected.proof.coreEpoch &&
            meowJsonEqual(JSONObject(previous.handoff.setupRecordJson), JSONObject(expected.expectedSetupRecordJson))) { "runtime_projection_superseded" }
        require(proof.serviceOwner == expected.proof.serviceOwner && proof.sessionGeneration == expected.proof.sessionGeneration &&
            proof.coreEpoch.toULongOrNull()?.let { it.toString() == proof.coreEpoch && it >= expected.proof.coreEpoch.toULong() } == true) { "runtime_apply_proof_invalid" }
        validateReplacement(previous.handoff, projection)
        require(JSONObject(projection.setupRecordJson).getString("yaml-md5") == JSONObject(previous.handoff.setupRecordJson).getString("yaml-md5")) { "runtime_refresh_yaml_changed" }
        val next = MeowRuntimeSnapshot(previous.revision, epoch, userRequested, projection, previous.resourceDirectory)
        validateSource(projection)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        lastApplied = next
        appliedProof = JSONObject().put("serviceOwner", proof.serviceOwner).put("sessionGeneration", proof.sessionGeneration).put("coreEpoch", proof.coreEpoch)
        save(epoch, next)
        val actual = checkNotNull(load().second)
        check(meowJsonEqual(actual.handoff.json(), projection.json())) { "runtime_projection_readback_mismatch" }
        RuntimeAppliedIdentity(actual.revision, actual.intentEpoch, actual.handoff.compiledSha256, proof, actual.handoff.setupRecordJson)
    }

    /** Durable candidate bytes are published only after the same live Core returns a committed receipt. */
    internal suspend fun stageGeoPublication(expected: RuntimeAppliedIdentity, transactionId: String,
        projection: RuntimeHandoff): MeowGeoPublication = storage.transaction {
        require(UUID.fromString(transactionId).toString() == transactionId)
        val (epoch,desired)=load()
        check(providerSourceTransaction?.optString("decision") != "UNDECIDED") { "provider_source_recovery_required" }
        val previous=checkNotNull(lastApplied)
        requireGeoExpected(expected,epoch,desired,previous)
        check(!storage.file("geo-pending.json").exists()) { "runtime_geo_recovery_required" }
        projection.validate(expectedCoreSha);validateProjection(projection);validateSource(projection)
        require(previous.handoff.coreSha == projection.coreSha && previous.handoff.sourceId == projection.sourceId &&
            previous.handoff.sourceRevision == projection.sourceRevision && previous.handoff.sourceSha256 == projection.sourceSha256 &&
            previous.handoff.vpnOptionsJson == projection.vpnOptionsJson && previous.handoff.notificationJson == projection.notificationJson) { "runtime_geo_non_geo_projection_changed" }
        val baseline=previous.handoff.resources.associateBy { it.relativePath }
        val added=projection.resources.filter { it != baseline[it.relativePath] }
        require(added.isNotEmpty() && added.all { asset ->
            asset.relativePath.startsWith("geo/") && asset.relativePath.split('/').size == 3 &&
                UUID.fromString(asset.relativePath.split('/')[1]).toString() == asset.relativePath.split('/')[1] &&
                baseline[asset.relativePath] == null
        }) { "runtime_geo_resource_scope_invalid" }
        val copied=mutableListOf<File>()
        try {
            var total=0L
            for(asset in added) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val source=File(asset.sourcePath).absoluteFile
                require(source.toPath() == source.toPath().toRealPath() && source.toPath().startsWith(privateRoot.toPath()) &&
                    Files.isRegularFile(source.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                val bytes=Files.newInputStream(source.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readBounded(64*1024*1024) }
                total+=bytes.size;require(total <= 256L*1024*1024 && runtimeSha256(bytes) == asset.sha256)
                val target=File(previous.resourceDirectory,asset.relativePath)
                require(!target.exists());check(target.parentFile.mkdirs() || target.parentFile.isDirectory)
                require(target.parentFile.toPath() == target.parentFile.toPath().toRealPath())
                java.io.FileOutputStream(target).use { it.write(bytes);it.fd.sync() };copied+=target
                require(runtimeSha256(target.readBytes()) == asset.sha256)
            }
            val next=MeowRuntimeSnapshot(previous.revision,epoch,previous.userRequested,projection,previous.resourceDirectory)
            verify(next)
            val journal=JSONObject().put("schemaVersion",1).put("transactionId",transactionId)
                .put("expectedCompiledSha256",expected.compiledSha256).put("revision",expected.revision)
                .put("intentEpoch",expected.intentEpoch).put("serviceOwner",expected.proof.serviceOwner)
                .put("sessionGeneration",expected.proof.sessionGeneration).put("coreEpoch",expected.proof.coreEpoch)
                .put("expectedSetupRecord",JSONObject(expected.expectedSetupRecordJson)).put("handoff",projection.json())
            storage.write("geo-pending.json",journal.toString())
            MeowGeoPublication(transactionId,expected,next,runtimeSha256(journal.toString().toByteArray()))
        } catch(failure:Throwable) {
            copied.forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            throw failure
        }
    }

    internal suspend fun commitGeoPublication(value: MeowGeoPublication, proof: RuntimeAppliedProof): RuntimeAppliedIdentity = storage.transaction {
        require(proof == value.expected.proof) { "runtime_geo_owner_changed" }
        val (epoch,desired)=load();val previous=checkNotNull(lastApplied)
        val persisted=appliedProof
        if(epoch == value.expected.intentEpoch && desired?.revision == value.next.revision &&
            previous.revision == value.next.revision && previous.intentEpoch == epoch &&
            desired.userRequested == value.next.userRequested && previous.userRequested == value.next.userRequested &&
            meowJsonEqual(desired.handoff.json(),value.next.handoff.json()) &&
            meowJsonEqual(previous.handoff.json(),value.next.handoff.json()) && persisted != null &&
            persisted.getLong("serviceOwner") == proof.serviceOwner &&
            persisted.getLong("sessionGeneration") == proof.sessionGeneration && persisted.getString("coreEpoch") == proof.coreEpoch) {
            verify(previous)
            val pending=storage.file("geo-pending.json")
            if(pending.exists()) {
                require(runtimeSha256(storage.read("geo-pending.json",32*1024*1024).toByteArray()) == value.journalSha256) { "runtime_geo_journal_changed" }
                Files.deleteIfExists(pending.toPath())
            }
            return@transaction RuntimeAppliedIdentity(previous.revision,epoch,previous.handoff.compiledSha256,proof,previous.handoff.setupRecordJson)
        }
        val journal=storage.read("geo-pending.json",32*1024*1024)
        require(runtimeSha256(journal.toByteArray()) == value.journalSha256) { "runtime_geo_journal_changed" }
        requireGeoExpected(value.expected,epoch,desired,previous)
        require(proof.serviceOwner == value.expected.proof.serviceOwner && proof.sessionGeneration == value.expected.proof.sessionGeneration &&
            proof.coreEpoch == value.expected.proof.coreEpoch) { "runtime_geo_owner_changed" }
        verify(value.next)
        // The source admission happened immediately before Core commit. Persist its actual historical
        // result even if its observer was cancelled; subsequent starts still independently verify proof.
        lastApplied=value.next
        appliedProof=JSONObject().put("serviceOwner",proof.serviceOwner).put("sessionGeneration",proof.sessionGeneration).put("coreEpoch",proof.coreEpoch)
        save(epoch,value.next)
        val actual=checkNotNull(load().second)
        require(actual.handoff.compiledSha256 == value.next.handoff.compiledSha256 && actual.handoff.setupRecordJson == value.next.handoff.setupRecordJson)
        Files.deleteIfExists(storage.file("geo-pending.json").toPath())
        val retained=actual.handoff.resources.map { it.relativePath }.toSet()
        previous.handoff.resources.filter { it.relativePath.startsWith("geo/") && it.relativePath !in retained }.forEach { asset ->
            runCatching { Files.deleteIfExists(File(previous.resourceDirectory,asset.relativePath).toPath()) }
        }
        RuntimeAppliedIdentity(actual.revision,epoch,actual.handoff.compiledSha256,proof,actual.handoff.setupRecordJson)
    }

    internal suspend fun abortGeoPublication(value: MeowGeoPublication) = storage.transaction {
        val pending=storage.file("geo-pending.json")
        if(pending.exists() && runtimeSha256(storage.read("geo-pending.json",32*1024*1024).toByteArray()) == value.journalSha256) {
            val retained=retainedResourcePaths(load().second)
            value.next.handoff.resources.filter { it.relativePath.startsWith("geo/") }.forEach { asset ->
                val target=File(value.next.resourceDirectory,asset.relativePath).toPath().toAbsolutePath().normalize()
                if(target !in retained)Files.deleteIfExists(target)
            }
            Files.deleteIfExists(pending.toPath())
        }
    }

    /** Called under the exclusive Native lifecycle lock, before a new Core can read these files. */
    internal suspend fun discardAbandonedGeoAfterNativeJoin() = storage.transaction {
        val pending=storage.file("geo-pending.json")
        if(!pending.exists())return@transaction
        val row=JSONObject(storage.read("geo-pending.json",32*1024*1024))
        require(row.getInt("schemaVersion") == 1)
        val candidate=RuntimeHandoff.fromJson(row.getJSONObject("handoff")).also { it.validate(expectedCoreSha);validateProjection(it) }
        val (_,desired)=load()
        val retained=retainedResourcePaths(desired)
        val revision=row.getString("revision").also { require(UUID.fromString(it).toString() == it) }
        val directory=File(storage.root,"resources/$revision")
        require(directory.toPath() == directory.toPath().toRealPath()) { "runtime_geo_recovery_resource_redirected" }
        for(asset in candidate.resources.filter { it.relativePath.startsWith("geo/") }) {
            val target=File(directory,asset.relativePath)
            val path=target.toPath().toAbsolutePath().normalize()
            if(path !in retained) {
                require(target.parentFile.toPath() == target.parentFile.toPath().toRealPath() && path.startsWith(directory.toPath()))
                Files.deleteIfExists(path)
            }
        }
        Files.deleteIfExists(pending.toPath())
    }

    private fun retainedResourcePaths(desired: MeowRuntimeSnapshot?): Set<java.nio.file.Path> =
        (listOfNotNull(desired,lastApplied) + listOfNotNull(providerSourceTransaction?.optJSONObject("prior")?.let(::decodeProviderSnapshot))).flatMap { snapshot ->
            snapshot.handoff.resources.map { asset ->
                File(snapshot.resourceDirectory,asset.relativePath).toPath().toAbsolutePath().normalize()
            }
        }.toSet()

    private fun requireGeoExpected(expected: RuntimeAppliedIdentity, epoch: Long, desired: MeowRuntimeSnapshot?, previous: MeowRuntimeSnapshot) {
        val proof=checkNotNull(appliedProof)
        check(epoch == expected.intentEpoch && desired?.revision == expected.revision && previous.revision == expected.revision &&
            desired.handoff.compiledSha256 == expected.compiledSha256 && previous.handoff.compiledSha256 == expected.compiledSha256 &&
            desired.userRequested == previous.userRequested && previous.intentEpoch == epoch &&
            proof.getLong("serviceOwner") == expected.proof.serviceOwner && proof.getLong("sessionGeneration") == expected.proof.sessionGeneration &&
            proof.getString("coreEpoch") == expected.proof.coreEpoch &&
            meowJsonEqual(JSONObject(previous.handoff.setupRecordJson),JSONObject(expected.expectedSetupRecordJson))) { "runtime_geo_publication_superseded" }
    }

    private fun validateReplacement(previous: RuntimeHandoff, next: RuntimeHandoff) {
        require(previous.coreSha == next.coreSha && previous.sourceId == next.sourceId && previous.sourceRevision == next.sourceRevision &&
            previous.sourceSha256 == next.sourceSha256 && previous.compiledYaml == next.compiledYaml && previous.compiledSha256 == next.compiledSha256 &&
            previous.resources == next.resources) { "runtime_projection_source_changed" }
        next.validate(expectedCoreSha); validateProjection(next)
    }

    suspend fun restoreLastApplied(expectedEpoch: Long): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, desired) = load()
        check(providerSourceTransaction?.optString("decision") != "UNDECIDED") { "provider_source_recovery_required" }
        check(epoch == expectedEpoch && desired?.userRequested == true) { "runtime_restore_not_requested" }
        val previous = checkNotNull(lastApplied) { "runtime_applied_snapshot_missing" }
        val next = MeowRuntimeSnapshot(previous.revision, Math.addExact(epoch, 1), true, previous.handoff, previous.resourceDirectory)
        save(next.intentEpoch, next); next
    }


    suspend fun commit(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?): MeowRuntimeSnapshot =
        commit(handoff, expectedEpoch, expectedRevision) {}

    internal suspend fun commit(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?,
        sourceIsCurrent: () -> Unit): MeowRuntimeSnapshot = commit(handoff, expectedEpoch, expectedRevision, sourceIsCurrent) { false }

    internal suspend fun commitForSetup(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?,
        hasRequestedIntent: () -> Boolean, sourceIsCurrent: () -> Unit, providerPermit: ProviderSourcePermit? = null): MeowRuntimeSnapshot =
        commit(handoff, expectedEpoch, expectedRevision, sourceIsCurrent, hasRequestedIntent, providerPermit)

    private suspend fun commit(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?,
        sourceIsCurrent: () -> Unit, hasRequestedIntent: () -> Boolean, providerPermit: ProviderSourcePermit? = null): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, old) = load()
        check(epoch == expectedEpoch && old?.revision == expectedRevision) { "runtime_commit_superseded" }
        val frozen = RuntimeHandoff.fromJson(handoff.json()).also { it.validate(expectedCoreSha); validateProjection(it) }
        val transaction = providerSourceTransaction?.takeIf { it.optString("decision") == "UNDECIDED" }
        require(transaction == null && providerPermit == null || transaction != null && transaction.optString("state") == "PREPARED" &&
            sourcePermitMatches(providerPermit, transaction, ProviderSourcePhase.Applying, transaction.getString("plannedRevision"), Math.addExact(epoch, 1))) { "provider_source_transaction_required" }
        val revision = transaction?.getString("plannedRevision") ?: UUID.randomUUID().toString()
        val resources = File(storage.root, "resources/$revision").apply { check(mkdirs()) }
        try {
            var total = 0L
            frozen.resources.forEach { asset ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val source = File(asset.sourcePath).absoluteFile
                require(source.toPath() == source.toPath().toRealPath() && source.toPath().startsWith(privateRoot.toPath()) &&
                    Files.isRegularFile(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "runtime_resource_outside_private_root" }
                val bytes = Files.newInputStream(source.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { it.readBounded(64 * 1024 * 1024) }
                total += bytes.size; require(total <= 256L * 1024 * 1024) { "runtime_resources_too_large" }
                require(runtimeSha256(bytes) == asset.sha256) { "runtime_hash_mismatch: resource" }
                val target = File(resources, asset.relativePath).apply { parentFile.mkdirs() }
                java.io.FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
            }
            val next = MeowRuntimeSnapshot(revision, Math.addExact(epoch, 1), old?.userRequested == true && hasRequestedIntent(), frozen, resources, providerPermit)
            verify(next)
            sourceIsCurrent()
            validateSource(frozen)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            transaction?.put("state", "APPLYING")?.put("candidate", encodeProviderSnapshot(next))
            save(next.intentEpoch, next)
            next
        } catch (failure: Throwable) {
            try { deletePrivateTree(storage.root, resources) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun requestStart(snapshotRevision: String, expectedEpoch: Long, permit: ProviderSourcePermit? = null): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision) { "runtime_start_superseded" }
        check(providerSourceAllowsAdmission(checkNotNull(snapshot), permit)) { "provider_source_recovery_required" }
        check(permit == null || snapshot.userRequested) { "provider_source_start_intent_not_requested" }
        val next = MeowRuntimeSnapshot(snapshotRevision, epoch, true, snapshot.handoff, snapshot.resourceDirectory, permit)
        save(epoch, next); next
    }

    /** Each new start owns a durable epoch, so a different engine cannot cancel its successor. */
    suspend fun beginStart(snapshotRevision: String, expectedEpoch: Long, permit: ProviderSourcePermit? = null): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision) { "runtime_start_superseded" }
        check(providerSourceAllowsAdmission(checkNotNull(snapshot), permit)) { "provider_source_recovery_required" }
        check(permit == null || snapshot.userRequested) { "provider_source_start_intent_not_requested" }
        val nextEpoch = Math.addExact(epoch, 1)
        val rebound = permit?.let { mintSourcePermit(checkNotNull(providerSourceTransaction), it.phase, snapshotRevision, nextEpoch) }
        val next = MeowRuntimeSnapshot(snapshotRevision, nextEpoch, true, snapshot.handoff, snapshot.resourceDirectory, rebound)
        save(next.intentEpoch, next); next
    }

    suspend fun admit(snapshotRevision: String, expectedEpoch: Long, permit: ProviderSourcePermit? = null): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision && snapshot.userRequested) { "runtime_start_superseded" }
        check(providerSourceAllowsAdmission(checkNotNull(snapshot), permit)) { "provider_source_recovery_required" }
        MeowRuntimeSnapshot(snapshot.revision, snapshot.intentEpoch, snapshot.userRequested, snapshot.handoff, snapshot.resourceDirectory, permit)
    }

    suspend fun invalidate(expectedEpoch: Long? = null): Long = storage.transaction {
        val (epoch, snapshot) = load()
        check(expectedEpoch == null || epoch == expectedEpoch) { "runtime_stop_superseded" }
        val nextEpoch = Math.addExact(epoch, 1)
        val stopped = snapshot?.let { MeowRuntimeSnapshot(it.revision, nextEpoch, false, it.handoff, it.resourceDirectory) }
        providerSourceTransaction?.let { transaction ->
            transaction.put("stopObserved", true)
            if (transaction.optString("decision") == "UNDECIDED") transaction.put("state", "ABORT_REQUIRED")
        }
        save(nextEpoch, stopped); nextEpoch
    }

    private fun load(): Pair<Long, MeowRuntimeSnapshot?> = try { loadVerified() } catch (failure: Throwable) {
        current = null
        throw failure
    }

    private fun loadVerified(): Pair<Long, MeowRuntimeSnapshot?> {
        if (!storage.file("current.json").exists()) {
            current = null; lastApplied = null; appliedProof = null; providerSourceTransaction = null; providerSourceReceipts = JSONObject()
            check(!hasAuthority) { "runtime_snapshot_missing" }
            return 0L to null
        }
        managed = true
        val envelope = JSONObject(storage.read("current.json", 32 * 1024 * 1024))
        val payload = envelope.getString("payload")
        require(runtimeSha256(payload.toByteArray()) == envelope.getString("sha256")) { "runtime_hash_mismatch: snapshot" }
        val row = JSONObject(payload)
        require(row.getInt("schemaVersion") == 1) { "runtime_schema_invalid" }
        val epoch = row.getLong("intentEpoch").also { require(it >= 0) { "runtime_epoch_invalid" } }
        fun decode(value: JSONObject): MeowRuntimeSnapshot {
            val revision = value.getString("revision").also { require(UUID.fromString(it).toString() == it) }
            return MeowRuntimeSnapshot(revision, value.getLong("intentEpoch"), value.getBoolean("userRequested"),
                RuntimeHandoff.fromJson(value.getJSONObject("handoff")), File(storage.root, "resources/$revision")).also(::verify)
        }
        val snapshot = if (row.isNull("snapshot")) null else decode(row.getJSONObject("snapshot"))
        require(snapshot == null || snapshot.intentEpoch == epoch) { "runtime_epoch_invalid" }
        lastApplied = if (row.isNull("lastApplied")) null else decode(row.getJSONObject("lastApplied"))
        appliedProof = if (row.isNull("appliedProof")) null else row.getJSONObject("appliedProof")
        providerSourceTransaction = row.optJSONObject("providerSourceTransaction")?.also { transaction ->
            require(transaction.getInt("schemaVersion") == 1 && UUID.fromString(transaction.getString("transactionId")).toString() == transaction.getString("transactionId"))
            require(transaction.getString("planSha256") == nativeLiteJsonHash(transaction.getJSONObject("plan")) &&
                transaction.getString("decision") in setOf("UNDECIDED", "COMMITTED", "ABORTED") &&
                transaction.getString("state") in setOf("PREPARED", "APPLYING", "ABORT_REQUIRED", "ROLLING_BACK", "COMMITTED", "ABORTED", "ABORTED_STOPPED"))
            transaction.optJSONObject("prior")?.let { verify(decodeProviderSnapshot(it)) }
        }
        providerSourceReceipts = row.optJSONObject("providerSourceReceipts") ?: JSONObject()
        require(providerSourceReceipts.length() <= 128)
        require((lastApplied == null) == (appliedProof == null)) { "runtime_apply_proof_missing" }
        appliedProof?.let { proof ->
            require(proof.getLong("serviceOwner") > 0 && proof.getLong("sessionGeneration") > 0 &&
                proof.getString("coreEpoch").let { text -> text.toULongOrNull()?.let { it > 0uL && it.toString() == text } == true }) { "runtime_apply_proof_invalid" }
        }
        current = snapshot
        return epoch to snapshot
    }

    private fun verify(snapshot: MeowRuntimeSnapshot) {
        snapshot.handoff.validate(expectedCoreSha)
        validateProjection(snapshot.handoff)
        require(snapshot.resourceDirectory.isDirectory) { "runtime_resources_missing" }
        snapshot.handoff.resources.forEach { asset ->
            val path = File(snapshot.resourceDirectory, asset.relativePath)
            require(path.canonicalFile.toPath().startsWith(snapshot.resourceDirectory.canonicalFile.toPath()) && !Files.isSymbolicLink(path.toPath()) && path.isFile) { "runtime_resource_path_invalid" }
            require(runtimeSha256(path.inputStream().use { it.readBounded(64 * 1024 * 1024) }) == asset.sha256) { "runtime_hash_mismatch: sealed resource" }
        }
    }

    private fun save(epoch: Long, snapshot: MeowRuntimeSnapshot?) {
        fun encode(value: MeowRuntimeSnapshot): JSONObject = JSONObject().put("revision", value.revision)
            .put("intentEpoch", value.intentEpoch).put("userRequested", value.userRequested).put("handoff", value.handoff.json())
        val row = JSONObject().put("schemaVersion", 1).put("intentEpoch", epoch)
            .put("snapshot", snapshot?.let(::encode) ?: JSONObject.NULL)
            .put("lastApplied", lastApplied?.let(::encode) ?: JSONObject.NULL)
            .put("appliedProof", appliedProof ?: JSONObject.NULL)
            .put("providerSourceTransaction", providerSourceTransaction ?: JSONObject.NULL)
            .put("providerSourceReceipts", providerSourceReceipts).toString()
        val encoded = JSONObject().put("payload", row).put("sha256", runtimeSha256(row.toByteArray())).toString()
        require(encoded.toByteArray().size <= 32 * 1024 * 1024) { "runtime_snapshot_too_large" }
        if (!storage.file("authority").exists()) storage.write("authority", "dart-runtime-v1")
        storage.write("current.json", encoded)
        managed = true; current = snapshot?.let { MeowRuntimeSnapshot(it.revision, it.intentEpoch, it.userRequested, it.handoff, it.resourceDirectory) }
    }

    companion object {
        private val currents = java.util.concurrent.ConcurrentHashMap<String, MeowRuntimeSnapshot>()
    }
}


internal class MeowGeoPublication(val transactionId: String, val expected: RuntimeAppliedIdentity,
    val next: MeowRuntimeSnapshot, val journalSha256: String)
