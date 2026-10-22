package dev.aerove.lite.networksettings

import org.json.JSONArray
import org.json.JSONObject

/** One namespaced member in the existing Settings transaction; never a second store. */
object NetworkSettingsJson {
    private const val maxSettingsBytes = 128 * 1024

    fun encode(settings: NetworkSettings): JSONObject {
        val value = settings.snapshot().requireValid()
        return JSONObject().put("version", 1).put("systemProxy", value.systemProxy).put("allowBypass", value.allowBypass)
            .put("bypassDomains", JSONArray(value.bypassDomains)).put("allowLan", value.allowLan)
            .put("ports", JSONObject().put("mixed", value.ports.mixed).put("http", value.ports.http).put("socks", value.ports.socks))
            .put("authentication", JSONObject().put("enabled", value.authentication.enabled)
                .put("username", value.authentication.username).put("password", value.authentication.password))
            .also { checkSize(it) }
    }

    /** null means absent. Explicit JSON null or wrong present types are corruption, not defaults. */
    fun decode(member: Any?): NetworkSettings {
        if (member == null) return NetworkSettings()
        val root = member as? JSONObject ?: issue("network", NetworkIssueCode.Type)
        checkSize(root)
        root.only(setOf("version", "systemProxy", "allowBypass", "bypassDomains", "allowLan", "ports", "authentication"), "network")
        if (root.integer("version", 1, "network") != 1) issue("network.version", NetworkIssueCode.Version)
        val defaults = NetworkSettings()
        val ports = root.member("ports", "network")
        ports?.only(setOf("mixed", "http", "socks"), "network.ports")
        val auth = root.member("authentication", "network")
        auth?.only(setOf("enabled", "username", "password"), "network.authentication")
        val domains = if (!root.has("bypassDomains")) defaults.bypassDomains else {
            val array = root.get("bypassDomains") as? JSONArray ?: issue("network.bypassDomains", NetworkIssueCode.Type)
            (0 until array.length()).map { index -> array.get(index) as? String
                ?: issue("network.bypassDomains[$index]", NetworkIssueCode.Type) }
        }
        return NetworkSettings(
            systemProxy = root.boolean("systemProxy", defaults.systemProxy, "network"),
            allowBypass = root.boolean("allowBypass", defaults.allowBypass, "network"),
            bypassDomains = domains,
            ports = UserProxyPorts(ports?.integer("mixed", defaults.ports.mixed, "network.ports") ?: defaults.ports.mixed,
                ports?.integer("http", 0, "network.ports") ?: 0, ports?.integer("socks", 0, "network.ports") ?: 0),
            allowLan = root.boolean("allowLan", defaults.allowLan, "network"),
            authentication = ProxyAuthentication(auth?.boolean("enabled", false, "network.authentication") ?: false,
                auth?.string("username", "", "network.authentication") ?: "", auth?.string("password", "", "network.authentication") ?: ""),
        ).requireValid().snapshot()
    }

    private fun checkSize(value: JSONObject) {
        if (value.toString().toByteArray(Charsets.UTF_8).size > maxSettingsBytes) issue("network", NetworkIssueCode.Size)
    }
    private fun JSONObject.only(allowed: Set<String>, path: String) {
        keys().forEach { if (it !in allowed) issue("$path.$it", NetworkIssueCode.UnknownField) }
    }
    private fun JSONObject.member(key: String, path: String): JSONObject? =
        if (!has(key)) null else get(key) as? JSONObject ?: issue("$path.$key", NetworkIssueCode.Type)
    private fun JSONObject.boolean(key: String, default: Boolean, path: String): Boolean =
        if (!has(key)) default else get(key) as? Boolean ?: issue("$path.$key", NetworkIssueCode.Type)
    private fun JSONObject.string(key: String, default: String, path: String): String =
        if (!has(key)) default else get(key) as? String ?: issue("$path.$key", NetworkIssueCode.Type)
    private fun JSONObject.integer(key: String, default: Int, path: String): Int {
        if (!has(key)) return default
        val raw = get(key)
        if (raw !is Int && raw !is Long) issue("$path.$key", NetworkIssueCode.Type)
        val number = (raw as Number).toLong()
        if (number !in Int.MIN_VALUE..Int.MAX_VALUE) issue("$path.$key", NetworkIssueCode.PortRange)
        return number.toInt()
    }
    private fun issue(field: String, code: NetworkIssueCode): Nothing = throw NetworkSettingsException(listOf(NetworkIssue(field, code)))
}
