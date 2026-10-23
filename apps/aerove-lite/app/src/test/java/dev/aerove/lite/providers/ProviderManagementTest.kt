package dev.aerove.lite.providers

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderManagementTest {
    private val secret = "fixture-only-secret-00000000000000"
    private val proxy = """{"providers":{"internal":{"vehicleType":"Compatible"},"A / B":{"vehicleType":"File","updatedAt":"2026-10-23T00:00:00Z","proxies":[{"name":"Node","type":"Vmess","history":[{"delay":42}]}]}}}"""
    private val rules = """{"providers":{"rules":{"vehicleType":"File","behavior":"domain","format":"yaml"}}}"""

    @Test fun actualHttpUpdateAndHealthcheckReadBackSavedCoreProvidersWithAuth() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                server.enqueue(MockResponse().setBody(proxy)); server.enqueue(MockResponse().setBody(rules))
                val values = api.snapshot()
                assertEquals(2, values.size)
                assertEquals(42, values.first().proxies.single().delayMillis)
                server.enqueue(MockResponse().setResponseCode(202).setBody("{}")); server.enqueue(MockResponse().setBody(org.json.JSONObject(proxy).getJSONObject("providers").getJSONObject("A / B").toString()))
                assertEquals("Node", api.update(ProviderKind.Proxy, "A / B").proxies.single().name)
                server.enqueue(MockResponse().setResponseCode(202).setBody("{}")); server.enqueue(MockResponse().setBody(org.json.JSONObject(proxy).getJSONObject("providers").getJSONObject("A / B").toString()))
                assertEquals(42, api.healthcheck("A / B").proxies.single().delayMillis)
                val requests = (0 until 6).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
                assertTrue(requests.all { it.getHeader("Authorization") == "Bearer $secret" })
                assertEquals("PUT", requests[2].method)
                assertEquals("/providers/proxies/A%20%2F%20B", requests[2].path)
                assertEquals("/providers/proxies/A%20%2F%20B/healthcheck", requests[4].path)
            }
        }
    }

    @Test fun cancellationAndCloseAreTerminalAndHttpErrorsDoNotExposeBodyOrSecret() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val api = ProviderManagementRepository(ControlEndpoint(server.port, secret))
            server.enqueue(MockResponse().setResponseCode(500).setBody("private $secret body"))
            try { api.read(ProviderKind.Rule, "rules"); fail() }
            catch (failure: CoreApiException) { assertFalse(failure.toString().contains(secret)); assertEquals(500, failure.statusCode) }
            server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody(proxy))
            val pending = async(Dispatchers.Default) { api.read(ProviderKind.Proxy, "A / B") }
            server.takeRequest(2, TimeUnit.SECONDS)
            server.takeRequest(2, TimeUnit.SECONDS)
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            api.close()
            try { api.snapshot(); fail() } catch (_: IllegalStateException) { }
        }
    }
}
