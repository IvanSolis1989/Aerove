package dev.aerove.lite.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ColorMode
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.navigation.TabAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

class NavigationPresentationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "navigation-${UUID.randomUUID()}")
    @After fun clean() { directory.deleteRecursively() }
    private fun tab(id: Int) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(context.getString(id)))

    @Test fun actualThemeEntrySavesAndReadsBothPreferencesThenUsesOpaqueDockedBar() {
        val state = mutableStateOf(LiteState())
        val actions = UiTestActions { settings ->
            runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).save(settings) }
            state.value = state.value.copy(settings = settings)
        }
        compose.replaceAppContent { LiteApp(state.value, actions) }
        tab(R.string.lite_tab_tools).performClick()
        compose.onNodeWithText(context.getString(R.string.lite_theme)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("navigation-style-false"))
        compose.onNodeWithTag("navigation-style-false").performClick().assertIsSelected()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("navigation-animation-slide"))
        compose.onNodeWithTag("navigation-animation-slide").performClick().assertIsSelected()
        val restored = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
        assertFalse(restored.floatingNavigationBar)
        assertEquals(TabAnimation.Slide, restored.tabAnimation)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("docked-navigation").assertIsDisplayed()
        tab(R.string.lite_tab_home).performClick().assertIsSelected()
    }

    @Test fun sharedHeaderAndSwitchMotionCancelsAndSnapsOnReducedOrPageInactive() {
        val target = mutableStateOf(0f)
        val reduced = mutableStateOf(false)
        val active = mutableStateOf(true)
        var observed = 0f
        var receipt = "not composed"
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            LiteTheme(LiteSettings()) {
                CompositionLocalProvider(LocalLiteReducedMotion provides reduced.value, LocalLitePageActive provides active.value) {
                    val frameTarget = target.value
                    val frameReduced = LocalLiteReducedMotion.current
                    val frameActive = LocalLitePageActive.current
                    val value by rememberLiteMotionValue(frameTarget, tween(200))
                    SideEffect {
                        observed = value
                        receipt = "target=$frameTarget reduced=$frameReduced active=$frameActive value=$value"
                    }
                    LiteText("motion:$value")
                }
            }
        }
        compose.runOnUiThread { target.value = 1f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue(observed > 0f && observed < 1f) }
        compose.runOnUiThread { reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("reduced snap: $receipt", 1f, observed, 0f) }
        compose.runOnUiThread { reduced.value = false; target.value = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals("inactive snap: $receipt", 0f, observed, 0f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, observed, 0f) }
    }

    @Test fun floatingDockKeepsItsActualSelectedPaneLocalAcrossMaterialsPressCollapseAndRtl() {
        val material = mutableStateOf(MaterialTier.Solid)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val collapsed = mutableStateOf(false)
        val selected = mutableStateOf(MainTab.Home)
        var pixels = 0f
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            LiteTheme(LiteSettings(material = material.value, colorMode = ColorMode.Light)) {
                val density = LocalDensity.current
                SideEffect { pixels = density.density }
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(LocalLiteVisuals.current.page))
                    CompositionLocalProvider(LocalLiteBackdrop provides backdrop,
                        LocalDensity provides Density(density.density, 1f),
                        LocalLayoutDirection provides direction.value, LocalLiteReducedMotion provides false) {
                        LiteNavigationDock(MainTab.entries, selected.value, collapsed.value, 0.dp,
                            { selected.value = it }, Modifier.width(360.dp).align(Alignment.BottomCenter))
                    }
                }
            }
        }
        fun settle() { compose.mainClock.advanceTimeBy(1200); compose.waitForIdle() }
        fun bounds(tag: String): Rect {
            val coordinates = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo.coordinates
            return Rect(coordinates.localToRoot(Offset.Zero), coordinates.localToRoot(
                Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
        }
        fun sameOuter(expected: Rect, label: String) {
            val actual = bounds("floating-navigation")
            assertEquals("$label outer bounds", expected, actual)
        }
        for (tier in MaterialTier.entries) for (rtl in listOf(false, true)) {
            val label = "$tier rtl=$rtl"
            compose.runOnUiThread {
                material.value = tier; direction.value = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                collapsed.value = false; selected.value = MainTab.Home
            }
            settle()
            val outer = bounds("floating-navigation")
            val lens = bounds("floating-navigation-selected-lens")
            val pitch = (outer.width - 16 * pixels) / MainTab.entries.size
            val width = maxOf(pitch + 8 * pixels, 84 * pixels).coerceAtMost(outer.width)
            assertEquals("$label outer height", 62 * pixels, outer.height, 2f)
            assertEquals("$label actual Pane width", width, lens.width, 2f)
            assertEquals("$label actual Pane height", 54 * pixels, lens.height, 2f)
            assertEquals("$label vertical centre", outer.center.y, lens.center.y, 1f)
            assertEquals("$label selected destination centre", tab(R.string.lite_tab_home).fetchSemanticsNode().boundsInRoot.center.x, lens.center.x, 2f)
            assertTrue("$label selected Pane must not cover the full Dock: outer=$outer lens=$lens", lens.width < outer.width / 2)
            tab(R.string.lite_tab_home).performTouchInput { down(center) }
            try {
                settle()
                val pressed = bounds("floating-navigation-selected-lens")
                assertEquals("$label pressed width", width + 16 * pixels, pressed.width, 2f)
                assertEquals("$label pressed height", 70 * pixels, pressed.height, 2f)
                assertEquals("$label pressed vertical centre", outer.center.y, pressed.center.y, 1f)
                sameOuter(outer, "$label pressed")
            } finally { tab(R.string.lite_tab_home).performTouchInput { up() } }
            settle()
            sameOuter(outer, "$label released")
            tab(R.string.lite_tab_proxies).performClick()
            settle()
            tab(R.string.lite_tab_proxies).assertIsSelected()
            assertEquals("$label moved to actual selected destination", tab(R.string.lite_tab_proxies).fetchSemanticsNode().boundsInRoot.center.x,
                bounds("floating-navigation-selected-lens").center.x, 2f)
            compose.runOnUiThread { collapsed.value = true }
            settle()
            val compactOuter = bounds("floating-navigation")
            val compactLens = bounds("floating-navigation-selected-lens")
            val compactPitch = (compactOuter.width - 16 * pixels) / MainTab.entries.size
            assertEquals("$label compact outer height", 56 * pixels, compactOuter.height, 2f)
            assertEquals("$label compact actual Pane width", maxOf(compactPitch + 8 * pixels, 64 * pixels), compactLens.width, 2f)
            assertEquals("$label compact actual Pane height", 48 * pixels, compactLens.height, 2f)
            assertEquals("$label compact vertical centre", compactOuter.center.y, compactLens.center.y, 1f)
            assertEquals("$label bottom position unchanged", outer.bottom, compactOuter.bottom, 1f)
            compose.runOnUiThread { collapsed.value = false }
            settle()
            sameOuter(outer, "$label restored")
        }
    }

    @Test fun visitedStateSurvivesAndLogicalInactiveImmediatelyStopsLifecycleAndInput() {
        val selected = mutableStateOf(0)
        val reduced = mutableStateOf(false)
        val active = mutableStateOf(true)
        val states = mutableMapOf<Int, Lifecycle.State>()
        val composed = mutableSetOf<Int>()
        val pageActive = mutableMapOf<Int, Boolean>()
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            LiteTheme(LiteSettings()) {
                CompositionLocalProvider(LocalLiteReducedMotion provides reduced.value) {
                    LiteTabTransitionHost(listOf(0, 1, 2, 3), selected.value, TabAnimation.Slide, active.value) { page, _ ->
                        var count by remember { mutableIntStateOf(0) }
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        val selectedPage = LocalLitePageActive.current
                        SideEffect { composed.add(page); states[page] = lifecycle.currentState; pageActive[page] = selectedPage }
                        Box(Modifier.fillMaxSize().testTag("page-$page").clickable { count++ }) { LiteText("$page:$count") }
                    }
                }
            }
        }
        compose.onNodeWithTag("page-0").performClick()
        compose.runOnUiThread { selected.value = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("page-0").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Lifecycle.State.CREATED, states[0]); assertFalse(1 in composed); assertFalse(2 in composed) }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnUiThread { reduced.value = true; selected.value = 0 }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("0:1").assertIsDisplayed()
        compose.runOnUiThread { active.value = false; selected.value = 3 }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(Lifecycle.State.CREATED, states[0]); assertEquals(Lifecycle.State.CREATED, states[3]); assertEquals(true, pageActive[3]); assertEquals(false, pageActive[0]) }
        compose.runOnUiThread { active.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("3:0").assertIsDisplayed()
    }
}
