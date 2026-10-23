package dev.aerove.lite.providers

import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.profilebuilder.BuilderFilter
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResources
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderManagementBarrierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val secret = "fixture-only-secret-00000000000000"
    private val proxies = """{"providers":{"fixture":{"vehicleType":"File","proxies":[]}}}"""
    private val rules = """{"providers":{}}"""
    private fun page(text: String) = """{"rules":["$text"],"ruleCount":1,"offset":0,"nextOffset":null,"complete":true,"coreEpoch":"7"}"""

    @Test fun cancelledProviderCallbackStaysOwnedUntilMaintenanceJoinAndRollbackResumeWorks() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val library = BuilderResourceLibrary(temporary.root)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val api = ProviderManagementRepository(ControlEndpoint(server.port, secret))
            val session = ProviderManagementSession(api, scope) {
                entered.complete(Unit)
                withContext(NonCancellable) {
                    release.await()
                    library.replace(library.snapshot(), BuilderResources(filters = listOf(BuilderFilter("late-provider", ".*"))))
                }
            }
            try {
                server.enqueue(MockResponse().setResponseCode(202)); server.enqueue(MockResponse().setBody(proxies))
                session.update(ProviderKind.Proxy, "fixture"); withTimeout(5000) { entered.await() }
                session.cancel()
                server.enqueue(MockResponse().setBody(proxies)); server.enqueue(MockResponse().setBody(rules))
                session.load(); withTimeout(5000) { session.state.first { it.providers.isNotEmpty() && it.busy.isEmpty() } }
                assertFalse(release.isCompleted)
                val paused = async { session.quiesce() }
                yield(); assertFalse(paused.isCompleted)
                session.load(); assertEquals(4, server.requestCount)
                release.complete(Unit); withTimeout(5000) { paused.await() }
                library.replace(library.snapshot(), BuilderResources())
                assertTrue(library.snapshot().resources.filters.isEmpty())
                session.resumeAfterMaintenance()
                server.enqueue(MockResponse().setBody(proxies)); server.enqueue(MockResponse().setBody(rules))
                session.load(); withTimeout(5000) { session.state.first { it.providers.isNotEmpty() && it.busy.isEmpty() } }
                assertEquals(6, server.requestCount)
            } finally { release.complete(Unit); session.closeAndJoin(); scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun previewImmediateRetryIsNotBlockedByOldResponseCloseButMaintenanceJoinsIt() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val closeEntered = CountDownLatch(1); val releaseClose = CountDownLatch(1)
            val responses = AtomicInteger(); val blocked = AtomicBoolean()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (responses.incrementAndGet() != 1) response else {
                    val original = checkNotNull(response.body)
                    val source = object : ForwardingSource(original.source()) {
                        override fun close() {
                            super.close()
                            if (blocked.compareAndSet(false, true)) { closeEntered.countDown(); releaseClose.await() }
                        }
                    }.buffer()
                    response.newBuilder().body(object : ResponseBody() {
                        override fun contentType() = original.contentType()
                        override fun contentLength() = original.contentLength()
                        override fun source() = source
                    }).build()
                }
            }.build()
            val api = ProviderManagementRepository(ControlEndpoint(server.port, secret), client)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val preview = RuleProviderPreviewSession(api, scope)
            try {
                server.enqueue(MockResponse().setBody(page("old")))
                preview.load("rules")
                assertTrue(withContext(Dispatchers.IO) { closeEntered.await(5, TimeUnit.SECONDS) })
                preview.cancel()
                server.enqueue(MockResponse().setBody(page("new")))
                preview.load("rules")
                withTimeout(5000) { preview.state.first { it.rules == listOf("new") && !it.busy } }
                assertEquals(1L, releaseClose.count)
                val paused = async { preview.quiesce() }
                yield(); assertFalse(paused.isCompleted)
                val before = preview.state.value
                preview.load("must-not-start"); assertEquals(before, preview.state.value)
                assertEquals(2, server.requestCount)
                releaseClose.countDown(); withTimeout(5000) { paused.await() }
                assertEquals(listOf("new"), preview.state.value.rules)
                preview.resumeAfterMaintenance()
                server.enqueue(MockResponse().setBody(page("again")))
                preview.load("rules"); withTimeout(5000) { preview.state.first { it.rules == listOf("again") && !it.busy } }
                assertEquals(3, server.requestCount)
            } finally {
                releaseClose.countDown(); preview.closeAndJoin(); api.close(); scope.coroutineContext[Job]!!.cancelAndJoin()
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            }
        }
    }
}
