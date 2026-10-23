package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.integration.PlatformEntryGate
import dev.aerove.lite.integration.VpnAuthorizationIntents
import org.junit.Assert.*
import org.junit.Test

class VpnAuthorizationLifecycleTest {
    @Test fun retainedSettingsAuthorizationSurvivesActivityRecreationWithoutConnectOrDuplicateApply() {
        val gate = PlatformEntryGate()
        val retainedOwner = VpnAuthorizationIntents(gate::currentStart)
        val token = gate.beginStart()
        val settings = LiteSettings(autoStart = true).let { it.copy(backgroundPolicy = it.backgroundPolicy.copy(autoRun = true)) }
        retainedOwner.offer(token, settings)
        assertEquals(settings, retainedOwner.next()!!.settings)
        // The recreated Activity uses the same controller owner; it must not launch again.
        val recreatedActivityOwner = retainedOwner
        assertNull(recreatedActivityOwner.next())
        val actual = recreatedActivityOwner.complete(true)!!
        assertSame(settings, actual.request.settings)
        assertEquals(token, actual.request.token)
        assertTrue(actual.granted)
        assertNull(recreatedActivityOwner.complete(true))
        assertNull(recreatedActivityOwner.next())
    }
    @Test fun stopAndLaterSettingsChoiceInvalidatePermissionReceiptWithoutStartingOrSaving() {
        val gate = PlatformEntryGate()
        val owner = VpnAuthorizationIntents(gate::currentStart)
        owner.offer(gate.beginStart(), LiteSettings(autoStart = true))
        owner.next()
        gate.cancelStart()
        assertNull(owner.complete(true))
        owner.offer(gate.beginStart(), LiteSettings(autoStart = true)); owner.next()
        owner.cancelSettings()
        assertNull(owner.complete(true))
        assertNull(owner.next())
    }
    @Test fun oldCallbackDoesNotAuthorizeNewIntentAndNewestQueuedIntentIsDrivenOnlyAfterIt() {
        val gate = PlatformEntryGate()
        val owner = VpnAuthorizationIntents(gate::currentStart)
        owner.offer(gate.beginStart(), LiteSettings(autoStart = true)); owner.next()
        val replaced = gate.beginStart(); owner.offer(replaced, null)
        val latest = gate.beginStart(); owner.offer(latest, LiteSettings())
        assertNull(owner.next())
        assertNull(owner.complete(true))
        assertEquals(latest, owner.next()!!.token)
        val latestResult = owner.complete(false)!!
        assertFalse(latestResult.granted)
        assertEquals(latest, latestResult.request.token)
        assertNull(owner.complete(true))
    }
}
