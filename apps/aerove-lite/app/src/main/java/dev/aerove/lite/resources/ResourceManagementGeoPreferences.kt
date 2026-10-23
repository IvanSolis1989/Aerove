package dev.aerove.lite.resources

import dev.aerove.lite.data.SubscriptionDownloader
import org.json.JSONObject

enum class GeoResourceKind(val wire: String, val sourceKey: String, val assetKind: String, val defaultUrl: String) {
    MMDB("mmdb", "mmdb", "mmdb", "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb"),
    ASN("asn", "asn-mmdb", "mmdb", "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb"),
    GEOSITE("geosite", "geosite", "geosite", "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat"),
}

data class GeoResourcePreferences(
    val autoUpdate: Boolean = false,
    val intervalHours: Int = 24,
    val urls: Map<GeoResourceKind, String> = GeoResourceKind.entries.associateWith { it.defaultUrl },
) {
    fun validate(): GeoResourcePreferences = apply {
        require(intervalHours > 0) { "Resource update interval must be positive" }
        require(urls.keys == GeoResourceKind.entries.toSet()) { "Geo resource URL set is incomplete" }
        urls.values.forEach(SubscriptionDownloader::parseUrl)
    }
    fun withUrl(kind: GeoResourceKind, url: String): GeoResourcePreferences = copy(urls = urls + (kind to url)).validate()
}

object GeoResourcePreferencesJson {
    fun encode(value: GeoResourcePreferences): JSONObject = value.validate().let {
        JSONObject().put("autoUpdate", it.autoUpdate).put("intervalHours", it.intervalHours)
            .put("urls", JSONObject().apply { it.urls.forEach { (kind, url) -> put(kind.wire, url) } })
    }
    fun decode(row: JSONObject?): GeoResourcePreferences {
        if (row == null) return GeoResourcePreferences()
        val enabled = if (!row.has("autoUpdate")) false else row.get("autoUpdate") as? Boolean
            ?: throw IllegalArgumentException("Invalid resource automatic update flag")
        val interval = if (!row.has("intervalHours")) 24 else (row.get("intervalHours") as? Number)?.toString()?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid resource update interval")
        val defaults = GeoResourcePreferences()
        val urls = if (!row.has("urls")) defaults.urls else {
            val values = row.get("urls") as? JSONObject ?: throw IllegalArgumentException("Invalid geo resource URLs")
            require(values.keys().asSequence().all { key -> GeoResourceKind.entries.any { it.wire == key } }) { "Unsupported geo resource URL field" }
            GeoResourceKind.entries.associateWith { kind ->
                if (!values.has(kind.wire)) kind.defaultUrl else values.get(kind.wire) as? String
                    ?: throw IllegalArgumentException("Invalid geo resource URL")
            }
        }
        return GeoResourcePreferences(enabled, interval, urls).validate()
    }
}
