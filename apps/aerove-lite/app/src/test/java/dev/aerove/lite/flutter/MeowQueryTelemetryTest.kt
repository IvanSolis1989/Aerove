package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.CoreApiException
import java.math.BigInteger
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowQueryTelemetryTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private val sessionId = "00000000-0000-4000-8000-000000000007"
    private val requestId = "00000000-0000-4000-8000-000000000008"
    private fun api(server: MockWebServer) = CoreApiClient(ControlEndpoint(server.port, secret))
    private val applied = object : MeowAppliedQueries {
        override suspend fun proxyGroupOrder() = listOf("Pick")
        override suspend fun proxyDefinition(name: String): Map<String, Any?>? =
            if (name == "Node") mapOf("name" to name, "type" to "ss", "server" to "example.test", "plugin-opts" to mapOf("mode" to "websocket")) else null
    }
    private fun CoroutineScope.session(client: CoreApiClient, events: (List<Map<String, Any?>>) -> Unit = {},
        failures: (Throwable) -> Unit = { throw AssertionError("Unexpected telemetry failure", it) },
        current: () -> Boolean = { true }, epoch: ULong = 7uL) =
        MeowProtocolSession(client, "test-owner", 4, epoch, this, current, events, failures, applied, false)

    @Test fun rawProxiesAndAppliedDefinitionKeepUnknownFieldsAndRealOrder() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"proxies":{"Pick":{"name":"Pick","type":"Selector","all":["Node"],"now":"Node","icon":"x","hidden":true},"Node":{"type":"Shadowsocks","provider-name":"remote","history":[{"time":"2026-10-30T01:00:00Z","delay":12}],"extension":{"x":3}}}}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val result = protocolMap(session.invoke("getProxies", null), "result")
                    assertEquals(listOf("Pick"), result["all"])
                    val nodes = protocolMap(result["proxies"], "proxies")
                    assertEquals("remote", protocolMap(nodes["Node"], "node")["provider-name"])
                    assertEquals(mapOf("x" to 3), protocolMap(nodes["Node"], "node")["extension"])
                    assertEquals(true, protocolMap(nodes["Pick"], "group")["hidden"])
                    val definition = protocolMap(session.invoke("getProxyDefinition", "Node"), "definition")
                    assertEquals(mapOf("mode" to "websocket"), definition["plugin-opts"])
                    assertNull(session.invoke("getProxyDefinition", "missing"))
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("/proxies", request.path)
                    assertEquals("Bearer $secret", request.getHeader("Authorization"))
                    assertEquals(1, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun connectionsKeepRawMetadataAndUseStringPortsAndExactBytes() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"connections":[${tracker()}],"uploadTotal":99,"downloadTotal":101}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val root = protocolMap(session.invoke("getConnections", null), "root")
                    val row = protocolMap(protocolList(root["connections"], "connections").single(), "tracker")
                    val metadata = protocolMap(row["metadata"], "metadata")
                    assertEquals("443", metadata["destinationPort"])
                    assertEquals("50000", metadata["sourcePort"])
                    assertEquals(10001L, metadata["uid"])
                    assertEquals(listOf("private"), metadata["sourceGeoIP"])
                    assertEquals(BigInteger("18446744073709551615"), row["upload"])
                    assertTrue(JSONObject(row).toString().contains("18446744073709551615"))
                    assertEquals(99, root["uploadTotal"])
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun providerProjectionKeepsSubscriptionAndUsesRuleNamePrecedence() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"providers":{"same":{"name":"same","type":"Proxy","vehicleType":"HTTP","updatedAt":"2026-10-30T00:00:00Z","proxies":[{"name":"Node"}],"path":"cache/p.yaml","subscriptionInfo":{"Upload":10,"Download":20,"Total":100,"Expire":123},"revision":"18446744073709551615"},"inline":{"vehicleType":"Compatible"}}}"""))
            server.enqueue(MockResponse().setBody("""{"providers":{"same":{"name":"same","type":"Rule","vehicleType":"File","updatedAt":"2026-10-30T00:00:01Z","ruleCount":4,"behavior":"domain"}}}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val rows = protocolList(session.invoke("getExternalProviders", null), "providers")
                    val row = protocolMap(rows.single(), "provider")
                    assertEquals("Rule", row["type"])
                    assertEquals(4L, row["count"])
                    assertEquals("domain", row["behavior"])
                    assertEquals("File", row["vehicle-type"])
                    assertEquals("2026-10-30T00:00:01Z", row["update-at"])
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun mutationAcknowledgementRequiresRealHttpSuccessAndFailureSurvives() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"connections":[${tracker()}]}"""))
            repeat(4) { server.enqueue(MockResponse().setResponseCode(204)) }
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"sessionStopped","message":"native ended","field":"owner"}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    assertEquals(true, session.invoke("closeConnection", requestId))
                    assertEquals(true, session.invoke("closeConnections", null))
                    assertEquals(true, session.invoke("resetConnections", null))
                    assertEquals(true, session.invoke("clearDnsCache", null))
                    val failure = assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("resetConnections", null) } }
                    assertEquals(503, failure.details["status"])
                    assertEquals("sessionStopped", failure.code)
                    assertEquals("native ended", failure.message)
                    assertEquals("owner", protocolMap(failure.details["native"], "native")["field"])
                    val requests = (0..5).map { server.takeRequest(2, TimeUnit.SECONDS)!! }
                    assertEquals(listOf("GET", "DELETE", "DELETE", "POST", "POST", "POST"), requests.map { it.method })
                    assertEquals("/cache/dns/flush", requests[4].path)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun singleProviderPreservesExactNativeUsageAndMissingConnectionIsFalse() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"providerNotFound"}"""))
            server.enqueue(MockResponse().setBody("""{"name":"sub","type":"Proxy","vehicleType":"HTTP","updatedAt":"2026-10-30T00:00:00Z","proxies":[{"name":"Node","hidden":false}],"subscriptionInfo":{"Upload":10,"Download":20,"Total":100,"Expire":123},"revision":"18446744073709551615"}"""))
            server.enqueue(MockResponse().setBody("""{"connections":[]}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val row = protocolMap(session.invoke("getExternalProvider", "sub"), "provider")
                    assertEquals(mapOf("Upload" to 10, "Download" to 20, "Total" to 100, "Expire" to 123), row["subscription-info"])
                    assertEquals(row["subscriptionInfo"], row["subscription-info"])
                    assertEquals("18446744073709551615", row["revision"])
                    assertEquals(false, session.invoke("closeConnection", requestId))
                    assertEquals(listOf("/providers/rules/sub", "/providers/proxies/sub", "/connections"),
                        (0..2).map { server.takeRequest(2, TimeUnit.SECONDS)!!.path })
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun routeChecksTranslateRoutesToHitsAndDoNotRoundUnsignedStamps() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"core-epoch":"18446744073709551615","route-version":"18446744073709551614","held":[true,false]}"""))
            api(server).use { client ->
                val session = session(client, epoch = ULong.MAX_VALUE)
                try {
                    val args = mapOf("routes" to listOf(listOf(mapOf("host" to "a.test:443", "proxy" to "Pick", "exit" to "Node")), emptyList<Any>()))
                    val result = protocolMap(session.invoke("checkRoutes", args), "result")
                    assertEquals(BigInteger("18446744073709551615"), result["core-epoch"])
                    assertEquals(listOf(true, false), result["held"])
                    val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertTrue(body.has("hits")); assertFalse(body.has("routes"))
                    assertEquals("a.test:443", body.getJSONArray("hits").getJSONArray(0).getJSONObject(0).getString("host"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun nativeSampleObservationDedupAndForegroundCancellationShareOneProducer() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val socket = CompletableDeferred<WebSocket>()
            val ended = CompletableDeferred<Unit>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.complete(Unit) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
            }))
            api(server).use { client ->
                val batches = Channel<List<Map<String, Any?>>>(Channel.UNLIMITED)
                val failures = Channel<Throwable>(Channel.UNLIMITED)
                val session = session(client, { batches.trySend(it) }, { failures.trySend(it) })
                try {
                    session.setUiTelemetryActive(true)
                    session.setUiTelemetryActive(true)
                    val peer = withTimeout(4000) { socket.await() }
                    peer.send(window("0", "[]", "0"))
                    peer.send("""{"type":"traffic","up":5,"down":7,"uploadTotal":101,"downloadTotal":202}""")
                    val events = """[{"kind":"request","cursor":"1","data":${tracker()}},{"kind":"dns","cursor":"2","data":${dns()}},{"kind":"requestTraffic","cursor":"3","data":{"id":"$requestId","upload":"10","download":"20"}},{"kind":"requestClosed","cursor":"4","data":{"id":"$requestId","upload":"30","download":"40","end":"2026-10-30T00:00:02Z"}}]"""
                    peer.send(window("4", events))
                    val batch = withTimeout(4000) { batches.receive() }
                    assertEquals(listOf("request", "dns", "requestTraffic"), batch.map { it["type"] })
                    val dns = protocolMap(batch[1]["data"], "dns")
                    assertNull(dns["initiator"]); assertEquals("NXDOMAIN", dns["rcode"]); assertEquals("timeout", dns["error"])
                    val counters = protocolMap(protocolList(batch[2]["data"], "traffic").single(), "traffic")
                    assertEquals(30L, counters["upload"]); assertEquals(40L, counters["download"])
                    assertEquals(mapOf("up" to 5L, "down" to 7L), session.invoke("getTraffic", false))
                    assertEquals(mapOf("up" to 101L, "down" to 202L), session.invoke("getTotalTraffic", false))
                    peer.send(window("4", events))
                    peer.send("""{"type":"providersLoaded","providers":[{"kind":"proxy","name":"p","revision":"8","phase":"update","changed":true}]}""")
                    assertEquals("loaded", withTimeout(4000) { batches.receive() }.single()["type"])
                    assertEquals(4uL, session.observationReceipt()!!.cursor)
                    assertEquals(2uL, session.observationReceipt()!!.dropped)
                    session.setUiTelemetryActive(false)
                    withTimeout(4000) { ended.await() }
                    assertEquals(mapOf("up" to 101L, "down" to 202L), session.invoke("getTotalTraffic", false))
                    assertEquals(1, server.requestCount)
                    assertEquals("/aerove/telemetry?observations=true", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                    assertTrue(batches.tryReceive().isFailure)
                    assertTrue(failures.tryReceive().isFailure)
                } finally { session.closeAndJoin(); batches.close(); failures.close() }
            }
        }
    }

    @Test fun actualDelayKeepsRequestedUrlButCannotReplaceMissingCompleteHistory() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"delay":32}""")
                .setHeader("x-aerove-core-epoch", "7").setHeader("x-aerove-route-version", "10"))
            api(server).use { client ->
                val session = session(client)
                try {
                    val row = session.invoke("asyncTestDelay", mapOf("proxy-name" to "Node", "test-url" to "https://example.test/check", "timeout" to 1234))
                    assertEquals(mapOf("name" to "Node", "url" to "https://example.test/check", "value" to 32), row)
                    assertEquals("implementation_missing", assertThrows(MeowQueryException::class.java) {
                        runBlocking { session.invoke("getLatestDelays", null) }
                    }.code)
                    assertEquals(1, server.requestCount)
                    assertTrue(server.takeRequest(2, TimeUnit.SECONDS)!!.path!!.contains("timeout=1234"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun timeoutIsARealMinusOneResultButTransportFailureIsNotAProbeReceipt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(504).setBody("""{"message":"Timeout"}""")
                .setHeader("x-aerove-core-epoch", "7").setHeader("x-aerove-route-version", "10"))
            server.enqueue(MockResponse().setResponseCode(503))
            api(server).use { client ->
                val session = session(client)
                val args = mapOf("proxy-name" to "Node", "test-url" to "https://example.test/", "timeout" to 1000)
                try {
                    assertEquals(-1, protocolMap(session.invoke("asyncTestDelay", args), "delay")["value"])
                    assertEquals(503, assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("asyncTestDelay", args) } }.details["status"])
                    assertEquals("bad_argument", assertThrows(MeowQueryException::class.java) {
                        runBlocking { session.invoke("asyncTestDelay", args + ("timeout" to 4294967297L)) }
                    }.code)
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun selectorUsesRealReadbackAndEmptySelectionUsesNativeUnpin() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"core-epoch":"7","route-version":"10","picks":{"Pick":"Node"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            server.enqueue(MockResponse().setBody("""{"core-epoch":"7","route-version":"11","picks":{"Pick":"AutoNode"}}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val result = session.invoke("changeProxy", mapOf("group-name" to "Pick", "proxy-name" to ""))
                    assertEquals(mapOf("message" to "", "changed" to true), result)
                    assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
                    val mutation = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("DELETE", mutation.method); assertEquals("/proxies/Pick", mutation.path)
                    assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun logDemandUsesIndependentSocketAndStopActuallyCancelsIt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val telemetry = CompletableDeferred<WebSocket>()
            val logs = CompletableDeferred<WebSocket>()
            val logsEnded = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (request.path == "/logs") logs.complete(webSocket) else telemetry.complete(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (request.path == "/logs") logsEnded.complete(Unit) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (request.path == "/logs") logsEnded.complete(Unit) }
                })
            }
            api(server).use { client ->
                val batches = Channel<List<Map<String, Any?>>>(Channel.UNLIMITED)
                val session = session(client, { batches.trySend(it) })
                try {
                    session.invoke("startLog", null)
                    assertEquals(0, server.requestCount)
                    session.setUiTelemetryActive(true)
                    val logPeer = withTimeout(4000) { logs.await() }
                    withTimeout(4000) { telemetry.await() }
                    logPeer.send("""{"type":"warn","payload":"actual warning"}""")
                    val event = withTimeout(4000) { batches.receive() }.single()
                    assertEquals("log", event["type"])
                    assertEquals("warning", protocolMap(event["data"], "log")["LogLevel"])
                    assertEquals("actual warning", protocolMap(event["data"], "log")["Payload"])
                    session.invoke("stopLog", null)
                    withTimeout(4000) { logsEnded.await() }
                    assertEquals(2, server.requestCount)
                    assertTrue(batches.tryReceive().isFailure)
                } finally { session.closeAndJoin(); batches.close() }
            }
        }
    }

    @Test fun stoppingLogsDoesNotReportAnAlreadyQueuedSocketFailure() = runBlocking<Unit> {
        val queued = LinkedBlockingQueue<Pair<CoroutineContext, Runnable>>()
        val dispatchGate = Any()
        var holding = false
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = synchronized(dispatchGate) {
                if (holding) { queued.add(context to block); Unit } else Dispatchers.Default.dispatch(context, block)
            }
        }
        val owner = SupervisorJob()
        MockWebServer().use { server ->
            val logs = CompletableDeferred<WebSocket>()
            val stream = CompletableDeferred<WebSocket>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (request.path == "/logs") logs.complete(webSocket) else stream.complete(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                })
            }
            api(server).use { client ->
                val failures = Channel<Throwable>(Channel.UNLIMITED)
                val ready = CompletableDeferred<Unit>()
                val telemetry = MeowTelemetryProtocol(client, 7uL, CoroutineScope(owner + dispatcher), { true }, { ready.complete(Unit) },
                    { failures.trySend(it) }, false)
                try {
                    telemetry.setLogEnabled(true)
                    telemetry.setActive(true)
                    val peer = withTimeout(4000) { logs.await() }
                    withTimeout(4000) { stream.await() }
                    assertTrue(peer.send("""{"type":"info","payload":"reader ready"}"""))
                    withTimeout(4000) { ready.await() }
                    synchronized(dispatchGate) { holding = true }
                    assertTrue(peer.close(1011, "actual upstream failure"))
                    val pending = withContext(Dispatchers.IO) { queued.poll(4, TimeUnit.SECONDS) }
                    assertNotNull("The real socket failure must be queued before stop", pending)
                    val stop = async(start = CoroutineStart.UNDISPATCHED) { telemetry.setLogEnabled(false) }
                    synchronized(dispatchGate) {
                        holding = false
                        pending!!.let { Dispatchers.Default.dispatch(it.first, it.second) }
                        while (true) {
                            val task = queued.poll() ?: break
                            Dispatchers.Default.dispatch(task.first, task.second)
                        }
                    }
                    withTimeout(4000) { stop.await() }
                    assertTrue("Cancelled log reader must not publish a socket failure", failures.tryReceive().isFailure)
                } finally {
                    synchronized(dispatchGate) {
                        holding = false
                        while (true) {
                            val task = queued.poll() ?: break
                            Dispatchers.Default.dispatch(task.first, task.second)
                        }
                    }
                    telemetry.close()
                    owner.cancelAndJoin()
                    failures.close()
                }
            }
        }
    }
    @Test fun activeLogFailureIsReportedOnceAndFailureCallbackExceptionsRemainVisible() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val logs = CompletableDeferred<WebSocket>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (request.path == "/logs") logs.complete(webSocket)
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                })
            }
            api(server).use { client ->
                val uncaught = Channel<Throwable>(Channel.UNLIMITED)
                val failures = Channel<Throwable>(Channel.UNLIMITED)
                val calls = AtomicInteger()
                val owner = SupervisorJob()
                val handler = CoroutineExceptionHandler { _, failure -> uncaught.trySend(failure) }
                val telemetry = MeowTelemetryProtocol(client, 7uL, CoroutineScope(owner + Dispatchers.Default + handler),
                    { true }, {}, { failure ->
                        calls.incrementAndGet()
                        failures.trySend(failure)
                        throw AssertionError("failure callback remains visible", failure)
                    }, false)
                try {
                    telemetry.setLogEnabled(true)
                    telemetry.setActive(true)
                    assertTrue(withTimeout(4000) { logs.await() }.close(1011, "actual upstream failure"))
                    val failure = withTimeout(4000) { failures.receive() }
                    assertTrue(failure is java.io.IOException)
                    assertEquals("Core stream closed (1011)", failure.message)
                    val callbackError = withTimeout(4000) { uncaught.receive() }
                    assertTrue(callbackError is AssertionError)
                    assertEquals("failure callback remains visible", callbackError.message)
                    assertSame(failure, callbackError.cause)
                    telemetry.setLogEnabled(false)
                    assertEquals(1, calls.get())
                    assertTrue(failures.tryReceive().isFailure)
                    assertTrue(uncaught.tryReceive().isFailure)
                } finally {
                    telemetry.close()
                    owner.cancelAndJoin()
                    failures.close(); uncaught.close()
                }
            }
        }
    }
    @Test fun callerCancellationCancelsTheActualHttpRead() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            api(server).use { client ->
                val session = session(client)
                try {
                    val task = async(start = CoroutineStart.UNDISPATCHED) { session.invoke("getConnectionCount", null) }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                    task.cancel()
                    withTimeout(2000) { task.join() }
                    assertTrue(task.isCancelled)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun routeWatchUsesNotificationsAndStopsWithoutPolling() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val socket = CompletableDeferred<WebSocket>()
            val reads = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/aerove/route") {
                        val version = 9 + reads.incrementAndGet()
                        return MockResponse().setBody("""{"core-epoch":"7","route-version":"$version","picks":{"Pick":"Node"}}""")
                    }
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { socket.complete(webSocket) }
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                    })
                }
            }
            api(server).use { client ->
                val batches = Channel<List<Map<String, Any?>>>(Channel.UNLIMITED)
                val session = session(client, { batches.trySend(it) })
                try {
                    assertEquals(mapOf("core-epoch" to 7L, "route-version" to 10L), session.invoke("watchRoute", true))
                    session.setUiTelemetryActive(true)
                    val peer = withTimeout(4000) { socket.await() }
                    peer.send("""{"type":"groupsChanged","revision":11,"routeVersion":11}""")
                    val event = withTimeout(4000) { batches.receive() }.single()
                    assertEquals("routeChanged", event["type"])
                    assertEquals(mapOf("core-epoch" to 7L, "route-version" to 11L), event["data"])
                    peer.send("""{"type":"groupsChanged","revision":11,"routeVersion":11}""")
                    peer.send("""{"type":"providersLoaded","providers":[{"kind":"rule","name":"r","revision":"1","phase":"initial","changed":false}]}""")
                    assertEquals("loaded", withTimeout(4000) { batches.receive() }.single()["type"])
                    assertEquals(2, reads.get())
                    assertNull(session.invoke("watchRoute", false))
                    peer.send("""{"type":"groupsChanged","revision":12,"routeVersion":12}""")
                    peer.send("""{"type":"providersLoaded","providers":[{"kind":"rule","name":"r","revision":"2","phase":"update","changed":true}]}""")
                    assertEquals("loaded", withTimeout(4000) { batches.receive() }.single()["type"])
                    assertEquals(2, reads.get())
                    assertTrue(batches.tryReceive().isFailure)
                } finally { session.closeAndJoin(); batches.close() }
            }
        }
    }

    @Test fun resumeWatermarkSkipsOldHistoryAndRejectsRetiredGenerationReplay() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val sockets = Channel<WebSocket>(Channel.UNLIMITED)
            val ended = Channel<Unit>(Channel.UNLIMITED)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { sockets.trySend(webSocket) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { ended.trySend(Unit) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { ended.trySend(Unit) }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, "") }
                })
            }
            api(server).use { client ->
                val batches = Channel<List<Map<String, Any?>>>(Channel.UNLIMITED)
                val session = session(client, { batches.trySend(it) })
                try {
                    session.setUiTelemetryActive(true)
                    val first = withTimeout(4000) { sockets.receive() }
                    first.send(window("11", """[{"kind":"request","cursor":"9","data":${tracker()}},{"kind":"dns","cursor":"11","data":${dns()}}]""", "10"))
                    assertEquals("dns", withTimeout(4000) { batches.receive() }.single()["type"])
                    session.setUiTelemetryActive(false)
                    withTimeout(4000) { ended.receive() }
                    session.setUiTelemetryActive(true)
                    val second = withTimeout(4000) { sockets.receive() }
                    second.send(window("21", """[{"kind":"request","cursor":"19","data":${tracker()}},{"kind":"request","cursor":"21","data":${tracker()}}]""", "20").replace("\"generation\":\"3\"", "\"generation\":\"4\""))
                    assertEquals("request", withTimeout(4000) { batches.receive() }.single()["type"])
                    second.send(window("99", """[{"kind":"request","cursor":"99","data":${tracker()}}]"""))
                    second.send("""{"type":"providersLoaded","providers":[{"kind":"proxy","name":"p","revision":"9","phase":"update","changed":true}]}""")
                    assertEquals("loaded", withTimeout(4000) { batches.receive() }.single()["type"])
                    assertEquals(MeowObservationReceipt(sessionId, 4uL, 20uL, 21uL, 2uL), session.observationReceipt())
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin(); batches.close(); sockets.close(); ended.close() }
            }
        }
    }

    @Test fun absentAppliedReadersAreExplicitBlockers() = runBlocking<Unit> {
        MockWebServer().use { server ->
            api(server).use { client ->
                val session = MeowProtocolSession(client, "owner", 1, 7uL, this, { true }, {}, {})
                try {
                    for (method in listOf("getProxies", "getLatestDelays", "getProxyDefinition")) {
                        val error = assertThrows(MeowQueryException::class.java) {
                            runBlocking { session.invoke(method, if (method == "getProxyDefinition") "Node" else null) }
                        }
                        assertEquals("implementation_missing", error.code)
                    }
                    assertEquals("implementation_missing", assertThrows(MeowQueryException::class.java) {
                        runBlocking { session.invoke("getTraffic", false) }
                    }.code)
                    assertEquals(0, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun terminalAndStaleSessionsAndMissingProducersNeverReturnFakeSuccess() = runBlocking<Unit> {
        MockWebServer().use { server ->
            api(server).use { client ->
                var current = true
                val session = session(client, current = { current })
                try {
                    for (method in listOf("getMemoryStats", "resetTraffic")) {
                        val error = assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke(method, null) } }
                        assertEquals("implementation_missing", error.code)
                    }
                    val missing = assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("getTraffic", false) } }
                    assertEquals("sample_unavailable", missing.code)
                    current = false
                    assertEquals("stale_session", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("getConnectionCount", null) } }.code)
                    session.closeAndJoin()
                    assertEquals("closed", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("getConnectionCount", null) } }.code)
                    assertEquals(0, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    private fun tracker() = """{"id":"$requestId","upload":"18446744073709551615","download":"11","start":"2026-10-30T00:00:00Z","metadata":{"network":"tcp","sourcePort":50000,"destinationPort":"443","uid":"10001","sourceGeoIP":["private"],"host":"example.test"},"chains":["Node","Pick"],"rule":"DOMAIN","rulePayload":"example.test"}"""
    private fun dns() = """{"domain":"missing.test","type":"A","initiator":"FutureSource","upstream":"1.1.1.1","cached":false,"answers":[],"rcode":"NXDOMAIN","error":"timeout","delay":27,"time":"2026-10-30T00:00:01Z"}"""
    private fun window(cursor: String, events: String, activation: String? = null) =
        """{"type":"observations","sessionId":"$sessionId","generation":"3","cursor":"$cursor","dropped":"2",${activation?.let { "\"activationCursor\":\"$it\"," }.orEmpty()}"events":$events}"""
}
