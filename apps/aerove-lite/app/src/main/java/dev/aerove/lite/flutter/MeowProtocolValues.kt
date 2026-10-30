package dev.aerove.lite.flutter

import org.json.JSONArray
import org.json.JSONObject

internal fun meowObject(value: Any?): Map<String, Any?> {
    val input = if (value is String) try { JSONObject(value) }
        catch (failure: org.json.JSONException) {
            throw MeowProtocolFailure("bad_argument", "Expected valid JSON object text", cause = failure)
        } else value
    if (input is JSONObject) return input.keys().asSequence().associateWith { key -> meowValue(input.get(key)) }
    if (input !is Map<*, *> || input.keys.any { it !is String })
        throw MeowProtocolFailure("bad_argument", "Expected a JSON object")
    return input.entries.associate { (key, item) -> key as String to item }
}

private fun meowValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> meowObject(value)
    is JSONArray -> List(value.length()) { meowValue(value.get(it)) }
    else -> value
}

internal fun meowNoArguments(value: Any?) {
    if (value != null) throw MeowProtocolFailure("bad_argument", "This method does not accept arguments")
}
