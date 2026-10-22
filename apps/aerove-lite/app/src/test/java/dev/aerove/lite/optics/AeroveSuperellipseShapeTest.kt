package dev.aerove.lite.optics

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AeroveSuperellipseShapeTest {
    @Test fun logicalRadiiFollowRtlAndDensityWithoutIndependentClamping() {
        val shape = AeroveSuperellipseShape(16.dp, 8.dp, 0.dp, 4.dp)
        val size = Size(600f, 120f)
        val ltr = shape.geometry(size, LayoutDirection.Ltr, Density(3f))
        val rtl = shape.geometry(size, LayoutDirection.Rtl, Density(3f))
        assertEquals(200.0, ltr.width, 0.0)
        assertEquals(40.0, ltr.height, 0.0)
        assertEquals(listOf(16.0, 8.0, 4.0, 0.0), ltr.radii)
        assertEquals(listOf(8.0, 16.0, 0.0, 4.0), rtl.radii)
    }
}
