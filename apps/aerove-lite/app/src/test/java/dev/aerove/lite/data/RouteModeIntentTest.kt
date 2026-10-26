package dev.aerove.lite.data

import dev.aerove.lite.ProxyMode
import dev.aerove.lite.integration.RouteModeIntent
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RouteModeIntentTest {
    @Test fun returningToSavedModeJoinsOldPatchAndOnlyLatestIntentPersists() = runBlocking<Unit> {
        MockWebServer().use{server->
            val entered=CountDownLatch(1);val release=CountDownLatch(1)
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.body.clone().readUtf8().contains("global")){entered.countDown();check(release.await(3,TimeUnit.SECONDS))}
                return MockResponse().setResponseCode(204)
            }};server.start()
            CoreApiClient(ControlEndpoint(server.port,"0123456789abcdef0123456789abcdef")).use{core->
                val owner=RouteModeIntent();val saved=mutableListOf<ProxyMode>();val first=checkNotNull(owner.reserve(true))
                val old=async(Dispatchers.Default){owner.apply(first,{true},{core.setMode(ProxyMode.Global)},{saved+=ProxyMode.Global})}
                try{
                    assertTrue(withContext(Dispatchers.IO){entered.await(3,TimeUnit.SECONDS)})
                    // A is still saved, so the second UI snapshot has no saved-field difference.
                    val second=checkNotNull(owner.reserve(false))
                    val latest=async(Dispatchers.Default){owner.apply(second,{true},{core.setMode(ProxyMode.Rule)},{saved+=ProxyMode.Rule})}
                    delay(80);assertEquals(1,server.requestCount);assertTrue(saved.isEmpty())
                    release.countDown();assertFalse(withTimeout(3000){old.await()});assertTrue(withTimeout(3000){latest.await()})
                    assertEquals(listOf(ProxyMode.Rule),saved);assertEquals(2,server.requestCount)
                    assertEquals("PATCH",server.takeRequest()!!.method);assertEquals("{\"mode\":\"rule\"}",server.takeRequest()!!.body.readUtf8())
                    assertNull(owner.reserve(false))
                }finally{release.countDown();old.cancelAndJoin()}
            }
        }
    }
    @Test fun failedOrUnavailableModeNeverPersistsAndDoesNotPoisonTheNextIntent() = runBlocking<Unit> {
        val owner=RouteModeIntent();var patches=0;var writes=0
        val unavailable=checkNotNull(owner.reserve(true))
        assertTrue(runCatching{owner.apply(unavailable,{false},{patches++},{writes++})}.exceptionOrNull() is CancellationException)
        assertEquals(0,patches);assertEquals(0,writes)
        val failed=checkNotNull(owner.reserve(true))
        assertTrue(runCatching{owner.apply(failed,{true},{error("HTTP failure")},{writes++})}.isFailure)
        val next=checkNotNull(owner.reserve(true));assertTrue(owner.apply(next,{true},{patches++},{writes++}))
        assertEquals(1,patches);assertEquals(1,writes)
    }
}
