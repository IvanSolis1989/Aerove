package dev.aerove.lite.proxies

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ProxyPresentationDelayTest {
    @Test fun actualCoreDelayRequestsDeduplicatePersistResultsAndRecoverAfterCancellation() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            CoreApiClient(ControlEndpoint(server.port, "delay-fixture-secret-00000000000000")).use { core ->
                ProxyPresentationDelayTests(core, scope).use { feature ->
                    val target = ProxyDelayTarget("Node / A", "https://example.com/check")
                    server.enqueue(MockResponse().setBody("{\"delay\":42}"))
                    withTimeout(3000) { feature.start(listOf(target, target), 8000).join() }
                    assertEquals(1, server.requestCount)
                    assertEquals(42, feature.state.value.results[target])
                    assertTrue(feature.state.value.pending.isEmpty())
                    val request = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertTrue(request.path!!.startsWith("/proxies/Node%20%2F%20A/delay?"))
                    assertEquals("8000", request.requestUrl!!.queryParameter("timeout"))
                    assertEquals(target.url, request.requestUrl!!.queryParameter("url"))
                    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                    val pending = feature.start(listOf(target))
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                    feature.cancel(clearResults = true)
                    withTimeout(2000) { pending.join() }
                    assertTrue(pending.isCancelled)
                    assertTrue(feature.state.value.results.isEmpty())
                    server.enqueue(MockResponse().setBody("{\"delay\":7}"))
                    withTimeout(3000) { feature.start(listOf(target)).join() }
                    assertEquals(7, feature.state.value.results[target])
                    assertTrue(feature.state.value.running.isEmpty())
                }
            }
            scope.cancel()
        }
    }
}
