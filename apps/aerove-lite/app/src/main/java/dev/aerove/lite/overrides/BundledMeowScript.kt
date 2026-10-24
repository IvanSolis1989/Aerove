package dev.aerove.lite.overrides

import dev.aerove.lite.data.strictUtf8PreservingBom
import java.security.MessageDigest

/** Immutable provenance describes the APK asset only, never later user edits or remote updates. */
object BundledMeowScript {
    const val ID = "smart-config-kit-aerove-lite"
    const val NAME = "Smart-Config-Kit (AEROVE Lite)"
    const val ASSET = "scripts/smart-config-kit-meow.js"
    const val VERSION = "v6.0.20-meow.3"
    const val COMMIT = "70d4ddbe0b6d52af7acbd503ff1bf556a3bddacd"
    const val SHA256 = "217a4d7ecfb56d99cbb79148159ee636b882af5520c4ad7b6c6c870405f938cb"
    const val BYTES = 112115
    const val UPDATE_URL = "https://raw.githubusercontent.com/IvanSolis1989/Smart-Config-Kit/refs/heads/main/Meow%E5%86%85%E6%A0%B8%E7%B3%BB/Aerove%20Lite/AeroveLite(meow).js"

    fun resource(bytes: ByteArray): ScriptResource {
        require(bytes.size == BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == SHA256) { "Bundled script integrity check failed" }
        return ScriptResource(ID, NAME, UPDATE_URL, bytes.strictUtf8PreservingBom())
    }
}
