package dev.aerove.lite.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowTransportProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualCandidateProviderPayloadIsCapturedUntilCloseThenSealedWithoutTransportFiles() = runBlocking<Unit> {
        val runtime = File(temporary.root, "runtime")
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("payload: [example.com]\n"))
            val schema = CompatibilityValidator.forMeow(NativeConfigValidator { error("premature conversion") }, runtime,
                NativePreparedConfigValidator { _, _ -> null }) { emptySet() }
            var opened = 0; var closed = 0
            var projected: File? = null
            val factory = ProviderDownloadFactory { context ->
                opened++
                val graph = ConfigYaml.parse(context.candidateYaml)
                val provider = (graph["proxy-providers"] as Map<*, *>)["P"] as Map<*, *>
                assertEquals("file", provider["type"])
                val file = File(provider["path"] as String); projected = file
                assertTrue(file.isFile); assertTrue(file.path.contains(".pending-"))
                assertTrue(file.toPath().startsWith(context.resourceReadRoot!!.toPath()))
                assertFalse(context.stagingRoot.toPath().startsWith(context.resourceReadRoot.toPath()))
                assertFalse(context.resourceReadRoot.toPath().startsWith(context.stagingRoot.toPath()))
                val node = (ConfigYaml.parse(file.readText())["proxies"] as List<*>).single() as Map<*, *>
                assertEquals("NODE", node["name"])
                val client = OkHttpClient()
                object : ProviderDownloadScope {
                    override suspend fun download(request: ProviderDownloadRequest): ProviderDownloadResult {
                        assertEquals("PICK", request.proxy)
                        val digest = client.newCall(Request.Builder().url(request.url).build()).execute().use { response ->
                            response.body!!.byteStream().use { copyResource(it, request.destination, request.maxBytes) }
                        }
                        return ProviderDownloadResult(digest.bytes, digest.sha256)
                    }
                    override suspend fun closeAndJoin() { assertTrue(file.isFile); closed++; client.connectionPool.evictAll() }
                }
            }
            val manager = ResourceBundleManager(runtime, schema, NativeResourceValidator { _, _, _, _ -> null },
                ResourceDownloader(), providerDownloads = factory)
            val id = UUID.randomUUID().toString()
            manager.importLocal(id, "provider.yaml", "proxies: [{name: NODE,type: http,server: '127.0.0.1',port: 8080}]\n".byteInputStream())
            val yaml = """
                proxy-providers: {P: {type: file,path: provider.yaml}}
                proxy-groups: [{name: PICK,type: select,use: [P]}]
                rule-providers: {R: {type: http,url: '${server.url("/rules")}',path: rules.yaml,behavior: domain,format: yaml,proxy: PICK}}
                rules: ['RULE-SET,R,DIRECT','MATCH,PICK']
            """.trimIndent()
            val bundle = manager.prepare(id, yaml)
            assertEquals(1, opened); assertEquals(1, closed); assertEquals(1, server.requestCount)
            assertFalse(projected!!.exists())
            assertFalse(bundle.directory.walkTopDown().any { it.name.startsWith(".transport-") })
            val provider = (ConfigYaml.parse(bundle.yaml)["proxy-providers"] as Map<*, *>)["P"] as Map<*, *>
            assertTrue(File(provider["path"] as String).isFile)
        } finally { server.shutdown() }
    }
}
