package dev.aerove.lite.ui

import dev.aerove.lite.ColorStyle

internal const val FullDefaultThemeColor = 0xff007affL
internal val FullThemePresetColors = listOf(
    FullDefaultThemeColor, 0xffcb30e0L, 0xffff2d55L, 0xffff383cL,
    0xffff8d28L, 0xffffcc00L, 0xff34c759L, 0xff8e8e93L,
)

internal data class ThemePresetSelection(
    val colors: List<Long> = FullThemePresetColors,
    val selected: Long? = FullDefaultThemeColor,
    val style: ColorStyle = ColorStyle.Vivid,
) {
    fun select(color: Long?): ThemePresetSelection {
        require(color == null || color in colors)
        return copy(selected = color)
    }

    fun add(color: Long): ThemePresetSelection {
        require(color in 0..0xffffffffL)
        return copy(colors = if (color in colors) colors else colors + color, selected = color)
    }

    fun edit(original: Long, edited: Long): ThemePresetSelection {
        require(edited in 0..0xffffffffL)
        val index = colors.indexOf(original)
        if (original == edited || index < 0) return this
        val updated = colors.toMutableList()
        if (edited in updated) updated.removeAt(index) else updated[index] = edited
        return copy(colors = updated, selected = if (selected == original) edited else selected)
    }

    fun delete(color: Long): ThemePresetSelection {
        val updated = colors.toMutableList()
        if (!updated.remove(color)) return this
        return copy(colors = updated, selected = if (selected == color) FullDefaultThemeColor.takeIf { it in updated } else selected)
    }
}

internal fun themeColorHex(color: Long): String = (color and 0xffffffL).toString(16).uppercase().padStart(6, '0')

internal fun parseThemeColorHex(value: String): Long? {
    val digits = value.removePrefix("#")
    return if (digits.length == 6 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
        digits.toLong(16) or 0xff000000L else null
}
