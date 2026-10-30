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
    private val validateProjection: (RuntimeHandoff) -> Unit = {}) {
    private val privateRoot = privateDirectory.canonicalFile
    private val storage = PrivateStorage(File(privateRoot, "runtime-handoffs"))
    private var current: MeowRuntimeSnapshot?
        get() = currents[storage.root.path]
        set(value) { if (value == null) currents.remove(storage.root.path) else currents[storage.root.path] = value }
    @Volatile private var managed = false
    private var lastApplied: MeowRuntimeSnapshot? = null
    private var appliedProof: JSONObject? = null

    val hasAuthority: Boolean get() = managed || storage.file("authority").exists() || storage.file("current.json").exists()
    fun isCurrent(revision: String, epoch: Long): Boolean = current?.let {
        it.revision == revision && it.intentEpoch == epoch && it.userRequested
    } == true

    suspend fun currentEpoch(): Long = storage.transaction { load().first }
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
        projection: RuntimeHandoff?): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision && snapshot.userRequested) { "runtime_apply_superseded" }
        require(proof.serviceOwner > 0 && proof.sessionGeneration > 0 &&
            proof.coreEpoch.toULongOrNull()?.let { it > 0uL && it.toString() == proof.coreEpoch } == true) { "runtime_apply_proof_invalid" }
        val applied = if (projection == null) checkNotNull(snapshot) else {
            validateReplacement(checkNotNull(snapshot).handoff, projection)
            MeowRuntimeSnapshot(snapshotRevision, epoch, true, projection, snapshot.resourceDirectory)
        }
        lastApplied = applied
        appliedProof = JSONObject().put("serviceOwner", proof.serviceOwner).put("sessionGeneration", proof.sessionGeneration).put("coreEpoch", proof.coreEpoch)
        save(epoch, applied)
        checkNotNull(load().second)
    }

    suspend fun commitRuntimeProjection(expected: RuntimeAppliedIdentity, projection: RuntimeHandoff,
        proof: RuntimeAppliedProof): RuntimeAppliedIdentity = storage.transaction {
        val (epoch, desired) = load()
        val previous = lastApplied
        val before = appliedProof
        check(epoch == expected.intentEpoch && desired != null && previous != null && before != null &&
            desired.userRequested && desired.revision == expected.revision && previous.revision == expected.revision &&
            previous.intentEpoch == expected.intentEpoch && previous.handoff.compiledSha256 == expected.compiledSha256 &&
            before.getLong("serviceOwner") == expected.proof.serviceOwner && before.getLong("sessionGeneration") == expected.proof.sessionGeneration &&
            before.getString("coreEpoch") == expected.proof.coreEpoch &&
            meowJsonEqual(JSONObject(previous.handoff.setupRecordJson), JSONObject(expected.expectedSetupRecordJson))) { "runtime_projection_superseded" }
        require(proof.serviceOwner == expected.proof.serviceOwner && proof.sessionGeneration == expected.proof.sessionGeneration &&
            proof.coreEpoch.toULongOrNull()?.let { it.toString() == proof.coreEpoch && it >= expected.proof.coreEpoch.toULong() } == true) { "runtime_apply_proof_invalid" }
        validateReplacement(previous.handoff, projection)
        require(JSONObject(projection.setupRecordJson).getString("yaml-md5") == JSONObject(previous.handoff.setupRecordJson).getString("yaml-md5")) { "runtime_refresh_yaml_changed" }
        val next = MeowRuntimeSnapshot(previous.revision, epoch, true, projection, previous.resourceDirectory)
        lastApplied = next
        appliedProof = JSONObject().put("serviceOwner", proof.serviceOwner).put("sessionGeneration", proof.sessionGeneration).put("coreEpoch", proof.coreEpoch)
        save(epoch, next)
        val actual = checkNotNull(load().second)
        check(meowJsonEqual(actual.handoff.json(), projection.json())) { "runtime_projection_readback_mismatch" }
        RuntimeAppliedIdentity(actual.revision, actual.intentEpoch, actual.handoff.compiledSha256, proof, actual.handoff.setupRecordJson)
    }

    private fun validateReplacement(previous: RuntimeHandoff, next: RuntimeHandoff) {
        require(previous.coreSha == next.coreSha && previous.sourceId == next.sourceId && previous.sourceRevision == next.sourceRevision &&
            previous.sourceSha256 == next.sourceSha256 && previous.compiledYaml == next.compiledYaml && previous.compiledSha256 == next.compiledSha256 &&
            previous.resources == next.resources) { "runtime_projection_source_changed" }
        next.validate(expectedCoreSha); validateProjection(next)
    }

    suspend fun restoreLastApplied(expectedEpoch: Long): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, desired) = load()
        check(epoch == expectedEpoch && desired?.userRequested == true) { "runtime_restore_not_requested" }
        val previous = checkNotNull(lastApplied) { "runtime_applied_snapshot_missing" }
        val next = MeowRuntimeSnapshot(previous.revision, Math.addExact(epoch, 1), true, previous.handoff, previous.resourceDirectory)
        save(next.intentEpoch, next); next
    }


    suspend fun commit(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?): MeowRuntimeSnapshot =
        commit(handoff, expectedEpoch, expectedRevision) {}

    internal suspend fun commit(handoff: RuntimeHandoff, expectedEpoch: Long, expectedRevision: String?,
        sourceIsCurrent: () -> Unit): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, old) = load()
        check(epoch == expectedEpoch && old?.revision == expectedRevision) { "runtime_commit_superseded" }
        val frozen = RuntimeHandoff.fromJson(handoff.json()).also { it.validate(expectedCoreSha); validateProjection(it) }
        val revision = UUID.randomUUID().toString()
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
            val next = MeowRuntimeSnapshot(revision, Math.addExact(epoch, 1), false, frozen, resources)
            verify(next)
            sourceIsCurrent()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            save(next.intentEpoch, next)
            next
        } catch (failure: Throwable) {
            try { deletePrivateTree(storage.root, resources) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    suspend fun requestStart(snapshotRevision: String, expectedEpoch: Long): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision) { "runtime_start_superseded" }
        val next = MeowRuntimeSnapshot(snapshotRevision, epoch, true, checkNotNull(snapshot).handoff, snapshot.resourceDirectory)
        save(epoch, next); next
    }

    /** Each new start owns a durable epoch, so a different engine cannot cancel its successor. */
    suspend fun beginStart(snapshotRevision: String, expectedEpoch: Long): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision) { "runtime_start_superseded" }
        val next = MeowRuntimeSnapshot(snapshotRevision, Math.addExact(epoch, 1), true,
            checkNotNull(snapshot).handoff, snapshot.resourceDirectory)
        save(next.intentEpoch, next); next
    }

    suspend fun admit(snapshotRevision: String, expectedEpoch: Long): MeowRuntimeSnapshot = storage.transaction {
        val (epoch, snapshot) = load()
        check(epoch == expectedEpoch && snapshot?.revision == snapshotRevision && snapshot.userRequested) { "runtime_start_superseded" }
        checkNotNull(snapshot)
    }

    suspend fun invalidate(expectedEpoch: Long? = null): Long = storage.transaction {
        val (epoch, snapshot) = load()
        check(expectedEpoch == null || epoch == expectedEpoch) { "runtime_stop_superseded" }
        val nextEpoch = Math.addExact(epoch, 1)
        val stopped = snapshot?.let { MeowRuntimeSnapshot(it.revision, nextEpoch, false, it.handoff, it.resourceDirectory) }
        save(nextEpoch, stopped); nextEpoch
    }

    private fun load(): Pair<Long, MeowRuntimeSnapshot?> = try { loadVerified() } catch (failure: Throwable) {
        current = null
        throw failure
    }

    private fun loadVerified(): Pair<Long, MeowRuntimeSnapshot?> {
        if (!storage.file("current.json").exists()) {
            current = null; lastApplied = null; appliedProof = null
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
            .put("appliedProof", appliedProof ?: JSONObject.NULL).toString()
        val encoded = JSONObject().put("payload", row).put("sha256", runtimeSha256(row.toByteArray())).toString()
        require(encoded.toByteArray().size <= 32 * 1024 * 1024) { "runtime_snapshot_too_large" }
        if (!storage.file("authority").exists()) storage.write("authority", "dart-runtime-v1")
        storage.write("current.json", encoded)
        managed = true; current = snapshot
    }

    companion object {
        private val currents = java.util.concurrent.ConcurrentHashMap<String, MeowRuntimeSnapshot>()
    }
}
