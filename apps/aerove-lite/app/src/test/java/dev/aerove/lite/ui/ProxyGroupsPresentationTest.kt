package dev.aerove.lite.ui

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.proxies.*
import org.junit.Assert.*
import org.junit.Test

class ProxyGroupsPresentationTest {
    @Test fun searchLimitNeverSplitsCompoundEmojiFlagSkinToneOrCombiningCluster() {
        listOf("👨‍👩‍👧‍👦", "👩🏽‍💻", "🇨🇳", "e\u0301", "1\uFE0F\u20E3").forEach { cluster ->
            val prefix = "x".repeat(255)
            assertEquals(prefix + cluster, proxySearchText(prefix + cluster + "overflow"))
            assertEquals(cluster.repeat(256), proxySearchText(cluster.repeat(257)))
        }
        assertEquals("", proxySearchText(""))
        assertEquals("👩🏽‍💻", proxyGroupMark("👩🏽‍💻 Work"))
        assertEquals("Work", proxyGroupTitle("👩🏽‍💻 Work"))
        assertEquals("CN Group", proxyGroupTitle("🇨🇳 CN  Group"))
        assertEquals("1 Group", proxyGroupTitle("1 Group"))
        assertEquals("🄰 Group", proxyGroupTitle("🄰 Group"))
        assertEquals("©", proxyGroupMark("©Copyright"))
    }
    @Test fun anchoredChoicesKeepStandardOrderAndListOnlyIconSubmenu() {
        val prefs = ProxyPresentationPreferences()
        assertEquals(listOf(ProxyOptionSection.Style, ProxyOptionSection.Sort, ProxyOptionSection.Layout,
            ProxyOptionSection.Size, ProxyOptionSection.Icon), proxyOptionSections(prefs))
        assertEquals(listOf("tab", "list"), proxyOptionChoices(ProxyOptionSection.Style))
        assertEquals(listOf("loose", "standard", "tight"), proxyOptionChoices(ProxyOptionSection.Layout))
        val tabs = proxyOptionSelection(prefs, ProxyOptionSection.Style, "tab")
        assertFalse(proxyOptionSections(tabs).contains(ProxyOptionSection.Icon))
        assertEquals(prefs.testUrl, tabs.testUrl)
        assertEquals(prefs.timeoutMillis, tabs.timeoutMillis)
    }

    @Test fun menuSortAndTimeoutProjectionUseRealValuesAndRetainSelected() {
        val groups = listOf(ProxyGroup("Select", "Selector", "B", listOf(ProxyNode("B", "Vmess", -1),
            ProxyNode("C", "Vmess", 20), ProxyNode("A", "Vmess", 10))))
        val prefs = proxyOptionSelection(ProxyPresentationPreferences(), ProxyOptionSection.Sort, "byName")
        assertEquals(listOf("A", "B", "C"), projectProxyGroups(groups, prefs).single().nodes.map { it.name })
        val delay = proxyOptionSelection(prefs, ProxyOptionSection.Sort, "delay").copy(hideTimeoutProxies = true)
        assertEquals(listOf("A", "C", "B"), projectProxyGroups(groups, delay).single().nodes.map { it.name })
        assertEquals(listOf("C"), projectProxyGroups(groups, delay, "c vmess").single().nodes.map { it.name })
        assertEquals(groups, projectProxyGroups(groups, proxyOptionSelection(prefs, ProxyOptionSection.Sort, "none")))
    }

    @Test fun standardGridAndSelectedRowOffsetsRespectCollapsedGroups() {
        assertEquals(1, proxyPresentationColumns(328f, ProxyPresentationLayout.loose))
        assertEquals(2, proxyPresentationColumns(328f, ProxyPresentationLayout.standard))
        assertEquals(3, proxyPresentationColumns(328f, ProxyPresentationLayout.tight))
        assertEquals(4, proxyPresentationColumns(751f, ProxyPresentationLayout.standard))
        val nodes = (0..4).map { ProxyNode("N$it", "Vmess") }
        val groups = listOf(ProxyGroup("A", "Selector", "N0", nodes), ProxyGroup("B", "Selector", "N4", nodes))
        assertEquals(4, proxySelectedListIndex(groups, setOf("B"), "B", 2))
        assertEquals(7, proxySelectedListIndex(groups, setOf("A", "B"), "B", 2))
        assertNull(proxySelectedListIndex(groups, emptySet(), "B", 2))
        assertEquals("A", proxyGroupMark("alpha"))
        assertEquals("中", proxyGroupMark("中文"))
    }
}
