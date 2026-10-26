package dev.aerove.lite.overrides

import dev.aerove.lite.data.strictUtf8PreservingBom
import java.security.MessageDigest

/** Immutable provenance describes the APK asset only, never later user edits or remote updates. */
object BundledMeowScript {
    const val ID = "smart-config-kit-aerove-lite"
    const val NAME = "Smart-Config-Kit (AEROVE Lite)"
    const val ASSET = "scripts/smart-config-kit-meow.js"
    const val VERSION = "v6.0.20-meow.4"
    const val COMMIT = "23a2c93b6cba799b83f51e56f95b7f378b425503"
    const val SHA256 = "2acc2840a9c1d896672c9933904a4ccf63a61beb8881543b9138c0606e53c88f"
    const val BYTES = 115462
    const val UPDATE_URL = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js"

    fun resource(bytes: ByteArray): ScriptResource {
        require(bytes.size == BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256) { "Bundled script integrity check failed" }
        return ScriptResource(ID, NAME, UPDATE_URL, bytes.strictUtf8PreservingBom())
    }
}
