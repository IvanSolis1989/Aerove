package dev.aerove.lite.data

import java.io.IOException
import org.json.JSONObject

data class StartupSelectionGroup(val type: String, val current: String, val fixed: String?, val members: List<String>)
data class StartupSelectionConsumption(val selected: String, val fixedBefore: String, val fixedAfter: String)
data class StartupSelectionSnapshot(val intentVersion: ULong, val graphToken: String, val requested: Map<String, String>, val route: RouteSnapshot,
    val groups: Map<String, StartupSelectionGroup>, val consumed: Map<String, StartupSelectionConsumption> = emptyMap())
class StartupSelectionUnsupported(val group: String, val reason: String) :
    IOException("Startup selection is unsupported for this group: $reason")

internal fun decodeStartupSelection(source: String): StartupSelectionSnapshot {
    val root = JSONObject(source)
    require((root.keys().asSequence().toSet() - "startup-consumption") == setOf("graph-token", "selection-intent", "requested", "route", "groups")) { "Invalid startup selection receipt fields" }
    fun strings(row: JSONObject): Map<String, String> = row.keys().asSequence().associateWith { key ->
        (row.get(key) as? String) ?: throw IOException("Invalid startup selection string")
    }
    val route = decodeRouteSnapshot(root.getJSONObject("route").toString())
    val groups = root.getJSONObject("groups")
    val decoded = groups.keys().asSequence().associateWith { name ->
        val row = groups.getJSONObject(name)
        require(row.keys().asSequence().toSet() == setOf("type", "current", "fixed", "members")) { "Invalid selection group fields" }
        val type = row.getString("type")
        val current = row.getString("current")
        val fixed = if (row.isNull("fixed")) null else row.get("fixed") as? String
            ?: throw IOException("Invalid selection fixed state")
        val members = row.getJSONArray("members").let { values -> (0 until values.length()).map { values.get(it) as? String
            ?: throw IOException("Invalid selection member") } }
        require(members.isNotEmpty() && current in members && route.picks[name] == current) { "Actual selection and route disagree" }
        StartupSelectionGroup(type, current, fixed, members)
    }
    val intentText = root.getString("selection-intent")
    val intent = intentText.toULongOrNull()?.takeIf { it.toString() == intentText }
        ?: throw IOException("Invalid startup selection intent")
    val graphToken = root.getString("graph-token")
    require(graphToken.toULongOrNull()?.let { it > 0uL && it.toString() == graphToken } == true) { "Invalid startup graph token" }
    val consumed = root.optJSONObject("startup-consumption")?.let { values ->
        values.keys().asSequence().associateWith { name ->
            val row = values.getJSONObject(name)
            require(row.keys().asSequence().toSet() == setOf("selected", "fixedBefore", "fixedAfter")) { "Invalid startup consumption" }
            StartupSelectionConsumption(row.getString("selected"), row.getString("fixedBefore"), row.getString("fixedAfter"))
        }
    } ?: if (root.has("startup-consumption")) throw IOException("Invalid startup consumption") else emptyMap()
    return StartupSelectionSnapshot(intent, graphToken, strings(root.getJSONObject("requested")), route, decoded, consumed)
}
