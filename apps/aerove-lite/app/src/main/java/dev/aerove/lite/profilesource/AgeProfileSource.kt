package dev.aerove.lite.profilesource

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Private identity material. Ownership transfers to this instance; close after the operation. */
class AgeSecret(bytes: ByteArray) : AutoCloseable {
    private var value: ByteArray?
    init { require(bytes.size <= MAX_IDENTITY_BYTES) { "identity_size" }; value = bytes.copyOf() }
    @Synchronized fun copyBytes(): ByteArray = value?.copyOf() ?: error("secret_closed")
    @Synchronized override fun close() { value?.fill(0); value = null }
    override fun toString(): String = "AgeSecret([redacted])"
}

const val MAX_CIPHERTEXT_BYTES = 8 * 1024 * 1024
const val MAX_PLAINTEXT_BYTES = 4 * 1024 * 1024
const val MAX_IDENTITY_BYTES = 64 * 1024

fun interface AgeDecryptTransport {
    suspend fun decrypt(ciphertext: ByteArray, identities: ByteArray): ByteArray
}

class AgeSourceException(val code: String) : IllegalArgumentException(code) {
    init { require(code in CODES) }
    companion object {
        val CODES = setOf("secret_required", "identity_size", "invalid_identity", "ciphertext_size",
            "plaintext_size", "invalid_ciphertext", "wrong_key", "decrypt_failed", "timeout",
            "input_error", "output_error", "invalid_encoding", "invalid_limits")
    }
}

class DecodedProfileSource(val yaml: String, val encrypted: Boolean,
    val downloadSha256: String, val sourceSha256: String) {
    override fun toString(): String = "DecodedProfileSource(encrypted=$encrypted)"
}

/** Decode before configuration validation; persist yaml, retain download and logical source identities separately. */
class AgeProfileSourceDecoder(private val transport: AgeDecryptTransport) {
    suspend fun decode(bytes: ByteArray, secret: AgeSecret? = null): DecodedProfileSource = withContext(Dispatchers.Default) {
        ensureActive()
        if (bytes.size > MAX_CIPHERTEXT_BYTES) throw AgeSourceException("ciphertext_size")
        val encrypted = isAgeEncrypted(bytes)
        val plain = if (encrypted) {
            val identity = secret?.copyBytes() ?: throw AgeSourceException("secret_required")
            try {
                if (identity.isEmpty()) throw AgeSourceException("secret_required")
                transport.decrypt(bytes, identity)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: AgeSourceException) { throw failure }
            catch (failure: Exception) {
                // Only a known fixed helper code may cross this boundary.
                throw AgeSourceException(failure.message?.removePrefix("age: ")?.takeIf { it in AgeSourceException.CODES } ?: "decrypt_failed")
            } finally { identity.fill(0) }
        } else bytes.copyOf()
        try {
            ensureActive()
            if (plain.size > MAX_PLAINTEXT_BYTES) throw AgeSourceException("plaintext_size")
            val yaml = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) { throw AgeSourceException("invalid_encoding") }
            DecodedProfileSource(yaml, encrypted, sha(bytes), sha(plain))
        } finally { plain.fill(0) }
    }
}

fun isAgeEncrypted(bytes: ByteArray): Boolean = AGE_PREFIXES.any { prefix ->
    bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
}
private val AGE_PREFIXES = listOf("-----BEGIN AGE ENCRYPTED FILE-----", "age-encryption.org/v1\n").map { it.toByteArray() }
private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
