package dev.aerove.lite.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlyphPaintedBoundsTest {
    private val arrow = Rect(5.5f, 5f, 18.5f, 19f)
    private fun check(expected: Rect, actual: Rect?) {
        requireNotNull(actual)
        assertEquals(expected.left, actual.left, 0.00001f)
        assertEquals(expected.top, actual.top, 0.00001f)
        assertEquals(expected.right, actual.right, 0.00001f)
        assertEquals(expected.bottom, actual.bottom, 0.00001f)
    }

    @Test fun outlinedArrowIncludesRoundStrokeHalfWidthAndOnePhysicalPixel() {
        check(Rect(3.6f, 3.1f, 20.4f, 20.9f),
            glyphPartPaintedBounds(arrow, LiteGlyphRole.Line, false, Size(24f, 24f), 0f, false))
    }

    @Test fun filledLineUsesTheSameHeavierStrokeAsThePainter() {
        check(Rect(3.4f, 2.9f, 20.6f, 21.1f),
            glyphPartPaintedBounds(arrow, LiteGlyphRole.Line, false, Size(24f, 24f), 1f, false))
        assertEquals(2.2f, glyphStrokeWidth(LiteGlyphRole.Line, 1f), 0f)
    }

    @Test fun antialiasEnvelopeIsAfterScaleInPhysicalPixels() {
        check(Rect((5.5f - 0.9f) * 10f / 24f - 1f, (5f - 0.9f) * 10f / 24f - 1f,
            (18.5f + 0.9f) * 10f / 24f + 1f, (19f + 0.9f) * 10f / 24f + 1f),
            glyphPartPaintedBounds(arrow, LiteGlyphRole.Line, false, Size(10f, 10f), 0f, false))
    }

    @Test fun mirroringUsesTheSameTwentyFourUnitGrid() {
        check(Rect(14.1f, 2.1f, 22.9f, 18.9f),
            glyphPartPaintedBounds(Rect(3f, 4f, 8f, 17f), LiteGlyphRole.Line, false, Size(24f, 24f), 0f, true))
    }

    @Test fun solidBodyDoesNotInventAnOutlineStroke() {
        check(Rect(2f, 3f, 9f, 18f),
            glyphPartPaintedBounds(Rect(3f, 4f, 8f, 17f), LiteGlyphRole.Body, true, Size(24f, 24f), 0f, false))
    }

    @Test fun solidDetailUsesTheSameCenterScaleAndCutoutsDoNotAddPaint() {
        check(Rect(3.25f, 6.25f, 7.75f, 14.75f),
            glyphPartPaintedBounds(Rect(3f, 4f, 8f, 17f), LiteGlyphRole.Detail, true, Size(24f, 24f), 0.25f, false))
        assertNull(glyphPartPaintedBounds(arrow, LiteGlyphRole.Detail, false, Size(24f, 24f), 0.5f, false))
        assertNull(glyphPartPaintedBounds(arrow, LiteGlyphRole.Detail, false, Size(24f, 24f), 1f, false))
    }

    @Test fun zeroSizedDrawingHasNoPaintEnvelope() {
        assertNull(glyphPartPaintedBounds(arrow, LiteGlyphRole.Line, false, Size.Zero, 0f, false))
    }
}
