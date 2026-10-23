package dev.aerove.lite.navigation

import org.junit.Assert.*
import org.junit.Test

class NavigationPresentationTest {
    @Test fun authoritativeTimingsAndStableWireNames() {
        assertEquals("slide", TabAnimation.Slide.wireName)
        assertEquals("fade", TabAnimation.Fade.wireName)
        assertEquals(300, tabDurationMillis(TabAnimation.Slide))
        assertEquals(220, tabDurationMillis(TabAnimation.Fade))
    }
    @Test fun floatingInsetsIncludeLinearTextScaleAndSafeAreaExactlyOnce() {
        assertEquals(83f, floatingNavigationInset(1f, 0f), 0f)
        assertEquals(88f, floatingNavigationInset(1.5f, 0f), 0f)
        assertEquals(106f, floatingNavigationInset(2f, 34f), 0f)
        assertEquals(114f, dockedNavigationInset(34f), 0f)
    }
    @Test fun fadeCancelsSpatialTranslationWhileSlideKeepsBothScenesAdjacent() {
        assertEquals(TabSceneFrame(0f, .25f), tabSceneFrame(TabAnimation.Fade, true, -1, .25f))
        assertEquals(TabSceneFrame(0f, .75f), tabSceneFrame(TabAnimation.Fade, false, -1, .25f))
        assertEquals(TabSceneFrame(-.75f, 1f), tabSceneFrame(TabAnimation.Slide, true, -1, .25f))
        assertEquals(TabSceneFrame(.25f, 1f), tabSceneFrame(TabAnimation.Slide, false, -1, .25f))
    }
    @Test fun rapidRedirectionUsesLatestVisibleSceneWithoutVisitingIntermediateTabs() {
        val tabs = listOf("home", "proxies", "profiles", "tools")
        assertEquals("home", retargetTab("home", "tools", .49f, tabs))
        assertEquals("tools", retargetTab("home", "tools", .51f, tabs))
        assertEquals("tools", retargetTab("home", "tools", .5f, tabs))
        assertEquals("tools", retargetTab("tools", "home", .5f, tabs))
    }
}
