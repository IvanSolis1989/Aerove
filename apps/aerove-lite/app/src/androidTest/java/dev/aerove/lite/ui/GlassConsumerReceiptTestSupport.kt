package dev.aerove.lite.ui

import android.view.View
import androidx.compose.ui.geometry.Rect
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.MaterialTier
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in for one existing representative only; never participates in optical acceptance. */
internal class GlassConsumerReceiptTestSupport private constructor(private val session: LiteGlassConsumerDiagnostics.Session) : AutoCloseable {
    private val pose = GlassConsumerPoseObservation()
    private var stage = 0L
    private var state: FixtureState? = null
    private var sourceIdentity: Int? = null
    private var sourceStage: Long? = null
    private var rootStage: Long? = null
    private var sourceBounds: Rect? = null
    private var sourceDrawNanos = 0L
    private var rootDrawNanos = 0L
    private var scene = JSONObject()
    private var exhausted = false
    private var armed: Arm? = null
    private var committed: JSONObject? = null

    companion object {
        fun requested(case: RenderCase, name: String): Boolean =
            InstrumentationRegistry.getArguments().getString("glassConsumerReceipt") == "true" &&
                name.startsWith("switch-optics_") && case.tier == MaterialTier.Clear && !case.aurora && !case.dark && !case.checked && !case.enabled

        fun create(case: RenderCase, name: String, root: View): GlassConsumerReceiptTestSupport? {
            if (!requested(case, name)) return null
            return GlassConsumerReceiptTestSupport(LiteGlassConsumerDiagnostics.observe(root))
        }
    }

    @Synchronized fun beginStage(next: FixtureState) {
        stage++
        if (stage > 32) exhausted = true
        state = next
        sourceStage = null
        rootStage = null
        scene = next.sceneDrawReceipt ?: JSONObject()
    }

    @Synchronized fun sourceLayout(bounds: Rect) {
        sourceBounds = bounds
        pose.sourceLayout(GlassConsumerRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    @Synchronized fun rootLayout(root: Any, bounds: Rect) {
        pose.rootLayout(root, GlassConsumerRect(bounds.left, bounds.top, bounds.right, bounds.bottom))
    }

    @Synchronized fun sourceDraw(actual: FixtureState, source: Any, width: Float, height: Float, density: Float) {
        if (state !== actual || exhausted) return
        pose.sourceDraw(source, width, height, density)
        sourceIdentity = System.identityHashCode(source)
        sourceStage = stage
        sourceDrawNanos = System.nanoTime()
        try {
            scene.put("consumerSource", JSONObject().put("stageEpoch", stage).put("sourceIdentity", sourceIdentity)
                .put("drawNanoTime", sourceDrawNanos).put("sizePx", numbers(listOf(width, height))).put("density", density)
                .put("stage", actual.stage.name).put("revision", actual.revision).put("auroraTime", actual.auroraTime)
                .put("switchMarkers", actual.switchMarkers).put("thumbMarker", actual.thumbMarker)
                .put("method", "Original source Canvas after original marker drawing"))
        } catch (_: Exception) { exhausted = true }
    }

    @Synchronized fun rootDraw(actual: FixtureState, root: Any) {
        if (state !== actual || exhausted) return
        pose.rootDraw(root)
        rootStage = stage
        rootDrawNanos = System.nanoTime()
        try {
            scene.put("consumerRoot", JSONObject().put("stageEpoch", stage).put("drawNanoTime", rootDrawNanos)
                .put("method", "Original OpticalFixture root after drawContent"))
        } catch (_: Exception) { exhausted = true }
    }

    private data class Arm(val before: GlassConsumerReadout, val stage: Long, val source: Int?, val sourceStage: Long?,
        val rootStage: Long?, val sourceBounds: Rect?, val poseRevision: Long, val armNanos: Long)

    @Synchronized fun arm(view: View) {
        armed = Arm(session.snapshot(view), stage, sourceIdentity, sourceStage, rootStage, sourceBounds, pose.revision, System.nanoTime())
        committed = null
    }

    @Synchronized fun commit(observation: FrameCommitObservation) {
        committed = try { observation.matrixSubmissionEvidence() }
            catch (failure: Exception) { JSONObject().put("observationUnavailable", failure.javaClass.simpleName) }
    }

    @Synchronized fun finish(view: View, copyOwner: Long): JSONObject {
        val arm = armed ?: return unavailable("no-arm")
        val commit = committed
        armed = null
        committed = null
        val after = session.snapshot(view)
        val commitNanos = commit?.optLong("commitNanoTime")?.takeIf { it > 0 }
        val identity = GlassConsumerFrameIdentity(session.id, arm.stage, System.identityHashCode(view), System.identityHashCode(view.rootView),
            sourceIdentity, sourceStage, rootStage, !exhausted && pose.revision == arm.poseRevision && stage == arm.stage && sourceIdentity == arm.source &&
                sourceStage == arm.sourceStage && rootStage == arm.rootStage && sourceBounds != null && sourceBounds == arm.sourceBounds &&
                sourceDrawNanos > 0 && rootDrawNanos >= sourceDrawNanos && rootDrawNanos >= arm.armNanos && commitNanos != null && rootDrawNanos <= commitNanos,
            arm.armNanos, commitNanos, commit?.optLong("captureId")?.takeIf { it > 0 }, copyOwner,
            commit?.optBoolean("originalDeadlinePassed", false) == true, arm.poseRevision, pose.revision)
        val linked = associateGlassConsumers(identity, arm.before, after)
        val commitMatches = commit != null && commit.optInt("viewIdentity") == identity.viewIdentity && commit.optInt("rootIdentity") == identity.rootIdentity
        return JSONObject().put("schema", "aerove-glass-consumer-cpu-v1").put("diagnosticOnly", true).put("acceptancePassed", false)
            .put("status", if (commitMatches) linked.status else "unknown").put("reason", if (commitMatches) linked.reason else "commit-view-root")
            .put("sessionId", session.id).put("stageEpoch", arm.stage).put("viewIdentity", identity.viewIdentity).put("rootIdentity", identity.rootIdentity)
            .put("sourceIdentity", sourceIdentity ?: JSONObject.NULL).put("sourceStageEpoch", sourceStage ?: JSONObject.NULL)
            .put("rootStageEpoch", rootStage ?: JSONObject.NULL).put("sourceWindowBounds", sourceBounds?.let { numbers(listOf(it.left, it.top, it.right, it.bottom)) } ?: JSONObject.NULL)
            .put("sourceDrawNanoTime", sourceDrawNanos).put("rootDrawNanoTime", rootDrawNanos).put("armNanoTime", arm.armNanos)
            .put("commit", commit ?: JSONObject.NULL).put("pixelCopyOwnerId", copyOwner).put("posesStable", identity.posesStable)
            .put("poseRevisionBefore", identity.poseRevisionBefore).put("poseRevisionAfter", identity.poseRevisionAfter)
            .put("sceneDrawReceipt", JSONObject(scene.toString())).put("before", readout(arm.before)).put("after", readout(after))
            .put("rawInputTile", JSONObject().put("status", "notAvailable").put("reason", "Existing private consumer tile has no side-effect-free export owner"))
            .put("gpuUniformReadback", "notAvailable").put("gpuFrameIdentity", "unknown")
            .put("configurationReuse", "Configuration may precede arm; linked requires an actual consumer draw between arm and commit, otherwise unknown")
            .put("scope", "Actual CPU setter/configuration and layout receipts; no GPU readback or exact GPU-frame identity")
    }

    private fun readout(value: GlassConsumerReadout) = JSONObject().put("active", value.active).put("overflow", value.overflow)
        .put("sessionId", value.sessionId).put("viewIdentity", value.viewIdentity).put("rootIdentity", value.rootIdentity)
        .put("consumers", JSONArray(value.consumers.map { pane ->
            JSONObject().put("paneId", pane.paneId).put("role", pane.role.name).put("sourceIdentity", pane.sourceIdentity)
                .put("layoutRevision", pane.layoutRevision)
                .put("drawConfigurationEpoch", pane.drawConfigurationEpoch ?: JSONObject.NULL).put("drawNanoTime", pane.drawNanoTime)
                .put("layout", pane.layout?.let { layout -> JSONObject().put("boundsSpace", "window; actual onGloballyPositioned unclipped bounds")
                    .put("bounds", numbers(listOf(layout.windowBounds.left, layout.windowBounds.top, layout.windowBounds.right, layout.windowBounds.bottom)))
                    .put("sizePx", JSONArray(listOf(layout.widthPx, layout.heightPx))).put("density", layout.density)
                    .put("rootIdentity", layout.rootIdentity).put("attached", layout.attached) } ?: JSONObject.NULL)
                .put("configuration", pane.configuration?.let { config -> JSONObject().put("epoch", config.epoch).put("shaderIdentity", config.shaderIdentity)
                    .put("shaderSourceSha256", config.sourceSha256).put("geometryDp", numbers(listOf(config.widthDp, config.heightDp)))
                    .put("tilePx", numbers(listOf(config.capture.widthPixels, config.capture.heightPixels))).put("density", config.capture.density)
                    .put("paddingPx", config.capture.paddingPixels).put("capturedBlurSigmaPx", config.capture.capturedBlurSigmaPixels)
                    .put("viewportPx", numbers(config.viewport)).put("complete", config.complete).put("nanoTime", config.nanoTime)
                    .put("setterCalls", JSONArray(config.uniforms.map { uniform -> JSONObject().put("name", uniform.name)
                        .put("values", numbers(uniform.values)).put("float32RawBits", JSONArray(uniform.bits)) })) } ?: JSONObject.NULL)
        }))

    fun unavailable(reason: String) = JSONObject().put("schema", "aerove-glass-consumer-cpu-v1").put("status", "unknown")
        .put("reason", reason).put("diagnosticOnly", true).put("acceptancePassed", false)
    private fun numbers(values: List<Float>) = JSONArray(values.map { if (it.isFinite()) it else JSONObject.NULL })
    @Synchronized override fun close() { session.close(); armed = null; committed = null; state = null }
}
