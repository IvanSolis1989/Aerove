package dev.aerove.lite.profilesource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgeProfileSourceTest {
    private val cipher = "-----BEGIN AGE ENCRYPTED FILE-----\nsynthetic".toByteArray()
    @Test fun plaintext_and_encrypted_have_distinct_source_lineage_and_wipe_transferred_identity() = runBlocking {
        val plain = "proxies: []\n".toByteArray()
        var passed: ByteArray? = null
        val decoder = AgeProfileSourceDecoder { _, identities -> passed = identities; plain.copyOf() }
        val unencrypted = decoder.decode(plain)
        assertFalse(unencrypted.encrypted)
        assertEquals(unencrypted.downloadSha256, unencrypted.sourceSha256)
        AgeSecret("test-only".toByteArray()).use { secret ->
            val encrypted = decoder.decode(cipher, secret)
            assertEquals(unencrypted.sourceSha256, encrypted.sourceSha256)
            assertNotEquals(encrypted.downloadSha256, encrypted.sourceSha256)
            assertTrue(encrypted.encrypted)
            assertFalse(encrypted.toString().contains("proxies"))
            assertFalse(secret.toString().contains("test-only"))
            assertTrue(passed!!.all { it == 0.toByte() })
        }
    }
    @Test fun secret_required_limits_utf8_and_errors_are_sanitized() = runBlocking {
        suspend fun code(block: suspend () -> Unit): String = try { block(); "success" } catch (error: AgeSourceException) { error.code }
        val decoder = AgeProfileSourceDecoder { _, _ -> throw IllegalStateException("private-secret-payload") }
        assertEquals("secret_required", code { decoder.decode(cipher) })
        assertEquals("ciphertext_size", code { decoder.decode(ByteArray(MAX_CIPHERTEXT_BYTES + 1)) })
        assertEquals("invalid_encoding", code { decoder.decode(byteArrayOf(0xc3.toByte(), 0x28)) })
        AgeSecret("test".toByteArray()).use { secret ->
            assertEquals("decrypt_failed", code { decoder.decode(cipher, secret) })
            assertEquals("wrong_key", code { AgeProfileSourceDecoder { _, _ -> error("age: wrong_key") }.decode(cipher, secret) })
            assertEquals("plaintext_size", code { AgeProfileSourceDecoder { _, _ -> ByteArray(MAX_PLAINTEXT_BYTES + 1) }.decode(cipher, secret) })
        }
    }
    @Test fun cancellation_propagates_and_identity_copy_is_wiped() = runBlocking {
        var passed: ByteArray? = null
        AgeSecret("test".toByteArray()).use { secret ->
            try {
                AgeProfileSourceDecoder { _, key -> passed = key; throw CancellationException() }.decode(cipher, secret)
                fail()
            } catch (_: CancellationException) { }
            assertTrue(passed!!.all { it == 0.toByte() })
        }
        val closed = AgeSecret(byteArrayOf(1)); closed.close()
        assertTrue(runCatching { closed.copyBytes() }.isFailure)
    }
}
