package dev.aerove.lite.backgroundpolicy

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.networksettings.NetworkSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundSettingsReadbackTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun aNewStoreReadsSavedPolicyAndExactReorderedSsids() = runBlocking {
        val root = directory.newFolder("settings")
        val policy = BackgroundPolicyPreferences(autoRun = true, minimizeOnExit = false, hidden = true,
            onlyStatisticsProxy = false, closeConnections = false, excludeSSIDs = listOf(" Office ", "咖啡"),
            showNotificationStopAction = false)
        val settings = LiteSettings(autoStart = true, network = NetworkSettings(vpnEnabled = false), backgroundPolicy = policy)
        LiteSettingsStore(root).save(settings)
        assertEquals(policy, LiteSettingsStore(root).read().backgroundPolicy)
        val reordered = policy.moveSsid(1, 0)
        LiteSettingsStore(root).save(settings.copy(backgroundPolicy = reordered))
        assertEquals(reordered, LiteSettingsStore(root).read().backgroundPolicy)
    }
}
