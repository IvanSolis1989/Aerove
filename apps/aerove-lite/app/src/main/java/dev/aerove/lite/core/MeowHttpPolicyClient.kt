package dev.aerove.lite.core

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Download HTTP policy only; does not relax node, DNS or health-check TLS. */
data class MeowHttpPolicy(val globalUserAgent: String, val checkCertificate: Boolean) {
    init {
        require(globalUserAgent.isNotBlank() && globalUserAgent.toByteArray(Charsets.UTF_8).size <= 4096 &&
            globalUserAgent.none { Character.isISOControl(it) }) { "Invalid Meow HTTP User-Agent" }
    }
    override fun toString() = "MeowHttpPolicy(userAgent=<redacted>, checkCertificate=$checkCertificate)"
}

data class MeowHttpPolicyStatus(val coreEpoch: String, val http: MeowHttpPolicy)
data class MeowHttpPolicyReceipt(val requestId: String, val status: MeowHttpPolicyStatus)

object MeowHttpPolicyCodec {
    fun epoch(value: String): String = value.also {
        require(it.matches(Regex("[1-9][0-9]*")) && it.toULongOrNull() != null) { "Invalid Meow HTTP policy epoch" }
    }
    fun requestId(value: String): String = value.also {
        val id = UUID.fromString(it)
        require(id != UUID(0, 0) && id.toString() == it) { "Invalid Meow HTTP policy request ID" }
    }
    private fun JSONObject.exact(vararg fields: String) {
        require(keys().asSequence().toSet() == fields.toSet()) { "Invalid Meow HTTP policy fields" }
    }
    private fun JSONObject.text(key: String) = get(key) as? String
        ?: throw IllegalArgumentException("Invalid Meow HTTP policy string: $key")
    private fun http(row: JSONObject): MeowHttpPolicy {
        row.exact("globalUserAgent", "checkCertificate")
        return MeowHttpPolicy(row.text("globalUserAgent"), row.get("checkCertificate") as? Boolean
            ?: throw IllegalArgumentException("Invalid Meow HTTP certificate policy"))
    }
    private fun policy(value: MeowHttpPolicy) = JSONObject().put("globalUserAgent", value.globalUserAgent)
        .put("checkCertificate", value.checkCertificate)

    fun request(id: String, expectedEpoch: String, value: MeowHttpPolicy): String = JSONObject()
        .put("schemaVersion", 1).put("requestId", requestId(id)).put("expectedCoreEpoch", epoch(expectedEpoch))
        .put("http", policy(value)).toString()

    fun status(json: String): MeowHttpPolicyStatus {
        val row = JSONObject(json)
        row.exact("coreEpoch", "http")
        return MeowHttpPolicyStatus(epoch(row.text("coreEpoch")), http(row.getJSONObject("http")))
    }
    fun receipt(json: String): MeowHttpPolicyReceipt {
        val row = JSONObject(json)
        row.exact("requestId", "coreEpoch", "http")
        return MeowHttpPolicyReceipt(requestId(row.text("requestId")),
            MeowHttpPolicyStatus(epoch(row.text("coreEpoch")), http(row.getJSONObject("http"))))
    }
}

/** Required shared session gate/epoch callbacks prevent an independent configuration revision owner. */
internal class MeowHttpPolicyClient(
    private val handle: Long,
    private val sessionGate: Mutex,
    private val readEpoch: () -> String?,
    private val acceptEpoch: (String) -> Unit,
    private val commands: Commands = JniCommands,
) {
    init { require(handle > 0) { "Invalid Meow handle" } }
    interface Commands {
        fun status(handle: Long): String
        fun configure(handle: Long, request: String): String
    }
    private object JniCommands : Commands {
        override fun status(handle: Long) = MeowNativeCore.policyStatus(handle)
        override fun configure(handle: Long, request: String) = MeowNativeCore.configurePolicies(handle, request)
    }
    private val intents = AtomicLong()

    suspend fun apply(value: MeowHttpPolicy, isCurrent: () -> Boolean = { true }): MeowHttpPolicyStatus {
        val intent = intents.incrementAndGet()
        val caller = currentCoroutineContext()
        fun current() {
            caller.ensureActive()
            if (intents.get() != intent || !isCurrent()) throw CancellationException("Meow HTTP policy superseded")
        }
        current()
        return sessionGate.withLock {
            current()
            // Drain accepted synchronous JNI through cancellation; never lose its real epoch receipt.
            val result = withContext(NonCancellable + Dispatchers.IO) {
                fun accept(status: MeowHttpPolicyStatus) {
                    val minimum = readEpoch()?.let(MeowHttpPolicyCodec::epoch)
                    check(minimum == null || status.coreEpoch.toULong() >= minimum.toULong()) { "Stale Meow HTTP policy epoch" }
                    acceptEpoch(status.coreEpoch)
                }
                val before = MeowHttpPolicyCodec.status(commands.status(handle))
                accept(before)
                current()
                if (before.http == value) return@withContext before
                val id = UUID.randomUUID().toString()
                val receipt = MeowHttpPolicyCodec.receipt(commands.configure(handle,
                    MeowHttpPolicyCodec.request(id, before.coreEpoch, value)))
                check(receipt.requestId == id && receipt.status.http == value &&
                    receipt.status.coreEpoch.toULong() > before.coreEpoch.toULong()) { "Meow HTTP policy receipt mismatch" }
                accept(receipt.status)
                current()
                val actual = MeowHttpPolicyCodec.status(commands.status(handle))
                accept(actual)
                current()
                check(actual.http == value) { "Meow HTTP policy did not converge" }
                actual
            }
            current()
            result
        }
    }
}
