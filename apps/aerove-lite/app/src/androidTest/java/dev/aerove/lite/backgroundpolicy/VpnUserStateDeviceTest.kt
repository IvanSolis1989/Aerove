package dev.aerove.lite.backgroundpolicy

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.LiteVpnService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses real Android preferences under a unique fixture name; never clears the application's intent file. */
@RunWith(AndroidJUnit4::class)
class VpnUserStateDeviceTest {
    @Test fun confirmedResetClearsOnlyOwnedKeysAndRequiresTheMaintenanceGate() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("isolatedBackgroundPolicy") == "true") {
            "Explicit isolatedBackgroundPolicy=true is required"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as LiteApplication
        check(application.vpn.value.phase == VpnPhase.Stopped && application.vpn.value.endpoint == null &&
            !LiteVpnService.wasRequested(context)) { "Never take over an existing run" }
        val name = "background-reset-fixture-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val fixture = object : ContextWrapper(context) {
            override fun getSharedPreferences(requestedName: String, mode: Int): SharedPreferences =
                if (requestedName == "vpn-intent") preferences else super.getSharedPreferences(requestedName, mode)
        }
        var primaryFailure: Throwable? = null
        try {
            check(preferences.edit().putBoolean("requested", true).putString("startupFailure", "fixture")
                .putString("future-owner", "preserve").commit())
            assertTrue(runCatching { LiteVpnService.clearUserState(fixture) }.exceptionOrNull() is IllegalStateException)
            assertTrue(preferences.contains("requested"))
            application.withMaintenance { LiteVpnService.clearUserState(fixture) }
            assertFalse(preferences.contains("requested"))
            assertFalse(preferences.contains("startupFailure"))
            assertEquals("preserve", preferences.getString("future-owner", null))
            assertFalse(LiteVpnService.wasRequested(context))
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!preferences.edit().clear().commit()) {
                val failure = IllegalStateException("Fixture preference cleanup failed")
                if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
            }
        }
    }
}
