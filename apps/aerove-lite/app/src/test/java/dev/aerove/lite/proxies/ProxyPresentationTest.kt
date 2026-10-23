package dev.aerove.lite.proxies

import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import org.junit.Assert.*
import org.junit.Test

class ProxyPresentationTest {
    @Test fun preferencesRoundTripAndLegacyNamesHaveStandardDefaults() {
        val value = ProxyPresentationPreferences(type = ProxyPresentationType.tab, sort = ProxyPresentationSort.delay,
            layout = ProxyPresentationLayout.tight, iconStyle = ProxyPresentationIcon.hidden, cardType = ProxyPresentationCard.compact,
            hideTimeoutProxies = true, testUrl = "https://example.com/probe", timeoutMillis = 12000)
        assertEquals(value, decodeProxyPresentation(encodeProxyPresentation(value)))
        assertEquals(8000, decodeProxyPresentation(null).timeoutMillis)
        val legacy = org.json.JSONObject().put("iconStyle", "icon").put("cardType", "min")
        assertEquals(ProxyPresentationIcon.plain, decodeProxyPresentation(legacy).iconStyle)
        assertEquals(ProxyPresentationCard.compact, decodeProxyPresentation(legacy).cardType)
        listOf("type", "sort", "layout", "iconStyle", "cardType", "hideTimeoutProxies", "testUrl", "timeoutMillis").forEach { key ->
            try { decodeProxyPresentation(org.json.JSONObject().put(key, org.json.JSONObject.NULL)); fail("Accepted null $key") }
            catch (_: IllegalArgumentException) { }
            try { decodeProxyPresentation(org.json.JSONObject().put(key, "invalid-value")); fail("Accepted invalid $key") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun searchRunsBeforeHideTimeoutAndKeepsSelectedAndAllTimedOutMatches() {
        val groups = listOf(ProxyGroup("Select", "Selector", "A", listOf(ProxyNode("A", "Shadowsocks"), ProxyNode("B", "Vmess"))))
        val prefs = ProxyPresentationPreferences(hideTimeoutProxies = true)
        val delays = mapOf(ProxyDelayTarget("A", prefs.testUrl) to -1, ProxyDelayTarget("B", prefs.testUrl) to -1)
        assertEquals(listOf("A"), projectProxyGroups(groups, prefs, delays = delays).single().nodes.map { it.name })
        assertEquals(listOf("B"), projectProxyGroups(groups, prefs, "b vmess", delays).single().nodes.map { it.name })
        assertTrue(projectProxyGroups(groups, prefs, "no match", delays).isEmpty())
        assertEquals(listOf("A", "B"), projectProxyGroups(listOf(groups.single().copy(selected = "")), prefs,
            delays = delays + (ProxyDelayTarget("B", prefs.testUrl) to 10)).single().nodes.map { it.name })
    }
    @Test fun delayTargetsResolveNestedSelectionAndUrlAndDeduplicateAcrossGroups() {
        val groups = listOf(ProxyGroup("Top", "Selector", "Nested", listOf(ProxyNode("Nested", "Selector"), ProxyNode("Leaf", "Vmess"))),
            ProxyGroup("Nested", "Selector", "Leaf", listOf(ProxyNode("Leaf", "Vmess"))))
        val targets = proxyDelayTargets(groups, ProxyPresentationPreferences(), groupUrls = mapOf("Nested" to "https://example.com/check"))
        assertTrue(targets.contains(ProxyDelayTarget("Leaf", "https://example.com/check")))
        assertEquals(targets.distinct(), targets)
        assertNull(resolveProxyDelayTarget("Top", listOf(groups.first().copy(selected = "Top")), emptyMap(), "https://example.com"))
    }
}
