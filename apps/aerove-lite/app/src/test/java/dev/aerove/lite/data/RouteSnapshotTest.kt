package dev.aerove.lite.data

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouteSnapshotTest {
    @Test fun decodesExactUnsignedCountersAndEscapedPicks() {
        val route = decodeRouteSnapshot("""{"core-epoch":"18446744073709551615","picks-version":"9007199254740993","picks":{"group\\name":"node\"name","中文":"DIRECT"}}""")
        assertEquals(ULong.MAX_VALUE, route.coreEpoch)
        assertEquals(9007199254740993uL, route.picksVersion)
        assertEquals(mapOf("group\\name" to "node\"name", "中文" to "DIRECT"), route.picks)
        assertEquals(emptyMap<String, String>(), decodeRouteSnapshot(valid("{}")).picks)
    }

    @Test fun rejectsAmbiguousMalformedOrUnboundedSchema() {
        for (source in listOf(
            valid("{\"g\":\"a\",\"g\":\"b\"}"), valid("{\"\":\"a\"}"),
            valid("{\"g\":\"\"}"), valid("{\"g\":1}"), valid("{\"g\":{\"x\":{}}}"),
            valid("{\"g\":\"a\\n\"}"), valid("[]"), valid("null"),
            """{"core-epoch":1,"picks-version":0,"picks":{}}""",
            valid("{}").dropLast(1), valid("{}") + " garbage",
            valid("{}").replace("\"picks-version\":\"0\"", "\"picks-version\":\"0\",\"picks-version\":\"1\""),
            valid("{}").replace("\"picks\":{}", "\"picks\":{},\"extra\":0"),
        )) assertThrows(source, IOException::class.java) { decodeRouteSnapshot(source) }
        for (field in listOf("core-epoch", "picks-version")) {
            val original = if (field == "core-epoch") "1" else "0"
            for (literal in listOf("1", "1.0", "1e0", "-1", "true", "false", "null", "[]", "{}",
                "\"\"", "\"01\"", "\"+1\"", "\"-1\"", "\"1.0\"", "\"1e0\"", "\" 1\"", "\"1 \"", "\"18446744073709551616\"")) {
                val source = valid("{}").replace("\"$field\":\"$original\"", "\"$field\":$literal")
                assertThrows(source, IOException::class.java) { decodeRouteSnapshot(source) }
            }
        }
        assertThrows(IOException::class.java) {
            decodeRouteSnapshot(valid("{}").replace("\"core-epoch\":\"1\"", "\"core-epoch\":\"0\""))
        }
    }

    @Test fun requestsAuthenticatedRealRouteAndPropagatesConflict() = runBlocking {
        MockWebServer().use { server ->
            val secret = "0123456789abcdef0123456789abcdef"
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                server.enqueue(MockResponse().setBody(valid("{\"GLOBAL\":\"DIRECT\"}")))
                assertEquals(mapOf("GLOBAL" to "DIRECT"), api.routeSnapshot().picks)
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/aerove/route", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                server.enqueue(MockResponse().setResponseCode(409).setBody("{\"error\":\"route_changed\"}"))
                try { api.routeSnapshot(); throw AssertionError("Expected HTTP failure") }
                catch (_: IOException) { }
                assertEquals(2, server.requestCount)
            }
        }
    }

    private fun valid(picks: String) = """{"core-epoch":"1","picks-version":"0","picks":$picks}"""
}