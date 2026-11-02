package dev.aerove.lite.overrides

import dev.aerove.lite.data.strictUtf8PreservingBom
import java.security.MessageDigest

/** Immutable provenance describes the APK asset only, never later user edits or remote updates. */
object BundledMeowScript {
    const val ID = "smart-config-kit-aerove-lite"
    const val NAME = "Smart-Config-Kit (AEROVE Lite)"
    const val ASSET = "scripts/smart-config-kit-meow.js"
    const val VERSION = "v6.0.21-meow.8"
    const val COMMIT = "5e28b84fa5161c67222b1b12f71732a2e7b80fae"
    const val SHA256 = "e6e451d7f85bd7a7151e9048daa5294ebaea8b65dda73d38d6af1d150941c6fc"
    const val BYTES = 117854
    const val UPDATE_URL = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js"

    fun resource(bytes: ByteArray): ScriptResource {
        require(bytes.size == BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256) { "Bundled script integrity check failed" }
        return ScriptResource(ID, NAME, UPDATE_URL, bytes.strictUtf8PreservingBom())
    }
}
