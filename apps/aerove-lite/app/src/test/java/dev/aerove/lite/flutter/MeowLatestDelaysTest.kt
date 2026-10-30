package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class MeowLatestDelaysTest {
    private class HistorySource(var snapshot: MeowDelayHistorySnapshot) : MeowAppliedQueries {
        override suspend fun proxyGroupOrder(): List<String> = error("Not used by these history tests")
        override suspend fun proxyDefinition(name: String): Map<String, Any?>? = error("Not used by these history tests")
        override suspend fun latestDelays() = snapshot
    }
    private fun row(url: String, value: Any) = mapOf("name" to "Node", "url" to url, "value" to value)
    private fun history(rows: List<Map<String, Any?>> = listOf(row("https://a.test/", 40))) =
        MeowDelayHistorySnapshot("owner", 5, 7uL, 10uL, 1uL, rows)
    private fun api(server: MockWebServer) = CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef"))
    private fun route(epoch: String = "7", version: String = "10") = MockResponse()
        .setBody("""{"core-epoch":"$epoch","route-version":"$version","picks":{"Pick":"Node"}}""")
    private fun probe(value: Int, epoch: String = "7", version: String = "10") = MockResponse()
        .setBody("""{"delay":$value}""").setHeader("x-aerove-core-epoch", epoch).setHeader("x-aerove-route-version", version)
    private val probeArgs = mapOf("proxy-name" to "Node", "test-url" to "https://a.test/", "timeout" to 1200)
    private fun CoroutineScope.session(client: CoreApiClient, source: MeowAppliedQueries? = null,
        epoch: ULong = 7uL, events: (List<Map<String, Any?>>) -> Unit = {}, failures: (Throwable) -> Unit = {}) =
        MeowProtocolSession(client, "owner", 5, epoch, this, { true }, events, failures, source, false)
    private fun failure(session: MeowProtocolSession, method: String = "getLatestDelays", arguments: Any? = null) =
        assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke(method, arguments) } }

    @Test fun authoritativeUrlHistoryIsNotOverwrittenByAnOlderLocalProbe() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(probe(12))
            server.enqueue(route())
            val source = HistorySource(history(listOf(row("https://a.test/", 40), row("https://b.test/", -2))))
            api(server).use { client ->
                val session = session(client, source)
                try {
                    assertEquals(12, protocolMap(session.invoke("asyncTestDelay", probeArgs), "probe")["value"])
                    assertEquals(source.snapshot.delays, session.invoke("getLatestDelays", null))
                    assertEquals(2, server.requestCount)
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("7", request.requestUrl!!.queryParameter("coreEpoch"))
                    assertEquals("https://a.test/", request.requestUrl!!.queryParameter("url"))
                    assertEquals("1200", request.requestUrl!!.queryParameter("timeout"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun historyMustMatchOwnerGenerationEpochAndCurrentRoute() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val base = history()
            val source = HistorySource(base)
            api(server).use { client ->
                val session = session(client, source)
                try {
                    for (snapshot in listOf(base.copy(owner = "retired"), base.copy(generation = 4), base.copy(coreEpoch = 8uL))) {
                        source.snapshot = snapshot
                        assertEquals("stale_session", failure(session).code)
                    }
                    assertEquals(0, server.requestCount)
                    source.snapshot = base
                    server.enqueue(route(version = "11"))
                    assertEquals("route_superseded", failure(session).code)
                    server.enqueue(route(epoch = "8"))
                    assertEquals("stale_session", failure(session).code)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun historyRevisionCannotReplayOrChangeItsBodyWithoutAdvancing() = runBlocking<Unit> {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(route()) }
            val source = HistorySource(history().copy(historyRevision = 9uL))
            api(server).use { client ->
                val session = session(client, source)
                try {
                    assertEquals(source.snapshot.delays, session.invoke("getLatestDelays", null))
                    source.snapshot = source.snapshot.copy(historyRevision = 8uL)
                    assertEquals("history_superseded", failure(session).code)
                    source.snapshot = source.snapshot.copy(historyRevision = 9uL, delays = listOf(row("https://a.test/", 41)))
                    assertEquals("invalid_core_response", failure(session).code)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun incompleteOrAmbiguousUrlRowsCannotBecomeSuccessfulHistory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val source = HistorySource(history())
            api(server).use { client ->
                val session = session(client, source)
                try {
                    val invalid = listOf(listOf(row("https://a.test/", 0)), listOf(row("https://a.test/", -1)),
                        listOf(row("https://a.test/", -3)), listOf(row("https://a.test/", 1.5)),
                        listOf(mapOf("name" to "Node", "value" to 3)),
                        listOf(row("https://a.test/", 3), row("https://a.test/", 4)))
                    for (rows in invalid) {
                        source.snapshot = history(rows)
                        assertEquals("invalid_core_response", failure(session).code)
                    }
                    assertEquals(0, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun delayReceiptIsRequiredEvenForTimeoutAndCannotUseAnotherEpoch() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"delay":9}"""))
            server.enqueue(MockResponse().setResponseCode(504).setBody("""{"message":"Timeout"}"""))
            server.enqueue(probe(9, epoch = "8"))
            server.enqueue(probe(9).addHeader("x-aerove-core-epoch", "8"))
            api(server).use { client ->
                val session = session(client)
                try {
                    assertEquals("invalid_core_response", failure(session, "asyncTestDelay", probeArgs).code)
                    assertEquals("invalid_core_response", failure(session, "asyncTestDelay", probeArgs).code)
                    assertEquals("stale_session", failure(session, "asyncTestDelay", probeArgs).code)
                    assertEquals("invalid_core_response", failure(session, "asyncTestDelay", probeArgs).code)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun exactUnsignedDelayReceiptAndRealZeroUseSharedSemantics() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(probe(9, ULong.MAX_VALUE.toString(), ULong.MAX_VALUE.toString()))
            server.enqueue(probe(0, ULong.MAX_VALUE.toString(), ULong.MAX_VALUE.toString()))
            api(server).use { client ->
                val session = session(client, epoch = ULong.MAX_VALUE)
                try {
                    assertEquals(9, protocolMap(session.invoke("asyncTestDelay", probeArgs), "probe")["value"])
                    assertEquals(-2, protocolMap(session.invoke("asyncTestDelay", probeArgs), "probe")["value"])
                    assertEquals(ULong.MAX_VALUE.toString(), server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.queryParameter("coreEpoch"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun watchFalseCancelsAnInFlightInitialRouteRead() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            api(server).use { client ->
                val session = session(client)
                try {
                    val old = async { session.invoke("watchRoute", true) }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                    assertNull(session.invoke("watchRoute", false))
                    withTimeout(2000) { old.join() }
                    assertTrue(old.isCancelled)
                    assertEquals(1, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun oldRouteReadbackCannotOverwriteANewerCommittedProbeStamp() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val release = CountDownLatch(1)
            val reads = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path!!.startsWith("/proxies/")) return probe(12, version = "14")
                    if (reads.incrementAndGet() == 1) { check(release.await(3, TimeUnit.SECONDS)); return route(version = "12") }
                    return route(version = "14")
                }
            }
            api(server).use { client ->
                val session = session(client)
                try {
                    val old = async { try { session.invoke("watchRoute", true); "success" } catch (failure: MeowQueryException) { failure.code } }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                    session.invoke("asyncTestDelay", probeArgs)
                    release.countDown()
                    assertEquals("route_superseded", withTimeout(2000) { old.await() })
                    assertEquals(mapOf("core-epoch" to 7L, "route-version" to 14L), session.invoke("watchRoute", true))
                } finally { release.countDown(); session.closeAndJoin() }
            }
        }
    }

    @Test fun cancellingANotificationRouteReadKeepsTheSingleTelemetryConsumerAlive() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val socket = CompletableDeferred<WebSocket>()
            val reading = CompletableDeferred<Unit>()
            val reads = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/aerove/route") {
                        if (reads.incrementAndGet() == 1) return route()
                        reading.complete(Unit)
                        return MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    }
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                    })
                }
            }
            api(server).use { client ->
                val batches = Channel<List<Map<String, Any?>>>(Channel.UNLIMITED)
                val failures = Channel<Throwable>(Channel.UNLIMITED)
                val session = session(client, events = { batches.trySend(it) }, failures = { failures.trySend(it) })
                try {
                    session.invoke("watchRoute", true)
                    session.setUiTelemetryActive(true)
                    val peer = withTimeout(3000) { socket.await() }
                    peer.send("""{"type":"groupsChanged","revision":11,"routeVersion":11}""")
                    withTimeout(3000) { reading.await() }
                    assertNull(session.invoke("watchRoute", false))
                    peer.send("""{"type":"providersLoaded","providers":[{"kind":"proxy","name":"p","revision":"8","phase":"update","changed":true}]}""")
                    assertEquals("loaded", withTimeout(3000) { batches.receive() }.single()["type"])
                    assertTrue(failures.tryReceive().isFailure)
                    assertEquals(2, reads.get())
                } finally { session.closeAndJoin(); batches.close(); failures.close() }
            }
        }
    }
}
