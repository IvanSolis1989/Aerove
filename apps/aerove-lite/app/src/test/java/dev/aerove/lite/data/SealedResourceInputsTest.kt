package dev.aerove.lite.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SealedResourceInputsTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Fixture(root: File) {
        val runtime = File(root, "runtime")
        var downloads = 0
        var open: suspend (ProviderDownloadContext) -> Unit = {}
        var onDownload: suspend (ProviderDownloadRequest) -> Unit = { request ->
            request.destination.writeText(if (request.url.endsWith("/proxy"))
                "proxies: [{name: child, type: http, server: 127.0.0.1, port: 1080}]\n" else "payload: [example.com]\n")
        }
        var close: suspend () -> Unit = {}
        val manager = ResourceBundleManager(runtime,
            CompatibilityValidator.forMeow(NativeConfigValidator { error("Unsealed validation") }, runtime,
                NativePreparedConfigValidator { _, _ -> null }) { emptySet() },
            NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), providerDownloads = ProviderDownloadFactory { context ->
                open(context)
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        downloads++; onDownload(request)
                        return ProviderDownloadResult(request.destination.length(), sha256(request.destination))
                    }
                    override suspend fun closeAndJoin() { close() }
                }
            })
        val sealer = FlutterResourceSealer(manager, "AEROVE/review84")
        val rule = "rule-providers: {old: {type: http, url: 'https://example.invalid/rule', path: old.yaml, behavior: domain, proxy: DIRECT}}\nrules: ['MATCH,DIRECT']\n"
        fun mixed(yaml: String): String {
            val root = ConfigYaml.parse(yaml)
            @Suppress("UNCHECKED_CAST")
            val providers = root.getOrPut("rule-providers") { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
            providers["new$downloads"] = mapOf("type" to "http", "url" to "https://example.invalid/new", "path" to "new.yaml", "behavior" to "domain", "proxy" to "DIRECT")
            return ConfigYaml.dump(root)
        }
    }

    @Test fun severalGenerationsPreserveOwnedAssetsAndFlatLineage() = runBlocking<Unit> {
        val f = Fixture(temporary.root)
        val profile = UUID.randomUUID().toString()
        val bundle = f.manager.prepare(profile,
            "proxy-providers: {old: {type: http, url: 'https://example.invalid/proxy', path: proxy.yaml, proxy: DIRECT}}\nrules: ['MATCH,DIRECT']\n",
            requestPolicy = ProviderRequestPolicy("AEROVE/review84", true))
        val old = f.sealer.borrow(bundle.yaml, bundle.directory)
        f.manager.discard(profile, bundle.generation)
        val second = f.sealer.prepare(f.mixed(old.yaml), true)
        val third = f.sealer.prepare(f.mixed(second.yaml), true)
        try {
            val providers = ConfigYaml.parse(third.yaml)["proxy-providers"] as Map<*, *>
            val payload = File((providers["old"] as Map<*, *>)["path"] as String)
            val node = (ConfigYaml.parse(payload.readText())["proxies"] as List<*>).single() as Map<*, *>
            assertEquals("child", node["name"])
            assertTrue(payload.toPath().startsWith(third.directory.toPath().resolve("assets")))
            val manifest = JSONObject(File(third.directory, "manifest.json").readText())
            val archived = third.resources.filter { it.relativePath.startsWith("lineage/") }
            assertEquals(6, archived.size)
            assertTrue(archived.all { it.relativePath.split('/').size == 4 })
            assertEquals(sha256(File(second.directory, "manifest.json")), manifest.getJSONArray("sealedInputs").getJSONObject(0).getString("manifestSha256"))
            old.closeAndJoin(); second.closeAndJoin()
            f.manager.prune(emptyMap(), emptySet())
            assertFalse(old.directory.exists()); assertTrue(second.directory.isDirectory)
        } finally { third.closeAndJoin(); second.closeAndJoin(); old.closeAndJoin() }
        assertFalse(old.directory.exists()); assertFalse(second.directory.exists()); assertFalse(third.directory.exists())
    }

    @Test fun arbitraryAbsoluteUnrecordedAndTamperedFilesStayRejected() = runBlocking<Unit> {
        val f = Fixture(temporary.root)
        val old = f.sealer.prepare(f.rule, true)
        try {
            val raw = ConfigYaml.parse(old.yaml)
            @Suppress("UNCHECKED_CAST")
            val oldProvider = (raw["rule-providers"] as Map<*, *>)["old"] as MutableMap<String, Any?>
            val original = oldProvider["path"] as String
            val outside = File(temporary.root, "outside.yaml").apply { writeText("payload: [outside.example]\n") }
            val unrecorded = File(old.directory, "assets/unrecorded.yaml").apply { writeText("payload: [unrecorded.example]\n") }
            for (path in listOf(outside.path, unrecorded.path, File(old.directory, "input.yaml").path)) {
                oldProvider["path"] = path
                assertTrue(runCatching { f.sealer.prepare(f.mixed(ConfigYaml.dump(raw)), true) }.isFailure)
            }
            oldProvider["path"] = original
            val bytes = File(original).readBytes()
            File(original).appendText("# changed\n")
            assertTrue(runCatching { f.sealer.prepare(f.mixed(ConfigYaml.dump(raw)), true) }.isFailure)
            File(original).writeBytes(bytes)
            assertEquals(1, f.downloads)
        } finally { old.closeAndJoin() }
        assertFalse(old.directory.exists())
    }

    @Test fun nestedTlsInHttpPayloadKeepsExistingExactSchemaFailureAndOldGeneration() = runBlocking<Unit> {
        val f = Fixture(temporary.root)
        val old = f.sealer.prepare(f.rule, true)
        val config = File(old.directory, "config.yaml").readBytes()
        f.onDownload = { request ->
            request.destination.writeText("proxies: [{name: child, type: http, server: 127.0.0.1, port: 1080, ca: ca.pem}]\n")
        }
        val root = ConfigYaml.parse(old.yaml)
        root["proxy-providers"] = mapOf("new" to mapOf("type" to "http", "url" to "https://example.invalid/proxy", "path" to "proxy.yaml", "proxy" to "DIRECT"))
        try {
            val failure = runCatching { f.sealer.prepare(ConfigYaml.dump(root), true) }.exceptionOrNull()
            assertTrue(failure is ConfigValidationException)
            assertEquals("$.proxies[0].ca", (failure as ConfigValidationException).issues.single().path)
            assertArrayEquals(config, File(old.directory, "config.yaml").readBytes())
            f.manager.prune(emptyMap(), emptySet()); assertTrue(old.directory.isDirectory)
        } finally { old.closeAndJoin() }
        assertFalse(old.directory.exists())
    }

    @Test fun cancellationJoinsProducerThenReleasesOldReaderAndRemovesCandidate() = runBlocking<Unit> {
        val f = Fixture(temporary.root)
        val old = f.sealer.prepare(f.rule, true)
        val entered = CompletableDeferred<Unit>(); val closing = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var candidate: File? = null
        f.open = { context ->
            candidate = context.stagingRoot.parentFile
            old.closeAndJoin(); f.manager.prune(emptyMap(), emptySet())
            assertTrue(old.directory.isDirectory)
        }
        f.onDownload = { entered.complete(Unit); awaitCancellation() }
        f.close = { closing.complete(Unit); release.await() }
        val work = async { f.sealer.prepare(f.mixed(old.yaml), true) }
        withTimeout(5000) { entered.await() }; work.cancel()
        withTimeout(5000) { closing.await() }
        assertFalse(work.isCompleted); assertTrue(old.directory.isDirectory); assertTrue(candidate!!.isDirectory)
        release.complete(Unit); withTimeout(5000) { work.join() }
        assertTrue(work.isCancelled); assertFalse(old.directory.exists()); assertFalse(candidate!!.exists())
    }

    @Test fun oldSourceMutationDuringNewHttpPreventsCandidateAndDoesNotChangeOldConfiguration() = runBlocking<Unit> {
        val f = Fixture(temporary.root)
        val old = f.sealer.prepare(f.rule, true)
        val originalConfig = File(old.directory, "config.yaml").readBytes()
        val originalManifest = File(old.directory, "manifest.json").readBytes()
        val input = File(old.directory, "input.yaml")
        val inputBytes = input.readBytes()
        f.open = { input.appendText("# mutated\n") }
        try {
            assertTrue(runCatching { f.sealer.prepare(f.mixed(old.yaml), true) }.isFailure)
            assertArrayEquals(originalConfig, File(old.directory, "config.yaml").readBytes())
            assertArrayEquals(originalManifest, File(old.directory, "manifest.json").readBytes())
            input.writeBytes(inputBytes)
            f.manager.prune(emptyMap(), emptySet())
            assertTrue(old.directory.isDirectory)
            val generations = File(f.runtime, "resources/bundles").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }
            assertEquals(listOf(old.directory), generations)
        } finally { old.closeAndJoin() }
        assertFalse(old.directory.exists())
    }
}
