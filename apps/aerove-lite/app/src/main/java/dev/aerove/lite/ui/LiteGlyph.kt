package dev.aerove.lite.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import kotlin.math.PI
import kotlin.math.min

internal enum class LiteGlyphRole { Body, Detail, Line }
internal data class LiteGlyphPart(val path: Path, val role: LiteGlyphRole, val solid: Boolean = false)
internal data class LiteGlyph(val shapes: List<LiteGlyphPart>, val mirrorInRtl: Boolean = false)
internal data class LiteGlyphVertex(val x: Float, val y: Float, val radius: Float) {
    val point get() = Offset(x, y)
}

internal fun vertex(x: Number, y: Number, radius: Number = 0) = LiteGlyphVertex(x.toFloat(), y.toFloat(), radius.toFloat())
internal fun point(x: Number, y: Number) = Offset(x.toFloat(), y.toFloat())

internal fun glyphBox(left: Number, top: Number, right: Number, bottom: Number, radius: Number, role: LiteGlyphRole = LiteGlyphRole.Body): LiteGlyphPart {
    val size = Size(right.toFloat() - left.toFloat(), bottom.toFloat() - top.toFloat())
    val path = (LiteSuperellipse(radius.toFloat().dp).createOutline(size, LayoutDirection.Ltr, Density(1f)) as Outline.Generic).path
    path.translate(point(left, top))
    return LiteGlyphPart(path, role)
}

internal fun glyphCircle(x: Number, y: Number, radius: Number, role: LiteGlyphRole = LiteGlyphRole.Body, solid: Boolean = false): LiteGlyphPart {
    val r = radius.toFloat()
    return LiteGlyphPart(Path().apply { addOval(Rect(x.toFloat() - r, y.toFloat() - r, x.toFloat() + r, y.toFloat() + r)) }, role, solid)
}

internal fun glyphOval(left: Number, top: Number, right: Number, bottom: Number, role: LiteGlyphRole = LiteGlyphRole.Body) =
    LiteGlyphPart(Path().apply { addOval(Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())) }, role)

internal fun glyphArc(x: Number, y: Number, radius: Number, start: Number, sweep: Number, role: LiteGlyphRole = LiteGlyphRole.Body): LiteGlyphPart {
    val r = radius.toFloat()
    val bounds = Rect(x.toFloat() - r, y.toFloat() - r, x.toFloat() + r, y.toFloat() + r)
    return LiteGlyphPart(Path().apply { addArc(bounds, (start.toDouble() * 180 / PI).toFloat(), (sweep.toDouble() * 180 / PI).toFloat()) }, role)
}

internal fun glyphLink(from: Offset, to: Offset, radius: Number): LiteGlyphPart {
    val delta = to - from
    val inset = delta / delta.getDistance() * (radius.toFloat() + 1.8f)
    val first = from + inset
    val last = to - inset
    return LiteGlyphPart(Path().apply { moveTo(first.x, first.y); lineTo(last.x, last.y) }, LiteGlyphRole.Line)
}

internal fun glyphPath(data: String, role: LiteGlyphRole = LiteGlyphRole.Body, solid: Boolean = false) =
    LiteGlyphPart(requireNotNull(PathParser.createPathFromPathData(data)).asComposePath(), role, solid)

internal fun glyphPolyline(vertices: List<LiteGlyphVertex>, closed: Boolean = false, role: LiteGlyphRole = LiteGlyphRole.Body): LiteGlyphPart {
    fun corner(index: Int): Pair<Offset, Offset> {
        val current = vertices[index].point
        val before = vertices[(index - 1 + vertices.size) % vertices.size].point - current
        val after = vertices[(index + 1) % vertices.size].point - current
        val radius = vertices[index].radius
        return (current + before / before.getDistance() * min(radius, before.getDistance() / 2)) to
            (current + after / after.getDistance() * min(radius, after.getDistance() / 2))
    }
    val path = Path()
    val first = if (closed) corner(0).second else vertices.first().point
    path.moveTo(first.x, first.y)
    val last = if (closed) vertices.size else vertices.lastIndex
    for (index in 1..last) {
        val vertex = vertices[index % vertices.size]
        if (!closed && index == vertices.lastIndex) path.lineTo(vertex.x, vertex.y)
        else {
            val (enter, exit) = corner(index % vertices.size)
            path.lineTo(enter.x, enter.y)
            path.quadraticBezierTo(vertex.x, vertex.y, exit.x, exit.y)
        }
    }
    if (closed) path.close()
    return LiteGlyphPart(path, role)
}

@Composable
internal fun GlyphDrawing(glyph: LiteGlyph, modifier: Modifier, color: Color, fill: Float) {
    val rtl = glyph.mirrorInRtl && LocalLayoutDirection.current == LayoutDirection.Rtl
    val stroke = remember { Paint().apply { style = PaintingStyle.Stroke; strokeCap = StrokeCap.Round; strokeJoin = StrokeJoin.Round } }
    val area = remember { Paint() }
    val layer = remember { Paint() }
    Canvas(modifier) {
        val progress = fill.coerceIn(0f, 1f)
        val cut = progress > 0.5f
        val detailScale = if (cut) (progress - 0.5f) * 2 else 1 - progress * 2
        val opaque = color.copy(alpha = 1f)
        stroke.color = opaque
        area.color = opaque
        layer.alpha = color.alpha
        val grid = Rect(0f, 0f, 24f, 24f)
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.scale(size.minDimension / 24f, size.minDimension / 24f)
            if (rtl) { canvas.translate(24f, 0f); canvas.scale(-1f, 1f) }
            if (color.alpha < 1f || cut) canvas.saveLayer(grid, layer) else canvas.save()
            for (part in glyph.shapes) if (part.role == LiteGlyphRole.Body && progress > 0f) {
                if (progress >= 1f) canvas.drawPath(part.path, area)
                else {
                    val bounds = part.path.getBounds()
                    val depth = min(bounds.width, bounds.height) / 2
                    stroke.strokeWidth = 2 * (0.9f + (depth - 0.9f) * progress)
                    canvas.save()
                    canvas.clipPath(part.path)
                    canvas.drawPath(part.path, stroke)
                    canvas.restore()
                }
            }
            if (detailScale > 0f) for (part in glyph.shapes) if (part.role == LiteGlyphRole.Detail) {
                val paint = if (part.solid) area else stroke
                paint.blendMode = if (cut) BlendMode.Clear else BlendMode.SrcOver
                stroke.strokeWidth = 1.8f * detailScale
                if (part.solid) {
                    val center = part.path.getBounds().center
                    canvas.save()
                    canvas.translate(center.x, center.y)
                    canvas.scale(detailScale, detailScale)
                    canvas.translate(-center.x, -center.y)
                    canvas.drawPath(part.path, paint)
                    canvas.restore()
                } else canvas.drawPath(part.path, paint)
                paint.blendMode = BlendMode.SrcOver
            }
            for (part in glyph.shapes) if (part.role != LiteGlyphRole.Detail) {
                stroke.strokeWidth = if (part.role == LiteGlyphRole.Line) 1.8f + 0.4f * progress else 1.8f
                canvas.drawPath(part.path, if (part.solid) area else stroke)
            }
            canvas.restore()
            canvas.restore()
        }
    }
}
