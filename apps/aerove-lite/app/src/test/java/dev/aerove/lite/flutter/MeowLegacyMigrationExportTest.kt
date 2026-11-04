package dev.aerove.lite.flutter

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.*
import dev.aerove.lite.overrides.*
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowLegacyMigrationExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val yaml = "\uFEFFmode: rule\r\nrules:\r\n  - MATCH,DIRECT\r\n"
    private val validator = ProfileValidator { }
    private data class Fixture(val root: File, val repo: ProfileRepository, val store: LiteSettingsStore,
        val overrides: OverrideRepository, val first: String, val selected: String, val manager: ResourceBundleManager?) {
        fun exporter() = MeowLegacyMigrationExport(root, repo, store, overrides)
    }
    private suspend fun fixture(withResources: Boolean = false): Fixture {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime")
        val manager = if (withResources) ResourceBundleManager(runtime,
            CompatibilityValidator(NativeConfigValidator { null }, runtime), NativeResourceValidator { _, _, _, _ -> null }) else null
        val repo = ProfileRepository(root, validator, resources = manager)
        val first = repo.importText("Fixture A", yaml, "https://example.invalid/source?token=fixture-private").id
        val second = repo.importText("Fixture B", yaml).id
        repo.select(second)
        LiteSettingsStore(root).save(LiteSettings())
        val overrides = OverrideRepository(root)
        overrides.putScript(ScriptResource(BundledMeowScript.ID, "Bundled fixture", null, "function main(c) { return c; }", 1))
        overrides.deleteScript(BundledMeowScript.ID)
        overrides.putScript(ScriptResource("custom", "User", "https://example.invalid/script?token=fixture-private", "// exact\r\nfunction main(c) { return c; }", 22))
        overrides.setGlobalRules(listOf(OverrideRule("r1", "DOMAIN,example.invalid,DIRECT"), OverrideRule("r2", "MATCH,DIRECT")))
        overrides.setProfile(ProfileOverride(first, OverrideMode.Script, "custom", listOf("DOMAIN,local.invalid,DIRECT"), listOf("r2"),
            dnsPreferences = dev.aerove.lite.dnssettings.DnsPreferences()
                .withValue(dev.aerove.lite.dnssettings.DnsField.Nameserver, dev.aerove.lite.dnssettings.DnsValue.Entries(listOf("https://example.invalid/dns-query")))
                .withValue(dev.aerove.lite.dnssettings.DnsField.Ipv6, dev.aerove.lite.dnssettings.DnsValue.Bool(false))
                .inherit(dev.aerove.lite.dnssettings.DnsField.Ipv6)))
        val indexFile = File(root, "profiles/index.json")
        val index = JSONObject(indexFile.readText())
        val profile = index.getJSONArray("profiles").getJSONObject(0)
        val revision = "${UUID.randomUUID()}.yaml"
        File(root, "profiles/$revision").writeText("mode: direct\n")
        profile.put("previous", revision)
        profile.getJSONObject("sourceMetadata").put("ageIdentity", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 0, 127)))
        index.put("unknown", JSONObject().put("original", "keep\r\nunknown"))
        indexFile.writeText(index.toString())
        File(root, "profiles/${UUID.randomUUID()}.yaml").writeText("orphan remains untouched")
        val library = File(root, "settings/overrides.json")
        val raw = JSONObject(library.readText()).put("bundledScriptInitialized", true).put("unknown", "exact")
        library.writeText(raw.toString())
        File(root, "backups").mkdir()
        File(root, "backups/webdav.json").writeText("""{"url":"https://example.invalid/dav","user":"fixture","password":"fixture-private","fileName":"aerove-lite.zip","unknown":"preserved"}""")
        return Fixture(root, repo, LiteSettingsStore(root), OverrideRepository(root) { error("export must never load bundled script") }, first, second, manager)
    }
    private fun hashes(root: File) = root.walkTopDown().filter { it.isFile && "native-lite-migration-exports" !in it.relativeTo(root).invariantSeparatorsPath }
        .associate { it.relativeTo(root).invariantSeparatorsPath to sha256(it) }
    private fun published(root: File) = File(root, "native-lite-migration-exports").listFiles().orEmpty().toList()
    private suspend fun rejected(f: Fixture, field: String): MeowLegacyExportException {
        val before = hashes(f.root)
        val failure = runCatching { f.exporter().export() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is MeowLegacyExportException)
        assertTrue((failure as MeowLegacyExportException).legacyField.startsWith(field))
        assertFalse(failure.toString().contains("fixture-private"))
        assertEquals(before, hashes(f.root))
        assertTrue(published(f.root).isEmpty())
        return failure
    }

    @Test fun rawSourceIdentitySelectionHistorySecretsUnknownAndDeletionFactSurviveWithoutSourceMutation() = runBlocking<Unit> {
        val f = fixture()
        val before = hashes(f.root)
        assertNull(f.store.changes.value)
        f.exporter().export().use { lease ->
            val manifest = JSONObject(lease.manifest.readText())
            assertFalse(manifest.getBoolean("migrationComplete"))
            assertEquals(sha256(lease.manifest), lease.manifestSha256)
            val payload = File(lease.manifest.parentFile, "payload")
            val rows = manifest.getJSONArray("files")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val path = row.getString("path")
                assertEquals(before[path], row.getString("sha256"))
                assertArrayEquals(File(f.root, path).readBytes(), File(payload, path).readBytes())
            }
            val index = JSONObject(File(payload, "profiles/index.json").readText())
            assertEquals(f.selected, index.getString("selected"))
            assertEquals("keep\r\nunknown", index.getJSONObject("unknown").getString("original"))
            val row = index.getJSONArray("profiles").getJSONObject(0)
            assertEquals(f.first, row.getString("id"))
            assertTrue(File(payload, "profiles/${row.getString("previous")}").isFile)
            assertArrayEquals(byteArrayOf(1, 2, 0, 127), Base64.getDecoder().decode(row.getJSONObject("sourceMetadata").getString("ageIdentity")))
            val scripts = OverrideRepository.decode(File(payload, "settings/overrides.json").readText())
            assertTrue(scripts.bundledScriptInitialized)
            assertEquals(listOf("custom"), scripts.scripts.map { it.id })
            assertEquals(listOf("r1", "r2"), scripts.globalRules.map { it.id })
            assertEquals(listOf("r2"), scripts.profiles.single().disabledGlobalRuleIds)
            val dns = scripts.profiles.single().dnsPreferences!!
            assertEquals(setOf(dev.aerove.lite.dnssettings.DnsField.Nameserver), dns.overridden)
            assertEquals(dev.aerove.lite.dnssettings.DnsValue.Bool(false), dns.values[dev.aerove.lite.dnssettings.DnsField.Ipv6])
            assertEquals(before, hashes(f.root))
            assertNull(f.store.changes.value)
            assertFalse(lease.toString().contains("fixture-private"))
        }
        assertTrue(published(f.root).isEmpty())
        assertEquals(before, hashes(f.root))
    }
    @Test fun damagedLogicalHashIsRejectedAndNotRepaired() = runBlocking<Unit> {
        val f = fixture()
        val file = File(f.root, "profiles/index.json")
        val index = JSONObject(file.readText())
        index.getJSONArray("profiles").getJSONObject(0).getJSONObject("sourceMetadata").put("logicalSourceSha256", "0".repeat(64))
        file.writeText(index.toString())
        rejected(f, "profiles/index.json")
    }
    @Test fun missingRetainedRevisionIsRejected() = runBlocking<Unit> {
        val f = fixture()
        val row = JSONObject(File(f.root, "profiles/index.json").readText()).getJSONArray("profiles").getJSONObject(0)
        File(f.root, "profiles/${row.getString("previous")}").delete()
        rejected(f, "profiles/index.json")
    }
    @Test fun pendingRestoreJournalIsNeverRecoveredByExport() = runBlocking<Unit> {
        val f = fixture()
        File(f.root, ".restore-journal").mkdir()
        File(f.root, ".restore-journal/state.json").writeText("broken fixture journal")
        val failure = rejected(f, "files/.domain.lock")
        assertTrue(failure.cause is IllegalStateException)
        assertEquals("Pending restore journal requires owner recovery", failure.cause!!.message)
    }
    @Test fun exportDiagnosticExcludesSensitiveMessagesAndBoundsCauseFrames() {
        val frames = Array(24) { index ->
            if (index == 0) StackTraceElement("fixture.NativeExport", "acquire", "Fixture.kt", 42)
            else StackTraceElement("fixture." + "LongClass".repeat(100), "frame", "Fixture.kt", index)
        }
        var original: Throwable = IllegalArgumentException("https://secret.invalid/?token=fixture-private")
        repeat(6) {
            val message = if (it % 2 == 0) "https://secret.invalid/?token=fixture-private"
                else "AGE-SECRET-KEY-fixture-private"
            original = IllegalStateException(message, original).also { it.stackTrace = frames }
        }
        val error = MeowLegacyExportException("profiles/fixture-private", "https://secret.invalid/?token=fixture-private AGE-SECRET-KEY", original)
            .also { it.stackTrace = frames }
        val diagnostic = JSONObject(error.safeDiagnostic())
        val causes = diagnostic.getJSONArray("causes")
        assertEquals(4, causes.length())
        assertEquals(MeowLegacyExportException::class.java.name, causes.getJSONObject(0).getString("type"))
        assertEquals(IllegalStateException::class.java.name, causes.getJSONObject(1).getString("type"))
        assertEquals("fixture.NativeExport.acquire(Fixture.kt:42)", causes.getJSONObject(1).getJSONArray("frames").getString(0))
        for (i in 0 until causes.length()) {
            val locations = causes.getJSONObject(i).getJSONArray("frames")
            assertEquals(8, locations.length())
            for (j in 0 until locations.length()) assertTrue(locations.getString(j).length <= 256)
        }
        val wire = diagnostic.toString()
        assertTrue(wire.length < 12000)
        listOf("fixture-private", "secret.invalid", "AGE-SECRET-KEY", "https://").forEach { assertFalse(wire.contains(it)) }
        assertSame(original, error.cause)
    }
    @Test fun pathEscapeInRevisionIsRejected() = runBlocking<Unit> {
        val f = fixture()
        val file = File(f.root, "profiles/index.json")
        val index = JSONObject(file.readText())
        index.getJSONArray("profiles").getJSONObject(0).put("current", "../../escape.yaml")
        file.writeText(index.toString())
        rejected(f, "profiles/index.json")
    }
    @Test fun missingResourceOwnerBlocksExportInsteadOfPretendingConsistency() = runBlocking<Unit> {
        val f = fixture()
        File(f.root, "runtime/resources/imports").mkdirs()
        rejected(f, "runtime/resources")
    }
    @Test fun malformedSettingsDoNotBecomeDefaults() = runBlocking<Unit> {
        val f = fixture()
        File(f.root, "settings/settings.json").writeText("{}"); rejected(f, "settings/settings.json")
    }
    @Test fun cancelledCallerPublishesNoExport() = runBlocking<Unit> {
        val f = fixture()
        val before = hashes(f.root)
        val job = launch(start = CoroutineStart.LAZY) { f.exporter().export().close() }
        job.cancel(); job.join()
        assertEquals(before, hashes(f.root)); assertTrue(published(f.root).isEmpty())
    }
    @Test fun librarySymlinkIsRejectedBeforeConstructingOrReadingTheCacheOwner() = runBlocking<Unit> {
        val f = fixture(true)
        val link = File(f.root, "runtime/resources/library")
        val outside = temporary.newFolder()
        File(outside, "sentinel").writeText("outside fixture boundary")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("Host must permit symbolic links", made)
        rejected(f, "files")
    }
    @Test fun realSymlinkSourceIsRejectedWhenHostAllowsLinks() = runBlocking<Unit> {
        val f = fixture()
        val link = File(f.root, "settings/link")
        val target = temporary.newFile()
        val made = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        org.junit.Assume.assumeTrue("Host must permit symbolic links", made)
        rejected(f, "files")
    }
    @Test fun completeResourceOriginsAndBuilderLibraryAreCopiedUnderTheRealFence() = runBlocking<Unit> {
        val f = fixture(true)
        val bytes = byteArrayOf(0, 1, 127, -1, 10)
        f.manager!!.importLocal(f.first, "local/provider.bin", bytes.inputStream())
        File(f.root, "settings/builder-resources.yaml").writeText(dev.aerove.lite.profilebuilder.BuilderResources().yaml())
        val cacheBytes = byteArrayOf(12, 45, 67, 89)
        val cacheFile = temporary.newFile().apply { writeBytes(cacheBytes) }
        f.manager.importLibraryRule(dev.aerove.lite.resources.ManagedRuleResource("geo fixture", "https://example.invalid/fixture.dat",
            "", "", cacheBytes.size.toLong(), sha256(cacheFile), 22, null, kind = "geosite"), cacheBytes.inputStream())
        val before = hashes(f.root)
        f.exporter().export().use { lease ->
            val path = "runtime/resources/imports/${f.first}/local/provider.bin"
            assertArrayEquals(bytes, File(lease.manifest.parentFile, "payload/$path").readBytes())
            val rows = JSONObject(lease.manifest.readText()).getJSONArray("files")
            assertTrue((0 until rows.length()).any { rows.getJSONObject(it).getString("role") == "resource-recovery-only" })
            assertEquals(before, hashes(f.root))
        }
        assertEquals(before, hashes(f.root))
    }
    @Test fun corruptLibraryPayloadIsRejectedByTheExistingCacheVerifier() = runBlocking<Unit> {
        val f = fixture(true)
        val bytes = byteArrayOf(1, 2, 3)
        val original = temporary.newFile().apply { writeBytes(bytes) }
        f.manager!!.importLibraryRule(dev.aerove.lite.resources.ManagedRuleResource("geo fixture", "https://example.invalid/fixture.dat",
            "", "", bytes.size.toLong(), sha256(original), 22, null, kind = "geosite"), bytes.inputStream())
        val payload = File(f.root, "runtime/resources/library").walkTopDown().single { it.name == "payload.bin" }
        payload.writeBytes(byteArrayOf(3, 2, 1))
        rejected(f, "runtime/resources/library/")
    }
    @Test fun actualImportWriterAndSnapshotExcludeEachOtherAndReleaseOnFailure() = runBlocking<Unit> {
        val f = fixture(true)
        val manager = f.manager!!
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val input = object : java.io.InputStream() {
            private var delivered = false
            override fun read(): Int {
                entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                if (delivered) return -1
                delivered = true
                return 1
            }
        }
        val writing = async(Dispatchers.IO) { manager.importLocal(f.first, "blocked.bin", input) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, java.util.concurrent.TimeUnit.SECONDS) })
            val error = runCatching { f.exporter().export() }.exceptionOrNull()
            assertTrue(error is MeowLegacyExportException)
            assertEquals("runtime/resources", (error as MeowLegacyExportException).legacyField)
            assertTrue(published(f.root).isEmpty())
        } finally { release.countDown(); writing.await() }
        manager.withMigrationSnapshot {
            assertTrue(runCatching { manager.importLocal(f.first, "never.bin", byteArrayOf(1).inputStream()) }.isFailure)
            assertFalse(File(f.root, "runtime/resources/imports/${f.first}/never.bin").exists())
        }
        assertTrue(runCatching {
            manager.withLibraryRestoreTransaction { manager.withLibraryRestoreTransaction { error("fixture nested failure") } }
        }.isFailure)
        f.exporter().export().close()
    }
    @Test fun generationCloseAlwaysReleasesAndPendingDiscardStillCompletes() = runBlocking<Unit> {
        val f = fixture(true)
        val manager = f.manager!!
        val id = UUID.randomUUID().toString()
        val bundle = manager.prepare(id, "mode: rule\nrules: ['MATCH,DIRECT']\n")
        manager.committed(id, bundle.generation)
        val reader = manager.retainGeneration(bundle.directory)
        rejected(f, "runtime/resources")
        manager.discard(id, bundle.generation)
        reader.close(); reader.close()
        assertFalse(bundle.directory.exists())
        f.exporter().export().close()
    }
    @Test fun callerCancellationDuringPayloadCopyRemovesCandidateAndReleasesSnapshot() = runBlocking<Unit> {
        val f = fixture(true)
        val large = File(f.root, "profiles/large-fixture.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(32L * 1024 * 1024) }
        val before = hashes(f.root)
        val job = launch(Dispatchers.IO) { f.exporter().export().close() }
        withTimeout(10000) { while (published(f.root).none { it.name.startsWith(".pending-") }) { check(job.isActive); yield() } }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(before, hashes(f.root)); assertTrue(published(f.root).isEmpty())
        large.delete()
        f.exporter().export().close()
    }
    @Test fun unsupportedExternalSourceModificationCannotPublishCompleteExport() = runBlocking<Unit> {
        val f = fixture(true)
        val large = File(f.root, "profiles/large-fixture.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(32L * 1024 * 1024) }
        val copying = async(Dispatchers.IO) { runCatching { f.exporter().export() } }
        withTimeout(10000) { while (published(f.root).none { it.name.startsWith(".pending-") }) { check(copying.isActive); yield() } }
        File(f.root, "settings/settings.json").appendText(" ")
        val failure = copying.await().exceptionOrNull()
        assertTrue(failure is MeowLegacyExportException)
        assertTrue(published(f.root).isEmpty())
    }

    @Test fun retainedTunBundlesUseEachActualPolicyAndOldSettingsWithoutChangingSource() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime")
        val settingsStore = LiteSettingsStore(root)
        settingsStore.save(LiteSettings(ipv6 = true,
            network = dev.aerove.lite.networksettings.NetworkSettings(mtu = 1450)))
        val savedSettings = settingsStore.read()
        var preparedChecks = 0
        val unbound = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, _ -> preparedChecks++; null }) { setOf("ss") }
        fun manager(validator: CompatibilityValidator) = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null })
        fun oldRepository(source: String): ProfileRepository {
            val reconciliation = MeowTunReconciliation.reconcile(source, savedSettings)
            val bound = unbound.withTunReconciliation(reconciliation)
            return ProfileRepository(root, bound, resources = manager(bound))
        }
        val firstSource = "mode: rule\ntun: {enable: true, mtu: 1480}\nrules: ['MATCH,DIRECT']\n"
        val secondSource = "mode: rule\ntun: {enable: false, mtu: 1400}\nrules: ['MATCH,DIRECT']\n"
        val firstRepository = oldRepository(firstSource)
        val first = firstRepository.importText("Retained TUN A", firstSource)
        firstRepository.markUsable(firstRepository.prepareForStart(first.id))
        firstRepository.replace(first.id, firstSource.replace("mode: rule", "mode: direct"))
        firstRepository.replace(first.id, firstSource.replace("mode: rule", "mode: global"))
        val secondRepository = oldRepository(secondSource)
        val second = secondRepository.importText("Retained TUN B", secondSource)
        val index = JSONObject(File(root, "profiles/index.json").readText()).getJSONArray("profiles")
        val firstRow = (0 until index.length()).map(index::getJSONObject).single { it.getString("id") == first.id }
        val secondRow = (0 until index.length()).map(index::getJSONObject).single { it.getString("id") == second.id }
        assertFalse(firstRow.isNull("bundle"))
        assertFalse(firstRow.isNull("previousBundle"))
        assertFalse(firstRow.isNull("lastUsableBundle"))
        assertNotEquals(firstRow.getString("bundle"), firstRow.getString("previousBundle"))
        assertNotEquals(firstRow.getString("previousBundle"), firstRow.getString("lastUsableBundle"))
        assertNotEquals(firstRow.getString("bundle"), firstRow.getString("lastUsableBundle"))
        assertNotEquals(firstRow.getString("bundle"), secondRow.getString("bundle"))
        val unboundManager = manager(unbound)
        val currentSource = File(root, "profiles/${firstRow.getString("current")}").readText()
        val activationFailure = runCatching {
            unboundManager.load(first.id, firstRow.getString("bundle"), currentSource)
        }.exceptionOrNull()
        assertTrue(activationFailure is ConfigValidationException)
        assertTrue((activationFailure as ConfigValidationException).issues.any {
            it.path == "$.tun.enable" && it.reason.contains("requires Android Builder reconciliation")
        })
        val profiles = ProfileRepository(root, unbound, resources = unboundManager)
        val settings = LiteSettingsStore(root)
        val exporter = MeowLegacyMigrationExport(root, profiles, settings, OverrideRepository(root))
        val before = hashes(root)
        val settingsBefore = File(root, "settings/settings.json").readBytes()
        val checksBefore = preparedChecks
        try {
            exporter.export().use { lease ->
                val rows = JSONObject(lease.manifest.readText()).getJSONArray("files")
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val path = row.getString("path")
                    assertEquals(before[path], row.getString("sha256"))
                    assertEquals(before[path], sha256(File(lease.manifest.parentFile, "payload/$path")))
                }
            }
        } catch (failure: MeowLegacyExportException) {
            runCatching { profiles.withMigrationSourceSnapshot { profiles.migrationSnapshot() } }
                .exceptionOrNull()?.let { failure.addSuppressed(it) }
            throw failure
        }
        assertTrue("All retained slots must still reach prepared validation", preparedChecks >= checksBefore + 4)
        assertEquals(before, hashes(root))
        assertArrayEquals(settingsBefore, File(root, "settings/settings.json").readBytes())
        assertNull(settings.changes.value)
        assertTrue(published(root).isEmpty())
        val oldConfig = File(runtime, "resources/bundles/${first.id}/${firstRow.getString("previousBundle")}/config.yaml")
        oldConfig.appendText("\n# damaged retained generation\n")
        val damaged = hashes(root)
        val failure = runCatching { exporter.export() }.exceptionOrNull()
        assertTrue(failure is MeowLegacyExportException)
        assertEquals("profiles/index.json", (failure as MeowLegacyExportException).legacyField)
        assertEquals(damaged, hashes(root))
        assertTrue(published(root).isEmpty())
    }
}

class ResourceReaderFenceRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun snapshotRejectsBusyLibraryReaderInsteadOfWaitingWithTheProfileDomainHeld() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val manager = ResourceBundleManager(runtime, CompatibilityValidator(NativeConfigValidator { null }, runtime),
            NativeResourceValidator { _, _, _, _ -> null })
        manager.withLibraryRulesSnapshot(emptyList()) { }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.IO) { manager.withLibraryRulesSnapshot(emptyList()) { entered.complete(Unit); release.await() } }
        try {
            entered.await()
            val failure = runCatching { withTimeout(500) { manager.withMigrationSnapshot { } } }.exceptionOrNull()
            assertTrue("Snapshot waited for a reader instead of rejecting it", failure !is CancellationException)
            assertTrue(failure?.message?.startsWith("runtime/resources/library:") == true)
        } finally { release.complete(Unit); reader.await() }
        manager.withMigrationSnapshot { }
    }
}
