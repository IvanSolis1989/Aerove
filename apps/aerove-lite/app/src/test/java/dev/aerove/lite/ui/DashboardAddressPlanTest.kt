package dev.aerove.lite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAddressPlanTest {
    @Test fun fittingAddressKeepsItsOriginalScale() {
        val plan = dashboardAddressPlan("198.51.100.8", 120f, 134, 22f)
        assertEquals(1f, plan.scale, 0f)
        assertEquals("198.51.100.8", plan.shown)
        assertFalse(plan.wrap)
    }

    @Test fun widerIpv4UsesContinuousScaleBeforeWrapping() {
        val plan = dashboardAddressPlan("198.51.100.10", 147f, 134, 22f)
        assertEquals(134f / 147f, plan.scale, 0f)
        assertEquals("198.51.100.10", plan.shown)
        assertFalse(plan.wrap)
        assertTrue(22f * plan.scale >= 11f)
    }

    @Test fun exactReadableFloorStillFitsOnOneLine() {
        val plan = dashboardAddressPlan("255.255.255.255", 268f, 134, 22f)
        assertEquals(0.5f, plan.scale, 0f)
        assertFalse(plan.wrap)
    }

    @Test fun ipv6TooWideAtTheFloorUsesOnlyAddressBreaks() {
        val address = "2001:0db8:85a3:0000:0000:8a2e:0370:7334"
        val plan = dashboardAddressPlan(address, 700f, 134, 28.6f)
        assertEquals(11f / 28.6f, plan.scale, 0f)
        assertTrue(plan.wrap)
        assertEquals(address, plan.shown.replace("\u200B", ""))
        assertEquals(7, plan.shown.count { it == '\u200B' })
        assertTrue(plan.shown.zipWithNext().filter { it.second == '\u200B' }.all { it.first == ':' })
    }

    @Test fun dottedAddressTooWideAtTheFloorRetainsAllDigits() {
        val plan = dashboardAddressPlan("255.255.255.255", 300f, 100, 22f)
        assertEquals("255.\u200B255.\u200B255.\u200B255", plan.shown)
        assertTrue(plan.wrap)
        assertEquals(0.5f, plan.scale, 0f)
    }

    @Test fun smallerUserTextIsNeverScaledUpToTheFloor() {
        val plan = dashboardAddressPlan("::1", 20f, 134, 10f)
        assertEquals(1f, plan.scale, 0f)
        assertFalse(plan.wrap)
    }
}
