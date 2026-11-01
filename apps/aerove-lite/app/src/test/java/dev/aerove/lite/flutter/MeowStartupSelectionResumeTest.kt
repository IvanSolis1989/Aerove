package dev.aerove.lite.flutter

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.data.CoreApiException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowStartupSelectionResumeTest {
    private val secret = "0123456789abcdef0123456789abcdef"
    private fun group(type: String, current: String, fixed: String? = "", members: List<String> = listOf("A", "B", "C")) =
        JSONObject().put("type", type).put("current", current).put("fixed", fixed ?: JSONObject.NULL).put("members", JSONArray(members))
    private fun snapshot(groups: Map<String, JSONObject>, intent: String = "7", version: String = "10", epoch: String = "2",
        requested: Map<String, String> = emptyMap(), consumed: Map<String, JSONObject> = emptyMap(), graph: String = "19"): String =
        JSONObject().put("graph-token", graph).put("selection-intent", intent).put("requested", JSONObject(requested))
            .put("route", JSONObject().put("core-epoch", epoch).put("route-version", version)
                .put("picks", JSONObject(groups.mapValues { it.value.getString("current") })))
            .put("groups", JSONObject(groups)).put("startup-consumption", JSONObject(consumed)).toString()
    private fun consumption(selected: String, before: String, after: String) =
        JSONObject().put("selected", selected).put("fixedBefore", before).put("fixedAfter", after)
    private suspend fun apply(server: MockWebServer, requested: Map<String, String>, current: () -> Boolean = { true }): MeowSelectionReceipt =
        CoreApiClient(ControlEndpoint(server.port, secret)).use { MeowRuntimeSelection(it).apply(requested, current) }
    private fun response(source: String) = MockResponse().setBody(source)
    private fun MockWebServer.next(): RecordedRequest = checkNotNull(takeRequest(3, TimeUnit.SECONDS))

    @Test fun allThreeGroupsRestoreThroughOneExistingCasBatchAndReadback() = runBlocking {
        MockWebServer().use { server ->
            val requested = linkedMapOf("Pick" to "B", "Fast" to "B", "Fail" to "B", "removed-group" to "old")
            val before = mapOf("Pick" to group("Selector", "A", null), "Fast" to group("URLTest", "A"), "Fail" to group("Fallback", "A"))
            val after = mapOf("Pick" to group("Selector", "B", null), "Fast" to group("URLTest", "B", "B"), "Fail" to group("Fallback", "B", "B"))
            server.enqueue(response(snapshot(before)))
            server.enqueue(response(snapshot(after, "8", "11", requested = requested,
                consumed = mapOf("Fail" to consumption("B", "B", "B")))))
            server.enqueue(response(snapshot(after, "8", "11")))
            val receipt = apply(server, requested)
            assertEquals(mapOf("Pick" to "B", "Fast" to "B", "Fail" to "B"), receipt.selected)
            assertEquals(requested, receipt.requested)
            assertEquals("B", receipt.actual["Fast"]!!.fixed)
            assertEquals("B", receipt.startupConsumption["Fail"]!!.selected)
            val first = server.next(); val post = server.next(); val last = server.next()
            for (request in listOf(first, post, last)) {
                assertEquals("/aerove/selection-cache", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
            }
            assertEquals(listOf("GET", "POST", "GET"), listOf(first.method, post.method, last.method))
            val body = JSONObject(post.body.readUtf8())
            assertEquals(setOf("expectedCoreEpoch", "expectedRouteVersion", "expectedSelectionIntent", "expectedGraphToken", "requested"), body.keys().asSequence().toSet())
            assertEquals("2", body.getString("expectedCoreEpoch")); assertEquals("10", body.getString("expectedRouteVersion"))
            assertEquals("7", body.getString("expectedSelectionIntent")); assertEquals("19", body.getString("expectedGraphToken"))
            assertEquals(requested.keys, body.getJSONObject("requested").keys().asSequence().toSet())
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun fallbackConsumptionProofRetainsFirstCWhileCurrentReturnsToAutomaticA() = runBlocking {
        MockWebServer().use { server ->
            val requested = mapOf("Fail" to "B")
            val groups = mapOf("Fail" to group("Fallback", "A"))
            server.enqueue(response(snapshot(groups)))
            server.enqueue(response(snapshot(groups, "8", "11", requested = requested,
                consumed = mapOf("Fail" to consumption("C", "B", "")))))
            server.enqueue(response(snapshot(groups, "8", "11")))
            val receipt = apply(server, requested)
            assertEquals("C", receipt.startupConsumption["Fail"]!!.selected)
            assertEquals("B", receipt.startupConsumption["Fail"]!!.fixedBefore)
            assertEquals("", receipt.actual["Fail"]!!.fixed)
            assertEquals("A", receipt.selected["Fail"])
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun staleFallbackAndUrlTestCacheKeepTheirDistinctNativeSemantics() = runBlocking {
        MockWebServer().use { server ->
            val requested = mapOf("Fail" to "REMOVED", "Fast" to "REMOVED")
            val before = mapOf("Fail" to group("Fallback", "A"), "Fast" to group("URLTest", "B"))
            val after = mapOf("Fail" to group("Fallback", "A", "REMOVED"), "Fast" to group("URLTest", "B"))
            server.enqueue(response(snapshot(before)))
            server.enqueue(response(snapshot(after, "8", "11", requested = requested,
                consumed = mapOf("Fail" to consumption("A", "REMOVED", "REMOVED")))))
            server.enqueue(response(snapshot(after, "8", "11")))
            val receipt = apply(server, requested)
            assertEquals("REMOVED", receipt.actual["Fail"]!!.fixed)
            assertEquals("", receipt.actual["Fast"]!!.fixed)
            assertEquals("A", receipt.startupConsumption["Fail"]!!.selected)
            assertEquals(requested, receipt.requested)
        }
    }

    @Test fun wrongCasReceiptMissingConsumptionOrSupersededReadbackNeverAcknowledge() = runBlocking {
        val requested = mapOf("Fail" to "REMOVED")
        val before = mapOf("Fail" to group("Fallback", "A"))
        val after = mapOf("Fail" to group("Fallback", "A", "REMOVED"))
        val good = snapshot(after, "8", "11", requested = requested, consumed = mapOf("Fail" to consumption("A", "REMOVED", "REMOVED")))
        val bad = listOf(JSONObject(good).put("selection-intent", "7").toString(),
            JSONObject(good).put("graph-token", "20").toString(),
            JSONObject(good).put("requested", JSONObject(mapOf("Fail" to "B"))).toString(),
            JSONObject(good).put("startup-consumption", JSONObject()).toString(),
            JSONObject(good).put("startup-consumption", JSONObject(mapOf("Fail" to consumption("C", "REMOVED", "REMOVED")))).toString())
        for (result in bad) MockWebServer().use { server ->
            server.enqueue(response(snapshot(before))); server.enqueue(response(result)); server.enqueue(response(snapshot(after, "8", "11")))
            try { apply(server, requested); fail("Invalid receipt was acknowledged") }
            catch (expected: MeowConfigProtocolException) { assertTrue(expected.code in setOf("selection_error", "superseded")) }
        }
        MockWebServer().use { server ->
            server.enqueue(response(snapshot(before))); server.enqueue(response(good)); server.enqueue(response(snapshot(after, "9", "12")))
            try { apply(server, requested); fail("Newer owner was acknowledged as the old owner") }
            catch (expected: MeowConfigProtocolException) { assertEquals("superseded", expected.code) }
        }
    }

    @Test fun newerHostIntentAfterSnapshotPreventsThePost() = runBlocking {
        MockWebServer().use { server ->
            val current = AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    current.set(false)
                    return response(snapshot(mapOf("Fast" to group("URLTest", "A"))))
                }
            }
            try { apply(server, mapOf("Fast" to "B"), current::get); fail() }
            catch (expected: MeowConfigProtocolException) { assertEquals("superseded", expected.code) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun failedRestoreDoesNotRetryOrInventSuccessAndANewEpochCanRecover() = runBlocking {
        MockWebServer().use { server ->
            val requested = mapOf("Fast" to "B")
            val before = mapOf("Fast" to group("URLTest", "A"))
            val after = mapOf("Fast" to group("URLTest", "B", "B"))
            server.enqueue(response(snapshot(before)))
            server.enqueue(MockResponse().setResponseCode(409).setBody("{}"))
            try { apply(server, requested); fail() } catch (_: CoreApiException) { }
            assertEquals(2, server.requestCount)
            server.enqueue(response(snapshot(before, epoch = "3")))
            server.enqueue(response(snapshot(after, "8", "11", epoch = "3", requested = requested)))
            server.enqueue(response(snapshot(after, "8", "11", epoch = "3")))
            val receipt = apply(server, requested)
            assertEquals(3uL, receipt.coreEpoch); assertEquals("B", receipt.actual["Fast"]!!.fixed)
            assertEquals(5, server.requestCount)
        }
    }

    @Test fun cancellingStartupMutationJoinsTheExistingHttpCallbackBeforeReturning() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = OkHttpClient.Builder().retryOnConnectionFailure(false).eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }).build()
        MockWebServer().use { server ->
            server.enqueue(response(snapshot(mapOf("Fast" to group("URLTest", "A")))))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            CoreApiClient(ControlEndpoint(server.port, secret), transport).use { client ->
                val restoring = launch(Dispatchers.Default) { MeowRuntimeSelection(client).apply(mapOf("Fast" to "B")) { true } }
                assertEquals("GET", server.next().method); assertEquals("POST", server.next().method)
                restoring.cancel()
                try {
                    assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                    val joined = async { restoring.join(); true }
                    assertNull("Startup cancellation returned while its transport callback still owned work", withTimeoutOrNull(100) { joined.await() })
                    release.countDown()
                    assertTrue(withTimeout(3000) { joined.await() })
                    assertEquals(2, server.requestCount)
                } finally { release.countDown(); withTimeout(3000) { restoring.join() } }
            }
        }
    }
}
