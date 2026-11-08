package dev.aerove.lite.data

import org.json.JSONObject

internal object MeowUnsupportedOutbounds {
    const val CORE = "meow-rs/0.26.1/727ddcd06f592e2f12c5222c4de6cce328df808c"
    const val FILTER = "meow-provider-outbounds-v2"
    val readableFilters = setOf("meow-provider-outbounds-v1", FILTER)
    val types: Set<String> = setOf("tuic", "ssh", "wireguard", "tailscale", "shadowquic", "mieru")
}

internal data class MeowProviderCompatibility(val sourceSha256: String, val derivedSha256: String,
    val retained: Int, val removedByType: Map<String, Int>, val removedByReason: Map<String, Int> = emptyMap()) {
    fun json(): JSONObject = JSONObject().put("sourceSha256", sourceSha256).put("derivedSha256", derivedSha256)
        .put("coreIdentity", MeowUnsupportedOutbounds.CORE).put("filterIdentity", MeowUnsupportedOutbounds.FILTER)
        .put("retained", retained).put("removedByType", JSONObject(removedByType))
        .put("removedByReason", JSONObject(removedByReason))
}

internal data class MeowProviderProjection(val payload: MutableMap<String, Any?>, val retained: Int,
    val removedByType: Map<String, Int>, val removedByReason: Map<String, Int> = emptyMap()) {
    companion object {
        private fun legacyVmess(node: Map<*, *>?): Boolean {
            if (node?.get("type") != "vmess") return false
            fun integer(v: Any?) = v is Byte || v is Short || v is Int || v is Long
            if (node.containsKey("alterId") && node.containsKey("alter-id") && (!integer(node["alterId"]) || !integer(node["alter-id"]) || node["alterId"] != node["alter-id"])) return false
            val value = if (node.containsKey("alterId")) node["alterId"] else node["alter-id"]
            return (value is Byte || value is Short || value is Int || value is Long) && (value as Number).toLong() > 0
        }
        fun prepare(source: String): MeowProviderProjection {
            val root = ConfigYaml.parse(source)
            if (root.keys != setOf("proxies") || root["proxies"] !is List<*>)
                throw ConfigValidationException(listOf(ConfigIssue("$.proxies", "节点资源必须只包含 proxies 列表")))
            val removed = sortedMapOf<String, Int>()
            val reasons = sortedMapOf<String, Int>()
            val removedNames = mutableSetOf<String>()
            val retained = (root["proxies"] as List<*>).filter { value ->
                val type = (value as? Map<*, *>)?.get("type")
                val legacy = legacyVmess(value as? Map<*, *>)
                if (type is String && (type in MeowUnsupportedOutbounds.types || legacy)) {
                    ((value as? Map<*, *>)?.get("name") as? String)?.let { removedNames.add(it) }
                    if (legacy) reasons["vmessLegacyAlterId"] = (reasons["vmessLegacyAlterId"] ?: 0) + 1
                    removed[type] = (removed[type] ?: 0) + 1
                    false
                } else true
            }
            if (retained.isEmpty()) throw ConfigValidationException(listOf(ConfigIssue("$.proxies",
                "清洗后没有可用节点；当前固定核心不支持的类型或能力：${removed.keys.joinToString { if (it == "vmess" && reasons.containsKey("vmessLegacyAlterId")) "VMess alterId > 0 (legacy)" else it }}")))
            removedNames.removeAll(retained.mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet())
            retained.forEachIndexed { index, node ->
                if ((node as? Map<*, *>)?.get("dialer-proxy") in removedNames)
                    throw ConfigValidationException(listOf(ConfigIssue("$.proxies[$index].dialer-proxy", "Dialer references a filtered node; no fallback is permitted")))
            }
            root["proxies"] = retained
            return MeowProviderProjection(root, retained.size, removed.toMap(), reasons.toMap())
        }
    }
}
