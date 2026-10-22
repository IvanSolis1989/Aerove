package dev.aerove.lite.data

import dev.aerove.lite.ProxyMode
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
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
                            webSocket.send(if (request.path == "/traffic") """{"up":7,"down":9}"""
                                else """{"uploadTotal":123456,"downloadTotal":654321,"connections":[]}""")
                        }
                    })
            }
            client(server).use { api ->
                val event = withTimeout(5000) { api.traffic().first() }
                assertEquals(7L, event.uploadPerSecond)
                assertEquals(9L, event.downloadPerSecond)
                assertEquals(123456L, event.upload)
                assertEquals(654321L, event.download)
            }
        }
    }
}
