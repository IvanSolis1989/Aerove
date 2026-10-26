package dev.aerove.lite.data

import dev.aerove.lite.resources.ResourceManagementRepository
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

class ResourceRefreshReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private var now = 1_700_000_000_000L
    private val runtime get() = File(temporary.root, "runtime")
    private val validator by lazy { CompatibilityValidator(NativeConfigValidator { null }, runtime) }
    private val key = "$.proxy-providers.remote"
    private val body = "proxies:\n - {name: node, type: socks5, server: 127.0.0.1, port: 1080}\n"
    private fun repository(downloader: ResourceDownloader = ResourceDownloader()): ProfileRepository = ProfileRepository(
        temporary.root, validator, clock = { now }, resources = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null }, downloader, clock = { now }))
    private fun source() = """
        proxy-providers:
          remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml, interval: 60}
        proxy-groups: [{name: Auto, type: select, use: [remote]}]
        rules: ['MATCH,Auto']
    """.trimIndent()
    private fun response(download: Int?, payload: String = body) = MockResponse().setBody(payload).apply {
        download?.let { addHeader("Subscription-Userinfo", "upload=1; download=$it; total=100") }
    }
    private suspend fun usage(repo: ProfileRepository, id: String) =
        ResourceManagementRepository(repo).snapshot(id).entries.single { it.key == key }
    private fun manifest(prepared: PreparedProfile) = File(prepared.resourceDirectory!!, "manifest.json")
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun samePayloadRetainsNewUsageInMutableReceiptWithoutChangingGenerationOrManifest() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(response(2).setHeader("ETag", "v1").setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT"))
        val id = repo.importText("Main", source()).id
        val before = repo.prepareForStart(id)
        val immutable = manifest(before).readBytes()
        assertEquals(2L, usage(repo, id).subscriptionInfo!!.download)
        now += 60_000
        server.enqueue(response(20).setHeader("ETag", "v2").setHeader("Last-Modified", "Thu, 22 Oct 2026 07:28:00 GMT"))
        assertNull(repo.refreshIfDue(id))
        val after = repo.refreshSchedule(id)
        assertEquals(2, server.requestCount)
        assertEquals(before.generation, after.generation)
        assertArrayEquals(immutable, manifest(after).readBytes())
        assertEquals(20L, usage(repo, id).subscriptionInfo!!.download)
        assertEquals(now, usage(repo, id).updatedAt)
        assertEquals(20L, usage(repository(), id).subscriptionInfo!!.download)
        val row = JSONObject(File(temporary.root, "profiles/index.json").readText()).getJSONArray("profiles").getJSONObject(0)
            .getJSONObject("resourceReceipts").getJSONObject(key)
        assertTrue(row.getString("subscriptionUserInfo").contains("download=20"))
        assertEquals(now, row.getLong("checkedAt"))
        repeat(2) {
            val request = server.takeRequest()
            assertNull(request.getHeader("If-None-Match"))
            assertNull(request.getHeader("If-Modified-Since"))
        }
    }

    @Test fun absentNewUsageHeaderClearsOldUsageInsteadOfFallingBackToImmutableReceipt() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(response(2))
        val id = repo.importText("Main", source()).id
        val before = repo.prepareForStart(id)
        val immutable = manifest(before).readBytes()
        now += 60_000
        server.enqueue(response(null))
        assertNull(repo.refreshIfDue(id))
        assertNull(usage(repo, id).subscriptionInfo)
        assertNull(usage(repository(), id).subscriptionInfo)
        assertArrayEquals(immutable, manifest(repo.refreshSchedule(id)).readBytes())
    }

    @Test fun manualRefreshCannotBeShadowedByPriorAutomaticReceipt() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(response(2))
        val id = repo.importText("Main", source()).id
        now += 60_000
        server.enqueue(response(20))
        assertNull(repo.refreshIfDue(id))
        assertEquals(20L, usage(repo, id).subscriptionInfo!!.download)
        server.enqueue(response(30))
        val manual = ResourceManagementRepository(repo).refresh(id)
        assertEquals(30L, manual.entries.single { it.key == key }.subscriptionInfo!!.download)
        assertEquals(30L, usage(repository(), id).subscriptionInfo!!.download)
        assertEquals(3, server.requestCount)
    }

    @Test fun changedPayloadCommitsMatchingUsageReceiptWithItsGeneration() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(response(2))
        val id = repo.importText("Main", source()).id
        val before = repo.prepareForStart(id)
        now += 60_000
        server.enqueue(response(20, body.replace("name: node", "name: changed")))
        val after = checkNotNull(repo.refreshIfDue(id))
        assertNotEquals(before.generation, after.generation)
        assertEquals(20L, usage(repository(), id).subscriptionInfo!!.download)
    }

    @Test fun cancelledDownloadCannotPublishNewUsageAndDoesNotBackOffTheRetry() = runBlocking<Unit> {
        val received = CountDownLatch(1)
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { if (server.requestCount == 3) received.countDown() }
        }.build()
        val repo = repository(ResourceDownloader(client))
        server.enqueue(response(2))
        val id = repo.importText("Main", source()).id
        now += 60_000
        server.enqueue(response(20))
        assertNull(repo.refreshIfDue(id))
        val committed = File(temporary.root, "profiles/index.json").readBytes()
        now += 60_000
        server.enqueue(response(30).setBodyDelay(5, TimeUnit.SECONDS))
        val update = launch(Dispatchers.Default) { repo.refreshIfDue(id) }
        assertTrue(withContext(Dispatchers.IO) { received.await(5, TimeUnit.SECONDS) })
        update.cancelAndJoin()
        assertArrayEquals(committed, File(temporary.root, "profiles/index.json").readBytes())
        assertEquals(20L, usage(repository(), id).subscriptionInfo!!.download)
        server.enqueue(response(40))
        assertNull(repo.refreshIfDue(id))
        assertEquals(40L, usage(repo, id).subscriptionInfo!!.download)
        assertEquals(4, server.requestCount)
    }

    @Test fun sideLoadingSamePayloadDoesNotReuseRemoteUsageOrLoseLocalOverride() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(response(2))
        val id = repo.importText("Main", source()).id
        now += 60_000
        server.enqueue(response(20))
        assertNull(repo.refreshIfDue(id))
        val before = repo.refreshSchedule(id)
        val local = repo.sideLoadResource(id, key, body.byteInputStream())
        assertNotEquals(before.generation, local.generation)
        assertTrue(usage(repo, id).sideLoaded)
        assertNull(usage(repo, id).subscriptionInfo)
        now += 60_000
        assertNull(repo.refreshIfDue(id))
        assertEquals(2, server.requestCount)
    }
}
