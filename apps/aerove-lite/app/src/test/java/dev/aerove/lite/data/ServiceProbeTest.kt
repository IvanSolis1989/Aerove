package dev.aerove.lite.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServiceProbeTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val checkedAt = 1_792_700_123_456L

    private fun client(server: MockWebServer, transport: OkHttpClient = OkHttpClient()) =
        CoreApiClient(ControlEndpoint(server.port, secret), transport)

    private fun row(
        target: ServiceTarget = ServiceTarget.Google,
        status: ServiceProbeStatus = ServiceProbeStatus.Available,
        chains: List<String> = listOf("HK / Tokyo", "Auto", "Pick"),
    ): String = JSONObject().put("name", target.id).put("status", status.id).put("region", "HK")
        .put("delay", 37).put("chains", JSONArray(chains)).put("checked-at", checkedAt)
        .put("core-epoch", 7).put("route-version", 12)
        .put("hits", JSONArray().put(JSONObject().put("host", "service.example:443").put("exit", "actual-exit"))).toString()

    private fun body(vararg rows: String): String = rows.joinToString(",", "[", "]")

    @Test fun targetsLabelsIconsAndStatusesMatchTheStandardDefinitions() {
        assertEquals(listOf("google", "github", "youtube", "chatgpt", "claude", "gemini", "netflix",
            "disney-plus", "prime-video", "spotify", "tiktok", "discord", "bilibili"), ServiceTarget.entries.map { it.id })
        assertEquals(listOf("Google", "GitHub", "YouTube", "ChatGPT", "Claude", "Gemini", "Netflix",
            "Disney+", "Prime Video", "Spotify", "TikTok", "Discord", "bilibili"), ServiceTarget.entries.map { it.label })
        assertEquals(listOf("google", "github", "youtube", "openai", "claude", "gemini", "netflix",
            "disneyplus", "primevideo", "spotify", "tiktok", "discord", "bilibili"), ServiceTarget.entries.map { it.icon })
        assertEquals(listOf("available", "unavailable", "restricted", "disallowed-isp", "blocked",
            "unsupported-region", "originals-only", "coming-soon", "timeout", "failed"), ServiceProbeStatus.entries.map { it.id })
        assertNull(ServiceTarget.byId("unknown"))
        assertNull(ServiceProbeStatus.byId("unknown"))
    }

    @Test fun oneAuthenticatedPostDecodesAllThirteenTargetsAndTenStatuses() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val targets = ServiceTarget.entries.toList()
            val statuses = targets.indices.map { ServiceProbeStatus.entries[it % ServiceProbeStatus.entries.size] }
            server.enqueue(MockResponse().setBody(body(*targets.mapIndexed { index, target -> row(target, statuses[index]) }.toTypedArray())))
            client(server).use { api ->
                val result = api.serviceChecks(targets)
                assertEquals(targets, result.map { it.target })
                assertEquals(statuses, result.map { it.status })
                result.forEach {
                    assertEquals("HK", it.region)
                    assertEquals(37L, it.delayMillis)
                    assertEquals(listOf("HK / Tokyo", "Auto", "Pick"), it.chains)
                    assertEquals("HK / Tokyo", it.node)
                    assertEquals(checkedAt, it.checkedAtMillis)
                    assertEquals(7uL, it.coreEpoch)
                    assertEquals(12uL, it.routeVersion)
                }
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/aerove/service-check", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals("127.0.0.1:${server.port}", request.getHeader("Host"))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
                val sent = JSONObject(request.body.readUtf8())
                assertEquals(setOf("names", "proxy-name", "timeout"), sent.keys().asSequence().toSet())
                assertEquals(targets.map { it.id }, (0 until 13).map { sent.getJSONArray("names").getString(it) })
                assertEquals("", sent.getString("proxy-name"))
                assertEquals(10000, sent.getInt("timeout"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun explicitProxyAndSubsetOrderArePreservedInOneRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val targets = listOf(ServiceTarget.Netflix, ServiceTarget.Google)
            server.enqueue(MockResponse().setBody(body(row(targets[0]), row(targets[1]))))
            client(server).use { api ->
                assertEquals(targets, api.serviceChecks(targets, "HK / 東京 & \"pick\"", 60000).map { it.target })
                val sent = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals("HK / 東京 & \"pick\"", sent.getString("proxy-name"))
                assertEquals(listOf("netflix", "google"), (0..1).map { sent.getJSONArray("names").getString(it) })
                assertEquals(60000, sent.getInt("timeout"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun batchTimeoutCoversSixCoreBudgetsAndSharesTheInjectedDispatcher() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val readTimeouts = mutableListOf<Int>()
            val callTimeouts = mutableListOf<Long>()
            val transport = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                readTimeouts += chain.readTimeoutMillis()
                callTimeouts += TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos())
                chain.proceed(chain.request())
            }.build()
            var sameDispatcher = true
            transport.dispatcher.maxRequests = 9
            val observed = transport.newBuilder().addNetworkInterceptor { chain ->
                sameDispatcher = sameDispatcher && transport.dispatcher.runningCalls().contains(chain.call())
                chain.proceed(chain.request())
            }.build()
            repeat(2) { server.enqueue(MockResponse().setBody(body(row()))) }
            client(server, observed).use { api ->
                api.serviceChecks(listOf(ServiceTarget.Google))
                api.serviceChecks(listOf(ServiceTarget.Google), timeoutMillis = 60000)
            }
            assertEquals(listOf(65000, 365000), readTimeouts)
            assertEquals(listOf(70000L, 370000L), callTimeouts)
            assertTrue(sameDispatcher)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun invalidRequestBudgetsAndNamesNeverReachTheController() = runBlocking<Unit> {
        MockWebServer().use { server ->
            client(server).use { api ->
                assertThrows(IllegalArgumentException::class.java) { runBlocking { api.serviceChecks(emptyList()) } }
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    api.serviceChecks(listOf(ServiceTarget.Google, ServiceTarget.Google))
                } }
                listOf(-1, 0, 60001).forEach { timeout ->
                    assertThrows(IllegalArgumentException::class.java) { runBlocking {
                        api.serviceChecks(listOf(ServiceTarget.Google), timeoutMillis = timeout)
                    } }
                }
                listOf("x".repeat(4097), "界".repeat(1366), "\u0001".repeat(4096)).forEach { proxy ->
                    assertThrows(IllegalArgumentException::class.java) { runBlocking {
                        api.serviceChecks(listOf(ServiceTarget.Google), proxyName = proxy)
                    } }
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun supersededStoppedAndAuthenticationErrorsRemainTypedAndAreNotRetried() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"service_check_superseded"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"session_stopped"}"""))
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"clock_unavailable"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"$secret"}"""))
            server.enqueue(MockResponse().setResponseCode(502).setBody("invalid upstream response $secret"))
            client(server).use { api ->
                val expected = listOf(409 to ServiceCheckFailure.Superseded, 503 to ServiceCheckFailure.SessionStopped,
                    503 to ServiceCheckFailure.ClockUnavailable, 401 to null, 502 to null)
                expected.forEach { (code, reason) ->
                    val error = assertThrows(ServiceCheckException::class.java) { runBlocking {
                        api.serviceChecks(listOf(ServiceTarget.Google))
                    } }
                    assertEquals(code, error.statusCode)
                    assertEquals(reason, error.reason)
                    assertFalse(error.message!!.contains(secret))
                }
                assertEquals(expected.size, server.requestCount)
            }
        }
    }

    @Test fun redirectsNeverForwardTheBearerOrCreateAnotherProbeRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
            client(server).use { api ->
                val error = assertThrows(ServiceCheckException::class.java) { runBlocking {
                    api.serviceChecks(listOf(ServiceTarget.Google))
                } }
                assertEquals(302, error.statusCode)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun unsignedRouteStampsAreExactAboveDoubleAndLongRanges() {
        val cases = listOf("9007199254740993", "9223372036854775808", "18446744073709551615")
        cases.forEach { literal ->
            val raw = row().replace("\"core-epoch\":7", "\"core-epoch\":$literal")
                .replace("\"route-version\":12", "\"route-version\":$literal")
            val result = decodeServiceChecks(body(raw), listOf(ServiceTarget.Google)).single()
            assertEquals(literal.toULong(), result.coreEpoch)
            assertEquals(literal.toULong(), result.routeVersion)
        }
        val raw = row(chains = listOf("node \"core-epoch\":1, \"route-version\":2", "Pick"))
        val result = decodeServiceChecks(body(raw), listOf(ServiceTarget.Google)).single()
        assertEquals(7uL, result.coreEpoch)
        assertEquals(12uL, result.routeVersion)
        assertEquals("node \"core-epoch\":1, \"route-version\":2", result.node)
    }

    @Test fun zeroDelayAndEmptyFailureChainAreCoreDataWithoutInventedValues() {
        val raw = JSONObject(row(status = ServiceProbeStatus.Timeout, chains = emptyList()))
            .put("region", "").put("delay", 0).put("route-version", 0).toString()
        val result = decodeServiceChecks(body(raw), listOf(ServiceTarget.Google)).single()
        assertEquals(ServiceProbeStatus.Timeout, result.status)
        assertNull(result.region)
        assertNull(result.node)
        assertEquals(0L, result.delayMillis)
        assertEquals(0uL, result.routeVersion)
        assertEquals(checkedAt, result.checkedAtMillis)
    }

    @Test fun malformedCountersSchemasAndServiceRowsFailInsteadOfBecomingResults() = runBlocking<Unit> {
        val base = row()
        val invalid = mutableListOf("", "{}", "[]", "[null]", "[$base,]", "[$base] trailing", body(base, base))
        listOf("name", "status", "region", "delay", "chains", "checked-at", "core-epoch", "route-version", "hits").forEach { field ->
            invalid += body(JSONObject(base).also { it.remove(field) }.toString())
        }
        listOf("-1", "1.0", "1e0", "\"1\"", "true", "null", "01", "18446744073709551616").forEach { value ->
            invalid += body(base.replace("\"core-epoch\":7", "\"core-epoch\":$value"))
            invalid += body(base.replace("\"route-version\":12", "\"route-version\":$value"))
        }
        invalid += listOf(
            body(base.replace("\"core-epoch\":7", "\"core-epoch\":0")),
            body(base.replace("\"checked-at\":$checkedAt", "\"checked-at\":0")),
            body(base.replace("\"checked-at\":$checkedAt", "\"checked-at\":9223372036854775808")),
            body(base.replace("\"delay\":37", "\"delay\":-1")),
            body(base.replace("\"delay\":37", "\"delay\":9223372036854775808")),
            body(base.replace("\"status\":\"available\"", "\"status\":\"new-status\"")),
            body(base.replace("\"region\":\"HK\"", "\"region\":\"HKG\"")),
            body(base.replace("\"name\":\"google\"", "\"name\":\"unknown\"")),
            body(row(ServiceTarget.Netflix)), body(row(chains = emptyList())),
            body(JSONObject(base).put("chains", JSONArray().put(5)).toString()),
            body(row(chains = listOf(""))), body(row(chains = listOf("node\nline"))),
            body(base.replace("\"core-epoch\":7", "\"core-epoch\":7,\"core-epoch\":8")),
            body(base.replace("\"core-epoch\":7", "\"core-epoch\":7,\"extra\":{\"core-epoch\":8}")),
            body(base.replace("\"core-epoch\":7", "\"core-epoch\":7,\"extra\":8")),
            body(base.replace("\"google\"", "'google'")), body(base.replace("\"google\"", "\"goo\\xgle\"")),
        )
        MockWebServer().use { server ->
            client(server).use { api ->
                invalid.forEach { response ->
                    server.enqueue(MockResponse().setBody(response))
                    assertThrows(response, IOException::class.java) { runBlocking {
                        api.serviceChecks(listOf(ServiceTarget.Google))
                    } }
                }
                assertEquals(invalid.size, server.requestCount)
            }
        }
    }

    @Test fun mixedRouteStampsMissingRowsAndReorderedRowsAreRejected() {
        val targets = listOf(ServiceTarget.Google, ServiceTarget.GitHub)
        val first = row(targets[0])
        val second = row(targets[1])
        listOf(body(second, first), body(first), body(first, second.replace("\"route-version\":12", "\"route-version\":13")),
            body(first, second.replace("\"core-epoch\":7", "\"core-epoch\":8"))).forEach { response ->
            assertThrows(IOException::class.java) { decodeServiceChecks(response, targets) }
        }
    }

    @Test fun cancellingAnAwaitedBatchCancelsTheActualCallWithoutRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val failed = CompletableDeferred<Call>()
            val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failed.complete(call) }
            }).build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            client(server, transport).use { api ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { api.serviceChecks(listOf(ServiceTarget.Google)) }
                assertEquals("/aerove/service-check", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                withTimeout(3000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                assertTrue(withTimeout(3000) { failed.await() }.isCanceled())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun cancellingAfterHeadersAlsoClosesABlockedResponseBody() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val reading = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Call>()
            val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { reading.complete(Unit) }
                override fun callFailed(call: Call, ioe: IOException) { failed.complete(call) }
            }).build()
            server.enqueue(MockResponse().setBody("[").setHeader("Content-Length", "10000"))
            client(server, transport).use { api ->
                val pending = async(start = CoroutineStart.UNDISPATCHED) { api.serviceChecks(listOf(ServiceTarget.Google)) }
                withTimeout(3000) { reading.await() }
                withTimeout(3000) { pending.cancelAndJoin() }
                assertTrue(pending.isCancelled)
                assertTrue(withTimeout(3000) { failed.await() }.isCanceled())
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun closingTheSessionCancelsInflightWorkAndRejectsLaterCalls() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = client(server)
            try {
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.serviceChecks(listOf(ServiceTarget.Google)) } }
                assertEquals("/aerove/service-check", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                api.close()
                assertTrue(withTimeout(3000) { pending.await() }.exceptionOrNull() is IOException)
                assertThrows(IllegalStateException::class.java) { runBlocking { api.serviceChecks(listOf(ServiceTarget.Google)) } }
                assertThrows(IllegalStateException::class.java) { runBlocking { api.memorySnapshot() } }
                assertEquals(1, server.requestCount)
            } finally { api.close() }
        }
    }
}
