package dev.aerove.lite.proxies

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** Frozen pre-optimization e456 resolver/projection is the differential oracle. */
class ProxyProjectionDifferentialTest {
    @Test fun indexedProjectionPreservesDuplicateCycleTimeoutAndSearchSemantics() {
        repeat(40) { seed ->
            val random=Random(seed)
            val names=listOf("A","B","ALPHA","a beta","组一","G0","G1","G2","")
            val nodes=List(24){ProxyNode(names[random.nextInt(names.size)],listOf("Direct","Reject","Dns","Shadowsocks")[random.nextInt(4)],listOf(null,-5,-1,0,1,40,200)[random.nextInt(7)])}
            val groups=List(5){ index->ProxyGroup("G${index%3}","Selector",names[random.nextInt(names.size)],List(12){nodes[random.nextInt(nodes.size)]}) }
            val urls=mapOf("G0" to "https://zero.example/", "G1" to "https://one.example/")
            val delays=nodes.mapNotNull { legacyResolve(it.name,groups,urls,"https://fallback.example/") }.associateWith{listOf(-1,0,30,90)[random.nextInt(4)]}
            for(sort in ProxyPresentationSort.entries) for(hidden in listOf(false,true)) for(query in listOf("","a","ALPHA","a  beta","reject","组")) {
                val preferences=ProxyPresentationPreferences(sort=sort,hideTimeoutProxies=hidden,testUrl="https://fallback.example/")
                assertEquals("seed=$seed sort=$sort hidden=$hidden query=$query",legacyProject(groups,preferences,query,delays,urls),projectProxyGroups(groups,preferences,query,delays,urls))
            }
        }
    }
}

fun legacyResolve(name: String, groups: List<ProxyGroup>, groupUrls: Map<String, String>, fallback: String): ProxyDelayTarget? {
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

fun legacySearch(node: ProxyNode, query: String): Boolean {
    val text = "${node.name}\n${node.type}".lowercase(Locale.ROOT)
    return query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty).all(text::contains)
}

fun legacyProject(
    groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences, query: String = "",
    delays: Map<ProxyDelayTarget, Int> = emptyMap(), groupUrls: Map<String, String> = emptyMap(),
): List<ProxyGroup> {
    val groupNames = groups.map { it.name }.toSet()
    val unprobeable = setOf("Reject", "RejectDrop", "Pass", "PassRule", "Rematch", "Compatible", "Dns")
    fun delay(group: ProxyGroup, node: ProxyNode): Int? {
        val key = legacyResolve(node.name, groups, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
        return key?.let(delays::get) ?: node.delayMillis
    }
    return groups.mapNotNull { group ->
        val matching = group.nodes.filter { legacySearch(it, query) }
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
