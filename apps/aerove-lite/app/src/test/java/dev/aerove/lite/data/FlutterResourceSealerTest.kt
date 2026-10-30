package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Native validators are injected; these tests prove file/HTTP ownership, not JNI or native routing. */
class FlutterResourceSealerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun schema(runtime: File, prepared: NativePreparedConfigValidator = NativePreparedConfigValidator { _, _ -> null }) =
        CompatibilityValidator.forMeow(NativeConfigValidator { error("Unsealed full validation") }, runtime, prepared) { emptySet() }
    private fun source(url: String, count: Int = 1) = "global-ua: exact-shared-UA\nproxy-groups: [{name: Route, type: select, proxies: [DIRECT]}]\nrule-providers:\n" +
        (0 until count).joinToString("\n") { "  r$it: {type: http, url: '$url/$it', path: rules/r$it.yaml, behavior: domain, format: yaml, proxy: Route, header: {X-Test: [a, b]}}" } +
        "\nrules: ['MATCH,DIRECT']\n"

    @Test fun leaseSurvivesForeignPruneUntilTransferAndPreservesOriginalPolicies() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient()
        try {
            repeat(153) { server.enqueue(MockResponse().setBody("payload: [example.com]\n")) }
            val runtime = File(temporary.root, "runtime")
            var closes = 0
            val factory = ProviderDownloadFactory { context ->
                assertEquals("exact-shared-UA", context.http!!.userAgent)
                assertFalse(context.http!!.checkCertificate)
                assertEquals(mapOf("Route" to "DIRECT", "removed" to "stale"), context.selectionCache)
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        assertEquals("Route", request.proxy)
                        val builder = Request.Builder().url(request.url).header("User-Agent", context.http!!.userAgent)
                        request.headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                        return client.newCall(builder.build()).execute().use {
                            val digest = it.body!!.byteStream().use { stream -> copyResource(stream, request.destination, request.maxBytes) }
                            ProviderDownloadResult(digest.bytes, digest.sha256)
                        }
                    }
                    override suspend fun closeAndJoin() { closes++ }
                }
            }
            val manager = ResourceBundleManager(runtime, schema(runtime), NativeResourceValidator { _, _, _, _ -> null },
                ResourceDownloader(), providerDownloads = factory)
            val input = source(server.url("/").toString().trimEnd('/'), 153)
            val lease = FlutterResourceSealer(manager, "fallback-UA").prepare(input, false, mapOf("Route" to "DIRECT", "removed" to "stale"))
            assertEquals(153, server.requestCount); assertEquals(1, closes)
            assertEquals(listOf("a", "b"), server.takeRequest().headers.values("X-Test"))
            manager.prune(emptyMap(), emptySet())
            assertTrue(lease.directory.isDirectory)
            assertEquals(input, File(lease.directory, "input.yaml").readText())
            val manifest = JSONObject(File(lease.directory, "manifest.json").readText())
            assertFalse(manifest.getJSONObject("requestPolicy").getBoolean("checkCertificate"))
            assertEquals("stale", manifest.getJSONObject("selectionCache").getString("removed"))
            assertEquals(153, manifest.getJSONArray("records").length())
            assertEquals(156, lease.resources.size)
            val transfer = temporary.newFolder()
            lease.resources.forEach { row ->
                val copied = File(transfer, row.relativePath).apply { parentFile.mkdirs(); writeBytes(File(row.sourcePath).readBytes()) }
                assertEquals(row.sha256, sha256(copied))
            }
            val borrowed = FlutterResourceSealer(manager, "fallback-UA").borrow(lease.yaml, lease.directory)
            lease.closeAndJoin(); lease.closeAndJoin()
            manager.prune(emptyMap(), emptySet())
            assertTrue("a reader lease must defer producer discard", lease.directory.isDirectory)
            borrowed.closeAndJoin(); borrowed.closeAndJoin()
            assertFalse(lease.directory.exists())
            assertTrue(File(transfer, "manifest.json").isFile)
            assertFalse(File(temporary.root, "profiles").exists()); assertFalse(File(temporary.root, "settings").exists())
        } finally { client.connectionPool.evictAll(); server.shutdown() }
    }

    @Test fun noExplicitProviderPolicyStillUsesCapturedOwnerAndCarriesTlsPreference() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        var downloads = 0
        val manager = ResourceBundleManager(runtime, schema(runtime), NativeResourceValidator { _, _, _, _ -> null },
            ResourceDownloader(), providerDownloads = ProviderDownloadFactory { context ->
                assertEquals("fallback-UA", context.http!!.userAgent); assertTrue(context.http!!.checkCertificate)
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        assertNull(request.proxy); assertTrue(request.headers.isEmpty()); downloads++
                        val digest = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                        return ProviderDownloadResult(digest.bytes, digest.sha256)
                    }
                    override suspend fun closeAndJoin() = Unit
                }
            })
        val hint = File(temporary.root, "profiles/providers/shared/rules/hash").path.replace('\\', '/')
        val input = "rule-providers: {r: {type: http, url: 'https://example.invalid/r', path: '$hint', behavior: domain}}\nrules: ['MATCH,DIRECT']"
        val lease = FlutterResourceSealer(manager, "fallback-UA").prepare(input, true)
        try {
            assertEquals(1, downloads); assertTrue(lease.yaml.contains("type: file"))
            assertFalse(File(hint).exists()); assertEquals(input, File(lease.directory, "input.yaml").readText())
            assertEquals(hint, JSONObject(File(lease.directory, "manifest.json").readText()).getJSONArray("records").getJSONObject(0).getString("localPath"))
        } finally { lease.closeAndJoin() }
    }

    @Test fun cancellationWaitsForProducerJoinBeforeRemovingCandidate() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val entered = CompletableDeferred<Unit>(); val closing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var root: File? = null
        val manager = ResourceBundleManager(runtime, schema(runtime), NativeResourceValidator { _, _, _, _ -> null },
            ResourceDownloader(), providerDownloads = ProviderDownloadFactory { context ->
                root = context.stagingRoot
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult { entered.complete(Unit); awaitCancellation() }
                    override suspend fun closeAndJoin() { closing.complete(Unit); release.await() }
                }
            })
        val work = async { FlutterResourceSealer(manager, "fallback-UA").prepare(source("https://example.invalid"), true) }
        withTimeout(5000) { entered.await() }; work.cancel()
        withTimeout(5000) { closing.await() }
        assertTrue(root!!.isDirectory); assertFalse(work.isCompleted)
        release.complete(Unit); withTimeout(5000) { work.join() }
        assertFalse(root!!.exists()); assertTrue(work.isCancelled)
    }

    @Test fun proxyProviderDependenciesBootstrapInOrderWithoutDirectFallbackAndCyclesFailBeforeHttp() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val fetched = mutableListOf<String>(); val routes = mutableListOf<String?>()
        var opened = 0; var closed = 0
        val manager = ResourceBundleManager(runtime, schema(runtime), NativeResourceValidator { _, _, _, _ -> null },
            ResourceDownloader(), providerDownloads = ProviderDownloadFactory { context ->
                opened++
                val providers = ConfigYaml.parse(context.candidateYaml)["proxy-providers"] as? Map<*, *>
                providers?.values?.forEach { assertEquals("file", (it as Map<*, *>)["type"]) }
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        fetched.add(request.url.substringAfterLast('/')); routes.add(request.proxy)
                        val digest = "proxies: [{name: child, type: socks5, server: 127.0.0.1, port: 1080}]\n".byteInputStream()
                            .use { copyResource(it, request.destination, request.maxBytes) }
                        return ProviderDownloadResult(digest.bytes, digest.sha256)
                    }
                    override suspend fun closeAndJoin() { closed++ }
                }
            })
        val input = """
            proxy-providers:
              A: {type: http, url: 'https://example.invalid/A', path: A.yaml, proxy: Route}
              B: {type: http, url: 'https://example.invalid/B', path: B.yaml, proxy: DIRECT}
            proxy-groups: [{name: Route, type: select, use: [B]}]
            rules: ['MATCH,DIRECT']
        """.trimIndent()
        val lease = FlutterResourceSealer(manager, "fallback-UA").prepare(input, true)
        try {
            assertEquals(listOf("B", "A"), fetched); assertEquals(listOf("DIRECT", "Route"), routes)
            assertEquals(2, opened); assertEquals(2, closed)
        } finally { lease.closeAndJoin() }
        fetched.clear()
        val cyclic = input.replace("proxy: DIRECT", "proxy: Route")
        try { FlutterResourceSealer(manager, "fallback-UA").prepare(cyclic, true); fail("bootstrap cycle accepted") }
        catch (failure: ConfigValidationException) {
            assertTrue(failure.issues.single().path.startsWith("$.proxy-providers."))
            assertTrue(failure.issues.single().reason.contains("cycle"))
        }
        assertTrue(fetched.isEmpty())
    }
}
