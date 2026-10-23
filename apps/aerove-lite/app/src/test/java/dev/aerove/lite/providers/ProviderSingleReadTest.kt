package dev.aerove.lite.providers

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderSingleReadTest {
    private val row = """{"vehicleType":"HTTP","revision":"18446744073709551615","proxies":[{"name":"Node","type":"Vmess","history":[{"delay":42}]}]}"""
    @Test fun exactSingleGetDecodesDirectCompleteRowWithoutListFallback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, "fixture-secret-000000000000000000")).use { api ->
                val name = "../providers/rules?query=secret#fragment%2F\\名 称"
                server.enqueue(MockResponse().setBody(row))
                val value = api.read(ProviderKind.Proxy, name)
                assertEquals(name, value.name); assertEquals("18446744073709551615", value.revision)
                assertEquals("Node", value.proxies.single().name); assertEquals(42, value.proxies.single().delayMillis)
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals(listOf("providers", "proxies", name), request.requestUrl!!.pathSegments)
                assertNull(request.requestUrl!!.query); assertNull(request.requestUrl!!.fragment)
                assertTrue(request.path!!.contains("%2F")); assertTrue(request.path!!.contains("%3F"))
                assertEquals(1, server.requestCount)
                for (dot in listOf(".", "..")) {
                    try { api.read(ProviderKind.Rule, dot); fail("Dot segment must not normalize into a different endpoint") }
                    catch (_: IllegalArgumentException) { }
                }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun missingAndConflictingSingleGetKeepTypedErrorsAndNeverReadList() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, "fixture-secret-000000000000000000")).use { api ->
                for (code in listOf(404, 409, 500)) {
                    server.enqueue(MockResponse().setResponseCode(code).setBody("private-response-body"))
                    try { api.read(ProviderKind.Rule, "A / B"); fail("Expected HTTP error") }
                    catch (error: CoreApiException) {
                        assertEquals(code, error.statusCode); assertFalse(error.toString().contains("private-response-body"))
                    }
                    assertEquals("/providers/rules/A%20%2F%20B", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                }
                assertEquals(3, server.requestCount)
            }
        }
    }
}
