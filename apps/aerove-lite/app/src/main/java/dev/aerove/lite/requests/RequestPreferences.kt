package dev.aerove.lite.requests

import org.json.JSONArray
import org.json.JSONObject

/** Embedded in LiteSettings; this value never opens a second preference store. */
data class RequestPreferences(
    val globalUserAgent: String? = null,
    val userAgents: List<String> = listOf("clash-verge/v2.4.2", "ClashforWindows/0.19.23"),
    val checkCertificate: Boolean = true,
) {
    init {
        require(globalUserAgent == null || validUserAgent(globalUserAgent)) { "Invalid User-Agent" }
        require(userAgents.size <= 256 && userAgents.all(::validUserAgent) && userAgents.distinct().size == userAgents.size) {
            "Invalid User-Agent library"
        }
    }

    fun visibleUserAgents(): List<String> = (userAgents + listOfNotNull(globalUserAgent)).distinct()

    fun editUserAgent(previous: String?, next: String): RequestPreferences {
        val value = next.trim()
        require(validUserAgent(value)) { "Invalid User-Agent" }
        require(value == previous || value !in visibleUserAgents()) { "Duplicate User-Agent" }
        val entries = visibleUserAgents()
        return copy(userAgents = if (previous == null) entries + value else entries.map { if (it == previous) value else it },
            globalUserAgent = if (previous != null && globalUserAgent == previous) value else globalUserAgent)
    }

    fun deleteUserAgent(value: String): RequestPreferences = copy(
        userAgents = visibleUserAgents() - value,
        globalUserAgent = globalUserAgent?.takeUnless { it == value },
    )

    fun moveUserAgent(from: Int, to: Int): RequestPreferences {
        val entries = visibleUserAgents().toMutableList()
        require(from in entries.indices && to in entries.indices)
        entries.add(to, entries.removeAt(from))
        return copy(userAgents = entries)
    }

    companion object {
        fun validUserAgent(value: String): Boolean = value.isNotBlank() && value.length <= 1024 &&
            value.all { it.code in 32..126 }
    }
}

fun interface RequestPolicySource {
    fun snapshot(): RequestPreferences
    val defaultUserAgent: String get() = "AEROVE-Lite"

    companion object {
        val secureDefaults = RequestPolicySource { RequestPreferences() }
    }
}

object RequestPreferencesJson {
    fun encode(value: RequestPreferences): JSONObject = JSONObject()
        .put("globalUserAgent", value.globalUserAgent ?: JSONObject.NULL)
        .put("userAgents", JSONArray(value.userAgents))
        .put("checkCertificate", value.checkCertificate)

    fun decode(value: JSONObject?): RequestPreferences {
        if (value == null) return RequestPreferences()
        require(value.keys().asSequence().all { it in setOf("globalUserAgent", "userAgents", "checkCertificate") }) { "Unknown request preference" }
        val selected = if (!value.has("globalUserAgent") || value.isNull("globalUserAgent")) null
            else value.get("globalUserAgent") as? String ?: throw IllegalArgumentException("Invalid User-Agent")
        val entries = if (!value.has("userAgents")) RequestPreferences().userAgents else {
            val array = value.get("userAgents") as? JSONArray ?: throw IllegalArgumentException("Invalid User-Agent library")
            List(array.length()) { array.get(it) as? String ?: throw IllegalArgumentException("Invalid User-Agent library item") }
        }
        val certificate = if (!value.has("checkCertificate")) true else
            value.get("checkCertificate") as? Boolean ?: throw IllegalArgumentException("Invalid certificate preference")
        return RequestPreferences(selected, entries, certificate)
    }
}

fun productUserAgent(appName: String, version: String): String = "$appName/v$version clash-verge Platform/android".also {
    require(RequestPreferences.validUserAgent(it)) { "Invalid application identity for User-Agent" }
}
