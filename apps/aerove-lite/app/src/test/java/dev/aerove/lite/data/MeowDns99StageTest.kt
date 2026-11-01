package dev.aerove.lite.data
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.aerove.lite.flutter.*
class MeowDns99StageTest {
 @get:Rule val temporary=TemporaryFolder()
 private fun fixture():JSONObject {
  val path=System.getProperty("dns99.fixture");org.junit.Assume.assumeNotNull("Supply actual getProfile/Node fixture",path)
  return JSONObject(File(checkNotNull(path)).readText())
 }
 private suspend fun stage(source:String,ipv6:Boolean):MeowRuntimeSnapshot {
  val home=temporary.newFolder();File(home,"config.yaml").writeText(source)
  val store=RuntimeHandoffStore(home,validateProjection=MeowRuntimeSnapshotSettings::validate)
  val effects=object:MeowSetupEffects {
   override fun isInitialized()=true
   override suspend fun readRuntime():MeowSetupRuntime?=null
   override suspend fun applyUpdate(expected:MeowSetupRuntime?,projection:MeowSetupProjection) {}
   override suspend fun refreshApplied(expected:MeowSetupRuntime,setupParamsJson:String)=false
   override suspend fun applyStaged(snapshot:MeowRuntimeSnapshot) {}
  }
  MeowConfigProtocol(home,NativeConfigValidator {null},{emptySet()}).use {config ->
   val api=MeowSetupProtocol(home,store,config,effects,NativePreparedConfigValidator { yaml,_ ->
    val root=ConfigYaml.parse(yaml);assertEquals(ipv6,root["ipv6"])
    val dns=root["dns"] as Map<*,*>;assertFalse(dns.containsKey("ipv6"));assertFalse(dns.containsKey("fake-ip-range6"));assertFalse(dns.containsKey("__aerove_dns_defaults"));null
   },verifiedFeatures={emptySet()},validator=NativeConfigValidator {null})
   api.syncState(mapOf("setupParams" to null,"vpnOptions" to mapOf("enable" to false,"port" to 7890,"ipv6" to ipv6,"dnsHijacking" to false,"allowBypass" to false,"systemProxy" to false,
    "bypassDomain" to emptyList<String>(),"stack" to "lwip","routeAddress" to emptyList<String>(),"mtu" to 9000,"congestionController" to "builtin",
    "accessControlProps" to mapOf("enable" to false,"mode" to "rejectSelected","acceptList" to emptyList<String>(),"rejectList" to emptyList<String>())),
    "stopTip" to "Stop","startTip" to "Start","localNetworkTip" to "LAN","currentProfileName" to "fixture","stopText" to "Stop","onlyStatisticsProxy" to true))
   api.setupConfig(mapOf("selected-map" to emptyMap<String,String>(),"test-url" to "https://example.test/probe","applied" to mapOf("digest" to "fixture","yaml-md5" to "a".repeat(32),"groups" to 0,"proxies" to 0,"rules" to 1)))
   val snapshot=checkNotNull(store.readCurrent());assertFalse(snapshot.userRequested);assertNull(store.readLastApplied())
   assertEquals(ipv6,MeowRuntimeSnapshotSettings.decode(snapshot.handoff.runtimeSettingsJson).ipv6)
   return snapshot
  }
 }
 @Test fun actualGetProfileDefaultsAndSingleJsOutputPassCompleteStageForBothRootIpv6States()=runBlocking<Unit> {
  val data=fixture()
  stage(data.getString("initialYaml"),true)
  stage(data.getString("rootFalseYaml"),false)
  stage(data.getString("scriptYaml"),true)
 }
 @Test fun allFourteenExplicitPathsStillFailTheUnchangedCompleteNativeStage()=runBlocking<Unit> {
  val values=linkedMapOf<String,Any?>("listen-routing-mark" to 0,"prefer-h3" to false,"respect-rules" to false,"ipv6" to true,"ipv6-timeout" to 100,"cache-algorithm" to "lru","cache-max-size" to 4096,"fake-ip-range6" to "fdfe:dcba:9876::1/64","fake-ip-ttl" to 1,"fallback-lazy-query" to false,"proxy-server-nameserver-policy" to emptyMap<String,Any?>(),"direct-nameserver" to emptyList<String>(),"direct-nameserver-follow-policy" to false,"fallback-filter.geosite" to emptyList<String>())
  for((path,value) in values) {
   val dns=linkedMapOf<String,Any?>("enable" to true,"nameserver" to listOf("https://1.1.1.1/dns-query"));val parts=path.split('.')
   if(parts.size==1)dns[path]=value else dns[parts[0]]=mapOf(parts[1] to value)
   val source=ConfigYaml.dump(mapOf("ipv6" to true,"dns" to dns,"rules" to listOf("MATCH,DIRECT")))
   val failure=runCatching {stage(source,true)}.exceptionOrNull()
   assertTrue(path,failure is ConfigValidationException);assertTrue(path,failure!!.message.orEmpty().contains("$.dns.$path"))
  }
 }
}
