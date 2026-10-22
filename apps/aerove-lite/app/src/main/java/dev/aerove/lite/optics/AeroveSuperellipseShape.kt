package dev.aerove.lite.optics

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

data class AeroveSuperellipseShape(
    val corner: Dp,
    val endCorner: Dp = corner,
    val bottomCorner: Dp = corner,
    val bottomEndCorner: Dp = endCorner,
) : Shape {
    fun geometry(size: Size, layoutDirection: LayoutDirection, density: Density): AeroveSuperellipseGeometry {
        val left = if (layoutDirection == LayoutDirection.Ltr) corner else endCorner
        val right = if (layoutDirection == LayoutDirection.Ltr) endCorner else corner
        val bottomLeft = if (layoutDirection == LayoutDirection.Ltr) bottomCorner else bottomEndCorner
        val bottomRight = if (layoutDirection == LayoutDirection.Ltr) bottomEndCorner else bottomCorner
        return AeroveSuperellipseGeometry.resolve(
            size.width / density.density.toDouble(), size.height / density.density.toDouble(),
            left.value.toDouble(), right.value.toDouble(), bottomRight.value.toDouble(), bottomLeft.value.toDouble(),
        )
    }

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        if (size.width <= 0 || size.height <= 0) return Outline.Generic(path)
        val geometry = geometry(size, layoutDirection, density)
        val scale = density.density.toDouble()
        fun x(p: OpticsPoint) = (p.x * scale).toFloat()
        fun y(p: OpticsPoint) = (p.y * scale).toFloat()
        var previous = geometry.start
        path.moveTo(x(previous), y(previous))
        for (segment in geometry.segments()) {
            previous = when (segment) {
                is SuperellipseSegment.Line -> segment.end.also { path.lineTo(x(it), y(it)) }
                is SuperellipseSegment.Cubic -> segment.end.also {
                    path.cubicTo(x(segment.control1), y(segment.control1), x(segment.control2), y(segment.control2), x(it), y(it))
                }
                is SuperellipseSegment.Conic -> segment.end.also {
                    conicPolyline(previous, segment, 0.02 / scale).forEach { point -> path.lineTo(x(point), y(point)) }
                }
            }
        }
        path.close()
        return Outline.Generic(path)
    }
}
