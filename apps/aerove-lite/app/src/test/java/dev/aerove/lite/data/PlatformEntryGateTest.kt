package dev.aerove.lite.data

import dev.aerove.lite.integration.PlatformEntryGate
import org.junit.Assert.*
import org.junit.Test

class PlatformEntryGateTest {
    @Test fun stopAndNewStartInvalidateThePreviouslyGrantedPermissionResult() {
        val gate = PlatformEntryGate()
        val first = gate.beginStart()
        gate.cancelStart()
        assertFalse(gate.currentStart(first))
        val second = gate.beginStart()
        assertFalse(gate.currentStart(first))
        assertTrue(gate.currentStart(second))
    }

    @Test fun loadAndConsentDoNotConsumeAnAutoRunIntentAndLaterPreferenceEditsDoNotRerunInitialization() {
        val gate = PlatformEntryGate()
        assertFalse(gate.consumeAutoRun(false, true, true, true, true))
        assertFalse(gate.consumeAutoRun(true, false, true, true, true))
        assertTrue(gate.consumeAutoRun(true, true, true, true, true))
        assertFalse(gate.consumeAutoRun(true, true, true, true, true))
        val disabled = PlatformEntryGate()
        assertFalse(disabled.consumeAutoRun(true, true, false, true, true))
        assertFalse(disabled.consumeAutoRun(true, true, true, true, true))
    }

    @Test fun coldAndHotDeliveryOfTheSameShortcutIdentityRunsOnlyOnceWithinTheRetainedOwner() {
        val gate = PlatformEntryGate()
        assertFalse(gate.acceptRequest(""))
        assertFalse(gate.acceptRequest("a".repeat(81)))
        assertTrue(gate.acceptRequest("shortcut-request"))
        assertFalse(gate.acceptRequest("shortcut-request"))
        assertTrue(gate.acceptRequest("next-explicit-gesture"))
    }

    @Test fun explicitStopBeforeLoadingCannotBeUndoneByTheLaterAutoRunInitialization() {
        val gate = PlatformEntryGate()
        gate.suppressAutoRun()
        gate.cancelStart()
        assertFalse(gate.consumeAutoRun(true, true, true, true, true))
    }
}
