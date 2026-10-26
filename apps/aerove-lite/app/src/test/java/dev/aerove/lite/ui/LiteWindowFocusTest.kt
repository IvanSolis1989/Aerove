package dev.aerove.lite.ui

import org.junit.Assert.*
import org.junit.Test

class LiteWindowFocusTest {
    @Test fun onlyActuallyFocusedOwnedWindowsCanReplaceRootFocus() {
        val registry = LiteOwnedWindowFocus()
        val editor = Any(); val next = Any()
        assertTrue(registry.focused(true))
        assertFalse(registry.focused(false))
        registry.update(editor, false)
        assertFalse(registry.focused(false))
        registry.update(editor, true)
        assertTrue(registry.focused(false))
        registry.update(next, false)
        registry.update(editor, false)
        assertFalse(registry.focused(false))
        registry.update(next, true)
        assertTrue(registry.focused(false))
        registry.remove(next)
        assertFalse(registry.focused(false))
        registry.remove(editor)
        assertTrue(registry.focused(true))
    }

    @Test fun transparentOverlayIsNotAnOcclusionButArrivedScanConsentAndExternalFocusLossAre() {
        assertTrue(liteWallpaperVisible(true, opaqueScan = false, opaqueConsent = false))
        assertFalse(liteWallpaperVisible(true, opaqueScan = true, opaqueConsent = false))
        assertFalse(liteWallpaperVisible(true, opaqueScan = false, opaqueConsent = true))
        assertFalse(liteWallpaperVisible(false, opaqueScan = false, opaqueConsent = false))
    }
}
