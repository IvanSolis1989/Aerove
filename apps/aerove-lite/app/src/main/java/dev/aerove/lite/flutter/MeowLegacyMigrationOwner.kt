package dev.aerove.lite.flutter

import dev.aerove.lite.data.privateDomainReadOnlyTransaction
import dev.aerove.lite.data.sha256
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import kotlinx.coroutines.ensureActive

internal object NativeLiteDataAuthority {
    fun phase(root: File): String? {
        val file = File(root, "native-lite-migration-native/state.json")
        require(file.parentFile.canonicalFile == file.parentFile.absoluteFile && !Files.isSymbolicLink(file.parentFile.toPath())) { "migration_authority_invalid" }
        if (!file.exists()) return null
        require(!Files.isSymbolicLink(file.toPath()) && file.length() <= 16384) { "migration_authority_invalid" }
        return JSONObject(file.readText()).also { require(it.getInt("schema") == 1) }.getString("phase")
            .also { require(it in setOf("pending", "shared")) { "migration_authority_invalid" } }
    }
    fun requireNativeStore(root: File) {
        check(phase(root) == null) { "native_business_store_retired_or_migrating" }
    }
}

internal class MeowLegacyMigrationOwner(
    private val root: File,
    private val exporter: MeowLegacyMigrationExport,
    private val verifyTarget: (File, String) -> Unit,
) {
    private val sessions = ConcurrentHashMap<String, String>()
    private val leases = ConcurrentHashMap<String, MeowLegacyExportLease>()
    private val directory = File(root, "native-lite-migration-native")
    private val state get() = File(directory, "state.json")

    suspend fun begin(session: String): Map<String, Any?> = privateDomainReadOnlyTransaction(root) {
        when (NativeLiteDataAuthority.phase(root)) {
            "shared" -> {
                val saved = JSONObject(state.readText())
                val receipt = File(directory, "committed-receipt.json")
                check(receipt.isFile && sha256(receipt) == saved.getString("receiptSha256")) { "migration_authority_receipt_missing" }
                return@privateDomainReadOnlyTransaction mapOf("status" to "shared", "sourceDigest" to saved.getString("sourceDigest"), "receiptSha256" to saved.getString("receiptSha256"))
            }
        }
        val legacy = listOf("profiles/index.json", "settings/settings.json", "settings/overrides.json", "settings/builder-resources.yaml", "backups/webdav.json")
            .any { File(root, it).exists() }
        if (!legacy && !state.exists()) return@privateDomainReadOnlyTransaction mapOf("status" to "absent")
        check(sessions.values.all { it == session }) { "migration_consumer_active" }
        leases.entries.firstOrNull()?.let { (token, lease) ->
            return@privateDomainReadOnlyTransaction mapOf("status" to "pending", "leaseId" to token,
                "manifest" to lease.manifest.path, "sha256" to lease.manifestSha256)
        }
        val previous = if (state.exists()) JSONObject(state.readText()) else null
        val lease = exporter.exportProjection(readOutputs(previous))
        if (previous != null && previous.getString("sourceDigest") != lease.manifestSha256) {
            lease.close(); error("migration_source_changed")
        }
        val token = UUID.randomUUID().toString()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            write(state, (previous ?: JSONObject()).put("schema", 1).put("phase", "pending").put("sourceDigest", lease.manifestSha256).toString())
            leases[token] = lease
            sessions[token] = session
        } catch (failure: Throwable) { lease.close(); throw failure }
        mapOf("status" to "pending", "leaseId" to token, "manifest" to lease.manifest.path, "sha256" to lease.manifestSha256)
    }

    private fun outputPath(path: String): Boolean = path.matches(Regex("(?:profiles/[1-9][0-9]{0,18}\\.yaml|scripts/[1-9][0-9]{0,18}\\.js|providers/rules/[0-9a-f]{32}|profiles/providers/[1-9][0-9]{0,18}/(?:proxies|rules)/[0-9a-f]{32})"))

    private fun decodeOutputs(data: JSONObject): Map<String, MeowMigrationOutput> {
        require(data.getInt("schema") == 1) { "migration_output_plan_schema" }
        val rows=data.getJSONArray("files")
        require(rows.length() <= 12000) { "migration_output_plan_limit" }
        val result=linkedMapOf<String,MeowMigrationOutput>()
        var total=0L
        for(i in 0 until rows.length()) {
            val row=rows.getJSONObject(i); val path=row.getString("path")
            val bytes=row.getLong("bytes"); val digest=row.getString("sha256")
            require(outputPath(path) && !result.containsKey(path) && bytes in 0..64L*1024*1024 && digest.matches(Regex("[0-9a-f]{64}"))) { "migration_output_plan_path_or_hash" }
            total=Math.addExact(total,bytes);require(total <= 512L*1024*1024) { "migration_output_plan_limit" }
            result[path]=MeowMigrationOutput(bytes,digest)
        }
        return result
    }

    private fun readOutputs(saved: JSONObject?): Map<String,MeowMigrationOutput> {
        if(saved == null || !saved.has("targetPlanSha256")) return emptyMap()
        val plan=File(directory,"target-files.json")
        require(plan.isFile && plan.canonicalFile == plan.absoluteFile && !Files.isSymbolicLink(plan.toPath()) && plan.length() <= 2*1024*1024 && sha256(plan) == saved.getString("targetPlanSha256")) { "migration_output_plan_changed" }
        val data=JSONObject(plan.readText())
        check(data.getString("sourceDigest") == saved.getString("sourceDigest")) { "migration_output_plan_source" }
        return decodeOutputs(data)
    }

    suspend fun prepareFiles(token: String, planPath: String, digest: String) = privateDomainReadOnlyTransaction(root) {
        val lease=checkNotNull(leases[token]) { "migration_lease_missing" }
        validate(token,lease.manifestSha256)
        val file=File(root,"native-lite-migration/target-files.json")
        require(File(planPath).absoluteFile == file.absoluteFile && file.canonicalFile == file.absoluteFile && !Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() <= 2*1024*1024 && sha256(file) == digest) { "migration_output_plan_private_path" }
        val data=JSONObject(file.readText())
        check(data.getString("sourceDigest") == lease.manifestSha256) { "migration_output_plan_source" }
        val outputs=decodeOutputs(data)
        outputs.keys.forEach { path ->
            val target=File(root,path)
            require(target.canonicalFile == target.absoluteFile && !Files.isSymbolicLink(target.toPath()) &&
                !Files.isSymbolicLink(File(target.path + ".pending").toPath())) { "migration_output_plan_private_path" }
        }
        val original=JSONObject(lease.manifest.readText()).getJSONArray("files")
        val originalPaths=(0 until original.length()).map { original.getJSONObject(it).getString("path") }.toSet()
        require(outputs.keys.none { candidate -> candidate in originalPaths || "$candidate.pending" in originalPaths || originalPaths.any { it.startsWith("$candidate/") } }) { "migration_output_overlaps_source" }
        val saved=JSONObject(state.readText())
        if(saved.has("targetPlanSha256")) check(saved.getString("targetPlanSha256") == digest) { "migration_output_plan_changed" }
        write(File(directory,"target-files.json"),file.readText())
        saved.put("targetPlanSha256",digest);write(state,saved.toString())
    }

    suspend fun confirmTransferred(sourceDigest: String, receiptDigest: String) = privateDomainReadOnlyTransaction(root) {
        check(NativeLiteDataAuthority.phase(root) == "shared") { "migration_authority_not_transferred" }
        val saved=JSONObject(state.readText())
        val receipt=File(directory,"committed-receipt.json")
        check(saved.getString("sourceDigest") == sourceDigest && saved.getString("receiptSha256") == receiptDigest &&
            receipt.canonicalFile == receipt.absoluteFile && !Files.isSymbolicLink(receipt.toPath()) && receipt.isFile && sha256(receipt) == receiptDigest) { "migration_authority_receipt_changed" }
    }

    suspend fun validate(token: String, digest: String) = privateDomainReadOnlyTransaction(root) {
        val lease = checkNotNull(leases[token]) { "migration_lease_missing" }
        check(lease.manifestSha256 == digest && sha256(lease.manifest) == digest) { "migration_lease_changed" }
        exporter.exportProjection(readOutputs(JSONObject(state.readText()))).use { check(it.manifestSha256 == digest) { "migration_source_changed" } }
    }

    suspend fun commit(token: String, receiptPath: String, receiptDigest: String) = privateDomainReadOnlyTransaction(root) {
        val lease = checkNotNull(leases[token]) { "migration_lease_missing" }
        validate(token, lease.manifestSha256)
        val receipt = File(root, "native-lite-migration/target-receipt.json")
        require(File(receiptPath).absoluteFile == receipt.absoluteFile && receipt.canonicalFile == receipt.absoluteFile &&
            !Files.isSymbolicLink(receipt.toPath()) && receipt.isFile && receipt.length() <= 8 * 1024 * 1024 && sha256(receipt) == receiptDigest) {
            "migration_receipt_path_or_hash"
        }
        val data = JSONObject(receipt.readText())
        check(data.getInt("schema") == 1 && data.getString("sourceDigest") == lease.manifestSha256) { "migration_receipt_source" }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val expected=readOutputs(JSONObject(state.readText()))
        check(decodeOutputs(JSONObject().put("schema",1).put("files",data.getJSONArray("files"))) == expected) { "migration_target_plan_changed" }
        verifyTarget(receipt, receiptDigest)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        write(File(directory, "committed-receipt.json"), receipt.readText())
        check(sha256(File(directory, "committed-receipt.json")) == receiptDigest) { "migration_receipt_readback" }
        write(state, JSONObject(state.readText()).put("schema", 1).put("phase", "shared").put("sourceDigest", lease.manifestSha256)
            .put("receiptSha256", receiptDigest).toString())
        check(NativeLiteDataAuthority.phase(root) == "shared") { "migration_authority_readback" }
    }

    fun release(token: String) { sessions.remove(token); leases.remove(token)?.close() }
    fun releaseSession(session: String) { sessions.filterValues { it == session }.keys.forEach(::release) }

    private fun write(target: File, text: String) {
        check(directory.canonicalFile == directory.absoluteFile && !Files.isSymbolicLink(directory.toPath())) { "migration_authority_private_path" }
        check(directory.isDirectory || directory.mkdirs())
        check(target.parentFile == directory && target.canonicalFile == target.absoluteFile && !Files.isSymbolicLink(target.toPath())) { "migration_authority_private_path" }
        val pending = File(target.parentFile, ".${target.name}.pending")
        check(!Files.isSymbolicLink(pending.toPath())) { "migration_authority_private_path" }
        java.io.FileOutputStream(pending).use { it.write(text.toByteArray()); it.fd.sync() }
        Files.move(pending.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        check(target.readText() == text) { "migration_journal_readback" }
    }
}
