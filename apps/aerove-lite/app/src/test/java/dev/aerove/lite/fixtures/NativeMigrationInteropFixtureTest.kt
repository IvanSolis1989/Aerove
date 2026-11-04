package dev.aerove.lite.fixtures

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.BackupRepository
import dev.aerove.lite.data.CompatibilityValidator
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.MeowTunReconciliation
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.data.NativePreparedConfigValidator
import dev.aerove.lite.data.NativeResourceValidator
import dev.aerove.lite.data.ResourceBundleManager
import dev.aerove.lite.data.LiteSettingsStore
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.data.ProfileValidator
import dev.aerove.lite.data.WebDavBackupConfig
import dev.aerove.lite.data.sha256
import dev.aerove.lite.flutter.MeowLegacyMigrationExport
import dev.aerove.lite.overrides.BundledMeowScript
import dev.aerove.lite.overrides.OverrideMode
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.ProfileOverride
import dev.aerove.lite.overrides.ScriptResource
import dev.aerove.lite.profilebuilder.BuilderEntry
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.BuilderResources
import dev.aerove.lite.profilesource.AgeSecret
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real repository input for the existing four Dart migration tests, not an export JSON fixture. */
class NativeMigrationInteropFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val yaml = "\uFEFFmode: rule\r\nrules:\r\n  - MATCH,DIRECT\r\n"
    private val identity = "# fixture identity\nAGE-SECRET-KEY-FIXTURE\n".toByteArray(Charsets.UTF_8)
    private val script = "// retained\r\nfunction main(c) { return c; }"
    private val preparedDirectories = mutableListOf<String>()

    private data class SourceSeed(
        val root: File, val profiles: ProfileRepository, val settings: LiteSettingsStore,
        val overrides: OverrideRepository, val subscriptionId: String, val localId: String,
        val retainedTunGeneration: String,
    )

    private suspend fun seed(root: File): SourceSeed {
        val settings = LiteSettingsStore(root)
        settings.save(LiteSettings())
        val savedSettings = settings.read()
        val runtime = File(root, "runtime")
        // The callbacks isolate JNI only; source policy, reconciliation and bundle persistence are real.
        val unbound = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, directory -> preparedDirectories += directory.canonicalPath; null }) { setOf("ss") }
        fun manager(validator: CompatibilityValidator) = ResourceBundleManager(runtime, validator,
            NativeResourceValidator { _, _, _, _ -> null })
        val historicalYaml = yaml + "tun: {enable: true, mtu: 1480}\r\n"
        val bound = unbound.withTunReconciliation(MeowTunReconciliation.reconcile(historicalYaml, savedSettings))
        val historical = ProfileRepository(root, bound, resources = manager(bound))
        val subscription = historical.importText("Fixture subscription", historicalYaml, "https://example.invalid/subscription")
        historical.markUsable(historical.prepareForStart(subscription.id))
        historical.replace(subscription.id, yaml)
        val profiles = ProfileRepository(root, ProfileValidator { })
        val local = profiles.importText("Fixture local", yaml)
        val rows = JSONObject(File(root, "profiles/index.json").readText()).getJSONArray("profiles")
        val row = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == subscription.id }
        val retainedTunGeneration = row.getString("previousBundle")
        assertEquals(retainedTunGeneration, row.getString("lastUsableBundle"))
        assertNotEquals(retainedTunGeneration, row.getString("bundle"))
        assertArrayEquals(yaml.toByteArray(Charsets.UTF_8), File(root, "profiles/${row.getString("current")}").readBytes())
        val historicalBundle = File(runtime, "resources/bundles/${subscription.id}/$retainedTunGeneration")
        val historicalManifest = JSONObject(File(historicalBundle, "manifest.json").readText())
        assertEquals(sha256(historicalYaml), historicalManifest.getString("sourceSha256"))
        val tun = ConfigYaml.parse(File(historicalBundle, "config.yaml").readText())["tun"] as Map<*, *>
        assertEquals(true, tun["enable"])
        assertEquals(1480, (tun["mtu"] as Number).toInt())
        AgeSecret(identity).use { profiles.setAgeSecret(subscription.id, it) }
        profiles.select(subscription.id)
        assertEquals(listOf(subscription.id, local.id), profiles.snapshot().profiles.map { it.id })
        assertEquals(subscription.id, profiles.snapshot().selectedId)
        checkNotNull(profiles.readAgeSecret(subscription.id)).use { assertArrayEquals(identity, it.copyBytes()) }

        val overrides = OverrideRepository(root)
        overrides.putScript(ScriptResource(BundledMeowScript.ID, "Bundled fixture", content = "function main(c) { return c; }", updatedAt = 1))
        overrides.deleteScript(BundledMeowScript.ID)
        overrides.putScript(ScriptResource("edited-script", "Edited script", content = script, updatedAt = 2))
        overrides.setProfile(ProfileOverride(subscription.id, OverrideMode.Script, "edited-script"))
        val library = BuilderResourceLibrary(root)
        library.replace(library.snapshot(), BuilderResources(proxies = listOf(BuilderEntry("Fixture SS", mapOf(
            "type" to "ss", "server" to "127.0.0.2", "port" to 443,
            "cipher" to "aes-128-gcm", "password" to "fixture-only",
        )))))
        BackupRepository(root, profiles, settings).saveWebDav(WebDavBackupConfig(
            "https://example.invalid/dav", "fixture", "fixture-only", "aerove-lite.zip",
        ))
        // An unknown old source field has no repository editing API; preserve it as raw old input.
        val index = File(root, "profiles/index.json")
        index.writeText(JSONObject(index.readText()).put("fixtureUnknown", JSONObject().put("preserve", true)).toString())
        return SourceSeed(root, ProfileRepository(root, unbound, resources = manager(unbound)), LiteSettingsStore(root),
            OverrideRepository(root) { error("export must not reseed a deleted bundled script") }, subscription.id, local.id,
            retainedTunGeneration)
    }

    private fun treeHashes(root: File): Map<String, String> = root.walkTopDown().filter { it.isFile }.associate { file ->
        assertFalse("Fixture contains a symbolic link", Files.isSymbolicLink(file.toPath()))
        file.relativeTo(root).invariantSeparatorsPath to sha256(file)
    }

    private fun sourceHashes(root: File): Map<String, String> =
        listOf("profiles", "settings", "backups/webdav.json", "runtime/resources").flatMap { path ->
            File(root, path).walkTopDown().filter { it.isFile }.toList()
        }.associate { file -> file.relativeTo(root).invariantSeparatorsPath to sha256(file) }

    private data class RetainedSnapshot(
        val leaseDirectory: File, val manifest: File, val digest: String, val hashes: Map<String, String>,
    )

    @Test fun exportsNativeSeedAndRetainsSnapshotAfterLeaseClose() = runBlocking<Unit> {
        val source = seed(temporary.newFolder("native-source").canonicalFile)
        val requested = System.getProperty("nativeMigration.fixture")
        require(requested == null || requested.isNotBlank()) { "Fixture output must not be blank" }
        val output = if (requested == null) File(temporary.root.canonicalFile, "snapshot") else File(requested).canonicalFile
        require(!output.exists()) { "Fixture output must be new" }
        require(!output.toPath().startsWith(source.root.toPath())) { "Fixture output must be outside the Native source" }
        if (requested != null) {
            require(!output.toPath().startsWith(temporary.root.canonicalFile.toPath())) { "Retained CI fixture must survive TemporaryFolder cleanup" }
        }
        check(output.mkdirs())
        val retained = File(output, "retained-export")
        val before = sourceHashes(source.root)
        // Source-domain lock files remain in before/after checks but are not exported.
        val expectedRecords = before.filterKeys { File(it).name !in setOf(".lock", ".domain.lock") }
        assertNull(source.settings.changes.value)
        val checksBeforeExport = preparedDirectories.size
        val snapshot = MeowLegacyMigrationExport(source.root, source.profiles, source.settings, source.overrides).export().use { lease ->
            val manifest = JSONObject(lease.manifest.readText())
            assertEquals("aerove-lite-native-source-export", manifest.getString("format"))
            assertEquals(1, manifest.getInt("schema"))
            assertEquals("source-recovery-candidate", manifest.getString("purpose"))
            assertFalse(manifest.getBoolean("migrationComplete"))
            assertEquals(lease.manifestSha256, sha256(lease.manifest))
            val rows = manifest.getJSONArray("files")
            val paths = (0 until rows.length()).map { rows.getJSONObject(it).getString("path") }
            assertEquals("No duplicate exported records", paths.size, paths.toSet().size)
            assertEquals(expectedRecords.keys, paths.toSet())
            assertTrue(paths.contains("runtime/resources/bundles/${source.subscriptionId}/${source.retainedTunGeneration}/config.yaml"))
            for (position in 0 until rows.length()) {
                val row = rows.getJSONObject(position)
                val path = row.getString("path")
                val original = File(source.root, path)
                val payload = File(lease.manifest.parentFile, "payload/$path")
                assertEquals(original.length(), row.getLong("bytes"))
                assertEquals(before[path], row.getString("sha256"))
                assertEquals(before[path], sha256(payload))
                assertArrayEquals(original.readBytes(), payload.readBytes())
            }
            val leaseDirectory = lease.manifest.parentFile
            val exported = treeHashes(leaseDirectory)
            assertEquals(setOf("manifest.json") + paths.map { "payload/$it" }, exported.keys)
            check(leaseDirectory.copyRecursively(retained))
            assertEquals(exported, treeHashes(retained))
            assertEquals(before, sourceHashes(source.root))
            RetainedSnapshot(leaseDirectory, File(retained, "manifest.json"), lease.manifestSha256, exported)
        }
        assertFalse("Closing the lease must remove only its original export", snapshot.leaseDirectory.exists())
        assertTrue("The retained explicit-TUN generation must reach prepared validation", preparedDirectories.drop(checksBeforeExport).contains(
            File(source.root, "runtime/resources/bundles/${source.subscriptionId}/${source.retainedTunGeneration}").canonicalPath))
        assertEquals(snapshot.hashes, treeHashes(retained))
        assertEquals(snapshot.digest, sha256(snapshot.manifest))
        assertEquals(before, sourceHashes(source.root))
        assertNull(source.settings.changes.value)
        val index = JSONObject(File(retained, "payload/profiles/index.json").readText())
        assertEquals(source.subscriptionId, index.getString("selected"))
        assertEquals(listOf(source.subscriptionId, source.localId), (0 until index.getJSONArray("profiles").length()).map {
            index.getJSONArray("profiles").getJSONObject(it).getString("id")
        })
        assertTrue(index.getJSONObject("fixtureUnknown").getBoolean("preserve"))
        val retainedProfile = index.getJSONArray("profiles").getJSONObject(0)
        assertEquals(source.retainedTunGeneration, retainedProfile.getString("previousBundle"))
        assertEquals(source.retainedTunGeneration, retainedProfile.getString("lastUsableBundle"))
        val library = source.overrides.snapshot()
        assertTrue(library.bundledScriptInitialized)
        assertEquals(listOf("Edited script"), library.scripts.map { it.name })
        assertEquals(script, library.scripts.single().content)
        assertEquals(before, sourceHashes(source.root))

        // Publish only after copy/readback and lease cleanup; never leave a descriptor to a deleted lease.
        val descriptor = File(output, "fixture.json")
        descriptor.writeText(JSONObject().put("manifest", snapshot.manifest.canonicalPath).put("sha256", snapshot.digest).toString())
        val readback = JSONObject(descriptor.readText())
        assertEquals(snapshot.manifest.canonicalPath, readback.getString("manifest"))
        assertEquals(sha256(snapshot.manifest), readback.getString("sha256"))
        println("Actual Kotlin migration export retained; lease closed; source unchanged")
    }
}
