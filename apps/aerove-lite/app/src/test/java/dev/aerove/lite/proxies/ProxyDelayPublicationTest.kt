package dev.aerove.lite.proxies

import dev.aerove.lite.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProxyDelayPublicationTest {
    @Test fun pendingIsImmediateResultsPublishAt300msAndCompletionFlushesImmediately() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse =
                MockResponse().setBody("{\"delay\":42}").apply{if(request.path!!.startsWith("/proxies/B/"))setBodyDelay(800,TimeUnit.MILLISECONDS)}}
            server.start();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            try {CoreApiClient(ControlEndpoint(server.port,"0123456789abcdef0123456789abcdef")).use { core ->
                ProxyPresentationDelayTests(core,scope).use { feature ->
                    val a=ProxyDelayTarget("A","https://example.com/check");val b=a.copy(proxy="B")
                    val job=feature.start(listOf(a,b));assertEquals(setOf(a,b),feature.state.value.pending)
                    assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));assertNotNull(server.takeRequest(2,TimeUnit.SECONDS))
                    delay(100);assertTrue(feature.state.value.results.isEmpty())
                    withTimeout(700){while(a !in feature.state.value.results)delay(5)}
                    assertEquals(setOf(b),feature.state.value.running);assertEquals(setOf(a,b),feature.state.value.pending)
                    withTimeout(3000){job.join()};assertEquals(mapOf(a to 42,b to 42),feature.state.value.results)
                    assertTrue(feature.state.value.pending.isEmpty());assertTrue(feature.state.value.running.isEmpty())
                }
            }}finally{scope.cancel()}
        }
    }

    @Test fun repeatedCancellationKeepsTheOldOwnerTailUntilItHasActuallyJoined() = runBlocking<Unit> {
        MockWebServer().use{server->server.start();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val starts=java.util.concurrent.atomic.AtomicInteger()
            try{CoreApiClient(ControlEndpoint(server.port,"0123456789abcdef0123456789abcdef")).use{core->
                ProxyPresentationDelayTests(core,scope,{
                    if(starts.incrementAndGet()==1)withContext(NonCancellable){entered.complete(Unit);release.await()}
                }).use{feature->
                    val target=ProxyDelayTarget("A","https://example.com/check")
                    feature.start(listOf(target));withTimeout(3000){entered.await()}
                    feature.cancel();feature.start(listOf(target));delay(30);feature.cancel()
                    server.enqueue(MockResponse().setBody("{\"delay\":7}"));val latest=feature.start(listOf(target))
                    delay(100);assertEquals(0,server.requestCount)
                    release.complete(Unit);withTimeout(3000){latest.join()}
                    assertEquals(1,server.requestCount);assertEquals(7,feature.state.value.results[target])
                }
            }}finally{release.complete(Unit);scope.cancel()}
        }
    }
}
