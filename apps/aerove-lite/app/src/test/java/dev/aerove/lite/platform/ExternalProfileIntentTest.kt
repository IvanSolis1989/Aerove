package dev.aerove.lite.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.aerove.lite.VpnPhase

class ExternalProfileIntentTest {
    @Test
    fun acceptsSupportedInstallSchemesWithoutChangingTheLink() {
        listOf("clash", "clashmeta", "flclash").forEach { scheme ->
            val link = "$scheme://install-config?url=https%3A%2F%2Fexample.com%2Fsub&name=Work"
            assertEquals(ExternalProfileIntent.Valid(link), ExternalProfileIntent.parse(link))
        }
    }

    @Test
    fun rejectsMissingMalformedOrUntrustedTargets() {
        assertEquals(ExternalProfileIntent.Invalid(ExternalProfileIntent.Reason.MALFORMED), ExternalProfileIntent.parse(null))
        assertEquals(ExternalProfileIntent.Invalid(ExternalProfileIntent.Reason.MALFORMED), ExternalProfileIntent.parse("   "))
        assertEquals(ExternalProfileIntent.Invalid(ExternalProfileIntent.Reason.MALFORMED), ExternalProfileIntent.parse("clash://["))
        assertEquals(ExternalProfileIntent.Invalid(ExternalProfileIntent.Reason.UNSUPPORTED_SCHEME),
            ExternalProfileIntent.parse("https://install-config?url=https%3A%2F%2Fexample.com"))
        listOf(
            "clash://other?url=https%3A%2F%2Fexample.com",
            "clash://install-config/path?url=https%3A%2F%2Fexample.com",
            "clash://install-config?url=https%3A%2F%2Fexample.com#fragment",
            "clash://install-config@evil.example?url=https%3A%2F%2Fexample.com",
        ).forEach { link ->
            assertTrue("expected invalid install target: $link",
                ExternalProfileIntent.parse(link) == ExternalProfileIntent.Invalid(ExternalProfileIntent.Reason.INVALID_INSTALL_TARGET))
        }
    }

    @Test
    fun quickActionsAreExactAndUnknownActionsAreRejected() {
        LauncherAction.entries.forEach { assertEquals(it, LauncherActions.parseAction(it.name)) }
        assertEquals(null, LauncherActions.parseAction(null))
        assertEquals(null, LauncherActions.parseAction("START_EXTRA"))
        assertEquals(null, LauncherActions.parseAction(" start"))
        assertEquals(LauncherAction.START, LauncherActions.parseIntentAction("dev.aerove.lite", "dev.aerove.lite.action.START", null))
        assertEquals(null, LauncherActions.parseIntentAction("dev.aerove.lite", "other.action.START", null))
        assertEquals(null, LauncherActions.parseIntentAction("dev.aerove.lite", "dev.aerove.lite.action.START", "STOP"))
    }

    @Test
    fun repeatedDeliveryOfOneQuickActionRequestIsDeduplicated() {
        val request = "request-${System.nanoTime()}"
        assertTrue(LauncherActions.acceptRequest(request))
        assertEquals(false, LauncherActions.acceptRequest(request))
        assertEquals(false, LauncherActions.acceptRequest(null))
    }

    @Test
    fun eachShortcutTapGetsANewRequestAndRestoredActivityDoesNotForwardAgain() {
        val firstTap = LauncherActions.requestIdForLaunch(null)
        val secondTap = LauncherActions.requestIdForLaunch(null)
        assertTrue(firstTap != null)
        assertTrue(secondTap != null)
        assertTrue(firstTap != secondTap)
        assertEquals(null, LauncherActions.requestIdForLaunch(firstTap))
        assertTrue(LauncherActions.shouldForwardOnCreate(false))
        assertEquals(false, LauncherActions.shouldForwardOnCreate(true))
    }

    @Test
    fun quickSettingsStateReflectsEveryVpnTransition() {
        assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, tileStateFor(VpnPhase.Stopped))
        assertEquals(android.service.quicksettings.Tile.STATE_UNAVAILABLE, tileStateFor(VpnPhase.Starting))
        assertEquals(android.service.quicksettings.Tile.STATE_ACTIVE, tileStateFor(VpnPhase.Running))
        assertEquals(android.service.quicksettings.Tile.STATE_UNAVAILABLE, tileStateFor(VpnPhase.Stopping))
        assertEquals(android.service.quicksettings.Tile.STATE_INACTIVE, tileStateFor(VpnPhase.Failed))
    }
}
