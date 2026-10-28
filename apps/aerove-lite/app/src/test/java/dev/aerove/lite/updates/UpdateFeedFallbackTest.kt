package dev.aerove.lite.updates

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateFeedFallbackTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = UpdateIdentity("0.2.0-beta.1", 2, listOf("arm64-v8a"))
    private fun row(code: Long = 2) = JSONObject().put("tag_name", "lite-v0.2.0-beta.1+$code")
        .put("draft", false).put("prerelease", true).put("body", "- change")
        .put("assets", JSONArray(listOf(LITE_APK_NAME, "SHA256SUMS", "BUILD_INFO.json").map { name ->
            JSONObject().put("name", name).put("size", 123).put("browser_download_url",
                "https://github.com/$LITE_REPOSITORY/releases/download/lite-v0.2.0-beta.1+$code/$name")
        }))
    private inner class Transport : UpdateTransport {
        var status = 403
        var backup = JSONArray().put(row()).put(row(3)).toString()
        var calls = 0
        var backups = 0
        var partial = false
        var holdBackup = false
        val entered = CompletableDeferred<Unit>()
        override suspend fun releases(page: Int): String {
            calls++
            if (partial && page == 1) return JSONArray(List(100) { row(99) }).toString()
            throw UpdateHttpException(status)
        }
        override suspend fun officialReleases(): String {
            backups++
            entered.complete(Unit)
            if (holdBackup) awaitCancellation()
            return backup
        }
        override suspend fun metadata(url: String): ByteArray = error("No metadata in feed tests")
        override suspend fun download(url: String, destination: File, expectedBytes: Long, progress: (Long, Long) -> Unit) = error("No download in feed tests")
    }
    private fun repository(transport: UpdateTransport) = UpdateRepository(transport,
        temporary.newFolder(), UpdateApkVerifier { _, _ -> error("No APK verification in feed tests") })

    @Test fun deniedOrLimitedApiUsesOnlyOneCompleteOfficialFeed() = runBlocking {
        for (status in listOf(403, 429)) {
            val transport = Transport().apply { this.status = status }
            assertEquals(3L, repository(transport).check(identity)?.versionCode)
            assertEquals(1, transport.calls)
            assertEquals(1, transport.backups)
        }
    }
    @Test fun otherHttpFailuresDoNotUseBackup() = runBlocking {
        for (status in listOf(401, 404, 500)) {
            val transport = Transport().apply { this.status = status }
            try { repository(transport).check(identity); fail("Expected HTTP failure") }
            catch (failure: UpdateHttpException) { assertEquals(status, failure.status) }
            assertEquals(0, transport.backups)
        }
    }
    @Test fun emptyMalformedForeignOrStaleBackupPreservesOriginal403() = runBlocking {
        for (body in listOf("[]", "{}", "[42]", JSONArray().put(row(1)).toString(),
            JSONArray().put(row().put("tag_name", "v0.11.0-beta.11+2026102264")).toString())) {
            val transport = Transport().apply { backup = body }
            try { repository(transport).check(identity); fail("Uncovered build cannot be reported current") }
            catch (failure: UpdateHttpException) { assertEquals(403, failure.status) }
            assertEquals(1, transport.backups)
        }
    }
    @Test fun partialApiResultsAreDiscardedAndCoveredCurrentBuildIsValid() = runBlocking {
        val transport = Transport().apply { partial = true; backup = JSONArray().put(row()).toString() }
        assertNull(repository(transport).check(identity))
        assertEquals(2, transport.calls)
        assertEquals(1, transport.backups)
    }
    @Test fun httpTransportUsesTheFixedOfficialUrlWithoutAuthorizationOrRedirects() = runBlocking {
        val urls = mutableListOf<String>()
        var redirect = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            urls += request.url.toString()
            assertNull(request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (redirect) 302 else 200).message("fixture")
                .header("Location", "https://mirror.invalid/releases.json").body("[]".toResponseBody()).build()
        }.build()
        val transport = UpdateHttpClient(client)
        try {
            assertEquals("[]", transport.officialReleases())
            assertEquals(listOf(OFFICIAL_RELEASE_FEED), urls)
            try { transport.metadata(OFFICIAL_RELEASE_FEED); fail("Website must not become an APK/metadata asset host") }
            catch (_: IllegalArgumentException) { }
            assertEquals(1, urls.size)
            redirect = true
            try { transport.officialReleases(); fail("Official feed must not redirect to a mirror") }
            catch (_: IllegalArgumentException) { }
            assertEquals(listOf(OFFICIAL_RELEASE_FEED, OFFICIAL_RELEASE_FEED), urls)
        } finally {
            transport.close()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun fallbackCancellationStaysCancelledWithoutRetry() = runBlocking {
        val transport = Transport().apply { holdBackup = true }
        val pending = async(Dispatchers.IO) { repository(transport).check(identity) }
        withTimeout(2000) { transport.entered.await() }
        withTimeout(2000) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(1, transport.calls)
        assertEquals(1, transport.backups)
    }
}
