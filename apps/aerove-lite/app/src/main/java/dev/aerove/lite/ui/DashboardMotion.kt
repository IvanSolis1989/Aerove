package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.aerove.lite.Traffic
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun rememberDashboardTime(moving: Boolean): State<Float> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = LocalLitePageActive.current && !LocalLiteReducedMotion.current
    return produceState(0f, moving, lifecycle, active) {
        if (moving && active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (isActive) withFrameNanos { now ->
                if (previous != 0L) value += ((now - previous) / 1_000_000_000f).coerceAtMost(0.05f)
                previous = now
            }
        }
    }
}

// The contour and standing waves match outbound_motion.dart; Backdrop still owns capture and composition.
internal class DashboardBubbleOutline(
    private val fullness: Float,
    private val time: Float,
    private val wobble: Float,
    private val squeeze: Float = 0f,
    private val irregularity: Float = 1f,
) : Shape {
    private val phase = fullness * 1.2
    private val base = (0.41 + 0.025 * fullness) * (1 - 0.1 * abs(wobble))

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        fun radius(angle: Double): Double {
            var skin = 0.018 * wobble * sin(2.3 * time + 0.9)
            for (mode in modes) skin += wobble * mode[1] * sin(mode[2] * time + mode[3]) * cos(mode[0] * (angle - mode[4] * time) + mode[5])
            return base * (1 + 0.07 * squeeze.coerceIn(-0.6f, 1f) * cos(2 * angle) +
                irregularity * 0.025 * cos(2 * angle + 1.1 + phase) + irregularity * 0.03 * cos(3 * angle + 0.6 - 0.7 * phase) + skin)
        }
        fun slope(angle: Double): Double {
            var skin = 0.0
            for (mode in modes) skin += wobble * mode[1] * sin(mode[2] * time + mode[3]) * mode[0] * sin(mode[0] * (angle - mode[4] * time) + mode[5])
            return -base * (0.14 * squeeze.coerceIn(-0.6f, 1f) * sin(2 * angle) +
                irregularity * 0.05 * sin(2 * angle + 1.1 + phase) + irregularity * 0.09 * sin(3 * angle + 0.6 - 0.7 * phase) + skin)
        }
        fun point(angle: Double): Offset {
            val reach = radius(angle)
            return Offset(((0.5 + reach * cos(angle)) * size.width).toFloat(), ((0.5 + reach * sin(angle)) * size.height).toFloat())
        }
        fun tangent(angle: Double): Offset {
            val reach = radius(angle)
            val rise = slope(angle)
            return Offset(((rise * cos(angle) - reach * sin(angle)) * size.width).toFloat(), ((rise * sin(angle) + reach * cos(angle)) * size.height).toFloat())
        }
        val step = 2 * PI / 24
        val first = point(0.0)
        val path = Path().apply { moveTo(first.x, first.y) }
        for (index in 0 until 24) {
            val from = index * step
            val to = from + step
            val out = point(from) + tangent(from) * (step / 3).toFloat()
            val into = point(to) - tangent(to) * (step / 3).toFloat()
            val end = point(to)
            path.cubicTo(out.x, out.y, into.x, into.y, end.x, end.y)
        }
        path.close()
        return Outline.Generic(path)
    }

    private companion object {
        val modes = arrayOf(
            doubleArrayOf(2.0, 0.055, 3.1, 0.0, 0.35, 0.4),
            doubleArrayOf(3.0, 0.028, 4.7, 1.3, -0.5, 1.1),
            doubleArrayOf(4.0, 0.014, 6.3, 2.1, 0.7, 2.3),
            doubleArrayOf(5.0, 0.008, 8.9, 0.7, -0.9, 0.5),
        )
    }
}

@Composable
internal fun DashboardRibbon(time: State<Float>, modifier: Modifier) {
    val dark = LocalLiteVisuals.current.dark
    Box(modifier.drawWithCache {
        val bright = if (dark) 0.12f else 0.26f
        val shade = if (dark) 0.08f else 0.13f
        val band = Path()
        val sheen = Path()
        val upper = FloatArray(74)
        val lower = FloatArray(74)
        val brushes = ribbonStrands.map { strand ->
            val alpha = strand[7].toFloat()
            Brush.horizontalGradient(
                0f to Color(0xFFD8E4EF).copy(alpha = 0.02f * alpha),
                0.27f to Color.White.copy(alpha = bright * alpha),
                0.5f to Color(0xFF73859B).copy(alpha = shade * alpha),
                0.72f to Color.White.copy(alpha = 0.8f * bright * alpha),
                1f to Color(0xFF73859B).copy(alpha = 0.02f * alpha),
                endX = size.width,
            )
        }
        fun through(path: Path, points: FloatArray, reverse: Boolean) {
            for (index in 1 until 36) {
                val i = if (reverse) 36 - index else index
                val next = if (reverse) i - 1 else i + 1
                path.quadraticBezierTo(points[i * 2], points[i * 2 + 1], (points[i * 2] + points[next * 2]) / 2, (points[i * 2 + 1] + points[next * 2 + 1]) / 2)
            }
            val last = if (reverse) 0 else 72
            path.lineTo(points[last], points[last + 1])
        }
        onDrawBehind {
            val phase = time.value.toDouble()
            for ((layer, strand) in ribbonStrands.withIndex()) {
                for (index in 0..36) {
                    val x = -0.08 + 1.16 * index / 36
                    val run = strand[3] * phase
                    val center = strand[0] - 0.22 * (x - 0.5) + strand[1] * sin(strand[2] * x - run + strand[8]) +
                        0.35 * strand[1] * sin(1.9 * strand[2] * x + 0.6 * run + 2 * strand[8])
                    val half = strand[4] * (0.18 + 0.82 * abs(cos(strand[5] * x - strand[6] * phase + strand[8])))
                    upper[index * 2] = (x * size.width).toFloat()
                    lower[index * 2] = upper[index * 2]
                    upper[index * 2 + 1] = ((center - half) * size.height).toFloat()
                    lower[index * 2 + 1] = ((center + half) * size.height).toFloat()
                }
                band.reset()
                band.moveTo(upper[0], upper[1])
                through(band, upper, false)
                band.lineTo(lower[72], lower[73])
                through(band, lower, true)
                band.close()
                drawPath(band, brushes[layer])
                sheen.reset()
                sheen.moveTo(upper[0], upper[1])
                through(sheen, upper, false)
                drawPath(sheen, Color.White.copy(alpha = (1.6 * bright * strand[7]).toFloat()), style = Stroke(1.2.dp.toPx()))
            }
        }
    })
}

private val ribbonStrands = arrayOf(
    doubleArrayOf(0.58, 0.15, 5.2, 1.0, 0.12, 3.1, 0.8, 1.0, 0.0),
    doubleArrayOf(0.46, 0.11, 6.6, -0.75, 0.075, 4.4, -1.1, 0.7, 2.1),
    doubleArrayOf(0.7, 0.09, 4.3, 1.35, 0.06, 2.6, 1.4, 0.55, 4.3),
)

// Real shared traffic samples replace the ribbon in the same background bounds.
// Geometry is cached until the history or measured card size changes; there is no sampler here.
@Composable
internal fun DashboardTrafficBackdrop(history: List<Traffic>, modifier: Modifier) {
    val curves = rememberDashboardCurves(history)
    val dark = LocalLiteVisuals.current.dark
    Box(modifier.drawWithCache {
        fun path(points: List<DashboardCurvePoint>) = Path().apply {
            if (points.isNotEmpty()) {
                moveTo(points[0].x * size.width, points[0].y * size.height)
                for (index in 1 until points.size) {
                    val previous = points[index - 1]
                    val next = points[index]
                    val middleX = (previous.x + next.x) * size.width / 2f
                    cubicTo(middleX, previous.y * size.height, middleX, next.y * size.height,
                        next.x * size.width, next.y * size.height)
                }
            }
        }
        val series = listOf(curves.upload to Color(0xFF8C78D5), curves.download to Color(0xFF519FD8)).map { (points, color) ->
            val line = path(points)
            val fill = Path().apply {
                addPath(line)
                if (points.isNotEmpty()) {
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            }
            Triple(line, fill, color)
        }
        val stroke = Stroke(1.8.dp.toPx())
        onDrawBehind {
            series.forEach { (line, fill, color) ->
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = if (dark) 0.16f else 0.12f), Color.Transparent)))
                drawPath(line, color.copy(alpha = if (dark) 0.48f else 0.35f), style = stroke)
            }
        }
    })
}

@Composable
private fun rememberDashboardCurves(history: List<Traffic>) = androidx.compose.runtime.remember(history) { dashboardTrafficCurves(history) }
