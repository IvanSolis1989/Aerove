package dev.aerove.lite.data

import java.io.File
import org.json.JSONObject

/** Preparation-only decoding/structure result. Pending is never config admission. */
fun interface NativePreparedRuleValidator {
    suspend fun preflight(path: File, behavior: String, format: String): RuleResourcePreflight
}

class RuleResourcePreflight private constructor(
    val requiresPreparedContext: Boolean, val count: Long,
    val geoip: Boolean, val asn: Boolean, val geosite: Boolean,
) {
    companion object {
        fun parse(json: String, expectedBehavior: String, expectedFormat: String): RuleResourcePreflight {
            require(json.toByteArray(Charsets.UTF_8).size <= 16384) { "Oversized resource preflight response" }
            val root = JSONObject(json)
            fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()
            require(keys(root) == setOf("state", "format", "behavior", "count", "requires")) { "Invalid resource preflight schema" }
            require(root.get("format") == expectedFormat && root.get("behavior") == expectedBehavior) { "Resource preflight identity mismatch" }
            val count = root.get("count")
            require((count is Int || count is Long) && (count as Number).toLong() >= 0) { "Invalid resource preflight count" }
            val needs = root.getJSONObject("requires")
            require(keys(needs) == setOf("geoip", "asn", "geosite")) { "Invalid resource preflight dependencies" }
            fun flag(key: String): Boolean {
                val value = needs.get(key)
                require(value is Boolean) { "Invalid resource preflight dependency $key" }
                return value
            }
            val geoip = flag("geoip"); val asn = flag("asn"); val geosite = flag("geosite")
            val pending = when (root.get("state")) {
                "validated" -> false
                "requiresPreparedContext" -> true
                else -> error("Unknown resource preflight state")
            }
            require(pending == (geoip || asn || geosite)) { "Inconsistent resource preflight dependencies" }
            return RuleResourcePreflight(pending, (count as Number).toLong(), geoip, asn, geosite)
        }
    }
}
