package dev.aerove.lite.editor

import org.json.JSONObject

data class EditorSuggestion(val label: String, val detail: String, val start: Int, val end: Int, val insertion: String)

internal class EditorCompletion private constructor(private val data: JSONObject) {
    private val nodes = data.getJSONArray("nodes")
    private fun node(id: Int) = nodes.getJSONObject(id)
    private fun fields(id: Int, type: String? = null): Map<String, Int> {
        val source = node(id)
        val result = source.getJSONObject("fields").intMap().toMutableMap()
        val variants = source.getJSONObject("variants")
        if (type != null && variants.has(type)) result.putAll(variants.getJSONObject(type).intMap())
        else variants.keys().forEach { result.putAll(variants.getJSONObject(it).intMap()) }
        return result
    }

    fun suggestions(text: String, line: Int, column: Int, language: EditorLanguage, schema: String = "config"): List<EditorSuggestion> {
        val lines = mutableListOf<String>()
        val offsets = mutableListOf(0)
        var offset = 0
        Regex("\\r\\n|\\r|\\n").findAll(text).forEach { separator ->
            lines += text.substring(offset, separator.range.first)
            offset = separator.range.last + 1
            offsets += offset
        }
        lines += text.substring(offset)
        val current = lines.getOrNull(line) ?: return emptyList()
        val before = current.take(column)
        if (before.trimStart().startsWith('#') || before.contains(" #") || before.trimStart().startsWith("//")) return emptyList()
        val prefix = before.takeLastWhile { it.isLetterOrDigit() || it in "-_$" }
        val lineOffset = offsets[line]
        val start = lineOffset + column - prefix.length
        val end = lineOffset + column
        if (language == EditorLanguage.JavaScript) return script(before, prefix, start, end)
        if (language == EditorLanguage.Json) {
            val keys = fields(data.getJSONObject("roots").optInt(schema, 0))
            return rank(prefix, keys.map { (key, id) -> EditorSuggestion(key, hint(id), start, end, key) })
        }
        val root = data.getJSONObject("roots").optInt(schema, 0)
        data class Scope(val indent: Int, val id: Int, val siblings: MutableMap<String, String> = linkedMapOf())
        val stack = mutableListOf(Scope(-1, root))
        for (i in 0 until line) {
            val raw = lines[i]
            if (raw.isBlank() || raw.trimStart().startsWith('#')) continue
            val indent = raw.takeWhile { it == ' ' }.length
            while (stack.size > 1 && indent <= stack.last().indent) stack.removeAt(stack.lastIndex)
            var scope = stack.last()
            var body = raw.trimStart()
            if (body.startsWith("- ") || body == "-") {
                val entry = node(scope.id).optInt("entry", -1)
                if (entry >= 0) {
                    scope = Scope(indent, entry)
                    stack += scope
                }
                body = body.removePrefix("-").trimStart()
            }
            val colon = body.indexOf(':')
            if (colon <= 0) continue
            val key = body.take(colon).trim().trim('\'', '"')
            val value = body.substring(colon + 1).trim().trim('\'', '"')
            scope.siblings[key] = value
            val child = fields(scope.id, scope.siblings["type"])[key] ?: node(scope.id).optInt("entry", -1).takeIf { it >= 0 }
            if (child != null && value.isEmpty()) stack += Scope(indent, child)
        }
        val indent = before.takeWhile { it == ' ' }.length
        while (stack.size > 1 && indent <= stack.last().indent) stack.removeAt(stack.lastIndex)
        var scope = stack.last()
        val body = before.trimStart().removePrefix("- ")
        if (before.trimStart().startsWith("- ")) {
            val entry = node(scope.id).optInt("entry", -1)
            if (entry >= 0) scope = Scope(indent, entry)
        }
        val available = fields(scope.id, scope.siblings["type"])
        val colon = body.indexOf(':')
        val valueNode = if (colon >= 0) available[body.take(colon).trim().trim('\'', '"')] else scope.id.takeIf { node(it).getString("kind") == "scalar" }
        if (valueNode != null) {
            val n = node(valueNode)
            val values = n.getJSONArray("values").let { a -> (0 until a.length()).map(a::getString) }.toMutableList()
            when (n.getString("scalar")) {
                "policy", "outbound" -> {
                    values += listOf("DIRECT", "REJECT", "GLOBAL")
                    values += names(lines)
                }
                "proxyProvider" -> values += dictionaryKeys(lines, "proxy-providers")
                "ruleProvider" -> values += dictionaryKeys(lines, "rule-providers")
                "rule" -> {
                    val types = data.getJSONObject("ruleTypes")
                    return rank(prefix, types.keys().asSequence().map { key ->
                        EditorSuggestion(key, types.getString(key), start, end, "$key,${types.getString(key)},DIRECT")
                    }.toList())
                }
            }
            if (values.isNotEmpty()) return rank(prefix, values.distinct().map { EditorSuggestion(it, "", start, end, it) })
        } else if (colon < 0 && available.isNotEmpty()) {
            val rest = current.drop(column).trimStart()
            return rank(prefix, available.filterKeys { it !in scope.siblings }.map { (key, id) ->
                val kind = node(id).getString("kind")
                val insertion = if (rest.startsWith(':')) key else if (kind == "scalar") "$key: " else "$key:\n${" ".repeat(indent + 2)}"
                EditorSuggestion(key, hint(id), start, end, insertion)
            })
        }
        return rank(prefix, Regex("[\\p{L}_][\\p{L}\\p{N}_-]{1,80}").findAll(text).map { it.value }.distinct().take(3000)
            .filter { it != prefix }.map { EditorSuggestion(it, "", start, end, it) }.toList())
    }

    private fun script(before: String, prefix: String, start: Int, end: Int): List<EditorSuggestion> {
        val receiver = before.dropLast(prefix.length).trimEnd().removeSuffix(".").takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val members = mapOf(
            "console" to listOf("log", "info", "warn", "error"), "JSON" to listOf("stringify", "parse"),
            "Object" to listOf("keys", "values", "entries", "assign", "fromEntries"), "Array" to listOf("isArray", "from", "of"),
            "Math" to listOf("max", "min", "floor", "ceil", "round", "random", "abs"),
        )
        val keys = fields(data.getJSONObject("roots").getInt("config")).keys
        val words = if (receiver == "config") keys.toList() else members[receiver] ?: listOf(
            "const", "let", "return", "function", "if", "else", "for", "of", "in", "while", "break", "continue", "switch", "case",
            "default", "new", "typeof", "instanceof", "true", "false", "null", "undefined", "try", "catch", "finally", "throw",
            "delete", "async", "await", "config", "console", "JSON", "Object", "Array", "RegExp", "Math", "String", "Number",
            "Boolean", "Date", "Set", "Map", "parseInt", "parseFloat", "encodeURIComponent", "decodeURIComponent",
        )
        return rank(prefix, words.map { EditorSuggestion(it, "", start, end, it) })
    }

    private fun hint(id: Int): String = when (node(id).getString("kind")) {
        "map" -> "{…}"
        "list" -> "[…]"
        else -> node(id).getJSONArray("values").let { a -> (0 until minOf(a.length(), 3)).joinToString(" | ") { a.getString(it) } }
    }

    private fun names(lines: List<String>): List<String> = lines.mapNotNull { Regex("^\\s*(?:-\\s+)?name:\\s*['\"]?([^'\"#]+)").find(it)?.groupValues?.get(1)?.trim() }

    private fun dictionaryKeys(lines: List<String>, section: String): List<String> {
        val start = lines.indexOfFirst { it.trimEnd() == "$section:" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { it.isBlank() || it.startsWith(' ') || it.startsWith('#') }
            .mapNotNull { Regex("^  ([^ :]+):").find(it)?.groupValues?.get(1) }
    }

    private fun rank(prefix: String, items: List<EditorSuggestion>): List<EditorSuggestion> = items.mapNotNull { item ->
        val value = item.label.lowercase()
        var at = 0
        var cost = 0
        for (char in prefix.lowercase()) {
            val next = value.indexOf(char, at)
            if (next < 0) return@mapNotNull null
            cost += next - at
            at = next + 1
        }
        item to cost
    }.sortedWith(compareBy({ it.second }, { it.first.label.length }, { it.first.label })).take(30).map { it.first }

    companion object {
        internal fun fromJson(json: String): EditorCompletion = EditorCompletion(JSONObject(json))
    }
}

private fun JSONObject.intMap(): Map<String, Int> = keys().asSequence().associateWith(::getInt)
