package dev.aerove.lite.data

import java.io.File

data class ProviderDownloadContext(val candidateYaml: String, val stagingRoot: File, val resourceReadRoot: File?)
data class ProviderDownloadRequest(val url: String, val proxy: String?, val headers: Map<String, List<String>>,
    val destination: File, val maxBytes: Long)
data class ProviderDownloadResult(val bytes: Long, val sha256: String, val subscriptionUserInfo: String? = null)
fun interface ProviderDownloadFactory {
    suspend fun open(context: ProviderDownloadContext): ProviderDownloadScope
}
interface ProviderDownloadScope {
    suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult
    /** Terminal/idempotent: no return (including failure) before every admitted writer is joined. */
    suspend fun closeAndJoin()
}

internal data class ProviderFetchPolicy(val proxy: String?, val headers: Map<String, List<String>>) {
    fun identity(): String = org.json.JSONArray(listOf(proxy ?: org.json.JSONObject.NULL,
        org.json.JSONObject(headers.toSortedMap()))).toString()
    companion object {
        fun from(provider: Map<String, Any?>): ProviderFetchPolicy? {
            if (!provider.containsKey("proxy") && !provider.containsKey("header")) return null
            val proxy = provider["proxy"] as? String
            val headers = (provider["header"] as? Map<*, *>)?.entries?.associate { (name, value) ->
                name as String to if (value is String) listOf(value) else (value as List<*>).map { it as String }
            }.orEmpty()
            return ProviderFetchPolicy(proxy, headers)
        }
    }
}
