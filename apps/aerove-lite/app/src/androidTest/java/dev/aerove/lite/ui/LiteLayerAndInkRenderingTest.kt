package dev.aerove.lite.ui

import android.graphics.Color
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
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
        val fixture = FixtureState(fixtureGeometry = JSONObject(), inkContrastSource = true, inkContrastDrawReceipt = JSONObject(), sceneDrawReceipt = JSONObject())
        withOpticalFixture(compose, case, "layers-ink_$case", initial = fixture) { state, probe, capture ->
            val source = compose.frame(state, fixture, capture, "source-phase-zero")
            val actual = compose.frame(state, fixture.copy(stage = FixtureStage.Production), capture, "production-phase-zero")
            val halfSource = compose.frame(state, fixture.copy(stage = FixtureStage.Source, auroraTime = 45_000_000L), capture, "source-phase-half")
            val halfActual = compose.frame(state, fixture.copy(stage = FixtureStage.Production, auroraTime = 45_000_000L), capture, "production-phase-half")
            val mask = compose.frame(state, fixture.copy(stage = FixtureStage.InkMask), capture, "opaque-glyph-mask")
            val selected = compose.frame(state, fixture.copy(stage = FixtureStage.Production, parentSelected = true), capture, "parent-selected")
            val front = compose.frame(state, fixture.copy(stage = FixtureStage.Production, parentSelected = true, frontHue = true), capture, "foreground-changed")
            val report = JSONObject().put("case", case.toString()).put("samplingSourceMode", "wallpaper+independent-test-contrast")
            var measuredGeometry: JSONObject? = null
            runInkAssertionsAndMetrics(null, metrics = { if (measuredGeometry == null) capture.metrics(report) }) {
                measuredGeometry = assertFixtureGeometry(actual, selected, front, probe)
            }
            val geometry = requireNotNull(measuredGeometry)
            report.put("fixtureGeometry", geometry)
            val rawMotion = captureUnmodifiedWallpaperMotion(state, fixture, probe, capture)
            report.put("unmodifiedWallpaperMotion", rawMotion)
            val controls = capturePlainInkControls(state, fixture, probe, capture, report,
                "plain-ink", "layers-ink_$case", source, halfSource, actual)
            try { runInkAssertionsAndMetrics(controls.failure, metrics = { capture.metrics(report) }) {
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
                assertTrue("Original child-region minus original foreground guard must contain pixels", childMutation.count > 0)
                geometry.put("remainingOriginalComparisonPixels", childMutation.count).put("geometryCalibrated", true)
                controls.requireCalibrated()
                for (name in listOf("ink", "inkIcon")) {
                    report.put(name, assertInk(mask, source, halfSource, actual, halfActual, probe, name, controls))
                }
                if (case.tier == MaterialTier.Solid) {
                    assertTrue("The solid child is opaque to its parent", childMutation.maximum <= 2)
                } else assertTrue("Nested glass must include the parent's plane, not sample the wallpaper alone", childMutation.maximum > 2)
                val glyphChange = comparePixels(selected, front, foreground)
                report.put("foregroundMutation", glyphChange.json())
                assertTrue("The foreground mutation must actually reach the GPU window", glyphChange.maximum > 40)
                val selfSample = comparePixels(selected, front, childRegion, foreground.inflate(2 * d))
                report.put("noSelfSampling", selfSample.json())
                assertTrue("Changing foreground glyphs must not leak their colors into the child's sampled surface", selfSample.maximum <= 2)
            } } finally { controls.close() }
        }
    }

    /** Observes raw source poses, then restores and actually captures the exact entry scene. */
    internal fun captureUnmodifiedWallpaperMotion(
        state: MutableState<FixtureState>, fixture: FixtureState, probe: RenderProbe, capture: HardwareCapture,
    ): JSONObject {
        val previous = state.value
        val evidence = JSONObject().put("entryRequestedState", previous.opticalConfiguration())
            .put("extraContrastEnabled", false).put("scope", "Real Wallpaper exposed under original bench; existing 0-310dp base and markers retained")
            .put("localIconMotionAcceptance", false)
        val failure = collectInkControls(capture = {
            val zero = compose.frame(state, fixture.copy(stage = FixtureStage.Source, inkContrastSource = false, auroraTime = 0L), capture, "raw-wallpaper-source-zero")
            assertCapturedFixtureScene(zero, state.value)
            val half = compose.frame(state, fixture.copy(stage = FixtureStage.Source, inkContrastSource = false, auroraTime = 45_000_000L), capture, "raw-wallpaper-source-half")
            assertCapturedFixtureScene(half, state.value)
            evidence.put("zero", zero.evidence).put("half", half.evidence)
                .put("fullWindowDelta", comparePixels(zero, half, requireNotNull(probe.bounds["root"])).json())
        }, restore = {
            val restored = compose.frame(state, previous, capture, "raw-wallpaper-restored-entry")
            assertCapturedFixtureScene(restored, previous)
            assertTrue("Raw Wallpaper capture must restore its exact entry state", state.value == previous)
            evidence.put("restoredFrame", restored.evidence).put("restoredExactEntryScene", true)
        })
        failure?.let { throw it }
        return evidence
    }

    private fun assertCapturedFixtureScene(frame: CapturedFrame, expected: FixtureState) {
        val observed = frame.evidence.getJSONObject("configuration").getJSONObject("sceneDrawReceipt")
        assertTrue("Scene identity must come from the actually drawn root and a committed window frame", observed.getLong("drawPasses") > 0)
        assertTrue("The actually drawn scene must retain the exact stage, pose and paint controls",
            observed.getString("stage") == expected.stage.name && observed.getLong("auroraTime") == expected.auroraTime &&
                observed.getBoolean("inkContrastSource") == expected.inkContrastSource &&
                observed.getBoolean("parentSelected") == expected.parentSelected && observed.getBoolean("frontHue") == expected.frontHue &&
                observed.getBoolean("plainInkControl") == expected.plainInkControl && observed.getBoolean("sdkInkReference") == (expected.sdkInkReference != null) &&
                observed.getDouble("inkAlpha").toFloat() == expected.inkAlpha)
    }

    internal fun capturePlainInkControls(
        state: MutableState<FixtureState>, fixture: FixtureState, probe: RenderProbe,
        capture: HardwareCapture, report: JSONObject, prefix: String, captureName: String,
        source: CapturedFrame, halfSource: CapturedFrame, originalActual: CapturedFrame,
    ): InkReferenceControls {
        val previous = state.value
        assertPlainControlEntry(previous.stage.name, previous.inkContrastSource, previous.plainInkControl, previous.sdkInkReference != null)
        val entryFrame = compose.frame(state, previous, capture, "$prefix-entry-production")
        assertCapturedFixtureScene(entryFrame, previous)
        val originalPaint = probe.ink
        val controls = InkReferenceControls(originalPaint)
        val observations = JSONArray()
        val evidence = JSONObject().put("diagnosticOnly", true).put("productAcceptance", false)
            .put("coverageOracleChanged", true).put("whiteMaskRole", "Geometry only; never RGBA coverage")
            .put("sameExecutionCalibrationRequired", true).put("observations", observations).put("entryProductionFrame", entryFrame.evidence)
            .put("requestedFontFamily", case.settings.fontFamily ?: JSONObject.NULL).put("fontScale", probe.fontScale)
        report.put("plainInkControls", evidence)
        val plain = linkedMapOf<String, CapturedFrame>()
        val backgrounds = mapOf("zero" to source, "half" to halfSource)
        try {
            controls.failure = collectInkControls(capture = {
                for ((phase, time) in listOf("zero" to 0L, "half" to 45_000_000L)) {
                    val next = fixture.copy(stage = FixtureStage.Production, auroraTime = time, plainInkControl = true)
                    val frame = compose.frame(state, next, capture, "$prefix-$phase")
                    plain[phase] = frame
                    val registry = SdkInkReferenceCapture()
                    val sdkFrame = compose.frame(state, next.copy(sdkInkReference = registry), capture, "$prefix-$phase-sdk")
                    val details = JSONArray()
                    observations.put(JSONObject().put("phase", phase).put("plainFrame", frame.evidence)
                        .put("sdkFrame", sdkFrame.evidence).put("backgroundFrame", backgrounds.getValue(phase).evidence)
                        .put("rawPaintArgb", Integer.toHexString(originalPaint).padStart(8, '0'))
                        .put("rawPaintAlpha", Color.alpha(originalPaint)).put("references", details))
                    val refs = linkedMapOf<String, SdkGlyphReference>()
                    controls.references[phase] = refs
                    for (name in listOf("ink", "inkIcon")) {
                        val reference = readSdkGlyphReference(registry, name, sdkFrame, captureName, "$prefix-$phase-sdk-$name")
                        refs[name] = reference
                        details.put(reference.evidence)
                        assertTrue("SDK reference must retain the exact original raw paint", reference.evidence.getInt("paintArgb") == originalPaint)
                        val expected = originalActual.evidence.getJSONObject("windowCoordinates").getJSONArray(name)
                        assertTrue("SDK glyph geometry must match the original production scene", reference.evidence.getJSONArray("windowBounds").toString() == expected.toString())
                    }
                }
                evidence.put("captureComplete", true)
                for (phase in listOf("zero", "half")) for (name in listOf("ink", "inkIcon")) {
                    calibrateSdkReference(controls.references.getValue(phase).getValue(name), plain.getValue(phase),
                        backgrounds.getValue(phase), name, originalPaint)
                }
                for (name in listOf("ink", "inkIcon")) {
                    val zero = controls.references.getValue("zero").getValue(name)
                    val half = controls.references.getValue("half").getValue(name)
                    var maximum = 0
                    for (y in 0 until zero.bitmap.height) for (x in 0 until zero.bitmap.width) {
                        maximum = max(maximum, channelError(zero.bitmap.getPixel(x, y), half.bitmap.getPixel(x, y)))
                    }
                    evidence.put("${name}IndependentRgbaPhaseError", maximum)
                    assertTrue("Independent raw-paint RGBA must not depend on the wallpaper phase", maximum <= 2)
                }
                if (case.aurora) for (name in listOf("ink", "inkIcon")) {
                    val reference = controls.references.getValue("zero").getValue(name)
                    var sourceMaximum = 0
                    for (y in ceil(reference.bounds.top).toInt() until floor(reference.bounds.bottom).toInt()) {
                        for (x in ceil(reference.bounds.left).toInt() until floor(reference.bounds.right).toInt()) {
                            if (reference.normalizedCoverage(x, y, originalPaint) >= 254) {
                                sourceMaximum = max(sourceMaximum, channelError(source.pixel(x.toFloat(), y.toFloat()), halfSource.pixel(x.toFloat(), y.toFloat())))
                            }
                        }
                    }
                    evidence.put("${name}ControlledSourceCoreChange", sourceMaximum)
                    assertTrue("The controlled input must pass the original local source-change gate before evaluating production ink ($name)", sourceMaximum > 8)
                }
                controls.calibrated = true
            }, restore = {
                val restored = compose.frame(state, previous, capture, "$prefix-restored-production")
                assertCapturedFixtureScene(restored, previous)
                evidence.put("restoredProductionFrame", restored.evidence)
                assertTrue("Original Production state and raw paint must be restored before production checks", state.value == previous && probe.ink == originalPaint)
            })
        } catch (failure: Throwable) { controls.close(); throw failure }
        controls.failure?.let { evidence.put("controlFailure", it.stackTraceToString()) }
        evidence.put("calibrated", controls.calibrated).put("restoredOriginalRawPaint", probe.ink == originalPaint)
        return controls
    }

    internal fun assertInk(
        mask: CapturedFrame,
        source: CapturedFrame,
        halfSource: CapturedFrame,
        actual: CapturedFrame,
        halfActual: CapturedFrame,
        probe: RenderProbe,
        name: String,
        controls: InkReferenceControls,
    ): JSONObject {
        controls.requireCalibrated()
        val firstReference = controls.references.getValue("zero").getValue(name)
        val secondReference = controls.references.getValue("half").getValue(name)
        val region = firstReference.bounds
        assertTrue("The white mask remains a geometry reference only", mask.evidence.getJSONObject("windowCoordinates").getJSONArray(name).toString() == firstReference.evidence.getJSONArray("windowBounds").toString())
        var count = 0
        var inkError = 0
        var actualChange = 0
        var sourceChange = 0
        var blankCount = 0
        var blankError = 0
        var edgeCount = 0
        var edgeError = 0
        fun composite(ink: Int, background: Int, coverage: Double): Int {
            val alpha = coverage // SDK RGBA already includes the original source paint alpha.
            fun channel(foreground: Int, behind: Int) = (foreground * alpha + behind * (1 - alpha)).roundToInt()
            return Color.rgb(channel(Color.red(ink), Color.red(background)), channel(Color.green(ink), Color.green(background)),
                channel(Color.blue(ink), Color.blue(background)))
        }
        for (y in ceil(region.top).toInt() until floor(region.bottom).toInt()) {
            for (x in ceil(region.left).toInt() until floor(region.right).toInt()) {
                val firstAlpha = Color.alpha(firstReference.pixel(x, y)) / 255.0
                val secondAlpha = Color.alpha(secondReference.pixel(x, y)) / 255.0
                val first = source.pixel(x.toFloat(), y.toFloat())
                val second = halfSource.pixel(x.toFloat(), y.toFloat())
                val observed = actual.pixel(x.toFloat(), y.toFloat())
                val halfObserved = halfActual.pixel(x.toFloat(), y.toFloat())
                val coverage = firstReference.normalizedCoverage(x, y, controls.paint)
                if (coverage <= 1) {
                    blankCount++
                    blankError = max(blankError, max(channelError(first, observed), channelError(second, halfObserved)))
                    continue
                }
                val firstInk = expectedInk(probe.ink, first, case, name == "inkIcon")
                val secondInk = expectedInk(probe.ink, second, case, name == "inkIcon")
                if (coverage < 254) {
                    if (coverage in 16.0..239.0) {
                        edgeCount++
                        edgeError = max(edgeError, channelError(composite(firstInk, first, firstAlpha), observed))
                        edgeError = max(edgeError, channelError(composite(secondInk, second, secondAlpha), halfObserved))
                    }
                    continue
                }
                inkError = max(inkError, channelError(composite(firstInk, first, firstAlpha), observed))
                inkError = max(inkError, channelError(composite(secondInk, second, secondAlpha), halfObserved))
                actualChange = max(actualChange, channelError(observed, halfObserved))
                sourceChange = max(sourceChange, channelError(first, second))
                count++
            }
        }
        val result = JSONObject().put("opaqueGlyphPixels", count).put("maximumInkError", inkError)
            .put("actualPhaseChange", actualChange).put("sourcePhaseChange", sourceChange)
            .put("oracle", "Independent scalar color math plus same-execution calibrated SDK raw-paint RGBA; source alpha used once")
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
            assertTrue("The controlled sampling source must actually change under the original glyph mask", sourceChange > 8)
            assertTrue("Aurora foreground, including Solid subtle ink, must respond to the captured wallpaper", actualChange > 1)
        }
        return result
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases(switchStates = false)
    }
}

@RunWith(AndroidJUnit4::class)
class LiteTranslucentAuroraInkRenderingTest {
    private val case = RenderCase(MaterialTier.Solid, true, false, false, true)
    private val fixture = LiteLayerAndInkRenderingTest(case)
    @get:Rule val compose = fixture.compose
    private val captureUnmodifiedWallpaperMotion = fixture::captureUnmodifiedWallpaperMotion
    private val capturePlainInkControls = fixture::capturePlainInkControls
    private val assertInk = fixture::assertInk

    @Test
    fun translucentAuroraInkPreservesGlyphCoverageAndBlankPixels() {
        val fixture = FixtureState(inkAlpha = 0.8f, fixtureGeometry = JSONObject(), inkContrastSource = true, inkContrastDrawReceipt = JSONObject(), sceneDrawReceipt = JSONObject())
        withOpticalFixture(compose, case, "translucent-ink_$case", initial = fixture) { state, probe, capture ->
            val rawMotion = captureUnmodifiedWallpaperMotion(state, fixture, probe, capture)
            val source = compose.frame(state, fixture, capture, "source-alpha-08")
            val mask = compose.frame(state, fixture.copy(stage = FixtureStage.InkMask), capture, "coverage-alpha-08")
            val actual = compose.frame(state, fixture.copy(stage = FixtureStage.Production), capture, "ink-alpha-08")
            val halfSource = compose.frame(state, fixture.copy(auroraTime = 45_000_000L), capture, "source-half-alpha-08")
            val halfActual = compose.frame(state, fixture.copy(stage = FixtureStage.Production, auroraTime = 45_000_000L), capture, "ink-half-alpha-08")
            val report = JSONObject().put("sourceAlpha", Color.alpha(probe.ink)).put("unmodifiedWallpaperMotion", rawMotion).put("samplingSourceMode", "wallpaper+independent-test-contrast")
            val controls = capturePlainInkControls(state, fixture, probe, capture, report,
                "plain-ink-alpha-08", "translucent-ink_$case", source, halfSource, actual)
            try { runInkAssertionsAndMetrics(controls.failure, metrics = { capture.metrics(report) }) {
                controls.requireCalibrated()
                assertTrue("The fixture must actually draw a translucent source", Color.alpha(probe.ink) == 204)
                for (name in listOf("ink", "inkIcon")) report.put(name, assertInk(mask, source, halfSource, actual, halfActual, probe, name, controls))
            } } finally { controls.close() }
        }
    }
}

private fun assertPlainControlEntry(stage: String, contrastSource: Boolean, plainControl: Boolean, sdkReference: Boolean) {
    assertTrue("Plain controls must enter from the original controlled Production scene", stage == "Production" && contrastSource && !plainControl && !sdkReference)
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

internal class SdkGlyphReference(val bitmap: Bitmap, val bounds: Rect, val evidence: JSONObject, val metadataFile: File) : Closeable {
    fun pixel(x: Int, y: Int): Int = bitmap.getPixel(x - bounds.left.roundToInt(), y - bounds.top.roundToInt())
    fun normalizedCoverage(x: Int, y: Int, paint: Int): Double = Color.alpha(pixel(x, y)) * 255.0 / Color.alpha(paint)
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

internal class InkReferenceControls(val paint: Int) : Closeable {
    val references = linkedMapOf<String, MutableMap<String, SdkGlyphReference>>()
    var calibrated = false
    var failure: Throwable? = null
    fun requireCalibrated() {
        assertTrue("Independent SDK RGBA must calibrate against same-execution plain frames before testing production", calibrated && failure == null)
    }
    override fun close() { references.values.forEach { phase -> phase.values.forEach { it.close() } } }
}

/** Reads a separate SDK layer snapshot; this is not a PixelCopy or production shader alpha readback. */
private fun readSdkGlyphReference(
    registry: SdkInkReferenceCapture, name: String, frame: CapturedFrame, captureName: String, label: String,
): SdkGlyphReference {
    val bounds = requireNotNull(registry.bounds[name])
    assertTrue("SDK layer origin must have an observed integer pixel phase", bounds.left == bounds.left.roundToInt().toFloat() && bounds.top == bounds.top.roundToInt().toFloat())
    val layer = requireNotNull(registry.layers[name])
    val source = runBlocking { layer.toImageBitmap() }.asAndroidBitmap()
    val bitmap = try { requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) }
        finally { source.recycle() }
    try {
        assertTrue("SDK RGBA dimensions must match the original glyph bounds", bitmap.width == bounds.width.roundToInt() && bitmap.height == bounds.height.roundToInt())
        val root = File(requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)),
            "captures/${captureName.replace(Regex("[^A-Za-z0-9_.-]"), "_")}")
        check(root.isDirectory)
        val png = File(root, "$label-rgba.png")
        png.outputStream().use { assertTrue("SDK RGBA PNG encode must succeed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(png.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        File(root, "$label-rgba.png.sha256").writeText("$hash  ${png.name}\n")
        val metadata = JSONObject(requireNotNull(registry.details[name]).toString())
            .put("method", "Independent SDK GraphicsLayer.toImageBitmap; transparent original-paint RGBA")
            .put("windowFrame", frame.evidence).put("windowBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            .put("rgbaImage", png.name).put("rgbaSha256", hash).put("rgbaSizePx", JSONArray(listOf(bitmap.width, bitmap.height)))
            .put("colorSpace", bitmap.colorSpace?.name ?: JSONObject.NULL).put("alphaAlreadyIncludesSourcePaint", true)
            .put("premultiplicationReadback", "Android Bitmap.getPixel returns straight color and measured alpha")
            .put("calibrated", false)
        val file = File(root, "$label-rgba.json")
        file.writeText(metadata.toString(2))
        return SdkGlyphReference(bitmap, bounds, metadata, file)
    } catch (failure: Throwable) { bitmap.recycle(); throw failure }
}

/** The independent source must first predict an actual unfiltered original-paint frame. */
private fun calibrateSdkReference(reference: SdkGlyphReference, plain: CapturedFrame, background: CapturedFrame, name: String, paint: Int) {
    val frame = reference.evidence.getJSONObject("windowFrame")
    val referenceConfiguration = frame.getJSONObject("configuration")
    val referenceReceipt = referenceConfiguration.getJSONObject("inkContrastDefinition").getJSONObject("drawReceipt")
    assertTrue("SDK controlled-source pose must have actually reached Canvas drawing", referenceReceipt.getBoolean("canvasDrawExecuted") && referenceReceipt.getLong("drawReadTime") == referenceConfiguration.getLong("auroraTime"))
    for (other in listOf(plain, background)) {
        assertTrue("Reference, plain and background must share the candidate source identity", frame.getString("sourceRevision") == other.evidence.getString("sourceRevision"))
        assertTrue("Reference calibration must use the same density and font scale", frame.getDouble("density") == other.evidence.getDouble("density") && frame.getDouble("fontScale") == other.evidence.getDouble("fontScale"))
        assertTrue("Reference, plain and background must use the same controlled sampling source", frame.getJSONObject("configuration").getBoolean("inkContrastSource") && other.evidence.getJSONObject("configuration").getBoolean("inkContrastSource"))
        val configuration = other.evidence.getJSONObject("configuration")
        val receipt = configuration.getJSONObject("inkContrastDefinition").getJSONObject("drawReceipt")
        assertTrue("Calibration must use the same actual controlled-source pose", receipt.getBoolean("canvasDrawExecuted") && receipt.getLong("drawReadTime") == configuration.getLong("auroraTime") && configuration.getLong("auroraTime") == referenceConfiguration.getLong("auroraTime"))
    }
    assertTrue("Plain glyph must keep the same original bounds", plain.evidence.getJSONObject("windowCoordinates").getJSONArray(name).toString() == reference.evidence.getJSONArray("windowBounds").toString())
    var blank = 0
    var core = 0
    var edge = 0
    var blankError = 0
    var coreError = 0
    var edgeError = 0
    var maximumAlpha = 0
    val region = reference.bounds
    for (y in ceil(region.top).toInt() until floor(region.bottom).toInt()) {
        for (x in ceil(region.left).toInt() until floor(region.right).toInt()) {
            val rgba = reference.pixel(x, y)
            val coverage = reference.normalizedCoverage(x, y, paint)
            val behind = background.pixel(x.toFloat(), y.toFloat())
            val observed = plain.pixel(x.toFloat(), y.toFloat())
            val alpha = Color.alpha(rgba) / 255.0
            maximumAlpha = max(maximumAlpha, Color.alpha(rgba))
            fun mix(a: Int, b: Int) = (a * alpha + b * (1 - alpha)).roundToInt()
            val predicted = if (coverage <= 1) behind else Color.rgb(mix(Color.red(rgba), Color.red(behind)),
                mix(Color.green(rgba), Color.green(behind)), mix(Color.blue(rgba), Color.blue(behind)))
            val error = channelError(predicted, observed)
            when {
                coverage <= 1 -> { blank++; blankError = max(blankError, error) }
                coverage >= 254 -> { core++; coreError = max(coreError, error) }
                coverage in 16.0..239.0 -> { edge++; edgeError = max(edgeError, error) }
            }
        }
    }
    val result = JSONObject().put("sameExecution", true).put("plainFrame", plain.evidence).put("backgroundFrame", background.evidence)
        .put("blankPixels", blank).put("corePixels", core).put("edgePixels", edge)
        .put("blankMaximumError", blankError).put("coreMaximumError", coreError).put("edgeMaximumError", edgeError)
        .put("maximumRgbaAlpha", maximumAlpha).put("sourcePaintAlpha", Color.alpha(paint)).put("sourceAlphaAppliedOnce", true)
    reference.evidence.put("calibration", result)
    reference.metadataFile.writeText(reference.evidence.toString(2))
    assertTrue("Independent SDK source alpha cannot exceed the original paint alpha", maximumAlpha <= Color.alpha(paint))
    assertTrue("Independent SDK calibration needs actual blank, core and antialiased samples", blank >= 20 && core >= 20 && edge >= 20)
    assertTrue("Independent SDK blank calibration must preserve the actual background", blankError <= 2)
    assertTrue("Independent SDK core calibration must match the actual original-paint text", coreError <= 2)
    assertTrue("Independent SDK partial alpha calibration must match the actual original-paint text", edgeError <= 3)
    reference.evidence.put("calibrated", true)
    reference.metadataFile.writeText(reference.evidence.toString(2))
}

/** Checks measured rectangles against SDK-quantized original layout requests before pixel sampling. */
private fun assertMeasuredFixtureRects(root: Rect, parent: Rect, child: Rect, foreground: Rect, cornerProbe: Rect,
    density: Density, naturalFrontSize: IntSize, frontMinimum: IntSize, frontOverflow: Boolean) {
    with(density) {
        assertTrue("The actual fixture root must have a positive measured size", root.width > 0 && root.height > 0)
        val parentWidth = minOf(320.dp, root.width.toDp() - 48.dp).roundToPx()
        val parentHeight = 128.dp.roundToPx()
        val parentLeft = root.left + 24.dp.roundToPx()
        val parentTop = root.top + 366.dp.roundToPx()
        assertTrue("The actual parent must retain its original paneWidth by 128 dp request and window offset",
            parent == Rect(parentLeft, parentTop, parentLeft + parentWidth, parentTop + parentHeight))
        val childSize = IntSize(140.dp.roundToPx(), 72.dp.roundToPx())
        val childOffset = Alignment.Center.align(childSize, IntSize(parentWidth, parentHeight), LayoutDirection.Ltr)
        val childLeft = parent.left + childOffset.x
        val childTop = parent.top + childOffset.y
        assertTrue("The actual child must retain its centered original 140 by 72 dp request",
            child == Rect(childLeft, childTop, childLeft + childSize.width, childTop + childSize.height))
        val cornerLeft = parent.left + (-20).dp.roundToPx()
        val cornerTop = parent.top + (-20).dp.roundToPx()
        assertTrue("The actual corner probe must retain its original offset and 140 by 72 dp request",
            cornerProbe == Rect(cornerLeft, cornerTop, cornerLeft + childSize.width, cornerTop + childSize.height))
        assertTrue("The original front text must be measured naturally without inherited minima or overflow",
            frontMinimum == IntSize.Zero && !frontOverflow && naturalFrontSize.width > 0 && naturalFrontSize.height > 0 &&
                naturalFrontSize.width < childSize.width && naturalFrontSize.height < childSize.height)
        val frontOffset = Alignment.Center.align(naturalFrontSize, childSize, LayoutDirection.Ltr)
        val frontLeft = child.left + frontOffset.x
        val frontTop = child.top + frontOffset.y
        assertTrue("The actual front must match its real TextLayout size centered in the original child",
            foreground == Rect(frontLeft, frontTop, frontLeft + naturalFrontSize.width, frontTop + naturalFrontSize.height))
        assertTrue("The actual test child must be contained by its parent", parent.contains(child.topLeft) && child.right <= parent.right && child.bottom <= parent.bottom)
    }
}

private fun assertFixtureGeometry(actual: CapturedFrame, selected: CapturedFrame, front: CapturedFrame,
    probe: RenderProbe): JSONObject {
    val boxes = actual.evidence.getJSONObject("windowCoordinates")
    fun rect(name: String): Rect {
        val v = boxes.getJSONArray(name)
        return Rect(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getDouble(2).toFloat(), v.getDouble(3).toFloat())
    }
    val trace = actual.evidence.getJSONObject("configuration").getJSONObject("fixtureGeometry")
    val layout = trace.getJSONObject("front-text-layout")
    assertTrue("The natural front retains the original single-line MMMM text", layout.getString("text") == "MMMM" && layout.getInt("lineCount") == 1)
    assertMeasuredFixtureRects(rect("root"), rect("parent"), rect("child"), rect("front"), rect("cornerProbe"),
        Density(probe.density, probe.fontScale), IntSize(layout.getInt("widthPx"), layout.getInt("heightPx")),
        IntSize(layout.getInt("minWidthPx"), layout.getInt("minHeightPx")), layout.getBoolean("overflow"))
    for (frame in listOf(selected, front)) for (name in listOf("root", "parent", "child", "front", "cornerProbe")) {
        assertTrue("Changing only parent or front paint cannot move the test geometry", frame.evidence.getJSONObject("windowCoordinates").getJSONArray(name).toString() == boxes.getJSONArray(name).toString())
    }
    for (name in listOf("parent-content", "child-content")) assertTrue("Test-only child measurement must release inherited minima", !trace.getJSONObject(name).getBoolean("propagateMinConstraints"))
    return JSONObject().put("unclippedWindowCoordinates", boxes).put("constraintTrace", trace)
        .put("geometryMeasuredPreflight", true).put("geometryCalibrated", false)
}
