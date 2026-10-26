package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceScheduleDeadlineResetTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val origin = 1_700_000_000_000L
    private var now = origin
    private val runtime get() = File(temporary.root, "runtime")
    private val validator by lazy { CompatibilityValidator(NativeConfigValidator { null }, runtime) }
    private fun repository() = ProfileRepository(temporary.root, validator, clock = { now }, resources = ResourceBundleManager(
        runtime, validator, NativeResourceValidator { _, _, _, _ -> null }, clock = { now }))
    private val repo by lazy { repository() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val facade by lazy { ProfileMaintenanceFacade(repo, scope) }
    private val payload = "payload: ['+.example.test']"
    private fun index() = File(temporary.root, "profiles/index.json")
    private fun storedNext() = JSONObject(index().readText()).getJSONArray("profiles").getJSONObject(0).getLong("nextRefreshAt")
    private suspend fun initial(): String {
        server.enqueue(MockResponse().setBody(payload))
        return repo.importText("Main", """
            rule-providers:
              remote: {type: http, url: '${server.url("/rules")}', path: rules/cache.yaml, behavior: domain, interval: 60}
            rules: ['RULE-SET,remote,DIRECT', 'MATCH,DIRECT']
        """.trimIndent()).id.also { assertEquals(origin + 60_000, repo.prepareForStart(it).nextRefreshAt) }
    }
    private suspend fun assertAllReadPaths(id: String, expected: Long) {
        assertEquals(expected, repo.refreshSchedule(id).nextRefreshAt)
        assertEquals(expected, repo.prepare(id).nextRefreshAt)
        assertEquals(expected, repo.prepareForStart(id).nextRefreshAt)
    }
    @Before fun start() { server.start() }
    @After fun stop() = runBlocking<Unit> { facade.close(); scope.coroutineContext[Job]!!.cancelAndJoin(); server.shutdown() }

    @Test fun resetAtSecondSixtyOneRemovesRetryButKeepsHealthyNormalDeadline() = runBlocking<Unit> {
        val id = initial()
        now = origin + 60_000
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
        assertAllReadPaths(id, origin + 70_000)
        now = origin + 61_000
        val beforeReset = index().readBytes()
        facade.resetAutomaticBackoff()
        assertArrayEquals(beforeReset, index().readBytes())
        assertAllReadPaths(id, origin + 60_000)
        assertEquals(origin + 60_000, repository().refreshSchedule(id).nextRefreshAt)
        assertEquals(2, server.requestCount)
        server.enqueue(MockResponse().setBody(payload))
        assertNull(repo.refreshIfDue(id))
        assertAllReadPaths(id, origin + 121_000)
        now = origin + 62_000
        facade.resetAutomaticBackoff()
        assertAllReadPaths(id, origin + 121_000)
        assertEquals(origin + 121_000, repository().refreshSchedule(id).nextRefreshAt)
        assertNull(repo.refreshIfDue(id))
        assertEquals(3, server.requestCount)
    }

    @Test fun retryIsMemoryOnlyAndDoublesWithoutChangingNormalInterval() = runBlocking<Unit> {
        val id = initial()
        now = origin + 60_000
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
        assertEquals(origin + 60_000, storedNext())
        now = origin + 61_000
        assertEquals(origin + 70_000, repo.refreshSchedule(id).nextRefreshAt)
        assertEquals(origin + 60_000, repository().refreshSchedule(id).nextRefreshAt)
        assertNull(repo.refreshIfDue(id))
        assertEquals(2, server.requestCount)
        now = origin + 70_000
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
        assertAllReadPaths(id, origin + 90_000)
        assertEquals(origin + 60_000, storedNext())
        now = origin + 71_000
        facade.resetAutomaticBackoff()
        assertAllReadPaths(id, origin + 60_000)
        server.enqueue(MockResponse().setBody(payload))
        assertNull(repo.refreshIfDue(id))
        assertAllReadPaths(id, origin + 131_000)
        assertEquals(4, server.requestCount)
    }
}
