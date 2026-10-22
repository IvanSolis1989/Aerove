package dev.aerove.lite.data

import dev.aerove.lite.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONArray
import org.json.JSONObject

class LiteSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun allProductSettingsRoundTripThroughDisk() = runBlocking<Unit> {
        val settings = LiteSettings(
            material = MaterialTier.Clear,
            aurora = true,
            colorMode = ColorMode.Dark,
            themeColor = 0xff124578,
            themeColors = LiteSettings().themeColors + 0xff124578L,
            colorStyle = ColorStyle.Tinted,
            auroraColors = listOf(0xFF123456L, 0xFFABCDEFL),
            auroraSeed = 9876543210L,
            auroraRotationDay = 20261022L,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound, DashboardWidget.Profiles),
            mode = ProxyMode.Global,
            ipv6 = true,
            overrideDns = true,
            dnsServers = listOf("https://1.1.1.1/dns-query", "tls://8.8.8.8:853"),
            appFilterMode = AppFilterMode.Exclude,
            packages = setOf("com.example.one", "com.example.two"),
            autoStart = true,
            openLogs = true,
            serviceOrder = listOf("netflix") + serviceTargetIds.filterNot { it == "netflix" },
            disabledServices = setOf("google", "github"),
            autoCheckUpdate = false,
        )
        assertEquals(settings, LiteSettingsStore(temporary.root).save(settings, vpnAuthorized = true))
        assertEquals(settings, LiteSettingsStore(temporary.root).read())
    }

    @Test fun missingFileUsesDefaults() = runBlocking<Unit> {
        assertEquals(LiteSettings(), LiteSettingsStore(temporary.root).read())
    }

    @Test fun oldSchemaOnePreservesExplicitThemeChoicesWhileAddingNewDefaults() = runBlocking<Unit> {
        val old = LiteSettings(material = MaterialTier.Solid, aurora = false, themeColor = 0xFF6B52AEL,
            themeColors = LiteSettings().themeColors + 0xFF6B52AEL, colorMode = ColorMode.Light)
        val store = LiteSettingsStore(temporary.root)
        store.save(old)
        val file = File(temporary.root, "settings/settings.json")
        val json = JSONObject(file.readText()).apply {
            listOf("colorStyle", "auroraColors", "auroraSeed", "auroraRotationDay", "dashboardWidgets",
                "themeColors", "followSystemColor", "editorFontSize", "editorLineWrap").forEach { remove(it) }
        }
        file.writeText(json.toString())
        val originalBytes = file.readBytes()
        assertEquals(old, LiteSettingsStore(temporary.root).read())
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test fun saveReturnsExactlyTheNormalizedValueWrittenToSchemaOne() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val input = LiteSettings(
            colorStyle = ColorStyle.Soft,
            auroraColors = listOf(0x123456L, 0x80112233L, 0L, 0xFFFFFFFFL),
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.MemoryInfo),
        )
        val saved: LiteSettings = store.save(input)
        assertEquals(listOf(0xFF123456L, 0xFF112233L, 0xFF000000L), saved.auroraColors)
        assertEquals(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo), saved.dashboardWidgets)
        assertEquals(input.normalized(), saved)
        assertEquals(saved, LiteSettingsStore(temporary.root).read())
        assertEquals(4, input.auroraColors.size)
        assertEquals(2, input.dashboardWidgets.size)
        val json = JSONObject(File(temporary.root, "settings/settings.json").readText())
        assertEquals(1, json.getInt("schema"))
        assertEquals("soft", json.getString("colorStyle"))
        assertEquals("[\"currentOutbound\",\"memoryInfo\"]", json.getJSONArray("dashboardWidgets").toString())
        assertEquals(0xFF123456L, json.getJSONArray("auroraColors").getLong(0))
    }

    @Test fun allFourColorStylesRoundTripUsingFullWireNames() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        ColorStyle.entries.forEach { style ->
            store.save(LiteSettings(colorStyle = style))
            val json = JSONObject(File(temporary.root, "settings/settings.json").readText())
            assertEquals(style.wireName, json.getString("colorStyle"))
            assertEquals(style, LiteSettingsStore(temporary.root).read().colorStyle)
        }
    }

    @Test fun legacyColorStyleFallbackMatchesFullFieldSalvageAndExplicitNullPrecedence() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val cases = listOf(
            Triple(null, "monochrome", ColorStyle.Monochrome),
            Triple(null, "tonalSpot", ColorStyle.Soft),
            Triple(null, "neutral", ColorStyle.Soft),
            Triple(null, "vibrant", ColorStyle.Vivid),
            Triple("tinted", "monochrome", ColorStyle.Tinted),
            Triple("futureStyle", "monochrome", ColorStyle.Monochrome),
            Triple(true, "neutral", ColorStyle.Soft),
            Triple(JSONObject.NULL, "monochrome", ColorStyle.Vivid),
        )
        cases.forEach { (style, legacy, expected) ->
            store.save(LiteSettings())
            val file = File(temporary.root, "settings/settings.json")
            val json = JSONObject(file.readText()).apply {
                if (style == null) remove("colorStyle") else put("colorStyle", style)
                put("schemeVariant", legacy)
            }
            file.writeText(json.toString())
            assertEquals("style=$style, legacy=$legacy", expected, store.read().colorStyle)
        }
    }

    @Test fun damagedAppearanceFieldsRestoreLocallyWithoutResettingNetworkOrExplicitTheme() = runBlocking<Unit> {
        val original = LiteSettings(
            material = MaterialTier.Frosted, aurora = false, colorMode = ColorMode.Dark,
            themeColor = 0xFF123456L, mode = ProxyMode.Direct,
            themeColors = LiteSettings().themeColors + 0xFF123456L,
            dnsServers = listOf("tls://8.8.8.8:853"), openLogs = true,
        )
        val store = LiteSettingsStore(temporary.root)
        store.save(original)
        val file = File(temporary.root, "settings/settings.json")
        val json = JSONObject(file.readText()).apply {
            put("colorStyle", "futureStyle")
            put("auroraColors", "not a palette")
            put("auroraSeed", "271828")
            put("auroraRotationDay", true)
            put("dashboardWidgets", JSONObject())
        }
        file.writeText(json.toString())
        val originalBytes = file.readBytes()
        assertEquals(original, store.read())
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test fun paletteRejectsNonIntegerJsonMembersAndChecksBeyondTheFirstThree() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        listOf("[]", "[1,2,3,true]", "[1,2,3,-1]", "[1,2,3,4294967296]", "[1.0]", "[\"1\"]", "[null]").forEach { encoded ->
            store.save(LiteSettings(auroraColors = listOf(0xFF010203L)))
            val file = File(temporary.root, "settings/settings.json")
            val json = JSONObject(file.readText()).apply { remove("auroraColors") }.toString()
            file.writeText("${json.dropLast(1)},\"auroraColors\":$encoded}")
            assertTrue(encoded, file.readText().contains("\"auroraColors\":$encoded"))
            assertEquals(encoded, LiteSettings().auroraColors, store.read().auroraColors)
        }
    }

    @Test fun numericRotationStateUsesFullNumberToIntegerSemantics() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val file = File(temporary.root, "settings/settings.json")
        val json = JSONObject(file.readText()).put("auroraSeed", 7.9).put("auroraRotationDay", -3.9)
        file.writeText(json.toString())
        val restored = store.read()
        assertEquals(7L, restored.auroraSeed)
        assertEquals(-3L, restored.auroraRotationDay)
    }

    @Test fun dashboardJsonKeepsNonStringEntriesUnknownUntilFullMigrationIsComplete() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        val cases = listOf(
            "[]" to listOf(DashboardWidget.CurrentOutbound),
            "[\"runTime\",null]" to LiteSettings().dashboardWidgets,
            "[\"networkTakeover\",42]" to listOf(DashboardWidget.CurrentOutbound),
            "[\"networkSpeed\",null,\"currentOutbound\",false]" to listOf(DashboardWidget.NetworkSpeed, DashboardWidget.CurrentOutbound),
            "[\"networkSpeed\",\"systemProxyButton\",\"tunButton\",\"outboundMode\",\"networkDetection\",\"trafficUsage\",\"intranetIp\",42]" to listOf(
                DashboardWidget.CurrentOutbound, DashboardWidget.NetworkSpeed, DashboardWidget.OutboundMode, DashboardWidget.IntranetIp,
            ),
        )
        cases.forEach { (encoded, expected) ->
            store.save(LiteSettings())
            val file = File(temporary.root, "settings/settings.json")
            file.writeText(JSONObject(file.readText()).put("dashboardWidgets", JSONArray(encoded)).toString())
            assertEquals(encoded, expected, store.read().dashboardWidgets)
        }
    }

    @Test fun dashboardResetPersistsAcrossReopenWithoutChangingOtherSettings() = runBlocking<Unit> {
        val customized = LiteSettings(
            material = MaterialTier.Solid, aurora = false, colorMode = ColorMode.Dark,
            themeColor = 0xFF123456L, colorStyle = ColorStyle.Monochrome,
            themeColors = LiteSettings().themeColors + 0xFF123456L,
            auroraColors = listOf(0xFF010203L), auroraSeed = 9876543210L, auroraRotationDay = 20261022L,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound),
            mode = ProxyMode.Global, ipv6 = true, overrideDns = true,
            dnsServers = listOf("https://8.8.8.8/dns-query"),
            appFilterMode = AppFilterMode.Exclude, packages = setOf("com.example.one"), autoStart = true, openLogs = true,
        )
        val store = LiteSettingsStore(temporary.root)
        store.save(customized, vpnAuthorized = true)
        val reset = store.save(store.read().resetDashboardWidgets(), vpnAuthorized = true)
        assertEquals(customized.copy(dashboardWidgets = LiteSettings().dashboardWidgets), reset)
        assertEquals(reset, LiteSettingsStore(temporary.root).read())
    }

    @Test fun unsupportedSchemaRemainsAnErrorAndDoesNotRewriteDisk() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val file = File(temporary.root, "settings/settings.json")
        file.writeText(JSONObject(file.readText()).put("schema", 2).toString())
        val originalBytes = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { store.read() } }
        assertArrayEquals(originalBytes, file.readBytes())
    }

    @Test fun oldSettingsWithoutOverrideFlagFollowProfileDns() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(overrideDns = true))
        val file = File(temporary.root, "settings/settings.json")
        val json = org.json.JSONObject(file.readText()).apply { remove("overrideDns") }
        file.writeText(json.toString())
        assertFalse(store.read().overrideDns)
    }

    @Test fun bootAutostartRequiresVpnAuthorizationAndFailureDoesNotWrite() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store.save(LiteSettings(autoStart = true)) }
        }
        assertFalse(store.read().autoStart)
    }

    @Test fun oldSettingsWithoutLogFlagKeepLoggingDisabled() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(openLogs = true))
        val file = File(temporary.root, "settings/settings.json")
        val json = org.json.JSONObject(file.readText()).apply { remove("openLogs") }
        file.writeText(json.toString())
        assertFalse(store.read().openLogs)
    }

    @Test fun invalidDnsAndEmptyAllowListPreservePriorSettings() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings(material = MaterialTier.Frosted))
        val file = File(temporary.root, "settings/settings.json")
        val originalBytes = file.readBytes()
        listOf(
            LiteSettings(dnsServers = listOf("system")),
            LiteSettings(dnsServers = listOf("dhcp://wlan0")),
            LiteSettings(dnsServers = emptyList()),
            LiteSettings(appFilterMode = AppFilterMode.Include),
            LiteSettings(themeColor = -1L, auroraColors = emptyList()),
            LiteSettings(packages = setOf("invalid package")),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { store.save(invalid) } }
            assertArrayEquals(originalBytes, file.readBytes())
        }
        assertEquals(MaterialTier.Frosted, store.read().material)
    }

    @Test fun corruptSettingsAreReportedInsteadOfSilentlyReset() = runBlocking<Unit> {
        val store = LiteSettingsStore(temporary.root)
        store.save(LiteSettings())
        val file = File(temporary.root, "settings/settings.json")
        file.writeText("{broken")
        assertThrows(org.json.JSONException::class.java) { runBlocking { store.read() } }
        assertEquals("{broken", file.readText())
    }
}
