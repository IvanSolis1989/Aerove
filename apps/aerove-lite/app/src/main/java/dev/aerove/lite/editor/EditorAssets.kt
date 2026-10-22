package dev.aerove.lite.editor

import android.content.Context
import android.graphics.Typeface
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration
import org.json.JSONArray
import org.json.JSONObject

internal class EditorAssets private constructor(
    val language: TextMateLanguage,
    val colors: TextMateColorScheme,
    val typeface: Typeface,
    val completion: EditorCompletion,
    private val grammars: GrammarRegistry,
) : AutoCloseable {
    override fun close() { grammars.dispose() }

    companion object {
        suspend fun load(context: Context, language: EditorLanguage, dark: Boolean): EditorAssets {
            var prepared: EditorAssets? = null
            try {
                return withContext(Dispatchers.IO) { create(context, language, dark).also { prepared = it } }
            } catch (failure: Throwable) {
                prepared?.language?.analyzeManager?.destroy()
                prepared?.close()
                throw failure
            }
        }

        private fun create(context: Context, language: EditorLanguage, dark: Boolean): EditorAssets {
            val grammars = GrammarRegistry(null)
            try {
                val themes = ThemeRegistry()
                val theme = theme(dark)
                themes.loadTheme(IThemeSource.fromInputStream(ByteArrayInputStream(theme.toByteArray()), "aerove.json", Charsets.UTF_8))
                themes.currentThemeModel.isDark = dark
                grammars.setTheme(themes.currentThemeModel)
                val files = if (language == EditorLanguage.Yaml) listOf("yaml-1.0", "yaml-1.1", "yaml-1.2", "yaml-1.3", "yaml-embedded", "yaml")
                    else listOf(language.asset)
                files.forEach { name ->
                    val bytes = context.assets.open("editor/grammars/$name.json").use { it.readBytes() }
                    val source = IGrammarSource.fromInputStream(ByteArrayInputStream(bytes), "$name.json", Charsets.UTF_8)
                    val scope = JSONObject(String(bytes, Charsets.UTF_8)).getString("scopeName")
                    grammars.loadGrammar(DefaultGrammarDefinition.withGrammarSource(source, name, scope))
                }
                val grammar = checkNotNull(grammars.findGrammar(language.scope)) { "Missing ${language.scope} grammar" }
                context.assets.open("editor/grammars/${language.asset}.configuration.json").reader().use {
                    grammars.languageConfigurationToGrammar(LanguageConfiguration.load(it), grammar)
                }
                val textmate = TextMateLanguage.create(language.scope, grammars, themes, false).apply {
                    tabSize = 2
                    useTab(false)
                    isAutoCompleteEnabled = false
                }
                return EditorAssets(
                    textmate,
                    TextMateColorScheme.create(themes, themes.currentThemeModel),
                    Typeface.createFromAsset(context.assets, "editor/fonts/JetBrainsMono-Regular.ttf"),
                    context.assets.open("editor/full-schema.json").bufferedReader(Charsets.UTF_8).use { EditorCompletion.fromJson(it.readText()) },
                    grammars,
                )
            } catch (failure: Throwable) {
                grammars.dispose()
                throw failure
            }
        }

        private fun theme(dark: Boolean): String {
            val palette = if (dark) listOf("#abb2bf", "#5c6370", "#c678dd", "#98c379", "#d19a66", "#e06c75", "#61afef")
                else listOf("#383a42", "#a0a1a7", "#a626a4", "#50a14f", "#986801", "#e45649", "#4078f2")
            val settings = JSONArray().put(JSONObject().put("settings", JSONObject().put("foreground", palette[0])))
            listOf("comment" to 1, "keyword,storage" to 2, "string" to 3, "constant.numeric,constant.language" to 4,
                "entity.name.tag,entity.other.attribute-name,support.type.property-name" to 5,
                "entity.name.function,entity.name.type,support.function" to 6).forEach { (scope, color) ->
                settings.put(JSONObject().put("scope", scope).put("settings", JSONObject().put("foreground", palette[color])))
            }
            return JSONObject().put("name", "Aerove Atom One").put("settings", settings).toString()
        }
    }
}

internal data class EditorPalette(val dark: Boolean, val background: Int, val ink: Int, val secondaryInk: Int, val accent: Int) {
    fun apply(scheme: EditorColorScheme) {
        val transparent = android.graphics.Color.TRANSPARENT
        fun alpha(color: Int, value: Int) = (color and 0x00ffffff) or (value shl 24)
        scheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, background)
        scheme.setColor(EditorColorScheme.TEXT_NORMAL, ink)
        scheme.setColor(EditorColorScheme.TEXT_SELECTED, ink)
        scheme.setColor(EditorColorScheme.LINE_NUMBER, secondaryInk)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_CURRENT, ink)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, background)
        scheme.setColor(EditorColorScheme.LINE_DIVIDER, transparent)
        scheme.setColor(EditorColorScheme.CURRENT_LINE, alpha(accent, 12))
        scheme.setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, alpha(accent, 70))
        scheme.setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, alpha(accent, 55))
        scheme.setColor(EditorColorScheme.SELECTION_INSERT, accent)
        scheme.setColor(EditorColorScheme.SELECTION_HANDLE, accent)
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB, alpha(secondaryInk, 100))
        scheme.setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, accent)
        scheme.setColor(EditorColorScheme.SCROLL_BAR_TRACK, transparent)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL, background)
        scheme.setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, ink)
        scheme.setColor(EditorColorScheme.BLOCK_LINE, alpha(secondaryInk, 50))
        scheme.setColor(EditorColorScheme.BLOCK_LINE_CURRENT, secondaryInk)
    }
}
