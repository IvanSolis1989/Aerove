package dev.aerove.lite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LiteHazeRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pillUsesIndependentSharpAndGaussianGpuInputs() {
        val fixture = FixtureState(pillPane = true)
        withOpticalFixture(compose, case, "pill-haze_$case", fixture) { state, probe, capture ->
            val source = capture.capture("sharp-source")
            val actual = compose.frame(state, fixture.copy(stage = FixtureStage.Production), capture, "production-pill")
            val gaussian = compose.frame(state, fixture.copy(stage = FixtureStage.NoLens), capture, "independent-gaussian-control")
            val point = requireNotNull(probe.bounds["pane"]).center
            val sharpPixel = source.pixel(point.x, point.y)
            val blurredPixel = gaussian.pixel(point.x, point.y)
            val actualPixel = actual.pixel(point.x, point.y)
            val expected = when (case.tier) {
                MaterialTier.Solid -> probe.surface
                MaterialTier.Frosted -> blurredPixel
                MaterialTier.Clear -> blend8(sharpPixel, blurredPixel, 0.72f)
            }
            val report = JSONObject().put("case", case.toString()).put("sharp", sharpPixel).put("gaussian", blurredPixel)
                .put("observed", actualPixel).put("expected", expected).put("maximumChannelError", channelError(actualPixel, expected))
            try {
                assertTrue("Pill must match Full 0.72 haze over an independent Gaussian input; no baked blur substitute", channelError(actualPixel, expected) <= 2)
                if (case.tier == MaterialTier.Clear) {
                    assertTrue("A source marker must distinguish the two GPU inputs", channelError(sharpPixel, blurredPixel) > 80)
                    assertTrue("Clear pill must retain its sharp contribution", channelError(actualPixel, blurredPixel) > 30)
                    assertTrue("Clear pill must also include its Gaussian contribution", channelError(actualPixel, sharpPixel) > 30)
                }
            } finally { capture.metrics(report) }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases(switchStates = false)
    }
}
