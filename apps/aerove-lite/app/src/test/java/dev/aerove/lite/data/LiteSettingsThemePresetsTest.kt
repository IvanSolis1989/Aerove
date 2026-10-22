package dev.aerove.lite.data

import dev.aerove.lite.*
import dev.aerove.lite.editor.EditorFontSize
import dev.aerove.lite.editor.EditorPreferences
import dev.aerove.lite.ui.FullThemePresetColors
import dev.aerove.lite.ui.ThemePresetSelection
import java.io.File
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiteSettingsThemePresetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val defaults = listOf(
        0xFF007AFFL, 0xFFCB30E0L, 0xFFFF2D55L, 0xFFFF383CL,
        0xFFFF8D28L, 0xFFFFCC00L, 0xFF34C759L, 0xFF8E8E93L,
    )
    private val blue = defaults.first()
    private val custom = 0xFF123456L
    private val file get() = File(temporary.root, "settings/settings.json")

    @Test fun defaultsMatchDocumentedPaletteTheUiApiAndActualEditorDefaults() {
        val settings = LiteSettings()
        assertEquals(defaults, settings.themeColors)
        assertEquals(FullThemePresetColors, settings.themeColors)
        assertEquals(blue, settings.themeColor)
        assertFalse(settings.followSystemColor)
        assertEquals(EditorPreferences().fontSize.sp.toInt(), settings.editorFontSize)
        assertEquals(EditorPreferences().lineWrap, settings.editorLineWrap)
        assertEquals(listOf(16f, 18f, 20f), EditorFontSize.entries.map { it.sp })
    }

    @Test fun customPaletteAndEditorSettingsRoundTripWithoutChangingOtherSettings() = runBlocking<Unit> {
        val settings = LiteSettings(
            material = MaterialTier.Frosted, aurora = false, colorMode = ColorMode.Dark,
            themeColor = custom, themeColors = listOf(custom, blue), followSystemColor = true,
            editorFontSize = 20, editorLineWrap = true,
            colorStyle = ColorStyle.Tinted, auroraColors = listOf(0xFF010203L),
            auroraSeed = 51L, auroraRotationDay = 20261022L,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound),
            mode = ProxyMode.Global, ipv6 = true, overrideDns = true,
            dnsServers = listOf("tls://8.8.8.8:853"), appFilterMode = AppFilterMode.Exclude,
            packages = setOf("com.example.one"), autoStart = true, openLogs = true,
        )
        assertEquals(settings, LiteSettingsStore(temporary.root).save(settings, vpnAuthorized = true))
        assertEquals(settings, LiteSettingsStore(temporary.root).read())
        val json = JSONObject(file.readText())
        assertEquals("[4279383126,4278221567]", json.getJSONArray("themeColors").toString())
        assertTrue(json.getBoolean("followSystemColor"))
        assertEquals(20, json.getInt("editorFontSize"))
        assertTrue(json.getBoolean("editorLineWrap"))
    }

    @Test fun normalizationSnapshotsAndDeduplicatesThePaletteInFirstOccurrenceOrder() {
        val colors = mutableListOf(custom, blue, custom, 0L, 0xFFFFFFFFL)
        val input = LiteSettings(themeColor = custom, themeColors = colors)
        val normalized = input.normalized()
        assertEquals(listOf(custom, blue, 0L, 0xFFFFFFFFL), normalized.themeColors)
        assertEquals(listOf(custom, blue, custom, 0L, 0xFFFFFFFFL), colors)
        colors.clear()
        assertEquals(listOf(custom, blue, 0L, 0xFFFFFFFFL), normalized.themeColors)
        assertEquals(normalized, normalized.normalized())
    }

    @Test fun explicitEmptyPaletteIsLegalAndDoesNotResurrectTheRememberedManualColor() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val input = LiteSettings(themeColor = custom, themeColors = emptyList())
        val expected = input.copy(followSystemColor = true)
        assertEquals(expected, store.save(input))
        assertEquals(expected, LiteSettingsStore(temporary.root).read())
        assertEquals(0, JSONObject(file.readText()).getJSONArray("themeColors").length())
        assertEquals(custom, store.read().themeColor)
    }

    @Test fun missingManualSelectionUsesBlueWhenAvailableAndSystemOtherwise() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val withBlue = LiteSettings(themeColor = custom, themeColors = listOf(0xFF111111L, blue))
        assertEquals(withBlue.copy(themeColor = blue), store.save(withBlue))
        val withoutBlue = withBlue.copy(themeColors = listOf(0xFF111111L))
        assertEquals(withoutBlue.copy(followSystemColor = true), store.save(withoutBlue))
        assertEquals(withoutBlue.copy(followSystemColor = true), store.read())
    }

    @Test fun realThemePresetOperationsPersistSelectedEditMergeAndDeleteFallbacks() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        var settings = LiteSettings()
        suspend fun save(selection: ThemePresetSelection) {
            settings = store.save(settings.copy(themeColors = selection.colors,
                themeColor = selection.selected ?: settings.themeColor,
                followSystemColor = selection.selected == null, colorStyle = selection.style))
            assertEquals(settings, LiteSettingsStore(temporary.root).read())
        }
        val added = ThemePresetSelection().add(custom)
        save(added)
        assertEquals(custom, settings.themeColor)
        save(added.edit(custom, blue))
        assertEquals(defaults, settings.themeColors)
        assertEquals(blue, settings.themeColor)
        val noBlue = ThemePresetSelection().add(custom).delete(blue)
        save(noBlue.delete(custom))
        assertTrue(settings.followSystemColor)
        assertFalse(blue in settings.themeColors)
        assertFalse(custom in settings.themeColors)
    }

    @Test fun legacyMissingPaletteAppendsCustomSelectionWithoutRewritingOnRead() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val legacy = JSONObject(file.readText()).put("themeColor", custom).apply {
            listOf("themeColors", "followSystemColor", "editorFontSize", "editorLineWrap").forEach { remove(it) }
        }
        file.writeText(legacy.toString())
        val before = file.readBytes()
        val migrated = LiteSettings(themeColor = custom, themeColors = defaults + custom)
        assertEquals(migrated, LiteSettingsStore(temporary.root).read())
        assertArrayEquals(before, file.readBytes())
        assertEquals(migrated, store.save(migrated))
        assertEquals(migrated, LiteSettingsStore(temporary.root).read())
    }

    @Test fun legacyExistingDefaultSelectionIsNotAddedTwice() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        defaults.forEach { color ->
            store.save(LiteSettings())
            file.writeText(JSONObject(file.readText()).put("themeColor", color).apply { remove("themeColors") }.toString())
            val settings = store.read()
            assertEquals(color, settings.themeColor)
            assertEquals(defaults, settings.themeColors)
            assertFalse(settings.followSystemColor)
        }
    }

    @Test fun explicitEmptyJsonPaletteIsNotMistakenForLegacyMissingData() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        writeRaw("themeColors", "[]")
        val before = file.readBytes()
        val restored = store.read()
        assertTrue(restored.themeColors.isEmpty())
        assertTrue(restored.followSystemColor)
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun malformedPaletteUsesLocalDefaultsWithoutResettingNetworkOrWritingDisk() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val initial = LiteSettings(mode = ProxyMode.Direct, ipv6 = true, openLogs = true)
        listOf("null", "{}", "true", "\"future\"", "[null]", "[true]", "[\"1\"]", "[1.0]",
            "[-1]", "[4294967296]", "[18446744073709551615]", "[4278221567,false]").forEach { raw ->
            store.save(initial)
            writeRaw("themeColors", raw)
            val before = file.readBytes()
            assertEquals(raw, initial, store.read())
            assertArrayEquals(raw, before, file.readBytes())
        }
    }

    @Test fun invalidRawSelectedColorsNeverOverflowOrCoerceAndNeverRewriteSettings() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        listOf("-1", "4294967296", "18446744073709551615", "18446744077987773183",
            "1.0", "\"4278221567\"", "null", "true").forEach { raw ->
            store.save(LiteSettings())
            writeRaw("themeColor", raw)
            val before = file.readBytes()
            assertThrows(raw, IllegalArgumentException::class.java) { runBlocking { store.read() } }
            assertArrayEquals(raw, before, file.readBytes())
        }
    }

    @Test fun booleanFlagsAcceptOnlyRealBooleansAndEditorUnknownValuesUseTheirDefaults() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        listOf("null", "\"true\"", "1", "{}", "[]").forEach { raw ->
            store.save(LiteSettings())
            writeRaw("followSystemColor", raw)
            writeRaw("editorLineWrap", raw)
            writeRaw("editorFontSize", raw)
            val before = file.readBytes()
            assertEquals(raw, LiteSettings(), store.read())
            assertArrayEquals(raw, before, file.readBytes())
        }
        store.save(LiteSettings())
        writeRaw("editorFontSize", "\"futureSize\"")
        assertEquals(16, store.read().editorFontSize)
    }

    @Test fun everyEditorSizeAndWrapChoiceRoundTripAsNumericSettings() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        EditorFontSize.entries.map { it.sp.toInt() }.forEach { size ->
            listOf(false, true).forEach { wrap ->
                val settings = LiteSettings(editorFontSize = size, editorLineWrap = wrap)
                assertEquals(settings, store.save(settings))
                assertEquals(size, JSONObject(file.readText()).getInt("editorFontSize"))
                assertEquals(settings, LiteSettingsStore(temporary.root).read())
            }
        }
    }

    @Test fun invalidEditorSizesNormalizeWithoutNarrowingOverflowedJsonNumbers() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        listOf(-1, 0, 17, 19, Int.MIN_VALUE, Int.MAX_VALUE).forEach { size ->
            val input = LiteSettings(editorFontSize = size)
            assertEquals(16, input.normalized().editorFontSize)
            assertEquals(LiteSettings(), store.save(input))
            assertEquals(LiteSettings(), LiteSettingsStore(temporary.root).read())
        }
        listOf("18.0", "\"18\"", "4294967314", "18446744073709551634").forEach { raw ->
            store.save(LiteSettings())
            writeRaw("editorFontSize", raw)
            val before = file.readBytes()
            assertEquals(raw, 16, store.read().editorFontSize)
            assertArrayEquals(raw, before, file.readBytes())
        }
    }

    @Test fun earlierNamedEditorValuesMigrateToNumbersOnTheNextExplicitSave() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        listOf("standard" to 16, "large" to 18, "extraLarge" to 20).forEach { (name, size) ->
            store.save(LiteSettings(editorLineWrap = true))
            writeRaw("editorFontSize", "\"$name\"")
            val before = file.readBytes()
            val restored = store.read()
            assertEquals(LiteSettings(editorFontSize = size, editorLineWrap = true), restored)
            assertArrayEquals(before, file.readBytes())
            assertEquals(restored, store.save(restored))
            val persisted = JSONObject(file.readText()).get("editorFontSize")
            assertTrue(persisted is Int)
            assertEquals(size, persisted)
        }
    }

    @Test fun settingsRemainBoundedAndAllExistingAuthorizationAndNetworkGatesRemainActive() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val before = file.readBytes()
        val huge = LiteSettings(themeColor = 0xFF000000L, themeColors = List(20000) { 0xFF000000L + it })
        val invalid = listOf(huge, LiteSettings(themeColor = -1L, followSystemColor = true),
            LiteSettings(autoStart = true), LiteSettings(dnsServers = listOf("system")),
            LiteSettings(appFilterMode = AppFilterMode.Include))
        invalid.forEach { settings ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(settings) } }
            assertArrayEquals(before, file.readBytes())
        }
        assertEquals(LiteSettings(), store.read())
        file.writeText(" ".repeat(128 * 1024 + 1))
        assertThrows(java.io.IOException::class.java) { runBlocking { store.read() } }
    }

    @Test fun atomicAuroraUpdatesPreserveAllNewThemeAndEditorSettings() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val initial = LiteSettings(themeColors = emptyList(), followSystemColor = true, themeColor = custom,
            editorFontSize = 18, editorLineWrap = true, auroraRotationDay = 20261021L)
        store.save(initial)
        val now = ZonedDateTime.parse("2026-10-22T05:00+09:00[Asia/Tokyo]")
        val random = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("Expected bounded nextInt")
            override fun nextInt(until: Int): Int = 0
        }
        val rotated = store.rotateAuroraForDay(now, random)
        assertEquals(initial.copy(auroraSeed = initial.auroraSeed + 1, auroraRotationDay = 20261022L), rotated)
        val shuffled = store.shuffleAuroraWallpaper(now, random)
        assertEquals(rotated.copy(auroraSeed = rotated.auroraSeed + 1), shuffled)
        assertEquals(shuffled, LiteSettingsStore(temporary.root).read())
    }

    private fun writeRaw(key: String, raw: String) {
        val rest = JSONObject(file.readText()).apply { remove(key) }.toString()
        file.writeText("${rest.dropLast(1)},\"$key\":$raw}")
        assertTrue(file.readText().contains("\"$key\":$raw"))
    }
}
