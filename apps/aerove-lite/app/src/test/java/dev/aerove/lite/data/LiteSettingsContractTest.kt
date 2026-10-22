package dev.aerove.lite.data

import dev.aerove.lite.*
import org.junit.Assert.*
import org.junit.Test

class LiteSettingsContractTest {
    private val androidDefaults = listOf(
        DashboardWidget.CurrentOutbound,
        DashboardWidget.NetworkSpeed,
        DashboardWidget.OutboundMode,
        DashboardWidget.VpnButton,
        DashboardWidget.IntranetIp,
        DashboardWidget.ServiceStatus,
        DashboardWidget.Profiles,
        DashboardWidget.ProxyGroups,
        DashboardWidget.ConnectionStats,
    )

    @Test fun freshSettingsMatchPersistedFullThemeAndAndroidDashboardDefaults() {
        val settings = LiteSettings()
        assertEquals(MaterialTier.Clear, settings.material)
        assertTrue(settings.aurora)
        assertEquals(ColorMode.System, settings.colorMode)
        assertEquals(0xFF007AFFL, settings.themeColor)
        assertEquals(ColorStyle.Vivid, settings.colorStyle)
        assertEquals(listOf(0xFF65BDD2L, 0xFFC589CAL, 0xFF75CCA8L), settings.auroraColors)
        assertEquals(271828L, settings.auroraSeed)
        assertEquals(0L, settings.auroraRotationDay)
        assertEquals(androidDefaults, settings.dashboardWidgets)
        assertFalse(settings.dashboardWidgets.contains(DashboardWidget.MemoryInfo))
    }

    @Test fun colorStylesHaveTheFourFullWireNames() {
        assertEquals(listOf("vivid", "tinted", "soft", "monochrome"), ColorStyle.entries.map { it.wireName })
    }

    @Test fun dashboardMissingEmptyRetiredUnknownAndDesktopListsFollowFullMigrationOrder() {
        val cases = listOf(
            null to androidDefaults,
            emptyList<String>() to listOf(DashboardWidget.CurrentOutbound),
            listOf("runTime", "overrideDnsButton", "overrideNtpButton") to listOf(DashboardWidget.CurrentOutbound),
            listOf("futureWidget") to androidDefaults,
            listOf("runTime", "futureWidget") to androidDefaults,
            listOf("networkTakeover") to listOf(DashboardWidget.CurrentOutbound),
            listOf("networkTakeover", "futureWidget") to listOf(DashboardWidget.CurrentOutbound),
            listOf("systemProxyButton", "futureWidget") to listOf(DashboardWidget.CurrentOutbound),
            listOf("currentOutbound", "futureWidget", "networkTakeover") to listOf(DashboardWidget.CurrentOutbound),
        )
        cases.forEach { (input, expected) ->
            assertEquals("dashboard=$input", expected, normalizeDashboardWidgets(input))
        }
    }

    @Test fun existingOutboundPositionAndFirstOccurrenceOrderArePreserved() {
        assertEquals(
            listOf(DashboardWidget.NetworkSpeed, DashboardWidget.CurrentOutbound, DashboardWidget.Profiles),
            normalizeDashboardWidgets(listOf("networkSpeed", "currentOutbound", "networkSpeed", "profiles", "currentOutbound")),
        )
    }

    @Test fun legacyDashboardNamesMapBeforeDeduplicationAndAndroidFiltering() {
        assertEquals(
            listOf(
                DashboardWidget.CurrentOutbound,
                DashboardWidget.IntranetIp,
                DashboardWidget.OutboundMode,
                DashboardWidget.ConnectionStats,
                DashboardWidget.NetworkSpeed,
            ),
            normalizeDashboardWidgets(listOf(
                "networkDetection", "outboundModeV2", "systemProxyButton", "tunButton",
                "connections", "requests", "dnsQueries", "trafficUsage", "networkSpeed",
            )),
        )
    }

    @Test fun legacyDefaultSetResetsOnlyWhenItHasNoCurrentOutbound() {
        val legacy = listOf(
            "networkSpeed", "systemProxyButton", "tunButton", "outboundModeV2",
            "networkDetection", "trafficUsage", "intranetIp",
        )
        assertEquals(androidDefaults, normalizeDashboardWidgets(legacy.reversed() + "networkSpeed"))
        assertEquals(
            listOf(
                DashboardWidget.NetworkSpeed, DashboardWidget.OutboundMode,
                DashboardWidget.IntranetIp, DashboardWidget.CurrentOutbound,
            ),
            normalizeDashboardWidgets(legacy + "currentOutbound"),
        )
    }

    @Test fun everyAndroidWidgetIncludingOptionalMemoryCanBeSavedInUserOrder() {
        val widgets = DashboardWidget.entries.reversed()
        assertEquals(10, widgets.size)
        assertEquals(widgets, normalizeDashboardWidgets(widgets.map { it.wireName }))
        assertFalse(DashboardWidget.entries.any { it.wireName == "networkTakeover" })
    }

    @Test fun auroraAcceptsOneTwoOrThreeColorsAndAddsOpaqueAlpha() {
        val cases = listOf(
            listOf(0L) to listOf(0xFF000000L),
            listOf(0x123456L, 0x80112233L) to listOf(0xFF123456L, 0xFF112233L),
            listOf(0L, 0xFFFFFFFFL, 1L, 2L) to listOf(0xFF000000L, 0xFFFFFFFFL, 0xFF000001L),
        )
        cases.forEach { (colors, expected) ->
            assertEquals(expected, LiteSettings(auroraColors = colors).normalized().auroraColors)
        }
    }

    @Test fun auroraRejectsTheWholePaletteEvenWhenOnlyTheFourthColorIsInvalid() {
        listOf(emptyList(), listOf(-1L), listOf(0x100000000L), listOf(1L, 2L, 3L, -1L)).forEach { colors ->
            assertEquals(LiteSettings().auroraColors, LiteSettings(auroraColors = colors).normalized().auroraColors)
        }
    }

    @Test fun normalizationSnapshotsCollectionsAndIsIdempotent() {
        val colors = mutableListOf(0x123456L, 0x654321L)
        val widgets = mutableListOf(DashboardWidget.MemoryInfo, DashboardWidget.MemoryInfo)
        val dns = mutableListOf("tls://8.8.8.8:853")
        val packages = mutableSetOf("com.example.one")
        val original = LiteSettings(auroraColors = colors, dashboardWidgets = widgets, dnsServers = dns, packages = packages)
        val normalized = original.normalized()
        assertEquals(listOf(0x123456L, 0x654321L), colors)
        assertEquals(listOf(DashboardWidget.MemoryInfo, DashboardWidget.MemoryInfo), widgets)
        colors.clear()
        widgets.clear()
        dns.clear()
        packages.clear()
        assertEquals(listOf(0xFF123456L, 0xFF654321L), normalized.auroraColors)
        assertEquals(listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo), normalized.dashboardWidgets)
        assertEquals(listOf("tls://8.8.8.8:853"), normalized.dnsServers)
        assertEquals(setOf("com.example.one"), normalized.packages)
        assertEquals(normalized, normalized.normalized())
    }

    @Test fun dashboardResetDoesNotResetOtherSettings() {
        val customized = LiteSettings(
            material = MaterialTier.Frosted,
            aurora = false,
            colorStyle = ColorStyle.Monochrome,
            themeColor = 0xFF123456L,
            auroraColors = listOf(0xFF010203L),
            auroraSeed = 9876543210L,
            auroraRotationDay = 20261022L,
            dashboardWidgets = listOf(DashboardWidget.MemoryInfo, DashboardWidget.CurrentOutbound),
            mode = ProxyMode.Direct,
            ipv6 = true,
            overrideDns = true,
            dnsServers = listOf("tls://8.8.8.8:853"),
            appFilterMode = AppFilterMode.Include,
            packages = setOf("com.example.one"),
            autoStart = true,
            openLogs = true,
        )
        assertEquals(customized.copy(dashboardWidgets = androidDefaults), customized.resetDashboardWidgets())
    }
}
