package dev.aerove.lite.data

import org.json.JSONObject

/** Only the trusted current start/metadata owner supplies this JNI plan identity. */
data class MeowGeoHostBinding(val serviceOwner: Long, val sessionGeneration: Long, val intentEpoch: Long,
    val compiledSha256: String, val sourceGuard: String) {
    init {
        require(serviceOwner > 0 && sessionGeneration > 0 && intentEpoch >= 0)
        require(listOf(compiledSha256, sourceGuard).all { it.matches(Regex("[0-9a-f]{64}")) })
    }
    companion object {
        fun decode(row: JSONObject): MeowGeoHostBinding {
            row.geoExact("serviceOwner","sessionGeneration","intentEpoch","compiledSha256","sourceGuard")
            fun number(key: String, positive: Boolean): Long = geoDecimal(row.geoText(key),positive).toLong()
            return MeowGeoHostBinding(number("serviceOwner",true),number("sessionGeneration",true),number("intentEpoch",false),
                row.geoText("compiledSha256"),row.geoText("sourceGuard"))
        }
    }
    fun json(): JSONObject = JSONObject().put("serviceOwner", serviceOwner.toString())
        .put("sessionGeneration", sessionGeneration.toString()).put("intentEpoch", intentEpoch.toString())
        .put("compiledSha256", compiledSha256).put("sourceGuard", sourceGuard)
}
