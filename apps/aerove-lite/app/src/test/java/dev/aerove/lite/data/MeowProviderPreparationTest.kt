package dev.aerove.lite.data

import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** These are Kotlin transaction/transport-contract tests; Core routing is verified by the Native companion. */
class MeowProviderPreparationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val runtime get() = File(temporary.root, "runtime")
    private fun source(url: String, two: Boolean = false) = """
        rule-providers:
          first: {type: http, url: '$url', path: cache/first.yaml, behavior: domain, format: yaml, proxy: named-route, header: {X-Test: [a, b]}}
          ${if (two) "second: {type: http, url: '$url', path: cache/second.yaml, behavior: domain, format: yaml, proxy: named-route, header: {X-Test: [b, a]}}" else ""}
        proxy-groups: [{name: named-route, type: select, proxies: [DIRECT]}]
        rules: ['RULE-SET,first,DIRECT', 'MATCH,DIRECT']
    """.trimIndent()
    private fun validator(prepared: NativePreparedConfigValidator) = CompatibilityValidator.forMeow(
        NativeConfigValidator { error("source was prematurely fully converted") }, runtime, prepared) { emptySet() }

    @Test fun namedRouteAndRepeatedHeadersUseOneOwnedScopeBeforeSealingAndFinalValidation() = runBlocking<Unit> {
        val origin = MockWebServer(); val proxy = MockWebServer(); origin.start(); proxy.start()
        try {
            repeat(2) { proxy.enqueue(MockResponse().setBody("payload: [example.com]\n")) }
            val opened = AtomicInteger(); val closed = AtomicInteger(); val transferred = AtomicInteger()
            val fetch = ProviderDownloadFactory { context ->
                opened.incrementAndGet(); assertTrue(context.stagingRoot.name == "assets")
                assertTrue(context.candidateYaml.contains("proxy: named-route"))
                val client = OkHttpClient.Builder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port))).build()
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        assertEquals("named-route", request.proxy)
                        assertTrue(request.destination.canonicalFile.toPath().startsWith(context.stagingRoot.toPath()))
                        val builder = Request.Builder().url(request.url)
                        request.headers.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
                        return client.newCall(builder.build()).execute().use { response ->
                            val digest = response.body!!.byteStream().use { copyResource(it, request.destination, request.maxBytes) }
                            transferred.incrementAndGet(); ProviderDownloadResult(digest.bytes, digest.sha256)
                        }
                    }
                    override suspend fun closeAndJoin() { assertEquals(2, transferred.get()); closed.incrementAndGet(); client.connectionPool.evictAll() }
                }
            }
            var nativeCalls = 0
            val schema = validator(NativePreparedConfigValidator { yaml, bundle ->
                assertEquals(1, closed.get()); assertTrue(bundle.isDirectory); nativeCalls++
                (ConfigYaml.parse(yaml)["rule-providers"] as Map<*, *>).values.forEach { raw ->
                    val row = raw as Map<*, *>; assertEquals("file", row["type"])
                    assertFalse(row.containsKey("proxy")); assertFalse(row.containsKey("header")); assertFalse(row.containsKey("url"))
                    assertTrue(File(row["path"] as String).canonicalFile.toPath().startsWith(bundle.toPath()))
                }; null
            })
            val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, path, _, _ ->
                assertEquals("payload: [example.com]\n", File(path).readText()); null
            }, ResourceDownloader(), providerDownloads = fetch)
            val repo = ProfileRepository(temporary.root, schema, resources = manager)
            val input = source(origin.url("/rules").toString(), true)
            val profile = repo.importText("Meow named transport", input)
            assertEquals(input, repo.readConfig(profile.id)); assertEquals(1, opened.get()); assertEquals(1, closed.get()); assertEquals(1, nativeCalls)
            assertEquals(0, origin.requestCount); assertEquals(2, proxy.requestCount)
            assertEquals(listOf("a", "b"), proxy.takeRequest().headers.values("X-Test"))
            assertEquals(listOf("b", "a"), proxy.takeRequest().headers.values("X-Test"))
            val prepared = repo.prepareForStart(profile.id)
            assertEquals(schema.coreVersion, org.json.JSONObject(File(prepared.resourceDirectory!!, "manifest.json").readText()).getString("coreVersion"))
            assertEquals("reading an unchanged generation redownloaded its providers", 2, transferred.get())
        } finally { origin.shutdown(); proxy.shutdown() }
    }

    @Test fun cancellationJoinsCapturedDownloadOwnerBeforeRemovingStagingOrPublishingProfile() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val closing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var cancelled = false; var joined = false; var staged: File? = null
        val fetch = ProviderDownloadFactory { context ->
            staged = context.stagingRoot
            object : ProviderDownloadScope {
                override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
                override suspend fun closeAndJoin() { assertTrue(cancelled); closing.complete(Unit); release.await(); joined = true }
            }
        }
        val schema = validator(NativePreparedConfigValidator { _, _ -> error("cancelled candidate was validated") })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val job = async { repo.importText("cancel", source("https://example.invalid/rules")) }
        withTimeout(5000) { started.await() }; job.cancel(CancellationException("fixture cancellation"))
        withTimeout(5000) { closing.await() }
        assertFalse(job.isCompleted); assertTrue(staged!!.isDirectory); assertFalse(File(temporary.root, "profiles/index.json").exists())
        release.complete(Unit); withTimeout(5000) { job.join() }
        assertTrue(joined); assertTrue(job.isCancelled); assertFalse(staged!!.exists()); assertTrue(repo.list().isEmpty())
    }

    @Test fun finalNativeRejectionKeepsOriginalIndexAndLastGoodGeneration() = runBlocking<Unit> {
        var reject = false; var closes = 0
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val bytes = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(bytes.bytes, bytes.sha256)
            }
            override suspend fun closeAndJoin() { closes++ }
        } }
        val schema = validator(NativePreparedConfigValidator { _, _ -> if (reject) "$.rules[0]: native fixture rejection" else null })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val input = source("https://example.invalid/rules"); val profile = repo.importText("good", input)
        val good = repo.prepareForStart(profile.id); repo.markUsable(good)
        val index = File(temporary.root, "profiles/index.json").readBytes()
        reject = true
        val failure = try { repo.refreshResources(profile.id); error("rejected native candidate was committed") } catch (e: ConfigValidationException) { e }
        assertEquals("$.rules[0]", failure.issues.single().path); assertArrayEquals(index, File(temporary.root, "profiles/index.json").readBytes())
        assertEquals(input, repo.readConfig(profile.id)); assertTrue(good.resourceDirectory!!.isDirectory); assertEquals(2, closes)
    }

    @Test fun receiptsDistinguishCapturedTransportVerifiedGenerationAndExplicitPrivateOverride() = runBlocking<Unit> {
        var downloads = 0
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                downloads++
                val digest = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() = Unit
        } }
        val schema = validator(NativePreparedConfigValidator { _, _ -> null })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val input = source("https://example.invalid/rules")
        val profile = repo.importText("receipts", input)
        val initial = repo.prepareForStart(profile.id)
        fun record(directory: File) = org.json.JSONObject(File(directory, "manifest.json").readText()).getJSONArray("records").getJSONObject(0)
        assertEquals("captured-transport", record(initial.resourceDirectory!!).getString("transportOrigin"))
        val old = ResourceBundle(initial.generation!!, initial.resourceDirectory!!, sha256(input), initial.yaml, null)
        val reused = manager.prepare(profile.id, input, old, emptySet())
        assertEquals("verified-generation", record(reused.directory).getString("transportOrigin"))
        assertEquals(1, downloads)
        manager.discard(profile.id, reused.generation)
        val edit = repo.resourceEditSnapshot(profile.id, "$.rule-providers.first")
        val replaced = repo.replaceResource(edit, "payload: [local.example]\n".byteInputStream())
        assertEquals("private-override", record(replaced.resourceDirectory!!).getString("transportOrigin"))
        assertEquals(1, downloads)
        assertEquals("payload: [local.example]\n", repo.resourceEditSnapshot(profile.id, edit.key).bytes.toString(Charsets.UTF_8))
        assertEquals(input, repo.readConfig(profile.id))
    }

    @Test fun legitimate149ProvidersFitBoundedBundleBut257NeverPublishes() = runBlocking<Unit> {
        var closes = 0
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val digest = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() { closes++ }
        } }
        val schema = validator(NativePreparedConfigValidator { _, _ -> null })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        fun many(count: Int) = ConfigYaml.dump(mapOf(
            "rule-providers" to (0 until count).associate { "r$it" to mapOf("type" to "http", "url" to "https://example.invalid/$it", "path" to "cache/$it.yaml", "behavior" to "domain", "format" to "yaml", "proxy" to "DIRECT") },
            "rules" to listOf("MATCH,DIRECT")))
        val profile = repo.importText("149", many(149))
        val prepared = repo.prepareForStart(profile.id)
        assertEquals(149, org.json.JSONObject(File(prepared.resourceDirectory!!, "manifest.json").readText()).getJSONArray("records").length())
        val index = File(temporary.root, "profiles/index.json").readBytes()
        val failure = try { repo.importText("257", many(257)); error("resource count bound was removed") } catch (failure: ConfigValidationException) { failure }
        assertTrue(failure.issues.any { it.reason.contains("Resource bundle limit exceeded") })
        assertArrayEquals(index, File(temporary.root, "profiles/index.json").readBytes())
        assertEquals(1, repo.list().size); assertEquals(2, closes)
    }

    @Test fun preparedConverterCannotMutateBorrowedGenerationBeforePublication() = runBlocking<Unit> {
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val digest = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() = Unit
        } }
        val schema = validator(NativePreparedConfigValidator { _, root ->
            val manifest = org.json.JSONObject(File(root, "manifest.json").readText())
            File(root, manifest.getJSONArray("records").getJSONObject(0).getString("file")).writeText("payload: [changed.example]\n")
            null
        })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val failure = try { repo.importText("mutated", source("https://example.invalid/rules")); error("mutated generation published") } catch (failure: ConfigValidationException) { failure }
        assertEquals("$.rule-providers.first", failure.issues.single().path)
        assertTrue(failure.issues.single().reason.contains("Immutable resource content changed"))
        assertTrue(repo.list().isEmpty()); assertFalse(File(temporary.root, "profiles/index.json").exists())
    }

    @Test fun capturedReceiptIgnoresUnconsumedSharedCacheWithSameUrlAndDifferentPayload() = runBlocking<Unit> {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("payload: [shared.example]\n"))
            server.enqueue(MockResponse().setBody("payload: [captured.example]\n"))
            val plain = source(server.url("/same").toString()).replace(", proxy: named-route, header: {X-Test: [a, b]}", "")
            val legacy = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val oldManager = ResourceBundleManager(runtime, legacy, NativeResourceValidator { _, _, _, _ -> null })
            oldManager.syncLibraryRule("different-library-name", server.url("/same").toString(), "domain", "yaml")
            val oldRepo = ProfileRepository(temporary.root, legacy, resources = oldManager)
            val old = oldRepo.prepareForStart(oldRepo.importText("shared cache seed", plain).id)
            val oldProvider = (ConfigYaml.parse(old.yaml)["rule-providers"] as Map<*, *>)["first"] as Map<*, *>
            assertTrue(File(oldProvider["path"] as String).readText().contains("shared.example"))
            var downloads = 0
            val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
                override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                    downloads++
                    return OkHttpClient().newCall(Request.Builder().url(request.url).build()).execute().use { response ->
                        val digest = response.body!!.byteStream().use { copyResource(it, request.destination, request.maxBytes) }
                        ProviderDownloadResult(digest.bytes, digest.sha256)
                    }
                }
                override suspend fun closeAndJoin() = Unit
            } }
            val schema = validator(NativePreparedConfigValidator { _, _ -> null })
            val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = fetch)
            val repo = ProfileRepository(temporary.root, schema, resources = manager)
            val id = repo.importText("captured", source(server.url("/same").toString())).id
            val prepared = repo.prepareForStart(id)
            val provider = (ConfigYaml.parse(prepared.yaml)["rule-providers"] as Map<*, *>)["first"] as Map<*, *>
            assertTrue(File(provider["path"] as String).readText().contains("captured.example"))
            assertEquals(prepared.generation, repo.prepareForStart(id).generation)
            assertEquals(1, downloads); assertEquals(2, server.requestCount)
            server.enqueue(MockResponse().setBody("payload: [new-shared.example]\n"))
            manager.syncLibraryRule("another-name-same-identity", server.url("/same").toString(), "domain", "yaml")
            assertEquals(prepared.generation, repo.prepareForStart(id).generation)
            assertEquals(1, downloads); assertEquals(3, server.requestCount)
            assertTrue(File(oldProvider["path"] as String).readText().contains("shared.example"))
        } finally { server.shutdown() }
    }

    @Test fun sourceNamedListenerSurvivesBundleAndBecomesActualComposerUserInboundPlan() = runBlocking<Unit> {
        val schema = validator(NativePreparedConfigValidator { yaml, _ ->
            assertFalse(ConfigYaml.parse(yaml).containsKey("listeners")); null
        })
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null })
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val input = "listeners: [{name: extra, type: http, port: 18888}]\nrules: ['MATCH,DIRECT']\n"
        val profile = repo.importText("named inbound", input)
        val prepared = repo.prepareForStart(profile.id)
        val named = ConfigYaml.parse(input)["listeners"]
        assertEquals(input, repo.readConfig(profile.id)); assertEquals(named, ConfigYaml.parse(prepared.yaml)["listeners"])
        val plan = MeowRuntimeConfigComposer(runtime).compose(prepared, dev.aerove.lite.LiteSettings(), 42, 49152, "x".repeat(32))
        assertEquals(named, ConfigYaml.parse(plan.userInboundYaml)["listeners"])
        assertFalse(ConfigYaml.parse(plan.engineYaml).containsKey("listeners"))
    }

    private fun preflightWire(behavior: String, format: String, pending: Boolean) = org.json.JSONObject()
        .put("state", if (pending) "requiresPreparedContext" else "validated").put("behavior", behavior).put("format", format).put("count", 2)
        .put("requires", org.json.JSONObject().put("geoip", pending).put("asn", pending).put("geosite", false)).toString()

    @Test fun geoResidualRequiresTypedPreparationAndFinalMissingDatabaseStillKeepsOldRevision() = runBlocking<Unit> {
        var geo = false; var standaloneCalls = 0; var preflightCalls = 0; var finalGeoCalls = 0
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val body = if (geo) "payload: ['SRC-GEOIP,CN', 'DOMAIN,example.com']\n" else "payload: [example.com]\n"
                val digest = body.byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() = Unit
        } }
        // Exact fixed parser default-context failure is deliberately non-null. Never suppress it by text.
        val standalone = NativeResourceValidator { _, _, _, _ -> standaloneCalls++; "GEOIP rule requires a GeoIP database, but none is configured" }
        val oldSchema = validator(NativePreparedConfigValidator { _, _ -> error("standalone failure must stop old path") })
        val oldManager = ResourceBundleManager(runtime, oldSchema, standalone, ResourceDownloader(), providerDownloads = fetch)
        val oldRepo = ProfileRepository(temporary.root, oldSchema, resources = oldManager)
        geo = true
        val residual = source("https://example.invalid/residual").replace("behavior: domain", "behavior: classical")
        val first = try { oldRepo.importText("old strict path", residual); error("standalone API was loosened") } catch (failure: ConfigValidationException) { failure }
        assertTrue(first.issues.single().reason.contains("GEOIP rule requires")); assertEquals(1, standaloneCalls)
        assertTrue(oldRepo.list().isEmpty())
        val schema = CompatibilityValidator.forMeow(NativeConfigValidator { error("premature config conversion") }, runtime,
            NativePreparedConfigValidator { yaml, root ->
                val provider = (ConfigYaml.parse(yaml)["rule-providers"] as Map<*, *>)["first"] as Map<*, *>
                val file = File(provider["path"] as String)
                assertTrue(file.canonicalFile.toPath().startsWith(root.toPath()))
                if (file.readText().contains("SRC-GEOIP,CN")) {
                    finalGeoCalls++
                    "$.rule-providers.first: GEOIP rule requires a GeoIP database, but none is configured"
                } else null
            }, NativePreparedRuleValidator { file, behavior, format ->
                preflightCalls++
                val pending = file.readText().contains("SRC-GEOIP,CN")
                if (pending) assertTrue(file.readText().contains("DOMAIN,example.com"))
                val wire = org.json.JSONObject(preflightWire(behavior, format, pending))
                wire.getJSONObject("requires").put("asn", false)
                RuleResourcePreflight.parse(wire.toString(), behavior, format)
            }) { emptySet() }
        val manager = ResourceBundleManager(runtime, schema, standalone, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        geo = false
        val original = source("https://example.invalid/initial")
        val id = repo.importText("good", original).id
        val prepared = repo.prepareForStart(id)
        val index = File(temporary.root, "profiles/index.json").readBytes()
        val record = org.json.JSONObject(File(prepared.resourceDirectory!!, "manifest.json").readText()).getJSONArray("records").getJSONObject(0)
        val asset = File(prepared.resourceDirectory, record.getString("file")); val bytes = asset.readBytes()
        geo = true
        val failure = try { repo.replace(id, residual); error("pending-context result was treated as admission") } catch (failure: ConfigValidationException) { failure }
        assertEquals("$.rule-providers.first", failure.issues.single().path)
        assertEquals(1, finalGeoCalls); assertEquals(2, preflightCalls); assertEquals(1, standaloneCalls)
        assertArrayEquals(index, File(temporary.root, "profiles/index.json").readBytes()); assertArrayEquals(bytes, asset.readBytes())
        assertEquals(original, repo.readConfig(id))
    }

    @Test fun malformedOrInconsistentPreflightReceiptNeverBecomesPendingSuccess() {
        val good = preflightWire("classical", "yaml", true)
        val receipt = RuleResourcePreflight.parse(good, "classical", "yaml")
        assertTrue(receipt.requiresPreparedContext); assertTrue(receipt.geoip); assertTrue(receipt.asn)
        val invalid = listOf(
            org.json.JSONObject(good).put("count", -1).toString(),
            org.json.JSONObject(good).put("count", 1.5).toString(),
            org.json.JSONObject(good).put("behavior", "domain").toString(),
            org.json.JSONObject(good).put("state", "validated").toString(),
            org.json.JSONObject(good).put("extra", true).toString())
        invalid.forEach { raw -> assertTrue(runCatching { RuleResourcePreflight.parse(raw, "classical", "yaml") }.isFailure) }
    }

    @Test fun preflightDecodeFailureDoesNotInvokeFinalConversionOrPublish() = runBlocking<Unit> {
        var typed = true
        val nativeCause = IllegalArgumentException("rule_set_invalid: invalid MRS magic")
        val schema = CompatibilityValidator.forMeow(NativeConfigValidator { error("premature conversion") }, runtime,
            NativePreparedConfigValidator { _, _ -> error("decode failure reached full conversion") },
            NativePreparedRuleValidator { _, _, _ ->
                if (typed) throw ConfigValidationException(listOf(ConfigIssue("$.payload", "rule_set_invalid: invalid MRS magic")))
                throw nativeCause
            }) { emptySet() }
        val fetch = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val digest = byteArrayOf(0, 1).inputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() = Unit
        } }
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> error("unexpected fallback") }, ResourceDownloader(), providerDownloads = fetch)
        val repo = ProfileRepository(temporary.root, schema, resources = manager)
        val failure = try { repo.importText("bad decode", source("https://example.invalid/bad").replace("format: yaml", "format: mrs")); error("decode error admitted") } catch (failure: ConfigValidationException) { failure }
        assertEquals("$.payload", failure.issues.single().path); assertEquals("rule_set_invalid: invalid MRS magic", failure.issues.single().reason)
        typed = false
        val wrapped = try { repo.importText("JNI decode error", source("https://example.invalid/bad").replace("format: yaml", "format: mrs")); error("native decode error admitted") } catch (failure: ConfigValidationException) { failure }
        assertEquals("$.rule-providers.first", wrapped.issues.single().path)
        assertTrue(wrapped.issues.single().reason.contains("rule_set_invalid: invalid MRS magic")); assertSame(nativeCause, wrapped.cause)
        assertTrue(repo.list().isEmpty()); assertFalse(File(temporary.root, "profiles/index.json").exists())
    }

    private fun ownedManager(schema: CompatibilityValidator) = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null },
        ResourceDownloader(), providerDownloads = ProviderDownloadFactory { object : ProviderDownloadScope {
            override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                val digest = "payload: [example.com]\n".byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                return ProviderDownloadResult(digest.bytes, digest.sha256)
            }
            override suspend fun closeAndJoin() = Unit
        } })
    /** Fake JNI edges under the production preparedResourceValidator: closeJoin must see the worker exited and the cache intact. */
    private inner class OwnedConversion {
        val entered = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val exited = AtomicBoolean(); val joined = AtomicBoolean(); val cacheAtJoin = AtomicBoolean()
        @Volatile var generation: File? = null
        @Volatile var cache: File? = null
        private val native = object : dev.aerove.lite.core.OfflineResourceNative {
            override fun create(plan: String): Long { assertTrue(org.json.JSONObject(plan).getBoolean("offline")); return 56 }
            override fun cancel(handle: Long) { assertEquals(56L, handle); cancelled.countDown() }
            override fun closeJoin(handle: Long) {
                assertEquals(56L, handle); assertTrue(exited.get()); assertTrue(generation!!.isDirectory)
                cacheAtJoin.set(cache!!.isDirectory); joined.set(true)
            }
            override fun validatePrepared(handle: Long, candidate: String, readRoot: String, cacheRoot: String): String? {
                assertEquals(56L, handle); generation = File(readRoot); cache = File(cacheRoot); assertTrue(cache!!.isDirectory)
                entered.countDown()
                check(cancelled.await(10, TimeUnit.SECONDS)) { "native cancellation never reached the conversion worker" }
                exited.set(true); error("resourceReadCancelled")
            }
        }
        val production = dev.aerove.lite.core.preparedResourceValidator(temporary.root, temporary.newFolder("validation-cache"), native)
        fun assertJoinedThenCacheRemoved() {
            assertTrue(exited.get()); assertTrue(joined.get()); assertTrue(cacheAtJoin.get()); assertFalse(cache!!.exists())
        }
    }

    @Test fun preparedConversionCancelReachesTheOwnedWorkerAndJoinsBeforeTheGenerationIsRemoved() = runBlocking<Unit> {
        val conversion = OwnedConversion()
        val schema = validator(conversion.production)
        val repo = ProfileRepository(temporary.root, schema, resources = ownedManager(schema))
        val job = async(Dispatchers.Default) { repo.importText("prepared cancel", source("https://example.invalid/rules")) }
        assertTrue(withContext(Dispatchers.IO) { conversion.entered.await(5, TimeUnit.SECONDS) })
        job.cancel(); withTimeout(5000) { job.join() }
        assertTrue(job.isCancelled); conversion.assertJoinedThenCacheRemoved()
        assertFalse(conversion.generation!!.exists())
        assertFalse(File(temporary.root, "profiles/index.json").exists()); assertTrue(repo.list().isEmpty())
    }

    @Test fun startupConversionCancelJoinsTheOwnedWorkerAndKeepsTheSealedGeneration() = runBlocking<Unit> {
        val conversion = OwnedConversion(); var owned = false
        val schema = validator(NativePreparedConfigValidator { yaml, bundle -> if (owned) conversion.production.validate(yaml, bundle) else null })
        val repo = ProfileRepository(temporary.root, schema, resources = ownedManager(schema))
        val profile = repo.importText("startup cancel", source("https://example.invalid/rules"))
        val index = File(temporary.root, "profiles/index.json").readBytes()
        owned = true
        val job = async(Dispatchers.Default) { repo.prepareForStart(profile.id) }
        assertTrue(withContext(Dispatchers.IO) { conversion.entered.await(5, TimeUnit.SECONDS) })
        job.cancel(); withTimeout(5000) { job.join() }
        assertTrue(job.isCancelled); conversion.assertJoinedThenCacheRemoved()
        assertTrue(conversion.generation!!.isDirectory)
        assertArrayEquals(index, File(temporary.root, "profiles/index.json").readBytes())
    }
}
