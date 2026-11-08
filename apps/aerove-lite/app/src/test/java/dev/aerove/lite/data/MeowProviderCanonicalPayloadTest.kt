package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowProviderCanonicalPayloadTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun mportOnlyProviderSealsCanonicalPortsAndPreservesOriginal() = runBlocking<Unit> {
        val original = "proxies: [{name: synthetic, type: hysteria2, server: example.invalid, password: synthetic, mport: 4443-4445}]\n"
        val runtime = File(temporary.root, "runtime")
        val schema = CompatibilityValidator.forMeow(NativeConfigValidator { null }, runtime,
            NativePreparedConfigValidator { _, _ -> null }) { setOf("hysteria2") }
        val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null },
            ResourceDownloader(), providerDownloads = ProviderDownloadFactory { object : ProviderDownloadScope {
                override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                    val result = original.byteInputStream().use { copyResource(it, request.destination, request.maxBytes) }
                    return ProviderDownloadResult(result.bytes, result.sha256)
                }
                override suspend fun closeAndJoin() = Unit
            } })
        val source = "proxy-providers: {feed: {type: http,url: 'https://example.invalid/feed',path: feed.yaml}}\n" +
            "proxy-groups: [{name: PICK,type: select,use: [feed]}]\nrules: ['MATCH,PICK']\n"
        val bundle = manager.prepare(java.util.UUID.randomUUID().toString(), source,
            requestPolicy = ProviderRequestPolicy("AEROVE", true))
        val rows = org.json.JSONObject(File(bundle.directory, "manifest.json").readText()).getJSONArray("records")
        val records = (0 until rows.length()).map(rows::getJSONObject)
        val raw = records.single { it.getString("kind") == "source-proxy" }
        val prepared = records.single { it.getString("key") == "$.proxy-providers.feed.prepared" }
        assertEquals(original, File(bundle.directory, raw.getString("file")).readText())
        val canonical = File(bundle.directory, prepared.getString("file")).readText()
        val node = (ConfigYaml.parse(canonical)["proxies"] as List<*>).single() as Map<*, *>
        assertEquals("4443-4445", node["ports"])
        assertFalse(node.containsKey("mport"))
        val report = prepared.getJSONObject("compatibility")
        assertEquals(sha256(original), report.getString("sourceSha256"))
        assertEquals(sha256(canonical), report.getString("derivedSha256"))
    }
}
