package dev.aerove.lite.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MemorySnapshotTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test fun authenticatedGetReadsActualProcessRssAndConfiguredLimitInBytes() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"inuse":125829120,"oslimit":268435456}"""))
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                val snapshot = api.memorySnapshot()
                assertEquals(125829120uL, snapshot.processRssBytes)
                assertEquals(268435456uL, snapshot.configuredLimitBytes)
                val request = server.takeRequest(3, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("/memory", request.path)
                assertEquals("Bearer $secret", request.getHeader("Authorization"))
                assertEquals(0L, request.bodySize)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun nativeZeroSentinelsMeanUnavailableSamplingAndUnlimitedConfiguration() {
        val snapshot = decodeMemorySnapshot("""{"inuse":0,"oslimit":0}""")
        assertNull(snapshot.processRssBytes)
        assertNull(snapshot.configuredLimitBytes)
        assertEquals(42uL, decodeMemorySnapshot("""{"inuse":42,"oslimit":0}""").processRssBytes)
        assertEquals(1uL, decodeMemorySnapshot("""{"inuse":0,"oslimit":1}""").configuredLimitBytes)
    }

    @Test fun unsignedArm64CountersKeepExactValuesWithoutDoubleRounding() {
        val snapshot = decodeMemorySnapshot("""{"inuse":9007199254740993,"oslimit":18446744073709551615}""")
        assertEquals(9007199254740993uL, snapshot.processRssBytes)
        assertEquals(ULong.MAX_VALUE, snapshot.configuredLimitBytes)
    }

    @Test fun missingInvalidOrAllocationStyleFieldsAreNotPretendedToBeRss() = runBlocking<Unit> {
        val bad = listOf("", "[]", "{}", "{\"inuse\":42}", "{\"oslimit\":42}",
            """{"heapAlloc":42,"numGC":1}""", """{"inuse":1,"oslimit":0,"heapAlloc":2}""",
            """{"inuse":-1,"oslimit":0}""", """{"inuse":1,"oslimit":-1}""",
            """{"inuse":1.5,"oslimit":0}""", """{"inuse":"42","oslimit":0}""",
            """{"inuse":true,"oslimit":0}""", """{"inuse":null,"oslimit":0}""",
            """{"inuse":1,"oslimit":18446744073709551616}""", """{"inuse":01,"oslimit":0}""",
            """{"inuse":1,"oslimit":0} trailing""", """{"inuse":1,"inuse":2,"oslimit":0}""",
            """{"inuse":1,"oslimit":0,"extra":{"inuse":2}}""")
        MockWebServer().use { server ->
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                bad.forEach { body ->
                    server.enqueue(MockResponse().setBody(body))
                    assertThrows(body, IOException::class.java) { runBlocking { api.memorySnapshot() } }
                }
                assertEquals(bad.size, server.requestCount)
            }
        }
    }

    @Test fun memoryErrorsAndOversizedPayloadsAreReportedWithoutFallbackOrRetry() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"session_stopped"}"""))
            server.enqueue(MockResponse().setBody(" ".repeat(1025)))
            CoreApiClient(ControlEndpoint(server.port, secret)).use { api ->
                val failed = assertThrows(CoreApiException::class.java) { runBlocking { api.memorySnapshot() } }
                assertEquals(503, failed.statusCode)
                assertEquals("memory", failed.operation)
                assertThrows(IOException::class.java) { runBlocking { api.memorySnapshot() } }
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test fun explicitReadsNeverReuseHttpCacheOrCreateABackgroundSubscription() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("aerove-memory-cache").toFile()
        try {
            Cache(directory, 1024 * 1024).use { cache ->
                MockWebServer().use { server ->
                    listOf(42, 43).forEach { rss ->
                        server.enqueue(MockResponse().setHeader("Cache-Control", "public,max-age=3600")
                            .setBody("""{"inuse":$rss,"oslimit":0}"""))
                    }
                    val transport = OkHttpClient.Builder().cache(cache).build()
                    CoreApiClient(ControlEndpoint(server.port, secret), transport).use { api ->
                        assertEquals(42uL, api.memorySnapshot().processRssBytes)
                        assertEquals(43uL, api.memorySnapshot().processRssBytes)
                        assertEquals(2, server.requestCount)
                    }
                }
            }
        } finally { directory.listFiles()?.forEach(File::delete); directory.delete() }
    }
}
