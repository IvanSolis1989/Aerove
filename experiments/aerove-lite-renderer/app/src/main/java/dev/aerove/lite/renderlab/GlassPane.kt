package dev.aerove.lite.renderlab

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

enum class MaterialMode { SOLID, FROST, CLEAR }

internal enum class PaneKind { CARD, TRACK, THUMB, LENS }

internal class GlassPane(
    name: String,
    private val density: Float,
    program: String,
    private val kind: PaneKind,
) {
    val bounds = RectF()
    private val shape = Superellipse()
    private val node = RenderNode(name).apply {
        setClipToBounds(true)
        setHasOverlappingRendering(true)
    }
    private val shader = RuntimeShader(program)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = MaterialMode.SOLID
    private var padding = 0
    private var originX = 0
    private var originY = 0
    private var materialKey: List<Any>? = null

    fun configure(rect: RectF, corner: Float, mode: MaterialMode, selected: Boolean = false) {
        bounds.set(
            rect.left.roundToInt().toFloat(),
            rect.top.roundToInt().toFloat(),
            rect.right.roundToInt().toFloat(),
            rect.bottom.roundToInt().toFloat(),
        )
        this.mode = mode
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val radius = min(corner, min(width, height) * 0.5f)
        shape.resize(width, height, radius)
        paint.color = when (kind) {
            PaneKind.CARD -> Color.rgb(250, 250, 252)
            PaneKind.TRACK -> if (selected) Color.rgb(48, 91, 208) else Color.rgb(204, 207, 216)
            PaneKind.THUMB -> Color.WHITE
            PaneKind.LENS -> Color.rgb(237, 238, 243)
        }
        val depth = if (mode != MaterialMode.CLEAR) 0f else when (kind) {
            PaneKind.THUMB, PaneKind.LENS -> -4.3f * density
            PaneKind.CARD -> 18f * density
            PaneKind.TRACK -> 9f * density
        }
        val dispersion = if (mode != MaterialMode.CLEAR) 0f else when (kind) {
            PaneKind.THUMB, PaneKind.LENS -> 6.5f * density
            else -> 2.8f * density
        }
        val bevel = when (kind) {
            PaneKind.THUMB, PaneKind.LENS -> 5.5f * density
            else -> 18f * density
        }
        val blur = if (mode == MaterialMode.FROST) 6f * density else 0f
        padding = ceil(abs(depth) + dispersion + blur * 3f + 3f).toInt()
        originX = bounds.left.toInt() - padding
        originY = bounds.top.toInt() - padding
        val inputWidth = width.toInt() + padding * 2
        val inputHeight = height.toInt() + padding * 2
        node.setPosition(originX, originY, originX + inputWidth, originY + inputHeight)
        val key = listOf(width, height, radius, mode, selected, padding)
        if (key == materialKey) return
        materialKey = key
        node.setRenderEffect(null)
        if (mode == MaterialMode.SOLID) return
        shader.setFloatUniform("inputSize", inputWidth.toFloat(), inputHeight.toFloat())
        shader.setFloatUniform("pane", padding.toFloat(), padding.toFloat(), padding + width, padding + height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("optics", depth, dispersion, bevel, if (mode == MaterialMode.CLEAR) 0.28f else 0f)
        val alpha = when {
            kind == PaneKind.TRACK && selected -> 0.18f
            mode == MaterialMode.FROST -> 0.14f
            else -> 0.055f
        }
        val tint = if (kind == PaneKind.TRACK && selected) {
            Color.argb((alpha * 255).roundToInt(), 48, 91, 208)
        } else {
            Color.argb((alpha * 255).roundToInt(), 255, 255, 255)
        }
        shader.setColorUniform("tint", tint)
        val optics = RenderEffect.createRuntimeShaderEffect(shader, "background")
        node.setRenderEffect(
            if (blur > 0f) {
                RenderEffect.createChainEffect(
                    optics,
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
                )
            } else optics,
        )
    }

    fun draw(canvas: Canvas, background: RenderNode) {
        check(background !== node) { "A pane cannot sample itself" }
        if (mode == MaterialMode.SOLID) {
            canvas.save()
            canvas.translate(bounds.left, bounds.top)
            canvas.drawPath(shape.path, paint)
            canvas.restore()
            return
        }
        // The unmasked crop is the effect input; shape coverage is applied after sampling.
        val input = node.beginRecording()
        try {
            input.translate(-originX.toFloat(), -originY.toFloat())
            input.drawRenderNode(background)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    fun contains(x: Float, y: Float): Boolean = shape.contains(bounds, x, y)

    fun release() {
        node.setRenderEffect(null)
        node.discardDisplayList()
        materialKey = null
    }
}
