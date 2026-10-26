package dev.aerove.lite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    val time = rememberAuroraTime(moving && LocalLitePageActive.current && !LocalLiteHighContrast.current,
        activity = LocalLiteMotionActivity.current)
    return androidx.compose.runtime.remember(time) { androidx.compose.runtime.derivedStateOf { time.longValue / 1_000_000f } }
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

/** Two real channels share the card's full background; zero is the physical bottom edge. */
@Composable
internal fun DashboardTrafficBackdrop(history: List<Traffic>, running: Boolean, active: Boolean,
    modifier: Modifier) {
    val visuals = LocalLiteVisuals.current
    val highContrast = LocalLiteHighContrast.current
    var held by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<Traffic>()) }
    if (active && running && history.isNotEmpty()) held = history
    val lift = androidx.compose.animation.core.animateFloatAsState(
        if (running && history.isNotEmpty()) 1f else 0f,
        androidx.compose.animation.core.tween(if (active && !LocalLiteReducedMotion.current && !highContrast) 240 else 0),
        label = "dashboardTrafficLift")
    val latest = androidx.compose.runtime.remember(held) { dashboardTrafficCurves(held) }
    var from by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(latest) }
    var to by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(latest) }
    val morph = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    val animateTrace = active && !LocalLiteReducedMotion.current && !highContrast
    androidx.compose.runtime.LaunchedEffect(latest, animateTrace) {
        if (latest != to) {
            from = interpolateDashboardCurves(from, to, morph.value)
            to = latest
            if (animateTrace && lift.value > 0f && lift.value < 1f) {
                morph.snapTo(0f)
                morph.animateTo(1f, androidx.compose.animation.core.tween(240))
            } else morph.snapTo(1f)
        } else if (!animateTrace) morph.snapTo(1f)
    }
    val curves = androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { interpolateDashboardCurves(from, to, morph.value) } }
    val opacity = if (highContrast) 0.12f else 0.34f
    Box(modifier) {
        TrafficPaths(curves, lift, opacity, filled = true, Modifier.matchParentSize())
        val source = if (visuals.aurora && visuals.material != dev.aerove.lite.MaterialTier.Solid) visuals.ink else null
        TrafficPaths(curves, lift, opacity, filled = false,
            if (source == null) Modifier.matchParentSize() else Modifier.matchParentSize().liteInk(source, dev.aerove.lite.optics.AeroveInkRole.Icon), source)
    }
}

@Composable
private fun TrafficPaths(curves: State<DashboardTrafficCurves>, lift: State<Float>, opacity: Float, filled: Boolean,
    modifier: Modifier, source: Color? = null) {
    Box(modifier.drawWithCache {
        val stroke = Stroke(1.8.dp.toPx())
        val path = Path()
        onDrawBehind {
            val amount = lift.value
            val paths = listOf(curves.value.upload to Color(0xFF8C78D5), curves.value.download to Color(0xFF519FD8))
            paths.forEachIndexed { series, (raw, color) ->
                val points = raw.ifEmpty { listOf(DashboardCurvePoint(0f, 1f), DashboardCurvePoint(1f, 1f)) }
                val baseline = size.height - stroke.width / 2
                fun y(point: DashboardCurvePoint) = baseline - (1f - point.y) * size.height * amount
                path.reset()
                path.moveTo(0f, y(points.first()))
                for (index in 1 until points.size) {
                    val previous = points[index - 1]
                    val next = points[index]
                    val middle = (previous.x + next.x) * size.width / 2f
                    path.cubicTo(middle, y(previous), middle, y(next), next.x * size.width, y(next))
                }
                if (filled) {
                    path.lineTo(size.width, size.height)
                    path.lineTo(0f, size.height)
                    path.close()
                    drawPath(path, color.copy(alpha = opacity * 0.14f))
                } else drawPath(path, (source ?: color).copy(alpha = opacity * if (source != null && series == 0) 0.7f else 1f), style = stroke)
            }
        }
    })
}

internal data class DashboardClearance(val bounds: androidx.compose.ui.geometry.Rect, val outline: Path? = null) {
    fun overlaps(other: androidx.compose.ui.geometry.Rect): Boolean {
        if (!bounds.overlaps(other)) return false
        val shape = outline ?: return true
        val placed = Path().apply { addPath(shape, bounds.topLeft) }
        val area = Path().apply { addRect(other) }
        return !Path.combine(androidx.compose.ui.graphics.PathOperation.Intersect, placed, area).isEmpty
    }
}
