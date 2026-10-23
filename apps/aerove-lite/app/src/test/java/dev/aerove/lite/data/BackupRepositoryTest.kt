package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ProxyMode
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val simple = "rules: ['MATCH,DIRECT']"

    private data class Data(val root: File, val profiles: ProfileRepository, val settings: LiteSettingsStore, val backups: BackupRepository, val resources: ResourceBundleManager)

    private fun data(name: String, native: NativeResourceValidator = NativeResourceValidator { _, _, _, _ -> null },
        limits: BackupLimits = BackupLimits()): Data {
        val root = temporary.newFolder(name)
        val runtime = File(root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val resources = ResourceBundleManager(runtime, validator, native)
        val profiles = ProfileRepository(root, validator, resources = resources)
        val settings = LiteSettingsStore(root)
        return Data(root, profiles, settings, BackupRepository(root, profiles, settings, limits) { 1234567 }, resources)
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { output -> entries.forEach { (name, content) ->
            output.putNextEntry(ZipEntry(name)); output.write(content); output.closeEntry()
        } }
    }.toByteArray()

    private fun unpack(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
            while (true) { val entry = input.nextEntry ?: break; entries[entry.name] = input.readBytes() }
        }
        return entries
    }

    private suspend fun archive(data: Data): ByteArray {
        val file = temporary.newFile()
        data.backups.createBackup(file)
        return file.readBytes()
    }

    private suspend fun restore(data: Data, bytes: ByteArray, scope: BackupRestoreScope = BackupRestoreScope.All,
        strategy: BackupRestoreStrategy = BackupRestoreStrategy.Compatible, authorized: Boolean = false) =
        data.backups.restore(ByteArrayInputStream(bytes), scope, strategy, authorized)

    @Test fun profileProxyGroupUiPersistsWithoutChangingSourceIdentityAndFollowsBothBackupRemaps() = runBlocking<Unit> {
        val source = data("proxy-ui-source")
        val profile = source.profiles.importText("With groups", "proxy-groups: [{name: Primary, type: select, proxies: [DIRECT]}]\nrules: ['MATCH,Primary']")
        val before = source.profiles.documentSnapshot(profile.id)
        val prepared = source.profiles.prepare(profile.id)
        val manifest = File(prepared.resourceDirectory, "manifest.json").readBytes()
        val lineage = source.profiles.sourceLineage(profile.id)
        val input = linkedSetOf("Primary", "Second")
        val saved = source.profiles.updateProxyGroupUi(profile.id, "Primary", input)
        input.clear()
        assertEquals(setOf("Primary", "Second"), saved.unfoldedProxyGroups)
        val fresh = ProfileRepository(source.root, CompatibilityValidator(NativeConfigValidator { null }, File(source.root, "runtime")), resources = source.resources)
        val after = fresh.documentSnapshot(profile.id)
        assertEquals(before.revision, after.revision)
        assertEquals(before.yaml, after.yaml)
        assertEquals(before.profile.copy(currentProxyGroup = "Primary", unfoldedProxyGroups = setOf("Primary", "Second")), after.profile)
        assertEquals(lineage.logicalSourceSha256, fresh.sourceLineage(profile.id).logicalSourceSha256)
        val afterPrepared = fresh.prepare(profile.id)
        assertEquals(prepared.generation, afterPrepared.generation)
        assertEquals(prepared.yaml, afterPrepared.yaml)
        assertArrayEquals(manifest, File(afterPrepared.resourceDirectory, "manifest.json").readBytes())
        val bytes = archive(source)
        for (scope in BackupRestoreScope.entries) {
            val target = data("proxy-ui-target-$scope")
            val existing = target.profiles.importText("Existing", simple)
            target.profiles.updateProxyGroupUi(existing.id, "Keep", setOf("Keep"))
            val restored = restore(target, bytes, scope)
            val mapped = restored.profileIds.getValue(profile.id)
            assertNotEquals(profile.id, mapped)
            val state = target.profiles.profileSummary(mapped)!!
            assertEquals("Primary", state.currentProxyGroup)
            assertEquals(setOf("Primary", "Second"), state.unfoldedProxyGroups)
            assertEquals("Keep", target.profiles.profileSummary(existing.id)!!.currentProxyGroup)
            assertEquals(before.yaml, target.profiles.documentSnapshot(mapped).yaml)
        }
    }

    @Test fun missingProxyGroupUiDefaultsToCollapsedAndMalformedPresentStateDoesNotRestore() = runBlocking<Unit> {
        val source = data("proxy-ui-legacy")
        val profile = source.profiles.importText("Old", simple)
        source.profiles.updateProxyGroupUi(profile.id, "Primary", setOf("Primary"))
        val indexFile = File(source.root, "profiles/index.json")
        val index = JSONObject(indexFile.readText())
        index.getJSONArray("profiles").getJSONObject(0).apply { remove("currentProxyGroup"); remove("unfoldedProxyGroups") }
        indexFile.writeText(index.toString())
        val old = source.profiles.profileSummary(profile.id)!!
        assertNull(old.currentProxyGroup)
        assertTrue(old.unfoldedProxyGroups.isEmpty())
        val files = unpack(archive(source))
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONArray("profiles").getJSONObject(0).apply { remove("currentProxyGroup"); remove("unfoldedProxyGroups") }
        files["manifest.json"] = manifest.toString().toByteArray()
        val target = data("proxy-ui-legacy-target")
        val restored = restore(target, zip(files), BackupRestoreScope.ProfilesOnly)
        val copied = target.profiles.profileSummary(restored.profileIds.getValue(profile.id))!!
        assertNull(copied.currentProxyGroup)
        assertTrue(copied.unfoldedProxyGroups.isEmpty())
        val before = File(target.root, "profiles/index.json").readBytes()
        manifest.getJSONArray("profiles").getJSONObject(0).put("unfoldedProxyGroups", JSONObject.NULL)
        files["manifest.json"] = manifest.toString().toByteArray()
        try { restore(target, zip(files)); fail("Present wrong type must not become empty state") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, File(target.root, "profiles/index.json").readBytes())
        val malformed = JSONObject(indexFile.readText())
        malformed.getJSONArray("profiles").getJSONObject(0).put("currentProxyGroup", false)
        indexFile.writeText(malformed.toString())
        try { source.profiles.profileSummary(profile.id); fail("Present group must be a string or null") } catch (_: IllegalArgumentException) { }
    }

    @Test fun customProfileIdentitySurvivesFreshRepositoryAndBothRestoreScopes() = runBlocking<Unit> {
        val source = data("custom-source")
        val custom = source.profiles.importText("Builder", simple, null, custom = true)
        source.profiles.importText("Local normal", simple)
        val fresh = ProfileRepository(source.root, CompatibilityValidator(NativeConfigValidator { null }, File(source.root, "fresh-runtime")))
        assertTrue(fresh.list().first { it.id == custom.id }.custom)
        assertFalse(fresh.list().first { it.id != custom.id }.custom)
        val bytes = archive(source)
        for (scope in BackupRestoreScope.entries) {
            val target = data("custom-target-$scope")
            restore(target, bytes, scope)
            assertTrue(target.profiles.list().first { it.name == "Builder" }.custom)
            assertFalse(target.profiles.list().first { it.name == "Local normal" }.custom)
        }
        val index = File(source.root, "profiles/index.json")
        val legacy = JSONObject(index.readText())
        val entries = legacy.getJSONArray("profiles")
        for (i in 0 until entries.length()) entries.getJSONObject(i).remove("custom")
        index.writeText(legacy.toString())
        assertTrue(fresh.list().none { it.custom })
    }

    @Test fun globalLibrariesAreExplicitHashCoveredPayloadAndProfileLinksUseArchiveIdentity() = runBlocking<Unit> {
        val original = data("library-original")
        original.profiles.importText("Plan", simple)
        val imported = data("library-reimported")
        restore(imported, archive(original))
        val profile = imported.profiles.list().single()
        val scripts = OverrideSnapshot(listOf(ScriptResource("script", "User script", content = "function main(x) { return x; }")),
            listOf(ProfileOverride(profile.id, OverrideMode.Script, "script")), listOf(OverrideRule("rule", "DOMAIN,example.com,DIRECT")))
        File(imported.root, "settings/overrides.json").writeText(OverrideRepository.encode(scripts))
        val library = BuilderResources(filters = listOf(BuilderFilter("User filter", "JP")))
        File(imported.root, "settings/builder-resources.yaml").writeText(library.yaml())
        val entries = unpack(archive(imported))
        val manifest = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        val archiveId = manifest.getJSONArray("profiles").getJSONObject(0).getString("id")
        assertNotEquals(profile.id, archiveId)
        val saved = OverrideRepository.decode(entries.getValue("settings/overrides.json").toString(Charsets.UTF_8))
        assertEquals(listOf(archiveId), saved.profiles.map { it.profileId })
        assertEquals(scripts.scripts, saved.scripts)
        assertEquals(scripts.globalRules, saved.globalRules)
        assertEquals(library, BuilderResources.parse(entries.getValue("settings/builder-resources.yaml").toString(Charsets.UTF_8)))
        val records = manifest.getJSONArray("files")
        listOf("settings/overrides.json", "settings/builder-resources.yaml").forEach { path ->
            val row = (0 until records.length()).map(records::getJSONObject).single { it.getString("path") == path }
            assertEquals(entries.getValue(path).size.toLong(), row.getLong("bytes"))
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(entries.getValue(path)).joinToString("") { "%02x".format(it) }
            assertEquals(digest, row.getString("sha256"))
        }
    }

    @Test fun corruptLibraryCannotProduceASeeminglyCompleteBackup() = runBlocking<Unit> {
        val source = data("library-corrupt")
        source.profiles.importText("Plan", simple)
        File(source.root, "settings/builder-resources.yaml").writeText("schema: 99")
        val destination = temporary.newFile().apply { writeText("previous archive") }
        assertTrue(runCatching { source.backups.createBackup(destination) }.isFailure)
        assertEquals("previous archive", destination.readText())
    }

    @Test fun profilesOnlyMergeKeepsExistingGlobalsAndRewritesImportedCollidingBindings() = runBlocking<Unit> {
        val source = data("profile-libraries-source")
        val incomingResources = BuilderResources(filters = listOf(BuilderFilter("shared", "JP"), BuilderFilter("unused", "SG")))
        val binding = BuilderResourceBindings(filters = mapOf("Group" to BuilderFilterBinding(listOf(incomingResources.filters.first().id), emptyList())))
        val profile = source.profiles.importText("Imported", binding.header() + "proxy-groups: [{name: Group, type: select, proxies: [DIRECT]}]\n" + simple)
        val incomingOverrides = OverrideSnapshot(listOf(ScriptResource("script", "Imported script", content = "throw new Error('must not execute on restore')")),
            listOf(ProfileOverride(profile.id, OverrideMode.Script, scriptId = "script")), listOf(OverrideRule("rule", "DOMAIN,incoming.example,DIRECT")))
        File(source.root, "settings/overrides.json").writeText(OverrideRepository.encode(incomingOverrides))
        File(source.root, "settings/builder-resources.yaml").writeText(incomingResources.yaml())
        val target = data("profile-libraries-target")
        val existing = target.profiles.importText("Existing", simple)
        val current = OverrideSnapshot(listOf(ScriptResource("script", "Existing script", content = "keep")),
            listOf(ProfileOverride(existing.id)), listOf(OverrideRule("rule", "DOMAIN,existing.example,DIRECT")))
        File(target.root, "settings/overrides.json").writeText(OverrideRepository.encode(current))
        File(target.root, "settings/builder-resources.yaml").writeText(BuilderResources(filters = listOf(BuilderFilter("shared", "US"))).yaml())
        val result = restore(target, archive(source), BackupRestoreScope.ProfilesOnly)
        val importedId = result.profileIds.getValue(profile.id)
        val actual = OverrideRepository(target.root).snapshot()
        assertEquals(current.globalRules, actual.globalRules)
        assertEquals("keep", actual.scripts.single { it.id == "script" }.content)
        val restored = actual.profiles.single { it.profileId == importedId }
        assertTrue("DOMAIN,incoming.example,DIRECT" in restored.addedRules)
        assertNotEquals("script", restored.scriptId)
        val sourceAgain = target.profiles.readConfig(importedId)
        val resources = BuilderResources.parse(File(target.root, "settings/builder-resources.yaml").readText())
        assertEquals(listOf(resources.filters.single { it.label == "shared 2" }.id), BuilderResourceBindings.parse(sourceAgain).filters.getValue("Group").labels)
        assertEquals(listOf("shared", "shared 2"), resources.filters.map { it.label })
        assertEquals(existing.id, target.profiles.snapshot().selectedId)
    }

    @Test fun failedRestoredProfileValidationRollsBackLibrariesSettingsAndDeferredPublication() = runBlocking<Unit> {
        val source = data("transaction-library-source")
        val profile = source.profiles.importText("Imported", "proxies: [{name: Test, type: socks5, server: example.com, port: 1080}]\n" + simple)
        source.settings.save(LiteSettings(openLogs = true))
        File(source.root, "settings/overrides.json").writeText(OverrideRepository.encode(OverrideSnapshot(
            scripts = listOf(ScriptResource("new", "New", content = "must not run")), profiles = listOf(ProfileOverride(profile.id)))))
        File(source.root, "settings/builder-resources.yaml").writeText(BuilderResources(filters = listOf(BuilderFilter("new", "JP"))).yaml())
        val bytes = archive(source)
        val target = data("transaction-library-target", NativeResourceValidator { kind, _, _, _ -> if (kind == "proxy-provider") "reject after metadata writes" else null })
        val original = target.profiles.importText("Keep", simple)
        val old = target.settings.read()
        val oldOverrides = OverrideRepository.encode(OverrideSnapshot(scripts = listOf(ScriptResource("old", "Old", content = "keep"))))
        File(target.root, "settings/overrides.json").writeText(oldOverrides)
        val oldBuilder = BuilderResources(filters = listOf(BuilderFilter("old", "US"))).yaml()
        File(target.root, "settings/builder-resources.yaml").writeText(oldBuilder)
        val published = mutableListOf<LiteSettings?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { target.settings.changes.collect { published += it } }
        try {
            assertTrue(runCatching { target.settings.deferPublication { restore(target, bytes, strategy = BackupRestoreStrategy.Override) } }.isFailure)
        } finally { collector.cancelAndJoin() }
        assertEquals(listOf(original), target.profiles.list())
        assertEquals(oldOverrides, File(target.root, "settings/overrides.json").readText())
        assertEquals(oldBuilder, File(target.root, "settings/builder-resources.yaml").readText())
        assertEquals(old, target.settings.read())
        assertTrue(published.all { it == old })
    }

    @Test fun oldArchiveOverrideDoesNotEraseNewGlobalLibrariesItNeverOwned() = runBlocking<Unit> {
        val source = data("old-library-source")
        source.profiles.importText("Imported", simple)
        val bytes = archive(source)
        val target = data("old-library-target")
        target.settings.read()
        val overrides = OverrideRepository.encode(OverrideSnapshot(scripts = listOf(ScriptResource("keep", "Keep", content = "keep"))))
        val builders = BuilderResources(filters = listOf(BuilderFilter("keep", "US"))).yaml()
        File(target.root, "settings/overrides.json").writeText(overrides)
        File(target.root, "settings/builder-resources.yaml").writeText(builders)
        restore(target, bytes, strategy = BackupRestoreStrategy.Override)
        assertEquals(overrides, File(target.root, "settings/overrides.json").readText())
        assertEquals(builders, File(target.root, "settings/builder-resources.yaml").readText())
    }

    @Test fun validatedGlobalRulePayloadRestoresOfflineAndFeedsAnAliasProvider() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("payload: ['+.example.com']"))
        server.start()
        val url = server.url("/global.yaml").toString()
        val source = data("global-rule-source")
        source.profiles.importText("Plan", simple)
        val original = source.resources.syncLibraryRule("Cloud", url, "domain", "yaml")
        File(source.root, "settings/builder-resources.yaml").writeText(BuilderResources(ruleProviders = listOf(
            BuilderEntry("Cloud", mapOf("type" to "http", "url" to url, "path" to "rules/cloud.yaml", "behavior" to "domain", "format" to "yaml")))).yaml())
        server.shutdown()
        val bytes = archive(source)
        val entries = unpack(bytes)
        assertTrue(entries.keys.any { it.startsWith("library/resources/") })
        val target = data("global-rule-target")
        restore(target, bytes)
        assertEquals(original, target.resources.libraryRule("Cloud", url, "domain", "yaml"))
        val profile = target.profiles.importText("Alias", "rule-providers: {Alias: {type: http, url: '$url', path: rules/alias.yaml, behavior: domain, format: yaml}}\nrules: ['RULE-SET,Alias,DIRECT']")
        val prepared = target.profiles.prepareForStart(profile.id)
        assertTrue(prepared.yaml.contains("rule-providers"))
        assertFalse(prepared.yaml.contains(url))
    }

    @Test fun failedRestoreRollsBackSharedCacheIndexAndKeepsThePreviousImmutablePayload() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("payload: ['+.new.example']"))
        server.enqueue(MockResponse().setBody("payload: ['+.old.example']"))
        server.start()
        val url = server.url("/global.yaml").toString()
        val source = data("global-cache-rollback-source")
        source.profiles.importText("Imported", "proxies: [{name: Test, type: socks5, server: example.com, port: 1080}]\n" + simple)
        source.resources.syncLibraryRule("Cloud", url, "domain", "yaml")
        val definitions = BuilderResources(ruleProviders = listOf(BuilderEntry("Cloud", mapOf("type" to "http", "url" to url,
            "path" to "rules/cloud.yaml", "behavior" to "domain", "format" to "yaml")))).yaml()
        File(source.root, "settings/builder-resources.yaml").writeText(definitions)
        val target = data("global-cache-rollback-target", NativeResourceValidator { kind, _, _, _ -> if (kind == "proxy-provider") "reject after cache import" else null })
        val existing = target.profiles.importText("Keep", simple)
        val old = target.resources.syncLibraryRule("Cloud", url, "domain", "yaml")
        File(target.root, "settings/builder-resources.yaml").writeText(definitions)
        server.shutdown()
        assertTrue(runCatching { restore(target, archive(source), strategy = BackupRestoreStrategy.Override) }.isFailure)
        assertEquals(old, target.resources.libraryRule("Cloud", url, "domain", "yaml"))
        assertEquals(listOf(existing), target.profiles.list())
    }

    @Test fun profilesOnlyRestoresReferencedPayloadPrivatelyWithoutChangingExistingSharedCache() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("payload: ['+.incoming.example']"))
        server.enqueue(MockResponse().setBody("payload: ['+.existing.example']"))
        server.start()
        val url = server.url("/shared.yaml").toString()
        val fields = mapOf<String, Any?>("type" to "http", "url" to url, "path" to "rules/cloud.yaml", "behavior" to "domain", "format" to "yaml")
        val sharedResources = BuilderResources(ruleProviders = listOf(BuilderEntry("Cloud", fields)))
        val definitions = sharedResources.yaml()
        val source = data("private-payload-source")
        val incomingPayload = source.resources.syncLibraryRule("Cloud", url, "domain", "yaml")
        val binding = BuilderResourceBindings(ruleProviders = mapOf("Alias" to requireNotNull(sharedResources.ruleProviders.single().id)))
        val incoming = source.profiles.importText("Imported", binding.header() + "rule-providers: {Alias: {type: http, url: '$url', path: rules/alias.yaml, behavior: domain, format: yaml}}\nrules: ['RULE-SET,Alias,DIRECT']")
        File(source.root, "settings/builder-resources.yaml").writeText(definitions)
        val target = data("private-payload-target")
        val old = target.resources.syncLibraryRule("Cloud", url, "domain", "yaml")
        val existing = target.profiles.importText("Existing", "rule-providers: {Existing: {type: http, url: '$url', path: rules/existing.yaml, behavior: domain, format: yaml}}\nrules: ['RULE-SET,Existing,DIRECT']")
        File(target.root, "settings/builder-resources.yaml").writeText(definitions)
        server.shutdown()
        val result = restore(target, archive(source), BackupRestoreScope.ProfilesOnly)
        assertEquals(old, target.resources.libraryRule("Cloud", url, "domain", "yaml"))
        val restored = target.profiles.prepareForStart(result.profileIds.getValue(incoming.id))
        val rows = JSONObject(File(restored.resourceDirectory, "manifest.json").readText()).getJSONArray("records")
        val importedRecord = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("key") == "$.rule-providers.Alias" }
        assertEquals(incomingPayload.sha256, importedRecord.getString("sha256"))
        assertTrue(importedRecord.getBoolean("localOverride"))
        val oldPrepared = target.profiles.prepareForStart(existing.id)
        val oldRows = JSONObject(File(oldPrepared.resourceDirectory, "manifest.json").readText()).getJSONArray("records")
        assertEquals(old.sha256, oldRows.getJSONObject(0).getString("sha256"))
    }

    @Test fun maintenanceMetadataSurvivesBackupRestore() = runBlocking<Unit> {
        val source = data("maintenance-source")
        val profile = source.profiles.importText("Plan", simple, "https://example.com/sub")
        source.profiles.editMetadata(profile.id, "Renamed", profile.url, false, 120_000L)
        val index = File(source.root, "profiles/index.json")
        val json = JSONObject(index.readText())
        json.getJSONArray("profiles").getJSONObject(0).put("lastSuccessfulUpdateAt", 12345L)
            .put("subscriptionUserInfo", "download=5; total=100").put("contentDisposition", "attachment; filename=plan.yaml")
        index.writeText(json.toString())
        val expected = source.profiles.list().single()
        val target = data("maintenance-target")
        restore(target, archive(source))
        val actual = target.profiles.list().single()
        assertEquals(expected.copy(id = actual.id), actual)
    }

    @Test fun realArchiveIncludesSettingsSourcesLocalResourcesAndTheirActualManifest() = runBlocking<Unit> {
        val source = data("source")
        val yaml = "rule-providers: {local: {type: file, path: rules/local.yaml, behavior: domain}}\nrules: ['RULE-SET,local,DIRECT']"
        val imported = source.profiles.importFile("With local resources", ByteArrayInputStream(zip(mapOf(
            "config.yaml" to yaml.toByteArray(), "rules/local.yaml" to "payload: ['+.example.com']".toByteArray(),
        ))))
        val wanted = source.settings.save(LiteSettings(material = MaterialTier.Frosted, mode = ProxyMode.Direct,
            auroraColors = listOf(0xff010203L, 0xff040506L), auroraSeed = 987, auroraRotationDay = 8, openLogs = true))
        source.backups.saveWebDav(WebDavBackupConfig("https://example.com/dav", "user", "secret"))
        val file = temporary.newFile()
        val manifest = source.backups.createBackup(file)
        assertEquals(1, manifest.profiles)
        val entries = unpack(file.readBytes())
        assertTrue(entries.containsKey("settings.json"))
        assertEquals(yaml, entries.getValue("profiles/${imported.id}/config.yaml").toString(Charsets.UTF_8))
        assertEquals("payload: ['+.example.com']", entries.getValue("profiles/${imported.id}/resources/rules/local.yaml").toString(Charsets.UTF_8))
        assertEquals(entries.keys - "manifest.json", manifest.files.map { it.path }.toSet())
        manifest.files.forEach { record ->
            val content = entries.getValue(record.path)
            assertEquals(content.size.toLong(), record.bytes)
            assertEquals(sha256(content.toString(Charsets.UTF_8)), record.sha256)
        }
        val target = data("target")
        val result = restore(target, file.readBytes())
        assertTrue(result.restoredSettings)
        assertEquals(wanted, target.settings.read())
        val id = result.profileIds.getValue(imported.id)
        assertEquals(id, target.profiles.snapshot().selectedId)
        val prepared = target.profiles.prepareForStart(id)
        assertEquals(yaml, prepared.source)
        val rule = (ConfigYaml.parse(prepared.yaml)["rule-providers"] as Map<*, *>)["local"] as Map<*, *>
        assertEquals("payload: ['+.example.com']", File(rule["path"] as String).readText())
        assertEquals("secret", target.backups.readWebDav()!!.password)
        assertTrue(File(target.root, "backups").listFiles()!!.none { it.name.startsWith(".work-") })
    }

    @Test fun compatibleUpsertsStableBackupIdentitiesAndOverrideKeepsRunningLeaseFiles() = runBlocking<Unit> {
        val source = data("source")
        val original = source.profiles.importText("Main", simple)
        val prepared = source.profiles.prepareForStart(original.id)
        val lease = source.profiles.retain(prepared)
        val bytes = archive(source)
        val first = restore(source, bytes).profileIds.getValue(original.id)
        assertNotEquals(original.id, first)
        assertEquals(1, source.profiles.list().size)
        assertTrue(prepared.resourceDirectory!!.isDirectory)
        assertTrue(File(source.root, "profiles/${prepared.revision}").isFile)
        val unrelated = source.profiles.importText("Keep", simple)
        restore(source, bytes)
        assertEquals(2, source.profiles.list().size)
        assertTrue(source.profiles.list().any { it.id == unrelated.id })
        restore(source, bytes, strategy = BackupRestoreStrategy.Override)
        assertEquals(1, source.profiles.list().size)
        assertTrue(prepared.resourceDirectory.isDirectory)
        source.profiles.release(lease)
        assertFalse(prepared.resourceDirectory.exists())
        assertFalse(File(source.root, "profiles/${prepared.revision}").exists())
    }

    @Test fun snapshotUsesVerifiedSealedResourcesInsteadOfARejectedMutableImport() = runBlocking<Unit> {
        val source = data("source")
        val yaml = "rule-providers: {local: {type: file, path: ./rules/local.yaml, behavior: domain}}"
        val profile = source.profiles.importFile("Local", ByteArrayInputStream(zip(mapOf(
            "config.yaml" to yaml.toByteArray(), "rules/local.yaml" to "payload: ['+.verified.example']".toByteArray(),
        ))))
        File(source.root, "runtime/resources/imports/${profile.id}/rules/local.yaml").writeText("invalid mutable replacement")
        val target = data("target")
        val result = restore(target, archive(source))
        val prepared = target.profiles.prepareForStart(result.profileIds.getValue(profile.id))
        val rule = (ConfigYaml.parse(prepared.yaml)["rule-providers"] as Map<*, *>)["local"] as Map<*, *>
        assertEquals("payload: ['+.verified.example']", File(rule["path"] as String).readText())
    }

    @Test fun profilesOnlyKeepsSettingsAndDoesNotGrantBackupVpnAuthorization() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("Imported", simple)
        source.settings.save(LiteSettings(autoStart = true, mode = ProxyMode.Global), vpnAuthorized = true)
        val target = data("target")
        val settings = target.settings.save(LiteSettings(mode = ProxyMode.Direct))
        val result = restore(target, archive(source), scope = BackupRestoreScope.ProfilesOnly)
        assertFalse(result.restoredSettings)
        assertEquals(settings, target.settings.read())
        assertEquals(1, target.profiles.list().size)
    }

    @Test fun profilesOnlyMergePreservesSelectionAcrossNewPhysicalIds() = runBlocking<Unit> {
        val source = data("source")
        val first = source.profiles.importText("First", simple)
        val second = source.profiles.importText("Second", simple)
        source.profiles.select(second.id)
        val bytes = archive(source)
        val target = data("target")
        val initially = restore(target, bytes)
        target.profiles.select(initially.profileIds.getValue(first.id))
        val updated = restore(target, bytes, scope = BackupRestoreScope.ProfilesOnly)
        assertEquals(updated.profileIds.getValue(first.id), target.profiles.snapshot().selectedId)
        assertEquals(2, target.profiles.list().size)
    }

    @Test fun unauthorizedAutoStartIsRejectedBeforePublishingAnyData() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("Imported", simple)
        source.settings.save(LiteSettings(autoStart = true), vpnAuthorized = true)
        val bytes = archive(source)
        val target = data("target")
        val old = target.profiles.importText("Existing", simple)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(target, bytes) } }
        assertEquals(listOf(old), target.profiles.list())
        assertFalse(target.settings.read().autoStart)
        restore(target, bytes, authorized = true)
        assertTrue(target.settings.read().autoStart)
    }

    @Test fun nativeResourceRejectionLeavesPriorSettingsIndexAndLeaseUntouched() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("First", simple)
        val local = "rule-providers: {local: {type: file, path: local.yaml, behavior: domain}}"
        source.profiles.importFile("Second", ByteArrayInputStream(zip(mapOf("config.yaml" to local.toByteArray(),
            "local.yaml" to "payload: ['+.example.com']".toByteArray()))))
        val bytes = archive(source)
        val target = data("target", NativeResourceValidator { kind, _, _, _ -> if (kind == "ruleset") "fixture native rejection" else null })
        val previous = target.profiles.importText("Keep", simple)
        val prepared = target.profiles.prepareForStart(previous.id)
        val lease = target.profiles.retain(prepared)
        target.settings.save(LiteSettings(mode = ProxyMode.Direct))
        val index = File(target.root, "profiles/index.json").readBytes()
        val settings = File(target.root, "settings/settings.json").readBytes()
        assertThrows(ConfigValidationException::class.java) { runBlocking { restore(target, bytes, strategy = BackupRestoreStrategy.Override) } }
        assertArrayEquals(index, File(target.root, "profiles/index.json").readBytes())
        assertArrayEquals(settings, File(target.root, "settings/settings.json").readBytes())
        assertTrue(prepared.resourceDirectory!!.isDirectory)
        assertEquals(listOf(previous), target.profiles.list())
        target.profiles.release(lease)
    }

    @Test fun crossStorePublicationFailureRollsBackExactPriorFiles() = runBlocking<Unit> {
        val data = data("data")
        data.profiles.importText("Keep", simple)
        data.settings.save(LiteSettings(mode = ProxyMode.Direct))
        val settings = File(data.root, "settings/settings.json").readBytes()
        val index = File(data.root, "profiles/index.json").readBytes()
        assertThrows(IOException::class.java) { runBlocking {
            atomicDomainRestore(data.root) {
                data.settings.save(LiteSettings(mode = ProxyMode.Global))
                PrivateStorage(File(data.root, "profiles")).write("index.json", "{broken")
                throw IOException("injected publication failure")
            }
        } }
        assertArrayEquals(settings, File(data.root, "settings/settings.json").readBytes())
        assertArrayEquals(index, File(data.root, "profiles/index.json").readBytes())
        assertFalse(File(data.root, ".restore-journal").exists())
    }

    @Test fun reopeningEitherStoreRecoversInterruptedPublicationBeforeReading() = runBlocking<Unit> {
        val data = data("data")
        val previous = data.profiles.importText("Keep", simple)
        val settings = data.settings.save(LiteSettings(mode = ProxyMode.Direct))
        val journal = File(data.root, ".restore-journal").apply { mkdir() }
        File(data.root, "settings/settings.json").copyTo(File(journal, "0.json"))
        File(data.root, "profiles/index.json").copyTo(File(journal, "1.json"))
        File(journal, "state.json").writeText("[true,true,false]")
        File(data.root, "settings/settings.json").writeText("{broken")
        File(data.root, "profiles/index.json").writeText("{broken")
        assertEquals(settings, data.settings.read())
        assertEquals(listOf(previous), data.profiles.list())
        assertFalse(journal.exists())
    }

    @Test fun snapshotsWaitUntilTheWholeDomainPublicationCompletes() = runBlocking<Unit> {
        val data = data("data")
        data.profiles.importText("Keep", simple)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async { atomicDomainRestore(data.root) {
            data.settings.save(LiteSettings(mode = ProxyMode.Direct))
            started.complete(Unit)
            release.await()
        } }
        started.await()
        val reader = async { archive(data) }
        kotlinx.coroutines.yield()
        assertFalse(reader.isCompleted)
        release.complete(Unit)
        writer.await()
        val restored = data("restored")
        restore(restored, reader.await())
        assertEquals(ProxyMode.Direct, restored.settings.read().mode)
    }

    @Test fun traversalCaseDuplicatesAndFileDirectoryCollisionsAreRejected() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("Main", simple)
        val entries = unpack(archive(source))
        val target = data("target")
        for (name in listOf("../outside", "/absolute", "C:/drive", "a\\b", "profiles/../x", "SETTINGS.JSON", "settings.json/child")) {
            val malicious = LinkedHashMap(entries).apply { put(name, "bad".toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(target, zip(malicious)) } }
            assertTrue(target.profiles.list().isEmpty())
        }
        assertFalse(File(target.root.parentFile, "outside").exists())
    }

    @Test fun manifestHashMissingFilesUnlistedPayloadAndProfileZipAreRejected() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("Main", simple)
        val entries = unpack(archive(source))
        val target = data("target")
        val wrong = LinkedHashMap(entries).apply { put("settings.json", "{}".toByteArray()) }
        val missing = LinkedHashMap(entries).apply { remove("settings.json") }
        val extra = LinkedHashMap(entries).apply { put("extra.txt", "not in manifest".toByteArray()) }
        val duplicate = LinkedHashMap(entries)
        val manifest = JSONObject(duplicate.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONArray("files").put(manifest.getJSONArray("files").getJSONObject(0))
        duplicate["manifest.json"] = manifest.toString().toByteArray()
        for (bytes in listOf(zip(wrong), zip(missing), zip(extra), zip(duplicate), zip(mapOf("config.yaml" to simple.toByteArray())))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(target, bytes) } }
            assertTrue(target.profiles.list().isEmpty())
        }
    }

    @Test fun crcCorruptionTruncationAndCentralExpandedLimitAreRejected() = runBlocking<Unit> {
        val source = data("source")
        source.profiles.importText("Main", simple)
        val bytes = archive(source)
        val central = (0..bytes.size - 46).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
            bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        val crc = bytes.copyOf().apply { this[central + 16] = (this[central + 16].toInt() xor 1).toByte() }
        val expanded = bytes.copyOf().apply { repeat(4) { this[central + 24 + it] = 0xff.toByte() } }
        val target = data("target")
        for (bad in listOf(crc, expanded, bytes.copyOf(bytes.size - 12))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(target, bad) } }
            assertTrue(target.profiles.list().isEmpty())
        }
    }

    @Test fun archiveEntryAndExpandedQuotasNeverReplaceAnExistingDestination() = runBlocking<Unit> {
        val source = data("source", limits = BackupLimits(entries = 2))
        source.profiles.importText("Main", simple)
        val destination = temporary.newFile().apply { writeText("keep destination") }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source.backups.createBackup(destination) } }
        assertEquals("keep destination", destination.readText())
        val normal = data("normal")
        normal.profiles.importText("Main", simple)
        val bytes = archive(normal)
        val small = data("small", limits = BackupLimits(archiveBytes = 100))
        assertThrows(IOException::class.java) { runBlocking { restore(small, bytes) } }
        assertTrue(small.profiles.list().isEmpty())
        val expanded = data("expanded", limits = BackupLimits(expandedBytes = 10))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(expanded, bytes) } }
        val entry = data("entry", limits = BackupLimits(entryBytes = 10))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { restore(entry, bytes) } }
    }
}
