package dev.aerove.lite.optics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class ConicSubdivisionTest {
    @Test fun rationalCircleQuarterStaysWithinSubdivisionBound() {
        val start = OpticsPoint(1.0, 0.0)
        val arc = SuperellipseSegment.Conic(OpticsPoint(1.0, 1.0), OpticsPoint(0.0, 1.0), sqrt(0.5))
        val polyline = listOf(start) + conicPolyline(start, arc, 0.0001)
        assertEquals(arc.end, polyline.last())
        polyline.zipWithNext().forEach { (a, b) ->
            assertTrue(hypot(a.x, a.y) > 0.999999999)
            val mid = (a + b) / 2.0
            assertTrue(1 - hypot(mid.x, mid.y) <= 0.0001)
        }
    }
}
