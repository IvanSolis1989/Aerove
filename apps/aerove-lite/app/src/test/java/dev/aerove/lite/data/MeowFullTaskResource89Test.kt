package dev.aerove.lite.data
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowFullTaskResource89Test {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture(): Pair<File,String> {
        val source=File(System.getProperty("resource.fixture"))
        val root=temporary.newFolder()
        source.copyRecursively(root,overwrite=true)
        val yaml=File(root,"ordinary-compiled.yaml").readText().replace(source.path,root.path).replace(source.path.replace('\\','/'),root.path.replace('\\','/'))
        return root to yaml
    }
    private fun manager(root: File, downloaded: ()->Unit = {}): ResourceBundleManager {
        val runtime=File(root,"runtime")
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {error("must use sealed prepared validation")},runtime,NativePreparedConfigValidator { _,_ ->null }) {setOf("ss")}
        return ResourceBundleManager(runtime,schema,NativeResourceValidator { _,_,_,_ ->null },ResourceDownloader(),providerDownloads=ProviderDownloadFactory {
            object:ProviderDownloadScope {
                override suspend fun download(request:ProviderDownloadRequest):ProviderDownloadResult {
                    downloaded();val content=if(request.url.endsWith("proxy")) "proxies: [{name: Fresh, type: direct}]\n" else "+.fresh.invalid\n"
                    request.destination.writeText(content)
                    return ProviderDownloadResult(request.destination.length(),sha256(request.destination))
                }
                override suspend fun closeAndJoin() { }
            }
        })
    }
    @Test fun actualTaskOutputConsumesPrivateSourceCachesThroughExistingSealer()=runBlocking<Unit> {
        val (root,source)=fixture();var downloads=0
        val resources=manager(root) {downloads++}
        val reconciliation=MeowTunReconciliation.reconcile(source,dev.aerove.lite.LiteSettings())
        assertEquals((ConfigYaml.parse(source)["tun"] as Map<*,*>)["mtu"],reconciliation.settings.network.mtu)
        val lease=FlutterResourceSealer(resources,"AEROVE-Lite fixture").prepare(source,true,tunReconciliation=reconciliation)
        try {
            assertEquals(0,downloads)
            val output=ConfigYaml.parse(lease.yaml)
            assertEquals(ConfigYaml.parse(source)["tun"],output["tun"])
            for(namespace in listOf("proxy-providers","rule-providers")) for(raw in (output[namespace] as Map<*,*>).values) {
                val row=raw as Map<*,*>;assertEquals("file",row["type"])
                assertTrue(File(row["path"] as String).isFile)
                assertTrue((row["path"] as String).startsWith(lease.directory.path))
            }
            assertArrayEquals(byteArrayOf(0,4,8,16,32),File(output["mmdb"] as String).readBytes())
            assertArrayEquals(byteArrayOf(1,3,9,27),File(output["geosite"] as String).readBytes())
            val manifest=JSONObject(File(lease.directory,"manifest.json").readText())
            val records=manifest.getJSONArray("records")
            val remote=(0 until records.length()).map(records::getJSONObject).single {it.getString("key")=="$.proxy-providers.remote"}
            assertEquals("verified-generation",remote.getString("transportOrigin"))
        } finally {lease.closeAndJoin()}
        assertFalse(lease.directory.exists())
    }
}
