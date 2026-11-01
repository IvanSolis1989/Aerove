package dev.aerove.lite.fixtures
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.data.*
import dev.aerove.lite.flutter.MeowLegacyMigrationExport
import dev.aerove.lite.overrides.OverrideRepository
import dev.aerove.lite.overrides.ProfileOverride
import dev.aerove.lite.dnssettings.*
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.regexp2.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

fun main(args:Array<String>)=runBlocking<Unit> {
    val root=File(args[0]);check(!root.exists());check(root.mkdirs())
    ExactFilterRuntime.install(dev.aerove.lite.profilebuilder.RealGoFilterExecutor())
    val runtime=File(root,"runtime");val validator=CompatibilityValidator.forMeow(NativeConfigValidator { null },runtime,NativePreparedConfigValidator { _,_ -> null }) { setOf("ss") }
    val payloads=mapOf(
        "https://example.invalid/proxy" to "proxies:\r\n  - {name: Remote, type: ss, server: 127.0.0.9, port: 12345, cipher: aes-128-gcm, password: fixture-only}\r\n".toByteArray(),
        "https://example.invalid/rules" to "+.remote.invalid\r\n".toByteArray(),
        "https://example.invalid/country" to byteArrayOf(0,4,8,16,32),
        "https://example.invalid/site" to byteArrayOf(1,3,9,27)
    )
    val client=OkHttpClient.Builder().addInterceptor { chain ->
        val request=chain.request();val bytes=checkNotNull(payloads[request.url.toString()])
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
    }.build()
    var downloads=0
    val manager=ResourceBundleManager(runtime,validator,NativeResourceValidator { _,_,_,_ ->null },ResourceDownloader(client),
        providerDownloads=ProviderDownloadFactory { object:ProviderDownloadScope {
            override suspend fun download(request:ProviderDownloadRequest):ProviderDownloadResult {
                downloads++;request.destination.writeBytes(checkNotNull(payloads[request.url]));return ProviderDownloadResult(request.destination.length(),sha256(request.destination),"upload=1; download=2; total=20; expire=1900000000")
            }
            override suspend fun closeAndJoin() { }
        } })
    val source=ProfileRepository(root,ProfileValidator { })
    val ordinary=source.importText("Ordinary resource fixture","""
proxies:
  - {name: A local, type: ss, server: 127.0.0.1, port: 12345, cipher: aes-128-gcm, password: fixture-only}
proxy-groups:
  - {name: Ordinary, type: select, proxies: [A local, DIRECT], use: [remote, local]}
proxy-providers:
  remote:
    type: http
    url: https://example.invalid/proxy
    path: cache/remote.yaml
    interval: 86400
    header: {X-Fixture: [fixture-only]}
    health-check: {enable: false}
    filter: '^Remote'
    exclude-filter: '^never'


  local: {type: file, path: local/nodes.yaml}
rule-providers:
  localRules: {type: file, behavior: domain, format: text, path: local/rules.txt}
  remoteRules:
    type: http
    url: https://example.invalid/rules
    path: cache/rules.txt
    behavior: domain
    format: text
    interval: 86400
    header: {X-Fixture: [fixture-only]}
mmdb-download-url: https://example.invalid/country
geosite-download-url: https://example.invalid/site
rules: [RULE-SET,localRules,DIRECT]
""".trimIndent().replace("rules: [RULE-SET,localRules,DIRECT]","rules:\n  - RULE-SET,localRules,DIRECT\n  - MATCH,DIRECT"))
    for((name,bytes) in mapOf("tls/ca.pem" to "fixture-certificate\r\n".toByteArray(),"local/nodes.yaml" to "proxies:\r\n  - {name: Local provider, type: direct}\r\n".toByteArray(),"local/rules.txt" to "+.local.invalid\r\n".toByteArray())) manager.importLocal(ordinary.id,name,bytes.inputStream())
    val settings=LiteSettingsStore(root);settings.save(LiteSettings().copy(acceptedDisclaimerVersion=1))
    val overrides=OverrideRepository(root);overrides.setProfile(ProfileOverride(ordinary.id,dnsPreferences=DnsPreferences().withValue(DnsField.Nameserver,DnsValue.Entries(listOf("https://1.0.0.1/dns-query"))).copy(hosts=mapOf("fixture.invalid" to listOf("127.0.0.4")))))
    val library=BuilderResourceLibrary(root)
    val first=BuilderFilter("B first","^B");val second=BuilderFilter("A second","^A")
    val icons=BuilderIconSet("Fixture icons","https://example.invalid/icons",listOf(BuilderIcon("Fixture","https://example.invalid/icon.png")),System.currentTimeMillis(),32)
    library.replace(library.snapshot(),BuilderResources(filters=listOf(first,second),iconSets=listOf(icons)))
    val bindings=BuilderResourceBindings(filters=mapOf("Pick" to BuilderFilterBinding(listOf(first.id,second.id),listOf("DIRECT"))),icons=mapOf("Pick" to BuilderIconBinding(icons.id,"https://example.invalid/icon.png")))
    val custom=source.importText("Bound fixture",ProfileBuilderDocument.parse("""
proxies:
  - {name: A one, type: ss, server: 127.0.0.2, port: 12345, cipher: aes-128-gcm, password: fixture-only}
  - {name: B two, type: ss, server: 127.0.0.3, port: 12345, cipher: aes-128-gcm, password: fixture-only}
proxy-groups:
  - {name: Pick, type: select, proxies: [DIRECT]}
rules:
  - MATCH,DIRECT
""".trimIndent()).withBindings(bindings).yaml(),null,true)
    val profiles=ProfileRepository(root,ProfileValidator { },resources=manager)
    profiles.prepare(ordinary.id);profiles.prepare(custom.id);profiles.select(ordinary.id)
    fun hashes()=root.walkTopDown().filter{it.isFile && "native-lite-migration-exports" !in it.relativeTo(root).invariantSeparatorsPath}.associate{it.relativeTo(root).invariantSeparatorsPath to sha256(it)}
    val before=hashes();val lease=MeowLegacyMigrationExport(root,profiles,settings,overrides).export();check(before==hashes())
    File(args[1]).writeText(JSONObject().put("manifest",lease.manifest.path).put("sha256",lease.manifestSha256).put("ordinaryUuid",ordinary.id).put("customUuid",custom.id).put("sourceUnchanged",true).put("downloadCount",downloads).toString())
    client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
    println("actual Native resource and binding fixture exported; source unchanged")
}
