package dev.aerove.lite.fixtures

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.*
import dev.aerove.lite.flutter.MeowLegacyMigrationExport
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.regexp2.ExactFilterRuntime
import dev.aerove.lite.resources.ManagedRuleResource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Source-only interop input; real repositories/export, isolated JNI edges, no core start or network. */
fun main() = runBlocking<Unit> {
    val requested = requireNotNull(System.getProperty("nativeBuilder.fixture")) { "nativeBuilder.fixture is required" }
    require(requested.isNotBlank())
    val output = File(requested).canonicalFile
    val boundary = File(requireNotNull(System.getenv("RUNNER_TEMP"))).canonicalFile
    require(output != boundary && output.toPath().startsWith(boundary.toPath()) && !output.exists()) {
        "Builder fixture requires a new task-owned RUNNER_TEMP directory"
    }
    require(!System.getenv("AEROVE_FILTER_HELPER").isNullOrBlank()) { "Real host filter helper required" }
    check(output.mkdirs())
    val descriptor = File(output, "fixture.json")
    try {
        ExactFilterRuntime.install(RealGoFilterExecutor())
        val root = File(output, "native-source").apply { check(mkdir()) }.canonicalFile
        val runtime = File(root, "runtime")
        val validator = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, _ -> null }) { setOf("ss") }
        val manager = ResourceBundleManager(runtime, validator, NativeResourceValidator { _, _, _, _ -> null })
        val settings = LiteSettingsStore(root)
        settings.save(LiteSettings().copy(acceptedDisclaimerVersion = 1))
        val overrides = OverrideRepository(root)
        overrides.putScript(ScriptResource(BundledMeowScript.ID, "Fixture seed marker",
            content = "function main(c) { return c; }", updatedAt = 1))
        overrides.deleteScript(BundledMeowScript.ID)

        val localId = UUID.randomUUID().toString()
        val ruleText = "+.example.invalid\r\n"
        val raw = ruleText.toByteArray(Charsets.UTF_8)
        val rule = BuilderEntry("local", mapOf("type" to "file", "path" to ".library/$localId",
            "behavior" to "domain", "format" to "text", "interval" to 3600), localId)
        // Restore is the existing source-cache API; its real line validator computes and checks count/hash.
        val stored = manager.importLibraryRule(ManagedRuleResource("local", "", "domain", "text",
            raw.size.toLong(), sha256(ruleText), 1700000000123, 1, localId = localId), raw.inputStream())
        check(stored.count == 1 && stored.sha256 == sha256(ruleText) && stored.bytes == raw.size.toLong())
        val icons = BuilderIconSet("Fixture icons", "https://example.invalid/icons",
            listOf(BuilderIcon("Fixture", "https://example.invalid/icon.png")), 1700000000789, 32)
        val filter = BuilderFilter("Fixture local filter", "^Local SS$")
        // Persist/validate the real filter resource; this consumer has no Core matcher, so do not bind it.
        val library = BuilderResourceLibrary(root)
        library.replace(library.snapshot(), BuilderResources(ruleProviders = listOf(rule),
            filters = listOf(filter), iconSets = listOf(icons)))
        val persisted = BuilderResourceLibrary(root).snapshot().resources
        check(persisted.ruleProviders.single() == rule)
        check(persisted.filters.single() == filter && persisted.iconSets.single() == icons)

        val source = ProfileRepository(root, ProfileValidator { })
        suspend fun create(name: String, server: String): String {
            val document = ProfileBuilderDocument.parse("""
custom-fact: {preserve: true}
proxies:
  - {name: Local SS, type: ss, server: $server, port: 443, cipher: aes-128-gcm, password: fixture-only}
proxy-groups:
  - {name: Pick, type: select, proxies: [Local SS, DIRECT]}
rule-providers:
  local: {type: file, path: .library/$localId, behavior: domain, format: text, interval: 3600}
rules:
  - RULE-SET,local,Pick
  - MATCH,DIRECT
""".trimIndent()).withBindings(BuilderResourceBindings(
                ruleProviders = mapOf("local" to localId),
                icons = mapOf("Pick" to BuilderIconBinding(icons.id, icons.icons.single().url))))
            check(document.issues().isEmpty())
            val session = ProfileBuilderSession.create(source, name)
            try {
                session.edit { document }
                check(session.save()) { "Production Builder save failed: ${session.state.value.error}" }
                return checkNotNull(session.savedProfileId)
            } finally { check(session.cancel()) { "Builder session did not close" } }
        }
        val first = create("Fixture custom", "127.0.0.2")
        val second = create("Fixture custom other", "127.0.0.3")
        source.select(first)
        check(source.snapshot().profiles.map { it.name } == listOf("Fixture custom", "Fixture custom other"))
        check(source.snapshot().selectedId == first)
        val profiles = ProfileRepository(root, ProfileValidator { }, resources = manager)
        val exportSettings = LiteSettingsStore(root)
        val exportOverrides = OverrideRepository(root) { error("Export must not reseed a deleted bundled script") }
        fun hashes(directory: File): Map<String, String> = directory.walkTopDown().onEnter {
            check(!Files.isSymbolicLink(it.toPath())); true
        }.filter { it.isFile && !it.relativeTo(directory).invariantSeparatorsPath.startsWith("native-lite-migration-exports/") }
            .associate { file ->
                check(!Files.isSymbolicLink(file.toPath()))
                file.relativeTo(directory).invariantSeparatorsPath to sha256(file)
            }
        val before = hashes(root)
        val retained = File(output, "retained-export")
        var leaseDirectory: File? = null
        var manifestDigest: String? = null
        var exportedHashes: Map<String, String>? = null
        MeowLegacyMigrationExport(root, profiles, exportSettings, exportOverrides).export().use { lease ->
            val manifest = JSONObject(lease.manifest.readText())
            check(manifest.getString("format") == "aerove-lite-native-source-export" && manifest.getInt("schema") == 1)
            check(!manifest.getBoolean("migrationComplete") && lease.manifestSha256 == sha256(lease.manifest))
            val rows = manifest.getJSONArray("files")
            val paths = (0 until rows.length()).map { rows.getJSONObject(it).getString("path") }
            check(paths.toSet().size == paths.size)
            val expected = before.filterKeys { path -> File(path).name !in setOf(".lock", ".domain.lock") }
            check(paths.toSet() == expected.keys) { "Builder export inventory differs from the actual source" }
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index); val path = row.getString("path")
                val payload = File(lease.manifest.parentFile, "payload/$path")
                check(row.getLong("bytes") == payload.length() && row.getString("sha256") == before[path])
                check(sha256(payload) == before[path] && payload.readBytes().contentEquals(File(root, path).readBytes()))
            }
            leaseDirectory = lease.manifest.parentFile
            exportedHashes = hashes(lease.manifest.parentFile)
            check(!retained.exists() && lease.manifest.parentFile.copyRecursively(retained))
            check(hashes(retained) == exportedHashes && hashes(root) == before)
            manifestDigest = lease.manifestSha256
        }
        check(!checkNotNull(leaseDirectory).exists()) { "Export lease did not close" }
        val manifest = File(retained, "manifest.json")
        check(manifest.isFile && sha256(manifest) == manifestDigest && hashes(retained) == exportedHashes)
        check(hashes(root) == before) { "Native Builder source changed during export/copy/lease close" }
        val snapshot = JSONObject(File(retained, "payload/profiles/index.json").readText())
        val rows = snapshot.getJSONArray("profiles")
        check(rows.length() == 2 && snapshot.getString("selected") == first)
        check(setOf(first, second) == (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet())
        val receipt = JSONObject().put("producer", "NativeBuilderInteropFixture")
            .put("source", requireNotNull(System.getenv("GITHUB_SHA")))
            .put("run", requireNotNull(System.getenv("GITHUB_RUN_ID")))
            .put("attempt", requireNotNull(System.getenv("GITHUB_RUN_ATTEMPT")))
            .put("sourceBefore", JSONObject(before)).put("sourceAfter", JSONObject(hashes(root)))
            .put("retainedFiles", JSONObject(checkNotNull(exportedHashes)))
            .put("sourceUnchanged", true).put("exportLeaseClosed", true)
            .put("appApkBuilt", false).put("nativeCoreExecuted", false).put("acceptance", false)
        File(output, "producer-receipt.json").writeText(receipt.toString(2) + "\n")
        val pending = File(output, "fixture.json.pending")
        val value = JSONObject().put("manifest", manifest.canonicalPath).put("sha256", checkNotNull(manifestDigest))
        FileOutputStream(pending).use { stream -> stream.write(value.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        check(JSONObject(pending.readText()).getString("manifest") == manifest.canonicalPath)
        check(!descriptor.exists() && pending.renameTo(descriptor))
        val readback = JSONObject(descriptor.readText())
        check(File(readback.getString("manifest")).isFile && sha256(File(readback.getString("manifest"))) == readback.getString("sha256"))
        println("Actual Native Builder source exported, copied and hashed; lease closed before descriptor publication")
    } catch (failure: Throwable) {
        if (descriptor.exists()) check(descriptor.delete()) { "Could not withdraw failed Builder fixture descriptor" }
        throw failure
    }
}
