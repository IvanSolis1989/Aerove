package dev.aerove.lite.proxies

import dev.aerove.lite.data.SubscriptionDownloader
import org.json.JSONObject

enum class ProxyPresentationType { tab, list }
enum class ProxyPresentationSort { none, delay, byName }
enum class ProxyPresentationLayout { loose, standard, tight }
enum class ProxyPresentationIcon { filled, plain, hidden }
enum class ProxyPresentationCard { loose, standard, compact }

data class ProxyPresentationPreferences(
    val type: ProxyPresentationType = ProxyPresentationType.list,
    val sort: ProxyPresentationSort = ProxyPresentationSort.none,
    val layout: ProxyPresentationLayout = ProxyPresentationLayout.standard,
    val iconStyle: ProxyPresentationIcon = ProxyPresentationIcon.filled,
    val cardType: ProxyPresentationCard = ProxyPresentationCard.standard,
    val hideTimeoutProxies: Boolean = false,
    val testUrl: String = DEFAULT_TEST_URL,
    val timeoutMillis: Int = 8000,
) {
    fun validate(): ProxyPresentationPreferences = apply {
        SubscriptionDownloader.parseUrl(testUrl)
        require(timeoutMillis in 1..60000) { "Invalid delay test timeout" }
    }
    companion object { const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204" }
}

fun encodeProxyPresentation(value: ProxyPresentationPreferences): JSONObject = value.validate().let {
    JSONObject().put("type", it.type.name).put("sort", if (it.sort == ProxyPresentationSort.byName) "name" else it.sort.name).put("layout", it.layout.name)
        .put("iconStyle", it.iconStyle.name).put("cardType", it.cardType.name).put("hideTimeoutProxies", it.hideTimeoutProxies)
        .put("testUrl", it.testUrl).put("timeoutMillis", it.timeoutMillis)
}

fun decodeProxyPresentation(row: JSONObject?): ProxyPresentationPreferences {
    if (row == null) return ProxyPresentationPreferences()
    fun <T : Enum<T>> choice(key: String, entries: List<T>, default: T): T {
        if (!row.has(key)) return default
        return entries.find { it.name == row.get(key) } ?: throw IllegalArgumentException("Invalid proxy preference $key")
    }
    fun text(key: String, default: String): String = if (!row.has(key)) default
        else row.get(key) as? String ?: throw IllegalArgumentException("Invalid proxy preference $key")
    val hidden = if (!row.has("hideTimeoutProxies")) false
        else row.get("hideTimeoutProxies") as? Boolean ?: throw IllegalArgumentException("Invalid proxy hide-timeout preference")
    val timeout = if (!row.has("timeoutMillis")) 8000
        else (row.get("timeoutMillis") as? Number)?.toString()?.toIntOrNull() ?: throw IllegalArgumentException("Invalid delay timeout")
    val icon = when (val wire = text("iconStyle", "filled")) { "standard" -> "filled"; "icon" -> "plain"; "none" -> "hidden"; else -> wire }
    val card = when (val wire = text("cardType", "standard")) { "expand" -> "loose"; "shrink" -> "standard"; "min" -> "compact"; else -> wire }
    return ProxyPresentationPreferences(
        choice("type", ProxyPresentationType.entries, ProxyPresentationType.list),
        when (text("sort", "none")) { "none" -> ProxyPresentationSort.none; "delay" -> ProxyPresentationSort.delay; "name" -> ProxyPresentationSort.byName; else -> throw IllegalArgumentException("Invalid proxy sort") },
        choice("layout", ProxyPresentationLayout.entries, ProxyPresentationLayout.standard),
        ProxyPresentationIcon.entries.find { it.name == icon } ?: throw IllegalArgumentException("Invalid proxy icon style"),
        ProxyPresentationCard.entries.find { it.name == card } ?: throw IllegalArgumentException("Invalid proxy card size"),
        hidden, text("testUrl", ProxyPresentationPreferences.DEFAULT_TEST_URL), timeout,
    ).validate()
}
