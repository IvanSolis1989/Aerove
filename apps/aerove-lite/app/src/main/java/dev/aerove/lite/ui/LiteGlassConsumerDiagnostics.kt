package dev.aerove.lite.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.aerove.lite.optics.AeroveGlassCapture
import dev.aerove.lite.optics.AeroveGlassUniformObserver
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class GlassConsumerRole { SwitchTrack, SwitchThumb }
internal val LocalLiteGlassConsumerRole = staticCompositionLocalOf<GlassConsumerRole?> { null }
internal data class GlassConsumerRect(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class GlassConsumerLayout(val windowBounds: GlassConsumerRect, val widthPx: Int, val heightPx: Int,
    val density: Float, val rootIdentity: Int, val attached: Boolean)
internal data class GlassConsumerUniform(val name: String, val values: List<Float>, val bits: List<Int>)
internal data class GlassConsumerConfiguration(val epoch: Long, val shaderIdentity: Int, val sourceSha256: String,
    val widthDp: Float, val heightDp: Float, val capture: AeroveGlassCapture, val viewport: List<Float>,
    val uniforms: List<GlassConsumerUniform>, val complete: Boolean, val nanoTime: Long)
internal data class GlassConsumerSnapshot(val paneId: Long, val role: GlassConsumerRole, val sourceIdentity: Int,
    val layout: GlassConsumerLayout?, val configuration: GlassConsumerConfiguration?,
    val drawConfigurationEpoch: Long?, val drawNanoTime: Long, val layoutRevision: Long)
internal data class GlassConsumerReadout(val sessionId: Long, val rootIdentity: Int, val viewIdentity: Int,
    val consumers: List<GlassConsumerSnapshot>, val overflow: Boolean, val active: Boolean)

internal class GlassConsumerPoseObservation {
    var revision = 0L
        private set
    private var source: Any? = null
    private var sourceBounds: GlassConsumerRect? = null
    private var sourceWidth = Float.NaN
    private var sourceHeight = Float.NaN
    private var sourceDensity = Float.NaN
    private var root: Any? = null
    private var rootBounds: GlassConsumerRect? = null

    fun sourceLayout(bounds: GlassConsumerRect) {
        if (sourceBounds != bounds) { sourceBounds = bounds; revision++ }
    }
    fun sourceDraw(source: Any, width: Float, height: Float, density: Float) {
        if (this.source !== source || sourceWidth != width || sourceHeight != height || sourceDensity != density) {
            this.source = source
            sourceWidth = width; sourceHeight = height; sourceDensity = density
            revision++
        }
    }
    fun rootLayout(root: Any, bounds: GlassConsumerRect) {
        if (this.root !== root || rootBounds != bounds) {
            this.root = root; rootBounds = bounds
            revision++
        }
    }
    fun rootDraw(root: Any) {
        if (this.root !== root) { this.root = root; revision++ }
    }
}

/** CPU call receipts only. No capture, scheduling, invalidation or Compose state writes. */
internal object LiteGlassConsumerDiagnostics {
    private val sequence = AtomicLong()
    private val current = AtomicReference<Session?>()
    val hasSubscription: Boolean get() = current.get() != null
    val subscriptionId: Long? get() = current.get()?.id

    fun observe(root: Any): Session {
        val session = Session(sequence.incrementAndGet(), root)
        check(current.compareAndSet(null, session)) { "A glass consumer diagnostic subscriber already exists" }
        return session
    }

    class Session internal constructor(val id: Long, root: Any) : AutoCloseable {
        private var root: Any? = root
        private val rootIdentity = System.identityHashCode(root)
        private val entries = linkedMapOf<Long, Handle>()
        private val hashes = mutableMapOf<String, String>()
        private var overflow = false
        private var configurationEpoch = 0L
        @Synchronized internal fun attach(root: Any, view: Any, role: GlassConsumerRole, source: Any): Handle? {
            if (current.get() !== this || this.root !== root) return null
            if (entries.size >= 8) { overflow = true; return null }
            return Handle(this, sequence.incrementAndGet(), view, role, System.identityHashCode(source)).also { entries[it.id] = it }
        }
        @Synchronized internal fun owns(handle: Handle) = current.get() === this && entries[handle.id] === handle
        @Synchronized internal fun detach(handle: Handle) { if (entries[handle.id] === handle) entries.remove(handle.id) }
        @Synchronized internal fun layout(handle: Handle, layout: GlassConsumerLayout) {
            if (owns(handle) && handle.layout != layout) { handle.layout = layout; handle.layoutRevision++ }
        }
        @Synchronized internal fun begin(handle: Handle, shader: Any, source: String, width: Float, height: Float,
            capture: AeroveGlassCapture, viewport: FloatArray): Recording? {
            if (!owns(handle)) return null
            if (source !in hashes && hashes.size >= 8) { overflow = true; return null }
            val hash = hashes.getOrPut(source) {
                MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            handle.configuration = null
            if (handle.configuringEpoch != null) handle.configurationConflict = true
            handle.configuringEpoch = ++configurationEpoch
            return Recording(handle, configurationEpoch, System.identityHashCode(shader), hash, width, height, capture, viewport.toList())
        }
        @Synchronized internal fun complete(recording: Recording, configuration: GlassConsumerConfiguration) {
            val handle = recording.handle
            if (!owns(handle)) return
            if (handle.configuringEpoch != configuration.epoch) {
                handle.configurationConflict = true
                handle.configuration = handle.configuration?.copy(complete = false)
            } else {
                handle.configuringEpoch = null
                handle.configuration = configuration.copy(complete = configuration.complete && !handle.configurationConflict)
            }
        }
        @Synchronized internal fun draw(handle: Handle) {
            if (!owns(handle)) return
            handle.drawConfigurationEpoch = handle.configuration?.epoch
            handle.drawNanoTime = System.nanoTime()
        }
        @Synchronized fun snapshot(view: Any): GlassConsumerReadout = GlassConsumerReadout(id, rootIdentity, System.identityHashCode(view),
            entries.values.filter { it.view === view }.map { GlassConsumerSnapshot(it.id, it.role, it.sourceIdentity, it.layout,
                it.configuration, it.drawConfigurationEpoch, it.drawNanoTime, it.layoutRevision) }, overflow, current.get() === this)
        @Synchronized override fun close() {
            current.compareAndSet(this, null)
            entries.clear()
            hashes.clear()
            root = null
        }
    }

    class Handle internal constructor(internal val session: Session, internal val id: Long, internal val view: Any,
        internal val role: GlassConsumerRole, internal val sourceIdentity: Int) {
        internal var layout: GlassConsumerLayout? = null
        internal var layoutRevision = 0L
        internal var configuration: GlassConsumerConfiguration? = null
        internal var configuringEpoch: Long? = null
        internal var configurationConflict = false
        internal var drawConfigurationEpoch: Long? = null
        internal var drawNanoTime = 0L
    }

    class Recording internal constructor(internal val handle: Handle, private val epoch: Long, private val shader: Int,
        private val hash: String, private val width: Float, private val height: Float, private val capture: AeroveGlassCapture,
        private val viewport: List<Float>) : AeroveGlassUniformObserver {
        private val values = mutableListOf<GlassConsumerUniform>()
        private var failed = false
        private var completed = false
        @Synchronized override fun onUniformSet(name: String, values: FloatArray) {
            if (completed || !handle.session.owns(handle)) return
            if (this.values.size >= 64 || values.size > 64 || values.any { !it.isFinite() }) { failed = true; return }
            this.values += GlassConsumerUniform(name, values.toList(), values.map(Float::toRawBits))
        }
        @Synchronized override fun onObserverFailure(error: Exception) { failed = true }
        @Synchronized fun complete() {
            if (completed) return
            completed = true
            handle.session.complete(this, GlassConsumerConfiguration(epoch, shader, hash, width, height, capture, viewport,
                values.toList(), !failed, System.nanoTime()))
        }
    }

    fun attach(root: Any, view: Any, role: GlassConsumerRole, source: Any): Handle? = current.get()?.attach(root, view, role, source)
    fun detach(handle: Handle) = handle.session.detach(handle)
    fun recordLayout(handle: Handle, layout: GlassConsumerLayout) = handle.session.layout(handle, layout)
    fun beginConfiguration(handle: Handle, shader: Any, source: String, widthDp: Float, heightDp: Float,
        capture: AeroveGlassCapture, viewport: FloatArray): Recording? = handle.session.begin(handle, shader, source, widthDp, heightDp, capture, viewport)
    fun recordDraw(handle: Handle) = handle.session.draw(handle)
}

internal data class GlassConsumerFrameIdentity(val sessionId: Long, val stageEpoch: Long, val viewIdentity: Int, val rootIdentity: Int,
    val sourceIdentity: Int?, val sourceStageEpoch: Long?, val rootStageEpoch: Long?, val posesStable: Boolean, val armNanos: Long,
    val commitNanos: Long?, val captureId: Long?, val pixelCopyOwnerId: Long?, val deadlinePassed: Boolean,
    val poseRevisionBefore: Long = 0L, val poseRevisionAfter: Long = 0L)
internal data class GlassConsumerAssociation(val status: String, val reason: String)
private val GlassConsumerSetterNames = listOf("uSize", "uFrame", "uSample", "uClamp", "uTexels", "uHazeSample", "uHazeClamp", "uHaze",
    "uShape", "uCorners", "uCurves", "uQuadrants", "uOptics", "uTint", "uLight", "uGlow", "uGlowSpread", "uEdge", "uRim", "uOutlined",
    "uProfile", "uShineTurns", "uShineLit", "uShineEnds", "uShineShares", "uHazeOffset")

/** A link proves stable observed CPU state around one original commit; it never proves GPU uniforms/pixels. */
internal fun associateGlassConsumers(frame: GlassConsumerFrameIdentity, before: GlassConsumerReadout, after: GlassConsumerReadout): GlassConsumerAssociation {
    fun unknown(reason: String) = GlassConsumerAssociation("unknown", reason)
    if (!before.active || !after.active || before.overflow || after.overflow) return unknown("inactive-or-capacity")
    if (before.sessionId != frame.sessionId || after.sessionId != frame.sessionId || before.rootIdentity != frame.rootIdentity ||
        after.rootIdentity != frame.rootIdentity || before.viewIdentity != frame.viewIdentity || after.viewIdentity != frame.viewIdentity) return unknown("session-view-root")
    val commit = frame.commitNanos ?: return unknown("no-commit")
    if (!frame.deadlinePassed || commit < frame.armNanos || frame.captureId == null || frame.pixelCopyOwnerId == null) return unknown("original-commit-or-copy")
    if (frame.poseRevisionBefore != frame.poseRevisionAfter) return unknown("source-root-pose-changed")
    if (!frame.posesStable || frame.sourceIdentity == null || frame.sourceStageEpoch != frame.stageEpoch || frame.rootStageEpoch != frame.stageEpoch) return unknown("source-root-stage")
    if (before.consumers.size != 2 || after.consumers.size != 2 || GlassConsumerRole.entries.any { role -> after.consumers.count { it.role == role } != 1 }) return unknown("non-unique-consumers")
    for (pane in after.consumers) {
        val old = before.consumers.singleOrNull { it.paneId == pane.paneId } ?: return unknown("consumer-changed")
        if (old.layoutRevision != pane.layoutRevision) return unknown("consumer-layout-changed")
        if (old.copy(drawNanoTime = pane.drawNanoTime, drawConfigurationEpoch = pane.drawConfigurationEpoch) != pane) return unknown("cpu-state-changed")
        val layout = pane.layout ?: return unknown("no-layout")
        if (!layout.attached || layout.rootIdentity != frame.rootIdentity || layout.widthPx <= 0 || layout.heightPx <= 0 || !layout.density.isFinite() || layout.density <= 0) return unknown("layout-root")
        if (listOf(layout.windowBounds.left, layout.windowBounds.top, layout.windowBounds.right, layout.windowBounds.bottom).any { !it.isFinite() }) return unknown("layout-bounds")
        if (pane.sourceIdentity != frame.sourceIdentity) return unknown("source-identity")
        val config = pane.configuration ?: return unknown("no-config")
        if (!config.complete || pane.drawConfigurationEpoch != config.epoch || pane.drawNanoTime < config.nanoTime ||
            pane.drawNanoTime < frame.armNanos || pane.drawNanoTime > commit) return unknown("draw-config-order-or-cached-draw")
        if (config.viewport.size != 4 || config.viewport.any { !it.isFinite() } || config.uniforms.map { it.name } != GlassConsumerSetterNames) return unknown("incomplete-uniforms-or-viewport")
    }
    return GlassConsumerAssociation("linked", "stable CPU configuration/layout/source across original View commit; GPU frame identity unobserved")
}
