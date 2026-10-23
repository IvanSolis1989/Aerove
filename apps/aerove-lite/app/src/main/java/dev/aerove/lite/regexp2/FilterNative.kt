package dev.aerove.lite.regexp2

/** Referenced only by the isolated service worker, never loaded by application initialization. */
internal object FilterNative {
    init { System.loadLibrary("aerove_filter_jni") }
    @JvmStatic external fun batch(request: ByteArray): ByteArray
    @JvmStatic external fun decrypt(ciphertext: ByteArray, identities: ByteArray, id: Long): ByteArray
    @JvmStatic external fun cancel(id: Long): Int
}
