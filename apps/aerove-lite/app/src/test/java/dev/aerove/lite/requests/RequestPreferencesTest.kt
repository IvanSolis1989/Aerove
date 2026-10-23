package dev.aerove.lite.requests

import dev.aerove.lite.data.ResourceDownloader
import dev.aerove.lite.data.SubscriptionDownloader
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savedLibraryRoundTripsEditsOrdersDeletesAndRejectsHeaderInjection() {
        val preferences = RequestPreferences().editUserAgent(null, "Custom/1")
            .copy(globalUserAgent = "Custom/1").editUserAgent("Custom/1", "Custom/2")
        val moved = preferences.moveUserAgent(2, 0)
        val file = temporary.newFile("preferences.json")
        file.writeText(RequestPreferencesJson.encode(moved).toString())
        val restored = RequestPreferencesJson.decode(JSONObject(file.readText()))
        assertEquals(moved, restored)
        assertEquals("Custom/2", restored.visibleUserAgents().first())
        assertNull(restored.deleteUserAgent("Custom/2").globalUserAgent)
        assertThrows(IllegalArgumentException::class.java) { restored.editUserAgent(null, "a\r\nb") }
        assertThrows(IllegalArgumentException::class.java) { restored.editUserAgent(null, "Custom/2") }
        assertTrue(RequestPreferencesJson.decode(null).checkCertificate)
    }

    @Test fun savedPolicyReachesSubscriptionRedirectAndResourceThenRestoresDefault() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val file = temporary.newFile("settings.json")
            file.writeText(RequestPreferencesJson.encode(RequestPreferences(globalUserAgent = "Saved/1")).toString())
            val source = RequestPolicySource { RequestPreferencesJson.decode(JSONObject(file.readText())) }
            val downloader = SubscriptionDownloader(source = source)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
            server.enqueue(MockResponse().setBody("mode: rule"))
            assertEquals("mode: rule", downloader.download(server.url("/source").toString()))
            repeat(2) { assertEquals("Saved/1", server.takeRequest().getHeader("User-Agent")) }
            server.enqueue(MockResponse().setBody("resource"))
            val target = File(temporary.root, "resource")
            ResourceDownloader(source = source).download(server.url("/resource").toString(), target, 100)
            assertEquals("Saved/1", server.takeRequest().getHeader("User-Agent"))
            assertEquals("resource", target.readText())
            file.writeText(RequestPreferencesJson.encode(RequestPreferences()).toString())
            server.enqueue(MockResponse().setBody("mode: global"))
            downloader.download(server.url("/restored").toString())
            assertEquals("AEROVE-Lite", server.takeRequest().getHeader("User-Agent"))
        }
    }

    @Test fun requestCancellationReleasesCallAndNextRequestRecovers() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = OkHttpClient()
            val downloader = SubscriptionDownloader(client)
            val job = async(Dispatchers.Default) { downloader.download(server.url("/cancel").toString()) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            server.enqueue(MockResponse().setBody("mode: rule"))
            assertEquals("mode: rule", downloader.download(server.url("/recover").toString()))
        }
    }

    @Test fun explicitTlsOptOutCannotContaminateSharedClientOrFollowingSecureRequest() = runBlocking<Unit> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            val shared = OkHttpClient()
            var preferences = RequestPreferences()
            val downloader = SubscriptionDownloader(shared, RequestPolicySource { preferences })
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) { runBlocking { downloader.download(server.url("/").toString()) } }
            preferences = preferences.copy(checkCertificate = false)
            server.enqueue(MockResponse().setBody("mode: rule"))
            assertEquals("mode: rule", downloader.download(server.url("/").toString()))
            preferences = preferences.copy(checkCertificate = true)
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) { runBlocking { downloader.download(server.url("/").toString()) } }
            assertThrows(javax.net.ssl.SSLHandshakeException::class.java) {
                shared.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute()
            }
        }
    }

    @Test fun cancelDuringResourceBodyRemovesPartialFileAndRecovers() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val bodyStarted = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
            }).build()
            val downloader = ResourceDownloader(client)
            server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 1, TimeUnit.SECONDS))
            val destination = File(temporary.root, "partial")
            val job = async(Dispatchers.Default) { downloader.download(server.url("/slow").toString(), destination, 1000) }
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            val began = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 2000)
            assertFalse(destination.exists())
            server.enqueue(MockResponse().setBody("restored"))
            downloader.download(server.url("/next").toString(), destination, 1000)
            assertEquals("restored", destination.readText())
        }
    }
}
