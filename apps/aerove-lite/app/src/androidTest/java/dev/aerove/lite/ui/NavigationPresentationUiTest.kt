package dev.aerove.lite.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteState
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
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteReducedMotion provides reduced.value, LocalLitePageActive provides active.value) {
                val value by rememberLiteMotionValue(target.value, tween(200))
                SideEffect { observed = value }
                LiteText("motion:$value")
            }
        }
        compose.runOnUiThread { target.value = 1f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle { assertTrue(observed > 0f && observed < 1f) }
        compose.runOnUiThread { reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(1f, observed, 0f) }
        compose.runOnUiThread { reduced.value = false; target.value = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(64)
        compose.runOnUiThread { active.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(0f, observed, 0f) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0f, observed, 0f) }
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
