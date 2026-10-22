package dev.aerove.lite.ui

import dev.aerove.lite.ColorStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ThemePresetsTest {
    @Test
    fun defaultsMatchTheFullSystemColorsInOrder() {
        assertEquals(listOf(0xff007affL, 0xffcb30e0L, 0xffff2d55L, 0xffff383cL,
            0xffff8d28L, 0xffffcc00L, 0xff34c759L, 0xff8e8e93L), FullThemePresetColors)
        assertEquals(0xff007affL, ThemePresetSelection().selected)
    }

    @Test
    fun addAndEditDeduplicateAndKeepSelectionConsistent() {
        val original = ThemePresetSelection(style = ColorStyle.Soft)
        val added = original.add(0xff123456L)
        assertEquals(original.colors + 0xff123456L, added.colors)
        assertEquals(0xff123456L, added.selected)
        assertEquals(added, added.add(0xff123456L))
        val replaced = added.edit(0xff123456L, 0xffabcdefL)
        assertEquals(0xffabcdefL, replaced.selected)
        assertEquals(ColorStyle.Soft, replaced.style)
        val merged = replaced.edit(0xffabcdefL, FullDefaultThemeColor)
        assertEquals(original.colors, merged.colors)
        assertEquals(FullDefaultThemeColor, merged.selected)
        assertSame(merged, merged.edit(0xff123456L, 0xff111111L))
    }

    @Test
    fun deletingTheSelectedColorUsesBlueOrTheSystemFallback() {
        val custom = ThemePresetSelection().add(0xff123456L)
        assertEquals(FullDefaultThemeColor, custom.delete(0xff123456L).selected)
        val noBlue = custom.delete(FullDefaultThemeColor)
        assertNull(noBlue.delete(0xff123456L).selected)
        assertEquals(custom.selected, custom.delete(0xffcb30e0L).selected)
        assertNull(custom.select(null).selected)
    }

    @Test
    fun hexDraftRejectsIncompleteOrInvalidValues() {
        assertEquals(0xff12abcdL, parseThemeColorHex("#12aBcD"))
        assertEquals("12ABCD", themeColorHex(0xff12abcdL))
        for (value in listOf("", "FFF", "12345", "GG0000", "FF123456", " 123456")) assertNull(parseThemeColorHex(value))
    }
}
