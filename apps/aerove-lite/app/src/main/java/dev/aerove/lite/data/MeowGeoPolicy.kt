package dev.aerove.lite.data

import dev.aerove.lite.resources.GeoResourceKind
import dev.aerove.lite.resources.GeoResourcePreferences

/** Host preferences are read from actual Meow source values; Core scheduling stays disabled. */
internal object MeowGeoPolicy {
    fun read(root: Map<String, Any?>, baseline: GeoResourcePreferences): GeoResourcePreferences {
        val value = root["geodata"] ?: emptyMap<String,Any?>()
        val geo = value as? Map<*, *> ?: fail("$.geodata", "Expected an object")
        val enabled = if (geo.containsKey("auto-update")) geo["auto-update"] as? Boolean
            ?: fail("$.geodata.auto-update", "Expected a Boolean Host maintenance policy") else baseline.autoUpdate
        val interval = if (geo.containsKey("auto-update-interval")) {
            val number = geo["auto-update-interval"] as? Number ?: fail("$.geodata.auto-update-interval", "Expected whole hours")
            if (number.toDouble() != number.toLong().toDouble() || number.toLong() !in 1..87600)
                fail("$.geodata.auto-update-interval", "Expected 1..87600 whole hours")
            number.toInt()
        } else baseline.intervalHours
        val urls = baseline.urls.toMutableMap()
        if (geo.containsKey("url")) {
            val rows = geo["url"] as? Map<*, *> ?: fail("$.geodata.url", "Expected an object")
            rows.forEach { (key, url) ->
                val kind = GeoResourceKind.entries.firstOrNull { it.wire == key }
                    ?: fail("$.geodata.url.$key", "No equivalent Meow geo format")
                if (url !is String) fail("$.geodata.url.$key", "Expected an HTTP(S) URL")
                try { dev.aerove.lite.data.SubscriptionDownloader.parseUrl(url) }
                catch (_: Exception) { fail("$.geodata.url.$key", "Expected an HTTP(S) URL") }
                urls[kind] = url
            }
        }
        for (kind in GeoResourceKind.entries) {
            val key = "${kind.sourceKey}-download-url"
            if (!root.containsKey(key)) continue
            val url = root[key] as? String ?: fail("$.$key", "Expected an HTTP(S) URL")
            try { SubscriptionDownloader.parseUrl(url) } catch (_: Exception) { fail("$.$key", "Expected an HTTP(S) URL") }
            if ((geo["url"] as? Map<*, *>)?.containsKey(kind.wire) == true && urls[kind] != url)
                fail("$.geodata.url.${kind.wire}", "Conflicts with $key")
            urls[kind] = url
        }
        return GeoResourcePreferences(enabled,interval,urls.toMap()).validate()
    }

    fun project(root: MutableMap<String,Any?>, preferences: GeoResourcePreferences) {
        preferences.validate()
        @Suppress("UNCHECKED_CAST")
        val geo = (root["geodata"] as? Map<String,Any?>)?.toMutableMap() ?: linkedMapOf()
        geo["auto-update"] = preferences.autoUpdate
        geo["auto-update-interval"] = preferences.intervalHours
        geo["url"] = GeoResourceKind.entries.associate { it.wire to preferences.urls.getValue(it) }
        root["geodata"] = geo
    }
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path,reason)))
}
