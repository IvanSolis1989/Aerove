package dev.aerove.lite.networksettings

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.CoreApiException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NetworkApiClientTest {
    private val secret = "network-private-controller-secret-123456"
    private val password = "sensitive-fixture-password"
    private val settings = NetworkSettings(ports = UserProxyPorts(14000, 14001, 0), allowLan = true,
        authentication = ProxyAuthentication(true, "fixture-user", password))
    private val high = 18_446_744_073_709_551_614uL
    private fun route(epoch: ULong) = MockResponse().setBody("""{"core-epoch":"$epoch","picks-version":"0","picks":{}}""")
    private fun receipt(id: String, epoch: ULong) = JSONObject().put("requestId", id).put("coreEpoch", epoch.toString())
    private fun readback(epoch: ULong): JSONObject = JSONObject().put("coreEpoch", epoch.toString())
        .put("network", JSONObject().put("mixed-port", 14000).put("port", 14001).put("socks-port", 0)
            .put("allow-lan", true).put("bind-address", "::").put("authEnabled", true))
        .put("activePorts", JSONArray(listOf(14000, 14001)))

    @Test fun exactUnsignedEpochAndAppliedManagerStateRoundTripOverHttp() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.method + " " + request.path
                    assertEquals("Bearer $secret", request.getHeader("Authorization"))
                    return when {
                        request.path == "/aerove/route" -> route(high)
                        request.method == "POST" -> {
                            val body = JSONObject(request.body.readUtf8())
                            assertTrue(body.get("expectedCoreEpoch") is String)
                            assertEquals(high.toString(), body.getString("expectedCoreEpoch"))
                            assertEquals(body.getString("requestId"), UUID.fromString(body.getString("requestId")).toString())
                            val network = body.getJSONObject("network")
                            assertFalse(network.has("socks-port"))
                            assertEquals("::", network.getString("bind-address"))
                            assertEquals("fixture-user:$password", network.getJSONArray("authentication").getString(0))
                            MockResponse().setBody(receipt(body.getString("requestId"), ULong.MAX_VALUE).toString())
                        }
                        else -> MockResponse().setBody(readback(ULong.MAX_VALUE).toString())
                    }
                }
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { it.applyNetwork(settings, true) }
            assertEquals(listOf("GET /aerove/route", "POST /aerove/network", "GET /aerove/network"), paths)
        }
    }

    @Test fun staleConflictRefreshesEpochAndUuidExactlyOnce() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var routes = 0; var posts = 0
            val ids = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/aerove/route" -> route(if (++routes == 1) 9_223_372_036_854_775_808uL else high)
                    request.method == "POST" -> {
                        val body = JSONObject(request.body.readUtf8())
                        ids += body.getString("requestId")
                        if (++posts == 1) MockResponse().setResponseCode(409).setBody("""{"error":"network_epoch_changed"}""")
                        else {
                            assertEquals(high.toString(), body.getString("expectedCoreEpoch"))
                            MockResponse().setBody(receipt(ids.last(), ULong.MAX_VALUE).toString())
                        }
                    }
                    else -> MockResponse().setBody(readback(ULong.MAX_VALUE).toString())
                }
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { it.applyNetwork(settings, true) }
            assertEquals(2, routes); assertEquals(2, posts); assertNotEquals(ids[0], ids[1])
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun secondConflictFailsTypedWithoutEchoingErrorBodyOrCredentials() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path == "/aerove/route") route(high)
                else MockResponse().setResponseCode(409).setBody("""{"error":"$password $secret"}""")
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                val error = runCatching { api.applyNetwork(settings, true) }.exceptionOrNull()
                assertTrue(error is CoreApiException)
                assertEquals(409, (error as CoreApiException).statusCode)
                assertFalse(error.stackTraceToString().contains(password))
                assertFalse(error.stackTraceToString().contains(secret))
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun mismatchedReceiptAndEchoedRequestAreNotAcceptedAsReadback() = runBlocking<Unit> {
        for (wrongReceipt in listOf(true, false)) MockWebServer().use { server ->
            var echo = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when {
                    request.path == "/aerove/route" -> route(high)
                    request.method == "POST" -> {
                        echo = request.body.readUtf8()
                        val id = JSONObject(echo).getString("requestId")
                        MockResponse().setBody(receipt(if (wrongReceipt) UUID.randomUUID().toString() else id, ULong.MAX_VALUE).toString())
                    }
                    else -> MockResponse().setBody(echo)
                }
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                val error = runCatching { api.applyNetwork(settings, true) }.exceptionOrNull()
                assertTrue(error is IOException)
                assertFalse(error!!.stackTraceToString().contains(password))
            }
            assertEquals(if (wrongReceipt) 2 else 3, server.requestCount)
        }
    }

    @Test fun readbackRequiresCanonicalEpochExactPortsLanBindAndAuthenticationState() {
        val plan = planNetwork(settings, 18000, true)
        verifyNetworkReadback(readback(ULong.MAX_VALUE).toString(), ULong.MAX_VALUE, plan)
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("coreEpoch", high.toString()) }, { it.put("coreEpoch", "018446744073709551615") },
            { it.put("coreEpoch", "18446744073709551616") }, { it.put("coreEpoch", ULong.MAX_VALUE.toDouble()) },
            { it.put("activePorts", JSONArray(listOf(14000))) }, { it.put("activePorts", JSONArray(listOf(14000, 14000))) },
            { it.getJSONObject("network").put("authEnabled", false) }, { it.getJSONObject("network").put("allow-lan", false) },
            { it.getJSONObject("network").put("bind-address", "0.0.0.0") }, { it.getJSONObject("network").put("port", 0) },
            { it.getJSONObject("network").put("socks-port", 14002) }, { it.getJSONObject("network").put("mixed-port", "14000") },
            { it.put("authentication", password) },
        )
        corruptions.forEach { corrupt ->
            val value = readback(ULong.MAX_VALUE).also(corrupt)
            val error = assertThrows(IOException::class.java) { verifyNetworkReadback(value.toString(), ULong.MAX_VALUE, plan) }
            assertFalse(error.stackTraceToString().contains(password))
        }
        val id = UUID.randomUUID().toString()
        for (value in listOf("0", "+1", "01", high.toString(), "18446744073709551616")) {
            assertThrows(IOException::class.java) { decodeNetworkReceipt(JSONObject().put("requestId", id).put("coreEpoch", value).toString(), id, high) }
        }
    }

    @Test fun privateControllerPortCollisionFailsBeforeAnyHttpCall() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("No call allowed") }.build()
        CoreApiClient(ControlEndpoint(14000, secret), client).use { api ->
            assertTrue(runCatching { api.applyNetwork(settings, true) }.exceptionOrNull() is NetworkSettingsException)
        }
        assertEquals(0, calls.get())
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun cancellationBeforeLateReceiptOrConflictStartsNoReadbackOrRetry() = runBlocking<Unit> {
        for (status in listOf(200, 409)) MockWebServer().use { server ->
            val received = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/aerove/route") route(high)
                else MockResponse().setResponseCode(status).setBody(receipt(JSONObject(request.body.readUtf8()).getString("requestId"), ULong.MAX_VALUE).toString())
            }
            val transport = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val reply = chain.proceed(chain.request())
                if (chain.request().method == "POST") { received.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                reply
            }.build()
            CoreApiClient(ControlEndpoint(server.port, secret), transport).use { api ->
                try {
                    val applying = async(Dispatchers.Default) { api.applyNetwork(settings, true) }
                    assertTrue(received.await(2, TimeUnit.SECONDS))
                    applying.cancel(); release.countDown()
                    withTimeout(2000) { applying.cancelAndJoin() }
                    assertTrue(applying.isCancelled)
                    assertEquals(2, server.requestCount)
                } finally { release.countDown() }
            }
            transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll()
        }
    }
}
