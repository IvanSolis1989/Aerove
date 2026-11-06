package dev.aerove.lite.data

import org.json.JSONObject

internal data class ProviderManualOverride(val sourceOwnerSha256: String, val definitionSha256: String,
    val baselineSourceSha256: String) {
    init {
        require(listOf(sourceOwnerSha256, definitionSha256, baselineSourceSha256).all { it.matches(Regex("[0-9a-f]{64}")) })
    }
    fun json(): JSONObject = JSONObject().put("sourceOwnerSha256", sourceOwnerSha256)
        .put("definitionSha256", definitionSha256).put("baselineSourceSha256", baselineSourceSha256)
    companion object {
        fun parse(row: JSONObject): ProviderManualOverride = ProviderManualOverride(row.getString("sourceOwnerSha256"),
            row.getString("definitionSha256"), row.getString("baselineSourceSha256"))
    }
}
