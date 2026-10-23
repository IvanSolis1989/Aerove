package dev.aerove.lite.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.MainActivity
import dev.aerove.lite.navigation.TabAnimation
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/** Runs the existing displacement/dispersion oracle at an actual intermediate scene opacity. */
class NavigationOpticsRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun halfFadeStillRefractsTheHardwareBackdropComparedWithIndependentNoLensControl() {
        val case = RenderCase(MaterialTier.Clear, false, false, false, true)
        val selected = mutableStateOf(0)
        val stage = mutableStateOf(FixtureStage.Source)
        val probe = RenderProbe()
        compose.mainClock.autoAdvance = false
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLiteReducedMotion provides false, LocalLayoutDirection provides LayoutDirection.Ltr) {
                LiteTabTransitionHost(listOf(0, 1), selected.value, TabAnimation.Fade, true, Modifier.fillMaxSize()) { page, _ ->
                    OpticalFixture(case, FixtureState(if (page == 0) FixtureStage.Source else stage.value), probe)
                }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        HardwareCapture(compose, probe, "navigation_half_fade_clear").use { capture ->
            fun frame(next: FixtureStage, label: String): CapturedFrame {
                compose.runOnUiThread { selected.value = 0; stage.value = next }
                compose.mainClock.advanceTimeBy(400)
                compose.runOnUiThread { selected.value = 1 }
                compose.mainClock.advanceTimeByFrame()
                compose.mainClock.advanceTimeBy(110, ignoreFrameDuration = true)
                compose.waitForIdle()
                return capture.capture(label)
            }
            val source = frame(FixtureStage.Source, "half-fade-source")
            val actual = frame(FixtureStage.Production, "half-fade-production")
            val control = frame(FixtureStage.NoLens, "half-fade-no-lens")
            capture.metrics(JSONObject().put("animation", "220ms easeInOut fade at 110ms; both scenes retained")
                .put("oracle", cardOptics(source, actual, control, probe, case)))
        }
    }
}
