package dev.aerove.lite.data

import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GeoResource91Test {
    @get:Rule val temporary=TemporaryFolder()
    private fun manager(root:File,clock:()->Long={10000L}):ResourceBundleManager {
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {error("must use prepared converter")},root,NativePreparedConfigValidator { _,_ ->null }) {setOf("ss")}
        return ResourceBundleManager(root,schema,NativeResourceValidator { _,_,_,_ ->null },ResourceDownloader(),clock=clock)
    }
    private fun source(rules:String="MATCH,DIRECT",interval:Int=24,url:String="https://fixture.invalid/country")="""
        geodata:
          auto-update: false
          auto-update-interval: $interval
          url: {mmdb: '$url', asn: 'https://fixture.invalid/asn', geosite: 'https://fixture.invalid/site'}
        rules:
          - $rules
    """.trimIndent()
    private fun row(lease:FlutterResourceLease)=JSONObject(File(lease.directory,"manifest.json").readText()).getJSONArray("records").getJSONObject(0)
    @Test fun urlsArePreferencesAndExactScannerRequiresOnlyActualConsumers()=runBlocking<Unit> {
        val root=temporary.newFolder();val sealer=FlutterResourceSealer(manager(root),"AEROVE fixture")
        var reads=0
        val lease=sealer.prepare(source(),true,geoResourceReader=HostGeoResourceReader { _,_,_,_ ->reads++;error("unused default URL must not fetch") })
        try {assertEquals(0,reads);assertEquals(0,JSONObject(File(lease.directory,"manifest.json").readText()).getJSONArray("records").length())} finally {lease.closeAndJoin()}
        assertFalse(sealer.requiresGeoCapture(source()))
        for(rule in listOf("GEOIP,CN,DIRECT","IP-ASN,64500,DIRECT","GEOSITE,fixture,DIRECT","OR,((DOMAIN,fixture.invalid),(GEOIP,CN)),DIRECT")) assertTrue(rule,sealer.requiresGeoCapture(source(rule)))
        assertTrue(sealer.requiresGeoCapture("rule-providers: {fixture: {type: inline, behavior: classical, payload: ['GEOSITE,fixture,DIRECT']}}"))
        assertTrue(sealer.requiresGeoCapture("dns: {nameserver-policy: {'geosite:fixture': ['1.1.1.1']}}"))
        assertTrue(sealer.requiresGeoCapture("dns: {fallback: ['1.1.1.1']}"))
        assertFalse(sealer.requiresGeoCapture("dns: {fallback: ['1.1.1.1'], fallback-filter: {geoip: false}}"))
    }
    @Test fun actualCopyReadbackBindsBytesUrlPolicyAgeAndPreferencesInsideImmutableGeneration()=runBlocking<Unit> {
        val root=temporary.newFolder();val sealer=FlutterResourceSealer(manager(root),"AEROVE fixture")
        val library=temporary.newFile().apply {writeBytes(byteArrayOf(1,3,7))};val seen=mutableListOf<Pair<String,String>>()
        val reader=HostGeoResourceReader {key,url,destination,max ->
            seen+=key to url;val bytes=library.readBytes();assertTrue(bytes.size<=max);destination.writeBytes(bytes);HostGeoResourceCopy(sha256(library),bytes.size.toLong(),1234L)
        }
        val first=sealer.prepare(source("GEOIP,CN,DIRECT"),true,geoResourceReader=reader)
        val original=row(first);val originalFile=File(first.directory,original.getString("file"))
        try {
            assertEquals(listOf("$.mmdb" to "https://fixture.invalid/country"),seen)
            assertArrayEquals(library.readBytes(),originalFile.readBytes());assertEquals(sha256(originalFile),original.getString("sha256"));assertEquals("host-catalog",original.getString("transportOrigin"));assertEquals(1234L,original.getLong("updatedAt"))
            val manifest=JSONObject(File(first.directory,"manifest.json").readText());assertTrue(manifest.has("requestPolicy"));assertEquals(sha256(source("GEOIP,CN,DIRECT")),manifest.getString("sourceSha256"))
            library.writeBytes(byteArrayOf(9,8,7,6))
            val sameUrlPrefs=sealer.prepare(source("GEOIP,CN,DIRECT",interval=48),true,geoResourceReader=reader)
            try {
                assertEquals(original.getString("sourceSha256"),row(sameUrlPrefs).getString("sourceSha256"))
                assertNotEquals(original.getString("hostGeoPreferencesSha256"),row(sameUrlPrefs).getString("hostGeoPreferencesSha256"))
                assertNotEquals(manifest.getString("policyFingerprint"),JSONObject(File(sameUrlPrefs.directory,"manifest.json").readText()).getString("policyFingerprint"))
            } finally {sameUrlPrefs.closeAndJoin()}

            val second=sealer.prepare(source("GEOIP,CN,DIRECT",interval=48,url="https://fixture.invalid/changed"),false,geoResourceReader=reader)
            try {val next=row(second);assertNotEquals(first.directory,second.directory);assertNotEquals(original.getString("sourceSha256"),next.getString("sourceSha256"));assertNotEquals(original.getString("sha256"),next.getString("sha256"));assertArrayEquals(byteArrayOf(1,3,7),originalFile.readBytes());assertArrayEquals(library.readBytes(),File(second.directory,next.getString("file")).readBytes());assertEquals("https://fixture.invalid/changed",seen.last().second)} finally {second.closeAndJoin()}
        } finally {first.closeAndJoin()}
    }
    @Test fun explicitLocalPinKeepsSourceAndDoesNotConsultCatalog()=runBlocking<Unit> {
        val root=temporary.newFolder();val resources=manager(root);val profile=UUID.randomUUID().toString();val bytes=byteArrayOf(11,12,13)
        resources.importLocal(profile,"geo/pinned.mmdb",bytes.inputStream())
        val source=source("GEOIP,CN,DIRECT")+"\nmmdb: geo/pinned.mmdb\n"
        val bundle=resources.prepare(profile,source,geoResourceReader=HostGeoResourceReader { _,_,_,_ ->error("explicit local pin must not consult catalog") })
        try {val config=ConfigYaml.parse(bundle.yaml);assertArrayEquals(bytes,File(config["mmdb"] as String).readBytes())} finally {resources.discard(profile,bundle.generation)}
    }
    @Test fun explicitPrivateOverrideRemainsAheadOfCurrentCatalog()=runBlocking<Unit> {
        val root=temporary.newFolder();val resources=manager(root);val profile=UUID.randomUUID().toString();val raw=source("GEOIP,CN,DIRECT");val policy=ProviderRequestPolicy("AEROVE fixture",true)
        val first=resources.prepare(profile,raw,requestPolicy=policy,geoResourceReader=HostGeoResourceReader { _,_,dest,_ ->dest.writeBytes(byteArrayOf(1));HostGeoResourceCopy(sha256(dest),1,1234) })
        val original=JSONObject(File(first.directory,"manifest.json").readText()).getJSONArray("records").getJSONObject(0)
        val changed=resources.withResourceOverride(profile,setOf("$.mmdb"),byteArrayOf(9,9).inputStream(),original.getString("sourceSha256")) {
            resources.prepare(profile,raw,requestPolicy=policy,geoResourceReader=HostGeoResourceReader { _,_,_,_ ->error("private override must remain before Catalog") })
        }
        try {assertArrayEquals(byteArrayOf(9,9),File(ConfigYaml.parse(changed.yaml)["mmdb"] as String).readBytes());assertTrue(JSONObject(File(changed.directory,"manifest.json").readText()).getJSONArray("records").getJSONObject(0).getBoolean("localOverride"))}
        finally {resources.discard(profile,changed.generation);resources.discard(profile,first.generation)}
    }
    @Test fun hostReaderSymlinkNeverGrantsAnOutsideAsset()=runBlocking<Unit> {
        val outside=temporary.newFile().apply {writeBytes(byteArrayOf(7))};val probe=File(temporary.newFolder(),"link")
        try {java.nio.file.Files.createSymbolicLink(probe.toPath(),outside.toPath())} catch(failure:Exception) {org.junit.Assume.assumeNoException("Windows symlink privilege unavailable",failure)}
        probe.delete()
        val root=temporary.newFolder();val sealer=FlutterResourceSealer(manager(root),"AEROVE fixture")
        val failure=runCatching {sealer.prepare(source("GEOIP,CN,DIRECT"),true,geoResourceReader=HostGeoResourceReader { _,_,dest,_ ->java.nio.file.Files.createSymbolicLink(dest.toPath(),outside.toPath());HostGeoResourceCopy(sha256(outside),1,1234) })}.exceptionOrNull()
        assertNotNull(failure);assertTrue(root.walkTopDown().none {it.name=="manifest.json" || it.name.startsWith(".pending-")});assertArrayEquals(byteArrayOf(7),outside.readBytes())
    }
    @Test fun digestAgeOrSizeMismatchRejectsCompletePublication()=runBlocking<Unit> {
        for(kind in listOf("sha","age","size","unknown-age")) {
            val root=temporary.newFolder();val sealer=FlutterResourceSealer(manager(root),"AEROVE fixture")
            val failure=runCatching {sealer.prepare(source("GEOIP,CN,DIRECT"),true,geoResourceReader=HostGeoResourceReader { _,_,dest,_ ->dest.writeBytes(byteArrayOf(1));HostGeoResourceCopy(if(kind=="sha") "0".repeat(64) else sha256(dest),if(kind=="size") Long.MAX_VALUE else 1L,if(kind=="unknown-age") null else if(kind=="age") 10001L else 1234L) })}.exceptionOrNull()
            assertNotNull(kind,failure);assertTrue(root.walkTopDown().none {it.name=="manifest.json" || it.name.startsWith(".pending-")})
        }
    }
    @Test fun cancellationJoinsCopyAndKeepsExistingImmutableGeneration()=runBlocking<Unit> {
        val root=temporary.newFolder();val sealer=FlutterResourceSealer(manager(root),"AEROVE fixture")
        val good=HostGeoResourceReader { _,_,dest,_ ->dest.writeBytes(byteArrayOf(5));HostGeoResourceCopy(sha256(dest),1,1234) }
        val first=sealer.prepare(source("GEOIP,CN,DIRECT"),true,geoResourceReader=good);val before=sha256(File(first.directory,"manifest.json"));val started=CompletableDeferred<Unit>();var joined=false
        val job=launch {sealer.prepare(source("GEOIP,CN,DIRECT"),true,geoResourceReader=HostGeoResourceReader { _,_,dest,_ ->try {dest.writeBytes(byteArrayOf(6));started.complete(Unit);awaitCancellation()} finally {joined=true} })}
        started.await();job.cancelAndJoin();assertTrue(joined);assertEquals(before,sha256(File(first.directory,"manifest.json")));assertTrue(root.walkTopDown().none {it.name.startsWith(".pending-")});first.closeAndJoin()
    }
    @Test fun actualCatalogCacheAgeRequestPolicyAndManualUpdateFeedNewSealedBytes()=runBlocking<Unit> {
        val root=temporary.newFolder();var now=10000L;var requests=0
        val http=okhttp3.OkHttpClient.Builder().addInterceptor {chain ->
            requests++;okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture").body(okhttp3.ResponseBody.create(null,byteArrayOf(requests.toByte(),2,3))).build()
        }.build()
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {error("prepared converter required")},root,NativePreparedConfigValidator { yaml,_ ->assertEquals(false,(ConfigYaml.parse(yaml)["geodata"] as Map<*,*>)["auto-update"]);null }) {setOf("ss")}
        val native=NativeResourceValidator { _,_,_,_ ->null }
        var prefs=dev.aerove.lite.resources.GeoResourcePreferences(true,1,dev.aerove.lite.resources.GeoResourceKind.entries.associateWith {kind ->"https://fixture.invalid/${if(kind.wire=="mmdb") "country" else if(kind.wire=="geosite") "site" else "asn"}"})
        val catalog=dev.aerove.lite.resources.GeoResourceCatalog(root,schema,native,ResourceDownloader(http),{prefs},{now})
        val resources=ResourceBundleManager(File(root,"sealed"),schema,native,ResourceDownloader(),clock={now})
        val sealer=FlutterResourceSealer(resources,"AEROVE fixture")
        val initial=prefs;val policy=dev.aerove.lite.requests.RequestPreferences(globalUserAgent="AEROVE fixture")
        val raw=source("GEOIP,CN,DIRECT",interval=1).replace("auto-update: false","auto-update: true")
        val leases=mutableListOf<FlutterResourceLease>()
        suspend fun seal(value:String=raw,settings:dev.aerove.lite.resources.GeoResourcePreferences=prefs,request:dev.aerove.lite.requests.RequestPreferences=policy):FlutterResourceLease = sealer.prepare(value,request.checkCertificate,geoResourceReader=catalog.reader(settings,request) {prefs==settings}).also {leases+=it}
        try {
            val first=seal();assertEquals(1,requests);val original=row(first);val originalFile=File(first.directory,original.getString("file"));assertArrayEquals(byteArrayOf(1,2,3),originalFile.readBytes());assertEquals(now,original.getLong("updatedAt"))
            seal();assertEquals(1,requests)
            now+=3_600_001L;val refreshed=seal();assertEquals(2,requests);assertArrayEquals(byteArrayOf(2,2,3),File(refreshed.directory,row(refreshed).getString("file")).readBytes());assertEquals(now,row(refreshed).getLong("updatedAt"));assertArrayEquals(byteArrayOf(1,2,3),originalFile.readBytes())
            val changedPolicy=policy.copy(globalUserAgent="Changed fixture",checkCertificate=false)
            val changedSource="global-ua: Changed fixture\n"+raw
            val policyLease=seal(changedSource,request=changedPolicy);assertEquals(3,requests);assertEquals(false,JSONObject(File(policyLease.directory,"manifest.json").readText()).getJSONObject("requestPolicy").getBoolean("checkCertificate"))
            catalog.sync(dev.aerove.lite.resources.GeoResourceKind.MMDB,prefs,changedPolicy) {true};assertEquals(4,requests)
            val manual=seal(changedSource,request=changedPolicy);assertEquals(4,requests);assertArrayEquals(byteArrayOf(4,2,3),File(manual.directory,row(manual).getString("file")).readBytes())
            prefs=prefs.withUrl(dev.aerove.lite.resources.GeoResourceKind.MMDB,"https://fixture.invalid/new")
            val wrong=runCatching {seal(raw,initial)}.exceptionOrNull();assertTrue(wrong is CancellationException)
            val newSource=changedSource.replace("https://fixture.invalid/country","https://fixture.invalid/new");val changed=seal(newSource,request=changedPolicy);assertEquals(5,requests);assertNotEquals(original.getString("sourceSha256"),row(changed).getString("sourceSha256"))
            assertTrue(root.walkTopDown().none {it.name.startsWith(".pending-")})
        } finally {leases.asReversed().forEach {it.closeAndJoin()};http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
    }

}
