package dev.aerove.lite.backgroundpolicy

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.VpnNotificationUpdates
import dev.aerove.lite.vpn.notificationShowsStopAction
import org.junit.Assert.*
import org.junit.Test

class BackgroundServicePolicyTest {
    @Test fun onlyPlainProxyNotificationsExposeTheEnabledStopAction() {
        assertTrue(notificationShowsStopAction(false, true))
        assertFalse(notificationShowsStopAction(false, false))
        assertFalse(notificationShowsStopAction(true, true))
        assertFalse(notificationShowsStopAction(true, false))
    }

    @Test fun savedNotificationPreferenceRefreshesWithoutRestartingWithdrawnNotification() {
        val published = mutableListOf<VpnPhase>()
        val updates = VpnNotificationUpdates(published::add) {}
        updates.started(VpnPhase.Running, "en")
        updates.contentChanged(VpnPhase.Running)
        assertEquals(listOf(VpnPhase.Running), published)
        updates.withdraw()
        updates.contentChanged(VpnPhase.Running)
        assertEquals(1, published.size)
        updates.close()
        updates.contentChanged(VpnPhase.Stopped)
        assertEquals(1, published.size)
    }
}
