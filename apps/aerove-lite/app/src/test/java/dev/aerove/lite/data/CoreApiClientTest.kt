package dev.aerove.lite.data

import dev.aerove.lite.ProxyMode
import dev.aerove.lite.telemetry.CoreTelemetry
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoreApiClientTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    private fun client(server: MockWebServer) = CoreApiClient(ControlEndpoint(server.port, secret))

    private fun MockWebServer.next(): RecordedRequest =
        takeRequest(3, TimeUnit.SECONDS) ?: error("No real HTTP request received")

    @Test fun groupsAndHistoryAreDecodedFromRealHttpResponse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"proxies":{"Pick":{"type":"Selector","now":"Node","all":["Node"]},"Node":{"type":"Shadowsocks","history":[{"delay":28}]}}}"""))
            client(server).use { api ->
                val groups = api.groups()
                assertEquals("Pick", groups.single().name)
                assertEquals("Node", groups.single().selected)
                assertEquals(28, groups.single().nodes.single().delayMillis)
                val request = server.next()
                assertEquals("/proxies", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals("127.0.0.1:${server.port}", request.getHeader("Host"))
            }
        }
    }

    @Test fun outboundIdentityUsesAuthenticatedCoreRouteAndPreservesUnknownValues() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ip":"1.1.1.1","countryCode":"AU","organization":null,"kind":"unknown"}"""))
            client(server).use { api ->
                val identity = api.outboundIdentity()
                assertEquals("1.1.1.1", identity.address)
                assertEquals("AU", identity.countryCode)
                assertNull(identity.organization)
                assertEquals("unknown", identity.kind)
                val request = server.next()
                assertEquals("/aerove/outbound", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
            }
        }
    }

    @Test fun failedOrMalformedOutboundQueryCannotBecomeFakePublicIdentity() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"outbound_changed"}"""))
            server.enqueue(MockResponse().setBody("""{"ip":"example.com","countryCode":"US","organization":null,"kind":"unknown"}"""))
            client(server).use { api ->
                val failure = assertThrows(CoreApiException::class.java) { runBlocking { api.outboundIdentity() } }
                assertEquals(409, failure.statusCode)
                assertThrows(java.io.IOException::class.java) { runBlocking { api.outboundIdentity() } }
            }
        }
    }

    @Test fun outboundProbeDecodesTheWinningActualChainWithoutAnotherRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ip":"1.1.1.1","countryCode":"AU","organization":null,"kind":"unknown","chains":["Node / HK","Auto","Pick"],"coreEpoch":"7","routeVersion":"12","hits":[{"host":"origin.example:443","exit":"actual-exit"}]}"""))
            client(server).use { api ->
                val answer = api.outboundProbe()
                assertEquals("1.1.1.1", answer.identity.address)
                assertEquals(listOf("Node / HK", "Auto", "Pick"), answer.chains)
                val request = server.next()
                assertEquals("/aerove/outbound", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun missingOrMalformedChainCannotBecomeARouteStampedCacheEntry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val fields = listOf("", ",\"chains\":[]", ",\"chains\":[5]", ",\"chains\":[\"\"]", ",\"chains\":null")
            fields.forEach { suffix ->
                server.enqueue(MockResponse().setBody("""{"ip":"1.1.1.1","countryCode":"AU","organization":null,"kind":"unknown"$suffix}"""))
            }
            client(server).use { api ->
                fields.forEach { _ -> assertThrows(IOException::class.java) { runBlocking { api.outboundProbe() } } }
                assertEquals(fields.size, server.requestCount)
            }
        }
    }

    @Test fun modePatchAndProxySelectionUseEncodedSegmentsAndJsonBody() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"mode":"rule"}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
            client(server).use { api ->
                assertEquals(ProxyMode.Rule, api.mode())
                api.setMode(ProxyMode.Global)
                api.select("HK / Tokyo", "A&B")
                assertEquals("/configs", server.next().path)
                val patch = server.next()
                assertEquals("PATCH", patch.method)
                assertEquals("global", JSONObject(patch.body.readUtf8()).getString("mode"))
                val selection = server.next()
                assertEquals("/proxies/HK%20%2F%20Tokyo", selection.path)
                assertEquals("PUT", selection.method)
                assertEquals("A&B", JSONObject(selection.body.readUtf8()).getString("name"))
            }
        }
    }

    @Test fun delayUsesMillisecondsAndPreservesProbeUrlQuery() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"delay":57}"""))
            client(server).use { api ->
                val url = "https://example.com/check?x=1&y=2"
                assertEquals(57, api.delay("HK/1", url, 3500))
                val request = server.next()
                assertEquals("/proxies/HK%2F1/delay", request.requestUrl!!.encodedPath)
                assertEquals("3500", request.requestUrl!!.queryParameter("timeout"))
                assertEquals(url, request.requestUrl!!.queryParameter("url"))
            }
        }
    }

    @Test fun connectionSnapshotAndDeletesUseCoreApi() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"uploadTotal":41,"downloadTotal":82,"connections":[{"id":"id-1","metadata":{"host":"example.com","network":"tcp","destinationPort":"443"},"rule":"Match","chains":["Node"],"upload":12,"download":34}]}"""))
            server.enqueue(MockResponse().setBody("connection closed"))
            server.enqueue(MockResponse().setBody("all connections closed"))
            client(server).use { api ->
                val connection = api.connections().single()
                assertEquals("example.com:443", connection.destination)
                assertEquals(12L, connection.upload)
                assertEquals(listOf("Node"), connection.chains)
                api.closeConnection("id-1")
                api.closeAllConnections()
                assertEquals("/connections", server.next().path)
                val one = server.next()
                assertEquals("DELETE", one.method)
                assertEquals("/connections/id-1", one.path)
                val all = server.next()
                assertEquals("DELETE", all.method)
                assertEquals("/connections", all.path)
            }
        }
    }

    @Test fun authenticationFailureIsNotFakeEmptyDataAndDoesNotLeakSecret() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("private diagnostic"))
            client(server).use { api ->
                val failure = assertThrows(CoreApiException::class.java) { runBlocking { api.groups() } }
                assertEquals(401, failure.statusCode)
                assertFalse(failure.message!!.contains(secret))
                assertFalse(ControlEndpoint(server.port, secret).toString().contains(secret))
            }
        }
    }

    @Test fun redirectsDoNotForwardLoopbackAuthorization() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
            client(server).use { api ->
                val failure = assertThrows(CoreApiException::class.java) { runBlocking { api.mode() } }
                assertEquals(302, failure.statusCode)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun logStreamDecodesCoreEventsAndCancellationClosesSubscription() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"info","payload":"core is running"}""")
                }
            }))
            client(server).use { api ->
                val event = withTimeout(5000) { api.logs().first() }
                assertEquals("info", event.level)
                assertEquals("core is running", event.message)
                assertEquals("/logs", server.next().path)
            }
        }
    }

    @Test fun trafficUsesCoreRatesAndCoreTotalsRatherThanIntegratingLocalCounters() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"type":"groupsChanged","revision":0,"routeVersion":0}""")
                            webSocket.send("""{"type":"traffic","up":7,"down":9,"uploadTotal":123456,"downloadTotal":654321}""")
                        }
                    })
            }
            client(server).use { api ->
                val event = withTimeout(5000) { api.traffic().first() }
                assertEquals(7L, event.uploadPerSecond)
                assertEquals(9L, event.downloadPerSecond)
                assertEquals(123456L, event.upload)
                assertEquals(654321L, event.download)
                val request = server.next()
                assertEquals("/aerove/telemetry", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun trafficAndGroupConsumersShareOneSocketAndDoNotFetchConnections() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val opened = CompletableDeferred<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(webSocket)
                    webSocket.send("""{"type":"traffic","up":1,"down":2,"uploadTotal":3,"downloadTotal":4}""")
                }
            }))
            client(server).use { api ->
                val trafficReady = CompletableDeferred<Unit>()
                val traffic = async(start = CoroutineStart.UNDISPATCHED) {
                    api.traffic().onEach { trafficReady.complete(Unit) }.take(2).toList()
                }
                val socket = withTimeout(5000) { opened.await() }
                withTimeout(5000) { trafficReady.await() }
                val groupsReady = CompletableDeferred<Unit>()
                val groups = async(start = CoroutineStart.UNDISPATCHED) {
                    api.telemetry().onEach { groupsReady.complete(Unit) }
                        .filterIsInstance<CoreTelemetry.GroupsChanged>().first()
                }
                withTimeout(5000) { groupsReady.await() }
                socket.send("""{"type":"groupsChanged","revision":18446744073709551615,"routeVersion":18446744073709551615}""")
                socket.send("""{"type":"traffic","up":5,"down":6,"uploadTotal":7,"downloadTotal":8}""")
                assertEquals(ULong.MAX_VALUE, withTimeout(5000) { groups.await() }.revision)
                assertEquals(7L, withTimeout(5000) { traffic.await() }.last().upload)
                assertEquals("/aerove/telemetry", server.next().path)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun invalidTelemetryIsReportedInsteadOfFallingBackToConnectionSnapshots() = runBlocking<Unit> {
        val frames = listOf(
            """{"type":"traffic","up":-1,"down":0,"uploadTotal":0,"downloadTotal":0}""",
            """{"type":"traffic","up":1.5,"down":0,"uploadTotal":0,"downloadTotal":0}""",
            """{"type":"traffic","up":0,"down":0,"uploadTotal":"5","downloadTotal":0}""",
            """{"type":"traffic","up":0,"down":0,"uploadTotal":9223372036854775808,"downloadTotal":0}""",
            """{"type":"traffic","up":0,"down":0}""",
            """{"type":"groupsChanged","revision":-1}""",
            """{"type":"groupsChanged","revision":1.5}""",
            """{"type":"groupsChanged","revision":"1"}""",
            """{"type":"groupsChanged","revision":-1,"other":{"revision":4}}""",
            """{"type":"groupsChanged","revision":18446744073709551616}""",
            """{"type":"unknown"}""",
        )
        frames.forEach { frame ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(frame) }
                }))
                client(server).use { api ->
                    assertThrows(IOException::class.java) { runBlocking { withTimeout(5000) { api.telemetry().first() } } }
                    assertEquals("/aerove/telemetry", server.next().path)
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }

    @Test fun closingClientTerminatesAnIdleSharedTelemetrySubscription() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val opened = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            }))
            val api = client(server)
            try {
                val waiting = async { runCatching { api.telemetry().first() } }
                withTimeout(5000) { opened.await() }
                api.close()
                assertTrue(withTimeout(5000) { waiting.await() }.exceptionOrNull() is IOException)
            } finally { api.close() }
        }
    }
}
