package dev.aerove.lite.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.ColorMode
import dev.aerove.lite.ColorStyle
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.LiteState
import dev.aerove.lite.MainActivity
import dev.aerove.lite.R
import dev.aerove.lite.data.LiteSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ThemePresetUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val directory = File(context.cacheDir, "theme-presets-${UUID.randomUUID()}")
    private val store = LiteSettingsStore(directory)
    private val state = mutableStateOf(LiteState())
    private var writes = 0

    @After fun clean() { directory.deleteRecursively() }

    private fun text(id: Int) = context.getString(id)
    private fun read() = runBlocking(Dispatchers.IO) { LiteSettingsStore(directory).read() }
    private fun launch(initial: LiteSettings, identity: String) {
        state.value = LiteState(settings = initial)
        val actions = UiTestActions { updated ->
            writes++
            runBlocking(Dispatchers.IO) { store.save(updated) }
            state.value = state.value.copy(settings = updated)
        }
        compose.replaceAppContent {
            key(identity) { CompositionLocalProvider(LocalLiteMotionOverride provides true) { LiteApp(state.value, actions) } }
        }
    }
    private fun openTheme() {
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text(R.string.lite_tab_tools))).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_theme)) and hasClickAction())
        compose.onNode(hasText(text(R.string.lite_theme)) and hasClickAction()).performClick()
    }
    private fun swatch(label: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(label))
    }
    private fun hex(value: String) = compose.onNode(hasContentDescription(text(R.string.lite_hex)) and hasSetTextAction()).performTextReplacement(value)
    private fun confirm() {
        val sheet = compose.onNode(hasText(text(R.string.lite_custom_color)))
        sheet.assertExists()
        compose.onNodeWithContentDescription(text(R.string.lite_confirm)).performClick()
        sheet.assertDoesNotExist()
    }

    @Test
    fun theFullEightPresetsAndFourStylesSelectRealPersistedSettings() {
        launch(LiteSettings(aurora = false), "presets")
        openTheme()
        val colors = listOf("007AFF", "CB30E0", "FF2D55", "FF383C", "FF8D28", "FFCC00", "34C759", "8E8E93")
        for (hex in colors) {
            swatch("#$hex")
            compose.onNodeWithTag("theme-color-$hex").performClick().assertIsSelected()
            assertEquals(hex.toLong(16) or 0xff000000L, read().themeColor)
            assertFalse(read().followSystemColor)
        }
        for ((style, label) in listOf(ColorStyle.Vivid to R.string.lite_style_vivid, ColorStyle.Tinted to R.string.lite_style_tinted,
            ColorStyle.Soft to R.string.lite_style_soft, ColorStyle.Monochrome to R.string.lite_style_monochrome)) {
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text(R.string.lite_color_style)) and hasClickAction())
            compose.onNode(hasText(text(R.string.lite_color_style)) and hasClickAction()).performClick()
            compose.onNode(hasText(text(label)) and hasClickAction()).performClick()
            assertEquals(style, read().colorStyle)
            assertEquals(colors.map { it.toLong(16) or 0xff000000L }, read().themeColors)
        }
    }

    @Test
    fun customColorCancelMergeDeleteAndSystemFallbackSurviveRecreation() {
        val initial = LiteSettings(aurora = false, colorStyle = ColorStyle.Soft)
        runBlocking(Dispatchers.IO) { store.save(initial) }
        launch(initial, "custom")
        openTheme()
        swatch(text(R.string.lite_add_color))
        compose.onNodeWithContentDescription(text(R.string.lite_add_color)).performClick()
        hex("123456")
        assertEquals(0, writes)
        val sheet = compose.onNode(hasText(text(R.string.lite_custom_color)))
        sheet.assertExists()
        compose.onNodeWithContentDescription(text(R.string.lite_cancel)).performClick()
        sheet.assertDoesNotExist()
        assertEquals(initial, read())
        swatch(text(R.string.lite_add_color))
        compose.onNodeWithContentDescription(text(R.string.lite_add_color)).performClick()
        hex("123456")
        compose.runOnUiThread { state.value = state.value.copy(settings = state.value.settings.copy(auroraSeed = 832838199)) }
        confirm()
        assertEquals(0xff123456L, read().themeColor)
        assertEquals(initial.themeColors + 0xff123456L, read().themeColors)
        assertEquals(832838199L, read().auroraSeed)
        assertEquals(ColorStyle.Soft, read().colorStyle)
        swatch("#123456")
        compose.onNodeWithTag("theme-color-123456").performTouchInput { longClick() }
        compose.onNode(hasText(text(R.string.lite_edit)) and hasClickAction()).performClick()
        hex("CB30E0")
        confirm()
        assertEquals(initial.themeColors, read().themeColors)
        assertEquals(0xffcb30e0L, read().themeColor)
        for (color in listOf("CB30E0", "007AFF")) {
            swatch("#$color")
            compose.onNodeWithTag("theme-color-$color").performTouchInput { longClick() }
            compose.onNode(hasText(text(R.string.lite_delete)) and hasClickAction()).performClick()
            compose.onNode(hasText(text(R.string.lite_delete)) and hasClickAction()).performClick()
        }
        assertTrue(read().followSystemColor)
        compose.onNodeWithTag("theme-system-color").assertIsSelected()
        val saved = read()
        compose.activityRule.scenario.recreate()
        launch(read(), "recreated")
        openTheme()
        swatch(text(R.string.lite_follow_system))
        compose.onNodeWithTag("theme-system-color").assertIsSelected()
        assertEquals(saved, read())
    }

    @Test
    fun resetRequiresConfirmationAndRestoresOnlyTheFullThemeDefaults() {
        val initial = LiteSettings(aurora = false, themeColors = listOf(0xff123456L), themeColor = 0xff123456L,
            colorStyle = ColorStyle.Tinted, auroraSeed = 839719428)
        runBlocking(Dispatchers.IO) { store.save(initial) }
        launch(initial, "reset")
        openTheme()
        swatch(text(R.string.lite_reset))
        compose.onNodeWithContentDescription(text(R.string.lite_reset)).performClick()
        compose.onNode(hasText(text(R.string.lite_cancel)) and hasClickAction()).performClick()
        assertEquals(initial, read())
        assertEquals(0, writes)
        compose.onNodeWithContentDescription(text(R.string.lite_reset)).performClick()
        compose.onNode(hasText(text(R.string.lite_reset)) and hasClickAction()).performClick()
        assertEquals(listOf(0xff007affL,0xffcb30e0L,0xffff2d55L,0xffff383cL,
            0xffff8d28L,0xffffcc00L,0xff34c759L,0xff8e8e93L), read().themeColors)
        assertEquals(0xff007affL, read().themeColor)
        assertFalse(read().followSystemColor)
        assertEquals(ColorStyle.Vivid, read().colorStyle)
        assertEquals(initial.auroraSeed, read().auroraSeed)
        assertEquals(1, writes)
    }

    @Test
    fun followSystemUsesTheFullLightFortyAndDarkEightySeeds() {
        val settings = mutableStateOf(LiteSettings(aurora = false, followSystemColor = true, themeColor = 0xffff2d55L))
        compose.replaceAppContent {
            LiteTheme(settings.value) {
                Text(MaterialTheme.colorScheme.primary.toArgb().toString(), Modifier.testTag("system-primary"))
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnUiThread { settings.value = settings.value.copy(colorMode = if (dark) ColorMode.Dark else ColorMode.Light) }
            val seed = context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            compose.onNodeWithTag("system-primary").assertTextEquals(fullColorScheme(seed, dark, false).primary.toArgb().toString())
            compose.runOnUiThread { settings.value = settings.value.copy(followSystemColor = false) }
            compose.onNodeWithTag("system-primary").assertTextEquals(fullColorScheme(0xffff2d55.toInt(), dark, false).primary.toArgb().toString())
            compose.runOnUiThread { settings.value = settings.value.copy(followSystemColor = true) }
        }
    }
}
