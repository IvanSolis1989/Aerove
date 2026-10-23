package dev.aerove.lite.backgroundpolicy

import org.json.JSONArray
import org.json.JSONObject

/** Stored inside the application's existing settings transaction. */
data class BackgroundPolicyPreferences(
    val autoRun: Boolean = false,
    val minimizeOnExit: Boolean = true,
    val hidden: Boolean = false,
    val onlyStatisticsProxy: Boolean = true,
    val closeConnections: Boolean = true,
    val excludeSSIDs: List<String> = emptyList(),
    val showNotificationStopAction: Boolean = true,
) {
    init {
        require(excludeSSIDs.all(::validSsid)) { "excludeSSIDs: SSID must contain 1–32 UTF-8 bytes" }
        require(excludeSSIDs.distinct().size == excludeSSIDs.size) { "excludeSSIDs: duplicate SSID" }
    }

    fun editSsid(previous: String?, next: String): BackgroundPolicyPreferences {
        require(validSsid(next)) { "excludeSSIDs: invalid SSID" }
        require(previous == null || previous in excludeSSIDs) { "excludeSSIDs: original SSID no longer exists" }
        require(next == previous || next !in excludeSSIDs) { "excludeSSIDs: duplicate SSID" }
        if (previous == next) return this
        // Standard replaces an edited SSID at the end of the list.
        return copy(excludeSSIDs = excludeSSIDs.filterNot { it == previous } + next)
    }

    fun deleteSsids(selected: Set<String>) = copy(excludeSSIDs = excludeSSIDs.filterNot(selected::contains))

    fun moveSsid(from: Int, to: Int): BackgroundPolicyPreferences {
        require(from in excludeSSIDs.indices && to in excludeSSIDs.indices)
        return copy(excludeSSIDs = excludeSSIDs.toMutableList().apply { add(to, removeAt(from)) })
    }

    companion object {
        fun validSsid(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 32 &&
            value.none { it == '\u0000' || it.isISOControl() }

        fun ssidFromPlatform(value: String?): String? {
            if (value == null || value == "<unknown ssid>") return null
            val decoded = if (value.length >= 2 && value.first() == '"' && value.last() == '"') value.substring(1, value.lastIndex) else value
            return decoded.takeIf(::validSsid)
        }
    }
}

object BackgroundPolicyJson {
    private val keys = setOf("autoRun", "minimizeOnExit", "hidden", "onlyStatisticsProxy", "closeConnections", "excludeSSIDs", "showNotificationStopAction")

    fun encode(value: BackgroundPolicyPreferences): JSONObject = JSONObject()
        .put("autoRun", value.autoRun).put("minimizeOnExit", value.minimizeOnExit)
        .put("hidden", value.hidden).put("onlyStatisticsProxy", value.onlyStatisticsProxy)
        .put("closeConnections", value.closeConnections).put("excludeSSIDs", JSONArray(value.excludeSSIDs))
        .put("showNotificationStopAction", value.showNotificationStopAction)

    fun decode(value: JSONObject?): BackgroundPolicyPreferences {
        if (value == null) return BackgroundPolicyPreferences()
        require(value.keys().asSequence().all { it in keys }) { "backgroundPolicy: unknown field" }
        fun boolean(key: String, default: Boolean): Boolean {
            if (!value.has(key)) return default
            return value.get(key) as? Boolean ?: error("backgroundPolicy.$key: expected boolean")
        }
        val ssids = if (!value.has("excludeSSIDs")) emptyList() else {
            val array = value.get("excludeSSIDs") as? JSONArray ?: error("backgroundPolicy.excludeSSIDs: expected array")
            List(array.length()) { index -> array.get(index) as? String ?: error("backgroundPolicy.excludeSSIDs[$index]: expected string") }
        }
        return BackgroundPolicyPreferences(boolean("autoRun", false), boolean("minimizeOnExit", true),
            boolean("hidden", false), boolean("onlyStatisticsProxy", true), boolean("closeConnections", true), ssids,
            boolean("showNotificationStopAction", true))
    }
}
