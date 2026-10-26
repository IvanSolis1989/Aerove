package dev.aerove.lite.vpn

import dev.aerove.lite.data.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class ResourcePastDeadlineOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = AtomicLong(1_700_000_000_000L)
    private val server = MockWebServer()
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }
    private fun repository(policy: ProfileSourcePolicy = ProfileSourcePolicy.identity): ProfileRepository {
        val runtime = File(temporary.root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        return ProfileRepository(temporary.root, validator, clock = now::get, sourcePolicy = policy,
            resources = ResourceBundleManager(runtime, validator, NativeResourceValidator { _, _, _, _ -> null }, clock = now::get))
    }
    private fun body(name: String) = "proxies:\n - {name: $name, type: socks5, server: 127.0.0.1, port: 1080}\n"
    private fun source() = """
        proxy-providers:
          a: {type: http, url: '${server.url("/a")}', path: providers/a.yaml, interval: 60}
          b: {type: http, url: '${server.url("/b")}', path: providers/b.yaml, interval: 60}
        proxy-groups: [{name: Auto, type: select, use: [a, b]}]
        rules: ['MATCH,Auto']
    """.trimIndent()

    @Test fun healthyKeyAlreadyDueAfterSlowFailureIsServicedWithoutResetOrReconnect() = runBlocking<Unit> {
        val refreshing = AtomicBoolean()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (refreshing.get() && request.path == "/b") {
                    now.addAndGet(70_000)
                    return MockResponse().setResponseCode(503)
                }
                return MockResponse().setBody(body(if (request.path == "/a") "alpha" else "beta"))
            }
        }
        val repo = repository()
        val id = repo.importText("Main", source()).id
        val before = repo.prepareForStart(id)
        val immutable = File(before.resourceDirectory!!, "manifest.json").readBytes()
        assertEquals(2, server.requestCount)
        now.addAndGet(60_000)
        refreshing.set(true)
        var active = true
        val waits = mutableListOf<Long>()
        var errors = 0
        refreshRuntimeResources(repo, id, CoreEndpoint(9000, "secret", resourceGeneration = before.generation), { active },
            { _, _ -> throw AssertionError("Same content must not apply") },
            { throw AssertionError("Same content must not reconnect") }, { errors++ }, now::get, { delay ->
                waits += delay
                if (waits.size == 2) {
                    assertEquals(0L, delay)
                    assertTrue(checkNotNull(repo.refreshSchedule(id).nextRefreshAt) < now.get())
                }
                if (waits.size == 3) {
                    assertEquals(10_000L, delay)
                    active = false
                }
            })
        assertEquals(listOf(0L, 0L, 10_000L), waits)
        assertEquals(1, errors)
        assertEquals(5, server.requestCount)
        assertEquals(before.generation, repo.refreshSchedule(id).generation)
        assertArrayEquals(immutable, File(repo.refreshSchedule(id).resourceDirectory!!, "manifest.json").readBytes())
        assertEquals(now.get(), repo.resourceChecks(repo.refreshSchedule(id)).checkedAt["$.proxy-providers.a"])
        assertEquals(now.get() + 10_000, repo.refreshSchedule(id).nextRefreshAt)
        assertEquals(0L, repo.resourceScheduleRevision.value)
    }

    @Test fun nonAdvancingPolicyFailureKeepsErrorAndCannotBusyRetrySamePastSchedule() = runBlocking<Unit> {
        var broken = false
        var failedPolicyReads = 0
        val policy = object : ProfileSourcePolicy {
            override suspend fun forStart(profileId: String, logicalOriginalSource: String): ProfileSourceTransform {
                if (broken) { failedPolicyReads++; error("Invalid profile policy") }
                return ProfileSourcePolicy.identity.forStart(profileId, logicalOriginalSource)
            }
        }
        server.enqueue(MockResponse().setBody(body("alpha")))
        server.enqueue(MockResponse().setBody(body("beta")))
        val repo = repository(policy)
        val id = repo.importText("Main", source()).id
        val before = repo.prepareForStart(id)
        now.addAndGet(60_000)
        broken = true
        val errors = mutableListOf<String?>()
        refreshRuntimeResources(repo, id, CoreEndpoint(9000, "secret", resourceGeneration = before.generation), { true },
            { _, _ -> throw AssertionError("No install") }, { throw AssertionError("No reconnect") },
            { errors += it.message }, now::get, { assertEquals(0L, it) })
        assertEquals(listOf("Invalid profile policy"), errors)
        assertEquals(1, failedPolicyReads)
        assertEquals(2, server.requestCount)
        assertEquals(now.get(), repo.refreshSchedule(id).nextRefreshAt)
        assertEquals(before.generation, repo.refreshSchedule(id).generation)
    }
}
