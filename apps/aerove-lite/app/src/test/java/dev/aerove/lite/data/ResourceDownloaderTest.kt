package dev.aerove.lite.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResourceDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer()
    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    @Test fun redirectsDownloadRealBytesAndRecordActualHashAndUnversionedAgent() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/payload"))
        server.enqueue(MockResponse().setBody("resource-body"))
        val target = File(temporary.root, "resource")
        val digest = ResourceDownloader().download(server.url("/redirect").toString(), target, 1024)
        assertEquals("resource-body", target.readText())
        assertEquals(sha256(target), digest.sha256)
        assertEquals(13L, digest.bytes)
        assertEquals("AEROVE-Lite", server.takeRequest().getHeader("User-Agent"))
        assertEquals("/payload", server.takeRequest().path)
    }

    @Test fun declaredAndChunkedOversizedBodiesLeaveNoPartialResource() = runBlocking<Unit> {
        val target = File(temporary.root, "resource")
        server.enqueue(MockResponse().setBody("12345"))
        assertThrows(IOException::class.java) { runBlocking { ResourceDownloader().download(server.url("/known").toString(), target, 4) } }
        assertFalse(target.exists())
        server.enqueue(MockResponse().setChunkedBody("12345", 2))
        assertThrows(IOException::class.java) { runBlocking { ResourceDownloader().download(server.url("/chunked").toString(), target, 4) } }
        assertFalse(target.exists())
    }

    @Test fun httpsDowngradeIsRejectedBeforeAnyCleartextRequest() = runBlocking<Unit> {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Redirect")
                .header("Location", "http://example.com/unsafe").body("".toResponseBody()).build()
        }.build()
        val target = File(temporary.root, "resource")
        val error = assertThrows(IOException::class.java) {
            runBlocking { ResourceDownloader(client).download("https://example.com/private", target, 1024) }
        }
        assertEquals("Resource HTTPS downgrade refused", error.message)
        assertEquals(1, requests)
        assertFalse(target.exists())
    }

    @Test fun httpFailureDoesNotIncludeSubscriptionSecretsOrWriteOutput() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private-response"))
        val target = File(temporary.root, "resource")
        val error = assertThrows(IOException::class.java) {
            runBlocking { ResourceDownloader().download(server.url("/resource?token=do-not-log").toString(), target, 1024) }
        }
        assertEquals("Resource HTTP 403", error.message)
        assertFalse(target.exists())
    }
}
