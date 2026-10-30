package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.ServiceTarget
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowServiceCheckTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun CoroutineScope.session(client: CoreApiClient, current: () -> Boolean = { true }, epoch: ULong = 7uL) =
        MeowProtocolSession(client, "actual-owner", 4, epoch, this, current, {}, { throw AssertionError(it) }, null, false)

    private fun arguments(names: List<String> = listOf("google"), proxy: String = "", timeout: Int = 10_000): Map<String, Any?> =
        mapOf("names" to names, "proxy-name" to proxy, "timeout" to timeout)

    private fun row(name: String, epoch: String = "7", version: String = "12", status: String = "available") =
        """{"name":"$name","status":"$status","region":"HK","delay":37,"chains":["Node","Pick"],"checked-at":1790000000000,"core-epoch":$epoch,"route-version":$version,"hits":[{"host":"$name.example","proxy":"Node","exit":"Node"}]}"""

    private fun MockWebServer.checks(vararg names: String, epoch: String = "7", version: String = "12") {
        enqueue(MockResponse().setBody(names.joinToString(",", "[", "]") { row(it, epoch, version) }))
    }

    private fun MockWebServer.route(epoch: String = "7", version: String = "12") {
        enqueue(MockResponse().setBody("""{"core-epoch":"$epoch","route-version":"$version","picks":{"Pick":"Node"}}"""))
    }

    @Test fun actualPostPreservesSharedArgumentsAndReturnsTheCompleteSharedDto() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.checks("netflix", "google"); server.route()
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    val rows = protocolList(session.invoke("serviceCheck", arguments(listOf("netflix", "google"), "HK / 東京 & \"pick\"", 60_000)), "checks")
                    assertEquals(listOf("netflix", "google"), rows.map { protocolMap(it, "check")["name"] })
                    val first = protocolMap(rows.first(), "check")
                    assertEquals(setOf("name", "status", "region", "delay", "chains", "checked-at", "core-epoch", "route-version", "hits"), first.keys)
                    assertEquals("available", first["status"]); assertEquals("HK", first["region"])
                    assertEquals(37L, first["delay"]); assertEquals(1790000000000L, first["checked-at"])
                    assertEquals(7L, first["core-epoch"]); assertEquals(12L, first["route-version"])
                    assertEquals(listOf("Node", "Pick"), first["chains"])
                    assertEquals(listOf(mapOf("host" to "netflix.example", "proxy" to "Node", "exit" to "Node")), first["hits"])
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("POST", request.method); assertEquals("/aerove/service-check", request.path)
                    assertEquals("Bearer $secret", request.getHeader("Authorization"))
                    val body = JSONObject(request.body.readUtf8())
                    assertEquals("HK / 東京 & \"pick\"", body.getString("proxy-name"))
                    assertEquals(60_000, body.getInt("timeout"))
                    assertEquals(listOf("netflix", "google"), (0 until body.getJSONArray("names").length()).map { body.getJSONArray("names").getString(it) })
                    assertEquals("/aerove/route", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun emptyNamesRunsAllRegisteredServicesWithTheNativeDefaultTimeout() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val names = ServiceTarget.entries.map { it.id }
            server.checks(*names.toTypedArray()); server.route()
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    assertEquals(names, protocolList(session.invoke("serviceCheck", arguments(emptyList(), timeout = 0)), "checks").map { protocolMap(it, "check")["name"] })
                    val body = JSONObject(server.takeRequest().body.readUtf8())
                    assertEquals(names, (0 until body.getJSONArray("names").length()).map { body.getJSONArray("names").getString(it) })
                    assertEquals(10_000, body.getInt("timeout"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun standardUnknownNameSelectionAndRepeatedNamesDoNotInventMeasurements() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.checks("google", "netflix", "google"); server.route()
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    val rows = protocolList(session.invoke("serviceCheck", arguments(listOf("missing", "google", "netflix", "google"))), "checks")
                    assertEquals(listOf("google", "netflix", "google"), rows.map { protocolMap(it, "check")["name"] })
                    assertEquals(2, server.requestCount)
                    assertTrue(protocolList(session.invoke("serviceCheck", arguments(listOf("missing"))), "checks").isEmpty())
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun anOlderDuplicateRequestCannotReclaimAndCancelTheNewerMeasurement() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val olderEntered = CountDownLatch(1)
            val newerEntered = CountDownLatch(1)
            val releaseNewer = CountDownLatch(1)
            val newerCancelled = AtomicBoolean(false)
            val claims = AtomicInteger()
            // Model the fixed Native admission contract at the real HTTP boundary, including its 409.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/aerove/route") return MockResponse().setBody(
                        """{"core-epoch":"7","route-version":"12","picks":{"Pick":"Node"}}""")
                    val names = JSONObject(request.body.readUtf8()).getJSONArray("names")
                    return when (claims.incrementAndGet()) {
                        1 -> {
                            olderEntered.countDown()
                            check(newerEntered.await(4, TimeUnit.SECONDS))
                            MockResponse().setBody((0 until names.length()).joinToString(",", "[", "]") {
                                val name = names.getString(it)
                                row(name, status = if (name == "google") "failed" else "available")
                            })
                        }
                        2 -> {
                            newerEntered.countDown()
                            check(releaseNewer.await(4, TimeUnit.SECONDS))
                            if (newerCancelled.get()) MockResponse().setResponseCode(409)
                                .setBody("""{"error":"service_check_superseded"}""")
                            else MockResponse().setBody("[${row("google")}]")
                        }
                        else -> {
                            newerCancelled.set(true)
                            MockResponse().setBody("[${row("google")}]")
                        }
                    }
                }
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    val older = async { runCatching { session.invoke("serviceCheck", arguments(listOf("google", "github", "google"))) } }
                    assertTrue(withContext(Dispatchers.IO) { olderEntered.await(4, TimeUnit.SECONDS) })
                    val newer = async { runCatching { session.invoke("serviceCheck", arguments()) } }
                    val oldResult = withTimeout(6000) { older.await() }
                    releaseNewer.countDown()
                    val newRows = protocolList(withTimeout(6000) { newer.await() }.getOrThrow(), "newer checks")
                    assertEquals("available", protocolMap(newRows.single(), "newer check")["status"])
                    val oldRows = protocolList(oldResult.getOrThrow(), "older checks")
                    assertEquals(listOf("failed", "available", "failed"), oldRows.map { protocolMap(it, "older check")["status"] })
                    assertFalse(newerCancelled.get())
                } finally { releaseNewer.countDown(); session.closeAndJoin() }
            }
        }
    }

    @Test fun repeatedItemsBeyondTheKnownKindCountKeepTheirOriginalMeasurements() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val names = List(15) { "google" }
            server.enqueue(MockResponse().setBody(names.mapIndexed { index, name ->
                row(name).replace("\"delay\":37", "\"delay\":$index")
            }.joinToString(",", "[", "]")))
            server.route()
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    val rows = protocolList(session.invoke("serviceCheck", arguments(names)), "checks")
                    assertEquals((0L..14L).toList(), rows.map { protocolMap(it, "check")["delay"] })
                    val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("names")
                    assertEquals(names, (0 until sent.length()).map(sent::getString))
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun escapedBodyBudgetIsAFieldArgumentErrorBeforeHttp() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val proxy = "\"".repeat(4096)
            val actualBody = JSONObject(arguments(proxy = proxy)).toString().toByteArray(Charsets.UTF_8)
            assertEquals(4096, proxy.toByteArray(Charsets.UTF_8).size)
            assertEquals(8244, actualBody.size)
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    for (tooLarge in listOf(proxy, "\"".repeat(4070) + "a")) {
                        val failure = assertThrows(MeowQueryException::class.java) {
                            runBlocking { session.invoke("serviceCheck", arguments(proxy = tooLarge)) }
                        }
                        assertEquals("bad_argument", failure.code)
                        assertEquals("arguments", failure.details["field"])
                        assertTrue(failure.message!!.contains("8192"))
                    }
                    assertEquals(0, server.requestCount)
                    server.checks("google"); server.route()
                    val boundary = "\"".repeat(4070)
                    val rows = protocolList(session.invoke("serviceCheck", arguments(proxy = boundary)), "checks")
                    assertEquals("available", protocolMap(rows.single(), "check")["status"])
                    assertEquals(8192L, server.takeRequest().body.size)
                    assertEquals(2, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun exactUnsignedReceiptsSurviveTheSharedJsonProjection() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val maximum = "18446744073709551615"
            server.checks("google", epoch = maximum, version = maximum); server.route(maximum, maximum)
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client, epoch = ULong.MAX_VALUE)
                try {
                    val row = protocolMap(protocolList(session.invoke("serviceCheck", arguments()), "checks").single(), "check")
                    assertEquals(BigInteger(maximum), row["core-epoch"])
                    assertEquals(BigInteger(maximum), row["route-version"])
                    assertTrue(JSONObject(row).toString().contains("\"core-epoch\":$maximum"))
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun nativeFailureCodesAndStatusAreNotReplacedWithEmptySuccess() = runBlocking<Unit> {
        MockWebServer().use { server ->
            for ((status, code) in listOf(409 to "service_check_superseded", 503 to "session_stopped", 400 to "invalid_service_parameters")) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$code"}"""))
            }
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    for ((status, code) in listOf(409 to "service_check_superseded", 503 to "session_stopped", 400 to "invalid_service_parameters")) {
                        val failure = assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("serviceCheck", arguments()) } }
                        assertEquals(code, failure.code); assertEquals(status, failure.details["status"])
                    }
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun invalidArgumentsAreRejectedBeforeAnyHttpRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    for (input in listOf(arguments(timeout = 60_001), arguments() + ("extra" to true),
                        arguments() + ("names" to listOf(3)), arguments(proxy = "bad\nproxy"))) {
                        assertEquals("bad_argument", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("serviceCheck", input) } }.code)
                    }
                    assertEquals(0, server.requestCount)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun staleEpochOrChangedRouteCannotPublishServiceResults() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.checks("google", epoch = "8")
            server.checks("google"); server.route(version = "13")
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    assertEquals("stale_session", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("serviceCheck", arguments()) } }.code)
                    assertEquals("route_superseded", assertThrows(MeowQueryException::class.java) { runBlocking { session.invoke("serviceCheck", arguments()) } }.code)
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun malformedOrMissingNativeChecksFailInsteadOfBecomingAnEmptyArray() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[{}]"))
            CoreApiClient(ControlEndpoint(server.port, secret)).use { client ->
                val session = session(client)
                try {
                    repeat(2) {
                        val failure = assertThrows(IOException::class.java) { runBlocking { session.invoke("serviceCheck", arguments()) } }
                        assertEquals(IOException::class.java, failure.javaClass)
                    }
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun sessionRetirementCancelsAndJoinsTheActualServiceHttpRead() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val ended = CompletableDeferred<Unit>()
            val transport = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { ended.complete(Unit) }
            }).build()
            CoreApiClient(ControlEndpoint(server.port, secret), transport).use { client ->
                val session = session(client)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { session.invoke("serviceCheck", arguments()) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
                withTimeout(4000) { session.closeAndJoin() }
                assertTrue(ended.isCompleted)
                withTimeout(4000) { pending.join() }
                assertTrue(pending.isCancelled)
                assertThrows(kotlinx.coroutines.CancellationException::class.java) { runBlocking { pending.await() } }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
