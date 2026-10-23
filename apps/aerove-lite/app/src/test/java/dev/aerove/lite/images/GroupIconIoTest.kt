package dev.aerove.lite.images

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GroupIconIoTest {
    @Test fun injectedProxyRouteIsPreservedInsteadOfForcingDirectNetwork() = runBlocking {
        val directory = Files.createTempDirectory("icon-http-proxy-").toFile()
        val proxy = MockWebServer()
        proxy.start()
        try {
            proxy.enqueue(MockResponse().setBody("through injected route"))
            val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(proxy.hostName, proxy.port))).build()
            IconHttpSource(client, directory).use { source ->
                assertEquals("through injected route", source.read(IconRequestKey("http://unresolvable.invalid/icon.png", "UA", true)).decodeToString())
            }
            assertTrue(proxy.takeRequest().requestLine.startsWith("GET http://unresolvable.invalid/icon.png "))
        } finally {
            proxy.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun diskCacheSurvivesLoaderRecreationAndUsesTheActualUserAgent() = runBlocking {
        val directory = Files.createTempDirectory("icon-http-cache-").toFile()
        val server = MockWebServer()
        server.start()
        val key = IconRequestKey(server.url("/image.png").toString(), "TestAgent/1", true)
        try {
            server.enqueue(MockResponse().setBody("real image bytes").setHeader("Cache-Control", "max-age=3600"))
            IconHttpSource(OkHttpClient(), directory).use { assertEquals("real image bytes", it.read(key).decodeToString()) }
            assertEquals("TestAgent/1", server.takeRequest().getHeader("User-Agent"))
            IconHttpSource(OkHttpClient(), directory).use { assertEquals("real image bytes", it.read(key).decodeToString()) }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun differentUserAgentsNeverReuseAnotherAgentsDiskResponse() = runBlocking {
        val directory = Files.createTempDirectory("icon-http-vary-").toFile()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("first"))
            server.enqueue(MockResponse().setBody("second"))
            IconHttpSource(OkHttpClient(), directory).use { source ->
                val first = IconRequestKey(server.url("/icon").toString(), "One", true)
                val second = first.copy(userAgent = "Two")
                assertEquals("first", source.read(first).decodeToString())
                assertEquals("second", source.read(second).decodeToString())
                assertEquals("second", source.read(second).decodeToString())
                server.enqueue(MockResponse().setBody("unverified"))
                assertEquals("unverified", source.read(second.copy(checkCertificate = false)).decodeToString())
                assertEquals("second", source.read(second).decodeToString())
            }
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun redirectsUseRealBodiesAndBothDeclaredAndChunkedLimitsAreEnforced() = runBlocking {
        val directory = Files.createTempDirectory("icon-http-limit-").toFile()
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody("redirected"))
            server.enqueue(MockResponse().setBody("tiny").setHeader("Content-Length", IconHttpSource.MAX_ICON_BYTES + 1))
            server.enqueue(MockResponse().setChunkedBody(Buffer().write(ByteArray(IconHttpSource.MAX_ICON_BYTES.toInt() + 1)), 8192))
            IconHttpSource(OkHttpClient(), directory).use { source ->
                assertEquals("redirected", source.read(IconRequestKey(server.url("/start").toString(), "UA", true)).decodeToString())
                for (path in listOf("/declared", "/chunked")) {
                    try { source.read(IconRequestKey(server.url(path).toString(), "UA", true)); fail("Oversized body accepted") }
                    catch (_: IOException) { }
                }
            }
            assertEquals(4, server.requestCount)
        } finally {
            server.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun cancellationClosesAnActualOutstandingHttpCall() = runBlocking {
        val directory = Files.createTempDirectory("icon-http-cancel-").toFile()
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            IconHttpSource(OkHttpClient(), directory).use { source ->
                val reading = scope.async { source.read(IconRequestKey(server.url("/blocked").toString(), "UA", true)) }
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                withTimeout(1500) { reading.cancelAndJoin() }
                assertTrue(reading.isCancelled)
            }
        } finally {
            scope.cancel(); server.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun sharedReadersCoalesceAndOneCancellationDoesNotCancelTheOther() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val starts = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val key = IconRequestKey("https://example.test/icon", "UA", true)
        val store = IconRequestStore(scope) { _: IconRequestKey ->
            starts.incrementAndGet(); entered.complete(Unit); finish.await(); WeightedIcon("actual", 6)
        }
        try {
            val one = async { store.load(key) }
            entered.await()
            val two = async { store.load(key) }
            delay(30)
            one.cancelAndJoin()
            finish.complete(Unit)
            assertEquals("actual", two.await())
            assertEquals("actual", store.load(key))
            assertEquals(1, starts.get())
        } finally { scope.cancel() }
    }
    @Test fun cancellationAlsoClosesTheBodyAfterRealHeadersWereReceived() = runBlocking {
        val directory = Files.createTempDirectory("icon-body-cancel-").toFile()
        val server = MockWebServer()
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val headers = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            chain.proceed(chain.request()).also { headers.complete(Unit) }
        }.build()
        try {
            server.enqueue(MockResponse().setBody("actual body").setBodyDelay(5, TimeUnit.SECONDS))
            IconHttpSource(client, directory).use { source ->
                val reading = scope.async { source.read(IconRequestKey(server.url("/body").toString(), "UA", true)) }
                withTimeout(2000) { headers.await() }
                delay(100)
                withTimeout(1500) { reading.cancelAndJoin() }
                assertTrue(reading.isCancelled)
            }
        } finally {
            scope.cancel(); server.shutdown()
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
    @Test fun simultaneousDifferentIconsKeepTheStandardEightSlotLimit() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val store = IconRequestStore(scope) { key: IconRequestKey ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            entered.send(Unit)
            try { release.await(); WeightedIcon(key.url, 1) }
            finally { active.decrementAndGet() }
        }
        try {
            val reads = (0 until 12).map { index -> async { store.load(IconRequestKey("https://example.test/$index", "UA", true)) } }
            withTimeout(2000) { repeat(8) { entered.receive() } }
            assertTrue(entered.tryReceive().isFailure)
            release.complete(Unit)
            reads.forEach { it.await() }
            assertEquals(8, peak.get())
        } finally { scope.cancel(); entered.close() }
    }
    @Test fun lastReaderCancellationReleasesTheRealFetchAndBoundedLruEvicts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val blocked = IconRequestStore<String>(scope) { _: IconRequestKey ->
            try { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            finally { cancelled.complete(Unit) }
        }
        val key = IconRequestKey("https://example.test/a", "UA", true)
        try {
            val reading = async { blocked.load(key) }
            entered.await(); reading.cancelAndJoin(); withTimeout(1500) { cancelled.await() }
            val calls = AtomicInteger()
            val lru = IconRequestStore(scope, limit = 6) { request: IconRequestKey -> calls.incrementAndGet(); WeightedIcon(request.url, 4) }
            lru.load(key); lru.load(key.copy(url = "https://example.test/b")); lru.load(key)
            assertEquals(3, calls.get())
        } finally { scope.cancel() }
    }
}


class IconCacheParityTest {
    @Test fun staleDiskIsPublishedBeforeOneValidationAndFreshReplacesIt() = runBlocking {
        val directory = Files.createTempDirectory("icon-stale-").toFile()
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val key = IconRequestKey(server.url("/icon").toString(), "UA", true)
        try {
            server.enqueue(MockResponse().setBody("old").setHeader("Cache-Control", "max-age=0").setHeader("ETag", "v1"))
            server.enqueue(MockResponse().setBody("fresh").setHeader("Cache-Control", "max-age=3600").setBodyDelay(200, TimeUnit.MILLISECONDS))
            IconHttpSource(OkHttpClient(), directory).use { source ->
                assertEquals("old", source.read(key).decodeToString())
                val store = IconRequestStore(scope, preview = { k: IconRequestKey -> source.preview(k)?.let { WeightedIcon(it.bytes.decodeToString(), it.bytes.size, it.validUntil, it.cacheable) } }) { k ->
                    val result = source.readResult(k)!!
                    WeightedIcon(result.bytes.decodeToString(), result.bytes.size, result.validUntil, result.cacheable)
                }
                val received = ArrayList<String>()
                store.observe(key).collect { received.add(it) }
                assertEquals(listOf("old", "fresh"), received)
                assertEquals("fresh", store.load(key))
                assertEquals(2, server.requestCount)
                server.takeRequest()
                assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
            }
        } finally { scope.cancel(); server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun durableRevalidationDirectivesDoNotPreviewBeforeActual304() = runBlocking {
        for (directive in listOf("no-cache", "max-age=0, must-revalidate", "max-age=0, proxy-revalidate")) {
            val directory = Files.createTempDirectory("icon-revalidate-").toFile()
            val server = MockWebServer()
            val started = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val calls = AtomicInteger()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    if (calls.incrementAndGet() == 1) return MockResponse().setBody("stored").setHeader("Cache-Control", directive).setHeader("ETag", "v1")
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(304).setHeader("Cache-Control", directive)
                }
            }
            server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val key = IconRequestKey(server.url("/icon").toString(), "UA", true)
            try {
                IconHttpSource(OkHttpClient(), directory).use { assertEquals("stored", it.read(key).decodeToString()) }
                IconHttpSource(OkHttpClient(), directory).use { source ->
                    assertNull("$directive permits a pre-validation preview", source.preview(key))
                    val store = IconRequestStore(scope, preview = { k: IconRequestKey -> source.preview(k)?.let { WeightedIcon(it.bytes.decodeToString(), it.bytes.size, it.validUntil, it.cacheable) } }) { k ->
                        val result = source.readResult(k)!!
                        WeightedIcon(result.bytes.decodeToString(), result.bytes.size, result.validUntil, result.cacheable)
                    }
                    val received = java.util.concurrent.CopyOnWriteArrayList<String>()
                    val observation = async(Dispatchers.IO) { store.observe(key).collect { received.add(it) } }
                    assertTrue(kotlinx.coroutines.withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
                    try { assertTrue("$directive displayed unvalidated disk data", received.isEmpty()) }
                    finally { release.countDown() }
                    observation.await()
                    assertEquals(listOf("stored"), received.toList())
                    assertEquals(2, server.requestCount)
                    server.takeRequest()
                    assertEquals("v1", server.takeRequest().getHeader("If-None-Match"))
                }
            } finally { release.countDown(); scope.cancel(); server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
        }
    }

    @Test fun noStoreNeverSeedsDiskOrMemoryAndDifferentPoliciesDoNotCoalesce() = runBlocking {
        val directory = Files.createTempDirectory("icon-no-store-").toFile()
        val server = MockWebServer(); server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val key = IconRequestKey(server.url("/icon").toString(), "UA", true)
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("private$it").setHeader("Cache-Control", "no-store")) }
            IconHttpSource(OkHttpClient(), directory).use { source ->
                val store = IconRequestStore(scope) { k: IconRequestKey ->
                    val result = source.readResult(k)!!
                    WeightedIcon(result.bytes.decodeToString(), result.bytes.size, result.validUntil, result.cacheable)
                }
                assertEquals("private0", store.load(key))
                assertNull(source.preview(key))
                assertEquals("private1", store.load(key))
                assertEquals(2, server.requestCount)
            }
            val count = AtomicInteger()
            val store = IconRequestStore(scope) { k: IconRequestKey -> WeightedIcon(k.userAgent + count.incrementAndGet(), 1) }
            assertEquals("UA1", store.load(key))
            assertEquals("OTHER2", store.load(key.copy(userAgent = "OTHER")))
            assertEquals("UA3", store.load(key.copy(checkCertificate = false)))
        } finally { scope.cancel(); server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun diskEvictsAtTwoHundredEntriesAndThirtyDaysWithoutUse() = runBlocking {
        val directory = Files.createTempDirectory("icon-prune-").toFile()
        val server = MockWebServer(); server.start()
        var now = System.currentTimeMillis()
        try {
            IconHttpSource(OkHttpClient(), directory, clock = { now }).use { source ->
                repeat(201) { index ->
                    server.enqueue(MockResponse().setBody("$index").setHeader("Cache-Control", "max-age=31536000"))
                    source.read(IconRequestKey(server.url("/$index").toString(), "UA", true))
                    now++
                }
                assertNull(source.preview(IconRequestKey(server.url("/0").toString(), "UA", true)))
                assertEquals("200", source.preview(IconRequestKey(server.url("/200").toString(), "UA", true))!!.bytes.decodeToString())
                now += 31L * 86400000
                assertNull(source.preview(IconRequestKey(server.url("/200").toString(), "UA", true)))
                assertEquals(201, server.requestCount)
            }
        } finally { server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun cacheableErrorHeadersCannotSeedAStoredFailure() = runBlocking {
        val directory = Files.createTempDirectory("icon-error-").toFile()
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404).setBody("failure").setHeader("Cache-Control", "max-age=3600"))
            server.enqueue(MockResponse().setBody("recovered"))
            IconHttpSource(OkHttpClient(), directory).use { source ->
                val key = IconRequestKey(server.url("/icon").toString(), "UA", true)
                try { source.read(key); fail("HTTP error accepted") } catch (_: IOException) { }
                assertNull(source.preview(key))
                assertEquals("recovered", source.read(key).decodeToString())
                assertEquals(2, server.requestCount)
            }
        } finally { server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun actualHeadersDetermineMemoryExpiryInsteadOfAnUnlimitedLifetime() = runBlocking {
        val directory = Files.createTempDirectory("icon-expiry-").toFile()
        val server = MockWebServer(); server.start()
        try {
            IconHttpSource(OkHttpClient(), directory).use { source ->
                val key = IconRequestKey(server.url("/default").toString(), "UA", true)
                server.enqueue(MockResponse().setBody("default"))
                val start = System.currentTimeMillis()
                val default = source.readResult(key)!!
                assertTrue(default.validUntil - start in 604798000L..604801000L)
                server.enqueue(MockResponse().setBody("short").setHeader("Cache-Control", "max-age=2"))
                assertTrue(source.readResult(key.copy(url = server.url("/short").toString()))!!.validUntil - System.currentTimeMillis() in 0L..2000L)
                server.enqueue(MockResponse().setBody("validate").setHeader("Cache-Control", "no-cache"))
                assertTrue(source.readResult(key.copy(url = server.url("/validate").toString()))!!.validUntil <= System.currentTimeMillis())
                server.enqueue(MockResponse().setBody("expires").setHeader("Expires", "Thu, 01 Jan 1970 00:00:00 GMT"))
                assertTrue(source.readResult(key.copy(url = server.url("/expires").toString()))!!.validUntil <= System.currentTimeMillis())
            }
        } finally { server.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun actualRedirectCallsResolveTheRouteAfterApplyingRequestPolicy() = runBlocking {
        val directory = Files.createTempDirectory("icon-route-").toFile()
        val first = MockWebServer(); val second = MockWebServer(); first.start(); second.start()
        val targets = ArrayList<okhttp3.HttpUrl>()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/image")))
            second.enqueue(MockResponse().setBody("routed").setHeader("Cache-Control", "no-store"))
            IconHttpSource(OkHttpClient(), directory, routeClient = { scoped, target ->
                targets.add(target)
                assertEquals(20000, scoped.callTimeoutMillis)
                scoped
            }).use { source ->
                assertEquals("routed", source.read(IconRequestKey(first.url("/start").toString(), "route-UA", true)).decodeToString())
            }
            assertEquals(listOf(first.url("/start"), second.url("/image")), targets)
            assertEquals("route-UA", first.takeRequest().getHeader("User-Agent"))
            assertEquals("route-UA", second.takeRequest().getHeader("User-Agent"))
        } finally { first.shutdown(); second.shutdown(); directory.listFiles()?.forEach { it.delete() }; directory.delete() }
    }

    @Test fun dataUriUsesBoundedActualPngAndSvgBytesWithoutAnyHttp() {
        val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
        assertArrayEquals(png, decodeIconDataUri("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png)))
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><path d=\"M0 0L1 1\"/></svg>".encodeToByteArray()
        assertArrayEquals(svg, decodeIconDataUri("data:image/svg+xml;base64," + java.util.Base64.getEncoder().encodeToString(svg)))
        assertTrue(IconSvgDocument.parse(svg).text.contains("M0 0L1 1"))
        listOf("data:text/html;base64,YQ==", "data:image/png;base64,!!!", "data:image/png;base64,", "data:image/png;base64," + "A".repeat(22369628)).forEach {
            try { decodeIconDataUri(it); fail("Invalid data URI accepted") } catch (_: IllegalArgumentException) { } catch (_: IOException) { }
        }
    }
}
