package dev.aerove.lite.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.MainActivity
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

@RunWith(Parameterized::class)
class LiteMaterialRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun threeMaterialsAndIndependentSwitchPlanesUseRealWindowPixels() {
        withOpticalFixture(compose, case, "materials_$case") { state, probe, capture ->
            val source = capture.capture("source-dark-patch", configuration = state.value.opticalConfiguration())
            val actual = compose.frame(state, FixtureState(FixtureStage.Production), capture, "production-dark-patch")
            val control = compose.frame(state, FixtureState(FixtureStage.NoLens), capture, "no-lens-dark-patch")
            val lightSource = compose.frame(state, FixtureState(FixtureStage.Source, revision = 1), capture, "source-light-patch")
            val changed = compose.frame(state, FixtureState(FixtureStage.Production, revision = 1), capture, "production-light-patch")
            val lightControl = compose.frame(state, FixtureState(FixtureStage.NoLens, revision = 1), capture, "no-lens-light-patch")
            val mask = compose.frame(state, FixtureState(FixtureStage.OutlineMask), capture, "opaque-superellipse-outline")
            val metrics = JSONObject().put("case", case.toString()).put("oracle", "Hardware source, production and independently rendered no-lens control")
            val diagnosticFailures = mutableListOf<Throwable>()
            val diagnostics = JSONArray()
            if (case.tier == MaterialTier.Clear && !case.aurora && case.dark && !case.checked && case.enabled) {
                for (lens in listOf(LensControl.DispersionOff, LensControl.DispersionOffRedEdge, LensControl.DispersionOffBlueEdge)) {
                    for (revision in listOf(0, 1)) {
                        val next = FixtureState(FixtureStage.Production, revision = revision, lensControl = lens)
                        val label = "diagnostic-${lens.name}-${if (revision == 0) "dark" else "light"}-patch"
                        val result = JSONObject().put("label", label).put("configuration", next.opticalConfiguration())
                            .put("diagnosticOnly", true).put("acceptancePassed", false)
                        try {
                            compose.frame(state, next, capture, label).use { frame ->
                                result.put("captured", true).put("windowImage", frame.evidence.getString("windowImage"))
                                    .put("windowSha256", frame.evidence.getString("windowSha256"))
                            }
                        } catch (failure: Throwable) {
                            rethrowMatrixCancellation(failure)
                            diagnosticFailures += failure
                            result.put("captured", false).put("failure", failure.stackTraceToString())
                        }
                        diagnostics.put(result)
                    }
                }
            }
            metrics.put("diagnosticControls", diagnostics)
            runOpticalAcceptanceWithEvidence(diagnosticFailures, formal = {
                metrics.put("card", cardOptics(source, actual, control, probe, case))
                metrics.put("switchDarkPatch", switchCompositing(source, actual, control, probe, case))
                metrics.put("switchLightPatch", switchCompositing(lightSource, changed, lightControl, probe, case))
                assertCorners(source, actual, probe, OpticalPaneShape, mask)
                assertCorners(lightSource, changed, probe, OpticalPaneShape, mask)
                val pane = requireNotNull(probe.bounds["pane"])
                val center = Rect(pane.center.x - 8 * probe.density, pane.center.y - 8 * probe.density, pane.center.x + 8 * probe.density, pane.center.y + 8 * probe.density)
                val difference = comparePixels(actual, changed, center)
                metrics.put("backgroundRevision", difference.json())
                if (case.tier == MaterialTier.Solid) {
                    assertTrue("Solid must remain opaque after the real captured backdrop changes", difference.maximum <= 2)
                } else assertTrue("A glass surface must use the current backdrop, not a stale or opaque snapshot", difference.mean > 8)
                if (case.tier == MaterialTier.Clear) {
                    val regular = compose.frame(state, FixtureState(FixtureStage.Production, regularPane = true), capture, "default-regular-dark-patch")
                    val regularChanged = compose.frame(state, FixtureState(FixtureStage.Production, revision = 1, regularPane = true), capture, "default-regular-light-patch")
                    assertCorners(source, regular, probe, OpticalPaneShape, mask)
                    assertCorners(lightSource, regularChanged, probe, OpticalPaneShape, mask)
                    val liveRegular = comparePixels(regular, regularChanged, center)
                    val clearCenter = comparePixels(source, regular, center)
                    metrics.put("defaultRegularBackgroundRevision", liveRegular.json()).put("defaultRegularTransparentCenter", clearCenter.json())
                    assertTrue("The actual default regular card must keep sampling the live backdrop", liveRegular.mean > 8)
                    assertTrue("A clear default card cannot add the old generic opaque/tinted center", clearCenter.maximum <= 2)
                }
            }, evidence = { originalFailure ->
                originalFailure?.let { metrics.put("originalAcceptanceFailure", it.stackTraceToString()) }
                capture.metrics(metrics)
            })
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases()
    }
}

internal fun runOpticalAcceptanceWithEvidence(
    diagnosticFailures: MutableList<Throwable>,
    formal: () -> Unit,
    evidence: (Throwable?) -> Unit,
) {
    var originalFailure: Throwable? = null
    try { formal() } catch (failure: Throwable) {
        rethrowMatrixCancellation(failure)
        originalFailure = failure
    }
    try { evidence(originalFailure) } catch (failure: Throwable) {
        rethrowMatrixCancellation(failure, originalFailure)
        diagnosticFailures += failure
    }
    val failure = originalFailure ?: diagnosticFailures.firstOrNull()
    if (failure != null) {
        diagnosticFailures.filter { it !== failure }.forEach(failure::addSuppressed)
        throw failure
    }
}

@RunWith(Parameterized::class)
class LiteSwitchRenderingTest(private val case: RenderCase) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun restingTrackAndThumbFollowTheMaterialIndependently() {
        val fixture = FixtureState(switchMarkers = true, switchForegroundControl = true)
        withOpticalFixture(compose, case, "switch-optics_$case", fixture) { state, probe, capture ->
            val baseline = fixture.copy(switchMarkers = false)
            val sourceBaseline = compose.frame(state, baseline.copy(stage = FixtureStage.Source), capture, "source-without-marker")
            val productionBaseline = compose.frame(state, baseline.copy(stage = FixtureStage.Production), capture, "production-without-marker")
            val controlBaseline = compose.frame(state, baseline.copy(stage = FixtureStage.NoLens), capture, "no-lens-without-marker")
            val metrics = JSONObject().put("case", case.toString())
            var acceptanceFailure: Throwable? = null
            try {
                val bounds = requireNotNull(probe.bounds["switch"])
                val d = probe.density
                for (thumb in listOf(false, true)) {
                    val marker = fixture.copy(thumbMarker = thumb)
                    val label = if (thumb) "thumb" else "track"
                    val source = compose.frame(state, marker.copy(stage = FixtureStage.Source), capture, "$label-source")
                    val actual = compose.frame(state, marker.copy(stage = FixtureStage.Production), capture, "$label-production")
                    val control = compose.frame(state, marker.copy(stage = FixtureStage.NoLens), capture, "$label-no-lens")
                    val x = bounds.left + (if (thumb) { if (case.checked) 48 else 28 } else { if (case.checked) 22 else 38 }) * d
                    val top = bounds.top + (if (thumb) 12 else 8) * d
                    val end = top + (if (thumb) 9 else 18) * d
                    fun profile(frame: CapturedFrame) = lineProfile(frame, probe, x, top + 0.5f * d, end, halfWidthDp = 1.5f, originY = top)
                    fun markerOnly(frame: CapturedFrame, background: CapturedFrame): LineProfile {
                        val signal = profile(frame)
                        val floor = profile(background)
                        return signal.copy(values = DoubleArray(signal.values.size) { i -> (signal.values[i] - floor.values[i]).coerceAtLeast(0.0) })
                    }
                    val original = markerOnly(source, sourceBaseline)
                    val refracted = markerOnly(actual, productionBaseline)
                    val flat = markerOnly(control, controlBaseline)
                    val alpha = if (case.enabled || case.tier != MaterialTier.Solid) 1.0 else 0.48
                    val withoutGroupFade = refracted.copy(values = DoubleArray(refracted.values.size) { i ->
                        (refracted.values[i] - original.values[i] * (1 - alpha)) / alpha
                    })
                    val noLensWithoutFade = flat.copy(values = DoubleArray(flat.values.size) { i ->
                        (flat.values[i] - original.values[i] * (1 - alpha)) / alpha
                    })
                    metrics.put(if (thumb) "thumb" else "track", JSONObject()
                        .put("source", original.json()).put("productionRaw", profile(actual).json()).put("noLensRaw", profile(control).json())
                        .put("markerIsolation", "Subtract each measured no-marker GPU frame so a stationary Full rim cannot masquerade as displaced source detail")
                        .put("productionWithoutGroupFade", withoutGroupFade.json()))
                    fun captureFailedFlatThumb(primary: AssertionError) {
                        if (!thumb || case.tier != MaterialTier.Clear) return
                        val receipt = JSONObject()
                            .put("diagnosticOnly", true)
                            .put("acceptancePassed", false)
                            .put("primaryAssertion", primary.message ?: primary.javaClass.name)
                            .put("captureLabel", "thumb-flat-with-production-track")
                            .put("noMarkerBaselineStage", FixtureStage.FlatThumb.name)
                        try {
                            val flatBaseline = compose.frame(state, baseline.copy(stage = FixtureStage.FlatThumb), capture, "thumb-flat-with-production-track-no-marker")
                            receipt.put("noMarkerBaseline", flatBaseline.evidence.optString("windowImage"))
                                .put("noMarkerBaselineSha256", flatBaseline.evidence.optString("windowSha256"))
                                .put("noMarkerBaselineMetadata", "thumb-flat-with-production-track-no-marker.json")
                            val flatThumb = compose.frame(state, marker.copy(stage = FixtureStage.FlatThumb), capture, "thumb-flat-with-production-track")
                            val flatMarker = markerOnly(flatThumb, flatBaseline)
                            val sameTrackDifference = comparePixels(actual, flatThumb, Rect(x - 1.5f * d, top + 0.5f * d, x + 1.5f * d, end))
                            receipt.put("captured", true)
                                .put("windowImage", flatThumb.evidence.optString("windowImage"))
                                .put("windowSha256", flatThumb.evidence.optString("windowSha256"))
                                .put("flatThumbMarkerAgainstMatchingFlatTrackNoMarker", flatMarker.json())
                                .put("productionThumbVsFlatThumbSameTrack", sameTrackDifference.json())
                                .put("usedForAcceptance", false).put("originalAcceptanceRemainsFailed", true)
                        } catch (diagnosticFailure: Throwable) {
                            if (primary !== diagnosticFailure) primary.addSuppressed(diagnosticFailure)
                            receipt.put("captured", false)
                                .put("diagnosticFailureType", diagnosticFailure.javaClass.name)
                                .put("diagnosticFailure", diagnosticFailure.message ?: "(no message)")
                        }
                        try {
                            metrics.getJSONObject("thumb").put("failureOnlySameTrackFlatThumb", receipt)
                        } catch (receiptFailure: Throwable) {
                            if (primary !== receiptFailure) primary.addSuppressed(receiptFailure)
                        }
                    }
                    try {
                        assertTrue("Missing hardware marker for the ${if (thumb) "thumb" else "track"}", original.contrast > 80)
                        if (case.tier == MaterialTier.Clear) {
                            val outward = original.centroid - withoutGroupFade.centroid
                            metrics.getJSONObject(if (thumb) "thumb" else "track").put("outwardDisplacementDp", outward)
                            assertTrue("The resting ${if (thumb) "thumb" else "track"} must itself displace the marker, including after removing disabled group opacity (dp=$outward)", outward >= 0.5)
                            assertTrue("The clear marker must remain visible after accounting for the intentional tint", withoutGroupFade.contrast >= original.contrast * 0.35)
                            assertTrue("The non-refracting control must leave the marker in place", abs(noLensWithoutFade.centroid - original.centroid) <= 0.25)
                        }
                    } catch (failure: AssertionError) {
                        try {
                            captureFailedFlatThumb(failure)
                        } catch (diagnosticFailure: Throwable) {
                            if (failure !== diagnosticFailure) failure.addSuppressed(diagnosticFailure)
                        }
                        throw failure
                    }
                    if (case.tier == MaterialTier.Clear && thumb) {
                        val trackOnly = compose.frame(state, marker.copy(stage = FixtureStage.FlatThumb), capture, "thumb-flat-with-production-track")
                        val difference = comparePixels(actual, trackOnly, Rect(x - 1.5f * d, top + 0.5f * d, x + 1.5f * d, end))
                        metrics.getJSONObject("thumb").put("thumbVsUnrefractingThumbOnSameTrack", difference.json())
                        assertTrue("Thumb must add its own optical displacement; inheriting the track's lens is insufficient", difference.maximum > 30)
                    }
                    if (case.tier != MaterialTier.Clear) {
                        val region = Rect(x - 1.5f * d, top + 0.5f * d, x + 1.5f * d, end)
                        val difference = comparePixels(actual, control, region)
                        metrics.getJSONObject(if (thumb) "thumb" else "track").put("noLensChannelError", difference.json())
                        assertTrue("Solid/Frosted ${if (thumb) "thumb" else "track"} must match its opaque/blur-only control without lens, rim or highlight", difference.maximum <= 2)
                        if (case.tier == MaterialTier.Frosted) assertTrue("Frosted switch planes must blur the marker", withoutGroupFade.contrast < original.contrast * 0.25)
                    }
                }
            } catch (failure: Throwable) {
                acceptanceFailure = failure
                throw failure
            } finally {
                try {
                    capture.metrics(metrics)
                } catch (metricsFailure: Throwable) {
                    val primary = acceptanceFailure
                    if (primary == null) throw metricsFailure
                    if (primary !== metricsFailure) primary.addSuppressed(metricsFailure)
                }
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = renderCases()
    }
}

internal fun renderCases(tiers: List<MaterialTier> = MaterialTier.entries, switchStates: Boolean = true): List<Array<Any>> = buildList {
    for (tier in tiers) for (aurora in listOf(false, true)) for (dark in listOf(false, true)) {
        for (checked in if (switchStates) listOf(false, true) else listOf(false)) {
            for (enabled in if (switchStates) listOf(false, true) else listOf(true)) add(arrayOf<Any>(RenderCase(tier, aurora, dark, checked, enabled)))
        }
    }
}

internal fun ComposeContentTestRule.frame(
    state: MutableState<FixtureState>,
    next: FixtureState,
    capture: HardwareCapture,
    label: String,
): CapturedFrame {
    runOnUiThread { capture.consumerStage(next); state.value = next }
    mainClock.advanceTimeBy(400)
    waitForIdle()
    return capture.capture(label, configuration = next.opticalConfiguration())
}

internal fun withOpticalFixture(
    compose: LiteComposeRule,
    case: RenderCase,
    name: String,
    initial: FixtureState = FixtureState(),
    test: (MutableState<FixtureState>, RenderProbe, HardwareCapture) -> Unit,
) {
    if (case.aurora) fullAuroraMesh(FullAuroraSeed, FullAuroraColors, case.dark)
    compose.mainClock.autoAdvance = false
    val state = mutableStateOf(initial)
    val probe = RenderProbe()
    if (GlassConsumerReceiptTestSupport.requested(case, name)) {
        compose.runOnUiThread {
            probe.consumerReceipt = GlassConsumerReceiptTestSupport.create(case, name, compose.activity.window.decorView.rootView)
            probe.consumerReceipt?.beginStage(initial)
        }
    }
    try {
        compose.replaceAppContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { OpticalFixture(case, state.value, probe) }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertViewport(probe)
        HardwareCapture(compose, probe, name).use { test(state, probe, it) }
    } finally {
        probe.consumerReceipt?.close()
        probe.consumerReceipt = null
    }
}
