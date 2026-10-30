package dev.aerove.lite.flutter

import java.math.BigDecimal
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/** Uses only Android's stock org.json surface; numeric comparisons never pass through Double. */
internal fun meowJsonEqual(left: Any?, right: Any?): Boolean {
    val leftNull = left == null || left === JSONObject.NULL
    val rightNull = right == null || right === JSONObject.NULL
    if (leftNull || rightNull) return leftNull && rightNull
    if (left is JSONObject && right is JSONObject) {
        if (left.length() != right.length()) return false
        val keys = left.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!right.has(key) || !meowJsonEqual(left.get(key), right.get(key))) return false
        }
        return true
    }
    if (left is JSONArray && right is JSONArray) {
        if (left.length() != right.length()) return false
        return (0 until left.length()).all { meowJsonEqual(left.get(it), right.get(it)) }
    }
    if (left is Number && right is Number) {
        val first = meowJsonDecimal(left) ?: return false
        val second = meowJsonDecimal(right) ?: return false
        return first.compareTo(second) == 0
    }
    return left == right
}

private fun meowJsonDecimal(value: Number): BigDecimal? = try {
    when (value) {
        is BigDecimal -> value
        is BigInteger -> BigDecimal(value)
        is Byte, is Short, is Int, is Long -> BigDecimal.valueOf(value.toLong())
        else -> BigDecimal(value.toString())
    }
} catch (_: NumberFormatException) { null }
