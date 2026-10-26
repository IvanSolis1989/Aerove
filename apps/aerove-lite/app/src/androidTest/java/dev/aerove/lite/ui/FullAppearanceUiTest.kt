package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.ColorMode
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.R
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import java.util.concurrent.CopyOnWriteArrayList

class FullAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun openTheme() {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(tab and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_theme)) and hasClickAction())
        compose.onNode(hasText(text(R.string.lite_theme)) and hasClickAction()).performClick()
    }

    private fun editFirstColor() {
        val description = context.getString(R.string.lite_aurora_edit_color, 1)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).performClick()
    }

    @Test
    fun colorDraftCancelsOrCommitsWithoutLosingOtherAppearanceSettings() {
        val initial = LiteSettings(colorStyle = ColorStyle.Tinted, auroraRotationDay = 99991231)
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        openTheme()
        editFirstColor()
        compose.onNodeWithTag("aurora-color-grid").performTouchInput { click(Offset(width * 0.46f, height * 0.55f)) }
        compose.runOnUiThread { assertTrue(actions.settings.isEmpty()) }
        compose.onNodeWithText(text(R.string.lite_cancel)).performClick()
        compose.runOnUiThread { assertEquals(initial, state.value.settings); assertTrue(actions.settings.isEmpty()) }
        editFirstColor()
        compose.onNodeWithTag("aurora-color-grid").performTouchInput { click(Offset(width * 0.46f, height * 0.55f)) }
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(auroraSeed = 832838199)) }
        compose.onNodeWithText(text(R.string.lite_confirm)).performClick()
        compose.runOnUiThread {
            assertEquals(1, actions.settings.size)
            assertNotEquals(initial.auroraColors[0], state.value.settings.auroraColors[0])
            assertEquals(initial.auroraColors.drop(1), state.value.settings.auroraColors.drop(1))
            assertEquals(832838199L, state.value.settings.auroraSeed)
            assertEquals(initial.colorStyle, state.value.settings.colorStyle)
            assertEquals(initial.auroraRotationDay, state.value.settings.auroraRotationDay)
            assertEquals(listOf("changeSettings"), actions.events)
        }
    }

    @Test
    fun paletteKeepsTheFullOneToThreeColorEditingBounds() {
        val state = mutableStateOf(LiteState())
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        openTheme()
        repeat(2) {
            editFirstColor()
            compose.onNodeWithText(text(R.string.lite_delete)).performClick()
        }
        compose.runOnUiThread { assertEquals(1, state.value.settings.auroraColors.size) }
        editFirstColor()
        compose.onNodeWithText(text(R.string.lite_delete)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.lite_cancel)).performClick()
        repeat(2) {
            compose.onNodeWithContentDescription(text(R.string.lite_add_color)).performClick()
            compose.onNodeWithText(text(R.string.lite_confirm)).performClick()
        }
        compose.runOnUiThread { assertEquals(3, state.value.settings.auroraColors.size) }
        compose.onNodeWithContentDescription(text(R.string.lite_add_color)).assertDoesNotExist()
    }

    @Test
    fun themePreviewStaysStillWhileTheWallpaperMoves() {
        val state = mutableStateOf(LiteState(settings = LiteSettings(auroraRotationDay = 99991231)))
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides false) { LiteApp(state.value, UiTestActions {}) } }
        openTheme()
        val preview = hasContentDescription(text(R.string.lite_preview))
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(preview)
        fun centre(): List<Int> {
            val map = compose.onNode(preview).captureToImage().toPixelMap()
            return (map.height / 5 until map.height * 4 / 5).flatMap { y -> (map.width / 5 until map.width * 4 / 5).map { x -> map[x, y].toArgb() } }
        }
        val before = centre()
        compose.mainClock.advanceTimeBy(5_000)
        assertTrue("The preview must stay as still as Standard's SeededAuroraWallpaper(motion: false)", before == centre())
    }

    @Test
    fun wallpaperShuffleUsesTheAtomicActionWithoutRewritingSettings() {
        val initial = LiteSettings(auroraRotationDay = 99991231)
        val state = mutableStateOf(LiteState(settings = initial))
        val actions = UiTestActions { state.value = state.value.copy(settings = it) }
        compose.replaceAppContent { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        openTheme()
        val label = text(R.string.lite_aurora_regenerate)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label) and hasClickAction())
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.runOnUiThread {
            assertEquals(initial, state.value.settings)
            assertTrue(actions.settings.isEmpty())
            assertEquals(listOf("shuffleAurora"), actions.events)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_normal_theme)) and hasClickAction())
        compose.onNode(hasText(text(R.string.lite_normal_theme)) and hasClickAction()).performClick()
        compose.onNodeWithText(label).assertDoesNotExist()
    }

    @Test
    fun colorStyleIsPartOfTheProductionRememberKey() {
        val settings = mutableStateOf(LiteSettings(aurora = false, colorMode = ColorMode.Light))
        compose.replaceAppContent {
            LiteTheme(settings.value) {
                Text(MaterialTheme.colorScheme.primary.toArgb().toString(), Modifier.testTag("resolved-primary"))
                Text(MaterialTheme.colorScheme.surface.toArgb().toString(), Modifier.testTag("resolved-surface"))
            }
        }
        for (dark in listOf(false, true)) for (style in ColorStyle.entries) {
            compose.runOnUiThread { settings.value = settings.value.copy(colorMode = if (dark) ColorMode.Dark else ColorMode.Light, colorStyle = style) }
            val expected = fullColorScheme(settings.value.themeColor.toInt(), dark, false, style)
            compose.onNodeWithTag("resolved-primary").assertTextEquals(expected.primary.toArgb().toString())
            compose.onNodeWithTag("resolved-surface").assertTextEquals(expected.surface.toArgb().toString())
        }
    }

    @Test
    fun customPaletteAndSeedReachTheActualHardwareWallpaper() {
        val settings = LiteSettings(material = MaterialTier.Clear, colorMode = ColorMode.Dark)
        val state = mutableStateOf(LiteState(settings = settings))
        val probe = RenderProbe()
        compose.replaceAppContent {
            val view = LocalView.current
            val density = LocalDensity.current
            SideEffect { probe.view = view; probe.density = density.density; probe.fontScale = density.fontScale }
            CompositionLocalProvider(LocalLiteMotionOverride provides true) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { probe.bounds["app"] = it.boundsInWindow() }) { LiteApp(state.value, UiTestActions {}) }
            }
        }
        HardwareCapture(compose, probe, "full-aurora-settings").use { evidence ->
            evidence.capture("default", configuration = JSONObject().put("fixture", "production LiteApp, stopped core, reduced motion")).use { before ->
                compose.runOnUiThread {
                    state.value = state.value.copy(settings = settings.copy(auroraColors = listOf(0xfff47231L, 0xfff0bb50L), auroraSeed = 832838199))
                }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("aurora-wallpaper-building").fetchSemanticsNodes().isEmpty() }
                compose.waitForIdle()
                evidence.capture("custom", configuration = JSONObject().put("fixture", "two custom colors, seed 832838199")).use { after ->
                    assertEquals(before.bitmap.width, after.bitmap.width)
                    assertEquals(before.bitmap.height, after.bitmap.height)
                    val changes = (1..8).map { sample ->
                        val x = 2
                        val y = before.bitmap.height * sample / 10
                        val a = before.bitmap.getPixel(x, y); val b = after.bitmap.getPixel(x, y)
                        (0..2).maxOf { shift -> abs((a ushr (shift * 8) and 255) - (b ushr (shift * 8) and 255)) }
                    }
                    assertTrue("Custom settings must change real uncovered wallpaper pixels", changes.count { it > 8 } >= 4)
                }
            }
        }
    }

    @Test
    fun interactionCapsFramesAndReturnsToIdleAfterTheFullGracePeriod() {
        val samples = CopyOnWriteArrayList<Long>()
        var activity: AuroraMotionActivity? = null
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides false) {
                LiteTheme(LiteSettings()) {
                    val motion = rememberAuroraMotionActivity("motion-fixture")
                    val time = rememberAuroraTime(true, activity = motion)
                    SideEffect { activity = motion }
                    LaunchedEffect(time) { snapshotFlow { time.longValue }.collect { samples += it } }
                    Box(Modifier.fillMaxSize().auroraMotionActivity(motion).testTag("motion-fixture"))
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        samples.clear()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Idle must publish 15fps on the device frame grid: ${samples.size}", samples.size in 14..16)
        compose.onNodeWithTag("motion-fixture").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnUiThread { assertEquals(10, requireNotNull(activity).framesPerSecond) }
        samples.clear()
        compose.mainClock.advanceTimeBy(1000)
        assertTrue("Held interaction must publish 10fps: ${samples.size}", samples.size in 9..11)
        compose.onNodeWithTag("motion-fixture").performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(320)
        compose.runOnUiThread { assertTrue(requireNotNull(activity).active) }
        compose.mainClock.advanceTimeBy(50)
        compose.runOnUiThread { assertEquals(15, requireNotNull(activity).framesPerSecond) }
    }

    @Test
    fun reducedMotionRestartsTheClockAndAnInactiveActivityHoldsIt() {
        val reduced = mutableStateOf(false)
        var time: androidx.compose.runtime.LongState? = null
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteMotionOverride provides reduced.value) {
                LiteTheme(LiteSettings()) {
                    val clock = rememberAuroraTime(true)
                    SideEffect { time = clock }
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread { assertTrue(requireNotNull(time).longValue > 0L); reduced.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { assertEquals(0L, requireNotNull(time).longValue) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnUiThread { assertEquals(0L, requireNotNull(time).longValue); reduced.value = false }
        compose.mainClock.advanceTimeBy(500)
        var frozen = 0L
        compose.runOnUiThread { frozen = requireNotNull(time).longValue; assertTrue("must restart from 0: $frozen", frozen in 1L..600_000L) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { frozen = requireNotNull(time).longValue; assertTrue(frozen > 0L) }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnUiThread { assertEquals(frozen, requireNotNull(time).longValue) }
    }

    @Test
    fun wallpaperShowsTheRestPoseWheneverReducedMotionIsOn() {
        for (reducedAtStart in listOf(true, false)) {
            val reduced = mutableStateOf(reducedAtStart)
            compose.mainClock.autoAdvance = false
            compose.replaceAppContent {
                CompositionLocalProvider(LocalLiteMotionOverride provides reduced.value) {
                    LiteTheme(LiteSettings(colorMode = ColorMode.Dark)) {
                        val clock = rememberAuroraTime(true)
                        val rest = remember { mutableLongStateOf(0L) }
                        Column {
                            Box(Modifier.size(160.dp, 320.dp).testTag("running")) { AuroraWallpaper(clock, FullAuroraColors, FullAuroraSeed, true, Modifier.fillMaxSize()) }
                            Box(Modifier.size(160.dp, 320.dp).testTag("rest")) { AuroraWallpaper(rest, FullAuroraColors, FullAuroraSeed, true, Modifier.fillMaxSize()) }
                        }
                    }
                }
            }
            fun atRest() = compose.onNodeWithTag("running").captureToImage().toPixelMap().buffer
                .contentEquals(compose.onNodeWithTag("rest").captureToImage().toPixelMap().buffer)
            compose.mainClock.advanceTimeBy(5_000)
            assertEquals("reduced from the start: $reducedAtStart", reducedAtStart, atRest())
            compose.runOnUiThread { reduced.value = true }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("switching reduced motion on mid-run must show the rest pose", atRest())
            compose.mainClock.advanceTimeBy(1_000)
            assertTrue(atRest())
            compose.runOnUiThread { reduced.value = false }
            compose.mainClock.advanceTimeBy(5_000)
            assertTrue("motion resumes from the rest pose", !atRest())
        }
    }
}
