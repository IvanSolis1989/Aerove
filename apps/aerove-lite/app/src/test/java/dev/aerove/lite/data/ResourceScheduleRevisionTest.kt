package dev.aerove.lite.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceScheduleRevisionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val origin = 1_700_000_000_000L
    private var now = origin
    private val runtime get() = File(temporary.root, "runtime")
    private val validator by lazy { CompatibilityValidator(NativeConfigValidator { null }, runtime) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val payload = "payload: ['+.example.test']"
    private fun repository(client: OkHttpClient = OkHttpClient()) = ProfileRepository(temporary.root, validator,
        clock = { now }, resources = ResourceBundleManager(runtime, validator, NativeResourceValidator { _, _, _, _ -> null },
            ResourceDownloader(client), clock = { now }))
    private fun index() = File(temporary.root, "profiles/index.json")
    private suspend fun initial(repo: ProfileRepository): String {
        server.enqueue(MockResponse().setBody(payload))
        return repo.importText("Main", """
            rule-providers:
              remote: {type: http, url: '${server.url("/rules")}', path: rules/cache.yaml, behavior: domain, interval: 60}
            rules: ['RULE-SET,remote,DIRECT', 'MATCH,DIRECT']
        """.trimIndent()).id.also { repo.prepareForStart(it) }
    }
    @Before fun start() { server.start() }
    @After fun stop() = runBlocking<Unit> { scope.coroutineContext[Job]!!.cancelAndJoin(); server.shutdown() }

    @Test fun revisionIsPublishedAfterClearingRetryWithoutWritingOrDownloading() = runBlocking<Unit> {
        val repo = repository()
        val facade = ProfileMaintenanceFacade(repo, scope)
        try {
            val id = initial(repo)
            now = origin + 60_000
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
            val beforeReset = index().readBytes()
            val revision: StateFlow<Long> = repo.resourceScheduleRevision
            assertEquals(0L, revision.value)
            val observed = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                revision.drop(1).first()
                repo.refreshSchedule(id).nextRefreshAt
            }
            facade.resetAutomaticBackoff()
            assertEquals(1L, revision.value)
            assertEquals(origin + 60_000, withTimeout(5000) { observed.await() })
            assertArrayEquals(beforeReset, index().readBytes())
            assertEquals(2, server.requestCount)
            facade.resetAutomaticBackoff()
            assertEquals(2L, revision.value)
            assertEquals(2, server.requestCount)
        } finally { facade.close() }
    }

    private suspend fun staleResponseCannotSurviveReset(status: Int) = coroutineScope {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also {
                if (server.requestCount == 3) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }.build()
        val repo = repository(client)
        val facade = ProfileMaintenanceFacade(repo, scope)
        var update: Deferred<Boolean>? = null
        try {
            val id = initial(repo)
            now = origin + 60_000
            server.enqueue(MockResponse().setResponseCode(503))
            assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
            val committed = index().readBytes()
            now = origin + 70_000
            server.enqueue(MockResponse().setResponseCode(status).setBody("payload: ['+.changed.test']")
                .setHeader("Subscription-Userinfo", "upload=1; download=30; total=100"))
            update = async(Dispatchers.Default) {
                try { repo.refreshIfDue(id); false } catch (_: CancellationException) { true }
            }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            facade.resetAutomaticBackoff()
            release.countDown()
            assertTrue(withTimeout(5000) { checkNotNull(update).await() })
            assertArrayEquals(committed, index().readBytes())
            assertEquals(1, File(runtime, "resources/bundles/$id").listFiles()!!.size)
            assertEquals(origin + 60_000, repo.refreshSchedule(id).nextRefreshAt)
            server.enqueue(MockResponse().setBody(payload))
            assertNull(repo.refreshIfDue(id))
            assertEquals(origin + 130_000, repository().refreshSchedule(id).nextRefreshAt)
            assertEquals(4, server.requestCount)
        } finally { release.countDown(); update?.cancelAndJoin(); facade.close() }
    }

    @Test fun successFromOldRevisionIsCancelledBeforeCommit() = runBlocking<Unit> { staleResponseCannotSurviveReset(200) }
    @Test fun failureFromOldRevisionDoesNotRecreateClearedBackoff() = runBlocking<Unit> { staleResponseCannotSurviveReset(503) }
}
