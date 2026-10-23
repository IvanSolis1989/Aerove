package dev.aerove.lite.core

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class MeowProbeDialPolicy(val unifiedDelay: Boolean, val tcpConcurrent: Boolean)
data class MeowHostPolicySnapshot(val coreEpoch: String, val http: MeowHttpPolicy, val probeDial: MeowProbeDialPolicy)
data class MeowProbePolicyReceipt(val coreEpoch: String, val probeDial: MeowProbeDialPolicy)

/** Only the operations and exact snapshot fields implemented by native probe-groups-freeze-01. */
object MeowProbePolicyCodec {
    private fun JSONObject.exact(vararg fields: String) {
        require(keys().asSequence().toSet() == fields.toSet()) { "Invalid Meow probe policy fields" }
    }
    private fun JSONObject.text(key: String) = get(key) as? String
        ?: throw IllegalArgumentException("Invalid Meow probe policy string: $key")
    private fun probe(row: JSONObject): MeowProbeDialPolicy {
        row.exact("unifiedDelay", "tcpConcurrent")
        fun flag(key: String) = row.get(key) as? Boolean
            ?: throw IllegalArgumentException("Invalid Meow probe policy boolean: $key")
        return MeowProbeDialPolicy(flag("unifiedDelay"), flag("tcpConcurrent"))
    }
    private fun request(id: String, epoch: String, operation: String, value: JSONObject): String = JSONObject()
        .put("schemaVersion", 1).put("requestId", MeowHttpPolicyCodec.requestId(id))
        .put("expectedCoreEpoch", MeowHttpPolicyCodec.epoch(epoch)).put("operation", operation).put("value", value).toString()

    fun snapshotRequest(id: String, epoch: String): String = request(id, epoch, "snapshot", JSONObject())
    fun setRequest(id: String, epoch: String, value: MeowProbeDialPolicy): String = request(id, epoch, "probeDial",
        JSONObject().put("unifiedDelay", value.unifiedDelay).put("tcpConcurrent", value.tcpConcurrent))

    private fun response(json: String, id: String, operation: String): JSONObject {
        val row = JSONObject(json)
        row.exact("requestId", "coreEpoch", "operation", "value")
        require(MeowHttpPolicyCodec.requestId(row.text("requestId")) == MeowHttpPolicyCodec.requestId(id) &&
            row.text("operation") == operation) { "Meow probe policy response mismatch" }
        MeowHttpPolicyCodec.epoch(row.text("coreEpoch"))
        return row
    }
    fun snapshot(json: String, id: String): MeowHostPolicySnapshot {
        val row = response(json, id, "snapshot")
        val epoch = row.text("coreEpoch")
        val value = row.getJSONObject("value")
        value.exact("http", "probeDial")
        val http = MeowHttpPolicyCodec.status(JSONObject().put("coreEpoch", epoch)
            .put("http", value.getJSONObject("http")).toString()).http
        return MeowHostPolicySnapshot(epoch, http, probe(value.getJSONObject("probeDial")))
    }
    fun receipt(json: String, id: String): MeowProbePolicyReceipt {
        val row = response(json, id, "probeDial")
        return MeowProbePolicyReceipt(row.text("coreEpoch"), probe(row.getJSONObject("value")))
    }
}

/** The caller supplies the existing session lock, epoch owner and latest-intent predicate. */
internal class MeowProbePolicyClient(
    private val handle: Long,
    private val sessionGate: Mutex,
    private val readEpoch: () -> String?,
    private val acceptEpoch: (String) -> Unit,
    private val commands: Commands = JniCommands,
) {
    init { require(handle > 0) { "Invalid Meow handle" } }
    interface Commands {
        fun currentEpoch(handle: Long): String
        fun command(handle: Long, request: String): String
    }
    private object JniCommands : Commands {
        override fun currentEpoch(handle: Long) = MeowNativeCore.readListenerStatus(handle).coreEpoch
        override fun command(handle: Long, request: String) = MeowNativeCore.hostPolicyCommand(handle, request)
    }
    private fun accept(epoch: String) {
        val actual = MeowHttpPolicyCodec.epoch(epoch).toULong()
        val previous = readEpoch()?.let(MeowHttpPolicyCodec::epoch)?.toULong()
        check(previous == null || actual >= previous) { "Stale Meow probe policy epoch" }
        acceptEpoch(epoch)
    }
    private fun snapshot(expected: String): MeowHostPolicySnapshot {
        val id = UUID.randomUUID().toString()
        val actual = MeowProbePolicyCodec.snapshot(commands.command(handle, MeowProbePolicyCodec.snapshotRequest(id, expected)), id)
        check(actual.coreEpoch == expected) { "Meow policy snapshot epoch mismatch" }
        accept(actual.coreEpoch)
        return actual
    }
    private suspend fun <T> transaction(currentIntent: () -> Boolean, action: (() -> Unit) -> T): T {
        val caller = currentCoroutineContext()
        fun current() {
            caller.ensureActive()
            if (!currentIntent()) throw CancellationException("Meow probe policy superseded")
        }
        current()
        return sessionGate.withLock {
            current()
            // An admitted actor transaction is drained even if its waiter is cancelled.
            val actual = withContext(NonCancellable + Dispatchers.IO) { current(); action(::current) }
            current()
            actual
        }
    }
    suspend fun read(currentIntent: () -> Boolean): MeowHostPolicySnapshot = transaction(currentIntent) { current ->
        val epoch = MeowHttpPolicyCodec.epoch(commands.currentEpoch(handle))
        accept(epoch)
        current()
        snapshot(epoch).also { current() }
    }
    suspend fun set(value: MeowProbeDialPolicy, currentIntent: () -> Boolean): MeowHostPolicySnapshot = transaction(currentIntent) { current ->
        val epoch = MeowHttpPolicyCodec.epoch(commands.currentEpoch(handle))
        accept(epoch)
        current()
        val before = snapshot(epoch)
        current()
        if (before.probeDial == value) before else {
            val id = UUID.randomUUID().toString()
            val receipt = MeowProbePolicyCodec.receipt(commands.command(handle, MeowProbePolicyCodec.setRequest(id, epoch, value)), id)
            check(receipt.probeDial == value && receipt.coreEpoch.toULong() > epoch.toULong()) { "Meow probe policy receipt mismatch" }
            accept(receipt.coreEpoch)
            current()
            snapshot(receipt.coreEpoch).also {
                current()
                check(it.probeDial == value) { "Meow probe policy did not converge" }
            }
        }
    }
}
