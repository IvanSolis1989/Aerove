package dev.aerove.lite.overrides

import dev.aerove.lite.data.strictUtf8PreservingBom
import java.security.MessageDigest

/** Immutable provenance describes the APK asset only, never later user edits or remote updates. */
object BundledMeowScript {
    const val ID = "smart-config-kit-aerove-lite"
    const val NAME = "Smart-Config-Kit (AEROVE Lite)"
    const val ASSET = "scripts/smart-config-kit-meow.js"
    const val VERSION = "v6.0.20-meow.5"
    const val COMMIT = "dba12215fe8f8d139a94fbd2d5b981282fbcf9c7"
    const val SHA256 = "c80118f8fad6f86ab17a6e2b2ecd9ed8dfe5e15ea27ab6f6d72e8a1b6b9a71a8"
    const val BYTES = 116534
    const val UPDATE_URL = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js"

    fun resource(bytes: ByteArray): ScriptResource {
        require(bytes.size == BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256) { "Bundled script integrity check failed" }
        return ScriptResource(ID, NAME, UPDATE_URL, bytes.strictUtf8PreservingBom())
    }
}
