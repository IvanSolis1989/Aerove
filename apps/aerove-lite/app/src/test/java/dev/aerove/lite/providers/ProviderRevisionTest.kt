package dev.aerove.lite.providers

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderRevisionTest {
    private fun row(revision: String?) = """{"vehicleType":"HTTP","updatedAt":"2026-10-23T00:00:00Z"${revision?.let { ",\"revision\":$it" }.orEmpty()}}"""
    private fun body(revision: String?) = """{"providers":{"Exact / 名称":${row(revision)}}}"""
    private fun client(server: MockWebServer) = ProviderManagementRepository(ControlEndpoint(server.port, "fixture-secret-000000000000000000"))

    @Test fun singleReadRetainsFullUnsignedRevisionAndOlderNullableRows() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { api ->
                for ((wire, expected) in listOf(null to null, "null" to null, "\"1\"" to "1",
                    "\"9223372036854775808\"" to "9223372036854775808",
                    "\"18446744073709551615\"" to "18446744073709551615")) {
                    server.enqueue(MockResponse().setBody(row(wire)))
                    val value = api.read(ProviderKind.Proxy, "Exact / 名称")
                    assertEquals(expected, value.revision); assertEquals("Exact / 名称", value.name)
                }
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun listReadRetainsIndependentKindRevisionWithTwoExistingGetsOnly() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { api ->
                server.enqueue(MockResponse().setBody(body("\"18446744073709551615\"")))
                server.enqueue(MockResponse().setBody(body("\"19\"")))
                val rows = api.snapshot()
                assertEquals(listOf(ProviderKind.Proxy, ProviderKind.Rule), rows.map { it.kind })
                assertEquals(listOf("18446744073709551615", "19"), rows.map { it.revision })
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun malformedPresentRevisionFailsWithoutCoercionOrRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { api ->
                val invalid = listOf("\"0\"", "\"00\"", "\"01\"", "\"-1\"", "\"+1\"", "\" 1\"", "\"1 \"",
                    "\"1.0\"", "\"1e2\"", "\"\"", "\"null\"", "\"18446744073709551616\"", "\"999999999999999999999999999999999999\"",
                    "1", "1.0", "18446744073709551615", "true", "{}", "[]", "\"１\"")
                invalid.forEach { value ->
                    server.enqueue(MockResponse().setBody(row(value)))
                    try { api.read(ProviderKind.Rule, "Exact / 名称"); fail("Accepted malformed revision") }
                    catch (error: IOException) { assertEquals("Invalid provider revision", error.message) }
                }
                assertEquals(invalid.size, server.requestCount)
            }
        }
    }

    @Test fun getConflictRemainsExistingTypedHttpErrorWithoutRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); client(server).use { api ->
                server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"provider_changed"}"""))
                try { api.read(ProviderKind.Proxy, "Exact / 名称"); fail("Expected conflict") }
                catch (error: CoreApiException) { assertEquals(409, error.statusCode) }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
