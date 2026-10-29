package dev.aerove.lite.overrides

import dev.aerove.lite.data.strictUtf8PreservingBom
import java.security.MessageDigest

/** Immutable provenance describes the APK asset only, never later user edits or remote updates. */
object BundledMeowScript {
    const val ID = "smart-config-kit-aerove-lite"
    const val NAME = "Smart-Config-Kit (AEROVE Lite)"
    const val ASSET = "scripts/smart-config-kit-meow.js"
    const val VERSION = "v6.0.21-meow.7"
    const val COMMIT = "c18c314d94206eb6724d235ca0d590c3e14e9121"
    const val SHA256 = "96b798d1d01129c0d8552a4a7a39b5108668d9007c6af996cb58b82d0d429586"
    const val BYTES = 116534
    const val UPDATE_URL = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js"

    fun resource(bytes: ByteArray): ScriptResource {
        require(bytes.size == BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256) { "Bundled script integrity check failed" }
        return ScriptResource(ID, NAME, UPDATE_URL, bytes.strictUtf8PreservingBom())
    }
}
