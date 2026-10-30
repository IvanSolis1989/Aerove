package dev.aerove.lite.data

import java.net.URI

/** A download borrows only its reachable candidate graph; unavailable providers are dependencies, never DIRECT. */
internal object ProviderBootstrapGraph {
    private fun fail(path: String, reason: String): Nothing = throw ConfigValidationException(listOf(ConfigIssue(path, reason)))

    private class Dependencies(private val root: Map<String, Any?>) {
        val groups = (root["proxy-groups"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.associateBy { it["name"] as String }.orEmpty()
        val nodes = (root["proxies"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.associateBy { it["name"] as String }.orEmpty()
        val providers = root["proxy-providers"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val names = linkedSetOf<String>()
        val providerNames = linkedSetOf<String>()
        private val visiting = linkedSetOf<String>()
        fun visit(name: String) {
            if (name in setOf("DIRECT", "REJECT")) return
            if (name in visiting) fail("$.provider-download.proxy", "Bootstrap outbound cycle: ${(visiting + name).joinToString(" -> ")}")
            if (!names.add(name)) return
            visiting.add(name)
            val group = groups[name]
            if (group != null) {
                if (group["include-all-proxies"] == true) nodes.keys.forEach(::visit)
                if (group["include-all"] == true || group["include-all-providers"] == true)
                    providers.keys.forEach { providerNames.add(it as String) }
                else (group["use"] as? List<*>)?.filterIsInstance<String>()?.forEach { providerNames.add(it) }
                (group["proxies"] as? List<*>)?.filterIsInstance<String>()?.forEach(::visit)
            }
            (nodes[name]?.get("dialer-proxy") as? String)?.let(::visit)
            visiting.remove(name)
        }
        fun capture(proxy: String?) {
            // Matches Native first_named_proxy: absence does not imply an explicit DIRECT override.
            (proxy ?: (root["proxies"] as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("name") as? String })?.let(::visit)
            fun server(value: Any?) {
                val reference = (value as? String)?.let { runCatching { URI(it).rawFragment }.getOrNull() }
                    ?.split('&')?.firstOrNull { it.startsWith("proxy=") || '=' !in it }?.removePrefix("proxy=")
                if (!reference.isNullOrEmpty()) visit(reference)
            }
            val dns = root["dns"] as? Map<*, *>
            listOf("nameserver", "fallback", "default-nameserver", "proxy-server-nameserver").forEach { key -> (dns?.get(key) as? List<*>)?.forEach(::server) }
            (dns?.get("nameserver-policy") as? Map<*, *>)?.values?.forEach(::server)
        }
    }

    fun orderedProviders(root: Map<String, Any?>, providers: Map<*, *>): List<Map.Entry<*, *>> {
        val ordered = mutableListOf<Map.Entry<*, *>>()
        val complete = mutableSetOf<String>(); val visiting = linkedSetOf<String>()
        fun visit(name: String) {
            if (name in visiting) fail("$.proxy-providers.$name.proxy", "HTTP provider bootstrap dependency cycle: ${(visiting + name).joinToString(" -> ")}")
            if (name in complete) return
            val entry = providers.entries.firstOrNull { it.key == name }
                ?: fail("$.proxy-providers.$name", "Unknown bootstrap provider dependency")
            visiting.add(name)
            val row = entry.value as Map<*, *>
            if (row["type"] == "http") {
                val dependencies = Dependencies(root).apply { capture(row["proxy"] as? String) }
                dependencies.providerNames.forEach(::visit)
            }
            visiting.remove(name); complete.add(name); ordered.add(entry)
        }
        fun priority(name: String): Int {
            val row = providers[name] as Map<*, *>
            if (row["type"] != "http") return 0
            val route = row["proxy"] as? String
            if (route in setOf("DIRECT", "REJECT") || route == null && (root["proxies"] as? List<*>).isNullOrEmpty()) return 1
            val graph = Dependencies(root)
            return if (route in graph.nodes || route in graph.groups) 2 else 3
        }
        providers.keys.map { it as String }.sortedBy(::priority).forEach(::visit)
        return ordered
    }

    fun capture(root: Map<String, Any?>, proxy: String?): MutableMap<String, Any?> {
        val copied = ConfigYaml.parse(ConfigYaml.dump(root))
        val dependencies = Dependencies(copied).apply { capture(proxy) }
        val unsealed = dependencies.providers.filterValues { (it as Map<*, *>)["type"] != "file" }.keys
        if (unsealed.isEmpty()) return copied
        dependencies.providerNames.firstOrNull { it in unsealed }?.let {
            fail("$.proxy-providers.$it", "Resource transport depends on an unsealed provider; its bootstrap dependency must complete first")
        }
        // Unknown names may be nodes supplied by a materialized file provider. Native resolves them and reports errors.
        copied["proxy-providers"] = dependencies.providers.filterKeys { it !in unsealed }
        copied["proxy-groups"] = dependencies.groups.filterKeys { it in dependencies.names }.values.toList()
        val keptNodes = dependencies.nodes.filterKeys { it in dependencies.names }.values.toList()
        copied["proxies"] = keptNodes
        return copied
    }
}
