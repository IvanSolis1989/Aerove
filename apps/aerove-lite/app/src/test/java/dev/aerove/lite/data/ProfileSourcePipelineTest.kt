package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.dnssettings.*
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileSourcePipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val simple = "rules: ['MATCH,DIRECT']"
    private data class Data(val profiles: ProfileRepository, val resources: ResourceBundleManager,
        val overrides: OverrideRepository, val builders: BuilderResourceLibrary)
    private fun data(root: File, settings: () -> LiteSettings, engine: ScriptEngine): Data {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val resources = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null })
        val overrides = OverrideRepository(root)
        val builders = BuilderResourceLibrary(root)
        val pipeline = ProfileSourcePipeline(builders, OverridePipeline(overrides, engine), { settings() })
        return Data(ProfileRepository(root, validator, resources = resources, sourcePolicy = pipeline), resources, overrides, builders)
    }

    @Test fun realMetadataPolicyReadDoesNotCollectItsUncommittedCandidate() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null })
        val overrides = OverrideRepository(root)
        lateinit var profiles: ProfileRepository
        val dns = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("9.9.9.9")))
        val pipeline = ProfileSourcePipeline(BuilderResourceLibrary(root), OverridePipeline(overrides, ScriptEngine { _, input -> input }),
            { LiteSettings() }, { id -> ProfileDnsSelection(dns, profiles.profileSummary(id)?.custom == true) })
        profiles = ProfileRepository(root, validator, resources = manager, sourcePolicy = pipeline)
        val profile = profiles.importText("Custom", simple, null, custom = true)
        val prepared = kotlinx.coroutines.withTimeout(5_000) { profiles.prepareForStart(profile.id) }
        assertTrue(checkNotNull(prepared.resourceDirectory).isDirectory)
        assertEquals(prepared.yaml, profiles.prepare(profile.id).yaml)
        assertTrue(profiles.profileSummary(profile.id)!!.custom)
    }

    @Test fun materializedProviderNamesValidateDnsRoutesBeforePublishingProfile() = runBlocking<Unit> {
        val server = MockWebServer()
        server.start()
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("proxies: [{name: dynamic-node, type: socks5, server: example.com, port: 1080}]")) }
            val root = temporary.newFolder()
            val data = data(root, { LiteSettings() }, ScriptEngine { _, input -> input })
            val source = """
                proxy-providers:
                  remote: {type: http, url: '${server.url("/nodes")}', path: nodes.yaml}
                proxy-groups: [{name: Auto, type: select, use: [remote]}]
                dns: {enable: true, nameserver: ['https://dns.example/dns-query#proxy=dynamic-node']}
                rules: ['MATCH,Auto']
            """.trimIndent()
            val valid = data.profiles.importText("Valid", source)
            assertTrue(data.profiles.prepareForStart(valid.id).yaml.contains("#proxy=dynamic-node"))
            val failed = runCatching { data.profiles.importText("Unknown", source.replace("#proxy=dynamic-node", "#proxy=missing-node")) }
            assertTrue(failed.isFailure)
            assertTrue(failed.exceptionOrNull()!!.message!!.contains("dns.nameserver[0]"))
            assertEquals(listOf(valid.id), data.profiles.list().map { it.id })
        } finally { server.shutdown() }
    }

    @Test fun customProfileSkipsNormalScriptsAndUsesItsOwnDnsSelection() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val repository = OverrideRepository(root)
        repository.putScript(ScriptResource("script", "Should not run", content = "throw"))
        val own = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("9.9.9.9")))
        repository.setProfile(ProfileOverride("custom", OverrideMode.Script, "script", dnsPreferences = own))
        val global = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("8.8.8.8")))
        var custom = true
        var executions = 0
        val pipeline = ProfileSourcePipeline(BuilderResourceLibrary(root), OverridePipeline(repository, ScriptEngine { _, input ->
            executions++; input
        }), { LiteSettings(dnsPreferences = global) }, { id ->
            ProfileDnsSelection(repository.snapshot().profiles.first { it.profileId == id }.dnsPreferences, custom)
        })
        val transformed = pipeline.forStart("custom", simple)
        assertEquals(0, executions)
        assertEquals(listOf("9.9.9.9"), (ConfigYaml.parse(transformed.compiledYaml)["dns"] as Map<*, *>)["nameserver"])
        assertTrue(transformed.isCurrent())
        custom = false
        assertFalse(transformed.isCurrent())
        val normal = pipeline.forStart("custom", simple)
        assertEquals(1, executions)
        assertEquals(listOf("8.8.8.8"), (ConfigYaml.parse(normal.compiledYaml)["dns"] as Map<*, *>)["nameserver"])
    }

    @Test fun onlyActualStartRunsScriptAfterBuilderBeforeDnsAndKeepsOriginalRevision() = runBlocking<Unit> {
        val root = temporary.newFolder()
        var executions = 0
        val dns = DnsPreferences().withValue(DnsField.Nameserver, DnsValue.Entries(listOf("8.8.8.8")))
            .copy(hosts = mapOf("ads.example" to listOf("127.0.0.1")))
        val data = data(root, { LiteSettings(dnsPreferences = dns) }, ScriptEngine { _, input ->
            executions++
            val config = JSONObject(input)
            assertEquals("latest.example", config.getJSONArray("proxies").getJSONObject(0).getString("server"))
            config.put("dns", JSONObject().put("enable", true).put("nameserver", listOf("9.9.9.9"))).toString()
        })
        val originalNode = BuilderEntry("global", mapOf("type" to "socks5", "server" to "old.example", "port" to 1080))
        val library = BuilderResources(proxies = listOf(originalNode))
        data.builders.replace(data.builders.snapshot(), library)
        val originalSource = library.bindProxy(requireNotNull(library.proxies.single().id), ProfileBuilderDocument.parse(simple)).yaml()
        val profile = data.profiles.importText("Plan", originalSource)
        val before = data.profiles.documentSnapshot(profile.id)
        data.overrides.putScript(ScriptResource("script", "Script", content = "selected"))
        data.overrides.setProfile(ProfileOverride(profile.id, OverrideMode.Script, "script"))
        data.builders.replace(data.builders.snapshot(), library.copy(proxies = listOf(library.proxies.single().copy(fields = originalNode.fields + ("server" to "latest.example")))))
        data.profiles.prepare(profile.id)
        assertEquals(0, executions)
        val prepared = data.profiles.prepareForStart(profile.id)
        assertEquals(1, executions)
        assertEquals(before.revision, prepared.revision)
        assertEquals(originalSource, data.profiles.readConfig(profile.id))
        val config = ConfigYaml.parse(prepared.yaml)
        assertEquals(listOf("8.8.8.8"), (config["dns"] as Map<*, *>)["nameserver"])
        assertEquals("127.0.0.1", (config["hosts"] as Map<*, *>)["ads.example"])
        val manifest = JSONObject(File(prepared.resourceDirectory, "manifest.json").readText())
        assertEquals(sha256(originalSource), manifest.getString("sourceSha256"))
        assertEquals(sha256(File(prepared.resourceDirectory, "input.yaml").readText()), manifest.getString("compiledInputSha256"))
        assertNotEquals(manifest.getString("sourceSha256"), manifest.getString("compiledInputSha256"))
        assertEquals(sha256(prepared.yaml), manifest.getString("configSha256"))
        val lease = data.profiles.retain(prepared)
        data.profiles.release(lease)
        assertEquals(1, executions)
        assertEquals(prepared.generation, data.profiles.prepareForStart(profile.id).generation)
    }

    @Test fun policyChangedDuringScriptCannotPublishItsStaleCompiledGeneration() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val root = temporary.newFolder()
        val data = data(root, { LiteSettings() }, ScriptEngine { _, input -> started.complete(Unit); release.await(); input })
        val profile = data.profiles.importText("Plan", simple)
        val before = data.profiles.prepare(profile.id)
        data.overrides.putScript(ScriptResource("script", "Script", content = "old"))
        data.overrides.setProfile(ProfileOverride(profile.id, OverrideMode.Script, "script"))
        val pending = async { runCatching { data.profiles.prepareForStart(profile.id) } }
        started.await()
        data.overrides.putScript(ScriptResource("script", "Script", content = "new"))
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertEquals(before.generation, data.profiles.prepare(profile.id).generation)
        assertEquals(before.revision, data.profiles.documentSnapshot(profile.id).revision)
    }

    @Test fun sharedCacheUpdateChangesPolicyWithoutRewritingSourceOrInvalidatingLeasedGeneration() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("payload: ['+.old.example']"))
        server.enqueue(MockResponse().setBody("payload: ['+.new.example']"))
        server.start()
        val url = server.url("/rules.yaml").toString()
        val root = temporary.newFolder()
        val data = data(root, { LiteSettings() }, ScriptEngine { _, _ -> error("No script selected") })
        data.resources.syncLibraryRule("Rule", url, "domain", "yaml")
        val source = "rule-providers: {Alias: {type: http, url: '$url', path: rules/alias.yaml, behavior: domain, format: yaml}}\nrules: ['RULE-SET,Alias,DIRECT']"
        val profile = data.profiles.importText("Plan", source)
        val first = data.profiles.prepareForStart(profile.id)
        val lease = data.profiles.retain(first)
        data.resources.syncLibraryRule("Renamed label", url, "domain", "yaml")
        server.shutdown()
        val second = data.profiles.prepareForStart(profile.id)
        assertEquals(first.revision, second.revision)
        assertEquals(source, data.profiles.readConfig(profile.id))
        assertNotEquals(first.generation, second.generation)
        val before = JSONObject(File(first.resourceDirectory, "manifest.json").readText())
        val after = JSONObject(File(second.resourceDirectory, "manifest.json").readText())
        assertNotEquals(before.getString("policyFingerprint"), after.getString("policyFingerprint"))
        assertEquals(before.getString("compiledInputSha256"), after.getString("compiledInputSha256"))
        data.profiles.collectGarbage()
        assertTrue(checkNotNull(first.resourceDirectory).isDirectory)
        assertEquals(first.yaml, data.resources.load(profile.id, checkNotNull(first.generation), source).yaml)
        data.profiles.release(lease)
        data.profiles.collectGarbage()
        assertFalse(first.resourceDirectory.isDirectory)
    }

    @Test fun compiledInputTamperingCannotPassOriginalOrLeaseIdentityChecks() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val data = data(root, { LiteSettings() }, ScriptEngine { _, input -> input })
        val profile = data.profiles.importText("Plan", simple)
        val prepared = data.profiles.prepareForStart(profile.id)
        File(prepared.resourceDirectory, "input.yaml").appendText("\n# changed")
        assertTrue(runCatching { data.profiles.retain(prepared) }.isFailure)
        assertTrue(runCatching { data.resources.load(profile.id, checkNotNull(prepared.generation), simple + "\n# other original") }.isFailure)
    }
}
