package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore.RuntimeMode
import dev.aerove.lite.networksettings.*
import dev.aerove.lite.flutter.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowTunReconciliation90Test {
    @get:Rule val temporary = TemporaryFolder()
    private val source = """
        dns: {enable: true, nameserver: ['https://example.test/dns-query']}
        rules: ['MATCH,DIRECT']
        tun:
          enable: true
          stack: lwip
          congestion-controller: builtin
          mtu: 1370
          inet4-address: 10.77.0.1/24
          inet6-address: fd11::1/126
          route-address: ['8.8.8.0/24', '2001:db8::/32']
          route-exclude-address: ['8.8.8.128/25']
          dns-hijack: ['any:53', '1.1.1.1:53']
    """.trimIndent()
    private fun options(settings: LiteSettings) = TunRuntimeOptions(settings.network.mtu, settings.network.dnsHijacking, settings.ipv6,
        settings.network.tunInet4Address, settings.network.tunInet6Address, settings.network.tunDnsHijack, settings.network)
    private fun composer(root: File) = MeowRuntimeConfigComposer(root,"AEROVE-test",File(root,"meow-cache/session").apply {mkdirs()})
    private fun rejects(path: String, block: () -> Unit) {
        val error = assertThrows(ConfigValidationException::class.java, block)
        assertEquals(path, error.issues.single().path)
    }
    @Test fun sourceRequiresExactContextAndTypedBuilderAndCoreReadbackAgree() {
        val file = File(temporary.newFolder(),"source.yaml").apply {writeText(source)}
        val original = file.readBytes()
        rejects("$.tun.enable") { MeowSourceConfig.input(file.readText()) }
        val receipt = MeowTunReconciliation.reconcile(source,LiteSettings(ipv6=true,network=NetworkSettings(bypassPrivateRoute=false)))
        val settings = receipt.settings
        assertEquals(1370,settings.network.mtu)
        assertEquals(ConfigYaml.parse(source)["tun"],MeowSourceConfig.input(source,receipt).first["tun"])
        val plan = planNetwork(settings.network,49152,true)
        val writes = mutableListOf<String>()
        applyVpnTunPolicy(object:VpnTunPolicyTarget {
            override fun mtu(value: Int) {writes += "mtu=$value"}
            override fun address(host: String, prefix: Int) {writes += "address=$host/$prefix"}
            override fun route(host: String, prefix: Int) {writes += "route=$host/$prefix"}
        },plan)
        assertTrue("mtu=1370" in writes);assertTrue("address=10.77.0.1/24" in writes);assertTrue("address=fd11::1/126" in writes)
        assertEquals(listOf("8.8.8.0/25","2001:db8:0:0:0:0:0:0/32"),plan.routes)
        val encoded=NetworkSettingsJson.encode(settings.network)
        assertEquals(settings.network,NetworkSettingsJson.decode(JSONObject(encoded.toString())))
        val core=composer(file.parentFile).compose(source,settings,41,49152,"a".repeat(32),tunOptions=options(settings),tunReconciliation=receipt)
        val tun=JSONObject(core.planJson()).getJSONObject("tun")
        assertEquals(1370,tun.getInt("mtu"));assertEquals("10.77.0.1/24",tun.getString("inet4Address"));assertEquals("fd11::1/126",tun.getString("inet6Address"))
        assertEquals(listOf("any:53","1.1.1.1:53"),(0 until tun.getJSONArray("dnsHijack").length()).map {tun.getJSONArray("dnsHijack").getString(it)})
        assertFalse(tun.getBoolean("autoRoute"));assertEquals(mapOf("enable" to false,"auto-route" to false),ConfigYaml.parse(core.engineYaml)["tun"])
        assertArrayEquals(original,file.readBytes())
        rejects("$.tun") { MeowSourceConfig.input(source.replace("1370","1380"),receipt) }
        rejects("$.tun") { composer(file.parentFile).compose(source,settings,41,49152,"a".repeat(32),tunOptions=options(settings).copy(mtu=1400),tunReconciliation=receipt) }
    }
    @Test fun sourceCannotAuthorizeVpnAndAutoRouteFalseDoesNotBecomeDefaultRoute() {
        val source="tun: {enable: true, mtu: 1380, auto-route: false}\nrules: ['MATCH,DIRECT']\n"
        val receipt=MeowTunReconciliation.reconcile(source,LiteSettings(network=NetworkSettings(vpnEnabled=false,bypassPrivateRoute=false)))
        assertFalse(receipt.settings.network.vpnEnabled);assertEquals(emptyList<String>(),planVpnRoutes(receipt.settings.network,false))
        val core=composer(temporary.newFolder()).compose(source,receipt.settings,-1,49152,"a".repeat(32),RuntimeMode.Proxy,
            tunOptions=options(receipt.settings),initiallyParked=true,tunReconciliation=receipt)
        assertNull(core.tun)
        assertEquals(emptyList<String>(),planVpnRoutes(receipt.settings.network.copy(vpnEnabled=true),false))
        rejects("$.tun.stack") { MeowTunReconciliation.reconcile("tun: {stack: system}",LiteSettings()) }
        rejects("$.tun.congestion-controller") { MeowTunReconciliation.reconcile("tun: {congestion-controller: cubic}",LiteSettings()) }
        rejects("$.tun.device") { MeowTunReconciliation.reconcile("tun: {device: custom}",LiteSettings()) }
        rejects("$.tun.unknown") { MeowTunReconciliation.reconcile("tun: {unknown: true}",LiteSettings()) }
        val defaultFake = "dns: {enable: true, enhanced-mode: fake-ip}\nrules: ['MATCH,DIRECT']\n"
        assertEquals(listOf("198.18.0.1/16"), MeowTunReconciliation.reservedRanges(defaultFake, false))
        val v6 = MeowTunReconciliation.reconcile("dns: {enable: true}\nrules: ['MATCH,DIRECT']\n", LiteSettings(ipv6=true))
        rejects("$.tun") { MeowTunReconciliation.requireActual("dns: {enable: true}\nrules: ['MATCH,DIRECT']\n",v6.settings,options(v6.settings),emptyList()) }

    }
    private fun shared() = mapOf<String,Any?>("setupParams" to null,"vpnOptions" to mapOf("enable" to false,"port" to 7890,"ipv6" to false,
        "dnsHijacking" to false,"allowBypass" to false,"systemProxy" to false,"bypassDomain" to emptyList<String>(),"stack" to "lwip","routeAddress" to emptyList<String>(),"mtu" to 1400,"congestionController" to "builtin",
        "accessControlProps" to mapOf("enable" to false,"mode" to "rejectSelected","acceptList" to emptyList<String>(),"rejectList" to emptyList<String>())),
        "stopTip" to "Stop","startTip" to "Start","localNetworkTip" to "LAN","currentProfileName" to "Fixture","stopText" to "Stop","onlyStatisticsProxy" to true)
    private fun setup() = mapOf<String,Any?>("selected-map" to emptyMap<String,String>(),"test-url" to "https://example.test/probe","applied" to mapOf("digest" to "fixture","yaml-md5" to "a".repeat(32),"groups" to 1,"proxies" to 1,"rules" to 2))
    @Test fun stageRejectsUnconfirmedBuilderChangesAndCancellationOrOldEpochCannotPublish() = runBlocking<Unit> {
        val root=temporary.newFolder();val file=File(root,"config.yaml")
        file.writeText("tun: {mtu: 1370}\nrules: ['MATCH,DIRECT']\n")
        val store=RuntimeHandoffStore(root,validateProjection=MeowRuntimeSnapshotSettings::validate)
        var applications=0;var blockBuilder=false
        var entered:CompletableDeferred<Unit>?=null;var finish:CompletableDeferred<Unit>?=null
        val effects=object:MeowSetupEffects {
            override fun isInitialized()=true
            override suspend fun readRuntime():MeowSetupRuntime?=null
            override suspend fun applyUpdate(expected:MeowSetupRuntime?,projection:MeowSetupProjection) {}
            override suspend fun refreshApplied(expected:MeowSetupRuntime,setupParamsJson:String)=false
            override suspend fun applyStaged(snapshot:MeowRuntimeSnapshot) {applications++}
            override suspend fun requireBuilderCompatible(settings:LiteSettings,vpnOptionsJson:String) {if(blockBuilder) throw MeowConfigProtocolException("restart_required","Explicit restart required")}
        }
        MeowConfigProtocol(root,NativeConfigValidator {null},{emptySet()}).use { config ->
            val native=NativeConfigValidator { entered?.complete(Unit);finish?.await();null }
            val api=MeowSetupProtocol(root,store,config,effects,verifiedFeatures={emptySet()},validator=native)
            api.syncState(shared());api.setupConfig(setup())
            val initial=store.readCurrent()!!
            assertEquals(1370,MeowRuntimeSnapshotSettings.decode(initial.handoff.runtimeSettingsJson).network.mtu)
            assertEquals(1370,JSONObject(initial.handoff.vpnOptionsJson).getInt("mtu"))
            assertEquals(mapOf("mtu" to 1370),ConfigYaml.parse(initial.handoff.compiledYaml)["tun"])
            fun changedOptions(row:JSONObject):RuntimeHandoff {
                val old=initial.handoff
                return RuntimeHandoff(old.coreSha,old.sourceId,old.sourceRevision,old.sourceSha256,old.compiledYaml,old.compiledSha256,old.setupRecordJson,
                    old.runtimeSettingsJson,row.toString(),old.notificationJson,old.resources)
            }
            assertThrows(IllegalArgumentException::class.java) { MeowRuntimeSnapshotSettings.validate(changedOptions(JSONObject(initial.handoff.vpnOptionsJson).put("mtu",0))) }
            assertThrows(IllegalArgumentException::class.java) { MeowRuntimeSnapshotSettings.validate(changedOptions(JSONObject(initial.handoff.vpnOptionsJson).put("bypassPrivateRoute",true))) }
            assertFalse(initial.userRequested);assertNull(store.readLastApplied());assertEquals(1,applications)
            file.writeText("tun: {mtu: 1380}\nrules: ['MATCH,DIRECT']\n")
            blockBuilder=true
            assertTrue(runCatching {api.setupConfig(setup())}.isFailure)
            assertEquals(initial.revision,store.readCurrent()!!.revision);assertEquals(1,applications)
            blockBuilder=false;entered=CompletableDeferred();finish=CompletableDeferred()
            val cancelled=async {runCatching {api.setupConfig(setup())}}
            withTimeout(5000) {entered!!.await()};cancelled.cancelAndJoin()
            assertEquals(initial.revision,store.readCurrent()!!.revision);assertEquals(1,applications)
            entered=CompletableDeferred();finish=CompletableDeferred()
            val oldEpoch=async {runCatching {api.setupConfig(setup())}}
            withTimeout(5000) {entered!!.await()};store.invalidate(initial.intentEpoch);finish!!.complete(Unit)
            assertTrue(oldEpoch.await().isFailure);assertEquals(1,applications)
            assertEquals(initial.handoff.compiledYaml,store.readCurrent()!!.handoff.compiledYaml)
            assertFalse(store.readCurrent()!!.userRequested);assertNull(store.readLastApplied())
        }
    }
    @Test fun rawRoutesGlobalPolicyAndSourceOverridesAreAppliedOnceAndMatchCanonicalBuilder() = runBlocking<Unit> {
        val plain="rules: ['MATCH,DIRECT']\n"
        val base=LiteSettings(network=NetworkSettings(vpnEnabled=true,bypassPrivateRoute=false,routeAddress=listOf("10.0.0.0/8"),routeExcludeAddress=listOf("10.128.0.0/9")))
        val wireOptions=JSONObject().put("enable",true).put("ipv6",false)
        val projection=meowReconcileFlutterTun(plain,base,JSONObject().put("vpnOptions",wireOptions))
        val builder=meowFlutterBuilderSettings(projection.settings,projection.shared.getJSONObject("vpnOptions").toString())
        assertEquals(listOf("10.0.0.0/9"),planVpnRoutes(builder.network,false))
        assertFalse(builder.network.bypassPrivateRoute)
        MeowTunReconciliation.requireActual(plain,projection.reconciliation.settings,options(builder))
        val overridden="tun: {route-address: ['8.0.0.0/8'], route-exclude-address: ['8.128.0.0/9']}\nrules: ['MATCH,DIRECT']\n"
        val changed=meowReconcileFlutterTun(overridden,base,JSONObject().put("vpnOptions",wireOptions))
        val changedBuilder=meowFlutterBuilderSettings(changed.settings,changed.shared.getJSONObject("vpnOptions").toString())
        assertEquals(listOf("8.0.0.0/9"),planVpnRoutes(changedBuilder.network,false))
        MeowTunReconciliation.requireActual(overridden,changed.reconciliation.settings,options(changedBuilder))
        val private=meowReconcileFlutterTun("tun: {route-address: ['10.0.0.0/8']}\n",base.copy(network=base.network.copy(bypassPrivateRoute=true)),JSONObject().put("vpnOptions",wireOptions))
        val privateBuilder=meowFlutterBuilderSettings(private.settings,private.shared.getJSONObject("vpnOptions").toString())
        assertEquals(emptyList<String>(),planVpnRoutes(privateBuilder.network,false))
        val fake=MeowTunReconciliation.reconcile("dns: {enable: true, enhanced-mode: fake-ip}\ntun: {auto-route: fake-ip}\n",base)
        assertEquals(listOf("198.18.0.0/16"),planVpnRoutes(fake.settings.network,false))
        val dtoPath=System.getProperty("tun90.dto")
        if(dtoPath != null) {
            val dto=JSONObject(File(dtoPath).readText())
            val wire=shared().toMutableMap();wire["vpnOptions"]=(dto.keys().asSequence().associateWith { dto.get(it) })
            val root=temporary.newFolder();File(root,"config.yaml").writeText(plain)
            val store=RuntimeHandoffStore(root,validateProjection=MeowRuntimeSnapshotSettings::validate)
            val effects=object:MeowSetupEffects {
                override fun isInitialized()=true
                override suspend fun readRuntime():MeowSetupRuntime?=null
                override suspend fun applyUpdate(expected:MeowSetupRuntime?,projection:MeowSetupProjection) {}
                override suspend fun refreshApplied(expected:MeowSetupRuntime,setupParamsJson:String)=false
                override suspend fun applyStaged(snapshot:MeowRuntimeSnapshot) {}
            }
            MeowConfigProtocol(root,NativeConfigValidator {null},{emptySet()}).use { config ->
                val api=MeowSetupProtocol(root,store,config,effects,verifiedFeatures={emptySet()},validator=NativeConfigValidator {null})
                api.syncState(wire);api.setupConfig(setup())
                val saved=store.readCurrent()!!.handoff
                val actual=meowFlutterBuilderSettings(MeowRuntimeSnapshotSettings.decode(saved.runtimeSettingsJson),saved.vpnOptionsJson)
                assertEquals(listOf("10.0.0.0/9","0:0:0:0:0:0:0:0/0"),planVpnRoutes(actual.network,actual.ipv6))
                assertEquals(listOf("10.0.0.0/9","0:0:0:0:0:0:0:0/0"),(0 until JSONObject(saved.vpnOptionsJson).getJSONArray("routeAddress").length()).map {JSONObject(saved.vpnOptionsJson).getJSONArray("routeAddress").getString(it)})
            }
        }
    }
    @Test fun actualTaskYamlTraversesSameSealerAndStageWithoutGrantingStartAuthority() = runBlocking<Unit> {
        val path=System.getProperty("tun90.fixture");assumeTrue("Actual resource89 Task fixture is required",path != null)
        val originalRoot=File(checkNotNull(path));val root=temporary.newFolder();originalRoot.copyRecursively(root,overwrite=true)
        val actual=File(originalRoot,"ordinary-compiled.yaml").readText()
        val input=actual.replace(originalRoot.path,root.path).replace(originalRoot.path.replace('\\','/'),root.path.replace('\\','/'))
        File(root,"config.yaml").writeText(input)
        val runtime=File(root,"runtime")
        val schema=CompatibilityValidator.forMeow(NativeConfigValidator {error("unsealed native conversion")},runtime,NativePreparedConfigValidator {_,_->null}) {setOf("ss")}
        val resources=ResourceBundleManager(runtime,schema,NativeResourceValidator {_,_,_,_->null},ResourceDownloader(),providerDownloads=ProviderDownloadFactory {error("Current verified source caches must satisfy this fixture")})
        var applied:MeowRuntimeSnapshot?=null
        var rejectBuilder=false
        val store=RuntimeHandoffStore(root,validateProjection=MeowRuntimeSnapshotSettings::validate)
        val effects=object:MeowSetupEffects {
            override fun isInitialized()=true
            override suspend fun readRuntime():MeowSetupRuntime?=null
            override suspend fun applyUpdate(expected:MeowSetupRuntime?,projection:MeowSetupProjection) {}
            override suspend fun refreshApplied(expected:MeowSetupRuntime,setupParamsJson:String)=false
            override suspend fun applyStaged(snapshot:MeowRuntimeSnapshot) {applied=snapshot}
            override suspend fun requireBuilderCompatible(settings:LiteSettings,vpnOptionsJson:String) {if(rejectBuilder) throw MeowConfigProtocolException("restart_required","Fixture represents an already established incompatible Builder")}
        }
        MeowConfigProtocol(root,NativeConfigValidator {null},{setOf("ss")}).use { config ->
            val api=MeowSetupProtocol(root,store,config,effects,preparedValidator=NativePreparedConfigValidator {_,_->null},verifiedFeatures={setOf("ss")},validator=NativeConfigValidator {null},resourceSealer=FlutterResourceSealer(resources,"AEROVE-test"))
            api.syncState(shared());api.setupConfig(setup())
            val staged=checkNotNull(applied)
            val sourceTun=ConfigYaml.parse(input)["tun"] as Map<*,*>
            assertEquals(sourceTun,ConfigYaml.parse(staged.handoff.compiledYaml)["tun"])
            val settings=MeowRuntimeSnapshotSettings.decode(staged.handoff.runtimeSettingsJson)
            assertEquals((sourceTun["mtu"] as Number).toInt(),settings.network.mtu)
            assertEquals(settings.network.mtu,JSONObject(staged.handoff.vpnOptionsJson).getInt("mtu"))
            assertFalse(staged.userRequested);assertNull(store.readLastApplied())
            rejectBuilder=true
            val before=store.readCurrent()!!.revision
            File(root,"config.yaml").writeText(input.replace("mtu: 9000","mtu: 1370"))
            assertTrue(runCatching {api.setupConfig(setup())}.isFailure)
            assertEquals(before,store.readCurrent()!!.revision)
            assertFalse(store.readCurrent()!!.userRequested)
        }
        assertEquals(actual,File(originalRoot,"ordinary-compiled.yaml").readText())
    }
}
