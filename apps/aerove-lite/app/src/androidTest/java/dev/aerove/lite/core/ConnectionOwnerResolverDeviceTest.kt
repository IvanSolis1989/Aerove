package dev.aerove.lite.core

import android.net.VpnService
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: CI owns the running Lite Tun and a second APK holding the supplied TCP/UDP sockets. */
@RunWith(AndroidJUnit4::class)
class ConnectionOwnerResolverDeviceTest {
    @Test fun api33AttributesAnotherApplicationsActualSockets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires an isolated, live cross-UID socket fixture", arguments.getString("processOwnerFixture") == "true")
        assertEquals(33, Build.VERSION.SDK_INT)
        val context = instrumentation.targetContext
        assertNull("The CI fixture must grant and start this application's actual Tun", VpnService.prepare(context))
        val fixturePackage = requireNotNull(arguments.getString("ownerFixturePackage"))
        val fixtureUid = context.packageManager.getApplicationInfo(fixturePackage, 0).uid
        assertNotEquals("A same-UID instrumentation socket cannot prove attribution", context.applicationInfo.uid, fixtureUid)
        val expectedPackage = context.packageManager.getPackagesForUid(fixtureUid)?.firstOrNull().orEmpty()
        assertTrue(expectedPackage.isNotEmpty())
        val resolver = ConnectionOwnerResolver.forTun(context) { true }
        for ((prefix, protocol) in listOf("ownerTcp" to 6, "ownerUdp" to 17)) {
            val source = requireNotNull(ConnectionOwnerResolver.numericEndpoint(requireNotNull(arguments.getString(prefix + "Source"))))
            val target = requireNotNull(ConnectionOwnerResolver.numericEndpoint(requireNotNull(arguments.getString(prefix + "Target"))))
            val actual = resolver.resolve(protocol, source, target)
            assertNotNull("The other APK must retain its real socket until this lookup completes", actual)
            assertEquals(fixtureUid, actual!!.uid)
            assertEquals(expectedPackage, actual.process)
            assertEquals("", actual.processPath)
        }
    }
}
