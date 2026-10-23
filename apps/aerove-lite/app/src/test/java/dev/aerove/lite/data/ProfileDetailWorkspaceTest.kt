package dev.aerove.lite.data

import dev.aerove.lite.integration.*
import dev.aerove.lite.providers.*
import dev.aerove.lite.resources.*
import dev.aerove.lite.overrides.OverrideMode
import dev.aerove.lite.vpn.CoreEndpoint
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProfileDetailWorkspaceTest {
    private class Fixture(val server: MockWebServer) {
        val root = Files.createTempDirectory("profile-detail").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val inspector = object : NativeRulePayloadInspector {
            override fun inspect(path: String) = RulePayloadInspection("text", "domain", 1)
            override fun dump(path: String, behavior: String, format: String) = File(path).readText()
        }
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, offlineRules = inspector)
        val library = dev.aerove.lite.profilebuilder.BuilderResourceLibrary(root)
        val pipeline = ProfileSourcePipeline(library, dev.aerove.lite.overrides.OverridePipeline(
            dev.aerove.lite.overrides.OverrideRepository(root), dev.aerove.lite.overrides.ScriptEngine { _, source -> source }), { dev.aerove.lite.LiteSettings() })
        val profiles = ProfileRepository(root, validator, resources = manager, sourcePolicy = pipeline, globalResources = library)
        val resources = ResourceManagementRepository(profiles)
        val managed = ProfileDetailResources(profiles, resources, library, GlobalRulePayloadRepository(root,library,manager,inspector,profiles))
        lateinit var profile: dev.aerove.lite.ProfileSummary
        var endpoint: CoreEndpoint? = null
        var epoch = 1L
        val applied = AtomicInteger()
        val changed = AtomicInteger()
        var applying: suspend (PreparedProfile) -> Unit = {}
        val page = ProfileDetailWorkspace(profiles, resources, scope, { profile }, { endpoint }, { true }, {
            applied.incrementAndGet(); applying(it); ResourceApplyReceipt(it.generation)
        }, { changed.incrementAndGet() }, { epoch }, { OverrideMode.Standard }, managed)
        suspend fun load() {
            profile = profiles.importText("actual", """
                rule-providers:
                  one: {type: http, url: '${server.url("/payload")}', path: one.yaml, behavior: domain}
                proxy-groups: [{name: group, type: select, proxies: [DIRECT]}]
                rules: ['RULE-SET,one,DIRECT', 'MATCH,DIRECT']
            """.trimIndent())
            val prepared = profiles.prepare(profile.id)
            val file = File(root, "applied.yaml"); file.writeText(profiles.readConfig(profile.id))
            endpoint = CoreEndpoint(server.port, "fixture-private-secret-00000000000000", AppliedConfigSnapshot(profile.id, file), prepared.revision, prepared.generation)
            page.foreground(true); page.telemetryActive(true); page.visible(true)
            withTimeout(5000) { page.state.first { it.counts != null && it.providers.size == 2 && it.busy.isEmpty() && it.resources.isNotEmpty() } }
        }
        suspend fun close() { page.quiesce(); page.close(); scope.cancel(); root.deleteRecursively() }
    }
    private val updates = AtomicInteger()
    private fun dispatcher(onPut: (RecordedRequest) -> Unit = {}) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/payload") return MockResponse().setBody("payload:\n - '+.before.example'\n")
            if (request.method == "PUT") { updates.incrementAndGet(); onPut(request); return MockResponse().setResponseCode(204) }
            val proxy="""{"vehicleType":"HTTP","proxies":[{"name":"node","type":"Direct"}]}"""
            val rule="""{"vehicleType":"HTTP","behavior":"domain","format":"yaml","ruleCount":${updates.get()+1},"updatedAt":"2026-10-23T00:00:00Z"}"""
            return MockResponse().setBody(when(request.path){
                "/providers/proxies/nodes" -> proxy
                "/providers/rules/one" -> rule
                "/providers/proxies" -> "{\"providers\":{\"nodes\":$proxy}}"
                else -> "{\"providers\":{\"one\":$rule}}"
            })
        }
    }
    @Test fun actualAppliedFileCountsAndParallelProviderUpdatesPublishFreshReadback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val both = CountDownLatch(2)
            server.dispatcher = dispatcher { both.countDown(); check(both.await(3, TimeUnit.SECONDS)) }
            server.start(); val f = Fixture(server)
            try {
                f.load()
                assertEquals(ProfileConfigurationCounts(1, 0, 2), f.page.state.value.counts)
                assertEquals(1, f.page.state.value.providers.single { it.kind == ProviderKind.Proxy }.proxies.size)
                f.page.updateAll()!!.join()
                assertEquals(0L, both.count)
                assertEquals(2, updates.get())
                assertEquals(3L, f.page.state.value.providers.single { it.kind == ProviderKind.Rule }.ruleCount)
                assertTrue(f.page.state.value.busy.isEmpty()); assertTrue(f.page.state.value.failures.isEmpty())
                assertEquals(1, f.changed.get())
            } finally { f.close() }
        }
    }
    @Test fun reopeningSameRuntimeReusesCountsAndProvidersWithoutAnotherHttpRead() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(); server.start(); val f = Fixture(server)
            try {
                f.load(); val requests = server.requestCount
                val expected = f.page.state.value.counts
                assertTrue(File(f.root,"applied.yaml").delete())
                f.page.visible(false); f.page.foreground(true); f.page.visible(true)
                withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                assertEquals(requests,server.requestCount)
                assertEquals(expected,f.page.state.value.counts)
                assertFalse(f.page.state.value.failed)
            } finally { f.close() }
        }
    }
    @Test fun editorUsesOpaqueResourceCasAndWaitsForActualApplyAcrossEndpointReplacement() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(); server.start(); val f = Fixture(server)
            try {
                f.load(); val provider = f.page.state.value.providers.single { it.kind == ProviderKind.Rule }
                f.page.open(provider, true)
                val editor = withTimeout(5000) { f.page.state.first { it.document != null }.document!!.session }
                editor.open(); editor.contentChanged()
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                f.applying = { prepared ->
                    val file = File(f.root,"next-applied.yaml"); file.writeText(prepared.source)
                    f.endpoint = CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(f.profile.id,file),prepared.revision,prepared.generation)
                    f.page.endpointChanged(); entered.complete(Unit); release.await()
                }
                val save = async { editor.save { "payload:\n - '+.after.example'\n" } }
                entered.await(); assertFalse(save.isCompleted)
                release.complete(Unit); assertTrue(save.await())
                assertFalse(editor.state.value.dirty)
                assertTrue(f.profiles.resourceEditSnapshot(f.profile.id,"$.rule-providers.one").bytes.toString(Charsets.UTF_8).contains("after.example"))
                assertEquals(1, f.applied.get()); assertEquals(1, f.changed.get())
            } finally { f.close() }
        }
    }
    @Test fun staleEditorKeepsDraftAndNeverAppliesOverNewerResource() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(); server.start(); val f = Fixture(server)
            try {
                f.load(); f.page.open(f.page.state.value.providers.single { it.kind == ProviderKind.Rule }, true)
                val editor = withTimeout(5000) { f.page.state.first { it.document != null }.document!!.session }
                editor.open(); editor.contentChanged()
                val token = f.profiles.resourceEditSnapshot(f.profile.id,"$.rule-providers.one")
                "payload:\n - '+.newer.example'\n".byteInputStream().use { f.profiles.replaceResource(token,it) }
                assertFalse(editor.save { "payload:\n - '+.stale.example'\n" })
                assertTrue(editor.state.value.dirty); assertNotNull(editor.state.value.error)
                assertEquals(0,f.applied.get())
                assertTrue(f.profiles.resourceEditSnapshot(f.profile.id,token.key).bytes.toString(Charsets.UTF_8).contains("newer.example"))
            } finally { f.close() }
        }
    }
    @Test fun uploadAcknowledgementIsOnceAndMaintenanceJoinsLateOpenWithoutApplying() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(); server.start(); val f = Fixture(server)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var closes = 0
            try {
                f.load(); val provider = f.page.state.value.providers.single { it.kind == ProviderKind.Rule }
                f.page.requestUpload(provider)
                val request = withTimeout(5000) { f.page.state.first { it.upload != null }.upload!! }
                assertTrue(f.page.documentLaunched(request.id)); assertFalse(f.page.documentLaunched(request.id))
                f.page.documentResult(request.id) {
                    withContext(NonCancellable) { entered.complete(Unit); release.await() }
                    object : ByteArrayInputStream("payload:\n - '+.late.example'\n".toByteArray()) { override fun close() { closes++; super.close() } }
                }
                entered.await(); val stop = async(start = CoroutineStart.UNDISPATCHED) { f.page.quiesce() }
                assertFalse(stop.isCompleted); release.complete(Unit); stop.await()
                assertEquals(1, closes); assertEquals(0, f.applied.get())
                assertTrue(f.profiles.resourceEditSnapshot(f.profile.id,"$.rule-providers.one").bytes.toString(Charsets.UTF_8).contains("before.example"))
                f.page.documentResult(request.id) { error("Late result must not open") }
                assertNull(f.page.state.value.upload)
            } finally { release.complete(Unit); f.close() }
        }
    }
    @Test fun ordinaryCloseInvalidatesLateUploadSnapshotBeforeReopening() = runBlocking<Unit> {
        lateClose(upload = true)
    }
    @Test fun ordinaryCloseInvalidatesLateEditorSnapshotBeforeReopening() = runBlocking<Unit> {
        lateClose(upload = false)
    }
    private suspend fun lateClose(upload: Boolean) = coroutineScope {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(); server.start(); val f = Fixture(server)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            try {
                f.load();val provider=f.page.state.value.providers.single { it.kind==ProviderKind.Rule }
                val hold=async(Dispatchers.Default) { privateDomainTransaction(f.root) { entered.complete(Unit);release.await() } }
                entered.await()
                if(upload) f.page.requestUpload(provider) else f.page.open(provider,true)
                f.page.visible(false);release.complete(Unit);hold.await()
                withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                assertNull(f.page.state.value.upload);assertNull(f.page.state.value.document)
                f.page.visible(true)
                withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                assertNull(f.page.state.value.upload);assertNull(f.page.state.value.document)
            } finally { release.complete(Unit);f.close() }
        }
    }
    @Test fun hiddenEndpointChangesDoNotReadAndForegroundConsumesOnlyLatestRuntime() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher=dispatcher();server.start();val f=Fixture(server)
            try {
                f.load();val count=server.requestCount;f.page.foreground(false)
                for (index in 1..2) {
                    val file=File(f.root,"new-$index.yaml").also { it.writeText("rules: ['MATCH,DIRECT']") }
                    f.endpoint=CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(f.profile.id,file))
                    f.page.endpointChanged()
                    withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                    assertEquals(count,server.requestCount)
                }
                f.page.foreground(true)
                withTimeout(5000) { f.scope.coroutineContext[Job]!!.children.toList().joinAll() }
                assertEquals(count+2,server.requestCount);assertEquals(1,f.page.state.value.counts!!.rules)
            } finally { f.close() }
        }
    }
    @Test fun managedFileSyncDownloadsNewPayloadBeforeApplyAndCorePutReadback() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var content="before.example";val puts=AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/payload") return MockResponse().setBody("payload:\n - '+.$content'\n")
                if(request.method=="PUT") { puts.incrementAndGet();return MockResponse().setResponseCode(204) }
                val proxy="""{"vehicleType":"File","proxies":[{"name":"node","type":"Direct"}]}"""
                val rule="""{"vehicleType":"File","behavior":"domain","format":"yaml","ruleCount":${puts.get()+1}}"""
                return MockResponse().setBody(when(request.path){
                    "/providers/proxies/nodes" -> proxy
                    "/providers/rules/one" -> rule
                    "/providers/proxies" -> "{\"providers\":{\"nodes\":$proxy}}"
                    else -> "{\"providers\":{\"one\":$rule}}"
                })
            }}
            server.start();val f=Fixture(server)
            try {
                f.load();val old=f.endpoint;content="after.example"
                f.applying={prepared ->
                    assertTrue(f.resources.preview(f.profile.id,"$.rule-providers.one").contains(content))
                    val file=File(f.root,"after.yaml").also{it.writeText(prepared.yaml)}
                    f.endpoint=CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(f.profile.id,file),prepared.revision,prepared.generation)
                    f.page.endpointChanged()
                }
                val provider=f.page.state.value.providers.single{it.kind==ProviderKind.Rule}
                f.page.update(provider)
                withTimeout(5000){f.scope.coroutineContext[Job]!!.children.toList().joinAll()}
                assertNotSame(old,f.endpoint);assertEquals(1,f.applied.get());assertEquals(1,puts.get())
                assertTrue(f.resources.preview(f.profile.id,"$.rule-providers.one").contains("after.example"))
                assertEquals(2L,f.page.state.value.providers.single{it.kind==ProviderKind.Rule}.ruleCount)
                assertFalse(f.page.state.value.failed)
                content="header.example";f.page.updateAll()!!.join()
                assertEquals(2,f.applied.get());assertEquals(3,puts.get())
                assertTrue(f.resources.preview(f.profile.id,"$.rule-providers.one").contains("header.example"))
            } finally { f.close() }
        }
    }

    @Test fun globalRemoteRuleFileSyncUsesSharedFacadeAndPreservesStableId() = runBlocking<Unit> {
        MockWebServer().use { server ->
            var content="before.example"
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody(when(request.path){
                "/payload" -> "payload:\n - '+.before.example'\n"
                "/global" -> "$content\n"
                "/providers/proxies" -> """{"providers":{"nodes":{"vehicleType":"File","proxies":[]}}}"""
                "/providers/proxies/nodes" -> """{"vehicleType":"File","proxies":[]}"""
                "/providers/rules/one" -> """{"vehicleType":"File","behavior":"domain","format":"text","ruleCount":1}"""
                else -> """{"providers":{"one":{"vehicleType":"File","behavior":"domain","format":"text","ruleCount":1}}}"""
            })}
            server.start();val f=Fixture(server)
            try {
                f.load()
                val facade=GlobalRulePayloadRepository(f.root,f.library,f.manager,f.inspector,f.profiles)
                val imported=facade.importUrl(f.library.snapshot(),"global",server.url("/global").toString())
                val entry=imported.resources.ruleProviders.single();val id=checkNotNull(entry.id)
                f.profile=f.profiles.importText("library", dev.aerove.lite.profilebuilder.BuilderResourceBindings(ruleProviders=mapOf("one" to id)).header() + """
                    rule-providers:
                      one: {type: inline, payload: [before.example], behavior: domain}
                    rules: ['RULE-SET,one,DIRECT', 'MATCH,DIRECT']
                """.trimIndent())
                val first=f.profiles.prepare(f.profile.id)
                val file=File(f.root,"global-applied.yaml").also{it.writeText(first.yaml)}
                f.endpoint=CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(f.profile.id,file),first.revision,first.generation)
                f.page.endpointChanged();withTimeout(5000){f.scope.coroutineContext[Job]!!.children.toList().joinAll()}
                assertEquals(server.url("/global").toString(),f.page.state.value.resources.single().url)
                assertTrue(f.page.state.value.resources.single().localPath!!.startsWith(".library/"))
                content="after.example"
                f.applying={prepared ->
                    assertTrue(f.resources.preview(f.profile.id,"$.rule-providers.one").contains("after.example"))
                    file.writeText(prepared.yaml)
                    f.endpoint=CoreEndpoint(server.port,"fixture-private-secret-00000000000000",AppliedConfigSnapshot(f.profile.id,file),prepared.revision,prepared.generation)
                    f.page.endpointChanged()
                }
                f.page.update(f.page.state.value.providers.single{it.kind==ProviderKind.Rule})
                withTimeout(5000){f.scope.coroutineContext[Job]!!.children.toList().joinAll()}
                assertEquals(1,f.applied.get());assertFalse(f.page.state.value.failed)
                assertEquals(id,f.library.snapshot().resources.ruleProviders.single().id)
                assertTrue(f.resources.preview(f.profile.id,"$.rule-providers.one").contains("after.example"))
            } finally { f.close() }
        }
    }

    @Test fun realWsLoadedEventWaitsWhileCoveredThenReadsOneProviderAndAcknowledgesReplay()=runBlocking<Unit>{
        MockWebServer().use { server->
            var revision="1"
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
                if(request.path=="/payload")return MockResponse().setBody("payload:\n - '+.before.example'\n")
                if(request.path=="/aerove/telemetry")return MockResponse().withWebSocketUpgrade(object:okhttp3.WebSocketListener(){
                    override fun onOpen(webSocket:okhttp3.WebSocket,response:okhttp3.Response){webSocket.send("""{"type":"providersLoaded","providers":[{"kind":"rule","name":"one","revision":"2","phase":"update","changed":false}]}""")}
                })
                val proxy="""{"vehicleType":"HTTP","proxies":[],"revision":"1"}"""
                val rule="""{"vehicleType":"HTTP","behavior":"domain","format":"yaml","ruleCount":1,"revision":"$revision"}"""
                return MockResponse().setBody(when(request.path){
                    "/providers/proxies/nodes"->proxy
                    "/providers/rules/one"->rule
                    "/providers/proxies"->"{\"providers\":{\"nodes\":$proxy}}"
                    else->"{\"providers\":{\"one\":$rule}}"
                })
            }}
            server.start();val f=Fixture(server)
            try{
                f.load();val before=server.requestCount;f.page.telemetryActive(false);revision="2"
                val event=CoreApiClient(ControlEndpoint(server.port,"fixture-private-secret-00000000000000")).use { client->
                    withTimeout(5000){client.telemetry().first{it is dev.aerove.lite.telemetry.CoreTelemetry.ProvidersLoaded}}
                }
                f.page.providersLoaded(f.endpoint!!,event);delay(600)
                assertEquals(before+1,server.requestCount)
                f.page.telemetryActive(true)
                withTimeout(5000){f.page.state.first{state->state.providers.any{it.kind==ProviderKind.Rule&&it.revision=="2"}}}
                assertEquals(before+2,server.requestCount)
                f.page.providersLoaded(f.endpoint!!,event);delay(600);assertEquals(before+2,server.requestCount)
            }finally{f.close()}
        }
    }

}
