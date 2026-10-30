package dev.aerove.client.platform

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class FontCatalogStoreTest {
    @Test fun aReattachedEngineReusesTheProcessCatalog() {
        val store = FontCatalogStore()
        val first = FontCatalogCache(store)
        assertEquals(listOf("Alpha"), first.read { listOf("Alpha") })
        first.close()
        val second = FontCatalogCache(store)
        assertEquals(listOf("Alpha"), second.read { error("a reattached engine must not rescan") })
    }

    @Test fun detachingOneEngineLeavesAnotherScanRunning() {
        val store = FontCatalogStore()
        val detached = FontCatalogCache(store)
        val running = FontCatalogCache(store)
        val families = running.read { checkpoint ->
            detached.close()
            checkpoint()
            listOf("Beta")
        }
        assertEquals(listOf("Beta"), families)
        assertThrows(CancellationException::class.java) { detached.read { listOf("late") } }
        assertEquals(listOf("Beta"), FontCatalogCache(store).read { error("cached read must not scan") })
    }

    @Test fun aConfigurationChangeDropsTheCatalogForEveryEngine() {
        val store = FontCatalogStore()
        val attached = FontCatalogCache(store)
        attached.read { listOf("Old") }
        store.invalidate()
        assertEquals(listOf("New"), FontCatalogCache(store).read { listOf("New") })
        assertEquals(listOf("New"), attached.read { error("cached read must not scan") })
    }
}
