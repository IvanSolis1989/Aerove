package dev.aerove.lite.ui

import dev.aerove.lite.optics.AeroveSuperellipseGeometry
import org.junit.Assert.assertTrue
import kotlin.math.ceil
import kotlin.math.floor

internal data class ShapePixel(val x: Int, val y: Int)
internal data class ShapeCornerSamples(val exterior: List<ShapePixel>, val interior: List<ShapePixel>)

// Select physical pixels, not dp offsets: CapturedFrame.pixel floors its window coordinate.
// A 3x3 pixel footprint (the sampled pixel plus a one-pixel guard) must remain on one side.
internal fun shapeCornerSamples(
    geometry: AeroveSuperellipseGeometry, left: Double, top: Double, density: Double,
): ShapeCornerSamples {
    require(density.isFinite() && density > 0 && left.isFinite() && top.isFinite())
    val right = left + geometry.width * density
    val bottom = top + geometry.height * density
    val exterior = mutableListOf<ShapePixel>()
    val interior = mutableListOf<ShapePixel>()
    for (rightCorner in listOf(false, true)) for (bottomCorner in listOf(false, true)) {
        val startX = if (rightCorner) floor(right).toInt() - 3 else ceil(left).toInt() + 1
        val startY = if (bottomCorner) floor(bottom).toInt() - 3 else ceil(top).toInt() + 1
        val limit = ceil(minOf(geometry.width, geometry.height) * density / 2).toInt()
        var outside: ShapePixel? = null
        var inside: ShapePixel? = null
        for (step in 0..limit) {
            val pixel = ShapePixel(startX + if (rightCorner) -step else step, startY + if (bottomCorner) -step else step)
            if (pixel.x - 1 < left || pixel.y - 1 < top || pixel.x + 2 > right || pixel.y + 2 > bottom) continue
            // Reuse production membership; no copied curve, radius clamp, or distance approximation.
            val memberships = listOf(-1.0, -0.5, 0.5, 1.5, 2.0).flatMap { dy ->
                listOf(-1.0, -0.5, 0.5, 1.5, 2.0).map { dx ->
                    geometry.contains((pixel.x + dx - left) / density, (pixel.y + dy - top) / density)
                }
            }
            if (outside == null && memberships.none { it }) outside = pixel
            if (inside == null && memberships.all { it }) inside = pixel
            if (outside != null && inside != null) break
        }
        exterior += requireNotNull(outside) { "No safely exterior corner pixel at this pane size/density/radius; do not skip the shape oracle" }
        interior += requireNotNull(inside) { "No safely interior corner pixel at this pane size/density/radius; do not skip the fill oracle" }
    }
    return ShapeCornerSamples(exterior, interior)
}

internal fun assertShapePixels(
    samples: ShapeCornerSamples,
    source: (Int, Int) -> Int, actual: (Int, Int) -> Int, mask: (Int, Int) -> Int,
    surface: Int, error: (Int, Int) -> Int,
) {
    for ((x, y) in samples.exterior) {
        assertTrue("The superellipse exterior must expose the original background", error(source(x, y), actual(x, y)) <= 2)
    }
    for ((x, y) in samples.interior) {
        if (error(source(x, y), surface) > 20) {
            assertTrue("The actual RSuperellipse outline must fill its interior; transparent optical tint cannot serve as a shape mask",
                error(source(x, y), mask(x, y)) > 3)
        }
    }
}
