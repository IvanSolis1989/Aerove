package dev.aerove.lite.data
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowSharedMigrationResource89Test {
    @get:Rule val temporary=TemporaryFolder()
    private fun fixture(): Pair<File,String> {
        val source=File(System.getProperty("resource.fixture"))
        val root=temporary.newFolder()
        source.copyRecursively(root,overwrite=true)
        val yaml=File(root,"ordinary-compiled.yaml").readText().replace(source.path,root.path).replace(source.path.replace('\\','/'),root.path.replace('\\','/'))
        return root to yaml
    }
    private fun manager(root: File, clock: ()->Long = System::currentTimeMillis, downloaded: ()->Unit = {}): ResourceBundleManager {
        val runtime=File(root,"runtime")
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {error("must use sealed prepared validation")},runtime,NativePreparedConfigValidator { _,_ ->null }) {setOf("ss")}
        return ResourceBundleManager(runtime,schema,NativeResourceValidator { _,_,_,_ ->null },ResourceDownloader(),clock=clock,providerDownloads=ProviderDownloadFactory {
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
    private fun resourceBoundary(full: String): String = ConfigYaml.dump(ConfigYaml.parse(full).filterKeys {
        it in setOf("proxies","proxy-groups","proxy-providers","rule-providers","rules","mmdb","asn-mmdb","geosite","mmdb-download-url","asn-mmdb-download-url","geosite-download-url","global-ua","aerove-native-resource-manifest")
    })
    @Test fun receiptPredicatesBindUrlHeadersProxyAgeAndRawSha()=runBlocking<Unit> {
        val (root,full)=fixture();val resource=manager(root)
        val input=resource.retainSealedInputs(ConfigYaml.parse(resourceBoundary(full)))
        try {
            val key="$.proxy-providers.remote";val item=input.shared.single {it.key==key};val data=item.metadata!!
            val url=data.getJSONObject("sourceDefinition").getString("url")
            val fetch=ProviderFetchPolicy(null,mapOf("X-Fixture" to listOf("fixture-only")))
            val at=data.getLong("updatedAt")
            assertNotNull(input.cached(key,url,"","yaml",fetch,null,at+1))
            assertNull(input.cached(key,"https://example.invalid/changed","","yaml",fetch,null,at+1))
            assertNull(input.cached(key,url,"","yaml",fetch.copy(headers=mapOf("X-Fixture" to listOf("changed"))),null,at+1))
            assertNull(input.cached(key,url,"","yaml",fetch.copy(proxy="DIRECT"),null,at+1))
            assertNull(input.cached(key,url,"","yaml",fetch,null,at+86400001))
            val file=File(item.file.sourcePath);val before=file.readBytes();file.appendText("changed")
            assertTrue(runCatching {input.cached(key,url,"","yaml",fetch,null,at+1)}.isFailure)
            file.writeBytes(before);input.verify()
        } finally {input.close()}
    }
    @Test fun sharedPathOutsidePrivateRootAndCorruptReceiptNeverGrantInput()=runBlocking<Unit> {
        val (root,full)=fixture();val source=ConfigYaml.parse(resourceBoundary(full));val resource=manager(root)
        val outside=temporary.newFile().apply {writeText("fixture")}
        @Suppress("UNCHECKED_CAST") val local=(source["proxy-providers"] as Map<String,MutableMap<String,Any?>>).getValue("local")
        local["path"]=outside.path
        assertTrue(runCatching {FlutterResourceSealer(resource,"fixture").prepare(ConfigYaml.dump(source),true)}.isFailure)
        val original=resource.retainSealedInputs(ConfigYaml.parse(resourceBoundary(full)))
        val receipt=File(original.shared.single {it.key=="$.proxy-providers.remote"}.receiptFile!!.sourcePath)
        original.close();receipt.writeText("{}")
        assertTrue(runCatching {resource.retainSealedInputs(ConfigYaml.parse(resourceBoundary(full)))}.isFailure)
    }
    @Test fun expiredReceiptsUseExistingCapturedTransportInsteadOfRejuvenatingOldCache()=runBlocking<Unit> {
        val (root,full)=fixture();val source=resourceBoundary(full)
        val inspector=manager(root);val captured=inspector.retainSealedInputs(ConfigYaml.parse(source))
        val old=captured.shared.single {it.key=="$.proxy-providers.remote"}.metadata!!.getLong("updatedAt");captured.close()
        var downloads=0;val now=old+172800001
        val resource=manager(root,clock={now}) {downloads++}
        val lease=FlutterResourceSealer(resource,"fixture").prepare(source,true)
        try {
            assertTrue(downloads>=2)
            val records=JSONObject(File(lease.directory,"manifest.json").readText()).getJSONArray("records")
            val remote=(0 until records.length()).map(records::getJSONObject).single {it.getString("key")=="$.proxy-providers.remote"}
            assertEquals("captured-transport",remote.getString("transportOrigin"));assertEquals(now,remote.getLong("updatedAt"))
        } finally {lease.closeAndJoin()}
    }
    @Test fun cancellationJoinsCapturedWriterAndCleansCandidateWithoutChangingSharedInputs()=runBlocking<Unit> {
        val (root,full)=fixture();val source=ConfigYaml.parse(resourceBoundary(full))
        @Suppress("UNCHECKED_CAST") val provider=(source["proxy-providers"] as Map<String,MutableMap<String,Any?>>).getValue("remote")
        provider["url"]="https://example.invalid/changed"
        val entered=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>();var closed=0
        val runtime=File(root,"runtime")
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {null},runtime,NativePreparedConfigValidator {_,_->null}) {setOf("ss")}
        val resource=ResourceBundleManager(runtime,schema,NativeResourceValidator {_,_,_,_->null},ResourceDownloader(),providerDownloads=ProviderDownloadFactory {
            object:ProviderDownloadScope {
                override suspend fun download(request:ProviderDownloadRequest):ProviderDownloadResult {
                    entered.complete(Unit);try {awaitCancellation()} finally {stopped.complete(Unit)}
                }
                override suspend fun closeAndJoin() {closed++}
            }
        })
        val before=File(root,"profiles").walkTopDown().filter {it.isFile}.associate {it.path to sha256(it)}
        val work=async {FlutterResourceSealer(resource,"fixture").prepare(ConfigYaml.dump(source),true)}
        withTimeout(5000) {entered.await()};work.cancelAndJoin()
        assertTrue(stopped.isCompleted);assertEquals(1,closed)
        assertEquals(before,File(root,"profiles").walkTopDown().filter {it.isFile}.associate {it.path to sha256(it)})
        assertFalse(File(runtime,"resources/bundles").walkTopDown().any {it.isFile})
    }
    @Test fun generationReleaseFailureStillClosesOtherLeasesAndNeverReleasesTwice() {
        val directory=temporary.newFolder();var first=0;var second=0
        val one=ResourceGenerationLease(directory) {first++}
        val two=ResourceGenerationLease(directory) {second++;error("fixture release failure")}
        val input=SealedResourceInputs(listOf(SealedResourceInputs.Origin(one,emptyList()),SealedResourceInputs.Origin(two,emptyList())))
        assertTrue(runCatching {input.close()}.isFailure);assertEquals(1,first);assertEquals(1,second)
        input.close();assertEquals(1,first);assertEquals(1,second)
    }
    @Test fun actualResourceSectionsConsumePrivateSourceCachesThroughExistingSealer()=runBlocking<Unit> {
        val (root,full)=fixture();var downloads=0
        val source=resourceBoundary(full)
        val resources=manager(root) {downloads++}
        val captured=resources.retainSealedInputs(ConfigYaml.parse(source))
        val originalTime=captured.shared.single {it.key=="$.proxy-providers.remote"}.metadata!!.getLong("updatedAt")
        captured.close()
        val lease=FlutterResourceSealer(resources,"AEROVE-Lite fixture").prepare(source,true)
        try {
            assertEquals(0,downloads)
            val output=ConfigYaml.parse(lease.yaml)
            assertFalse(output.containsKey("aerove-native-resource-manifest"))
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
            assertEquals(originalTime,remote.getLong("updatedAt"))
        } finally {lease.closeAndJoin()}
        assertFalse(lease.directory.exists())
    }
}
