package dev.aerove.lite.platformqa

import java.net.InetSocketAddress
import java.net.ServerSocket

/** Match the production listener's reuse flag so TIME_WAIT is not a live-listener failure. */
internal fun probeReleasedPort(port: Int): Boolean {
    require(port in 1024..65535)
    return ServerSocket().use { probe ->
        probe.reuseAddress = true
        probe.bind(InetSocketAddress("127.0.0.1", port))
        true
    }
}
