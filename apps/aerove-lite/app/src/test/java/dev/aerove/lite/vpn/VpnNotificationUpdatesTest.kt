package dev.aerove.lite.vpn

import dev.aerove.lite.VpnPhase
import org.junit.Assert.*
import org.junit.Test

class VpnNotificationUpdatesTest {
    @Test fun localeEventRefreshesTheAlreadyPostedPhaseOnlyOncePerLocale() {
        val phases = mutableListOf<VpnPhase>()
        var channelUpdates = 0
        val updates = VpnNotificationUpdates(phases::add) { channelUpdates++ }
        updates.started(VpnPhase.Running, "en")
        updates.phase(VpnPhase.Running)
        assertTrue(phases.isEmpty())
        updates.configurationChanged("ja", VpnPhase.Running)
        assertEquals(listOf(VpnPhase.Running), phases)
        assertEquals(1, channelUpdates)
        updates.configurationChanged("ja", VpnPhase.Running)
        updates.phase(VpnPhase.Running)
        assertEquals(1, phases.size)
        assertEquals(1, channelUpdates)
    }

    @Test fun configurationUsesTheCurrentOwnerPhaseAndWithdrawalNeverReposts() {
        val phases = mutableListOf<VpnPhase>()
        var channelUpdates = 0
        val updates = VpnNotificationUpdates(phases::add) { channelUpdates++ }
        updates.started(VpnPhase.Running, "en")
        updates.configurationChanged("fr", VpnPhase.Stopping)
        assertEquals(listOf(VpnPhase.Stopping), phases)
        updates.withdraw()
        updates.configurationChanged("ja", VpnPhase.Running)
        updates.phase(VpnPhase.Running)
        assertEquals(listOf(VpnPhase.Stopping), phases)
        assertEquals(1, channelUpdates)
    }

    @Test fun destroyedServiceIgnoresDelayedConfigurationAndPhaseEvents() {
        val phases = mutableListOf<VpnPhase>()
        var channelUpdates = 0
        val updates = VpnNotificationUpdates(phases::add) { channelUpdates++ }
        updates.started(VpnPhase.Starting, "en")
        updates.phase(VpnPhase.Running)
        updates.close()
        updates.started(VpnPhase.Running, "fr")
        updates.configurationChanged("ja", VpnPhase.Running)
        updates.phase(VpnPhase.Stopped)
        assertEquals(listOf(VpnPhase.Running), phases)
        assertEquals(0, channelUpdates)
    }
}
