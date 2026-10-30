package dev.aerove.lite.flutter

import dev.aerove.lite.data.SubscriptionDownloader
import org.json.JSONObject

internal const val MEOW_DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

internal class MeowServiceSetup(source: String, previousTestUrl: String) {
    val json = JSONObject(source)
    val testUrl: String
    val skipCertVerify: Boolean
    val selectedMap: Map<String, String>
    init {
        val supplied = json.get("test-url") as? String ?: error("test-url: Expected a string")
        testUrl = supplied.ifEmpty { previousTestUrl }
        SubscriptionDownloader.parseUrl(testUrl)
        skipCertVerify = json.get("skip-cert-verify") as? Boolean ?: error("skip-cert-verify: Expected a boolean")
        val selections = json.getJSONObject("selected-map")
        selectedMap = selections.keys().asSequence().associateWith { name ->
            require(name.isNotBlank() && name.length <= 16384 && name.none(Char::isISOControl)) { "selected-map: Invalid group" }
            (selections.get(name) as? String ?: error("selected-map: Expected a name")).also {
                require(it.length <= 16384 && it.none(Char::isISOControl)) { "selected-map: Invalid proxy" }
            }
        }
    }
}

internal fun meowMissingGroupTestUrl(source: Map<String, Any?>): Boolean =
    (source["proxy-groups"] as? List<*>)?.any { item ->
        val row = item as? Map<*, *> ?: return@any false
        row["type"] in setOf("url-test", "fallback", "load-balance") && (row["url"] == null || row["url"] == "")
    } == true

internal fun meowProjectGroupTestUrl(source: Map<String, Any?>, testUrl: String): Map<String, Any?> {
    SubscriptionDownloader.parseUrl(testUrl)
    val groups = source["proxy-groups"] as? List<*> ?: return source
    return source + ("proxy-groups" to groups.map { item ->
        val row = item as? Map<*, *> ?: return@map item
        if (row["type"] in setOf("url-test", "fallback", "load-balance") && (row["url"] == null || row["url"] == ""))
            row + ("url" to testUrl) else row
    })
}
