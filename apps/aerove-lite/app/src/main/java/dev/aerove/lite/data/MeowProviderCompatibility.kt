package dev.aerove.lite.data

import org.json.JSONObject

internal object MeowUnsupportedOutbounds {
    const val CORE = "meow-rs/0.26.1/727ddcd06f592e2f12c5222c4de6cce328df808c"
    const val FILTER = "meow-provider-outbounds-v1"
    val types: Set<String> = setOf("tuic", "ssh", "wireguard", "tailscale", "shadowquic", "mieru")
}

internal data class MeowProviderCompatibility(val sourceSha256: String, val derivedSha256: String,
    val retained: Int, val removedByType: Map<String, Int>) {
    fun json(): JSONObject = JSONObject().put("sourceSha256", sourceSha256).put("derivedSha256", derivedSha256)
        .put("coreIdentity", MeowUnsupportedOutbounds.CORE).put("filterIdentity", MeowUnsupportedOutbounds.FILTER)
        .put("retained", retained).put("removedByType", JSONObject(removedByType))
}

internal data class MeowProviderProjection(val payload: MutableMap<String, Any?>, val retained: Int,
    val removedByType: Map<String, Int>) {
    companion object {
        fun prepare(source: String): MeowProviderProjection {
            val root = ConfigYaml.parse(source)
            if (root.keys != setOf("proxies") || root["proxies"] !is List<*>)
                throw ConfigValidationException(listOf(ConfigIssue("$.proxies", "节点资源必须只包含 proxies 列表")))
            val removed = sortedMapOf<String, Int>()
            val retained = (root["proxies"] as List<*>).filter { value ->
                val type = (value as? Map<*, *>)?.get("type")
                if (type is String && type in MeowUnsupportedOutbounds.types) {
                    removed[type] = (removed[type] ?: 0) + 1
                    false
                } else true
            }
            if (retained.isEmpty()) throw ConfigValidationException(listOf(ConfigIssue("$.proxies",
                "清洗后没有可用节点；当前固定核心不支持的类型：${removed.keys.joinToString()}")))
            root["proxies"] = retained
            return MeowProviderProjection(root, retained.size, removed.toMap())
        }
    }
}
