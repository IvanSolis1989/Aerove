package dev.aerove.lite.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkOwnerTest {
    private data class Caps(val wifi: Boolean = false, val internet: Boolean = true, val ssid: String? = null)
    private data class Links(val dns: String?, val address: String = "10.0.0.2")
    private class Driver : PhysicalNetworkDriver<String, Caps, Links> {
        override var activeNetwork: String? = "wifi"
        var initial = ObservedPhysicalNetworks(setOf("wifi"), mapOf("wifi" to Caps(wifi = true)), mapOf("wifi" to Links("1.1.1.1")))
        val callbacks = mutableListOf<PhysicalNetworkEvents<String, Caps, Links>>()
        val flags = mutableListOf<Boolean>()
        var active = 0
        var maximum = 0
        var failNext = false
        var closeFailures = 0
        override fun seed() = initial
        override fun register(includeLocation: Boolean, events: PhysicalNetworkEvents<String, Caps, Links>): AutoCloseable {
            if (failNext) { failNext = false; throw IllegalStateException("registration failed") }
            active++
            maximum = maxOf(maximum, active)
            check(active == 1)
            callbacks += events
            flags += includeLocation
            var released = false
            return AutoCloseable {
                if (!released) {
                    if (closeFailures > 0) { closeFailures--; throw IllegalStateException("unregister not acknowledged") }
                    active--; check(active == 0); released = true
                }
            }
        }
    }
    private fun owner(driver: Driver) = LeasedPhysicalNetworks(driver, { it.internet }, { it.wifi },
        { network, links -> links.dns?.let { "$network:$it" } }, { it.firstOrNull()?.address })

    @Test fun appServiceAndFocusShareOneRegistrationAndLastReleaseKeepsSnapshot() {
        val driver = Driver()
        val owner = owner(driver)
        val maintenance = owner.acquire()
        val service = owner.acquire()
        val focusedUi = owner.acquire()
        val snapshot = owner.snapshots.value
        assertEquals("wifi", snapshot.preferred)
        assertEquals("10.0.0.2", snapshot.address)
        assertEquals(1L, owner.revision.value)
        focusedUi.close()
        assertEquals(1, driver.active)
        service.close()
        assertEquals(1, driver.active)
        driver.callbacks.single().lost("wifi")
        assertNull(owner.snapshots.value.preferred)
        driver.callbacks.single().available("wifi")
        driver.callbacks.single().capabilities("wifi", Caps(wifi = true))
        driver.callbacks.single().links("wifi", Links("1.1.1.1"))
        assertEquals(3L, owner.revision.value)
        val retained = owner.snapshots.value
        maintenance.close()
        assertEquals(0, driver.active)
        assertEquals(retained, owner.snapshots.value)
        val revision = owner.revision.value
        driver.callbacks.single().lost("wifi")
        assertEquals(retained, owner.snapshots.value)
        assertEquals(revision, owner.revision.value)
        maintenance.close()
        val foregroundAgain = owner.acquire()
        assertEquals(1, driver.active)
        foregroundAgain.close()
        assertEquals(1, driver.maximum)
    }

    @Test fun identicalSnapshotsDoNotJitterButDnsOrPhysicalHandleDo() {
        val driver = Driver()
        val owner = owner(driver)
        val lease = owner.acquire()
        val events = driver.callbacks.single()
        events.capabilities("wifi", Caps(wifi = true))
        val snapshot = owner.snapshots.value
        val revision = owner.revision.value
        events.capabilities("wifi", Caps(wifi = true))
        events.links("wifi", Links("1.1.1.1"))
        assertEquals(snapshot, owner.snapshots.value)
        assertEquals(revision, owner.revision.value)
        events.links("wifi", Links("9.9.9.9"))
        assertEquals("10.0.0.2", owner.snapshots.value.address)
        assertEquals(revision + 1, owner.revision.value)
        driver.activeNetwork = "cell"
        events.available("cell")
        events.capabilities("cell", Caps())
        events.links("cell", Links("9.9.9.9"))
        assertEquals("cell", owner.snapshots.value.preferred)
        assertEquals(revision + 2, owner.revision.value)
        lease.close()
    }

    @Test fun locationUpgradeAndDowngradeInvalidateOldCallbacksWithoutOverlap() {
        val driver = Driver()
        val owner = owner(driver)
        val maintenance = owner.acquire()
        val old = driver.callbacks.last()
        val service = owner.acquire(true)
        assertEquals(listOf(false, true), driver.flags)
        assertTrue(owner.snapshots.value.observedCapabilities.isEmpty())
        old.lost("wifi")
        assertEquals("wifi", owner.snapshots.value.preferred)
        val authorized = driver.callbacks.last()
        authorized.capabilities("wifi", Caps(wifi = true, ssid = "Home"))
        owner.refresh()
        assertEquals("Home", owner.snapshots.value.capabilities["wifi"]!!.ssid)
        service.updateLocation(false)
        assertEquals(listOf(false, true, false), driver.flags)
        assertTrue(owner.snapshots.value.observedCapabilities.isEmpty())
        authorized.capabilities("wifi", Caps(wifi = true, ssid = "Late"))
        assertNull(owner.snapshots.value.capabilities["wifi"]!!.ssid)
        service.close()
        assertEquals(1, driver.active)
        maintenance.close()
        assertEquals(1, driver.maximum)
    }

    @Test fun unknownLinksRemainUnknownUntilRealDnsArrivesAndSelectionKeepsOriginalOrder() {
        val driver = Driver()
        driver.activeNetwork = null
        driver.initial = ObservedPhysicalNetworks(linkedSetOf("cell", "wifi"), mapOf("cell" to Caps(), "wifi" to Caps(wifi = true)))
        val owner = owner(driver)
        val lease = owner.acquire()
        assertEquals("wifi", owner.snapshots.value.preferred)
        assertEquals(0L, owner.revision.value)
        assertNull(owner.snapshots.value.address)
        val events = driver.callbacks.single()
        events.links("wifi", Links(null))
        assertEquals(0L, owner.revision.value)
        events.links("wifi", Links("1.1.1.1"))
        assertEquals(1L, owner.revision.value)
        driver.activeNetwork = "cell"
        events.links("cell", Links("8.8.8.8"))
        assertEquals("cell", owner.snapshots.value.preferred)
        events.lost("cell")
        assertEquals("wifi", owner.snapshots.value.preferred)
        lease.close()
    }

    @Test fun registrationFailureRestoresExistingDemandAndRejectsRetiredCallbacks() {
        val driver = Driver()
        val owner = owner(driver)
        val app = owner.acquire()
        val old = driver.callbacks.last()
        driver.failNext = true
        try { owner.acquire(true); fail("must report registration failure") } catch (_: IllegalStateException) { }
        assertEquals(1, driver.active)
        assertEquals(listOf(false, false), driver.flags)
        old.lost("wifi")
        assertEquals("wifi", owner.snapshots.value.preferred)
        app.close()
        assertEquals(0, driver.active)
        assertEquals(1, driver.maximum)
    }

    @Test fun cancelledFocusCollectionReleasesItsLeaseWhileServiceStillOwnsDemand() = runBlocking<Unit> {
        val driver = Driver()
        val owner = owner(driver)
        val service = owner.acquire()
        val started = CompletableDeferred<Unit>()
        val focus = launch {
            val ui = owner.acquire()
            try { owner.snapshots.collect { started.complete(Unit) } } finally { ui.close() }
        }
        started.await()
        focus.cancelAndJoin()
        assertTrue(focus.isCompleted)
        assertEquals(1, driver.active)
        service.close()
        assertEquals(0, driver.active)
    }
    @Test fun failedFinalUnregisterMustBeAcknowledgedBeforeNewRegistrationCanRecoverEvents() {
        val driver = Driver()
        val owner = owner(driver)
        val lease = owner.acquire()
        val retired = driver.callbacks.single()
        driver.closeFailures = 1
        try { lease.close(); fail("must report unconfirmed unregister") } catch (_: IllegalStateException) { }
        assertEquals(1, driver.active)
        val revision = owner.revision.value
        retired.lost("wifi")
        assertEquals("wifi", owner.snapshots.value.preferred)
        val next = owner.acquire()
        assertEquals(1, driver.active)
        assertEquals(2, driver.callbacks.size)
        retired.links("wifi", Links("8.8.8.8"))
        assertEquals(revision, owner.revision.value)
        driver.callbacks.last().links("wifi", Links("9.9.9.9"))
        assertEquals(revision + 1, owner.revision.value)
        next.close()
        assertEquals(0, driver.active)
        assertEquals(1, driver.maximum)
    }

    @Test fun failedLocationUpgradeCanRetryRetirementThroughExistingDemandRefresh() {
        val driver = Driver()
        val owner = owner(driver)
        val app = owner.acquire()
        driver.closeFailures = 2
        try { owner.acquire(true); fail("must report unconfirmed unregister") } catch (_: IllegalStateException) { }
        assertEquals(listOf(false), driver.flags)
        assertEquals(1, driver.active)
        owner.refresh()
        assertEquals(listOf(false, false), driver.flags)
        val revision = owner.revision.value
        driver.callbacks.last().lost("wifi")
        assertEquals(revision + 1, owner.revision.value)
        app.close()
        assertEquals(0, driver.active)
        assertEquals(1, driver.maximum)
    }

    @Test fun failedLocationDowngradeCanRecoverOnSameValueLeaseUpdateWithoutLocationDemand() {
        val driver = Driver()
        val owner = owner(driver)
        val app = owner.acquire()
        val service = owner.acquire(true)
        val retired = driver.callbacks.last()
        driver.closeFailures = 1
        try { service.close(); fail("must report unconfirmed unregister") } catch (_: IllegalStateException) { }
        retired.capabilities("wifi", Caps(wifi = true, ssid = "Late"))
        assertNull(owner.snapshots.value.capabilities["wifi"]!!.ssid)
        app.updateLocation(false)
        assertEquals(listOf(false, true, false), driver.flags)
        assertTrue(owner.snapshots.value.observedCapabilities.isEmpty())
        assertEquals(1, driver.active)
        app.close()
        assertEquals(0, driver.active)
        assertEquals(1, driver.maximum)
    }

    @Test fun closedLeaseCanRetryPendingFinalUnregisterWithoutReopeningDemand() {
        val driver = Driver()
        val owner = owner(driver)
        val lease = owner.acquire()
        driver.closeFailures = 1
        try { lease.close(); fail("must report unconfirmed unregister") } catch (_: IllegalStateException) { }
        lease.close()
        assertEquals(0, driver.active)
        assertEquals(listOf(false), driver.flags)
        lease.updateLocation(true)
        assertEquals(0, driver.active)
    }

}
