package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceBundleManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private val runtime get() = File(temporary.root, "runtime")
    private var now = 1_700_000_000_000L
    private val configValidator by lazy { CompatibilityValidator(NativeConfigValidator { null }, runtime) }
    private val calls = mutableListOf<List<String>>()
    private var rejectResources = false
    private val resourceValidator = NativeResourceValidator { kind, path, behavior, format ->
        assertTrue(File(path).isFile)
        assertFalse(path.contains(".pending-"))
        calls += listOf(kind, path, behavior, format)
        if (rejectResources) "native fixture rejected payload" else null
    }

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun repository(native: NativeResourceValidator = resourceValidator, downloader: ResourceDownloader = ResourceDownloader()): ProfileRepository {
        val manager = ResourceBundleManager(runtime, configValidator, native, downloader) { now }
        return ProfileRepository(temporary.root, configValidator, clock = { now }, resources = manager)
    }

    private fun ruleSource(path: String = "cache/rules.yaml", interval: Int = 60): String = """
        rule-providers:
          remote:
            type: http
            url: '${server.url("/rules")}'
            path: '$path'
            behavior: domain
            interval: $interval
        rules: ['RULE-SET,remote,DIRECT', 'MATCH,DIRECT']
    """.trimIndent()

    private fun payload(domain: String = "+.example.com") = MockResponse().setBody("payload:\n - '$domain'\n")
    private fun index() = File(temporary.root, "profiles/index.json")
    private fun provider(prepared: PreparedProfile, namespace: String = "rule-providers") =
        (ConfigYaml.parse(prepared.yaml)[namespace] as Map<*, *>).values.single() as Map<*, *>

    @Test fun sameRelativePathInDifferentProfilesHasSeparateSealedFilesAndHashes() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload("+.first.example"))
        server.enqueue(payload("+.second.example"))
        val first = repo.prepareForStart(repo.importText("First", ruleSource()).id)
        val second = repo.prepareForStart(repo.importText("Second", ruleSource()).id)
        val a = File(provider(first)["path"] as String)
        val b = File(provider(second)["path"] as String)
        assertNotEquals(a, b)
        assertTrue(a.readText().contains("first.example"))
        assertTrue(b.readText().contains("second.example"))
        assertEquals("file", provider(first)["type"])
        assertEquals(0, provider(first)["interval"])
        assertFalse(provider(first).containsKey("url"))
        val manifest = JSONObject(File(first.resourceDirectory!!, "manifest.json").readText())
        assertEquals(sha256(first.source), manifest.getString("sourceSha256"))
        val record = manifest.getJSONArray("records").getJSONObject(0)
        assertEquals(sha256(a), record.getString("sha256"))
        assertEquals(64, record.getString("sourceSha256").length)
        val composed = ConfigYaml.parse(RuntimeConfigComposer(runtime).compose(first, LiteSettings(), 42, 49152, "x".repeat(32)))
        assertEquals(provider(first), (composed["rule-providers"] as Map<*, *>).values.single())
        assertEquals(2, calls.size)
    }

    @Test fun failedRefreshKeepsSourceIndexAndPreviouslyUsableGeneration() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource()).id
        val working = repo.prepareForStart(id)
        repo.markUsable(working)
        val before = index().readBytes()
        val workingBytes = File(provider(working)["path"] as String).readBytes()
        rejectResources = true
        server.enqueue(payload("+.bad.example"))
        val failure = assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshResources(id) } }
        assertEquals("$.rule-providers.remote", failure.issues.single().path)
        assertArrayEquals(before, index().readBytes())
        assertEquals(working.source, repo.readConfig(id))
        assertArrayEquals(workingBytes, File(provider(working)["path"] as String).readBytes())
        assertEquals(1, File(runtime, "resources/bundles/$id").listFiles()!!.size)
        assertEquals(working.generation, repo.prepareForStart(id).generation)
        server.enqueue(MockResponse().setResponseCode(503))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshResources(id) } }
        assertArrayEquals(before, index().readBytes())
    }

    @Test fun dueRefreshChangesGenerationAndCannotConfirmAnOldPreparedRevision() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource(interval = 60)).id
        val first = repo.prepareForStart(id)
        assertEquals(now + 60_000, first.nextRefreshAt)
        assertNull(repo.refreshIfDue(id))
        now += 60_000
        server.enqueue(payload("+.new.example"))
        val next = checkNotNull(repo.refreshIfDue(id))
        assertEquals(first.source, next.source)
        assertNotEquals(first.generation, next.generation)
        assertThrows(ProfileConflictException::class.java) { runBlocking { repo.markUsable(first) } }
        repo.markUsable(next)
        assertTrue(first.resourceDirectory!!.isDirectory)
        assertEquals(now + 60_000, next.nextRefreshAt)
    }

    @Test fun malformedRuleYamlAndNestedProxyFieldsAreNotSilentlyDiscarded() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(MockResponse().setBody("payload: ['+.example.com']\nignored: true"))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.importText("Rules", ruleSource()) } }
        val source = """
            proxy-providers:
              remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml}
            proxy-groups: [{name: Auto, type: select, use: [remote]}]
            rules: ['MATCH,Auto']
        """.trimIndent()
        server.enqueue(MockResponse().setBody("proxies:\n - {name: node, type: vless, client-fingerprint: chrome}"))
        val failure = assertThrows(ConfigValidationException::class.java) { runBlocking { repo.importText("Proxies", source) } }
        assertTrue(failure.issues.any { it.path == "$.proxies[0].client-fingerprint" })
        assertTrue(repo.list().isEmpty())
    }

    @Test fun mrsUsesNativeLoaderAndDoesNotDropInlineRules() = runBlocking<Unit> {
        val repo = repository()
        val source = ruleSource().replace("behavior: domain", "behavior: domain\n    format: mrs")
        rejectResources = true
        server.enqueue(MockResponse().setBody("not-an-mrs"))
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.importText("MRS", source) } }
        assertEquals(listOf("ruleset", "domain", "mrs"), listOf(calls.single()[0], calls.single()[2], calls.single()[3]))
        val withInline = source.replace("format: mrs", "format: mrs\n    payload: ['+.inline.example']")
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.importText("Inline MRS", withInline) } }
        assertTrue(repo.list().isEmpty())
    }

    @Test fun inlineAndDownloadedPayloadBothReachValidationAndRuntime() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val source = ruleSource().replace("behavior: domain", "behavior: domain\n    payload: ['+.inline.example']")
        val prepared = repo.prepareForStart(repo.importText("Combined", source).id)
        assertEquals(listOf("+.inline.example"), provider(prepared)["payload"])
        assertEquals(2, calls.count { it[0] == "ruleset" })
        assertTrue(calls.any { File(it[1]).readText().contains("inline.example") })
        assertTrue(File(provider(prepared)["path"] as String).readText().contains("example.com"))
    }

    @Test fun fixedCoreDefaultsAreDownloadedAndDelegatedToNativeResourceValidators() = runBlocking<Unit> {
        val urls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("native-loader-fixture".toResponseBody()).build()
        }.build()
        val repo = repository(downloader = ResourceDownloader(client))
        val id = repo.importText("Geo", "rules: ['GEOIP,CN,DIRECT', 'IP-ASN,13335,DIRECT', 'GEOSITE,cn,DIRECT', 'MATCH,DIRECT']").id
        val prepared = repo.prepareForStart(id)
        assertEquals(listOf(ResourceBundleManager.DEFAULT_COUNTRY_URL, ResourceBundleManager.DEFAULT_ASN_URL,
            ResourceBundleManager.DEFAULT_GEOSITE_URL), urls)
        assertEquals(listOf("mmdb", "mmdb", "geosite"), calls.map { it[0] })
        listOf("mmdb", "asn-mmdb", "geosite").forEach { key ->
            assertTrue(File(ConfigYaml.parse(prepared.yaml)[key] as String).isFile)
        }
    }

    @Test fun missingLocalResourcesAndEscapingHttpCachePathsFailBeforePublishing() = runBlocking<Unit> {
        val repo = repository()
        val missing = "rule-providers: {local: {type: file, path: absent.yaml, behavior: domain}}"
        val failure = assertThrows(ConfigValidationException::class.java) { runBlocking { repo.importText("Missing", missing) } }
        assertTrue(failure.message!!.contains("Local resource is missing"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.importText("Escape", ruleSource("../escape")) } }
        assertTrue(repo.list().isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test fun retainedRunningBundleSurvivesUpdatesAndDeletionUntilLeaseRelease() = runBlocking<Unit> {
        val repo = repository()
        val source = "rules: ['MATCH,DIRECT']"
        val id = repo.importText("Main", source).id
        val usable = repo.prepareForStart(id)
        repo.markUsable(usable)
        repo.replace(id, "$source\n# second")
        val running = repo.prepareForStart(id)
        val lease = repo.retain(running)
        repeat(4) { repo.replace(id, "$source\n# update $it") }
        assertEquals(4, File(runtime, "resources/bundles/$id").listFiles()!!.size)
        assertTrue(running.resourceDirectory!!.isDirectory)
        assertTrue(File(temporary.root, "profiles/${running.revision}").isFile)
        repo.release(lease)
        assertFalse(running.resourceDirectory!!.isDirectory)
        assertEquals(3, File(runtime, "resources/bundles/$id").listFiles()!!.size)
        val latest = repo.prepareForStart(id)
        val active = repo.retain(latest)
        repository().delete(id)
        assertTrue(latest.resourceDirectory!!.isDirectory)
        assertEquals(1, File(runtime, "resources/bundles/$id").listFiles()!!.size)
        repo.release(active)
        assertFalse(File(runtime, "resources/bundles/$id").exists())
        assertEquals(0, File(temporary.root, "profiles").listFiles()!!.count { it.extension == "yaml" })
    }

    @Test fun corruptLastUsableBundleCannotChangeTheCurrentPointerDuringRollback() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource()).id
        val usable = repo.prepareForStart(id)
        repo.markUsable(usable)
        server.enqueue(payload("+.new.example"))
        val next = repo.refreshResources(id)
        val before = index().readBytes()
        File(provider(usable)["path"] as String).writeText("tampered")
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.restoreLastUsable(id) } }
        assertArrayEquals(before, index().readBytes())
        assertEquals(next.generation, repo.prepareForStart(id).generation)
    }

    @Test fun confirmedRollbackRestoresTheExactSourceAndResourcePair() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload("+.usable.example"))
        val id = repo.importText("Main", ruleSource()).id
        val usable = repo.prepareForStart(id)
        repo.markUsable(usable)
        server.enqueue(payload("+.new.example"))
        repo.refreshResources(id)
        val restored = repo.restoreLastUsable(id)
        assertEquals(usable.source, restored.source)
        assertEquals(usable.generation, restored.generation)
        assertTrue(File(provider(restored)["path"] as String).readText().contains("usable.example"))
    }

    @Test fun cancellationAfterFinalDirectoryCreationCannotPublishOrLeakCandidate() = runBlocking<Unit> {
        lateinit var task: Deferred<*>
        val repo = repository(NativeResourceValidator { _, path, _, _ ->
            assertTrue(File(path).isFile)
            task.cancel()
            null
        })
        server.enqueue(payload())
        task = async(start = CoroutineStart.LAZY) { repo.importText("Cancelled", ruleSource()) }
        task.start()
        assertThrows(CancellationException::class.java) { runBlocking { task.await() } }
        assertTrue(repo.list().isEmpty())
        assertTrue(File(runtime, "resources/bundles").listFiles().orEmpty().isEmpty())
    }

    @Test fun reclaimedOrAlteredPreparedInputsCannotAcquireALease() = runBlocking<Unit> {
        val repo = repository()
        val source = "rules: ['MATCH,DIRECT']"
        val id = repo.importText("Main", source).id
        val first = repo.prepareForStart(id)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.retain(first.copy(yaml = "mode: global")) }
        }
        repo.replace(id, "$source\n# second")
        repo.replace(id, "$source\n# third")
        assertFalse(first.resourceDirectory!!.exists())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.retain(first) } }
        assertEquals(2, File(runtime, "resources/bundles/$id").listFiles()!!.size)
    }
    @Test fun resourceEditorRejectsStaleContentAndSourceLineageWithoutLosingNewPayload() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload("+.original.example"))
        val id = repo.importText("Main", ruleSource()).id
        val opened = repo.resourceEditSnapshot(id, "$.rule-providers.remote")
        repo.sideLoadResource(id, opened.key, "payload: ['+.new.example']".byteInputStream())
        val newer = repo.resourceEditSnapshot(id, opened.key)
        val indexBefore = index().readBytes()
        assertThrows(ProfileConflictException::class.java) { runBlocking {
            repo.replaceResource(opened, "payload: ['+.stale.example']".byteInputStream())
        } }
        assertArrayEquals(indexBefore, index().readBytes())
        assertArrayEquals(newer.bytes, repository().resourceEditSnapshot(id, opened.key).bytes)
        repo.replace(id, ruleSource() + "\nlog-level: debug")
        val changed = repo.documentSnapshot(id)
        assertThrows(ProfileConflictException::class.java) { runBlocking {
            repo.replaceResource(newer, "payload: ['+.stale.example']".byteInputStream())
        } }
        assertEquals(changed, repo.documentSnapshot(id))
        assertArrayEquals(newer.bytes, repo.resourceEditSnapshot(id, opened.key).bytes)
    }

    @Test fun resourceEditorSuccessReadsBackManifestAndKeepsLeasedGeneration() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload("+.original.example"))
        val id = repo.importText("Main", ruleSource()).id
        val old = repo.prepare(id)
        val lease = repo.retain(old)
        val oldFile = File(provider(old)["path"] as String)
        val oldBytes = oldFile.readBytes()
        try {
            val opened = repo.resourceEditSnapshot(id, "$.rule-providers.remote")
            val replacement = "payload: ['+.edited.example']"
            val applied = repo.replaceResource(opened, replacement.byteInputStream())
            val fresh = repository().resourceEditSnapshot(id, opened.key)
            assertEquals(replacement, fresh.bytes.toString(Charsets.UTF_8))
            assertNotEquals(opened.generation, fresh.generation)
            assertEquals(applied.generation, fresh.generation)
            val manifest = JSONObject(File(applied.resourceDirectory!!, "manifest.json").readText())
            assertEquals(fresh.contentSha256, manifest.getJSONArray("records").getJSONObject(0).getString("sha256"))
            assertEquals(sha256(replacement), fresh.contentSha256)
            assertArrayEquals(oldBytes, oldFile.readBytes())
            assertEquals(old.source, repo.readConfig(id))
        } finally { repo.release(lease) }
    }

    @Test fun resourceEditorCancellationRestoreAndNativeFailurePreserveCommittedContent() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload("+.original.example"))
        val id = repo.importText("Main", ruleSource()).id
        val opened = repo.resourceEditSnapshot(id, "$.rule-providers.remote")
        val before = index().readBytes()
        val cancelledInput = object : java.io.InputStream() {
            override fun read(): Int = throw CancellationException("Editor cancelled")
        }
        assertThrows(CancellationException::class.java) { runBlocking { repo.replaceResource(opened, cancelledInput) } }
        assertArrayEquals(before, index().readBytes())
        assertArrayEquals(opened.bytes, repo.resourceEditSnapshot(id, opened.key).bytes)
        rejectResources = true
        assertThrows(ConfigValidationException::class.java) { runBlocking {
            repo.replaceResource(opened, "payload: ['+.rejected.example']".byteInputStream())
        } }
        rejectResources = false
        assertArrayEquals(before, index().readBytes())
        assertArrayEquals(opened.bytes, repo.resourceEditSnapshot(id, opened.key).bytes)
        val archive = java.nio.file.Files.createTempFile("aerove-editor-restore-", ".zip").toFile()
        val backups = BackupRepository(temporary.root, repo, LiteSettingsStore(temporary.root))
        try {
            backups.createBackup(archive)
            server.enqueue(payload("+.original.example"))
            archive.inputStream().use { backups.restore(it, strategy = BackupRestoreStrategy.Override) }
        } finally { archive.delete() }
        assertTrue(runCatching { repo.replaceResource(opened, "payload: ['+.late.example']".byteInputStream()) }.isFailure)
        val restored = repository().list().single()
        assertArrayEquals(opened.bytes, repo.resourceEditSnapshot(restored.id, opened.key).bytes)
    }    @Test fun resourceEditorRejectsDerivedProxyProviderAndOffersOnlyOriginalSource() = runBlocking<Unit> {
        val repo = repository()
        val source = """
            proxy-providers:
              remote: {type: http, url: '${server.url("/proxies")}', path: providers/cache.yaml}
            proxy-groups: [{name: Auto, type: select, use: [remote]}]
            rules: ['MATCH,Auto']
        """.trimIndent()
        server.enqueue(MockResponse().setBody("proxies: [{name: node, type: socks5, server: node.example, port: 1080}]"))
        val id = repo.importText("Proxies", source).id
        val original = repo.resourceEditSnapshot(id, "$.proxy-providers.remote")
        assertTrue(original.bytes.toString(Charsets.UTF_8).contains("node.example"))
        val before = index().readBytes()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            repo.resourceEditSnapshot(id, "$.proxy-providers.remote.prepared")
        } }
        assertArrayEquals(before, index().readBytes())
    }
    @Test fun identicalDueRefreshKeepsGenerationAndAdvancesOnlyIndexReceipt() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource(interval = 60)).id
        val before = repo.prepareForStart(id)
        val manifest = File(before.resourceDirectory!!, "manifest.json").readBytes()
        now += 60_000
        server.enqueue(payload())
        assertNull(repo.refreshIfDue(id))
        val after = repo.refreshSchedule(id)
        assertEquals(before.generation, after.generation)
        assertEquals(now + 60_000, after.nextRefreshAt)
        assertArrayEquals(manifest, File(before.resourceDirectory, "manifest.json").readBytes())
        assertNull(repo.refreshIfDue(id))
        assertEquals(2, server.requestCount)
        assertEquals(1, File(runtime, "resources/bundles/$id").listFiles()!!.size)
    }

    @Test fun dueRefreshSkipsZeroIntervalAndPreservesLocalOverride() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource(interval = 0)).id
        now += 86_400_000
        assertNull(repo.refreshIfDue(id))
        assertEquals(1, server.requestCount)
        repo.sideLoadResource(id, "$.rule-providers.remote", "payload: ['+.local.test']".byteInputStream())
        now += 86_400_000
        assertNull(repo.refreshIfDue(id))
        assertTrue(File(provider(repo.prepareForStart(id))["path"] as String).readText().contains("local.test"))
        assertEquals(1, server.requestCount)
    }

    @Test fun dueRefreshDownloadsOnlyDueKeyAndDoesNotResetOtherDeadline() = runBlocking<Unit> {
        val repo = repository()
        val source = ConfigYaml.parse(ruleSource(interval = 60))
        @Suppress("UNCHECKED_CAST") val providers = source["rule-providers"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") val remote = providers["remote"] as Map<String, Any?>
        providers["slow"] = remote + mapOf("interval" to 120, "url" to server.url("/slow").toString())
        server.enqueue(payload()); server.enqueue(payload())
        val id = repo.importText("Main", ConfigYaml.dump(source)).id
        val first = repo.prepareForStart(id)
        now += 60_000
        server.enqueue(payload())
        assertNull(repo.refreshIfDue(id))
        assertEquals(3, server.requestCount)
        assertEquals(first.nextRefreshAt!! + 60_000, repo.refreshSchedule(id).nextRefreshAt)
        now += 60_000
        server.enqueue(payload()); server.enqueue(payload())
        assertNull(repo.refreshIfDue(id))
        assertEquals(5, server.requestCount)
    }

    @Test fun failedDueKeyRetriesWithoutRedownloadingSuccessfulKey() = runBlocking<Unit> {
        val repo = repository()
        val source = ConfigYaml.parse(ruleSource(interval = 60))
        @Suppress("UNCHECKED_CAST") val providers = source["rule-providers"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") val remote = providers["remote"] as Map<String, Any?>
        providers["second"] = remote + mapOf("url" to server.url("/second").toString())
        server.enqueue(payload()); server.enqueue(payload())
        val id = repo.importText("Main", ConfigYaml.dump(source)).id
        now += 60_000
        server.enqueue(MockResponse().setResponseCode(503)); server.enqueue(payload())
        assertThrows(ConfigValidationException::class.java) { runBlocking { repo.refreshIfDue(id) } }
        assertEquals(now + 10_000, repo.refreshSchedule(id).nextRefreshAt)
        assertNull(repo.refreshIfDue(id))
        assertEquals(4, server.requestCount)
        now += 10_000
        server.enqueue(payload())
        assertNull(repo.refreshIfDue(id))
        assertEquals(5, server.requestCount)
    }

    @Test fun automaticRefreshDoesNotDiscardSideLoadedDueResource() = runBlocking<Unit> {
        val repo = repository()
        server.enqueue(payload())
        val id = repo.importText("Main", ruleSource(interval = 60)).id
        repo.sideLoadResource(id, "$.rule-providers.remote", "payload: ['+.local.test']".byteInputStream())
        val before = repo.prepareForStart(id)
        now += 120_000
        assertNull(repo.refreshIfDue(id))
        val after = repo.refreshSchedule(id)
        assertEquals(before.generation, after.generation)
        assertTrue(File(provider(after)["path"] as String).readText().contains("local.test"))
        assertEquals(1, server.requestCount)
    }
}
