package dev.aerove.lite.ui

import dev.aerove.lite.networksettings.DashboardLanReceipt
import org.junit.Assert.*
import org.junit.Test

class DashboardLanFeedbackTest {
    @Test fun passiveRestoreMissingOrUnrelatedReceiptAndFailureCannotCopy() {
        val intent = DashboardLanFeedbackIntent()
        assertFalse(intent.consume(DashboardLanReceipt(1, 8899), true, 8899, true))
        intent.requested(2)
        assertFalse(intent.consume(null, true, 8899, true))
        assertFalse(intent.consume(DashboardLanReceipt(1, 8899), true, 8899, true))
        assertFalse(intent.consume(DashboardLanReceipt(2, null), true, 8899, true))
        assertFalse(intent.consume(DashboardLanReceipt(2, 8899), true, 8899, true))
    }

    @Test fun onlyMatchingEnabledPortIsConsumedExactlyOnce() {
        val intent = DashboardLanFeedbackIntent(); intent.requested(2)
        assertTrue(intent.consume(DashboardLanReceipt(2, 8899), true, 8899, true))
        assertFalse(intent.consume(DashboardLanReceipt(2, 8899), true, 8899, true))
        intent.requested(3)
        assertFalse(intent.consume(DashboardLanReceipt(3, 8899), false, 8899, true))
        intent.requested(4)
        assertFalse(intent.consume(DashboardLanReceipt(4, 8899), true, 7890, true))
    }

    @Test fun leavingOrBackgroundingDropsTheGesturePermanently() {
        val intent = DashboardLanFeedbackIntent(); intent.requested(1)
        assertFalse(intent.consume(null, false, 8899, false))
        assertFalse(intent.consume(DashboardLanReceipt(1, 8899), true, 8899, true))
        intent.requested(2); intent.invalidate()
        assertFalse(intent.consume(DashboardLanReceipt(2, 8899), true, 8899, true))
    }

    @Test fun addressUsesTheRealPortAndIpv6BracketsWithoutAFallback() {
        assertEquals("192.0.2.8:8899", dashboardLanAddress("192.0.2.8", 8899, true))
        assertEquals("[2001:db8::8]:8899", dashboardLanAddress("2001:db8::8", 8899, true))
        assertNull(dashboardLanAddress(null, 8899, true))
        assertNull(dashboardLanAddress(" ", 8899, true))
    }
}
