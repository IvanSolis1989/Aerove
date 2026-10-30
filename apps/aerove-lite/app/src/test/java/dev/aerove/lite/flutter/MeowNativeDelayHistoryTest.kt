package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.CoreApiException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowNativeDelayHistoryTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun client(server: MockWebServer) = CoreApiClient(ControlEndpoint(server.port, secret))
    private fun row(url: String = "https://a.test/probe", value: Any = 42) = JSONObject()
        .put("name", "Node").put("url", url).put("value", value)
    private fun history(epoch: String = "7", route: String = "10", revision: String = "1",
        rows: List<JSONObject> = listOf(row())) = JSONObject().put("complete", true).put("core-epoch", epoch)
        .put("route-version", route).put("history-revision", revision).put("delays", JSONArray(rows))
    private suspend fun invalid(reader: MeowNativeDelayHistory): MeowQueryException {
        try { reader.read(); fail("Expected invalid native response") } catch (failure: MeowQueryException) {
            assertEquals("invalid_core_response", failure.code); return failure
        }
        error("Unreachable")
    }

    @Test fun exactHttpRequestPreservesNativeStampsAllUrlRowsAndFailureSentinel() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(history(ULong.MAX_VALUE.toString(), "0", "0",
                listOf(row(), row("https://b.test/probe", -2))).toString()))
            client(server).use { api ->
                val value = MeowNativeDelayHistory(api, "actual-owner", 91, ULong.MAX_VALUE).read()
                assertEquals("actual-owner", value.owner); assertEquals(91L, value.generation)
                assertEquals(ULong.MAX_VALUE, value.coreEpoch); assertEquals(0uL, value.routeVersion); assertEquals(0uL, value.historyRevision)
                assertEquals(2, value.delays.size)
                assertEquals(listOf(42, -2), value.delays.map { it["value"] })
                val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/aerove/delay-history?coreEpoch=${ULong.MAX_VALUE}", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
            }
        }
    }

    @Test fun everyReadUsesEndpointAndDoesNotReuseCacheOrProxyHistory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(history(route = "10", revision = "1", rows = listOf(row(value = 12))).toString()))
            server.enqueue(MockResponse().setBody(history(route = "15", revision = "9", rows = listOf(row(value = 57))).toString()))
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                assertEquals(12, reader.read().delays.single()["value"])
                val next = reader.read()
                assertEquals(15uL, next.routeVersion); assertEquals(9uL, next.historyRevision)
                assertEquals(57, next.delays.single()["value"])
                repeat(2) { assertEquals("/aerove/delay-history?coreEpoch=7", server.takeRequest().path) }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun incompleteMissingUnknownAndNoncanonicalStampsAreRejected() = runBlocking<Unit> {
        val bad = listOf(history().put("complete", false), history().put("complete", "true"),
            history().also { it.remove("complete") }, history().put("extra", true),
            history().put("core-epoch", 7), history(epoch = "0"), history(epoch = "07"),
            history(epoch = "18446744073709551616"), history(route = "-1"), history(route = "01"),
            history().put("route-version", 1.0), history(revision = "-1"), history(revision = "00"))
        MockWebServer().use { server ->
            bad.forEach { server.enqueue(MockResponse().setBody(it.toString())) }
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                bad.forEach { invalid(reader) }
                assertEquals(bad.size, server.requestCount)
            }
        }
    }

    @Test fun duplicateOrInvalidRowsFailEntireSnapshotWithoutDroppingAnyRow() = runBlocking<Unit> {
        val badRows = listOf(listOf(row(), row()), listOf(row(), row("https://b.test/", 0)),
            listOf(row(value = -1)), listOf(row(value = 65536)), listOf(row(value = "42")), listOf(row(value = 1.5)),
            listOf(row().put("name", "")), listOf(row().put("url", "ftp://a.test/")),
            listOf(row().put("url", "https://user:password@a.test/")), listOf(row().put("extra", true)))
        MockWebServer().use { server ->
            badRows.forEach { server.enqueue(MockResponse().setBody(history(rows = it).toString())) }
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                badRows.forEach { invalid(reader) }
                assertEquals(badRows.size, server.requestCount)
            }
        }
    }

    @Test fun actualEpochMismatchIsStaleAndHttpFailureDoesNotFallback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(history(epoch = "8").toString()))
            server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"delayHistoryIncomplete"}"""))
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                try { reader.read(); fail("Expected stale epoch") } catch (failure: MeowQueryException) {
                    assertEquals("stale_session", failure.code); assertEquals("8", failure.details["actual"])
                }
                try { reader.read(); fail("Expected HTTP rejection") } catch (failure: CoreApiException) {
                    assertEquals(403, failure.statusCode)
                }
                try { reader.read(); fail("Expected incomplete-history rejection") } catch (failure: CoreApiException) {
                    assertEquals(409, failure.statusCode)
                }
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun malformedAndTrailingJsonAreRejectedButCompleteEmptyHistoryIsValid() = runBlocking<Unit> {
        MockWebServer().use { server ->
            listOf("{", "[]", history().toString() + " {}", history().put("delays", JSONArray().put(JSONObject.NULL)).toString())
                .forEach { server.enqueue(MockResponse().setBody(it)) }
            server.enqueue(MockResponse().setBody(history(rows = emptyList()).toString()))
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                repeat(4) { invalid(reader) }
                assertTrue(reader.read().delays.isEmpty())
                assertEquals(5, server.requestCount)
            }
        }
    }

    @Test fun cancellationWithdrawsInFlightRequestAndReaderCanReadNextResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setBody(history(revision = "2").toString()))
            client(server).use { api ->
                val reader = MeowNativeDelayHistory(api, "owner", 5, 7uL)
                val pending = async { reader.read() }
                withTimeout(2000) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                } }
                withTimeout(2000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                assertEquals(2uL, withTimeout(3000) { reader.read() }.historyRevision)
                assertEquals(2, server.requestCount)
            }
        }
    }
}
