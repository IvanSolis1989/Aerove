package dev.aerove.lite.integration
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.providers.*
import dev.aerove.lite.telemetry.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProviderLoadedRefreshTest {
    private class Fixture {
        val server=MockWebServer();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val revisions=ConcurrentHashMap<String,String>().apply{put("one","1");put("two","1")}
        val published=Channel<List<RuntimeProvider>>(Channel.UNLIMITED)
        val errors=Channel<Exception>(Channel.UNLIMITED)
        var cache=true
        val api:ProviderManagementRepository
        val refresh:ProviderLoadedRefresh
        init {
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                fun row(name:String)="""{"vehicleType":"HTTP","format":"yaml","behavior":"domain","proxies":[],"revision":"${revisions.getValue(name)}"}"""
                val names=if(request.path!!.contains("/proxies")) listOf("one") else listOf("two")
                val name=request.path!!.substringAfterLast('/')
                return MockResponse().setBody(if(name in names) row(name) else "{\"providers\":{${names.joinToString(","){"\"$it\":"+row(it)}}}}")
            }}
            server.start();api=ProviderManagementRepository(ControlEndpoint(server.port,"fixture-private-secret-00000000000000"))
            refresh=ProviderLoadedRefresh(scope,{cache},{kind,name->api.read(kind,name)},{api.snapshot()},
                {rows,_->published.trySend(rows)},{errors.trySend(it)})
        }
        suspend fun close(){refresh.close();scope.cancel();api.close();server.close()}
    }
    private fun event(name:String,rev:ULong)=ProviderLoaded(if(name=="one")ProviderKind.Proxy else ProviderKind.Rule,name,rev,ProviderLoadPhase.Update,false)
    @Test fun namedReadIsDebouncedAndSameActiveAndReplayDoNotReadAgain()=runBlocking<Unit>{
        val f=Fixture();val owner=Any()
        try{
            f.refresh.session(owner);f.refresh.active(true);f.refresh.accept(owner,listOf(event("one",1uL)))
            repeat(5){f.refresh.active(true)};delay(200);assertEquals(0,f.server.requestCount)
            assertEquals("1",withTimeout(3000){f.published.receive()}.single().revision)
            assertEquals(1,f.server.requestCount);assertEquals("/providers/proxies/one",f.server.takeRequest().path)
            f.refresh.accept(owner,listOf(event("one",1uL)));delay(600);assertEquals(1,f.server.requestCount)
        }finally{f.close()}
    }
    @Test fun hiddenMultipleNamesArePendingUntilOneListReadOnResume()=runBlocking<Unit>{
        val f=Fixture();val owner=Any()
        try{
            f.refresh.session(owner);f.refresh.accept(owner,listOf(event("one",1uL),event("two",1uL)))
            delay(600);assertEquals(0,f.server.requestCount);f.refresh.active(true)
            assertEquals(2,withTimeout(3000){f.published.receive()}.size);assertEquals(2,f.server.requestCount)
            assertEquals(setOf("/providers/proxies","/providers/rules"),setOf(f.server.takeRequest().path,f.server.takeRequest().path))
        }finally{f.close()}
    }
    @Test fun actualManualGetAcksLateEventWhileHigherAutomaticRevisionRemainsPending()=runBlocking<Unit>{
        val f=Fixture();val owner=Any()
        try{
            f.revisions["one"]="7";f.refresh.session(owner);f.refresh.active(true)
            val actual=f.api.read(ProviderKind.Proxy,"one");f.refresh.acknowledge(owner,listOf(actual))
            f.refresh.accept(owner,listOf(event("one",7uL)));delay(600);assertEquals(1,f.server.requestCount)
            f.refresh.manualStarted(owner);f.refresh.accept(owner,listOf(event("one",8uL)))
            f.refresh.acknowledge(owner,listOf(actual));delay(600);assertEquals(1,f.server.requestCount)
            f.revisions["one"]="8";f.refresh.manualFinished(owner)
            assertEquals("8",withTimeout(3000){f.published.receive()}.single().revision);assertEquals(2,f.server.requestCount)
        }finally{f.close()}
    }
    @Test fun runtimeReplacementDropsOldPendingSeenAndLateEvents()=runBlocking<Unit>{
        val f=Fixture();val old=Any();val latest=Any()
        try{
            f.refresh.session(old);f.refresh.acknowledge(old,listOf(f.api.read(ProviderKind.Proxy,"one")))
            f.refresh.accept(old,listOf(event("two",9uL)));f.refresh.session(latest);f.refresh.active(true)
            f.refresh.accept(old,listOf(event("two",10uL)));delay(600);assertEquals(1,f.server.requestCount)
            f.refresh.accept(latest,listOf(event("one",1uL)))
            assertEquals("1",withTimeout(3000){f.published.receive()}.single().revision);assertEquals(2,f.server.requestCount)
        }finally{f.close()}
    }    @Test fun groupsChangedNeverInvalidatesProviderMetadata()=runBlocking<Unit>{
        val f=Fixture();val owner=Any()
        try{f.refresh.session(owner);f.refresh.active(true)
            f.refresh.accept(owner,CoreTelemetry.GroupsChanged(7uL));delay(600)
            assertEquals(0,f.server.requestCount)
        }finally{f.close()}
    }
    @Test fun newerEventDuring409ReadGetsOneFreshDrainWithoutRetryingFailedRevision()=runBlocking<Unit>{
        val f=Fixture();val owner=Any();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val original=f.server.dispatcher;var first=true
        f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(first){first=false;entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return MockResponse().setResponseCode(409)}
            return original.dispatch(request)
        }}
        try{f.refresh.session(owner);f.refresh.active(true);f.refresh.accept(owner,listOf(event("one",7uL)))
            assertTrue(withContext(Dispatchers.IO){entered.await(3,java.util.concurrent.TimeUnit.SECONDS)})
            f.revisions["one"]="8";f.refresh.accept(owner,listOf(event("one",8uL)));release.countDown()
            withTimeout(3000){f.errors.receive()}
            assertEquals("8",withTimeout(3000){f.published.receive()}.single().revision)
            assertEquals(2,f.server.requestCount)
        }finally{release.countDown();f.close()}
    }
    @Test fun failedRevisionReplayDoesNotRetryUntilExplicitForegroundBoundary()=runBlocking<Unit>{
        val f=Fixture();val owner=Any()
        f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setResponseCode(409)}
        try{f.refresh.session(owner);f.refresh.active(true);f.refresh.accept(owner,listOf(event("one",7uL)))
            withTimeout(3000){f.errors.receive()}
            withTimeout(3000){f.scope.coroutineContext[Job]!!.children.toList().joinAll()}
            repeat(3){f.refresh.accept(owner,listOf(event("one",7uL)))}
            delay(700);assertEquals(1,f.server.requestCount)
            f.refresh.active(false);f.refresh.active(true)
            withTimeout(3000){f.errors.receive()};assertEquals(2,f.server.requestCount)
        }finally{f.close()}
    }
    @Test fun lateOlderAutomaticReadCannotOverwriteNewerActualManualReceipt()=runBlocking<Unit>{
        val f=Fixture();val owner=Any();val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val original=f.server.dispatcher;var first=true
        f.server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(first){first=false;val old=original.dispatch(request);entered.countDown();check(release.await(3,java.util.concurrent.TimeUnit.SECONDS));return old}
            return original.dispatch(request)
        }}
        try{f.revisions["one"]="5";f.refresh.session(owner);f.refresh.active(true);f.refresh.accept(owner,listOf(event("one",5uL)))
            assertTrue(withContext(Dispatchers.IO){entered.await(3,java.util.concurrent.TimeUnit.SECONDS)})
            f.refresh.manualStarted(owner);f.revisions["one"]="6"
            val actual=f.api.read(ProviderKind.Proxy,"one");assertEquals("6",actual.revision)
            f.refresh.acknowledge(owner,listOf(actual));f.refresh.manualFinished(owner);release.countDown()
            withTimeout(3000){f.scope.coroutineContext[Job]!!.children.toList().joinAll()}
            assertTrue(f.published.tryReceive().isFailure);assertEquals(2,f.server.requestCount)
        }finally{release.countDown();f.close()}
    }

}
