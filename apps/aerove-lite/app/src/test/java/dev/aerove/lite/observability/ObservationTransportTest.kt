package dev.aerove.lite.observability

import dev.aerove.lite.data.*
import dev.aerove.lite.telemetry.CoreTelemetry
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ObservationTransportTest {
    private val secret = "fixture-controller-secret-0123456789"

    @Test fun observationsUseTheExistingSharedSocketAndSwitchOnlyAfterItsCloseBarrier() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val live = AtomicInteger(); val overlap = AtomicBoolean(false)
            val sockets = LinkedBlockingQueue<WebSocket>()
            val session = UUID.randomUUID().toString()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("Bearer $secret", request.getHeader("Authorization"))
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        private val released = AtomicBoolean(false)
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (live.incrementAndGet() != 1) overlap.set(true)
                            sockets.add(webSocket)
                            webSocket.send("""{"type":"traffic","up":1,"down":2,"uploadTotal":3,"downloadTotal":4}""")
                            if (request.requestUrl!!.queryParameter("observations") == "true") webSocket.send(
                                """{"type":"observations","sessionId":"$session","generation":"18446744073709551615","cursor":"0","dropped":"0","events":[]}""")
                        }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            if (released.compareAndSet(false, true)) live.decrementAndGet()
                            webSocket.close(code, "")
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (released.compareAndSet(false, true)) live.decrementAndGet()
                        }
                    })
                }
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                val values = Channel<CoreTelemetry>(Channel.UNLIMITED)
                val collecting = launch(Dispatchers.Default) { api.telemetry().collect { values.send(it) } }
                suspend fun traffic() = withTimeout(5000) { while (values.receive() !is CoreTelemetry.TrafficSample) Unit }
                try {
                    traffic(); assertEquals("/aerove/telemetry", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                    sockets.poll(3, TimeUnit.SECONDS)!!
                    api.setTelemetryObservations(true)
                    val window = withTimeout(5000) {
                        var event: CoreTelemetry
                        do { event = values.receive() } while (event !is CoreTelemetry.Observations)
                        (event as CoreTelemetry.Observations).value
                    }
                    assertEquals(ULong.MAX_VALUE, window.generation)
                    assertEquals("/aerove/telemetry?observations=true", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                    val rich = sockets.poll(3, TimeUnit.SECONDS)!!
                    api.setTelemetryObservations(false)
                    rich.send("""{"type":"observations","private":"stale"}""")
                    traffic()
                    assertEquals("/aerove/telemetry", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                    api.setTelemetryObservations(false)
                    assertFalse(overlap.get()); assertEquals(3, server.requestCount)
                } finally { collecting.cancelAndJoin(); values.close() }
            }
        }
    }

    @Test fun dnsFlushIsOneAuthenticatedPostWithTypedSecretFreeFailureAndFullCallCancellation() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setResponseCode(401).setBody("private $secret"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                api.clearDnsCache()
                val first = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("POST", first.method); assertEquals("/cache/dns/flush", first.path)
                assertEquals("Bearer $secret", first.getHeader("Authorization"))
                val error = runCatching { api.clearDnsCache() }.exceptionOrNull()
                assertTrue(error is CoreApiException); assertEquals(401, (error as CoreApiException).statusCode)
                assertFalse(error.stackTraceToString().contains(secret))
                server.takeRequest(3, TimeUnit.SECONDS)!!
                val blocked = launch(Dispatchers.Default) { api.clearDnsCache() }
                assertEquals("/cache/dns/flush", server.takeRequest(3, TimeUnit.SECONDS)!!.path)
                withTimeout(2000) { blocked.cancelAndJoin() }
                assertTrue(blocked.isCancelled); assertEquals(3, server.requestCount)
            }
        }
    }
}
