// Copyright 2013 The Flutter Authors. All rights reserved.
// Derived from Flutter 3.47.6 Impeller geometry; see assets/optics/NOTICE.md.
package dev.aerove.lite.optics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

data class OpticsPoint(val x: Double, val y: Double) {
    operator fun plus(p: OpticsPoint) = OpticsPoint(x + p.x, y + p.y)
    operator fun minus(p: OpticsPoint) = OpticsPoint(x - p.x, y - p.y)
    operator fun times(k: Double) = OpticsPoint(x * k, y * k)
    operator fun div(k: Double) = OpticsPoint(x / k, y / k)
    fun length() = hypot(x, y)
    fun flip() = OpticsPoint(y, x)
}

data class SuperellipseOctant(
    val a: Double,
    val n: Double,
    val joint: OpticsPoint,
    val center: OpticsPoint,
    val radius: Double,
    val meet: OpticsPoint,
) {
    fun contains(p: OpticsPoint): Boolean {
        if (p.x < 0 || p.y < 0 || p.y < p.x) return true
        if (p.x <= joint.x) return (p.x / a).pow(n) + (p.y / a).pow(n) <= 1
        return (p - center).length() < (joint - center).length()
    }

    fun inset(p: OpticsPoint): Double {
        val v = p - center
        val j = joint - center
        if (j.x * v.y < j.y * v.x) return radius - v.length()
        val sx = max(p.x, 0.0) / a
        val sy = max(p.y, 0.0) / a
        val major = max(max(sx, sy), 1e-4)
        val norm = major * (1 + (min(sx, sy) / major).pow(n)).pow(1 / n)
        val slope = hypot((sx / norm).pow(n - 1), (sy / norm).pow(n - 1))
        return a * (1 - norm) / max(slope, 1e-4)
    }

    companion object {
        private val fits = arrayOf(
            doubleArrayOf(2.0, 2.00000000, 1.13276676),
            doubleArrayOf(2.1, 2.18349805, 1.20311921),
            doubleArrayOf(2.2, 2.33888662, 1.28698796),
            doubleArrayOf(2.3, 2.48660575, 1.36351941),
            doubleArrayOf(2.4, 2.62226596, 1.44717976),
            doubleArrayOf(2.5, 2.75148990, 1.53385819),
            doubleArrayOf(3.0, 3.36298265, 1.98288283),
            doubleArrayOf(3.5, 4.08649929, 2.23811846),
            doubleArrayOf(4.0, 4.85481134, 2.47563463),
            doubleArrayOf(4.5, 5.62945551, 2.72948597),
            doubleArrayOf(5.0, 6.43023796, 2.98020421),
        )

        fun compute(a: Double, radius: Double): SuperellipseOctant {
            if (radius <= 1e-3 || a == 0.0) {
                val corner = OpticsPoint(a, a)
                return SuperellipseOctant(a, 0.0, corner, corner, 0.0, corner)
            }
            val ratio = max(2.0, a * 2 / radius)
            val n: Double
            val k: Double
            if (ratio > 5) {
                n = 6.43023796 + 1.559599389 * (ratio - 5)
                k = 2.98020421 + 0.522807185 * (ratio - 5)
            } else {
                var i = 0
                while (i < fits.size - 2 && ratio > fits[i + 1][0]) i++
                val lo = fits[i]
                val hi = fits[i + 1]
                val t = (ratio - lo[0]) / (hi[0] - lo[0])
                n = lo[1] * (1 - t) + hi[1] * t
                k = lo[2] * (1 - t) + hi[2] * t
            }
            val x = 1 - 1 / k
            val joint = OpticsPoint(x * a, (1 - x.pow(n)).pow(1 / n) * a)
            val tangent = (joint.x / joint.y).pow(n - 1)
            val d = (joint.x - tangent * joint.y) / (1 - tangent)
            val m = a - (1 - sqrt(0.5)) * radius
            val arc = (m - d) * sqrt(2.0)
            val meet = OpticsPoint(m, m)
            val chord = meet - joint
            val reach = sqrt(max(0.0, arc * arc - chord.length().pow(2) / 4))
            val center = (joint + meet) / 2.0 + OpticsPoint(chord.y, -chord.x) * (reach / chord.length())
            return SuperellipseOctant(a, n, joint, center, arc, meet)
        }
    }
}

sealed interface SuperellipseSegment {
    data class Line(val end: OpticsPoint) : SuperellipseSegment
    data class Conic(val control: OpticsPoint, val end: OpticsPoint, val weight: Double) : SuperellipseSegment
    data class Cubic(val control1: OpticsPoint, val control2: OpticsPoint, val end: OpticsPoint) : SuperellipseSegment
}

data class SuperellipseQuadrant(
    val origin: OpticsPoint,
    val halfSize: OpticsPoint,
    val sign: OpticsPoint,
    val radius: Double,
) {
    val along = SuperellipseOctant.compute(halfSize.x, radius)
    val across = SuperellipseOctant.compute(halfSize.y, radius)
    private val c = halfSize.x - halfSize.y
    private fun transform(p: OpticsPoint) = origin + OpticsPoint(p.x * sign.x, p.y * sign.y)
    private fun point(p: OpticsPoint) = OpticsPoint((p.x - origin.x) * sign.x, (p.y - origin.y) * sign.y)
    val top: OpticsPoint get() = transform(OpticsPoint(0.0, halfSize.y))

    fun contains(p: OpticsPoint): Boolean {
        val q = point(p)
        if (q.x < 0 || q.y < 0) return true
        if (along.n < 2 || across.n < 2) return q.x <= halfSize.x && q.y <= halfSize.y
        return along.contains(OpticsPoint(q.x, q.y + c)) &&
            across.contains(OpticsPoint(q.y, q.x - c))
    }

    fun inset(p: OpticsPoint): Double {
        val q = point(p)
        if (along.n < 2 || across.n < 2) return min(halfSize.x - q.x, halfSize.y - q.y)
        return if (q.y + c >= q.x) along.inset(OpticsPoint(q.x, q.y + c))
        else across.inset(OpticsPoint(q.y, q.x - c))
    }

    fun segments(reverse: Boolean): List<SuperellipseSegment> {
        if (along.n < 2 || across.n < 2) return listOf(
            SuperellipseSegment.Line(transform(halfSize)),
            SuperellipseSegment.Line(if (reverse) top else transform(OpticsPoint(halfSize.x, 0.0))),
        )
        fun octant(o: SuperellipseOctant, flipped: Boolean, backwards: Boolean): List<SuperellipseSegment> {
            val offset = if (flipped) OpticsPoint(c, 0.0) else OpticsPoint(0.0, -c)
            fun mapped(p: OpticsPoint) = transform((if (flipped) p.flip() else p) + offset)
            val clampedN = min(o.n, 14.0)
            val index = floor(clampedN - 2).toInt().coerceIn(0, weights.size - 2)
            val fraction = clampedN - 2 - index
            val w1 = (1 - fraction) * weights[index][0] + fraction * weights[index + 1][0] * sqrt(clampedN)
            val w2 = (1 - fraction) * weights[index][1] + fraction * weights[index + 1][1] * o.joint.x / o.a
            val yh = (sqrt(clampedN) + o.joint.y / o.a) / (sqrt(clampedN) + 1)
            val h = OpticsPoint((1 - yh.pow(o.n)).pow(1 / o.n) * o.a, yh * o.a)
            val a = OpticsPoint(0.0, o.a)
            val kh = -(h.x / h.y).pow(o.n - 1)
            val kj = -(o.joint.x / o.joint.y).pow(o.n - 1)
            val control1 = intersection(a, 0.0, h, kh)
            val control2 = intersection(h, kh, o.joint, kj)
            val v = o.joint - o.center
            val end = o.meet - o.center
            val angle = abs(atan2(v.x * end.y - v.y * end.x, v.x * end.x + v.y * end.y))
            val factor = tan(angle / 4) * 4 / 3
            val cubic1 = o.joint + OpticsPoint(v.y, -v.x) * factor
            val cubic2 = o.meet + OpticsPoint(-end.y, end.x) * factor
            return if (!backwards) listOf(
                SuperellipseSegment.Conic(mapped(control1), mapped(h), w1),
                SuperellipseSegment.Conic(mapped(control2), mapped(o.joint), w2),
                SuperellipseSegment.Cubic(mapped(cubic1), mapped(cubic2), mapped(o.meet)),
            ) else listOf(
                SuperellipseSegment.Cubic(mapped(cubic2), mapped(cubic1), mapped(o.joint)),
                SuperellipseSegment.Conic(mapped(control2), mapped(h), w2),
                SuperellipseSegment.Conic(mapped(control1), mapped(a), w1),
            )
        }
        return if (reverse) octant(across, true, false) + octant(along, false, true)
        else octant(along, false, false) + octant(across, true, true)
    }

    companion object {
        private val weights = arrayOf(
            doubleArrayOf(0.7078, 8.3194), doubleArrayOf(0.7895, 2.4523),
            doubleArrayOf(0.8379, 1.8528), doubleArrayOf(0.8701, 1.6891),
            doubleArrayOf(0.8932, 1.5806), doubleArrayOf(0.9107, 1.5043),
            doubleArrayOf(0.9244, 1.4470), doubleArrayOf(0.9355, 1.4037),
            doubleArrayOf(0.9448, 1.3701), doubleArrayOf(0.9526, 1.3431),
            doubleArrayOf(0.9594, 1.3212), doubleArrayOf(0.9653, 1.3032),
            doubleArrayOf(0.9705, 1.2880),
        )

        private fun intersection(p: OpticsPoint, k: Double, q: OpticsPoint, l: Double): OpticsPoint {
            if (abs(k - l) < 1e-3) return (p + q) / 2.0
            val x = (k * p.x - l * q.x + q.y - p.y) / (k - l)
            return OpticsPoint(x, k * (x - p.x) + p.y)
        }
    }
}

class AeroveSuperellipseGeometry private constructor(
    val width: Double,
    val height: Double,
    val radii: List<Double>,
    val quadrants: List<SuperellipseQuadrant>,
) {
    val start: OpticsPoint get() = quadrants[1].top
    fun segments(): List<SuperellipseSegment> = quadrants[1].segments(false) + quadrants[2].segments(true) +
        quadrants[3].segments(false) + quadrants[0].segments(true)

    fun contains(x: Double, y: Double): Boolean = x >= 0 && y >= 0 && x < width && y < height &&
        quadrants.all { it.contains(OpticsPoint(x, y)) }

    fun cornerInset(x: Double, y: Double): Double = quadrants.minOf { q ->
        if ((x - q.origin.x) * q.sign.x < 0 || (y - q.origin.y) * q.sign.y < 0) Double.POSITIVE_INFINITY
        else q.inset(OpticsPoint(x, y))
    }

    fun curveUniforms(): FloatArray = quadrants.flatMap { q ->
        listOf(q.along.n, q.along.joint.x, q.along.joint.y, q.along.radius,
            q.across.n, q.across.joint.x, q.across.joint.y, q.across.radius,
            q.along.center.x, q.along.center.y, q.across.center.x, q.across.center.y)
    }.map { it.toFloat() }.toFloatArray()

    fun quadrantUniforms(): FloatArray = quadrants.flatMap {
        listOf(it.origin.x, it.origin.y, it.halfSize.x, it.halfSize.y)
    }.map { it.toFloat() }.toFloatArray()

    companion object {
        fun resolve(width: Double, height: Double, topLeft: Double, topRight: Double = topLeft,
            bottomRight: Double = topRight, bottomLeft: Double = topLeft): AeroveSuperellipseGeometry {
            require(width.isFinite() && height.isFinite() && width >= 0 && height >= 0)
            val input = listOf(topLeft, topRight, bottomRight, bottomLeft)
            require(input.all { it.isFinite() })
            val positive = input.map { max(it, 0.0) }
            fun scale(i: Int, j: Int, dimension: Double): Double {
                val sum = positive[i] + positive[j]
                return if (sum > dimension) dimension / sum else 1.0
            }
            val factor = minOf(scale(0, 1, width), scale(3, 2, width), scale(0, 3, height), scale(1, 2, height))
            val r = positive.map { it * factor }
            fun split(a: Double, b: Double, side: Double) = if (a + b == 0.0) side / 2 else side * a / (a + b)
            val top = split(r[0], r[1], width)
            val bottom = split(r[3], r[2], width)
            val left = split(r[0], r[3], height)
            val right = split(r[1], r[2], height)
            val origins = listOf(OpticsPoint(top, left), OpticsPoint(top, right), OpticsPoint(bottom, right), OpticsPoint(bottom, left))
            val signs = listOf(OpticsPoint(-1.0, -1.0), OpticsPoint(1.0, -1.0), OpticsPoint(1.0, 1.0), OpticsPoint(-1.0, 1.0))
            val corners = listOf(OpticsPoint(0.0, 0.0), OpticsPoint(width, 0.0), OpticsPoint(width, height), OpticsPoint(0.0, height))
            val q = origins.indices.map { i ->
                val half = OpticsPoint(abs(corners[i].x - origins[i].x), abs(corners[i].y - origins[i].y))
                SuperellipseQuadrant(origins[i], half, signs[i], min(r[i], min(half.x, half.y)))
            }
            return AeroveSuperellipseGeometry(width, height, r, q)
        }
    }
}
