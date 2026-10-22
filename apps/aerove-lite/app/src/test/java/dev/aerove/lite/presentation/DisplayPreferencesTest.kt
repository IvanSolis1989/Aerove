package dev.aerove.lite.presentation

import org.junit.Assert.*
import org.junit.Test

class DisplayPreferencesTest {
    @Test fun customScaleReplacesSystemAndIsLinear() {
        assertEquals(1.1f, effectiveTextScale(1.3f, true, 1.1f), 0f)
        assertEquals(1.3f, effectiveTextScale(1.3f, false, 1.1f), 0f)
    }
    @Test fun systemAndCustomScaleShareTheStandardBounds() {
        assertEquals(0.8f, effectiveTextScale(0.5f, false, 1f), 0f)
        assertEquals(1.4f, effectiveTextScale(2f, false, 1f), 0f)
        assertEquals(0.8f, effectiveTextScale(1f, true, -1f), 0f)
        assertEquals(1.4f, effectiveTextScale(1f, true, 2f), 0f)
        assertEquals(1f, effectiveTextScale(1f, true, Float.NaN), 0f)
    }
    @Test fun pureBlackIsOnlyEffectiveInDarkMode() {
        assertFalse(effectivePureBlack(true, false))
        assertTrue(effectivePureBlack(true, true))
        assertFalse(presentationOpaque(true, false, false))
        assertTrue(presentationOpaque(true, true, false))
    }
    @Test fun opaqueGatesSuppressAuroraWithoutChangingStoredPreference() {
        assertTrue(effectiveAurora(true, true, false, false))
        assertFalse(effectiveAurora(true, true, true, false))
        assertFalse(effectiveAurora(true, false, false, true))
        assertFalse(effectiveAurora(false, false, true, false))
    }
}
