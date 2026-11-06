package dev.aerove.lite.flutter

import dev.aerove.lite.data.readBounded
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal class MeowProviderSourceTransactions(private val home: File, private val store: RuntimeHandoffStore,
    private val verifySource: (JSONObject) -> String,
    private val actualIdentity: suspend () -> RuntimeAppliedIdentity?,
    private val apply: suspend (JSONObject, ProviderSourcePermit, JSONObject) -> Unit,
    private val quiesce: suspend () -> Unit,
    private val restore: suspend (MeowRuntimeSnapshot) -> RuntimeAppliedIdentity?) {
    private val gate = gates.computeIfAbsent(home.canonicalPath) { Mutex() }

    suspend fun invoke(arguments: Map<String, Any?>): Map<String, Any?> = gate.withLock {
        val request = JSONObject(arguments)
        require(request.getInt("schemaVersion") == 1)
        val id = request.getString("transactionId").also { require(UUID.fromString(it).toString() == it) }
        val operation = request.getString("operation")
        when (operation) {
            "begin" -> {
                keys(request, setOf("schemaVersion", "operation", "transactionId", "plan", "sourceJournal", "proof"))
                val plan = request.getJSONObject("plan")
                keys(plan, setOf("ownerSha256", "namespace", "name", "expectedRevision", "expected", "sourcePlanSha256"))
                require(plan.getString("namespace") in setOf("proxy-providers", "rule-providers"))
                protocolName(plan.get("name"), "name")
                require(verifySource(request.getJSONObject("proof")) == plan.getString("ownerSha256")) { "provider_source_owner_changed" }
                val journal = checkedJournal(id, request.getJSONObject("sourceJournal"))
                require(journal.getString("sourcePlanSha256") == plan.getString("sourcePlanSha256"))
                require(journal.getJSONObject("sourcePlan").getString("beforeStateSha256") == nativeLiteJsonHash(request.getJSONObject("proof")))
                val identity = actualIdentity() ?: error("provider_source_actual_graph_required")
                val prior = checkNotNull(store.readLastApplied())
                val declarations = dev.aerove.lite.data.ConfigYaml.parse(prior.handoff.compiledYaml)[plan.getString("namespace")] as? Map<*, *>
                require((declarations?.get(plan.getString("name")) as? Map<*, *>)?.get("type") == "file") { "provider_source_external_target_missing" }
                val oldProof = JSONObject(prior.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")
                require(nativeLiteJsonHash(oldProof) == nativeLiteJsonHash(request.getJSONObject("proof"))) { "provider_source_applied_proof_changed" }
                store.beginProviderSourceTransaction(id, plan, request.getJSONObject("sourceJournal"), identity)
            }
            "apply" -> {
                keys(request, setOf("schemaVersion", "operation", "transactionId", "planSha256", "publication", "setupParams"))
                val row = transaction(id, request)
                if (row.getString("decision") == "UNDECIDED") {
                    check(row.getString("state") == "PREPARED" && !row.optBoolean("stopObserved")) { "provider_source_abort_required" }
                    checkedJournal(id, row.getJSONObject("sourceJournal"))
                    val params = request.getJSONObject("setupParams")
                    require(!params.has("provider-update"))
                    val proof = params.getJSONObject("data-source-proof")
                    require(verifySource(proof) == row.getJSONObject("plan").getString("ownerSha256")) { "provider_source_owner_changed" }
                    val publication = request.getJSONObject("publication")
                    keys(publication, setOf("sourceJournalSha256", "afterStateSha256"))
                    require(publication.getString("sourceJournalSha256") == row.getJSONObject("sourceJournal").getString("sha256") &&
                        publication.getString("afterStateSha256") == nativeLiteJsonHash(proof)) { "provider_source_publication_changed" }
                    val permit = store.providerSourceApplyPermit(id, row.getString("planSha256"))
                    try { apply(params, permit, publication) }
                    catch (failure: Throwable) {
                        try { withContext(NonCancellable) { store.failProviderSourceTransaction(id) } }
                        catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                        throw failure
                    }
                }
            }
            "query", "sourceFinalized" -> {
                keys(request, setOf("schemaVersion", "operation", "transactionId", "planSha256"), setOf("sourceFinalized"))
                if (transaction(id, request).getString("decision") == "UNDECIDED") {
                    withContext(NonCancellable) { quiesce(); store.failProviderSourceTransaction(id, quiesced = true) }
                    if (checkNotNull(store.readProviderSourceTransaction(id)).getString("decision") == "COMMITTED" && actualIdentity() == null) {
                        store.readCurrent()?.takeIf { it.userRequested }?.let { current ->
                            restore(current)
                        }
                    }
                }
                if (operation == "sourceFinalized" || request.optBoolean("sourceFinalized"))
                    store.finalizeProviderSourceTransaction(id, request.getString("planSha256"))
            }
            "abort" -> {
                keys(request, setOf("schemaVersion", "operation", "transactionId", "planSha256"), setOf("restored"))
                val row = transaction(id, request)
                if (row.getString("decision") == "UNDECIDED") {
                    if (request.has("restored")) check(row.optBoolean("quiesced")) { "provider_source_pre_restore_join_required" }
                    withContext(NonCancellable) { quiesce(); store.failProviderSourceTransaction(id, quiesced = true) }
                    request.optJSONObject("restored")?.let { restored ->
                        keys(restored, setOf("sourceJournalSha256", "beforeStateSha256", "proof"))
                        checkedJournal(id, row.getJSONObject("sourceJournal"))
                        val proof = restored.getJSONObject("proof")
                        require(verifySource(proof) == row.getJSONObject("plan").getString("ownerSha256"))
                        val journal = checkedJournal(id, row.getJSONObject("sourceJournal"))
                        require(restored.getString("sourceJournalSha256") == row.getJSONObject("sourceJournal").getString("sha256") &&
                            restored.getString("beforeStateSha256") == nativeLiteJsonHash(proof) &&
                            restored.getString("beforeStateSha256") == journal.getJSONObject("sourcePlan").getString("beforeStateSha256"))
                        val prior = store.stageProviderSourceRollback(id, proof)
                        if (checkNotNull(store.readProviderSourceTransaction(id)).optBoolean("stopObserved")) store.finishStoppedProviderSourceAbort(id)
                        else {
                            val identity = restore(prior) ?: error("provider_source_rollback_unconfirmed")
                            require(store.isGraphCurrent(identity) && actualIdentity()?.let { meowSameRuntimeIdentity(it, identity) } == true)
                        }
                    }
                }
            }
            else -> error("provider_source_unknown_operation")
        }
        response(checkNotNull(store.readProviderSourceTransaction(id)))
    }

    private suspend fun transaction(id: String, request: JSONObject): JSONObject = checkNotNull(store.readProviderSourceTransaction(id)).also {
        require(it.getString("planSha256") == request.getString("planSha256")) { "provider_source_plan_changed" }
    }
    private fun checkedJournal(id: String, descriptor: JSONObject): JSONObject {
        keys(descriptor, setOf("relativePath", "sha256"))
        require(descriptor.getString("relativePath") == "provider-source-transactions/$id/source-journal.json")
        val file = File(home, descriptor.getString("relativePath"))
        require(file.toPath() == file.toPath().toRealPath() && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val bytes = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { it.readBounded(8 * 1024 * 1024) }
        require(runtimeSha256(bytes) == descriptor.getString("sha256")) { "provider_source_journal_changed" }
        return JSONObject(String(bytes, Charsets.UTF_8)).also {
            keys(it, setOf("schema", "id", "sourcePlan", "sourcePlanSha256"))
            require(it.getInt("schema") == 1 && it.getString("id") == id && nativeLiteJsonHash(it.getJSONObject("sourcePlan")) == it.getString("sourcePlanSha256"))
        }
    }
    private suspend fun response(row: JSONObject): Map<String, Any?> {
        val decision = row.getString("decision")
        val identity = actualIdentity()?.takeIf { store.isGraphCurrent(it) && it.revision == row.optString("actualRevision") }
        val state = row.getString("state")
        val prior = row.optJSONObject("prior")?.let { snapshot ->
            val proof = row.getJSONObject("priorProof")
            mapOf("revision" to snapshot.getString("revision"), "intentEpoch" to snapshot.getLong("intentEpoch"),
                "compiledSha256" to snapshot.getJSONObject("handoff").getString("compiledSha256"),
                "serviceOwner" to proof.getLong("serviceOwner"), "sessionGeneration" to proof.getLong("sessionGeneration"), "coreEpoch" to proof.getString("coreEpoch"))
        }
        return mapOf("schemaVersion" to 1, "transactionId" to row.getString("transactionId"), "state" to state,
            "decision" to decision, "planSha256" to row.getString("planSha256"), "plannedRevision" to row.getString("plannedRevision"),
            "sourceAction" to when (decision) { "COMMITTED" -> "retain-new"; "ABORTED" -> "none"; else -> if (row.optBoolean("quiesced")) "restore-old" else "none" },
            "runtimeAction" to if (state == "ABORTED_STOPPED") "remain-stopped" else if (decision == "UNDECIDED" && state != "PREPARED") "reapply-prior" else "none",
            "revision" to identity?.revision, "applied" to identity?.let { protocolObject(it.expectedSetupRecordJson) }, "prior" to prior, "error" to null)
    }
    private fun keys(row: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        require(row.keys().asSequence().toSet().let { it.containsAll(required) && (it - required - optional).isEmpty() }) { "provider_source_envelope_invalid" }
    }
    companion object { private val gates = java.util.concurrent.ConcurrentHashMap<String, Mutex>() }
}
