package dev.aerove.lite.providers

import dev.aerove.lite.data.ControlEndpoint
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProviderManagementRulesTest {
    private val secret = "fixture-only-secret-00000000000000"
    private fun page(rules: String, count: Int, offset: Int, next: Int?, epoch: String = "42") =
        """{"rules":$rules,"ruleCount":$count,"offset":$offset,"nextOffset":$next,"complete":${next == null},"coreEpoch":"$epoch"}"""

    @Test fun actualPagedDumpRetainsWildcardAndLocksEpoch() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                server.enqueue(MockResponse().setBody(page("[\"+.example.com\",\"1.2.3.0/24\"]", 3, 0, 2)))
                server.enqueue(MockResponse().setBody(page("[\"DOMAIN,example.net\"]", 3, 2, null)))
                val rows = mutableListOf<String>()
                api.forEachRulePage("A / B", 2) { rows += it.rules }
                assertEquals(listOf("+.example.com", "1.2.3.0/24", "DOMAIN,example.net"), rows)
                val first = server.takeRequest(2, TimeUnit.SECONDS)!!
                val second = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("/providers/rules/A%20%2F%20B/rules?offset=0&limit=2", first.path)
                assertEquals("42", second.requestUrl!!.queryParameter("coreEpoch"))
                assertEquals("2", second.requestUrl!!.queryParameter("offset"))
                assertEquals("Bearer $secret", second.getHeader("Authorization"))
            }
        }
    }

    @Test fun defaultPageIsExplicitlyIncompleteAndPastEndIsEmpty() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                server.enqueue(MockResponse().setBody(page("[\"one\"]", 2, 0, 1)))
                assertFalse(api.rulePage("rules").complete)
                assertEquals("500", server.takeRequest().requestUrl!!.queryParameter("limit"))
                server.enqueue(MockResponse().setBody(page("[]", 2, 9, null)))
                assertTrue(api.rulePage("rules", 9).complete)
            }
        }
    }

    @Test fun conflictAndEpochOrCountChangeNeverProduceCompleteDump() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                for (second in listOf(MockResponse().setResponseCode(409).setBody("private $secret"),
                    MockResponse().setBody(page("[\"two\"]", 2, 1, null, "43")),
                    MockResponse().setBody(page("[\"two\"]", 3, 1, 2)))) {
                    server.enqueue(MockResponse().setBody(page("[\"one\"]", 2, 0, 1)))
                    server.enqueue(second)
                    try { api.previewRules("rules"); fail() }
                    catch (failure: RuleProviderChangedException) { assertFalse(failure.toString().contains(secret)) }
                }
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test fun malformedContinuationAndNonCanonicalEpochAreRejected() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                for (body in listOf(page("[\"one\"]", 2, 0, 0), page("[\"one\"]", 2, 0, null),
                    page("[\"one\"]", 1, 0, null, "042"), page("[2]", 1, 0, null))) {
                    server.enqueue(MockResponse().setBody(body))
                    try { api.rulePage("rules"); fail() } catch (_: Exception) { }
                }
                val before = server.requestCount
                try { api.rulePage("rules", coreEpoch = "18446744073709551616"); fail() } catch (_: IllegalArgumentException) { }
                assertEquals(before, server.requestCount)
            }
        }
    }

    @Test fun cancellationStopsNextPageWithoutRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                server.enqueue(MockResponse().setBody(page("[\"one\"]", 2, 0, 1)))
                server.enqueue(MockResponse().setHeadersDelay(2, TimeUnit.SECONDS).setBody(page("[\"two\"]", 2, 1, null)))
                val pending = async(Dispatchers.Default) { api.previewRules("rules") }
                server.takeRequest(2, TimeUnit.SECONDS)
                server.takeRequest(2, TimeUnit.SECONDS)
                pending.cancelAndJoin()
                assertTrue(pending.isCancelled)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun previewSessionKeepsPartialRowsExplicitAndStopsAfterChangedEpoch() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            ProviderManagementRepository(ControlEndpoint(server.port, secret)).use { api ->
                val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
                val preview = RuleProviderPreviewSession(api, scope)
                try {
                    server.enqueue(MockResponse().setBody(page("[\"one\"]", 2, 0, 1)))
                    preview.load("rules")
                    val partial = withTimeout(5000) { preview.state.first { !it.busy && it.ruleCount != null } }
                    assertFalse(partial.complete)
                    assertEquals(listOf("one"), partial.rules)
                    server.enqueue(MockResponse().setResponseCode(409))
                    preview.next()
                    val changed = withTimeout(5000) { preview.state.first { it.changed } }
                    assertEquals(partial.rules, changed.rules)
                    assertFalse(changed.complete)
                    preview.next()
                    assertEquals(2, server.requestCount)
                    server.enqueue(MockResponse().setBody(page("[\"replacement\"]", 1, 0, null, "43")))
                    preview.load("rules")
                    val refreshed = withTimeout(5000) { preview.state.first { it.complete } }
                    assertFalse(refreshed.changed)
                    assertEquals(listOf("replacement"), refreshed.rules)
                } finally { preview.close(); scope.cancel() }
            }
        }
    }
}
