package dev.aerove.lite.data

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

internal class ProfileLinkProblem(
    val code: ProfileLinkErrorCode,
    val field: String,
    override val message: String,
) : IllegalArgumentException(message)

internal fun linkProblem(field: String, reason: String, code: ProfileLinkErrorCode = ProfileLinkErrorCode.INVALID_INPUT): Nothing =
    throw ProfileLinkProblem(code, field, reason)

internal fun checkedLinkText(value: String, limit: Int, field: String): String {
    if (value.length > limit) linkProblem(field, "Text exceeds the byte limit", ProfileLinkErrorCode.LIMIT_EXCEEDED)
    val bytes = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
    } catch (_: Exception) {
        linkProblem(field, "Invalid Unicode text", ProfileLinkErrorCode.INVALID_ENCODING)
    }
    if (bytes > limit) linkProblem(field, "Text exceeds the byte limit", ProfileLinkErrorCode.LIMIT_EXCEEDED)
    return value
}

internal fun percentLinkText(value: String, query: Boolean = false): String {
    val bytes = ByteArrayOutputStream(value.length)
    var start = 0
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' || (query && char == '+')) {
            bytes.write(value.substring(start, index).toByteArray(Charsets.UTF_8))
            if (char == '%') {
                if (index + 2 >= value.length) linkProblem("$", "Invalid percent escape", ProfileLinkErrorCode.INVALID_ENCODING)
                val high = value[index + 1].digitToIntOrNull(16)
                val low = value[index + 2].digitToIntOrNull(16)
                if (high == null || low == null) linkProblem("$", "Invalid percent escape", ProfileLinkErrorCode.INVALID_ENCODING)
                bytes.write(high * 16 + low)
                index += 3
            } else {
                bytes.write(32)
                index++
            }
            start = index
        } else index++
    }
    bytes.write(value.substring(start).toByteArray(Charsets.UTF_8))
    val result = try { bytes.toByteArray().strictUtf8() } catch (_: Exception) {
        linkProblem("$", "Percent escapes are not UTF-8", ProfileLinkErrorCode.INVALID_ENCODING)
    }
    if (result.any { it.isISOControl() }) linkProblem("$", "Control characters are not allowed", ProfileLinkErrorCode.INVALID_ENCODING)
    return result
}

internal fun base64LinkText(value: String): String {
    if (value.isEmpty() || !value.matches(Regex("[A-Za-z0-9+/_-]*={0,2}")) || value.length % 4 == 1) {
        linkProblem("$.base64", "Invalid base64 text", ProfileLinkErrorCode.INVALID_ENCODING)
    }
    return try {
        val alphabet = value.replace('-', '+').replace('_', '/')
        Base64.getDecoder().decode(alphabet).strictUtf8()
    } catch (_: Exception) {
        linkProblem("$.base64", "Invalid base64 or UTF-8", ProfileLinkErrorCode.INVALID_ENCODING)
    }
}

internal fun linkUri(value: String): URI = try { URI(value) } catch (_: Exception) {
    linkProblem("$.url", "Malformed URI", ProfileLinkErrorCode.INVALID_URL)
}

internal fun requireLinkJson(text: String) {
    var index = 0
    fun invalid(): Nothing = linkProblem("$.json", "Invalid JSON syntax", ProfileLinkErrorCode.INVALID_CONFIGURATION)
    fun space() { while (index < text.length && text[index] in " \n\r\t") index++ }
    fun consume(char: Char): Boolean {
        space()
        return if (text.getOrNull(index) == char) { index++; true } else false
    }
    fun string() {
        if (!consume('"')) invalid()
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') return
            if (char.code < 32) invalid()
            if (char == '\\') {
                when (text.getOrNull(index++)) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> repeat(4) { if (text.getOrNull(index++)?.digitToIntOrNull(16) == null) invalid() }
                    else -> invalid()
                }
            }
        }
        invalid()
    }
    fun value(depth: Int) {
        if (depth > 50) linkProblem("$.json", "JSON nesting is too deep", ProfileLinkErrorCode.LIMIT_EXCEEDED)
        space()
        when (text.getOrNull(index)) {
            '{' -> {
                index++
                if (!consume('}')) while (true) {
                    string()
                    if (!consume(':')) invalid()
                    value(depth + 1)
                    if (consume('}')) break
                    if (!consume(',')) invalid()
                }
            }
            '[' -> {
                index++
                if (!consume(']')) while (true) {
                    value(depth + 1)
                    if (consume(']')) break
                    if (!consume(',')) invalid()
                }
            }
            '"' -> string()
            't', 'f', 'n' -> {
                val literal = when (text[index]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                if (!text.startsWith(literal, index)) invalid()
                index += literal.length
            }
            else -> {
                val number = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?").find(text, index)
                if (number == null || number.range.first != index) invalid()
                index += number.value.length
            }
        }
    }
    value(0)
    space()
    if (index != text.length) invalid()
}

internal class ProfileLinkQuery(raw: String?, private val limits: ProfileLinkLimits) {
    private val values = linkedMapOf<String, String>()
    private val consumed = mutableSetOf<String>()

    init {
        if (!raw.isNullOrEmpty()) {
            val parts = raw.split('&')
            if (parts.size > limits.maxParameters) linkProblem("$.query", "Too many parameters", ProfileLinkErrorCode.LIMIT_EXCEEDED)
            parts.forEach { part ->
                val key = percentLinkText(part.substringBefore('='), true)
                val value = percentLinkText(part.substringAfter('=', ""), true)
                if (key.isEmpty() || key.length > 64 || key.any { !it.isLetterOrDigit() && it !in "-_." }) {
                    linkProblem("$.query", "Invalid parameter name")
                }
                checkedLinkText(value, limits.maxParameterBytes, "$.query.$key")
                if (values.put(key, value) != null) linkProblem("$.query.$key", "Duplicate parameter", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
            }
        }
    }

    fun take(key: String): String? { consumed += key; return values[key] }

    fun alias(vararg keys: String): String? {
        val present = keys.mapNotNull { key -> take(key)?.let { key to it } }
        if (present.size > 1) linkProblem("$.query.${present.last().first}", "Conflicting parameter aliases", ProfileLinkErrorCode.DUPLICATE_PARAMETER)
        return present.firstOrNull()?.second
    }

    fun finish() {
        values.keys.firstOrNull { it !in consumed }?.let {
            linkProblem("$.query.$it", "Unknown share-link parameter; it was not discarded")
        }
    }
}
