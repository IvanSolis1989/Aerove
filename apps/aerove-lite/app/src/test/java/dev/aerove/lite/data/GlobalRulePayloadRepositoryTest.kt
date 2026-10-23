package dev.aerove.lite.data

import dev.aerove.lite.profilebuilder.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Parser receipts are injected; these tests exercise real IO/CAS/journal, not JNI parsing. */
class GlobalRulePayloadRepositoryTest {
    private fun BuilderResourceSnapshot.id(name: String): String = checkNotNull(resources.ruleProviders.single { it.name == name }.id)
    @get:Rule val temporary = TemporaryFolder()
    private class Parser : NativeRulePayloadInspector {
        var failDump = false
        override fun inspect(path: String): RulePayloadInspection {
            if (File(path).readBytes().firstOrNull() == 0xff.toByte()) return RulePayloadInspection("mrs", "domain", 1)
            val text = File(path).readText()
            require(text != "invalid")
            return RulePayloadInspection("text", "domain", text.lineSequence().count { it.isNotBlank() })
        }
        override fun dump(path: String, behavior: String, format: String): String {
            check(!failDump) { "Injected parser failure" }
            if (format == "mrs") return "binary.example\n"
            return File(path).readText().lineSequence().filter { it.isNotBlank() }.joinToString("\n", postfix = if (File(path).length() == 0L) "" else "\n") { it.removePrefix("DOMAIN-SUFFIX,") }
        }
    }
    private data class Data(val root: File, val parser: Parser, val library: BuilderResourceLibrary,
        val profiles: ProfileRepository, val manager: ResourceBundleManager, val payloads: GlobalRulePayloadRepository)
    private fun data(name: String): Data {
        val root = temporary.newFolder(name)
        val parser = Parser()
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, offlineRules = parser)
        val library = BuilderResourceLibrary(root)
        val pipeline = ProfileSourcePipeline(library, dev.aerove.lite.overrides.OverridePipeline(
            dev.aerove.lite.overrides.OverrideRepository(root), dev.aerove.lite.overrides.ScriptEngine { _, source -> source }), { dev.aerove.lite.LiteSettings() })
        val profiles = ProfileRepository(root, validator, resources = manager, sourcePolicy = pipeline, globalResources = library)
        return Data(root, parser, library, profiles, manager, GlobalRulePayloadRepository(root, library, manager, parser, profiles))
    }

    @Test fun originReadbackAndCanonicalRuntimeAreIndependentAndLocalEmptyIsReal() = runBlocking<Unit> {
        val d = data("origin")
        val origin = "DOMAIN-SUFFIX,example.com\n"
        val saved = d.payloads.importFile(d.library.snapshot(), "one", origin.byteInputStream())
        assertEquals(origin, d.payloads.preview(saved, saved.id("one")).text)
        val fields = saved.resources.ruleProviders.single().fields
        val yaml = ConfigYaml.dump(mapOf("rule-providers" to mapOf("one" to fields), "rules" to listOf("RULE-SET,one,DIRECT", "MATCH,DIRECT")))
        val id = d.profiles.importText("Managed", yaml).id
        val prepared = d.profiles.prepare(id)
        val provider = (ConfigYaml.parse(prepared.yaml)["rule-providers"] as Map<*, *>)["one"] as Map<*, *>
        assertEquals("text", provider["format"])
        assertEquals("example.com\n", File(provider["path"] as String).readText())
        val empty = d.payloads.saveText(saved, null, "empty", "")
        assertEquals(0L, d.payloads.info(empty, empty.id("empty")).bytes)
        assertEquals("", d.payloads.preview(empty, empty.id("empty")).text)
    }

    @Test fun staleEditorCannotOverwriteNewPayloadAndParserFailureLeavesExactDefinition() = runBlocking<Unit> {
        val d = data("cas")
        val first = d.payloads.saveText(d.library.snapshot(), null, "one", "first.example")
        val second = d.payloads.saveText(first, first.id("one"), "one", "second.example")
        assertNotEquals(first.encoded, second.encoded)
        try { d.payloads.saveText(first, first.id("one"), "one", "stale.example"); fail() } catch (_: IllegalArgumentException) { }
        d.parser.failDump = true
        try { d.payloads.saveText(second, second.id("one"), "one", "broken.example"); fail() } catch (_: IllegalStateException) { }
        assertEquals(second.encoded, d.library.snapshot().encoded)
        assertEquals("second.example", d.payloads.preview(second, second.id("one")).text)
    }

    @Test fun stableIdRenameDoesNotChangeAnyProfileRevisionOrBody() = runBlocking<Unit> {
        val d = data("rename")
        val first = d.payloads.saveText(d.library.snapshot(), null, "one", "first.example")
        val body = "# retained exact body\r\nrules: ['MATCH,DIRECT']\r\n"
        // Binding must point at a real source provider for the actual Builder resolver.
        val header = BuilderResourceBindings(ruleProviders = mapOf("local" to first.id("one"))).header()
        val providerBody = "rule-providers:\r\n  local: {type: inline, behavior: domain, payload: [example.com]}\r\n" + body
        val a = d.profiles.importText("A", header + providerBody).id
        val b = d.profiles.importText("B", header + providerBody).id
        val c = d.profiles.importText("C", body).id
        val identity = d.profiles.profileSummary(a)!!.sourceIdentity
        val documents = listOf(a, b, c).associateWith { d.profiles.documentSnapshot(it) }
        val renamed = d.payloads.options(first, first.id("one"), "renamed", null)
        for (id in listOf(a, b)) {
            val source = d.profiles.readConfig(id)
            assertEquals(providerBody, source.substringAfter('\n'))
            assertEquals(first.id("one"), BuilderResourceBindings.parse(source).ruleProviders["local"])
            assertEquals(documents[id], d.profiles.documentSnapshot(id))
        }
        assertEquals(body, d.profiles.readConfig(c))
        assertEquals(identity, d.profiles.profileSummary(a)!!.sourceIdentity)
        assertEquals("first.example", d.payloads.preview(renamed, renamed.id("renamed")).text)
    }

    @Test fun remoteSyncUsesFreshVersionAndRejectsEmptyOrChangedDefinition() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("remote")
            server.enqueue(MockResponse().setBody("remote.example"))
            val first = d.payloads.importUrl(d.library.snapshot(), "remote", server.url("/one").toString())
            server.enqueue(MockResponse().setBody("new.example"))
            val second = d.payloads.sync(first, first.id("remote"))
            assertNotEquals(first.encoded, second.encoded)
            assertFalse(d.payloads.preview(second, second.id("remote")).editable)
            server.enqueue(MockResponse().setBody(""))
            try { d.payloads.sync(second, second.id("remote")); fail() } catch (_: java.io.IOException) { }
            assertEquals(second.encoded, d.library.snapshot().encoded)
            assertEquals("new.example", d.payloads.preview(second, second.id("remote")).text)
        }
    }

    @Test fun localOriginAndEmptyPayloadSurviveBothBackupScopesWithNewLocalIdentity() = runBlocking<Unit> {
        val source = data("backup-source")
        var saved = source.payloads.saveText(source.library.snapshot(), null, "local", "DOMAIN-SUFFIX,offline.example\n")
        saved = source.payloads.saveText(saved, null, "empty", "")
        val header = BuilderResourceBindings(ruleProviders = mapOf("one" to saved.id("local"), "zero" to saved.id("empty"))).header()
        val yaml = header + "rule-providers:\n  one: {type: inline, behavior: domain, payload: [example.com]}\n  zero: {type: inline, behavior: domain, payload: []}\nrules: ['RULE-SET,one,DIRECT', 'MATCH,DIRECT']\n"
        source.profiles.importText("Backed up", yaml)
        val archive = File(temporary.root, "backup.zip")
        BackupRepository(source.root, source.profiles, LiteSettingsStore(source.root)).createBackup(archive)
        for (scope in BackupRestoreScope.entries) {
            val target = data("restore-${scope.name}")
            val backups = BackupRepository(target.root, target.profiles, LiteSettingsStore(target.root))
            archive.inputStream().use { backups.restore(it, scope) }
            val actual = target.library.snapshot()
            assertNotEquals(saved.resources.ruleProviders.first().fields["path"], actual.resources.ruleProviders.first().fields["path"])
            assertEquals("DOMAIN-SUFFIX,offline.example\n", target.payloads.preview(actual, actual.id("local")).text)
            assertEquals("", target.payloads.preview(actual, actual.id("empty")).text)
            val id = target.profiles.list().single().id
            val prepared = target.profiles.prepare(id)
            assertTrue(prepared.yaml.contains("rule-providers"))
        }
    }

    @Test fun definitionFailureRollsBackPublishedCacheAndBothProfileHeaders() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("rollback")
            server.enqueue(MockResponse().setBody("original.example"))
            val before = d.payloads.importUrl(d.library.snapshot(), "one", server.url("/one").toString())
            val body = "rule-providers:\n  local: {type: inline, behavior: domain, payload: [example.com]}\nrules: ['MATCH,DIRECT']\n"
            val yaml = BuilderResourceBindings(ruleProviders = mapOf("local" to before.id("one"))).header() + body
            val ids = listOf(d.profiles.importText("A", yaml).id, d.profiles.importText("B", yaml).id)
            val documents = ids.associateWith { d.profiles.documentSnapshot(it) }
            val invalid = before.copy(resources = before.resources.copy(proxies = listOf(BuilderEntry("duplicate", emptyMap()), BuilderEntry("duplicate", emptyMap()))))
            server.enqueue(MockResponse().setBody("replacement.example"))
            try { d.payloads.options(invalid, invalid.id("one"), "renamed", server.url("/two").toString()); fail() }
            catch (failure: IllegalArgumentException) { assertTrue(rollbackVerified(failure)) }
            assertEquals(before.encoded, d.library.snapshot().encoded)
            ids.forEach { assertEquals(documents[it], d.profiles.documentSnapshot(it)) }
            assertEquals("original.example", d.payloads.preview(before, before.id("one")).text)
        }
    }

    @Test fun lateOldUrlDownloadCannotPublishAfterReplacement() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("late")
            server.enqueue(MockResponse().setBody("original.example"))
            val before = d.payloads.importUrl(d.library.snapshot(), "one", server.url("/old").toString())
            server.takeRequest()
            supervisorScope {
                server.enqueue(MockResponse().setBody("late.example").setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS))
                val old = async { runCatching { d.payloads.sync(before, before.id("one")) } }
                withContext(Dispatchers.IO) { server.takeRequest() }
                server.enqueue(MockResponse().setBody("current.example"))
                val current = d.payloads.options(before, before.id("one"), "one", server.url("/new").toString())
                assertTrue(old.await().exceptionOrNull() is IllegalArgumentException)
                assertEquals(current.encoded, d.library.snapshot().encoded)
                assertEquals("current.example", d.payloads.preview(current, current.id("one")).text)
                assertEquals("original.example", d.manager.libraryRule("one", server.url("/old").toString(), "domain", "text")?.let {
                    // Old identity remains its last committed origin, never the rejected late download.
                    d.manager.withGlobalRule("one", it.url, it.behavior, it.format, null) { _, file -> file.readText() }
                })
            }
        }
    }

    @Test fun cancelledDownloadJoinsBeforeReturningAndCleansOnlyItsStaging() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("cancel")
            server.enqueue(MockResponse().setBody("late.example").setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS))
            val job = launch { d.payloads.importUrl(d.library.snapshot(), "", server.url("/named.txt").toString()) }
            withContext(Dispatchers.IO) { server.takeRequest() }
            job.cancelAndJoin()
            assertTrue(d.library.snapshot().resources.ruleProviders.isEmpty())
            assertTrue(File(d.root, "runtime/global-rule-staging").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun binaryOriginIsRetainedAndPreviewUsesOfflineDumperWithoutRuntime() = runBlocking<Unit> {
        val d = data("binary")
        val bytes = byteArrayOf(0xff.toByte(), 0, 1, 2, 3)
        val saved = d.payloads.importFile(d.library.snapshot(), "binary", bytes.inputStream())
        assertEquals("mrs", d.payloads.info(saved, saved.id("binary")).format)
        val preview = d.payloads.preview(saved, saved.id("binary"))
        assertFalse(preview.editable)
        assertEquals("binary.example\n", preview.text)
        val fields = saved.resources.ruleProviders.single().fields
        d.manager.withGlobalRule("binary", "", "domain", "mrs", globalRuleLocalId(fields["path"] as String)) { _, file ->
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun legacyIdentityMigrationIsAtomicAndPreservesBodiesPrivateKeysAndStableRevisions() = runBlocking<Unit> {
        val d = data("identity")
        val saved = d.payloads.saveText(d.library.snapshot(), null, "one", "original.example")
        val legacy = ConfigYaml.dump(mapOf("schema" to 1, "proxies" to emptyList<Any>(),
            "rule-providers" to mapOf("one" to saved.resources.ruleProviders.single().fields), "filters" to emptyList<Any>(), "icon-sets" to emptyList<Any>()))
        File(d.root, "settings/builder-resources.yaml").writeText(legacy)
        val body = "# preserve exact CRLF\r\nrule-providers:\r\n  local: {type: inline, behavior: domain, payload: [example.com]}\r\nrules: ['MATCH,DIRECT']\r\n"
        val yaml = "\uFEFF" + BuilderResourceBindings(ruleProviders = mapOf("local" to "one"), schema = 1).header() + body
        val ids = listOf(d.profiles.importText("A", yaml).id, d.profiles.importText("B", yaml).id)
        val keyBytes = "synthetic-private-storage-fixture".toByteArray()
        dev.aerove.lite.profilesource.AgeSecret(keyBytes).use { key -> ids.forEach { d.profiles.setAgeSecret(it, key) } }
        val before = ids.associateWith { d.profiles.documentSnapshot(it) }
        val stable = d.profiles.migrateGlobalResourceIdentity(d.library)
        assertEquals(2, stable.schemaVersion)
        ids.forEach { id ->
            val after = d.profiles.documentSnapshot(id)
            assertEquals(body, after.yaml.substringAfter('\n'))
            assertTrue(after.yaml.startsWith('\uFEFF'))
            assertEquals(before[id]!!.profile, after.profile)
            assertNotEquals(before[id]!!.revision, after.revision)
            assertEquals(stable.id("one"), BuilderResourceBindings.parse(after.yaml).ruleProviders["local"])
            val actualKey = d.profiles.readAgeSecret(id)!!.use { it.copyBytes() }
            try { assertArrayEquals(keyBytes, actualKey) } finally { actualKey.fill(0) }
        }
        val documents = ids.associateWith { d.profiles.documentSnapshot(it) }
        assertEquals(stable, d.profiles.migrateGlobalResourceIdentity(d.library))
        ids.forEach { assertEquals(documents[it], d.profiles.documentSnapshot(it)) }
        keyBytes.fill(0)
    }

    @Test fun deletedLocalDefinitionKeepsStaticSourceOriginalThroughPruneAndBothBackupScopes() = runBlocking<Unit> {
        val d = data("deleted-origin")
        val saved = d.payloads.saveText(d.library.snapshot(), null, "one", "DOMAIN-SUFFIX,retained.example\n")
        val id = saved.id("one")
        val body = ConfigYaml.dump(mapOf("rule-providers" to mapOf("one" to saved.resources.ruleProviders.single().fields),
            "rules" to listOf("RULE-SET,one,DIRECT", "MATCH,DIRECT")))
        val yaml = BuilderResourceBindings(ruleProviders = mapOf("one" to id)).header() + body
        val profile = d.profiles.importText("Uses original", yaml)
        val running = d.profiles.prepare(profile.id)
        val lease = d.profiles.retain(running)
        try {
            val deleted = d.payloads.delete(saved, id)
            assertTrue(deleted.resources.ruleProviders.isEmpty())
            assertEquals(body, d.profiles.readConfig(profile.id))
            d.profiles.pruneCache()
            assertTrue(running.resourceDirectory!!.isDirectory)
            assertNotNull(d.profiles.prepare(profile.id).generation)
            val archive = File(temporary.root, "deleted-origin.zip")
            BackupRepository(d.root, d.profiles, LiteSettingsStore(d.root)).createBackup(archive)
            for (scope in BackupRestoreScope.entries) {
                val target = data("deleted-restore-${scope.name}")
                archive.inputStream().use { BackupRepository(target.root, target.profiles, LiteSettingsStore(target.root)).restore(it, scope) }
                assertTrue(target.library.snapshot().resources.ruleProviders.isEmpty())
                val restored = target.profiles.prepare(target.profiles.list().single().id)
                val fields = (ConfigYaml.parse(restored.yaml)["rule-providers"] as Map<*, *>)["one"] as Map<*, *>
                assertEquals("retained.example\n", File(fields["path"] as String).readText())
            }
            d.profiles.delete(profile.id)
            d.profiles.pruneCache()
            assertTrue(running.resourceDirectory!!.isDirectory)
        } finally { d.profiles.release(lease) }
        d.profiles.pruneCache()
        val fields = saved.resources.ruleProviders.single().fields
        assertTrue(runCatching { d.manager.withGlobalRule("one", "", "domain", "text", globalRuleLocalId(fields["path"] as String)) { _, _ -> } }.isFailure)
    }

    @Test fun failedPostMigrationMutationRestoresLegacyLibraryAndBothExactDocuments() = runBlocking<Unit> {
        val d = data("migration-rollback")
        val saved = d.payloads.saveText(d.library.snapshot(), null, "one", "original.example")
        val legacy = ConfigYaml.dump(mapOf("schema" to 1, "proxies" to emptyList<Any>(),
            "rule-providers" to mapOf("one" to saved.resources.ruleProviders.single().fields), "filters" to emptyList<Any>(), "icon-sets" to emptyList<Any>()))
        File(d.root, "settings/builder-resources.yaml").writeText(legacy)
        val yaml = BuilderResourceBindings(ruleProviders = mapOf("local" to "one"), schema = 1).header() +
            "rule-providers:\n  local: {type: inline, behavior: domain, payload: [example.com]}\nrules: ['MATCH,DIRECT']\n"
        val ids = listOf(d.profiles.importText("A", yaml).id, d.profiles.importText("B", yaml).id)
        val documents = ids.associateWith { d.profiles.documentSnapshot(it) }
        val expected = d.library.snapshot()
        val invalid = expected.resources.copy(proxies = listOf(BuilderEntry("duplicate", emptyMap()), BuilderEntry("duplicate", emptyMap())))
        try { d.profiles.commitGlobalResourceMutation(d.library, expected, invalid); fail() }
        catch (failure: IllegalArgumentException) { assertTrue(rollbackVerified(failure)) }
        assertEquals(legacy, d.library.snapshot().encoded)
        ids.forEach { assertEquals(documents[it], d.profiles.documentSnapshot(it)) }
    }

    @Test fun globalDeletionCannotOmitItsReferenceDetachmentPlan() = runBlocking<Unit> {
        val d = data("missing-plan")
        val saved = d.payloads.saveText(d.library.snapshot(), null, "one", "original.example")
        val yaml = BuilderResourceBindings(ruleProviders = mapOf("local" to saved.id("one"))).header() +
            "rule-providers:\n  local: {type: inline, behavior: domain, payload: [example.com]}\nrules: ['MATCH,DIRECT']\n"
        val profile = d.profiles.importText("Linked", yaml)
        val before = d.profiles.documentSnapshot(profile.id)
        try { d.profiles.commitGlobalResourceMutation(d.library, saved, saved.resources.copy(ruleProviders = emptyList())); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(saved.encoded, d.library.snapshot().encoded)
        assertEquals(before, d.profiles.documentSnapshot(profile.id))
    }

    @Test fun coldStartMigratesLegacyHeadersBeforeCapturingRevisionAndRechecksNewSources() = runBlocking<Unit> {
        val d = data("cold-identity")
        val saved = d.payloads.saveText(d.library.snapshot(), null, "one", "original.example")
        val source = BuilderResourceBindings(ruleProviders = mapOf("local" to "one"), schema = 1).header() +
            "rule-providers:\n  local: {type: inline, behavior: domain, payload: [example.com]}\nrules: ['MATCH,DIRECT']\n"
        val first = d.profiles.importText("First legacy source", source)
        val before = d.profiles.documentSnapshot(first.id)
        val prepared = d.profiles.prepareForStart(first.id)
        val migrated = d.profiles.documentSnapshot(first.id)
        assertNotEquals(before.revision, migrated.revision)
        assertEquals(migrated.revision, prepared.revision)
        assertEquals(migrated.yaml, prepared.source)
        assertEquals(saved.id("one"), BuilderResourceBindings.parse(migrated.yaml).ruleProviders["local"])
        d.profiles.prepareForStart(first.id)
        assertEquals(migrated, d.profiles.documentSnapshot(first.id))
        val second = d.profiles.importText("Second legacy source", source)
        val secondBefore = d.profiles.documentSnapshot(second.id)
        d.profiles.prepareForStart(second.id)
        assertNotEquals(secondBefore.revision, d.profiles.documentSnapshot(second.id).revision)
        assertEquals(migrated, d.profiles.documentSnapshot(first.id))
    }

    @Test fun resourceLibrarySyncDispatchesThroughTheSameCanonicalPayloadFacade() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("library-dispatch")
            server.enqueue(MockResponse().setBody("DOMAIN-SUFFIX,one.example"))
            val first = d.payloads.importUrl(d.library.snapshot(), "", server.url("/remote.txt").toString())
            val resourceId = first.id("remote")
            val shared = dev.aerove.lite.resources.ResourceManagementLibraryRepository(d.library, d.manager, d.payloads)
            val row = shared.snapshot().entries.single()
            assertEquals("rule:$resourceId", row.id)
            server.enqueue(MockResponse().setBody("DOMAIN-SUFFIX,two.example"))
            val refreshed = shared.sync(row.id)
            assertNotEquals(first.encoded, refreshed.builder.encoded)
            assertEquals(resourceId, refreshed.builder.id("remote"))
            assertEquals(1, refreshed.entries.single().count)
            assertEquals("DOMAIN-SUFFIX,two.example", d.payloads.preview(refreshed.builder, resourceId).text)
        }
    }

    @Test fun differentRuleIdsDownloadConcurrentlyAndMergeWithOtherCommittedResources() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("parallel-rules")
            server.enqueue(MockResponse().setBody("one.example"))
            val first = d.payloads.importUrl(d.library.snapshot(), "one", server.url("/one").toString())
            server.enqueue(MockResponse().setBody("two.example"))
            val before = d.payloads.importUrl(first, "two", server.url("/two").toString())
            server.takeRequest(); server.takeRequest()
            val started = CountDownLatch(2); val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(if (request.path == "/one") "new-one.example" else "new-two.example")
                }
            }
            coroutineScope {
                val one = async { d.payloads.sync(before, before.id("one")) }
                val two = async { d.payloads.sync(before, before.id("two")) }
                val withOther = try {
                    withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                    d.library.replace(before, before.resources.copy(iconSets = listOf(BuilderIconSet("Other", icons = listOf(BuilderIcon("icon", "https://example.com/icon.png"))))))
                } finally { release.countDown() }
                awaitAll(one, two)
                val actual = d.library.snapshot()
                assertEquals(withOther.resources.iconSets, actual.resources.iconSets)
                assertEquals(before.id("one"), actual.id("one"))
                assertEquals(before.id("two"), actual.id("two"))
                assertEquals("new-one.example", d.payloads.preview(actual, actual.id("one")).text)
                assertEquals("new-two.example", d.payloads.preview(actual, actual.id("two")).text)
            }
        }
    }

    @Test fun sameRuleUrlAbaAndMaintenanceEpochRejectOldRefreshWithoutChangingCache() = runBlocking<Unit> {
        for (maintenance in listOf(false, true)) MockWebServer().use { server ->
            server.start()
            val d = data("rule-epoch-$maintenance")
            server.enqueue(MockResponse().setBody("original.example"))
            val before = d.payloads.importUrl(d.library.snapshot(), "one", server.url("/original").toString())
            server.takeRequest()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("rejected.example")
                }
            }
            val pending = async { runCatching { d.payloads.sync(before, before.id("one")) } }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                if (maintenance) d.library.invalidateRemoteRefreshes() else {
                    val old = before.resources.ruleProviders.single()
                    val changed = d.library.replace(before, before.resources.copy(ruleProviders = listOf(old.copy(fields = old.fields + ("url" to server.url("/temporary").toString())))))
                    d.library.replace(changed, before.resources)
                }
            } finally { release.countDown() }
            assertTrue(pending.await().exceptionOrNull() is IllegalArgumentException)
            assertEquals(before.encoded, d.library.snapshot().encoded)
            assertEquals("original.example", d.payloads.preview(before, before.id("one")).text)
            assertTrue(File(d.root, "runtime/global-rule-staging").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun actualBackupRestoreInvalidatesAnIdenticalPendingRuleRefresh() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val d = data("rule-restore-epoch")
            server.enqueue(MockResponse().setBody("original.example"))
            val before = d.payloads.importUrl(d.library.snapshot(), "one", server.url("/original").toString())
            server.takeRequest()
            d.profiles.importText("Profile", "rules: [MATCH,DIRECT]")
            val backups = BackupRepository(d.root, d.profiles, LiteSettingsStore(d.root))
            val archive = File(temporary.root, "parallel-restore.zip")
            backups.createBackup(archive)
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody("late.example")
                }
            }
            val pending = async { runCatching { d.payloads.sync(before, before.id("one")) } }
            try {
                withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
                archive.inputStream().use { backups.restore(it) }
            } finally { release.countDown() }
            assertTrue(pending.await().isFailure)
            val restored = d.library.snapshot()
            assertEquals(before.encoded, restored.encoded)
            assertEquals("original.example", d.payloads.preview(restored, restored.id("one")).text)
            assertTrue(File(d.root, "runtime/global-rule-staging").listFiles().orEmpty().isEmpty())
        }
    }    @Test fun inspectionReceiptRequiresExactlyTheThreeTypedKeys() {
        assertEquals(RulePayloadInspection("mrs", "domain", 2),
            RulePayloadInspection.decode("""{"count":2,"behavior":"domain","format":"mrs"}"""))
        for (invalid in listOf(
            """{"format":"text","behavior":"classical"}""",
            """{"format":"text","behavior":"classical","count":0,"extra":null}""",
            """{"format":"text","behavior":"classical","count":"0"}""",
            """{"format":"text","behavior":"classical","count":true}""",
            """{"format":"text","behavior":"classical","count":1.5}""",
            """{"format":"text","behavior":"classical","count":2147483648}""",
            """{"format":"text","behavior":"classical","count":-1}""",
        )) assertThrows(IllegalArgumentException::class.java) { RulePayloadInspection.decode(invalid) }
    }}
