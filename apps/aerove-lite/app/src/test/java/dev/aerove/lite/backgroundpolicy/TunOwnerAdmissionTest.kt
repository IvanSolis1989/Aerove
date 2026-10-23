package dev.aerove.lite.backgroundpolicy

import dev.aerove.lite.core.ConnectionOwnerResolver
import dev.aerove.lite.vpn.TunOwnerAdmission
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress

class TunOwnerAdmissionTest {
    private val source = InetSocketAddress("198.18.0.1", 10001)
    private val target = InetSocketAddress("192.0.2.1", 443)

    @Test fun parkedOwnerIsUnknownUntilActualEstablishAndClosedOwnerCannotReopen() {
        val gate = TunOwnerAdmission { true }
        var calls = 0
        val resolver = ConnectionOwnerResolver({ _, _, _ -> calls++; 42 }, { arrayOf("app.example") }, gate::admitted, { false })
        assertNull(resolver.resolve(6, source, target)); assertEquals(0, calls)
        gate.established(); assertEquals(42, resolver.resolve(6, source, target)?.uid)
        gate.park(); assertNull(resolver.resolve(6, source, target)); assertEquals(1, calls)
        gate.established(); assertNotNull(resolver.resolve(17, source, target))
        gate.close(); assertNull(resolver.resolve(6, source, target))
        assertThrows(IllegalStateException::class.java) { gate.established() }
    }

    @Test fun supersededSessionCannotPublishUidOrReuseAnotherSessionsAdmission() {
        var generation = 1
        val old = TunOwnerAdmission { generation == 1 }; old.established()
        var packageReads = 0
        val resolver = ConnectionOwnerResolver({ _, _, _ -> generation = 2; 42 }, { packageReads++; arrayOf("old.app") }, old::admitted, { false })
        assertNull(resolver.resolve(6, source, target)); assertEquals(0, packageReads)
        val fresh = TunOwnerAdmission { generation == 2 }; fresh.established()
        assertTrue(fresh.admitted()); assertFalse(old.admitted())
        assertThrows(IllegalStateException::class.java) { old.established() }
    }

    @Test fun onlyPackageEventsClearTheBoundedPackageNameCache() {
        val gate = TunOwnerAdmission { true }; gate.established()
        var name = "before.app"; var packageReads = 0
        val resolver = ConnectionOwnerResolver({ _, _, _ -> 42 }, { packageReads++; arrayOf(name) }, gate::admitted, { false })
        assertEquals("before.app", resolver.resolve(6, source, target)?.process)
        name = "after.app"
        assertEquals("before.app", resolver.resolve(6, source, target)?.process)
        assertEquals(1, packageReads)
        resolver.clearPackageCache()
        assertEquals("after.app", resolver.resolve(6, source, target)?.process)
        assertEquals(2, packageReads)
    }

    @Test fun permissionDenialAndMissingUidRemainUnknownRatherThanRootUid() {
        val gate = TunOwnerAdmission { true }; gate.established()
        val denied = ConnectionOwnerResolver({ _, _, _ -> throw SecurityException("revoked") }, { error("not reached") }, gate::admitted, { false })
        assertNull(denied.resolveConnectionOwner(6, "198.18.0.1:10001", "192.0.2.1:443"))
        val unknown = ConnectionOwnerResolver({ _, _, _ -> -1 }, { error("not reached") }, gate::admitted, { false })
        assertNull(unknown.resolve(6, source, target))
    }
    @Test fun startupTakesLatestProcessPolicyWithoutAdoptingPendingBuilderOrAppearance() {
        val planned = dev.aerove.lite.LiteSettings(findProcessMode = dev.aerove.lite.FindProcessMode.Strict)
        val saved = planned.copy(findProcessMode = dev.aerove.lite.FindProcessMode.Always, unifiedDelay = false,
            network = planned.network.copy(mtu = 1400), fontFamily = "pending-font")
        val startup = dev.aerove.lite.vpn.runtimePoliciesForStart(planned, saved)
        assertEquals(dev.aerove.lite.FindProcessMode.Always, startup.findProcessMode)
        assertFalse(startup.unifiedDelay)
        assertEquals(planned.network, startup.network)
        assertEquals(planned.fontFamily, startup.fontFamily)
    }

    @Test fun startupPreservesAllSavedLogicalProcessModes() {
        val planned = dev.aerove.lite.LiteSettings()
        for (saved in dev.aerove.lite.FindProcessMode.entries) {
            assertEquals(saved, dev.aerove.lite.vpn.runtimePoliciesForStart(planned, planned.copy(findProcessMode = saved)).findProcessMode)
        }
    }

    @Test fun delayedOldPlanCannotCaptureTheNewRunsCurrentIntentToken() {
        var current = 2L; var requested = true
        val old = dev.aerove.lite.vpn.capturedOwnerIntent(1L, { current }, { requested })
        assertFalse(old())
        val fresh = dev.aerove.lite.vpn.capturedOwnerIntent(2L, { current }, { requested })
        assertTrue(fresh())
        current = 3L
        assertFalse(fresh())
        assertFalse(old())
        requested = false
        assertFalse(dev.aerove.lite.vpn.capturedOwnerIntent(3L, { current }, { requested })())
        assertThrows(IllegalStateException::class.java) {
            dev.aerove.lite.vpn.capturedOwnerIntent(null, { current }, { true })
        }
    }

}
