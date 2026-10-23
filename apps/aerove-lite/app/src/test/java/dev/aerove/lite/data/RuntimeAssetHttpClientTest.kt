package dev.aerove.lite.data

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.integration.runtimeAssetHttpClient
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.ProxyAuthentication
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.VpnSnapshot
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RuntimeAssetHttpClientTest {
    @Test fun actualMixedProxyAuthenticatesWhileParkedAndPrivateHostUseRealDirectHttp() {
        MockWebServer().use { origin -> MockWebServer().use { proxy ->
            origin.start(); proxy.start()
            val actual = NetworkSettings(ports = UserProxyPorts(proxy.port), authentication = ProxyAuthentication(true, "actual-user", "actual-password"))
            var snapshot = VpnSnapshot(phase = VpnPhase.Running, appliedNetwork = actual, listening = true)
            val client = okhttp3.OkHttpClient.Builder().dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) })
                .callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val url = "http://example.test:${origin.port}/icon"
                proxy.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic realm=asset"))
                proxy.enqueue(MockResponse().setBody("proxied"))
                assertEquals("proxied", runtimeAssetHttpClient(client, snapshot, url.toHttpUrl()).newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() })
                val challenge = proxy.takeRequest(1, TimeUnit.SECONDS)!!; val authenticated = proxy.takeRequest(1, TimeUnit.SECONDS)!!
                assertNull(challenge.getHeader("Proxy-Authorization"))
                assertEquals(Credentials.basic("actual-user", "actual-password"), authenticated.getHeader("Proxy-Authorization"))
                assertNull(authenticated.getHeader("Authorization"))
                snapshot = snapshot.copy(suspended = true, listening = false)
                origin.enqueue(MockResponse().setBody("parked-direct"))
                assertEquals("parked-direct", runtimeAssetHttpClient(client, snapshot, url.toHttpUrl()).newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() })
                assertNull(origin.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Proxy-Authorization"))
                snapshot = snapshot.copy(suspended = false, listening = true)
                origin.enqueue(MockResponse().setBody("private-direct"))
                val privateUrl = origin.url("/private").newBuilder().host("127.0.0.1").build()
                assertEquals("private-direct", runtimeAssetHttpClient(client, snapshot, privateUrl).newCall(Request.Builder().url(privateUrl).build()).execute().use { it.body!!.string() })
                assertEquals("/private", origin.takeRequest(1, TimeUnit.SECONDS)!!.path)
                assertEquals(2, proxy.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }
    @Test fun sameClientUsesLatestActuallyAppliedMixedPort() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(); second.start()
            var snapshot = VpnSnapshot(phase = VpnPhase.Running, listening = true,
                appliedNetwork = NetworkSettings(ports = UserProxyPorts(first.port)))
            val client = okhttp3.OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val request = Request.Builder().url("http://example.test/icon").build()
                first.enqueue(MockResponse().setBody("first")); second.enqueue(MockResponse().setBody("second"))
                assertEquals("first", runtimeAssetHttpClient(client, snapshot, request.url).newCall(request).execute().use { it.body!!.string() })
                snapshot = snapshot.copy(appliedNetwork = snapshot.appliedNetwork!!.copy(ports = UserProxyPorts(second.port)))
                assertEquals("second", runtimeAssetHttpClient(client, snapshot, request.url).newCall(request).execute().use { it.body!!.string() })
                assertEquals(1, first.requestCount); assertEquals(1, second.requestCount)
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        } }
    }
}
