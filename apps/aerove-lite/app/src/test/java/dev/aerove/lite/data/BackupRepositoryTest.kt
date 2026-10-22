package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.MaterialTier
import dev.aerove.lite.ProxyMode
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val simple = "rules: ['MATCH,DIRECT']"

    private data class Data(val root: File, val profiles: ProfileRepository, val settings: LiteSettingsStore, val backups: BackupRepository)

    private fun data(name: String, native: NativeResourceValidator = NativeResourceValidator { _, _, _, _ -> null },
        limits: BackupLimits = BackupLimits()): Data {
        val root = temporary.newFolder(name)
        val runtime = File(root, "runtime")
        val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
        val profiles = ProfileRepository(root, validator, resources = ResourceBundleManager(runtime, validator, native))
        val settings = LiteSettingsStore(root)
        return Data(root, profiles, settings, BackupRepository(root, profiles, settings, limits) { 1234567 })
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
