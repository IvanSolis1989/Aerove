package dev.aerove.lite.vpn

import dev.aerove.lite.data.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceRefreshOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private var now = 1_700_000_000_000L
    private val runtime get() = File(temporary.root, "runtime")
    private val validator by lazy { CompatibilityValidator(NativeConfigValidator { null }, runtime) }
    private val body = "proxies:\n - {name: node, type: socks5, server: 127.0.0.1, port: 1080}\n"
    private fun repository(downloader: ResourceDownloader = ResourceDownloader()) = ProfileRepository(
        temporary.root, validator, clock = { now }, resources = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null }, downloader, clock = { now }))
    private suspend fun initial(repo: ProfileRepository): PreparedProfile {
        server.enqueue(MockResponse().setBody(body))
        val id = repo.importText("Main", """
            proxy-providers:
              remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml, interval: 60}
            proxy-groups: [{name: Auto, type: select, use: [remote]}]
            rules: ['MATCH,Auto']
        """.trimIndent()).id
        return repo.prepareForStart(id)
    }
    private fun endpoint(before: PreparedProfile) = CoreEndpoint(9000, "secret", resourceGeneration = before.generation)
    private fun manifest(prepared: PreparedProfile) = File(prepared.resourceDirectory!!, "manifest.json").readBytes()
    private fun index() = File(temporary.root, "profiles/index.json")
    private fun changed() = body.replace("name: node", "name: changed")
    private fun unexpected(): Nothing = throw AssertionError("Unexpected runtime transition")
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun notDueWaitsOnScheduleWithoutRebuildingGeneration() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        val immutable = manifest(before)
        var active = true
        refreshRuntimeResources(repo, before.profileId, endpoint(before), { active }, { _, _ -> unexpected() },
            { unexpected() }, { unexpected() }, { now }, { delay ->
                assertEquals(60_000L, delay)
                active = false
            })
        assertEquals(1, server.requestCount)
        assertEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
        assertArrayEquals(immutable, manifest(repo.refreshSchedule(before.profileId)))
    }

    @Test fun samePayloadAdvancesReceiptWithoutReloadOrReconnect() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        val immutable = manifest(before)
        now += 60_000
        server.enqueue(MockResponse().setBody(body).setHeader("Subscription-Userinfo", "upload=1; download=20; total=100"))
        var active = true
        var waits = 0
        refreshRuntimeResources(repo, before.profileId, endpoint(before), { active }, { _, _ -> unexpected() },
            { unexpected() }, { unexpected() }, { now }, { delay ->
                if (++waits == 1) assertEquals(0L, delay) else {
                    assertEquals(60_000L, delay)
                    active = false
                }
            })
        val after = repo.refreshSchedule(before.profileId)
        assertEquals(before.generation, after.generation)
        assertArrayEquals(immutable, manifest(after))
        assertEquals(now + 60_000, after.nextRefreshAt)
        val receipt = JSONObject(index().readText()).getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("resourceReceipts").getJSONObject("$.proxy-providers.remote")
        assertEquals(now, receipt.getLong("checkedAt"))
        assertTrue(receipt.getString("subscriptionUserInfo").contains("download=20"))
    }

    @Test fun changedGenerationReloadKeepsSameEndpointAndContinuesItsSchedule() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        var lineage = before.revision to before.generation
        val endpoint = CoreEndpoint(9000, "secret", resourceLineage = { lineage })
        now += 60_000
        server.enqueue(MockResponse().setBody(changed()))
        var active = true
        var waits = 0
        refreshRuntimeResources(repo, before.profileId, endpoint, { active }, { next, current ->
            assertTrue(current())
            assertNotEquals(before.generation, next.generation)
            assertTrue(next.resourceDirectory!!.walkTopDown().filter { it.isFile }.any { it.readText().contains("name: changed") })
            lineage = next.revision to next.generation
            true
        }, { unexpected() }, { unexpected() }, { now }, { delay ->
            if (++waits == 1) assertEquals(0L, delay) else {
                assertEquals(60_000L, delay)
                assertEquals(repo.refreshSchedule(before.profileId).generation, endpoint.resourceGeneration)
                active = false
            }
        })
        assertEquals(2, waits)
        assertNotEquals(before.generation, endpoint.resourceGeneration)
    }

    @Test fun unsupportedReloadReconnectsOnlyAfterRealGenerationChange() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        now += 60_000
        server.enqueue(MockResponse().setBody(changed()))
        var reconnected = false
        refreshRuntimeResources(repo, before.profileId, endpoint(before), { true }, { next, _ ->
            assertNotEquals(before.generation, next.generation)
            false
        }, { reconnected = true }, { unexpected() }, { now }, { assertEquals(0L, it) })
        assertTrue(reconnected)
        assertNotEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
    }

    @Test fun supersededOwnerCannotReconnectAfterRealRefresh() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        now += 60_000
        server.enqueue(MockResponse().setBody(changed()))
        var active = true
        refreshRuntimeResources(repo, before.profileId, endpoint(before), { active }, { next, _ ->
            assertNotEquals(before.generation, next.generation)
            active = false
            false
        }, { unexpected() }, { unexpected() }, { now }, { assertEquals(0L, it) })
        assertNotEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
    }

    @Test fun cancelledReloadBoundaryCannotReconnectCommittedResources() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        now += 60_000
        server.enqueue(MockResponse().setBody(changed()))
        val owner = launch {
            refreshRuntimeResources(repo, before.profileId, endpoint(before), { true }, { next, _ ->
                assertNotEquals(before.generation, next.generation)
                currentCoroutineContext().cancel()
                false
            }, { unexpected() }, { unexpected() }, { now }, { assertEquals(0L, it) })
        }
        owner.join()
        assertTrue(owner.isCancelled)
        assertNotEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
    }

    @Test fun failedProviderUsesCommittedTenSecondRetryThenInstallsNewBytes() = runBlocking<Unit> {
        val repo = repository()
        val before = initial(repo)
        now += 60_000
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(changed()))
        var failed = false
        var reconnected = false
        val waits = mutableListOf<Long>()
        refreshRuntimeResources(repo, before.profileId, endpoint(before), { true }, { next, _ ->
            assertNotEquals(before.generation, next.generation)
            false
        }, { reconnected = true }, {
            failed = true
        }, { now }, { delay ->
            waits += delay
            if (waits.size == 2) {
                assertEquals(10_000L, delay)
                assertEquals(now + 10_000, repo.refreshSchedule(before.profileId).nextRefreshAt)
                now += delay
            }
        })
        assertTrue(failed)
        assertTrue(reconnected)
        assertEquals(listOf(0L, 10_000L), waits)
        assertEquals(3, server.requestCount)
        assertNotEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
    }

    @Test fun stopCancelsAndJoinsDownloadWithoutPublishingGenerationOrReceipt() = runBlocking<Unit> {
        val received = CountDownLatch(1)
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { if (server.requestCount == 2) received.countDown() }
        }.build()
        val repo = repository(ResourceDownloader(client))
        val before = initial(repo)
        val committed = index().readBytes()
        val immutable = manifest(before)
        now += 60_000
        server.enqueue(MockResponse().setBody(changed()).setBodyDelay(3, TimeUnit.SECONDS))
        val owner = launch {
            refreshRuntimeResources(repo, before.profileId, endpoint(before), { true }, { _, _ -> unexpected() },
                { unexpected() }, { unexpected() }, { now }, { assertEquals(0L, it) })
        }
        assertTrue(withContext(Dispatchers.IO) { received.await(5, TimeUnit.SECONDS) })
        owner.cancelAndJoin()
        assertTrue(owner.isCompleted)
        assertArrayEquals(committed, index().readBytes())
        assertArrayEquals(immutable, manifest(repo.refreshSchedule(before.profileId)))
        assertEquals(before.generation, repo.refreshSchedule(before.profileId).generation)
    }
}
