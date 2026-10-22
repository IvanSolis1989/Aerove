package dev.aerove.lite.data

import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavBackupClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var http: OkHttpClient
    private val secret = "password-secret"

    @Before fun start() {
        val store = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/backup-localhost.p12")!!.use { store.load(it, "fixture-password".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "fixture-password".toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        server.useHttps(context.socketFactory, false)
        server.start(java.net.InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1)), 0)
        http = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .sslSocketFactory(context.socketFactory, trust.trustManagers.single() as X509TrustManager).build()
    }

    @After fun stop() { server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }

    private fun client(limit: Long = 256L * 1024 * 1024, transport: OkHttpClient = http) = WebDavBackupClient(
        WebDavBackupConfig(url("/dav/").toString(), "user", secret), transport, maxArchiveBytes = limit,
    )

    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()

    private fun request(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS) ?: error("Missing real HTTPS request")

    private fun listing(href: String = "/dav/AEROVE-Lite/aerove-lite.zip", length: String = "4"): String = """
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>/dav/AEROVE-Lite/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
          <d:response><d:href>$href</d:href><d:propstat><d:prop><d:getcontentlength>$length</d:getcontentlength><d:getetag>etag-1</d:getetag><d:getlastmodified>Thu, 22 Oct 2026 00:00:00 GMT</d:getlastmodified><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()

    @Test fun pingListUploadDownloadAndDeleteUseRealHttpsAndBoundCredentials() = runBlocking<Unit> {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(204))
        client.ping()
        assertEquals("OPTIONS", request().method)
        server.enqueue(MockResponse().setResponseCode(207).setBody(listing()))
        val entry = client.list().single()
        assertEquals("aerove-lite.zip", entry.name)
        assertEquals(4L, entry.bytes)
        assertEquals("etag-1", entry.etag)
        val list = request()
        assertEquals("PROPFIND", list.method)
        assertEquals("1", list.getHeader("Depth"))
        assertEquals(Credentials.basic("user", secret), list.getHeader("Authorization"))
        val source = temporary.newFile().apply { writeText("backup-body") }
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        client.upload(source, "named.zip", overwrite = false)
        assertEquals("MKCOL", request().method)
        val upload = request()
        assertEquals("/dav/AEROVE-Lite/named.zip", upload.path)
        assertEquals("*", upload.getHeader("If-None-Match"))
        assertEquals("backup-body", upload.body.readUtf8())
        server.enqueue(MockResponse().setBody("downloaded"))
        val destination = temporary.newFile().apply { writeText("old") }
        client.download(destination)
        assertEquals("downloaded", destination.readText())
        assertEquals("GET", request().method)
        server.enqueue(MockResponse().setResponseCode(204))
        client.delete("named.zip")
        assertEquals("DELETE", request().method)
    }

    @Test fun digestChallengeIsAnsweredWithTheActualMethodAndUri() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Digest realm=\"backup\", nonce=\"nonce-1\", qop=\"auth\", algorithm=MD5"))
        server.enqueue(MockResponse().setResponseCode(204))
        client().ping()
        assertTrue(request().getHeader("Authorization")!!.startsWith("Basic "))
        val second = request()
        val authorization = second.getHeader("Authorization")!!
        assertTrue(authorization.startsWith("Digest "))
        val values = Regex("(\\w+)=(?:\"([^\"]*)\"|([^, ]+))").findAll(authorization)
            .associate { it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3]) }
        fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).hex()
        val first = md5("user:backup:$secret")
        val last = md5("OPTIONS:/dav/")
        assertEquals(md5("$first:nonce-1:${values["nc"]}:${values["cnonce"]}:auth:$last"), values["response"])
        assertFalse(authorization.contains(secret))
    }

    @Test fun crossOriginAndDowngradeRedirectsNeverReceiveCredentials() = runBlocking<Unit> {
        for (location in listOf("https://localhost:1/stolen", "http://localhost:${server.port}/plain", url("/outside.zip").toString())) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", location))
            val target = temporary.newFile().apply { writeText("keep") }
            assertThrows(WebDavBackupException::class.java) { runBlocking { client().download(target) } }
            request()
            assertEquals("keep", target.readText())
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun sameOriginCollectionRedirectPreservesTheGet() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", url("/dav/AEROVE-Lite/other.zip")))
        server.enqueue(MockResponse().setBody("redirected"))
        val target = temporary.newFile()
        client().download(target)
        assertEquals("redirected", target.readText())
        assertEquals("GET", request().method)
        assertEquals("GET", request().method)
    }

    @Test fun invalidUrlsAndNamesAreRejectedWithoutMakingRequests() {
        for (url in listOf("http://example.com/dav", "https://user:secret@example.com/", "https://example.com/?token=secret",
            "https://example.com/#fragment", "https://example.com/a/../b", "https://example.com/a/%2e%2e/b", "https://example.com/a%2fb")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavBackupConfig(url, "user", secret) }
        }
        for (name in listOf("../backup", "a/b", "a\\b", "%2e%2e", "..", "C:backup", "a\nzip")) {
            assertThrows(IllegalArgumentException::class.java) { WebDavBackupConfig("https://example.com/", "user", secret, name) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun xxeForeignAndNestedListingEntriesAreRejected() = runBlocking<Unit> {
        val xxe = "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///secret'>]>" + listing().replace("etag-1", "&secret;")
        for (xml in listOf(xxe, listing("https://example.com/stolen.zip"), listing("/dav/outside.zip"), listing("/dav/AEROVE-Lite/nested/file.zip"), listing(length = "-1"))) {
            server.enqueue(MockResponse().setResponseCode(207).setBody(xml))
            assertThrows(WebDavBackupException::class.java) { runBlocking { client().list() } }
            request()
        }
    }

    @Test fun oversizedAndInterruptedDownloadsKeepTheDestinationAndRemoveStaging() = runBlocking<Unit> {
        val destination = temporary.newFile().apply { writeText("keep") }
        server.enqueue(MockResponse().setBody("too-long-content"))
        assertThrows(WebDavBackupException::class.java) { runBlocking { client(limit = 4).download(destination) } }
        request()
        server.enqueue(MockResponse().setChunkedBody("still-too-long", 2))
        assertThrows(WebDavBackupException::class.java) { runBlocking { client(limit = 4).download(destination) } }
        request()
        server.enqueue(MockResponse().setBody("x".repeat(10000)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThrows(WebDavBackupException::class.java) { runBlocking { client().download(destination) } }
        request()
        assertEquals("keep", destination.readText())
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".dav-") })
    }

    @Test fun cancellationClosesTheCallAndKeepsExistingFiles() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val destination = temporary.newFile().apply { writeText("keep") }
        val task = async { client().download(destination) }
        delay(250)
        task.cancelAndJoin()
        assertEquals("keep", destination.readText())
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".dav-") })
    }

    @Test fun cancellingAfterResponseHeadersClosesABlockedBodyRead() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("payload").setBodyDelay(2, TimeUnit.SECONDS))
        val destination = temporary.newFile().apply { writeText("keep") }
        val task = async { client().download(destination) }
        delay(250)
        kotlinx.coroutines.withTimeout(1000) { task.cancelAndJoin() }
        assertEquals("keep", destination.readText())
        assertTrue(temporary.root.listFiles()!!.none { it.name.startsWith(".dav-") })
    }

    @Test fun anonymousOptionsAndSameCollectionSlashRedirectAreSupported() = runBlocking<Unit> {
        val anonymous = WebDavBackupClient(WebDavBackupConfig(url("/dav/").toString(), "", ""), http)
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", url("/dav")))
        server.enqueue(MockResponse().setResponseCode(204))
        anonymous.ping()
        assertNull(request().getHeader("Authorization"))
        val redirected = request()
        assertEquals("OPTIONS", redirected.method)
        assertNull(redirected.getHeader("Authorization"))
    }

    @Test fun networkAndHttpErrorsDoNotLeakCredentialsOrServerBody() = runBlocking<Unit> {
        val transport = http.newBuilder().addInterceptor { throw IOException("url contains $secret") }.build()
        val failure = assertThrows(WebDavBackupException::class.java) { runBlocking { client(transport = transport).ping() } }
        assertFalse(failure.toString().contains(secret))
        assertNull(failure.cause)
        server.enqueue(MockResponse().setResponseCode(403).setBody(secret))
        val httpFailure = assertThrows(WebDavBackupException::class.java) { runBlocking { client().delete() } }
        assertEquals(403, httpFailure.statusCode)
        assertFalse(httpFailure.toString().contains(secret))
        assertFalse(WebDavBackupConfig("https://example.com/", "user", secret).toString().contains(secret))
    }
}
