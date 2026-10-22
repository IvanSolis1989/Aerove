package dev.aerove.lite.scan

import org.junit.Assert.*
import org.junit.Test

class ScanLayoutTest {
    @Test fun portraitKeepsTorchBelowFrame() {
        val layout = ScanLayout.calculate(400f, 800f, 42f)
        assertEquals(268f, layout.window.width, 0.01f)
        assertTrue(layout.hint.bottom < layout.torch.top)
        assertEquals(168f, layout.torch.left, 0.01f)
    }

    @Test fun landscapeMovesTorchBesideFrame() {
        val layout = ScanLayout.calculate(800f, 360f, 42f)
        assertTrue(layout.torch.left > layout.window.left + layout.window.width)
        assertTrue(layout.torch.top < layout.window.bottom)
        assertTrue(layout.hint.bottom <= 360f)
    }

    @Test fun largeTextAndShortWindowsNeverProduceNegativeFrames() {
        for ((width, height) in listOf(320f to 240f, 1200f to 800f, 240f to 300f)) {
            val layout = ScanLayout.calculate(width, height, 100f)
            assertTrue(layout.window.width >= 0)
            assertTrue(layout.window.left >= 0)
            assertTrue(layout.window.width <= minOf(width, height) * 0.67f)
        }
    }
}
