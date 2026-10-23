package dev.aerove.lite.resources

import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ResourceDownloader
import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.data.SubscriptionDownloader
import dev.aerove.lite.data.sha256
import dev.aerove.lite.profilebuilder.BuilderEntry
import dev.aerove.lite.profilebuilder.BuilderIcon
import dev.aerove.lite.profilebuilder.BuilderIconSet
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResources
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.ScriptLibrarySession
import dev.aerove.lite.overrides.ScriptResource
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceManagementGeoTest {
    @get:Rule val temporary = TemporaryFolder()
    private val runtime get() = File(temporary.root, "runtime")
    private val validator get() = CompatibilityValidator(NativeConfigValidator { null }, runtime)
    private fun preferences(server: MockWebServer, enabled: Boolean = false): GeoResourcePreferences =
        GeoResourcePreferences(enabled, urls = GeoResourceKind.entries.associateWith { server.url("/${it.wire}").toString() })

    @Test fun defaultAndSavedPreferenceCodecMatchesStandardAndRejectsPresentInvalidFields() {
        val defaults = GeoResourcePreferences()
        assertFalse(defaults.autoUpdate); assertEquals(24, defaults.intervalHours)
        assertEquals("https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb", defaults.urls[GeoResourceKind.MMDB])
        assertEquals(defaults, GeoResourcePreferencesJson.decode(GeoResourcePreferencesJson.encode(defaults)))
        val changed = defaults.withUrl(GeoResourceKind.ASN, "https://example.com/asn")
        val saved = temporary.newFile("geo-settings.json").apply { writeText(GeoResourcePreferencesJson.encode(changed).toString()) }
        assertEquals(changed, GeoResourcePreferencesJson.decode(JSONObject(saved.readText())))
        listOf(JSONObject().put("autoUpdate", "true"), JSONObject().put("intervalHours", -1), JSONObject().put("urls", JSONObject.NULL),
            JSONObject().put("urls", JSONObject().put("geoip", "https://example.com/unsupported")),
            JSONObject().put("urls", JSONObject().put("mmdb", JSONObject.NULL))).forEach {
            try { GeoResourcePreferencesJson.decode(it); fail("Accepted malformed geo preferences") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun visibleMaintenanceDownloadsSavesReadsBackAndHonorsOffIntervalAndCurrentGate() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            var settings = preferences(server)
            var now = 1_700_000_000_000L
            val validated = mutableListOf<String>()
            val catalog = GeoResourceCatalog(runtime, validator, NativeResourceValidator { kind, path, behavior, format ->
                assertTrue(File(path).readText().startsWith("native-fixture-")); assertEquals("", behavior); assertEquals("", format)
                validated += kind; null
            }, ResourceDownloader(), { settings }, { now })
            assertTrue(catalog.snapshot().all { it.saved == null })
            assertTrue(catalog.updateIfDue { true }.updated.isEmpty())
            assertEquals(0, server.requestCount)
            settings = settings.copy(autoUpdate = true)
            repeat(3) { server.enqueue(MockResponse().setBody("native-fixture-$it")) }
            assertEquals(GeoResourceKind.entries, catalog.updateIfDue { true }.updated)
            assertEquals(listOf("mmdb", "mmdb", "geosite"), validated)
            val saved = catalog.snapshot()
            assertTrue(saved.all { it.saved!!.bytes > 0 && it.saved.updatedAt == now })
            assertTrue(catalog.updateIfDue { true }.updated.isEmpty())
            now += 24 * 3_600_000L
            assertTrue(catalog.updateIfDue { true }.updated.isEmpty())
            now++
            assertTrue(catalog.updateIfDue { false }.updated.isEmpty())
            repeat(3) { server.enqueue(MockResponse().setBody("native-fixture-new-$it")) }
            assertEquals(3, catalog.updateIfDue { true }.updated.size)
            assertEquals(6, server.requestCount)
            assertNotEquals(saved.first().saved!!.sha256, catalog.snapshot().first().saved!!.sha256)
        }
    }

    @Test fun changedUrlCancelsOldIntentAndNativeFailureKeepsPriorSavedAsset() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("native-fixture-original"))
            var settings = preferences(server)
            var changeUrl = false
            var reject = false
            val catalog = GeoResourceCatalog(runtime, validator, NativeResourceValidator { _, _, _, _ ->
                if (changeUrl) settings = settings.withUrl(GeoResourceKind.MMDB, server.url("/new").toString())
                if (reject) "native fixture rejected asset" else null
            }, ResourceDownloader(), { settings })
            catalog.sync(GeoResourceKind.MMDB)
            val before = catalog.snapshot().first().saved
            reject = true; server.enqueue(MockResponse().setBody("native-fixture-invalid"))
            try { catalog.sync(GeoResourceKind.MMDB); fail() } catch (_: IllegalStateException) { }
            assertEquals(before, catalog.snapshot().first().saved)
            reject = false; changeUrl = true; server.enqueue(MockResponse().setBody("native-fixture-old-intent"))
            try { catalog.sync(GeoResourceKind.MMDB); fail() } catch (_: CancellationException) { }
            assertNull(catalog.snapshot().first().saved)
        }
    }

    @Test fun cacheRestoreSharesMutexHasReentrantReadbackAndKeepsOldPayloadForJournalRollback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("native-fixture-original"))
            val cache = ResourceManagementRuleCache(File(runtime, "resources/library"), validator,
                NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(), { 123L })
            val url = server.url("/mmdb").toString()
            val old = cache.sync("mmdb", url, "", "", assetKind = "mmdb")
            val nextPayload = "native-fixture-import"
            val replacement = old.copy(bytes = nextPayload.toByteArray().size.toLong(), sha256 = sha256(nextPayload), updatedAt = 234L)
            val independent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            lateinit var concurrent: kotlinx.coroutines.Deferred<ManagedRuleResource>
            cache.withRestoreTransaction { transaction ->
                val paths = transaction.journalPaths(listOf(old))
                assertEquals(1, paths.size); assertTrue(paths.single().name.matches(Regex("[0-9a-f]{64}\\.json")))
                val originalIndex = paths.single().readBytes()
                transaction.importResource(replacement, ByteArrayInputStream(nextPayload.toByteArray()))
                assertEquals(replacement, withTimeout(2000) { cache.snapshot("mmdb", url, "", "", "mmdb") })
                paths.single().writeBytes(originalIndex)
                assertEquals(old, cache.snapshot("mmdb", url, "", "", "mmdb"))
                server.enqueue(MockResponse().setBody("native-fixture-concurrent"))
                val started = CompletableDeferred<Unit>()
                concurrent = independent.async { started.complete(Unit); cache.sync("mmdb", url, "", "", assetKind = "mmdb") }
                started.await(); delay(100)
                assertFalse(concurrent.isCompleted); assertEquals(1, server.requestCount)
            }
            assertNotEquals(old.sha256, withTimeout(3000) { concurrent.await() }.sha256)
            independent.cancel()
        }
    }

    @Test fun globalMaintenanceUsesUniqueLibrariesAndManualAutomaticAndCoreVisibilityGates() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            var settings = preferences(server)
            var now = System.currentTimeMillis() + 1000
            val native = NativeResourceValidator { _, _, _, _ -> null }
            val inspector = object : dev.aerove.lite.data.NativeRulePayloadInspector {
                override fun inspect(path: String): dev.aerove.lite.data.RulePayloadInspection {
                    require(File(path).readText() == "payload: ['+.example.com']")
                    return dev.aerove.lite.data.RulePayloadInspection("yaml", "domain", 1)
                }
                override fun dump(path: String, behavior: String, format: String): String {
                    require(behavior == "domain" && format == "yaml")
                    inspect(path)
                    return "+.example.com\n"
                }
            }
            val manager = ResourceBundleManager(runtime, validator, native, clock = { now }, offlineRules = inspector)
            val library = BuilderResourceLibrary(temporary.root)
            val iconUrl = server.url("/icons").toString()
            library.replace(library.snapshot(), BuilderResources(ruleProviders = listOf(BuilderEntry("Rules", mapOf(
                "type" to "http", "url" to server.url("/rules").toString(), "path" to "rules.yaml", "behavior" to "domain"))),
                iconSets = listOf(BuilderIconSet("Icons", iconUrl, listOf(BuilderIcon("old", "https://example.com/old.png")), 1L))))
            val profiles = dev.aerove.lite.data.ProfileRepository(temporary.root, validator, resources = manager, globalResources = library)
            val ruleLibrary = ResourceManagementLibraryRepository(library, manager,
                dev.aerove.lite.data.GlobalRulePayloadRepository(temporary.root, library, manager, inspector, profiles))
            val scriptRepo = OverrideRepository(temporary.root)
            scriptRepo.putScript(ScriptResource("script", "Remote", server.url("/script").toString(), "old", 1L))
            val scripts = ScriptLibrarySession(scriptRepo, SubscriptionDownloader())
            val catalog = GeoResourceCatalog(runtime, validator, native, ResourceDownloader(), { settings }, { now })
            var applied = 0
            val maintenance = ResourceManagementMaintenance(catalog, ruleLibrary, scripts, { settings }, { applied++ }, { now })
            assertTrue(maintenance.updateIfDue({ true }, { true }).updated.isEmpty())
            assertEquals(0, server.requestCount)
            val icons = """{"name":"Icons","icons":[{"name":"New","url":"https://example.com/new.png"}]}"""
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/rules" -> MockResponse().setBody("payload: ['+.example.com']")
                    "/icons" -> MockResponse().setBody(icons)
                    "/script" -> MockResponse().setBody("function main(config) { return config; }")
                    "/mmdb", "/asn", "/geosite" -> MockResponse().setBody("geo-fixture:${request.path}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val iconId = "icon:" + library.snapshot().resources.enumerate().single {
                it.kind == dev.aerove.lite.profilebuilder.BuilderResourceKind.IconSet
            }.id
            val manual = maintenance.updateAll({ true }, { true })
            assertEquals(6, manual.updated.size); assertTrue(manual.failed.isEmpty()); assertEquals(6, server.requestCount); assertEquals(1, applied)
            assertEquals("New", library.snapshot().resources.iconSets.single().icons.single().name)
            assertTrue(scriptRepo.snapshot().scripts.single().content.contains("function main"))
            settings = settings.copy(autoUpdate = true)
            assertTrue(maintenance.updateIfDue({ true }, { true }).updated.isEmpty())
            now += 24 * 3_600_000L + 1
            assertTrue(maintenance.updateIfDue({ false }, { true }).updated.isEmpty())
            val stopped = maintenance.updateIfDue({ true }, { false })
            assertEquals(setOf(iconId, "script:script"), stopped.updated)
            assertEquals(8, server.requestCount)
        }
    }

    @Test fun overlappingManualAndMaintenanceRequestsShareOneDownloadPerResource() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val settings = preferences(server)
            val native = NativeResourceValidator { _, _, _, _ -> null }
            val catalog = GeoResourceCatalog(runtime, validator, native, ResourceDownloader(), { settings })
            server.enqueue(MockResponse().setBody("geo-fixture").setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS))
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { catalog.sync(GeoResourceKind.MMDB) }
                val second = async(start = CoroutineStart.UNDISPATCHED) { catalog.sync(GeoResourceKind.MMDB) }
                assertEquals(first.await(), second.await())
            }
            assertEquals(1, server.requestCount)
            val library = BuilderResourceLibrary(temporary.root)
            library.replace(library.snapshot(), BuilderResources(ruleProviders = listOf(BuilderEntry("Rules", mapOf(
                "type" to "http", "url" to server.url("/rules").toString(), "path" to "rules.yaml", "behavior" to "domain")))))
            val resources = ResourceManagementLibraryRepository(library, ResourceBundleManager(runtime, validator, native))
            server.enqueue(MockResponse().setBody("payload: ['+.example.com']").setBodyDelay(150, java.util.concurrent.TimeUnit.MILLISECONDS))
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { resources.sync("rule:Rules") }
                val second = async(start = CoroutineStart.UNDISPATCHED) { resources.sync("rule:Rules") }
                assertEquals(first.await(), second.await())
            }
            assertEquals(2, server.requestCount)
        }
    }
}
