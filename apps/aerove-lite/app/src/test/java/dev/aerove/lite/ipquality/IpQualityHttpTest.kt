package dev.aerove.lite.ipquality

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IpQualityHttpTest {
    @Test fun realHttpDecodesResponseAndPreservesRateLimitWithoutRetry() = runBlocking {
        MockWebServer().use { server -> IpQualityHttp().use { api ->
            server.enqueue(MockResponse().setBody("""{"ip":"1.1.1.1","type":"isp","aso":"Real ISP"}"""))
            assertEquals("Real ISP", api.fetch(IpQualitySource.IdentMe, "1.1.1.1", server.url("/json").toString()).quality.organization)
            assertTrue(server.takeRequest().getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
            server.enqueue(MockResponse().setResponseCode(429).setBody("secret must not be shown"))
            try { api.fetch(IpQualitySource.IpQuery, "1.1.1.1", server.url("/limited").toString()); fail("Expected rate limit") }
            catch (failure: IpQualitySourceException) {
                assertEquals(IpQualitySourceStatus.RateLimited, failure.status); assertFalse(failure.message!!.contains("secret"))
            }
            assertEquals(2, server.requestCount)
        } }
    }

    @Test fun cancellingAfterHeadersClosesRealBlockedBody() = runBlocking {
        MockWebServer().use { server -> IpQualityHttp().use { api ->
            server.enqueue(MockResponse().setBody("""{"ip":"1.1.1.1","type":"isp"}""").setBodyDelay(5, TimeUnit.SECONDS))
            val job = async(start = CoroutineStart.UNDISPATCHED) { api.fetch(IpQualitySource.IdentMe, "1.1.1.1", server.url("/slow").toString()) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); delay(30); job.cancel()
            try { withTimeout(1000) { job.await() }; fail("Expected cancellation") } catch (_: CancellationException) { }
        } }
    }

    @Test fun closeCancelsRealCallAndForbidsReuse() = runBlocking {
        MockWebServer().use { server ->
            val api = IpQualityHttp()
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
            val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { api.fetch(IpQualitySource.IdentMe, "1.1.1.1", server.url("/").toString()) } }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); delay(30); api.close()
            assertTrue(withTimeout(1000) { result.await() }.isFailure)
            try { api.fetch(IpQualitySource.IdentMe, "1.1.1.1", server.url("/").toString()); fail("Closed client reused") }
            catch (_: IllegalStateException) { }
        }
    }
}
