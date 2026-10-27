package dev.aerove.lite.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.aerove.lite.MainActivity
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs
import kotlin.math.roundToInt

/** Direct demand regression, additional to every unchanged original optical gate. */
@RunWith(Parameterized::class)
class LiteSolidBackdropDemandRenderingTest(private val aurora: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun defaultAndMountExitUnmountRemountKeepOnlyClippedBackground() {
        val case = RenderCase(MaterialTier.Solid, aurora, true, false, true)
        val fixture = FixtureState(fixtureGeometry = JSONObject(), inkContrastSource = true,
            inkContrastDrawReceipt = JSONObject(), sceneDrawReceipt = JSONObject())
        withOpticalFixture(compose, case, "solid-demand_$case", initial = fixture) { state, probe, capture ->
            val observations = JSONArray()
            val report = JSONObject().put("case", case.toString()).put("observations", observations)
                .put("gpuRecordCounterAvailable", false).put("firstPresentedPixelsObserved", false)
                .put("captureTiming", "First requested committed capture after one Compose clock frame; no 400ms settling delay")
            fun snap(pose: SolidDemandPose, selected: Boolean = false, front: Boolean = false): Pair<CapturedFrame, SolidDemandEvidence> {
                val evidence = SolidDemandEvidence()
                val next = fixture.copy(stage = FixtureStage.Production, solidDemandPose = pose,
                    solidDemandEvidence = evidence, parentSelected = selected, frontHue = front)
                compose.runOnUiThread { state.value = next }
                compose.mainClock.advanceTimeByFrame()
                val frame = capture.capture("${observations.length()}-${pose.name}-${if (selected) "selected" else "base"}-${if (front) "cyan" else "magenta"}",
                    configuration = next.opticalConfiguration())
                val observed = evidence.observed
                assertTrue("The actual probe must draw the requested pose", observed.optInt("draws") > 0 &&
                    observed.getJSONObject("lastDraw").getString("pose") == pose.name)
                assertTrue("The first actual draw must use the new parent paint state", observed.getJSONObject("firstDraw").getBoolean("parentSelected") == selected)
                observations.put(JSONObject().put("frame", frame.evidence).put("actualProbeDraw", JSONObject(observed.toString())))
                return frame to evidence
            }
            runInkAssertionsAndMetrics(null, metrics = { capture.metrics(report) }) {
                val source = compose.frame(state, fixture, capture, "source-no-parent")
                val (default, defaultEvidence) = snap(SolidDemandPose.DefaultNoGlass)
                assertSame("The actual omitted/default API must retain the ancestor source", defaultEvidence.ancestor, defaultEvidence.nested)
                val d = probe.density
                val parent = requireNotNull(probe.bounds["parent"])
                val child = requireNotNull(probe.bounds["child"])
                val front = requireNotNull(probe.bounds["front"])
                val corner = requireNotNull(probe.bounds["cornerProbe"])
                val root = requireNotNull(probe.bounds["root"])
                assertTrue("Actual parent keeps the original requested dimensions", abs(parent.width - minOf(320 * d, root.width - 48 * d).roundToInt()) <= 1 && abs(parent.height - (128 * d).roundToInt()) <= 1)
                assertTrue("Actual child keeps original natural size", abs(child.width - (140 * d).roundToInt()) <= 1 && abs(child.height - (72 * d).roundToInt()) <= 1)
                assertTrue("Foreground and original child comparison must be nonempty", front.width > 0 && front.height > 0 && child.contains(front.topLeft) && front.right <= child.right && front.bottom <= child.bottom)
                val patch = Rect(parent.left + 40 * d, parent.top + 12 * d, parent.left + 60 * d, parent.top + 20 * d)
                val outside = Offset(parent.left + 0.5f * d, parent.top + 0.5f * d)
                assertTrue("Original probe must cover original parentPatch and clipped corner", corner.contains(patch.topLeft) && corner.contains(patch.bottomRight) && corner.contains(outside))
                assertTrue("Default Solid exports no added parent plane into the readback source", comparePixels(source, default, patch).maximum <= 2)
                val (mounted, mountedEvidence) = snap(SolidDemandPose.Mounted)
                assertNotSame("A real mounted Clear descendant must get a parent export", mountedEvidence.ancestor, mountedEvidence.nested)
                val (selected, _) = snap(SolidDemandPose.Mounted, selected = true)
                report.put("parentMutation", comparePixels(mounted, selected, patch).json())
                assertTrue("The original parentPatch must observe actual parent paint mutation", comparePixels(mounted, selected, patch).maximum > 3)
                assertTrue("Actual export preserves the original clipped-corner gate", channelError(source.pixel(outside.x, outside.y), selected.pixel(outside.x, outside.y)) <= 2)
                val childRegion = child.deflate(6 * d)
                assertTrue("Original opaque child stays independent of parent", comparePixels(mounted, selected, childRegion, front.inflate(2 * d)).maximum <= 2)
                val (exit, exitEvidence) = snap(SolidDemandPose.VisibleExit, selected = true)
                assertNotSame("Visible exiting glass retains demand despite logical page inactivity", exitEvidence.ancestor, exitEvidence.nested)
                assertTrue("Exit pose must actually carry inactive logical-page state", !exitEvidence.observed.getJSONObject("lastDraw").getBoolean("logicalPageActive"))
                assertTrue("Visible exiting glass keeps the same clipped parent pixels", comparePixels(selected, exit, patch).maximum <= 2)
                val (unmounted, unmountedEvidence) = snap(SolidDemandPose.Unmounted, selected = true)
                assertSame("Unmounted glass withdraws the export source", unmountedEvidence.ancestor, unmountedEvidence.nested)
                assertTrue("After withdrawal readback returns to actual ancestor pixels", comparePixels(source, unmounted, patch).maximum <= 2)
                val (remounted, remountedEvidence) = snap(SolidDemandPose.Remounted, selected = true)
                assertNotSame("Remount must not reuse the removed combined source", mountedEvidence.nested, remountedEvidence.nested)
                assertNotSame("Remount must publish an actual export", remountedEvidence.ancestor, remountedEvidence.nested)
                assertTrue("First requested remount capture uses latest parent, not the old unselected frame", comparePixels(selected, remounted, patch).maximum <= 2 && comparePixels(mounted, remounted, patch).maximum > 3)
                val (changedFront, _) = snap(SolidDemandPose.Remounted, selected = true, front = true)
                assertTrue("Original front mutation must reach real pixels", comparePixels(remounted, changedFront, front).maximum > 40)
                assertTrue("Foreground must not feed back into the sampled child", comparePixels(remounted, changedFront, childRegion, front.inflate(2 * d)).maximum <= 2)
                for (name in listOf("ink", "inkIcon")) {
                    val bounds = requireNotNull(probe.bounds[name])
                    for (frame in listOf(mounted, selected, exit, unmounted, remounted, changedFront))
                        assertTrue("Local demand must not change independent $name pixels", comparePixels(default, frame, bounds).maximum <= 2)
                }
                for (evidence in listOf(defaultEvidence, mountedEvidence, exitEvidence, unmountedEvidence, remountedEvidence)) {
                    assertSame("Ink must retain the original independent root source", defaultEvidence.ink, evidence.ink)
                    assertTrue("Actual draw must observe unchanged Ink source", evidence.observed.getJSONObject("lastDraw").getBoolean("inkIsAncestor"))
                }
                report.put("regressionAssertionsPassed", true)
            }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "aurora={0}")
        fun cases() = listOf(arrayOf(false), arrayOf(true))
    }
}
