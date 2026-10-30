package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

class MeowRoutedProbeTest {
    private val secret = "private-routed-probe-test-secret-0001"
    private val url = "https://caller.test/source?format=raw"
    private val maximum = "18446744073709551615"
    private fun CoroutineScope.session(client: CoreApiClient, epoch: ULong = 7uL, current: () -> Boolean = { true }) =
        MeowProtocolSession(client, "owner-79", 79, epoch, this, current, {}, { throw AssertionError(it) })
    private fun api(server: MockWebServer, client: OkHttpClient = OkHttpClient()) = CoreApiClient(ControlEndpoint(server.port, secret), client)
    private fun route(epoch: String = "7", version: String = "0") =
        MockResponse().setBody("""{"core-epoch":"$epoch","route-version":"$version","picks":{"Pick":"Node"}}""")
    private fun probe(epoch: String = "7", version: String = "0") = JSONObject()
        .put("status-code", 403).put("delay", 27).put("body", "raw").put("url", "https://caller.test/final")
        .put("chains", JSONArray(listOf("Node", "Pick"))).put("rule", "DOMAIN").put("rule-payload", "caller.test")
        .put("core-epoch", epoch).put("route-version", version)
        .put("hits", JSONArray().put(JSONObject().put("host", "caller.test:443").put("proxy", "Pick").put("exit", "actual Node")))
    private fun outbound(epoch: String = "7", version: String = "0") = JSONObject()
        .put("url", url).put("body", "ip=1.1.1.1\nextra=raw").put("delay", 31)
        .put("chains", JSONArray(listOf("Node", "Pick"))).put("core-epoch", epoch).put("route-version", version)
        .put("hits", JSONArray().put(JSONObject().put("host", "caller.test:443").put("proxy", "Pick").put("exit", "actual Node"))
            .put(JSONObject().put("host", "failed.test:443").put("proxy", "Pick").put("exit", "")))
        .put("node-hits", JSONArray().put(JSONObject().put("host", "caller.test:443").put("proxy", "Node").put("exit", "actual Node")))
    private fun probeArgs() = mapOf("url" to url, "proxy-name" to "Pick", "timeout" to 1234, "max-body" to 8,
        "headers" to mapOf("Authorization" to "remote-only", "X-Caller" to "exact", "User-Agent" to "caller-agent"))
    private fun outboundArgs() = mapOf("proxy-name" to "Pick", "urls" to listOf("https://failed.test/", url), "timeout" to 4321)

    @Test fun probeUsesPrivateBearerJsonHeadersAndExactU64ReceiptWithOneReadback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(probe(maximum, maximum).put("delay", BigInteger(maximum)).toString()))
            server.enqueue(route(maximum, maximum))
            api(server).use { client ->
                val session = session(client, ULong.MAX_VALUE)
                try {
                    val result = protocolMap(session.invoke("probe", probeArgs()), "result")
                    assertEquals(403, result["status-code"]); assertEquals("raw", result["body"])
                    assertEquals("https://caller.test/final", result["url"])
                    assertEquals("DOMAIN", result["rule"]); assertEquals("caller.test", result["rule-payload"])
                    assertEquals(BigInteger(maximum), result["delay"])
                    assertEquals(BigInteger(maximum), result["core-epoch"]); assertEquals(BigInteger(maximum), result["route-version"])
                    assertTrue(JSONObject(result).toString().contains(maximum))
                    val call = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("POST", call.method); assertEquals("/aerove/probe?coreEpoch=$maximum", call.path)
                    assertEquals("Bearer $secret", call.getHeader("Authorization")); assertNull(call.getHeader("X-Caller"))
                    val sent = JSONObject(call.body.readUtf8())
                    assertEquals(url, sent.getString("url")); assertEquals(1234, sent.getInt("timeout")); assertEquals(8, sent.getInt("max-body"))
                    assertEquals("remote-only", sent.getJSONObject("headers").getString("Authorization"))
                    assertEquals("exact", sent.getJSONObject("headers").getString("X-Caller"))
                    assertEquals("/aerove/route", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun outboundPreservesCallerUrlsRawBodyFailedHitsAndWinningNodeHits() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(outbound().toString())); server.enqueue(route())
            api(server).use { client ->
                val session = session(client)
                try {
                    val result = protocolMap(session.invoke("outboundIp", outboundArgs()), "result")
                    assertEquals(url, result["url"]); assertEquals("ip=1.1.1.1\nextra=raw", result["body"])
                    assertEquals(listOf("Node", "Pick"), result["chains"]); assertEquals(0L, result["route-version"])
                    val hits = protocolList(result["hits"], "hits"); assertEquals(2, hits.size)
                    assertEquals("", protocolMap(hits[1], "failed")["exit"])
                    assertEquals("Node", protocolMap(protocolList(result["node-hits"], "node-hits").single(), "node")["proxy"])
                    val call = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("/aerove/outbound-ip?coreEpoch=7", call.path)
                    assertEquals("Bearer $secret", call.getHeader("Authorization"))
                    val sent = JSONObject(call.body.readUtf8())
                    assertEquals(JSONArray(outboundArgs()["urls"] as List<*>).toString(), sent.getJSONArray("urls").toString())
                    assertEquals(4321, sent.getInt("timeout")); assertEquals("Pick", sent.getString("proxy-name"))
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun nativeFailedAndTimeoutDtosRemainFailuresWithoutChangingTheirMeaning() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for (kind in listOf("failed", "timeout")) {
                val failed = probe().put("status-code", 0).put("delay", 0).put("body", "").put("url", url)
                    .put("chains", JSONArray()).put("rule", "").put("rule-payload", "").put("error", kind).put("message", "native $kind")
                server.enqueue(MockResponse().setBody(failed.toString())); server.enqueue(route())
                val empty = outbound().put("url", "").put("body", "").put("delay", 0).put("chains", JSONArray())
                    .put("node-hits", JSONArray()).put("error", kind)
                server.enqueue(MockResponse().setBody(empty.toString())); server.enqueue(route())
            }
            api(server).use { client ->
                val session = session(client)
                try { for (kind in listOf("failed", "timeout")) {
                    val failed = protocolMap(session.invoke("probe", probeArgs()), "probe")
                    assertEquals(kind, failed["error"]); assertEquals("native $kind", failed["message"]); assertEquals(0, failed["status-code"])
                    assertEquals(kind, protocolMap(session.invoke("outboundIp", outboundArgs()), "outbound")["error"])
                } } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun httpNativeAdmissionFailureKeepsCodeStatusAndBody() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"outbound_superseded","message":"new epoch","field":"owner"}"""))
            api(server).use { client ->
                val session = session(client)
                try {
                    val failure = assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("outboundIp", outboundArgs()) } }
                    assertEquals("outbound_superseded", failure.code); assertEquals(409, failure.details["status"])
                    assertEquals("owner", protocolMap(failure.details["native"], "native")["field"])
                    assertEquals(1, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun emptyCallerUrlsReachNativeAndExplicitFailureNeverFallsBackToFixedSources() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(outbound().put("url", "").put("body", "").put("delay", 0)
                .put("chains", JSONArray()).put("hits", JSONArray()).put("node-hits", JSONArray()).put("error", "failed").toString()))
            server.enqueue(route())
            api(server).use { client ->
                val session = session(client)
                try {
                    assertEquals("failed", protocolMap(session.invoke("outboundIp", mapOf("timeout" to -1)), "result")["error"])
                    val call = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("/aerove/outbound-ip?coreEpoch=7", call.path)
                    val body = JSONObject(call.body.readUtf8())
                    assertEquals(0, body.getJSONArray("urls").length()); assertEquals(-1, body.getInt("timeout")); assertEquals("", body.getString("proxy-name"))
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun replacedOwnerCannotDeliverACompletedProbeFromTheOldSession() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val current = AtomicBoolean(true)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/aerove/probe?")) {
                        current.set(false); MockResponse().setBody(probe().toString())
                    } else route()
            }
            api(server).use { client ->
                val session = session(client, current = current::get)
                try {
                    assertEquals("stale_session", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("probe", probeArgs()) } }.code)
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun invalidParamsRejectBeforeAnyHttpAndDefaultsReachNativeUnchanged() = runBlocking<Unit> {
        MockWebServer().use { server ->
            api(server).use { client ->
                val session = session(client)
                try {
                    val invalid = listOf(probeArgs() + ("timeout" to 60001), probeArgs() + ("timeout" to 1.5),
                        probeArgs() + ("max-body" to 8388609), probeArgs() + ("headers" to mapOf("bad header" to "x")),
                        probeArgs() + ("headers" to mapOf("a" to "x\r\ny")), probeArgs() + ("headers" to mapOf("a" to 1)),
                        probeArgs() + ("url" to "file:///tmp/private"), probeArgs() + ("unknown" to true))
                    for (args in invalid) assertEquals("bad_argument", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("probe", args) } }.code)
                    assertEquals("bad_argument", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("outboundIp", outboundArgs() + ("urls" to listOf(1))) } }.code)
                    assertEquals(0, server.requestCount)
                    server.enqueue(MockResponse().setBody(probe().put("body", "").put("hits", JSONArray().put(JSONObject().put("host", "caller.test:443").put("exit", "actual Node"))).toString()))
                    server.enqueue(route())
                    session.invoke("probe", mapOf("url" to url, "timeout" to 0))
                    val sent = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
                    assertEquals(0, sent.getInt("timeout")); assertEquals(0, sent.getInt("max-body")); assertEquals("", sent.getString("proxy-name"))
                    assertEquals(0, sent.getJSONObject("headers").length())
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun malformedOrUnattestedResultsFailClosedBeforeReadback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val invalid = listOf(probe().put("delay", 1.5), probe().put("core-epoch", 7), probe().put("route-version", "00"),
                probe().put("status-code", 0), probe().put("body", "body exceeds requested cap"), probe().put("error", "future-failure"),
                probe().put("hits", JSONArray()), probe().put("chains", JSONArray(listOf(1))),
                probe().put("hits", JSONArray().put(JSONObject().put("host", "caller.test:443").put("proxy", "other").put("exit", ""))))
            val duplicate = probe().toString().replaceFirst("\"delay\":27", "\"delay\":27,\"delay\":28")
            for (bad in invalid) server.enqueue(MockResponse().setBody(bad.toString()))
            server.enqueue(MockResponse().setBody(duplicate))
            api(server).use { client ->
                val session = session(client)
                try {
                    repeat(invalid.size + 1) { assertEquals("invalid_core_response", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("probe", probeArgs()) } }.code) }
                    assertEquals(invalid.size + 1, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun outboundWrongSourceOrNodeOwnershipAndChangedRouteAreRejected() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(outbound().put("url", "https://fixed-source.test/").toString()))
            server.enqueue(MockResponse().setBody(outbound().put("node-hits", JSONArray().put(JSONObject().put("host", "foreign.test:443").put("proxy", "Node").put("exit", ""))).toString()))
            server.enqueue(MockResponse().setBody(outbound().toString())); server.enqueue(route(version = "1"))
            server.enqueue(MockResponse().setBody(probe(epoch = "8").toString()))
            api(server).use { client ->
                val session = session(client)
                try {
                    repeat(2) { assertEquals("invalid_core_response", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("outboundIp", outboundArgs()) } }.code) }
                    assertEquals("route_superseded", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("outboundIp", outboundArgs()) } }.code)
                    assertEquals("stale_session", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("probe", probeArgs()) } }.code)
                    assertEquals(5, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun callerCancelAndSessionCloseJoinActualPrivateHttpFailureWithoutExtraSubscriptions() = runBlocking<Unit> {
        for (bodyStarted in listOf(false, true)) for (method in listOf("probe", "outboundIp")) MockWebServer().use { server ->
            val failed = AtomicBoolean(false)
            val reading = CompletableDeferred<Unit>()
            val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failed.set(true) }
                override fun responseBodyStart(call: Call) { reading.complete(Unit) }
            }).build()
            server.enqueue(if (bodyStarted) MockResponse().setBody("{").setHeader("Content-Length", "10000")
                else MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            api(server, transport).use { client ->
                val session = session(client)
                val task = async(start = CoroutineStart.UNDISPATCHED) { session.invoke(method, if (method == "probe") probeArgs() else outboundArgs()) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                if (bodyStarted) withTimeout(3000) { reading.await() }
                if (method == "probe") withTimeout(3000) { task.cancelAndJoin() }
                else { withTimeout(3000) { session.closeAndJoin() }; withTimeout(3000) { task.join() } }
                assertTrue(task.isCancelled); assertTrue("Actual HTTP failure callback must precede join", failed.get())
                session.closeAndJoin(); assertEquals(1, server.requestCount)
            }
        }
    }
}
