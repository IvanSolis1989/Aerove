package dev.aerove.lite.vpn

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MeowAndroidHostTest {
    @Test fun capturedDnsCopiesOnePropertiesGenerationAndRejectsInvalidIdentity() {
        val addresses=mutableListOf("192.0.2.53","2001:db8::53")
        val snapshot=capturedMeowDns("wlan0",addresses)
        addresses.clear()
        assertEquals("wlan0",snapshot.defaultInterface)
        assertEquals(listOf("192.0.2.53","2001:db8::53"),snapshot.interfaces["wlan0"])
        assertThrows(IllegalStateException::class.java){capturedMeowDns(null,listOf("192.0.2.53"))}
        assertThrows(IllegalArgumentException::class.java){capturedMeowDns("wlan0",listOf("dns.example"))}
    }
    @Test fun cancellationAtTimeoutReturnStillExposesOwnedLockToCleanup()=runBlocking<Unit>{
        val mutex=Mutex();val owner=Any();var acquired=false;var cleaned=false
        val cancelAfterLock=object:Mutex by mutex {
            override suspend fun lock(owner:Any?) {
                mutex.lock(owner)
                currentCoroutineContext().cancel()
            }
        }
        val worker=launch {
            try {
                acquireMeowLifecycleLock(cancelAfterLock,owner) { acquired=true }
            } finally {
                if(acquired){assertTrue(mutex.holdsLock(owner));mutex.unlock(owner);cleaned=true}
            }
        }
        worker.join();assertTrue(worker.isCancelled);assertTrue(acquired);assertTrue(cleaned)
        assertTrue(mutex.tryLock(owner));mutex.unlock(owner)
    }
    @Test fun privateControlUsesExactEndpointAndBearerWithoutFollowingRedirects()=runBlocking<Unit>{
        val server=MockWebServer();val other=MockWebServer();server.start();other.start()
        val endpoint=CoreEndpoint(server.port,"0123456789abcdef0123456789abcdef")
        try {
            server.enqueue(MockResponse().setBody("{\"version\":\"candidate\"}"))
            verifyMeowControl(endpoint)
            val request=server.takeRequest();assertEquals("/version",request.path)
            assertEquals("Bearer ${endpoint.secret}",request.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location",other.url("/wrong")))
            try{verifyMeowControl(endpoint);fail("redirect must not establish readiness")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("not ready"))}
            assertEquals(0,other.requestCount)
            server.enqueue(MockResponse().setResponseCode(401))
            try{verifyMeowControl(endpoint);fail("unauthenticated response must fail")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("not ready"))}
            assertEquals(3,server.requestCount)
        } finally { server.close();other.close() }
    }
}
