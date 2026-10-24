package dev.aerove.lite.updates

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateHttpCancellationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val trustedUrl = "https://github.com/IvanSolis1989/Aerove/releases/download/lite-v0.2.0-beta.1+2/SHA256SUMS"
    private val payload = "0123456789".toByteArray()

    @Test fun metadataBodyStallCancelsItsSocketAndNextRequestCompletes() = stalledBody { transport, attempt ->
        transport.metadata(trustedUrl).also { if (attempt == 2) assertArrayEquals(payload, it) }
    }

    @Test fun apkBodyStallCancelsItsSocketAndNextDownloadCompletes() = stalledBody { transport, attempt ->
        val destination = File(temporary.root, "attempt-$attempt.apk")
        transport.download(trustedUrl.replace("SHA256SUMS", LITE_APK_NAME), destination, payload.size.toLong()) { _, _ -> }
        if (attempt == 2) assertArrayEquals(payload, destination.readBytes())
    }

    @Test fun immediateFeatureRetryIsNotBlockedByCancelledResponseCleanup() = stalledBody(featureFlow = true)

    private fun stalledBody(featureFlow: Boolean = false, action: suspend (UpdateHttpClient, Int) -> Unit = { _, _ -> }) = runBlocking<Unit> {
        val headersSent = CountDownLatch(1)
        val bodyReadStarted = CountDownLatch(1)
        val cancelledSocket = CountDownLatch(1)
        val bodyReads = AtomicInteger()
        val requests = AtomicInteger()
        val responses = AtomicInteger()
        val retryRequested = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val success = if (featureFlow) "[]".toByteArray() else payload
        val active = AtomicReference<Socket?>()
        val failure = AtomicReference<Throwable?>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread(name = "update-stalled-body-fixture", isDaemon = true) {
                try {
                    repeat(2) { attempt -> server.accept().use { socket ->
                        active.set(socket)
                        socket.soTimeout = 5000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        while (!input.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        if (attempt == 1) retryRequested.countDown()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: ${success.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        if (attempt == 0) {
                            headersSent.countDown()
                            assertEquals("Cancellation must close the actual HTTP socket", -1, input.read())
                            cancelledSocket.countDown()
                        } else { output.write(success); output.flush() }
                    } }
                } catch (error: Throwable) { failure.set(error) }
            }
            val client = OkHttpClient.Builder().addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = checkNotNull(response.body)
                val responseNumber = responses.incrementAndGet()
                val tracked = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (bodyReads.incrementAndGet() == 1) bodyReadStarted.countDown()
                        return super.read(sink, byteCount)
                    }
                    override fun close() {
                        if (featureFlow && responseNumber == 1) {
                            // Hold only post-cancel resource cleanup to expose the finishing-worker interleaving.
                            cleanupStarted.countDown()
                            check(releaseCleanup.await(5, TimeUnit.SECONDS))
                        }
                        super.close()
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source() = tracked
                }).build()
            }.addInterceptor { chain ->
                val request = chain.request()
                assertTrue(request.url.host in setOf("github.com", "api.github.com"))
                // Preserve production endpoint validation; redirect only this injected test client's wire to loopback.
                chain.proceed(request.newBuilder().url("http://127.0.0.1:${server.localPort}${request.url.encodedPath}").build())
            }.build()
            val transport = UpdateHttpClient(client)
            try {
                if (featureFlow) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val platform = object : UpdatePlatform {
                        override val packageName = LITE_PACKAGE
                        override suspend fun identity() = UpdateIdentity("0.1.0-beta.1", 1, listOf("arm64-v8a"))
                        override suspend fun automaticEnabled() = true
                        override suspend fun promptedTag(): String? = null
                        override suspend fun savePromptedTag(tag: String) = Unit
                        override suspend fun resetPromptedTag() = Unit
                        override suspend fun canInstall() = false
                        override suspend fun installUri(file: File): String = error("No installer in cancellation fixture")
                    }
                    val feature = UpdateFeature(UpdateRepository(transport, temporary.newFolder("feature-cache"), UpdateApkVerifier { _, _ -> }), platform, scope) { }
                    try {
                        feature.check()
                        assertTrue(headersSent.await(2, TimeUnit.SECONDS))
                        assertTrue(bodyReadStarted.await(2, TimeUnit.SECONDS))
                        feature.cancel()
                        assertTrue(cancelledSocket.await(2, TimeUnit.SECONDS))
                        assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
                        feature.check()
                        assertTrue("The new user request must not be dropped while cancelled response cleanup finishes", retryRequested.await(2, TimeUnit.SECONDS))
                        releaseCleanup.countDown()
                        withTimeout(2000) { feature.state.first { it.phase == UpdatePhase.Current } }
                        scope.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
                        assertEquals(UpdatePhase.Current, feature.state.value.phase)
                    } finally { releaseCleanup.countDown(); feature.closeAndJoin(); scope.cancel() }
                } else {
                    val first = async(Dispatchers.IO) { action(transport, 1) }
                    assertTrue(headersSent.await(2, TimeUnit.SECONDS))
                    assertTrue(bodyReadStarted.await(2, TimeUnit.SECONDS))
                    withTimeout(2000) { first.cancelAndJoin() }
                    assertTrue(first.isCancelled)
                    assertTrue(cancelledSocket.await(2, TimeUnit.SECONDS))
                    withTimeout(2000) { action(transport, 2) }
                }
                assertEquals(2, requests.get())
                failure.get()?.let { throw AssertionError("Loopback responder failed", it) }
            } finally {
                releaseCleanup.countDown()
                active.get()?.close()
                server.close()
                transport.close()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
                responder.join(2000)
            }
        }
    }

    @Test fun closingAnInjectedUpdateClientDoesNotEvictOrShutdownTheSharedClient() = runBlocking<Unit> {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("shared fixture"))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/shared")).build())
        }.build()
        val transport = UpdateHttpClient(client)
        try {
            assertEquals("shared fixture", transport.metadata(trustedUrl).toString(Charsets.UTF_8))
            assertEquals(1, client.connectionPool.connectionCount())
            transport.close(); transport.close()
            assertFalse(client.dispatcher.executorService.isShutdown)
            assertEquals(1, client.connectionPool.connectionCount())
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown()
            }
        }
    }

}
