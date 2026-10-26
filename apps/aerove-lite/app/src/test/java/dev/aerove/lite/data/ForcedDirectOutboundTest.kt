package dev.aerove.lite.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ForcedDirectOutboundTest {
    private fun response(direct: String = "true", epoch: String = "\"18446744073709551615\"") =
        """{"ip":"203.0.113.8","countryCode":"US","organization":null,"kind":"direct","chains":["DIRECT"],"direct":$direct,"coreEpoch":$epoch,"routeVersion":"18446744073709551615","hits":[{"host":"origin.example:443","proxy":"DIRECT","exit":"direct-origin"}]}"""

    @Test fun parkedProbeRequestsNativeDirectAndConsumesActualRouteReceipt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response()))
            server.start()
            CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef")).use { client ->
                val actual = client.outboundProbe(forceDirect = true)
                assertEquals(listOf("DIRECT"), actual.chains)
                assertEquals("direct", actual.identity.kind)
                assertEquals(ULong.MAX_VALUE,actual.coreEpoch)
                assertEquals(ULong.MAX_VALUE,actual.routeVersion)
                assertEquals(listOf(RouteHit("origin.example:443","DIRECT","direct-origin")),actual.hits)
            }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/aerove/outbound?forceDirect=true", request.path)
            assertEquals("Bearer 0123456789abcdef0123456789abcdef", request.getHeader("Authorization"))
        }
    }

    @Test fun directProbeRejectsProxyMissingOrMalformedReceiptsAndPropagatesNativeEpochConflict() = runBlocking<Unit> {
        MockWebServer().use { server ->
            listOf(response("false"), response(epoch = "\"01\""), response(epoch = "\"18446744073709551616\""),
                """{"ip":"203.0.113.8","countryCode":"US","organization":null,"kind":"direct","chains":["DIRECT"]}""").forEach {
                server.enqueue(MockResponse().setBody(it))
            }
            server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"outbound_changed\"}"))
            server.start()
            CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef")).use { client ->
                repeat(5) {
                    try { client.outboundProbe(forceDirect = true); fail("Unconfirmed direct route must not be published") }
                    catch (_: IOException) { }
                }
            }
        }
    }

    @Test fun resetConnectionsUsesTheAuthenticatedStrictEmptyBodyResolverTransportEndpoint() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef")).use { it.resetConnections() }
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/aerove/reset-connections", request.path)
            assertEquals(0L, request.bodySize)
            assertEquals("Bearer 0123456789abcdef0123456789abcdef", request.getHeader("Authorization"))
        }
    }
}
