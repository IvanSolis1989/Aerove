package dev.aerove.lite.platform

import dev.aerove.lite.integration.ResourceMaintenanceLifecycle
import dev.aerove.lite.telemetry.UiReadSignals
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class MaintenanceNetworkLeaseTest {
    private class Driver : PhysicalNetworkDriver<String, String, String> {
        var registrations = 0; var closes = 0
        lateinit var events: PhysicalNetworkEvents<String, String, String>
        override val activeNetwork = "wifi"
        override fun seed() = ObservedPhysicalNetworks(setOf("wifi"), mapOf("wifi" to "wifi"), mapOf("wifi" to "dns1:10.0.0.2"))
        override fun register(includeLocation: Boolean, events: PhysicalNetworkEvents<String, String, String>): AutoCloseable {
            assertFalse(includeLocation); registrations++; this.events = events
            return AutoCloseable { closes++ }
        }
    }
    private suspend fun until(condition: () -> Boolean) = withTimeout(4000) { while (!condition()) delay(2) }
    @Test fun uiFocusReleasesOnlyItsLeaseAndMaintenanceStillObservesNetworkRecoveryWithoutService() = runBlocking<Unit> {
        val driver = Driver()
        val physical = LeasedPhysicalNetworks(driver, { true }, { it == "wifi" }, { n, link -> "$n:$link" }, { links -> links.firstOrNull()?.substringAfter(':') })
        var resets = 0; var rounds = 0
        val maintenance = ResourceMaintenanceLifecycle<Any>(this, { null }, { true }, { _, current, _ -> assertTrue(current()); rounds++ },
            { resets++ }, physical.revision.value, "stopped", { throw it })
        maintenance.foreground(true); maintenance.ready()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { physical.revision.collect(maintenance::network) }
        val maintenanceLease = physical.acquire(false)
        val signals = UiReadSignals(); signals.foreground(true); signals.focused(true)
        val seen = mutableListOf<String?>()
        val ui = launch { signals.state.collectLatest { demand ->
            if (demand.active) leasedNetworkAddresses({ physical.acquire(false) }, physical.snapshots.map { it.address }).collect { seen += it }
        } }
        try {
            until { seen.lastOrNull() == "10.0.0.2" && resets == 1 }
            assertEquals(1, driver.registrations)
            signals.focused(false); delay(20); assertEquals(0, driver.closes)
            driver.events.links("wifi", "dns2:10.0.0.3"); until { resets == 2 }
            assertEquals(listOf("10.0.0.2"), seen)
            maintenance.afterAutomaticSweep { true }; assertEquals(2, rounds)
            driver.events.links("wifi", "dns2:10.0.0.3"); delay(10); assertEquals(2, resets)
            signals.focused(true); until { seen.lastOrNull() == "10.0.0.3" }
            assertEquals(1, driver.registrations); assertEquals(0, driver.closes)
            maintenance.foreground(false); maintenanceLease.close(); assertEquals(0, driver.closes)
            signals.focused(false); until { driver.closes == 1 }
        } finally { ui.cancelAndJoin(); observer.cancelAndJoin(); maintenanceLease.close(); maintenance.close() }
    }
    @Test fun addressProjectionClosesItsOwnLeaseOnCancelAndReadbackIsLatest() = runBlocking<Unit> {
        val values = MutableStateFlow<String?>("one"); var leases = 0; var closes = 0
        fun acquire(): AutoCloseable { leases++; return AutoCloseable { closes++ } }
        val seen = mutableListOf<String?>()
        var read = launch { leasedNetworkAddresses(::acquire, values).collect { seen += it } }
        until { seen == listOf("one") }; read.cancelAndJoin(); values.value = "two"
        assertEquals(1, closes); assertEquals(listOf("one"), seen)
        read = launch { leasedNetworkAddresses(::acquire, values).collect { seen += it } }
        until { seen == listOf("one", "two") }; read.cancelAndJoin()
        assertEquals(2, leases); assertEquals(2, closes)
    }
}
