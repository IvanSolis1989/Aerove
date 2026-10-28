package dev.aerove.lite.ui

import dev.aerove.lite.DashboardWidget
import dev.aerove.lite.Traffic
import org.junit.Assert.*
import org.junit.Test

class DashboardPresentationTest {
    @Test fun savedMembersAnchorAtTheirEarliestPositionWithoutChangingPreferences() {
        val saved = listOf(DashboardWidget.NetworkSpeed, DashboardWidget.Profiles, DashboardWidget.CurrentOutbound,
            DashboardWidget.IntranetIp, DashboardWidget.ServiceStatus, DashboardWidget.VpnButton,
            DashboardWidget.ProxyGroups, DashboardWidget.ConnectionStats, DashboardWidget.OutboundMode)
        val copy = saved.toList()
        assertEquals(listOf(DashboardWidget.Profiles, DashboardWidget.CurrentOutbound, DashboardWidget.IntranetIp,
            DashboardWidget.ServiceStatus, DashboardWidget.ProxyGroups), dashboardPresentationWidgets(saved))
        assertEquals(copy, saved)
    }
    @Test fun hidingGroupMembersNeverRestoresThemOrDropsIndependentCards() {
        val saved = listOf(DashboardWidget.CurrentOutbound, DashboardWidget.MemoryInfo, DashboardWidget.ConnectionStats)
        assertEquals(saved, dashboardPresentationWidgets(saved))
        assertEquals(emptyList<DashboardWidget>(), dashboardPresentationWidgets(emptyList()))
        assertEquals(listOf(DashboardWidget.VpnButton), dashboardPresentationWidgets(listOf(DashboardWidget.VpnButton)))
    }
    @Test fun trafficUsesRealSamplesWithEmptyAndZeroDistinct() {
        assertTrue(dashboardTrafficCurves(emptyList()).upload.isEmpty())
        val zero = dashboardTrafficCurves(listOf(Traffic()))
        assertEquals(listOf(DashboardCurvePoint(0f, 1f), DashboardCurvePoint(1f, 1f)), zero.upload)
        assertEquals(zero.upload, zero.download)
    }
    @Test fun bothDirectionsUseTheSamePeakAndOnlyLastThirtySamples() {
        val curves = dashboardTrafficCurves(List(31) { index -> Traffic(if (index == 0) Long.MAX_VALUE else 8192, 16384) })
        assertEquals(30, curves.upload.size)
        assertEquals(0f, curves.upload.first().x, 0f)
        assertEquals(1f, curves.upload.last().x, 0f)
        assertEquals(0.69f, curves.upload.first().y, 0.00001f)
        assertEquals(0.38f, curves.download.first().y, 0.00001f)
    }
    @Test fun smallRealRatesKeepTheMinimumScaleWithoutInventingPeaks() {
        val curves = dashboardTrafficCurves(listOf(Traffic(4096, 0)))
        assertEquals(0.69f, curves.upload.first().y, 0.00001f)
        assertEquals(1f, curves.download.first().y, 0f)
    }
    @Test fun lanCopyUsesTheActualPortAndBracketsIpv6OnlyWhenEnabled() {
        assertEquals("192.0.2.8:7890", dashboardLanAddress("192.0.2.8", 7890, true))
        assertEquals("[2001:db8::8]:7890", dashboardLanAddress("2001:db8::8", 7890, true))
        assertEquals("[2001:db8::8]:7890", dashboardLanAddress("[2001:db8::8]", 7890, true))
        assertEquals("192.0.2.8", dashboardLanAddress("192.0.2.8", 7890, false))
        assertNull(dashboardLanAddress(null, 7890, true))
        assertNull(dashboardLanAddress("192.0.2.8", 0, true))
    }
    @Test fun interruptedShapeChangesStartAtTheCurrentRealTrace() {
        val first = dashboardTrafficCurves(listOf(Traffic(8192, 4096)))
        val second = dashboardTrafficCurves(listOf(Traffic(0, 16384), Traffic(4096, 0)))
        val current = interpolateDashboardCurves(first, second, 0.4f)
        val third = dashboardTrafficCurves(listOf(Traffic(16384, 8192)))
        assertEquals(current, interpolateDashboardCurves(current, third, 0f))
        assertEquals(third, interpolateDashboardCurves(current, third, 1f))
        assertTrue((current.upload + current.download).all { it.y in 0f..1f })
    }

    @Test fun fusedFamiliesKeepStableIdentityAndOrderedPartialMembers() {
        val raw = listOf(DashboardWidget.IntranetIp, DashboardWidget.Profiles, DashboardWidget.ConnectionStats,
            DashboardWidget.VpnButton, DashboardWidget.MemoryInfo, DashboardWidget.ServiceStatus)
        val groups = dashboardPresentationGroups(raw)
        assertEquals(listOf(DashboardFamily.Controls, DashboardFamily.Profiles, DashboardFamily.Overview, DashboardFamily.Memory), groups.map { it.family })
        assertEquals(DashboardWidget.OutboundMode, groups.first().identity)
        assertEquals(DashboardWidget.IntranetIp, groups.first().anchor)
        assertEquals(listOf(DashboardWidget.IntranetIp, DashboardWidget.VpnButton), groups.first().members)
        assertEquals(listOf(DashboardWidget.ConnectionStats, DashboardWidget.ServiceStatus), groups[2].members)
        assertEquals(groups.map { it.anchor }, dashboardPresentationWidgets(raw))
        assertFalse(DashboardFamily.Controls in dashboardAddableFamilies(raw))
        assertFalse(DashboardFamily.Overview in dashboardAddableFamilies(raw))
    }
    @Test fun movingOneFamilyPreservesOtherRawOrderingAndDoesNotFillPartialGroups() {
        val raw = listOf(DashboardWidget.IntranetIp, DashboardWidget.Profiles, DashboardWidget.VpnButton,
            DashboardWidget.ConnectionStats, DashboardWidget.MemoryInfo, DashboardWidget.ServiceStatus)
        assertEquals(listOf(DashboardWidget.Profiles, DashboardWidget.ConnectionStats, DashboardWidget.IntranetIp,
            DashboardWidget.VpnButton, DashboardWidget.MemoryInfo, DashboardWidget.ServiceStatus),
            dashboardMoveFamily(raw, DashboardFamily.Controls, DashboardFamily.Overview, after = true))
        assertEquals(raw, dashboardMoveFamily(raw, DashboardFamily.Controls, DashboardFamily.Controls, after = true))
        assertEquals(raw, dashboardMoveFamily(raw, DashboardFamily.Controls, DashboardFamily.Status, after = true))
        assertFalse(DashboardWidget.OutboundMode in dashboardMoveFamily(raw, DashboardFamily.Controls, DashboardFamily.Memory, after = true))
    }
    @Test fun explicitRestoreAddsCompleteFamilyWhileUntouchedPartialGroupsStayPartial() {
        val raw = listOf(DashboardWidget.IntranetIp, DashboardWidget.MemoryInfo, DashboardWidget.ConnectionStats)
        val hidden = dashboardHideFamily(raw, DashboardFamily.Controls)
        assertEquals(listOf(DashboardWidget.MemoryInfo, DashboardWidget.ConnectionStats), hidden)
        assertEquals(1, dashboardAddableFamilies(hidden).count { it == DashboardFamily.Controls })
        assertEquals(hidden + listOf(DashboardWidget.OutboundMode, DashboardWidget.VpnButton, DashboardWidget.IntranetIp),
            dashboardRestoreFamily(hidden, DashboardFamily.Controls))
        assertEquals(raw, dashboardRestoreFamily(raw, DashboardFamily.Controls))
        assertEquals(raw, dashboardHideFamily(raw, DashboardFamily.Status))
        assertEquals(emptyList<DashboardWidget>(), dashboardPresentationWidgets(emptyList()))
        assertTrue(DashboardFamily.entries.none { DashboardWidget.NetworkSpeed in it.supported })
        assertEquals(listOf(DashboardWidget.ConnectionStats), dashboardPresentationGroups(raw).first { it.family == DashboardFamily.Overview }.members)
    }}
