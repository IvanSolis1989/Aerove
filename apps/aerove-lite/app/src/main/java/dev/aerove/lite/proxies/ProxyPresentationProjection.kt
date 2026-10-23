package dev.aerove.lite.proxies

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.data.ConfigYaml
import java.util.Locale

data class ProxyDelayTarget(val proxy: String, val url: String)

fun proxyGroupTestUrls(source: String): Map<String, String> =
    (ConfigYaml.parse(source)["proxy-groups"] as? List<*>)?.mapNotNull { value ->
        val group = value as? Map<*, *> ?: return@mapNotNull null
        val name = group["name"] as? String ?: return@mapNotNull null
        val url = (group["url"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        name to url
    }?.toMap().orEmpty()

fun resolveProxyDelayTarget(name: String, groups: List<ProxyGroup>, groupUrls: Map<String, String>, fallback: String): ProxyDelayTarget? {
    val byName = groups.associateBy { it.name }
    val visited = mutableSetOf<String>()
    var current = name
    var url = fallback
    while (true) {
        if (!visited.add(current) || current.isEmpty()) return null
        val group = byName[current] ?: return ProxyDelayTarget(current, url)
        groupUrls[current]?.takeIf(String::isNotBlank)?.let { url = it }
        current = group.selected.ifEmpty { group.nodes.firstOrNull()?.name.orEmpty() }
    }
}

fun proxySearchMatches(node: ProxyNode, query: String): Boolean {
    val text = "${node.name}\n${node.type}".lowercase(Locale.ROOT)
    return query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty).all(text::contains)
}

fun projectProxyGroups(
    groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences, query: String = "",
    delays: Map<ProxyDelayTarget, Int> = emptyMap(), groupUrls: Map<String, String> = emptyMap(),
): List<ProxyGroup> {
    val groupNames = groups.map { it.name }.toSet()
    val unprobeable = setOf("Reject", "RejectDrop", "Pass", "PassRule", "Rematch", "Compatible", "Dns")
    fun delay(group: ProxyGroup, node: ProxyNode): Int? {
        val key = resolveProxyDelayTarget(node.name, groups, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
        return key?.let(delays::get) ?: node.delayMillis
    }
    return groups.mapNotNull { group ->
        val matching = group.nodes.filter { proxySearchMatches(it, query) }
        if (matching.isEmpty() && query.isNotBlank()) return@mapNotNull null
        val selectedName = group.selected.ifEmpty { group.nodes.firstOrNull()?.name.orEmpty() }
        val visible = if (!preferences.hideTimeoutProxies) matching else matching.filter { node ->
            node.name == selectedName || node.name in groupNames || node.type in unprobeable || (delay(group, node) ?: 1) > 0
        }.ifEmpty { matching }
        val sorted = when (preferences.sort) {
            ProxyPresentationSort.none -> visible
            ProxyPresentationSort.byName -> visible.sortedBy { it.name }
            ProxyPresentationSort.delay -> visible.sortedWith(compareBy<ProxyNode> {
                val ms = delay(group, it)
                when { ms == null || ms == 0 -> 1; ms == -1 -> 2; ms < 0 -> 3; else -> 0 }
            }.thenBy { delay(group, it) ?: 0 }.thenBy { if (it.name in groupNames) 0 else 1 })
        }
        group.copy(nodes = sorted.map { node -> node.copy(delayMillis = delay(group, node)) })
    }
}

/** Scope is the searched group before timeout filtering, matching Standard's page-group test. */
fun proxyDelayTargets(groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences,
    groupName: String? = null, query: String = "", groupUrls: Map<String, String> = emptyMap()): List<ProxyDelayTarget> =
    groups.filter { groupName == null || it.name == groupName }.flatMap { group ->
        group.nodes.filter { proxySearchMatches(it, query) }.mapNotNull {
            resolveProxyDelayTarget(it.name, groups, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
        }
    }.distinct()
