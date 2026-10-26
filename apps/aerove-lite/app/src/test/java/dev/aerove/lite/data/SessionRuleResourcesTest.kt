package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.*
import dev.aerove.lite.vpn.CoreEndpoint
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionRuleResourcesTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Parser : NativeRulePayloadInspector {
        override suspend fun inspect(path: String) = RulePayloadInspection("text", "domain", File(path).readLines().count { it.isNotBlank() })
        override suspend fun dump(path: String, behavior: String, format: String) = File(path).readText()
    }
    private class Fixture(val root: File) {
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root,"runtime"))
        val parser = Parser()
        val library = BuilderResourceLibrary(root)
        val overrides = OverrideRepository(root)
        val manager = ResourceBundleManager(File(root,"runtime"),validator, NativeResourceValidator { _,_,_,_->null },offlineRules=parser)
        val pipeline = ProfileSourcePipeline(library,OverridePipeline(overrides) { _, source -> source },{LiteSettings()})
        val profiles = ProfileRepository(root,validator,resources=manager,sourcePolicy=pipeline,globalResources=library)
        val payloads = GlobalRulePayloadRepository(root,library,manager,parser,profiles)
        lateinit var saved: BuilderResourceSnapshot
        lateinit var profileId: String
        suspend fun initialize() {
            saved=payloads.saveText(library.snapshot(),null,"one","first.example\n")
            val id=checkNotNull(saved.resources.ruleProviders.single().id)
            profileId=profiles.importText("Linked",BuilderResourceBindings(ruleProviders=mapOf("local" to id)).header()+
                "rule-providers:\n  local: {type: inline, behavior: domain, payload: [placeholder.example]}\nrules: ['RULE-SET,local,DIRECT','MATCH,DIRECT']\n").id
        }
        suspend fun update(text: String): PreparedProfile {
            saved=payloads.saveText(saved,checkNotNull(saved.resources.ruleProviders.single().id),"one",text)
            return profiles.prepareForStart(profileId)
        }
        fun file(prepared: PreparedProfile): File = File(((ConfigYaml.parse(prepared.yaml)["rule-providers"] as Map<*,*>)["local"] as Map<*,*>)["path"] as String)
        fun file(config: MeowRuntimeConfig): File = File(((ConfigYaml.parse(config.engineYaml)["rule-providers"] as Map<*,*>)["local"] as Map<*,*>)["path"] as String)
        fun configure(session: SessionRuleResources, initial: PreparedProfile, port: Int): MeowRuntimeConfig {
            val cache=File(root,"runtime/meow-cache/${System.nanoTime()}").apply { check(mkdirs()) }
            return session.configure(MeowRuntimeConfigComposer(File(root,"runtime"),cacheDirectory=cache).compose(initial,LiteSettings(),-1,port,
                "0123456789abcdef0123456789abcdef",NativeCore.RuntimeMode.Proxy))
        }
        fun endpoint(session: SessionRuleResources, config: MeowRuntimeConfig): CoreEndpoint = CoreEndpoint(config.privateControl.port,config.privateControl.secret,
            resourceLineage={val value=session.prepared;value.revision to value.generation})
    }
    @Test fun verifiedBytesAreCopiedOnceAndPutReadsNewFileBeforeAtomicLeaseLineagePublication() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize()
        val initial=f.profiles.prepareForStart(f.profileId);val original=f.file(initial)
        val initialLease=f.profiles.retain(initial)
        val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use { server ->
            server.start();val config=f.configure(session,initial,server.port);val running=f.file(config);val endpoint=f.endpoint(session,config)
            try {
                assertNotEquals(original.canonicalPath,running.canonicalPath)
                assertEquals("first.example\n",running.readText())
                server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                    assertEquals("PUT",request.method);assertEquals("/providers/rules/local",request.path)
                    assertEquals("second.example\n",running.readText())
                    assertEquals(initial.generation,endpoint.resourceGeneration)
                    return MockResponse().setResponseCode(204)
                }}
                val next=f.update("second.example\n")
                assertTrue(session.reload(next,endpoint){true})
                assertEquals(next.generation,endpoint.resourceGeneration)
                assertEquals(next.generation,session.configuration!!.resourceGeneration)
                assertEquals("first.example\n",original.readText())
                assertEquals("second.example\n",running.readText())
                assertEquals(1,server.requestCount)
                f.profiles.collectGarbage();assertTrue(f.file(next).isFile)
            } finally { session.closeAfterNativeJoin();f.profiles.release(initialLease) }
        }
        assertFalse(session.readRoot.exists())
        session.closeAfterNativeJoin()
    }
    @Test fun failedPutRestoresOwnedFilesKeepsOldGenerationAndForcesFullApplyThereafter() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use { server ->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            try {
                server.enqueue(MockResponse().setResponseCode(500));val next=f.update("second.example\n")
                assertFalse(session.reload(next,endpoint){true});assertEquals(initial.generation,endpoint.resourceGeneration)
                assertEquals("first.example\n",f.file(config).readText())
                assertFalse(session.reload(next,endpoint){true});assertEquals(1,server.requestCount)
            } finally {session.closeAfterNativeJoin();f.profiles.release(retained)}
        }
    }
    @Test fun supersededRequestCannotPublishNewGenerationAndCloseWaitsForReloadCleanup() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val current=AtomicBoolean(true)
        val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use { server ->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {entered.countDown();check(release.await(3,TimeUnit.SECONDS));return MockResponse().setResponseCode(204)}}
            try {
                val next=f.update("second.example\n")
                val reload=async(Dispatchers.Default){session.reload(next,endpoint){current.get()}}
                assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                current.set(false)
                val close=async(Dispatchers.Default){session.closeAfterNativeJoin()}
                yield();assertFalse(close.isCompleted)
                release.countDown();assertFalse(withTimeout(5000){reload.await()});withTimeout(5000){close.await()}
                assertEquals(initial.generation,endpoint.resourceGeneration);assertFalse(session.readRoot.exists())
            }finally{release.countDown();session.closeAfterNativeJoin();f.profiles.release(retained)}
        }
    }
    @Test fun unusedRulesDoNothingWhileInlineDefinitionAndOtherConfigurationChangesRequireApply() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use {server->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            try {
                f.saved=f.payloads.saveText(f.saved,null,"unused","unused.example\n")
                assertTrue(session.reload(f.profiles.prepareForStart(f.profileId),endpoint){true});assertEquals(0,server.requestCount)
                f.overrides.setGlobalRules(listOf(OverrideRule("rule","DOMAIN,new.example,DIRECT")))
                assertFalse(session.reload(f.profiles.prepareForStart(f.profileId),endpoint){true});assertEquals(0,server.requestCount)
            } finally {session.closeAfterNativeJoin();f.profiles.release(retained)}
        }
    }
    @Test fun inlineProviderDefinitionRequiresApplyAndNeverIssuesAFileRefresh() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use{server->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            try{
                f.saved=f.library.replace(f.saved,f.saved.resources.copy(ruleProviders=f.saved.resources.ruleProviders.map{
                    it.copy(fields=mapOf("type" to "inline","behavior" to "domain","payload" to listOf("inline.example")))
                }))
                assertFalse(session.reload(f.profiles.prepareForStart(f.profileId),endpoint){true})
                assertEquals(initial.generation,endpoint.resourceGeneration);assertEquals(0,server.requestCount)
            }finally{session.closeAfterNativeJoin();f.profiles.release(retained)}
        }
    }


    @Test fun cancelledPutJoinsHttpAndRollsBackBeforeReadRootCleanup() = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        MockWebServer().use{server->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            try{
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                val next=f.update("cancelled.example\n");val reload=async(Dispatchers.Default){session.reload(next,endpoint){true}}
                assertNotNull(withContext(Dispatchers.IO){server.takeRequest(3,TimeUnit.SECONDS)})
                withTimeout(3000){reload.cancelAndJoin()};assertTrue(reload.isCancelled)
                assertEquals(initial.generation,endpoint.resourceGeneration);assertEquals("first.example\n",f.file(config).readText())
                session.closeAfterNativeJoin();assertFalse(session.readRoot.exists())
            }finally{session.closeAfterNativeJoin();f.profiles.release(retained)}
        }
    }

    private fun rollbackIoFailure(releaseAlsoFails: Boolean) = runBlocking<Unit> {
        val f=Fixture(temporary.newFolder());f.initialize();val initial=f.profiles.prepareForStart(f.profileId)
        val retained=f.profiles.retain(initial);val session=SessionRuleResources.create(f.root,initial,f.profiles){true}
        val lock=File(f.root,"profiles/.lock")
        MockWebServer().use{server->server.start();val config=f.configure(session,initial,server.port);val endpoint=f.endpoint(session,config)
            val target=f.file(config)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                check(target.delete());check(target.mkdir());File(target,"occupied").writeText("prevent atomic rollback")
                if(releaseAlsoFails){check(lock.delete());check(lock.mkdir())}
                return MockResponse().setResponseCode(500)
            }}
            try{
                val next=f.update("new.example\n")
                val failure=runCatching{session.reload(next,endpoint){true}}.exceptionOrNull()
                assertTrue("The actual rollback move must fail",failure is java.io.IOException)
                if(releaseAlsoFails){
                    assertTrue("Release is attempted independently after rollback fails",failure!!.suppressed.any{it.message.orEmpty().contains(".lock")})
                    assertTrue(runCatching{session.closeAfterNativeJoin()}.exceptionOrNull() is java.io.IOException)
                    assertFalse("Directory cleanup is independent from the failed release",session.readRoot.exists())
                    check(lock.delete())
                }
                session.closeAfterNativeJoin();f.profiles.release(retained)
                assertTrue("Every acquired generation lease was released or retried",f.profiles.resetUserData{}.cleanupComplete)
            }finally{
                if(lock.isDirectory)check(lock.delete())
                session.closeAfterNativeJoin();f.profiles.release(retained)
            }
        }
    }
    @Test fun rollbackMoveIoFailureCannotSkipNewLeaseRelease() = rollbackIoFailure(false)
    @Test fun rollbackAndReleaseIoFailuresRetainOwnershipUntilCloseRetries() = rollbackIoFailure(true)
}
