package dev.aerove.lite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Collects calibration evidence only; never substitutes for the original Card acceptance method. */
class LiteChromaticCalibrationRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun independentSdkRgbDisplacementsAndActualD0OutlinesProduceSpatialEvidence() {
        val case = RenderCase(MaterialTier.Clear, false, true, false, true)
        withOpticalFixture(compose, case, "chromatic-calibration_$case") { state, probe, capture ->
            val report = JSONObject().put("diagnosticOnly", true).put("calibrated", false)
                .put("productAcceptanceChanged", false).put("productAcceptancePassed", false)
                .put("status", "CONTROL_MEASUREMENTS_ONLY_PRODUCT_CALIBRATION_NOT_ESTABLISHED")
                .put("cardMetricCandidate", "directional-full-depth-v1")
                .put("metricInputs", "Pixel channel arrays, density and existing top/bottom orientation only")
                .put("numericGridPx", 1.0 / 16).put("numericSearchPx", JSONArray(listOf(-8, 8)))
                .put("numericPrecisionIsNotMeasurementAccuracy", true)
                .put("boundaryPolicy", "Replicate observed endpoint values for interpolation; all original rows remain in residual; truncation reported")
            val records = JSONArray()
            report.put("records", records)
            fun fitJson(fit: SpatialFit) = JSONObject().put("shiftPx", fit.shiftPx).put("rmse", fit.rmse)
                .put("zeroShiftRmse", fit.zeroShiftRmse).put("gain", fit.gain).put("offset", fit.offset)
                .put("adjacentGridRmseGap", fit.adjacentGridRmseGap)
            fun channelJson(c: ChannelSpatialDiagnostic) = JSONObject().put("full", fitJson(c.full))
                .put("rising", fitJson(c.rising)).put("falling", fitJson(c.falling))
                .put("referenceRisingEnergy", c.referenceRisingEnergy).put("referenceFallingEnergy", c.referenceFallingEnergy)
                .put("flankShiftDisagreementPx", c.flankShiftDisagreementPx)
            fun measure(frame: CapturedFrame): JSONObject {
                val pane = requireNotNull(probe.bounds["pane"])
                val result = JSONObject()
                for (bottom in listOf(false, true)) {
                    val start = if (bottom) pane.bottom - 30 * probe.density else pane.top
                    val end = if (bottom) pane.bottom else pane.top + 30 * probe.density
                    val profiles = (0..2).map { lineProfile(frame, probe, pane.center.x, start, end, halfWidthDp = 1f, channel = it) }
                    val diagnostic = chromaticSpatialDiagnostic(profiles.map { it.values }, probe.density.toDouble(), bottom)
                    result.put(if (bottom) "bottom" else "top", JSONObject().put("red", channelJson(diagnostic.red))
                        .put("blue", channelJson(diagnostic.blue)).put("signedRedBlueDp", diagnostic.signedRedBlueDp)
                        .put("greenBandTouchesStart", diagnostic.greenBandTouchesStart).put("greenBandTouchesEnd", diagnostic.greenBandTouchesEnd)
                        .put("unmodifiedFullStripProfiles", JSONArray(profiles.map { it.json() }))
                        .put("directional2d", directionalChromaticProfile(frame, probe, pane, bottom).json()))
                }
                return result
            }
            fun verifyDirectionalControl(diagnostic: JSONObject, knownSpanPx: Float) {
                for (band in listOf("top", "bottom")) {
                    val result = diagnostic.getJSONObject(band).getJSONObject("directional2d")
                    assertTrue("A calibration row cannot lose its marker or silently crop a horizontal flank",
                        result.getJSONArray("missingSignalRows").length() == 0 && result.getJSONArray("boundaryRows").length() == 0)
                    val measured = result.getDouble("meanSignedRedBlueDp")
                    result.put("knownSignedSpanDp", knownSpanPx / probe.density)
                        .put("absoluteKnownSpanErrorDp", abs(measured - knownSpanPx / probe.density))
                    // Labels verify the independently measured result; they never enter the pixel estimator.
                    assertTrue("Known positive/negative displacement and zero/outline controls must remain distinguishable at 0.15dp",
                        when {
                            knownSpanPx > 0f -> measured >= 0.15
                            knownSpanPx < 0f -> measured <= -0.15
                            else -> abs(measured) < 0.15
                        })
                }
            }
            runInkAssertionsAndMetrics(null, metrics = { capture.metrics(report) }) {
                val poses = listOf(SdkChromaticStencil.Zero, SdkChromaticStencil.Zero, SdkChromaticStencil.Subpixel,
                    SdkChromaticStencil.OnePixel, SdkChromaticStencil.TwoPixels, SdkChromaticStencil.Reverse,
                    SdkChromaticStencil.ReverseSubpixel, SdkChromaticStencil.UniformRed, SdkChromaticStencil.UniformBlue)
                for ((index, pose) in poses.withIndex()) {
                    val receipt = JSONObject()
                    val next = FixtureState(stage = FixtureStage.Production, sdkChromaticStencil = pose, sdkChromaticDrawReceipt = receipt)
                    compose.frame(state, next, capture, "sdk-$index-${pose.name}").use { frame ->
                        assertTrue("The independent SDK Canvas must actually draw its declared controlled input",
                            receipt.optBoolean("drawExecuted") && receipt.getString("pose") == pose.name && !receipt.getBoolean("productionLensInvoked"))
                        val pane = requireNotNull(probe.bounds["pane"])
                        val core = frame.pixel(pane.center.x, pane.top + 8 * probe.density)
                        val blank = frame.pixel(pane.center.x, pane.top + 20 * probe.density)
                        assertTrue("Actual SDK RGB core must retain the declared original white paint", channelError(core, 0xFFF7F8FC.toInt()) <= 2)
                        assertTrue("Actual SDK blank must retain the original dark bench background", channelError(blank, 0xFF172432.toInt()) <= 2)
                        val diagnostic = measure(frame)
                        records.put(JSONObject().put("bank", "INDEPENDENT_SDK").put("capture", frame.evidence)
                            .put("actualSdkDraw", JSONObject(receipt.toString())).put("actualCoreArgb", core).put("actualBlankArgb", blank)
                            .put("diagnostic", diagnostic))
                        verifyDirectionalControl(diagnostic, pose.redBlueSpanPx)
                    }
                }
                for (lens in listOf(LensControl.DispersionOff, LensControl.DispersionOffRedEdge, LensControl.DispersionOffBlueEdge)) {
                    val next = FixtureState(stage = FixtureStage.Production, lensControl = lens)
                    compose.frame(state, next, capture, "actual-${lens.name}").use { frame ->
                        // Label is a bank identity, not an estimator input. These are genuine current-run controls.
                        val diagnostic = measure(frame)
                        records.put(JSONObject().put("bank", "ACTUAL_D0_CONTROLS").put("capture", frame.evidence)
                            .put("diagnostic", diagnostic))
                        verifyDirectionalControl(diagnostic, 0f)
                    }
                }
                report.put("captureBankComplete", true).put("calibrated", false)
            }
        }
    }
}
