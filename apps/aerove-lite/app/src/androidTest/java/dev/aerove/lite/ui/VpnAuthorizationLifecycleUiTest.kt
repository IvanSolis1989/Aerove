package dev.aerove.lite.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import dev.aerove.lite.LiteController
import dev.aerove.lite.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VpnAuthorizationLifecycleUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun actualActivityRecreationRetainsOneSettingsAuthorizationInTheExistingController() {
        lateinit var retained: LiteController
        var token = 0L
        compose.activityRule.scenario.onActivity { activity ->
            retained = ViewModelProvider(activity)[LiteController::class.java]
            val original = retained.state.value.settings
            val wanted = original.copy(backgroundPolicy = original.backgroundPolicy.copy(autoRun = true), autoStart = true)
            token = retained.beginStartRequest()
            retained.offerVpnAuthorization(token, wanted)
            assertEquals(wanted, retained.nextVpnAuthorization()!!.settings)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { activity ->
            val actual = ViewModelProvider(activity)[LiteController::class.java]
            assertSame(retained, actual)
            assertNull("Recreation must not launch a second permission request", actual.nextVpnAuthorization())
            val receipt = actual.completeVpnAuthorization(true)!!
            assertEquals(token, receipt.request.token)
            assertTrue(receipt.request.settings!!.backgroundPolicy.autoRun)
            assertNull("Receipt is consumed exactly once", actual.completeVpnAuthorization(true))
        }
    }
}
