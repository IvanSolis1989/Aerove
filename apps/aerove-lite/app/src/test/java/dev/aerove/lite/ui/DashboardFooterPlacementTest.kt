package dev.aerove.lite.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFooterPlacementTest {
    private val readings = listOf(Rect(0f, 132f, 100f, 144f), Rect(0f, 152f, 100f, 164f))

    @Test fun preferredCornerRemainsWhenActualTextLinesLeaveSpace() {
        val lines = listOf(Rect(94f, 136f, 150f, 144f), Rect(94f, 155f, 158f, 164f)).map { DashboardClearance(it) }
        assertEquals(174, dashboardFooterOffsetX(174, 192, readings, lines))
    }

    @Test fun collisionsMoveOnlyHorizontallyAtBothFooterLines() {
        val lines = listOf(Rect(220f, 128f, 270f, 148f), Rect(130f, 150f, 160f, 168f)).map { DashboardClearance(it) }
        val x = dashboardFooterOffsetX(174, 192, readings, lines)
        assertEquals(30, x)
        readings.forEach { line -> assertTrue(lines.none { it.overlaps(line.translate(x!!.toFloat(), 0f)) }) }
        assertEquals(138f, readings[0].center.y, 0f)
        assertEquals(158f, readings[1].center.y, 0f)
    }

    @Test fun physicalRightCornerStaysTheSameWithDirectionalPadding() {
        val ltr = dashboardFooterPreferredX(292, 100, 34, 16)
        val rtl = dashboardFooterPreferredX(292, 100, 34, 20)
        assertEquals(174, ltr)
        assertEquals(178, rtl)
        assertEquals(328 - 34, 20 + ltr + 100)
        assertEquals(328 - 34, 16 + rtl + 100)
    }

    @Test fun equivalentPhysicalCollisionMovesLeftInBothDirections() {
        val ltr = dashboardFooterOffsetX(dashboardFooterPreferredX(292, 100, 34, 16), 192,
            readings, listOf(DashboardClearance(Rect(224f, 130f, 260f, 146f))))
        val rtl = dashboardFooterOffsetX(dashboardFooterPreferredX(292, 100, 34, 20), 192,
            readings, listOf(DashboardClearance(Rect(228f, 130f, 264f, 146f))))
        assertEquals(124, ltr)
        assertEquals(128, rtl)
        assertEquals(20 + ltr!!, 16 + rtl!!)
    }

    @Test fun transparentArrowTextGapIsNotAnOccupiedPart() {
        val parts = listOf(Rect(1f, 134f, 9f, 142f), Rect(13f, 132f, 84f, 144f))
        assertEquals(174, dashboardFooterOffsetX(174, 208, parts,
            listOf(DashboardClearance(Rect(184f, 134f, 186f, 142f)))))
    }

    @Test fun arrowCollisionUsesItsOwnRightOffsetRatherThanRowWidth() {
        val parts = listOf(Rect(1f, 134f, 9f, 142f), Rect(13f, 132f, 84f, 144f))
        assertEquals(171, dashboardFooterOffsetX(174, 208, parts,
            listOf(DashboardClearance(Rect(180f, 134f, 182f, 142f)))))
    }

    @Test fun noHorizontalSlotCannotBecomeAnUnrelatedVerticalSlot() {
        assertNull(dashboardFooterOffsetX(174, 192, readings,
            listOf(DashboardClearance(Rect(0f, 130f, 292f, 166f)))))
        assertTrue(dashboardFooterAppends(null))
        assertFalse(dashboardFooterAppends(174))
        assertFalse(dashboardFooterAppends(0))
    }

    @Test fun otherRowsDoNotBlockTheFooter() {
        assertEquals(174, dashboardFooterOffsetX(174, 192, readings,
            listOf(DashboardClearance(Rect(0f, 0f, 292f, 120f)))))
    }

    @Test fun widerThanAvailableTextRemainsAnExplicitNoSlot() {
        assertNull(dashboardFooterOffsetX(-12, -4, readings, emptyList()))
    }
}
