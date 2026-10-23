package dev.aerove.lite.profilebuilder

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class BuilderFilterBinding(val labels: List<String>, val manualMembers: List<String>)
data class BuilderIconBinding(val set: String, val url: String)

/** Saved in a YAML comment so source and client references share the repository's single revision CAS. */
data class BuilderResourceBindings(
    val proxies: Map<String, String> = emptyMap(),
    val ruleProviders: Map<String, String> = emptyMap(),
    val filters: Map<String, BuilderFilterBinding> = emptyMap(),
    val icons: Map<String, BuilderIconBinding> = emptyMap(),
    val schema: Int = 2,
) {
    fun isEmpty(): Boolean = proxies.isEmpty() && ruleProviders.isEmpty() && filters.isEmpty() && icons.isEmpty()
    fun header(): String {
        if (isEmpty()) return ""
        require(schema in 1..2) { "Unknown resource binding version" }
        if (schema == 2) require((proxies.values + ruleProviders.values + filters.values.flatMap { it.labels } + icons.values.map { it.set }).all(::validBuilderResourceId)) { "Invalid resource binding ID" }
        val json = JSONObject().put("schema", schema).put("proxies", JSONObject(proxies)).put("rule-providers", JSONObject(ruleProviders))
        val filterJson = JSONObject()
        filters.forEach { (group, binding) -> filterJson.put(group, JSONObject().put("labels", JSONArray(binding.labels)).put("manual", JSONArray(binding.manualMembers))) }
        val iconJson = JSONObject()
        icons.forEach { (group, binding) -> iconJson.put(group, JSONObject().put("set", binding.set).put("url", binding.url)) }
        json.put("filters", filterJson).put("icons", iconJson)
        val encoded = json.toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= 64 * 1024) { "Resource bindings are too large" }
        return PREFIX + Base64.getEncoder().encodeToString(encoded) + "\n"
    }

    companion object {
        fun replaceHeader(source: String, bindings: BuilderResourceBindings): String {
            parse(source)
            val match = Regex("(?m)^\uFEFF?[ \t]*# aerove-builder-bindings: [^\r\n]*(?:\r?\n|$)").find(source)
            if (match == null) {
                val header = bindings.header()
                return if (header.isEmpty()) source else if (source.startsWith("\uFEFF")) "\uFEFF" + header + source.substring(1) else header + source
            }
            val ending = if (match.value.endsWith("\r\n")) "\r\n" else if (match.value.endsWith("\n")) "\n" else ""
            val header = bindings.header().trimEnd('\n')
            val bom = if (match.value.startsWith("\uFEFF")) "\uFEFF" else ""
            return source.replaceRange(match.range, bom + if (header.isEmpty()) "" else header + ending)
        }
        private const val PREFIX = "# aerove-builder-bindings: "
        fun parse(source: String): BuilderResourceBindings {
            val headers = source.lineSequence().map { it.removePrefix("\uFEFF").trimStart() }.filter { it.startsWith(PREFIX) }.toList()
            require(headers.size <= 1) { "Duplicate resource binding header" }
            val header = headers.singleOrNull() ?: return BuilderResourceBindings()
            require(header.length <= 90 * 1024) { "Resource bindings are too large" }
            val json = JSONObject(String(Base64.getDecoder().decode(header.removePrefix(PREFIX)), Charsets.UTF_8))
            val version = json.getInt("schema")
            require(version in 1..2) { "Unknown resource binding version" }
            fun names(value: JSONObject): Map<String, String> = value.keys().asSequence().associateWith { value.getString(it) }
            fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }
            val filters = json.getJSONObject("filters")
            val icons = json.getJSONObject("icons")
            return BuilderResourceBindings(names(json.getJSONObject("proxies")), names(json.getJSONObject("rule-providers")),
                filters.keys().asSequence().associateWith { group -> filters.getJSONObject(group).let {
                    BuilderFilterBinding(strings(it.getJSONArray("labels")), strings(it.getJSONArray("manual")))
                } }, icons.keys().asSequence().associateWith { group -> icons.getJSONObject(group).let {
                    BuilderIconBinding(it.getString("set"), it.getString("url"))
                } }, version).also { if (version == 2) it.header() }
        }
    }
}
