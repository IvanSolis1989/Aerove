package dev.aerove.lite.renderlab

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal class Superellipse {
    val path = Path()
    private var width = -1f
    private var height = -1f
    private var radius = -1f

    fun resize(width: Float, height: Float, corner: Float) {
        val radius = min(corner, min(width, height) * 0.5f).coerceAtLeast(0f)
        if (this.width == width && this.height == height && this.radius == radius) return
        this.width = width
        this.height = height
        this.radius = radius
        path.rewind()
        if (width <= 0f || height <= 0f) return
        path.moveTo(radius, 0f)
        path.lineTo(width - radius, 0f)
        corner(width - radius, radius, radius, 3)
        path.lineTo(width, height - radius)
        corner(width - radius, height - radius, radius, 0)
        path.lineTo(radius, height)
        corner(radius, height - radius, radius, 1)
        path.lineTo(0f, radius)
        corner(radius, radius, radius, 2)
        path.close()
    }

    private fun corner(cx: Float, cy: Float, radius: Float, quadrant: Int) {
        val start = quadrant * STEPS
        for (index in 1..STEPS) {
            val offset = ((start + index) % (STEPS * 4)) * 2
            path.lineTo(cx + UNIT[offset] * radius, cy + UNIT[offset + 1] * radius)
        }
    }

    fun contains(bounds: RectF, x: Float, y: Float): Boolean {
        val px = abs(x - bounds.centerX()) - (bounds.width() * 0.5f - radius)
        val py = abs(y - bounds.centerY()) - (bounds.height() * 0.5f - radius)
        val ox = max(px, 0f)
        val oy = max(py, 0f)
        return (ox * ox * ox * ox + oy * oy * oy * oy).pow(0.25f) -
            radius + min(max(px, py), 0f) <= 0f
    }

    companion object {
        private const val STEPS = 64
        private val UNIT = FloatArray(STEPS * 8).apply {
            for (index in 0 until STEPS * 4) {
                val angle = index * Math.PI * 2.0 / (STEPS * 4)
                val x = cos(angle)
                val y = sin(angle)
                this[index * 2] = (Math.signum(x) * sqrt(abs(x))).toFloat()
                this[index * 2 + 1] = (Math.signum(y) * sqrt(abs(y))).toFloat()
            }
        }
    }
}
