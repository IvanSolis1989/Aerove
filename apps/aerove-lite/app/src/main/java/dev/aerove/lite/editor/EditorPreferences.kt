package dev.aerove.lite.editor

enum class EditorFontSize(val sp: Float) { Standard(16f), Large(18f), ExtraLarge(20f) }

data class EditorPreferences(
    val fontSize: EditorFontSize = EditorFontSize.Standard,
    val lineWrap: Boolean = false,
)

enum class EditorLanguage(val asset: String, val scope: String) {
    Yaml("yaml", "source.yaml"), Json("json", "source.json"), JavaScript("javascript", "source.js"),
}
