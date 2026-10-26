package dev.aerove.lite.proxies

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.data.ConfigYaml
import java.util.Locale
import java.util.IdentityHashMap

data class ProxyDelayTarget(val proxy: String, val url: String)

fun proxyGroupTestUrls(source: String): Map<String, String> =
    (ConfigYaml.parse(source)["proxy-groups"] as? List<*>)?.mapNotNull { value ->
        val group = value as? Map<*, *> ?: return@mapNotNull null
        val name = group["name"] as? String ?: return@mapNotNull null
        val url = (group["url"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        name to url
    }?.toMap().orEmpty()

fun resolveProxyDelayTarget(name: String, groups: List<ProxyGroup>, groupUrls: Map<String, String>, fallback: String): ProxyDelayTarget? {
    return resolveProxyDelayTarget(name, groups.associateBy { it.name }, groupUrls, fallback)
}

internal fun resolveProxyDelayTarget(name: String, byName: Map<String, ProxyGroup>, groupUrls: Map<String, String>, fallback: String): ProxyDelayTarget? {
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

private val proxyQueryWhitespace = Regex("\\s+")

internal fun proxySearch(query: String): (ProxyNode) -> Boolean {
    val terms = query.lowercase(Locale.ROOT).split(proxyQueryWhitespace).filter(String::isNotEmpty)
    return { node ->
        val text = "${node.name}\n${node.type}".lowercase(Locale.ROOT)
        terms.all(text::contains)
    }
}

fun proxySearchMatches(node: ProxyNode, query: String): Boolean = proxySearch(query)(node)

fun projectProxyGroups(
    groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences, query: String = "",
    delays: Map<ProxyDelayTarget, Int> = emptyMap(), groupUrls: Map<String, String> = emptyMap(),
): List<ProxyGroup> {
    val byName = groups.associateBy { it.name }
    val groupNames = byName.keys
    val matches = proxySearch(query)
    val unprobeable = setOf("Reject", "RejectDrop", "Pass", "PassRule", "Rematch", "Compatible", "Dns")
    return groups.mapNotNull { group ->
        val memo = IdentityHashMap<ProxyNode, Int?>()
        fun delay(node: ProxyNode): Int? {
            if (memo.containsKey(node)) return memo[node]
            val key = resolveProxyDelayTarget(node.name, byName, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
            val value = key?.let(delays::get) ?: node.delayMillis
            memo[node] = value
            return value
        }
        val matching = group.nodes.filter(matches)
        if (matching.isEmpty() && query.isNotBlank()) return@mapNotNull null
        val selectedName = group.selected.ifEmpty { group.nodes.firstOrNull()?.name.orEmpty() }
        val visible = if (!preferences.hideTimeoutProxies) matching else matching.filter { node ->
            node.name == selectedName || node.name in groupNames || node.type in unprobeable || (delay(node) ?: 1) > 0
        }.ifEmpty { matching }
        val sorted = when (preferences.sort) {
            ProxyPresentationSort.none -> visible
            ProxyPresentationSort.byName -> visible.sortedBy { it.name }
            ProxyPresentationSort.delay -> visible.sortedWith(compareBy<ProxyNode> {
                val ms = delay(it)
                when { ms == null || ms == 0 -> 1; ms == -1 -> 2; ms < 0 -> 3; else -> 0 }
            }.thenBy { delay(it) ?: 0 }.thenBy { if (it.name in groupNames) 0 else 1 })
        }
        group.copy(nodes = sorted.map { node -> node.copy(delayMillis = delay(node)) })
    }
}

/** Scope is the searched group before timeout filtering, matching Standard's page-group test. */
fun proxyDelayTargets(groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences,
    groupName: String? = null, query: String = "", groupUrls: Map<String, String> = emptyMap()): List<ProxyDelayTarget> {
    val byName = groups.associateBy { it.name }
    val matches = proxySearch(query)
    return groups.filter { groupName == null || it.name == groupName }.flatMap {
        proxyDelayTargets(it, byName, preferences, groupUrls, matches)
    }.distinct()
}

internal fun proxyDelayTargets(group: ProxyGroup, byName: Map<String, ProxyGroup>,
    preferences: ProxyPresentationPreferences, groupUrls: Map<String, String>,
    matches: (ProxyNode) -> Boolean): List<ProxyDelayTarget> = group.nodes.filter(matches).mapNotNull {
    resolveProxyDelayTarget(it.name, byName, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
}.distinct()
