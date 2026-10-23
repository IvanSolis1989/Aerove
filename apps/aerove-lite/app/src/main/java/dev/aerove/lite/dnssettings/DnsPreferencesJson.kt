package dev.aerove.lite.dnssettings

import org.json.JSONArray
import org.json.JSONObject

object DnsPreferencesJson {
    fun encode(value: DnsPreferences): JSONObject {
        val settings = value.snapshot().requireValid()
        val values = JSONObject()
        DnsField.entries.forEach { field -> settings.values[field]?.let { values.put(field.path, encodeValue(it)) } }
        val hosts = JSONObject()
        settings.hosts.forEach { (key, targets) -> hosts.put(key, JSONArray(targets)) }
        return JSONObject().put("version", 1).put("values", values)
            .put("overridden", JSONArray(DnsField.entries.filter { it in settings.overridden }.map { it.path }))
            .put("hosts", hosts).also(::checkSize)
            .also { settings.legacyFallbackServers?.let { servers -> it.put("legacyFallbackServers", JSONArray(servers)) }; checkSize(it) }
    }

    fun decode(member: Any?): DnsPreferences {
        if (member == null) return DnsPreferences()
        val root = member as? JSONObject ?: dnsIssue("dnsPreferences", DnsIssueCode.Type)
        checkSize(root)
        only(root, setOf("version", "values", "overridden", "hosts", "legacyFallbackServers"), "dnsPreferences")
        if (root.has("version") && root.get("version") !is Int) dnsIssue("dnsPreferences.version", DnsIssueCode.Type)
        if (root.has("version") && root.getInt("version") != 1) dnsIssue("dnsPreferences.version", DnsIssueCode.Version)
        val values = member(root, "values")?.let { fields -> fields.keys().asSequence().associate { key ->
            val field = dnsField(key)
            field to decodeValue(field, fields.get(key))
        } } ?: emptyMap()
        val selected = if (!root.has("overridden")) emptyList() else strings(root.get("overridden"), "dnsPreferences.overridden")
        if (selected.distinct().size != selected.size) dnsIssue("dnsPreferences.overridden", DnsIssueCode.Duplicate)
        val hosts = member(root, "hosts")?.let { fields -> fields.keys().asSequence().associateWith { key ->
            strings(fields.get(key), "hosts.$key")
        } } ?: emptyMap()
        val legacy = if (root.has("legacyFallbackServers")) strings(root.get("legacyFallbackServers"), "dnsPreferences.legacyFallbackServers") else null
        return DnsPreferences(values, selected.map(::dnsField).toSet(), hosts, legacy).requireValid().snapshot()
    }

    private fun encodeValue(value: DnsValue): Any = when (value) {
        is DnsValue.Bool -> value.value
        is DnsValue.Text -> value.value
        is DnsValue.Entries -> JSONArray(value.value)
        is DnsValue.Policy -> JSONObject().apply { value.value.forEach { (key, servers) -> put(key, JSONArray(servers)) } }
    }

    private fun decodeValue(field: DnsField, raw: Any): DnsValue {
        val path = "dns.${field.path}"
        return when (field.default) {
            is DnsValue.Bool -> DnsValue.Bool(raw as? Boolean ?: dnsIssue(path, DnsIssueCode.Type))
            is DnsValue.Text -> DnsValue.Text(raw as? String ?: dnsIssue(path, DnsIssueCode.Type))
            is DnsValue.Entries -> DnsValue.Entries(strings(raw, path))
            is DnsValue.Policy -> {
                val map = raw as? JSONObject ?: dnsIssue(path, DnsIssueCode.Type)
                DnsValue.Policy(map.keys().asSequence().associateWith { key -> strings(map.get(key), "$path.$key") })
            }
        }
    }

    private fun strings(raw: Any, path: String): List<String> {
        val array = raw as? JSONArray ?: dnsIssue(path, DnsIssueCode.Type)
        return (0 until array.length()).map { array.get(it) as? String ?: dnsIssue("$path[$it]", DnsIssueCode.Type) }
    }
    private fun member(root: JSONObject, key: String): JSONObject? = if (!root.has(key)) null
        else root.get(key) as? JSONObject ?: dnsIssue("dnsPreferences.$key", DnsIssueCode.Type)
    private fun only(root: JSONObject, keys: Set<String>, path: String) {
        root.keys().forEach { if (it !in keys) dnsIssue("$path.$it", DnsIssueCode.UnknownField) }
    }
    private fun checkSize(root: JSONObject) {
        if (root.toString().toByteArray(Charsets.UTF_8).size > 512 * 1024) dnsIssue("dnsPreferences", DnsIssueCode.Size)
    }
}
