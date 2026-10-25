package dev.aerove.lite.ui

import dev.aerove.lite.optics.AeroveSuperellipseGeometry
import org.junit.Assert.*
import org.junit.Test

class OpticalShapeSamplesTest {
    private fun geometry(w: Double = 320.0, h: Double = 176.0, r: Double = 24.0) = AeroveSuperellipseGeometry.resolve(w, h, r)
    private fun delta(a: Int, b: Int) = (0..3).maxOf { kotlin.math.abs((a ushr (it * 8) and 255) - (b ushr (it * 8) and 255)) }
    private val background = 0xff172432.toInt()
    private val surface = 0xfffcf8f9.toInt()

    @Test fun real107GeometryRejectsOld6DpAndSelectsGuardedPixels() {
        val shape = geometry()
        assertFalse(shape.contains(6.0, 6.0))
        val samples = shapeCornerSamples(shape, 63.0, 84.0, 2.625)
        for (point in samples.interior) assertTrue(shape.contains((point.x + 0.5 - 63) / 2.625, (point.y + 0.5 - 84) / 2.625))
        for (point in samples.exterior) assertFalse(shape.contains((point.x + 0.5 - 63) / 2.625, (point.y + 0.5 - 84) / 2.625))
        println("REAL107_SAMPLES exterior=${samples.exterior} interior=${samples.interior}")
    }

    @Test fun densityFractionalOriginAndClampedRadiusUseTheProductionMembership() {
        for (density in listOf(0.75, 1.0, 2.625, 4.0)) {
            for ((w, h, r) in listOf(Triple(320.0, 176.0, 24.0), Triple(80.0, 64.0, 1000.0), Triple(80.0, 64.0, 24.0))) {
                val shape = geometry(w, h, r)
                val samples = shapeCornerSamples(shape, 11.25, 17.75, density)
                for ((points, expected) in listOf(samples.interior to true, samples.exterior to false)) for (p in points) {
                    // Dense independent guard readback, beyond the selector's finite membership grid.
                    for (iy in 0..12) for (ix in 0..12) {
                        assertEquals(expected, shape.contains((p.x - 1 + ix / 4.0 - 11.25) / density, (p.y - 1 + iy / 4.0 - 17.75) / density))
                    }
                }
                if (r == 1000.0) assertEquals(32.0, shape.radii.first(), 0.0)
            }
        }
    }

    @Test fun missingFillAndExteriorLeakRemainRejectedByOriginalThresholds() {
        val samples = shapeCornerSamples(geometry(), 0.0, 0.0, 2.625)
        val source = { _: Int, _: Int -> background }
        val goodMask = { x: Int, y: Int -> if (ShapePixel(x, y) in samples.interior) surface else background }
        assertShapePixels(samples, source, goodMask, goodMask, surface, ::delta)
        assertThrows(AssertionError::class.java) { assertShapePixels(samples, source, goodMask, source, surface, ::delta) }
        val leaked = { _: Int, _: Int -> surface }
        assertThrows(AssertionError::class.java) { assertShapePixels(samples, source, leaked, goodMask, surface, ::delta) }
        val errorThree = { _: Int, _: Int -> background + 3 }
        assertThrows(AssertionError::class.java) { assertShapePixels(samples, source, errorThree, goodMask, surface, ::delta) }
        val fillOnlyThree = { _: Int, _: Int -> background + 3 }
        assertThrows(AssertionError::class.java) { assertShapePixels(samples, source, source, fillOnlyThree, surface, ::delta) }
    }

    @Test fun insufficientPixelFootprintFailsExplicitlyInsteadOfSkippingCoverage() {
        assertThrows(IllegalArgumentException::class.java) { shapeCornerSamples(geometry(4.0, 4.0, 1000.0), 0.0, 0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { shapeCornerSamples(geometry(r = 0.0), 0.0, 0.0, 1.0) }
    }
}
