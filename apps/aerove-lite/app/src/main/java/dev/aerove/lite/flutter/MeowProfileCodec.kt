package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigIssue
import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.ConfigYaml
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object MeowProfileCodec {
    private val baseSections = listOf("proxies", "proxy-groups", "sub-rules")

    fun parse(source: String): MutableMap<String, Any?> = ConfigYaml.parse(source)

    fun scriptInput(source: String): String {
        val root = parse(source)
        root["rules"] = rules(root) ?: emptyList<String>()
        if (root["proxy-providers"] == null) root["proxy-providers"] = emptyMap<String, Any?>()
        return jsonValue(root, "$").toString()
    }

    fun profileBase(source: String, originalBytes: ByteArray): Map<String, Any?> {
        val root = parse(source)
        val proxies = mappings(root, "proxies")
        val groups = mappings(root, "proxy-groups")
        val rules = rules(root) ?: emptyList()
        (baseSections + "rules").forEach(root::remove)
        return linkedMapOf("config" to root, "digest" to sha256(originalBytes),
            "match-rules" to rules.filter { it.substringBefore(',').trim().equals("MATCH", ignoreCase = true) },
            "proxies" to proxies.size, "proxy-groups" to groups.size, "rules" to rules.size)
    }

    fun outbounds(source: String): Map<String, Any?> {
        val root = parse(source)
        fun project(key: String) = mappings(root, key).mapIndexed { index, row ->
            linkedMapOf("name" to text(row["name"], "$.$key[$index].name"), "type" to text(row["type"], "$.$key[$index].type"))
        }
        return linkedMapOf("proxies" to project("proxies"), "proxy-groups" to project("proxy-groups"))
    }

    fun merge(source: String, overlay: String): String {
        val profile = parse(source)
        val result = parse(overlay)
        baseSections.forEach { key -> if (profile.containsKey(key)) result[key] = profile[key] }
        // Standard profile_base.go replaces only the large base sections; the overlay owns all other keys.
        if (profile.containsKey("rules")) {
            val base = profile["rules"]
            val added = result["rules"]
            result["rules"] = when {
                added == null || (added is List<*> && added.isEmpty()) -> base
                base == null -> added
                added is List<*> && base is List<*> -> added + base
                else -> invalid("$.rules", "Expected a list")
            }
        }
        return ConfigYaml.dump(result)
    }

    private fun rules(root: Map<String, Any?>): List<String>? {
        val value = root["rules"] ?: return null
        val rows = value as? List<*> ?: invalid("$.rules", "Expected a list")
        return rows.mapIndexed { index, row -> text(row, "$.rules[$index]") }
    }

    private fun mappings(root: Map<String, Any?>, key: String): List<Map<*, *>> {
        val value = root[key] ?: return emptyList()
        val rows = value as? List<*> ?: invalid("$.$key", "Expected a list")
        return rows.mapIndexed { index, row -> row as? Map<*, *> ?: invalid("$.$key[$index]", "Expected an object") }
    }

    private fun jsonValue(value: Any?, path: String): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (key, child) -> put(key as String, jsonValue(child, "$path.$key")) } }
        is List<*> -> JSONArray().apply { value.forEachIndexed { index, child -> put(jsonValue(child, "$path[$index]")) } }
        is String, is Boolean -> value
        is Number -> value.also {
            if ((it is Double && !it.isFinite()) || (it is Float && !it.isFinite())) invalid(path, "Non-finite numbers cannot be script JSON")
        }
        else -> invalid(path, "Unsupported YAML scalar for script JSON")
    }

    private fun text(value: Any?, path: String): String = value as? String ?: invalid(path, "Expected a string")
    private fun invalid(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
