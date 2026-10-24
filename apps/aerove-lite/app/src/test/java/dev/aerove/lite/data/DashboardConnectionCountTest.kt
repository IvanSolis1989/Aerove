package dev.aerove.lite.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class DashboardConnectionCountTest {
    @Test fun authenticatedCountOnlyRequestReturnsZeroAndPositiveWithoutListingConnections() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            CoreApiClient(ControlEndpoint(server.port, "count-test-private-secret-00000000")).use { client ->
                server.enqueue(MockResponse().setBody("{\"count\":0}"))
                assertEquals(0, client.connectionCount())
                server.enqueue(MockResponse().setBody("{\"count\":17}"))
                assertEquals(17, client.connectionCount())
                repeat(2) {
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("GET", request.method)
                    assertEquals("/aerove/connections/count", request.path)
                    assertEquals("Bearer count-test-private-secret-00000000", request.getHeader("Authorization"))
                    assertEquals(0L, request.bodySize)
                }
            }
        }
    }

    @Test fun malformedCountsAndAuthenticationFailuresNeverBecomeZero() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            CoreApiClient(ControlEndpoint(server.port, "count-test-private-secret-00000000")).use { client ->
                for (body in listOf("{\"count\":-1}", "{\"count\":1.5}", "{\"count\":\"7\"}",
                    "{\"count\":2147483648}", "{\"count\":0,\"connections\":[]}")) {
                    server.enqueue(MockResponse().setBody(body))
                    try { client.connectionCount(); fail("Accepted invalid count") } catch (_: IOException) { }
                }
                server.enqueue(MockResponse().setResponseCode(401))
                try { client.connectionCount(); fail("Ignored authorization failure") }
                catch (failure: CoreApiException) { assertEquals(401, failure.statusCode) }
                server.enqueue(MockResponse().setResponseCode(410).setBody("{\"error\":\"cancelled\"}"))
                try { client.connectionCount(); fail("Ignored cancelled owner") }
                catch (failure: CoreApiException) { assertEquals(410, failure.statusCode) }
            }
        }
    }
}
