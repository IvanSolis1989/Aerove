package dev.aerove.lite.regexp2

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Versioned UTF-8 protocol. Only success carries usable indices; errors are never matches. */
object FilterProtocol {
    const val VERSION = 1
    const val MAX_BYTES = 4 * 1024 * 1024
    const val INLINE_BYTES = 128 * 1024
    const val BATCH = 1
    const val CANCEL = 2
    const val SHUTDOWN = 3
    const val RESULT = 4
    const val AGE = 5
    const val AGE_RESULT = 6
    const val DEADLINE_MS = 10000
    const val GRACE_MS = 500L
    fun request(id: Long, include: List<String>, exclude: List<String>, names: List<String>, validateOnly: Boolean): ByteArray {
        require(id > 0)
        val value = JSONObject().put("id", id).put("include", JSONArray(include)).put("exclude", JSONArray(exclude))
            .put("names", JSONArray(names)).put("validateOnly", validateOnly).put("deadlineMs", DEADLINE_MS)
            .toString().let(::utf8)
        require(value.size <= MAX_BYTES) { "Filter batch exceeds 4 MiB" }
        return value
    }
    fun utf8(value: String): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        return ByteArray(encoded.remaining()).also(encoded::get)
    }
    fun indices(id: Long, bytes: ByteArray, size: Int): List<Int> {
        require(bytes.size <= MAX_BYTES)
        val result = JSONObject(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
        require(result.getLong("id") == id) { "Filter response identity mismatch" }
        check(result.getString("engine") == "regexp2-v1.12.0+aerove-cancel1/None") { "Filter engine mismatch" }
        check(result.getString("status") == "ok") {
            val error = result.optJSONObject("error")
            "${error?.optString("path") ?: "$"}: ${error?.optString("message") ?: result.getString("status")}"
        }
        val values = result.optJSONArray("indices") ?: return emptyList()
        return List(values.length()) { values.getInt(it).also { index -> require(index in 0 until size) { "Invalid filter result index" } } }
    }
}
