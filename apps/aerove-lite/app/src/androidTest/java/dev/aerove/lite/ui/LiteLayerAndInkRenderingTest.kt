package dev.aerove.lite.ui

import android.graphics.Color
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

@RunWith(Parameterized::class)
class LiteLayerAndInkRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun realAuroraInkAndNestedPlanesExcludeTheirOwnForeground() {
        withOpticalFixture(compose, case, "layers-ink_$case") { state, probe, capture ->
            val source = capture.capture("source-phase-zero")
            val actual = compose.frame(state, FixtureState(FixtureStage.Production), capture, "production-phase-zero")
            val halfSource = compose.frame(state, FixtureState(FixtureStage.Source, auroraTime = 45_000_000L), capture, "source-phase-half")
            val halfActual = compose.frame(state, FixtureState(FixtureStage.Production, auroraTime = 45_000_000L), capture, "production-phase-half")
            val mask = compose.frame(state, FixtureState(FixtureStage.InkMask), capture, "opaque-glyph-mask")
            val selected = compose.frame(state, FixtureState(FixtureStage.Production, parentSelected = true), capture, "parent-selected")
            val front = compose.frame(state, FixtureState(FixtureStage.Production, parentSelected = true, frontHue = true), capture, "foreground-changed")
            val report = JSONObject().put("case", case.toString())
            val controlFailure = capturePlainInkControls(state, FixtureState(), probe, capture, report,
                "plain-ink", "source-phase-zero", "source-phase-half")
            runInkAssertionsAndMetrics(controlFailure, metrics = { capture.metrics(report) }) {
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
            }
        }
    }

    @Test
    fun translucentAuroraInkPreservesGlyphCoverageAndBlankPixels() {
        // One representative real GPU case exercises source alpha without duplicating the matrix.
        assumeTrue("Source-alpha coverage uses the Solid/light/Aurora representative",
            case.aurora && case.tier == MaterialTier.Solid && !case.dark)
        val fixture = FixtureState(inkAlpha = 0.8f)
        withOpticalFixture(compose, case, "translucent-ink_$case", initial = fixture) { state, probe, capture ->
            val source = capture.capture("source-alpha-08")
            val actual = compose.frame(state, fixture.copy(stage = FixtureStage.Production), capture, "ink-alpha-08")
            val halfSource = compose.frame(state, fixture.copy(auroraTime = 45_000_000L), capture, "source-half-alpha-08")
            val halfActual = compose.frame(state, fixture.copy(stage = FixtureStage.Production, auroraTime = 45_000_000L), capture, "ink-half-alpha-08")
            val mask = compose.frame(state, fixture.copy(stage = FixtureStage.InkMask), capture, "coverage-alpha-08")
            val report = JSONObject().put("sourceAlpha", Color.alpha(probe.ink))
            val controlFailure = capturePlainInkControls(state, fixture, probe, capture, report,
                "plain-ink-alpha-08", "source-alpha-08", "source-half-alpha-08")
            runInkAssertionsAndMetrics(controlFailure, metrics = { capture.metrics(report) }) {
                assertTrue("The fixture must actually draw a translucent source", Color.alpha(probe.ink) == 204)
                for (name in listOf("ink", "inkIcon")) report.put(name, assertInk(mask, source, halfSource, actual, halfActual, probe, name))
            }
        }
    }

    private fun capturePlainInkControls(
        state: MutableState<FixtureState>,
        fixture: FixtureState,
        probe: RenderProbe,
        capture: HardwareCapture,
        report: JSONObject,
        prefix: String,
        zeroSource: String,
        halfSource: String,
    ): Throwable? {
        if (case != RenderCase(MaterialTier.Solid, aurora = true, dark = false, checked = false, enabled = true)) return null
        val previous = state.value
        val originalPaint = probe.ink
        val observations = JSONArray()
        val controls = JSONObject().put("diagnosticOnly", true).put("productAcceptance", false)
            .put("coverageOracleChanged", false).put("observations", observations)
        report.put("plainInkControls", controls)
        val failure = collectInkControls(capture = {
            for ((phase, time, background) in listOf(Triple("zero", 0L, zeroSource), Triple("half", 45_000_000L, halfSource))) {
                val next = fixture.copy(stage = FixtureStage.Production, auroraTime = time, plainInkControl = true)
                val label = "$prefix-$phase"
                val frame = compose.frame(state, next, capture, label)
                val style = LiteType.Headline
                observations.put(JSONObject().put("label", label).put("frameMetadata", frame.evidence)
                    .put("plainInkControl", next.plainInkControl).put("backgroundReference", "$background-window.png")
                    .put("backgroundIdentity", JSONObject().put("case", case.toString()).put("aurora", case.aurora)
                        .put("revision", next.revision).put("auroraTime", next.auroraTime))
                    .put("rawPaintArgb", Integer.toHexString(probe.ink).padStart(8, '0'))
                    .put("rawPaintAlpha", Color.alpha(probe.ink)).put("fixtureInkAlpha", next.inkAlpha)
                    .put("matchesOriginalRawPaint", probe.ink == originalPaint)
                    .put("text", "MMMMMM").put("textStyle", JSONObject().put("name", "LiteType.Headline")
                        .put("fontSizeSp", style.fontSize.value).put("lineHeightSp", style.lineHeight.value)
                        .put("fontWeight", style.fontWeight?.weight ?: JSONObject.NULL)
                        .put("letterSpacingSp", style.letterSpacing.value)
                        .put("requestedFontFamily", case.settings.fontFamily ?: JSONObject.NULL)
                        .put("familyRoute", "LiteText style.fontFamily ?: LocalLiteFontFamily")
                        .put("resolvedPlatformTypeface", "not directly observed"))
                    .put("icon", "FullGlyphs.logs").put("windowCoordinates", frame.evidence.getJSONObject("windowCoordinates")))
            }
        }, restore = {
            compose.runOnUiThread { state.value = previous }
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        })
        if (failure != null) controls.put("controlFailure", "${failure.javaClass.name}: ${failure.message}")
        controls.put("captureComplete", failure == null).put("restoredOriginalRawPaint", probe.ink == originalPaint)
        return failure
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
        var blankCount = 0
        var blankError = 0
        var edgeCount = 0
        var edgeError = 0
        fun composite(ink: Int, background: Int, coverage: Double): Int {
            val alpha = coverage * Color.alpha(probe.ink) / 255.0
            fun channel(foreground: Int, behind: Int) = (foreground * alpha + behind * (1 - alpha)).roundToInt()
            return Color.rgb(channel(Color.red(ink), Color.red(background)), channel(Color.green(ink), Color.green(background)),
                channel(Color.blue(ink), Color.blue(background)))
        }
        for (y in ceil(region.top).toInt() until floor(region.bottom).toInt()) {
            for (x in ceil(region.left).toInt() until floor(region.right).toInt()) {
                val color = mask.pixel(x.toFloat(), y.toFloat())
                val first = source.pixel(x.toFloat(), y.toFloat())
                val second = halfSource.pixel(x.toFloat(), y.toFloat())
                val observed = actual.pixel(x.toFloat(), y.toFloat())
                val halfObserved = halfActual.pixel(x.toFloat(), y.toFloat())
                val coverage = maxOf(Color.red(color), Color.green(color), Color.blue(color))
                if (coverage <= 1) {
                    blankCount++
                    blankError = max(blankError, max(channelError(first, observed), channelError(second, halfObserved)))
                    continue
                }
                val firstInk = expectedInk(probe.ink, first, case, name == "inkIcon")
                val secondInk = expectedInk(probe.ink, second, case, name == "inkIcon")
                if (minOf(Color.red(color), Color.green(color), Color.blue(color)) < 254) {
                    if (coverage in 16..239) {
                        edgeCount++
                        edgeError = max(edgeError, channelError(composite(firstInk, first, coverage / 255.0), observed))
                        edgeError = max(edgeError, channelError(composite(secondInk, second, coverage / 255.0), halfObserved))
                    }
                    continue
                }
                inkError = max(inkError, channelError(composite(firstInk, first, 1.0), observed))
                inkError = max(inkError, channelError(composite(secondInk, second, 1.0), halfObserved))
                actualChange = max(actualChange, channelError(observed, halfObserved))
                sourceChange = max(sourceChange, channelError(first, second))
                count++
            }
        }
        val result = JSONObject().put("opaqueGlyphPixels", count).put("maximumInkError", inkError)
            .put("actualPhaseChange", actualChange).put("sourcePhaseChange", sourceChange)
            .put("oracle", "Independent Full foreground ink scalar math on measured PNG field pixels")
            .put("subtle", case.tier == MaterialTier.Solid)
            .put("blankMaskPixels", blankCount).put("maximumBlankPixelError", blankError)
            .put("partialCoveragePixels", edgeCount).put("maximumCoverageError", edgeError)
            .put("sourceAlpha", Color.alpha(probe.ink))
        assertTrue("The measured mask must contain transparent pixels ($name)", blankCount >= 20)
        assertTrue("Blank glyph pixels must leave the captured field unchanged ($name error=$blankError)", blankError <= 2)
        assertTrue("The measured mask must include antialiased edges ($name)", edgeCount >= 20)
        // Two 8-bit captures and the final blend can each round a channel once.
        assertTrue("Partial alpha must preserve the measured glyph coverage ($name error=$edgeError)", edgeError <= 3)
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

internal fun collectInkControls(capture: () -> Unit, restore: () -> Unit): Throwable? {
    var failure: Throwable? = null
    try { capture() } catch (caught: Throwable) {
        rethrowMatrixCancellation(caught)
        failure = caught
    }
    // Fatal capture errors leave the function before any restore or ordinary error collection.
    try { restore() } catch (caught: Throwable) {
        rethrowMatrixCancellation(caught, failure)
        val original = failure
        if (original == null) failure = caught else if (original !== caught) original.addSuppressed(caught)
    }
    return failure
}

internal fun runInkAssertionsAndMetrics(controlFailure: Throwable?, metrics: () -> Unit, assertions: () -> Unit) {
    var original: Throwable? = null
    try {
        assertions()
        controlFailure?.let { throw it }
    } catch (failure: Throwable) {
        rethrowMatrixCancellation(failure, controlFailure)
        original = failure
        if (controlFailure != null && controlFailure !== failure) failure.addSuppressed(controlFailure)
    }
    try { metrics() } catch (failure: Throwable) {
        rethrowMatrixCancellation(failure, original)
        val formal = original
        if (formal == null) throw failure else if (formal !== failure) formal.addSuppressed(failure)
    }
    original?.let { throw it }
}
