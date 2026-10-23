package dev.aerove.lite.data

import dev.aerove.lite.proxies.ProxyDelayTarget
import dev.aerove.lite.proxies.ProxyPresentationDelayTests
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class CoreProbeConcurrencyTest {
    @Test fun actualPrivateHttpTransportAdmitsTheStandardThirtyTwoProbePool() = runBlocking<Unit> {
        val entered = CountDownLatch(32)
        val release = CountDownLatch(1)
        val active = AtomicInteger(); val peak = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }; entered.countDown()
                try { check(release.await(10, TimeUnit.SECONDS)) { "Probe test release timed out" } }
                finally { active.decrementAndGet() }
                return MockResponse().setBody("{\"delay\":12}")
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = CoreApiClient(ControlEndpoint(server.port, "0123456789abcdef0123456789abcdef"))
        val probes = ProxyPresentationDelayTests(client, scope)
        try {
            val job = probes.start((0 until 32).map { ProxyDelayTarget("node$it", "https://www.gstatic.com/generate_204") })
            val allEntered = withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) }
            release.countDown()
            withTimeout(10_000) { job.join() }
            assertTrue("The real HTTP layer must not silently reduce the explicit 32-probe pool; peak=${peak.get()}", allEntered)
            assertEquals(32, peak.get())
            assertEquals(32, probes.state.value.results.size)
        } finally { release.countDown(); probes.close(); client.close(); scope.cancel(); server.close() }
    }
}
