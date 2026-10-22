package dev.aerove.lite.optics

import kotlin.math.hypot

internal fun conicPolyline(start: OpticsPoint, conic: SuperellipseSegment.Conic, tolerance: Double): List<OpticsPoint> {
    require(tolerance.isFinite() && tolerance > 0 && conic.weight.isFinite() && conic.weight > 0)
    data class Homogeneous(val x: Double, val y: Double, val w: Double) {
        fun point() = OpticsPoint(x / w, y / w)
        fun midpoint(p: Homogeneous) = Homogeneous((x + p.x) / 2, (y + p.y) / 2, (w + p.w) / 2)
    }
    val output = mutableListOf<OpticsPoint>()
    fun subdivide(a: Homogeneous, b: Homogeneous, c: Homogeneous, depth: Int) {
        val p = a.point()
        val q = b.point()
        val r = c.point()
        val v = r - p
        val square = v.x * v.x + v.y * v.y
        val t = if (square == 0.0) 0.0 else ((q.x - p.x) * v.x + (q.y - p.y) * v.y) / square
        val nearest = p + v * t.coerceIn(0.0, 1.0)
        if (hypot(q.x - nearest.x, q.y - nearest.y) <= tolerance) {
            output.add(r)
            return
        }
        check(depth < 24) { "Conic subdivision failed to converge" }
        val ab = a.midpoint(b)
        val bc = b.midpoint(c)
        val mid = ab.midpoint(bc)
        subdivide(a, ab, mid, depth + 1)
        subdivide(mid, bc, c, depth + 1)
    }
    subdivide(
        Homogeneous(start.x, start.y, 1.0),
        Homogeneous(conic.control.x * conic.weight, conic.control.y * conic.weight, conic.weight),
        Homogeneous(conic.end.x, conic.end.y, 1.0), 0,
    )
    return output
}
