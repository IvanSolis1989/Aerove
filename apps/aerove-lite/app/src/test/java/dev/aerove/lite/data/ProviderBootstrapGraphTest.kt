package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Candidate graph/file handoff tests. Native route and HTTP execution are not simulated as acceptance. */
class ProviderBootstrapGraphTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun input(flag: String = "include-all-proxies: true") = """
        proxies:
          - {name: NODE_A, type: http, server: 127.0.0.1, port: 1111}
          - {name: NODE_B, type: http, server: 127.0.0.1, port: 2222}
        proxy-groups: [{name: Route, type: select, proxies: [DIRECT], $flag}]
        proxy-providers: {remote: {type: http, url: 'https://example.invalid/provider', path: hint.yaml, proxy: Route}}
        rules: ['MATCH,DIRECT']
    """.trimIndent()

    @Test fun implicitTopLevelMembersAndHistoricalChoiceSurviveActualProviderCapture() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val schema = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, _ -> null }) { emptySet() }
        var captured = 0
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(),
            providerDownloads = ProviderDownloadFactory { context ->
                captured++
                val graph = ConfigYaml.parse(context.candidateYaml)
                val nodes = (graph["proxies"] as List<*>).map { (it as Map<*, *>)["name"] }
                assertEquals(listOf("NODE_A", "NODE_B"), nodes)
                assertEquals(mapOf("Route" to "NODE_B"), context.selectionCache)
                val route = (graph["proxy-groups"] as List<*>).single() as Map<*, *>
                assertEquals(true, route["include-all-proxies"]); assertEquals(listOf("DIRECT"), route["proxies"])
                assertTrue((graph["proxy-providers"] as Map<*, *>).isEmpty())
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        assertEquals("Route", request.proxy)
                        request.destination.writeText("proxies: [{name: remote-node, type: http, server: 127.0.0.1, port: 3333}]\n")
                        return ProviderDownloadResult(request.destination.length(), sha256(request.destination))
                    }
                    override suspend fun closeAndJoin() = Unit
                }
            })
        val lease = FlutterResourceSealer(manager, "AEROVE/review83").prepare(input(), true, mapOf("Route" to "NODE_B"))
        try { assertEquals(1, captured); assertTrue(lease.yaml.contains("include-all-proxies: true")) }
        finally { lease.closeAndJoin() }
    }

    @Test fun includeAllProviderAliasesExposeRealSelfDependencyWithoutChangingFlags() {
        for (flag in listOf("include-all: true", "include-all-providers: true")) {
            val raw = ConfigYaml.parse(input(flag))
            val error = assertThrows(ConfigValidationException::class.java) {
                ProviderBootstrapGraph.orderedProviders(raw, raw["proxy-providers"] as Map<*, *>)
            }
            assertEquals("$.proxy-providers.remote.proxy", error.issues.single().path)
            assertTrue(error.issues.single().reason.contains("cycle"))
            assertThrows(ConfigValidationException::class.java) { ProviderBootstrapGraph.capture(raw, "Route") }
            assertTrue(ConfigYaml.dump(raw).contains(flag))
        }
    }

    @Test fun implicitNodesRetainTheirDialerGroupsAndFileProviderDependencies() {
        val raw = ConfigYaml.parse(input().replace("port: 2222}", "port: 2222, dialer-proxy: Chain}")
            .replace("proxy-groups: [{name: Route, type: select, proxies: [DIRECT], include-all-proxies: true}]",
                "proxy-groups: [{name: Route, type: select, proxies: [DIRECT], include-all-proxies: true, filter: kept}, {name: Chain, type: select, use: [ready]}]")
            .replace("proxy-providers: {remote:", "proxy-providers: {ready: {type: file, path: ready.yaml}, remote:"))
        val graph = ProviderBootstrapGraph.capture(raw, "Route")
        assertEquals(listOf("NODE_A", "NODE_B"), (graph["proxies"] as List<*>).map { (it as Map<*, *>)["name"] })
        assertEquals(setOf("Route", "Chain"), (graph["proxy-groups"] as List<*>).map { (it as Map<*, *>)["name"] }.toSet())
        assertEquals(setOf("ready"), (graph["proxy-providers"] as Map<*, *>).keys)
        assertEquals("kept", ((graph["proxy-groups"] as List<*>).first() as Map<*, *>)["filter"])
        assertEquals("Chain", ((graph["proxies"] as List<*>)[1] as Map<*, *>)["dialer-proxy"])
    }

    @Test fun falseIncludeFlagDoesNotInventMembersOrProviderDependencies() {
        val raw = ConfigYaml.parse(input("include-all-proxies: false"))
        val graph = ProviderBootstrapGraph.capture(raw, "Route")
        assertTrue((graph["proxies"] as List<*>).isEmpty())
        assertTrue((graph["proxy-providers"] as Map<*, *>).isEmpty())
        assertEquals(false, ((graph["proxy-groups"] as List<*>).single() as Map<*, *>)["include-all-proxies"])
        assertEquals(listOf("remote"), ProviderBootstrapGraph.orderedProviders(raw, raw["proxy-providers"] as Map<*, *>).map { it.key })
    }

    @Test fun mixedSealedAndHttpResourcesComposeUnderOldReaderLeaseAndKeepOriginalInput() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val schema = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, _ -> null }) { emptySet() }
        var downloads = 0; var onOpen: suspend () -> Unit = {}
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null }, ResourceDownloader(),
            providerDownloads = ProviderDownloadFactory {
                onOpen()
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        downloads++; request.destination.writeText("payload: [example.com]\n")
                        return ProviderDownloadResult(request.destination.length(), sha256(request.destination))
                    }
                    override suspend fun closeAndJoin() = Unit
                }
            })
        val sealer = FlutterResourceSealer(manager, "AEROVE/review83")
        val old = sealer.prepare("rule-providers: {old: {type: http, url: 'https://example.invalid/old', path: old.yaml, behavior: domain, proxy: DIRECT}}\nrules: ['MATCH,DIRECT']\n", true)
        val oldManifest = File(old.directory, "manifest.json").readBytes()
        val raw = ConfigYaml.parse(old.yaml)
        @Suppress("UNCHECKED_CAST") val providers = raw["rule-providers"] as MutableMap<String, Any?>
        providers["new"] = mapOf("type" to "http", "url" to "https://example.invalid/new", "path" to "new.yaml", "behavior" to "domain", "proxy" to "DIRECT")
        val input = ConfigYaml.dump(raw)
        onOpen = {
            old.closeAndJoin(); manager.prune(emptyMap(), emptySet())
            assertTrue("old generation must remain leased through candidate handoff", old.directory.isDirectory)
        }
        val combined = sealer.prepare(input, true)
        try {
            assertEquals(2, downloads)
            assertEquals(input, File(combined.directory, "input.yaml").readText())
            val manifest = org.json.JSONObject(File(combined.directory, "manifest.json").readText())
            assertEquals(sha256(input), manifest.getString("compiledInputSha256"))
            val origin = manifest.getJSONArray("sealedInputs").getJSONObject(0)
            assertEquals(sha256(oldManifest.toString(Charsets.UTF_8)), origin.getString("manifestSha256"))
            val files = origin.getJSONArray("files")
            val copiedManifest = (0 until files.length()).map { files.getJSONObject(it) }.single { it.getString("sourceFile") == "manifest.json" }
            assertArrayEquals(oldManifest, File(combined.directory, copiedManifest.getString("file")).readBytes())
            val projected = ConfigYaml.parse(combined.yaml)["rule-providers"] as Map<*, *>
            projected.values.forEach { row ->
                val path = (row as Map<*, *>)["path"] as String
                assertTrue(File(path).toPath().startsWith(combined.directory.toPath()))
                assertEquals("payload: [example.com]\n", File(path).readText())
            }
            manager.prune(emptyMap(), emptySet()); assertTrue(old.directory.isDirectory)
            assertTrue(combined.resources.any { it.relativePath.startsWith("lineage/") })
        } finally { combined.closeAndJoin(); old.closeAndJoin() }
        assertFalse(old.directory.exists())
        assertFalse(File(temporary.root, "profiles").exists()); assertFalse(File(temporary.root, "settings").exists())
    }
}
