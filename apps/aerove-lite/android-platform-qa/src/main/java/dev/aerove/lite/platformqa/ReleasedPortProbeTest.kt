package dev.aerove.lite.platformqa

import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Real socket regressions shared by host validation and the current Android platform runner. */
class ReleasedPortProbeTest {
    @Test fun releasedListenerTimeWaitAllowsProductionCompatibleProbe() {
        val port = listener("127.0.0.1").use { server ->
            Socket().use { client ->
                client.soTimeout = 5_000
                client.connect(InetSocketAddress("127.0.0.1", server.localPort), 5_000)
                server.accept().use { accepted ->
                    assertTrue("Accepted socket must retain production reuse", accepted.reuseAddress)
                    // The server actively closes, leaving its port in TIME_WAIT after the client closes.
                    accepted.shutdownOutput()
                    assertEquals(-1, client.getInputStream().read())
                }
            }
            server.localPort
        }
        rejectAddressInUse {
            ServerSocket().use { oldProbe ->
                oldProbe.reuseAddress = false
                oldProbe.bind(InetSocketAddress("127.0.0.1", port))
            }
        }
        assertTrue("Closed listener with TIME_WAIT must be reusable", probeReleasedPort(port))
    }

    @Test fun productionCompatibleProbeRejectsLiveLoopbackAndWildcardListeners() {
        for (host in listOf("127.0.0.1", "0.0.0.0")) {
            listener(host).use { server ->
                rejectAddressInUse { probeReleasedPort(server.localPort) }
            }
        }
    }

    private fun listener(host: String) = ServerSocket().apply {
        try {
            reuseAddress = true
            soTimeout = 5_000
            bind(InetSocketAddress(host, 0))
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    private fun rejectAddressInUse(block: () -> Unit) {
        try {
            block()
            fail("The probe must reject this occupied address")
        } catch (_: BindException) {
            // Only a real bind conflict satisfies this negative control.
        }
    }
}
