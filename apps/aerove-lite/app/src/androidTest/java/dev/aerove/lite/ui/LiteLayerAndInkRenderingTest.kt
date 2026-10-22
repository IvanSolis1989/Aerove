package dev.aerove.lite.ui

import android.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

@RunWith(Parameterized::class)
class LiteLayerAndInkRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realAuroraInkAndNestedPlanesExcludeTheirOwnForeground() {
        withOpticalFixture(compose, case, "layers-ink_$case") { state, probe, capture ->
            val source = capture.capture("source-phase-zero")
            val actual = compose.frame(state, FixtureState(FixtureStage.Production), capture, "production-phase-zero")
            val halfSource = compose.frame(state, FixtureState(FixtureStage.Source, auroraPhase = 0.5f), capture, "source-phase-half")
            val halfActual = compose.frame(state, FixtureState(FixtureStage.Production, auroraPhase = 0.5f), capture, "production-phase-half")
            val mask = compose.frame(state, FixtureState(FixtureStage.InkMask), capture, "opaque-glyph-mask")
            val selected = compose.frame(state, FixtureState(FixtureStage.Production, parentSelected = true), capture, "parent-selected")
            val front = compose.frame(state, FixtureState(FixtureStage.Production, parentSelected = true, frontHue = true), capture, "foreground-changed")
            val report = JSONObject().put("case", case.toString())
            try {
                for (name in listOf("ink", "inkIcon")) {
                    report.put(name, assertInk(mask, source, halfSource, actual, halfActual, probe, name))
                }
                val d = probe.density
                val parent = requireNotNull(probe.bounds["parent"])
                val child = requireNotNull(probe.bounds["child"])
                val foreground = requireNotNull(probe.bounds["front"])
                val outside = androidx.compose.ui.geometry.Offset(parent.left + 0.5f * d, parent.top + 0.5f * d)
                val cornerProbe = requireNotNull(probe.bounds["cornerProbe"])
                assertTrue("The real child probe must cover the parent's clipped corner", cornerProbe.contains(outside))
                val cornerError = channelError(source.pixel(outside.x, outside.y), actual.pixel(outside.x, outside.y))
                report.put("exportedCornerChannelError", cornerError)
                assertTrue("A child must sample the clipped parent plane; its rectangular export cannot fill the parent's exterior", cornerError <= 2)
                val parentPatch = Rect(parent.left + 40 * d, parent.top + 12 * d, parent.left + 60 * d, parent.top + 20 * d)
                val changedParent = comparePixels(actual, selected, parentPatch)
                report.put("parentMutation", changedParent.json())
                assertTrue("The fixture must actually alter the parent's captured surface", changedParent.maximum > 3)
                val childRegion = child.deflate(6 * d)
                val childMutation = comparePixels(actual, selected, childRegion, foreground.inflate(2 * d))
                report.put("childSamplesParent", childMutation.json())
                if (case.tier == MaterialTier.Solid) {
                    assertTrue("The solid child is opaque to its parent", childMutation.maximum <= 2)
                } else assertTrue("Nested glass must include the parent's plane, not sample the wallpaper alone", childMutation.maximum > 2)
                val glyphChange = comparePixels(selected, front, foreground)
                report.put("foregroundMutation", glyphChange.json())
                assertTrue("The foreground mutation must actually reach the GPU window", glyphChange.maximum > 40)
                val selfSample = comparePixels(selected, front, childRegion, foreground.inflate(2 * d))
                report.put("noSelfSampling", selfSample.json())
                assertTrue("Changing foreground glyphs must not leak their colors into the child's sampled surface", selfSample.maximum <= 2)
            } finally { capture.metrics(report) }
        }
    }

    private fun assertInk(
        mask: CapturedFrame,
        source: CapturedFrame,
        halfSource: CapturedFrame,
        actual: CapturedFrame,
        halfActual: CapturedFrame,
        probe: RenderProbe,
        name: String,
    ): JSONObject {
        val region = requireNotNull(probe.bounds[name])
        var count = 0
        var inkError = 0
        var actualChange = 0
        var sourceChange = 0
        for (y in ceil(region.top).toInt() until floor(region.bottom).toInt()) {
            for (x in ceil(region.left).toInt() until floor(region.right).toInt()) {
                val color = mask.pixel(x.toFloat(), y.toFloat())
                if (minOf(Color.red(color), Color.green(color), Color.blue(color)) < 254) continue
                val first = source.pixel(x.toFloat(), y.toFloat())
                val second = halfSource.pixel(x.toFloat(), y.toFloat())
                val observed = actual.pixel(x.toFloat(), y.toFloat())
                val halfObserved = halfActual.pixel(x.toFloat(), y.toFloat())
                inkError = max(inkError, channelError(expectedInk(probe.ink, first, case, name == "inkIcon"), observed))
                inkError = max(inkError, channelError(expectedInk(probe.ink, second, case, name == "inkIcon"), halfObserved))
                actualChange = max(actualChange, channelError(observed, halfObserved))
                sourceChange = max(sourceChange, channelError(first, second))
                count++
            }
        }
        val result = JSONObject().put("opaqueGlyphPixels", count).put("maximumInkError", inkError)
            .put("actualPhaseChange", actualChange).put("sourcePhaseChange", sourceChange)
            .put("oracle", "Independent Full foreground ink scalar math on measured PNG field pixels")
            .put("subtle", case.tier == MaterialTier.Solid)
        assertTrue("The hardware glyph mask must contain opaque core pixels ($name)", count >= 20)
        assertTrue("$name must preserve ordinary ink and match Full Aurora role/quality/subtle math (error=$inkError)", inkError <= 2)
        if (!case.aurora) {
            assertTrue("Ordinary foreground color cannot change with wallpaper phase", actualChange <= 2)
        } else {
            assertTrue("The source wallpaper must actually move under the glyph mask", sourceChange > 8)
            assertTrue("Aurora foreground, including Solid subtle ink, must respond to the captured wallpaper", actualChange > 1)
        }
        return result
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases(switchStates = false)
    }
}
