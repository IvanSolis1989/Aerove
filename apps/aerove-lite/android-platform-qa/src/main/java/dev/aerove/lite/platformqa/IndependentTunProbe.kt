package dev.aerove.lite.platformqa

import android.net.ConnectivityManager
import android.net.RouteInfo
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.test.runner.AndroidJUnitRunner
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.vpn.CoreEndpoint
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Reuses NativeSmoke's bounded independent shell-UID pipe probe, without its legacy Activity. */
internal class IndependentTunProbe(private val instrumentation: AndroidJUnitRunner) {
    suspend fun capture(cm: ConnectivityManager, network: android.net.Network, settings: LiteSettings,
        endpoint: CoreEndpoint): JSONObject {
        val address = InetAddress.getByName("10.0.2.2")
        val properties = checkNotNull(cm.getLinkProperties(network))
        val matching = properties.routes.filter { it.matches(address) }
        val longest = matching.maxOfOrNull { it.destination.prefixLength }
        val routes = matching.filter { it.destination.prefixLength == longest }
        val result = JSONObject().put("accepted", false).put("destination", "10.0.2.2:18080")
            .put("bypassPrivateRoute", settings.network.bypassPrivateRoute).put("appFilterMode", settings.appFilterMode.name)
            .put("networkHandle", network.networkHandle.toString()).put("routes", org.json.JSONArray(routes.map { it.toString() }))
        if (settings.network.bypassPrivateRoute || settings.appFilterMode != AppFilterMode.All ||
            routes.isEmpty() || routes.any { it.type != RouteInfo.RTN_UNICAST }) {
            return result.put("blocked", "Controlled private fixture is not proved inside an all-UID unicast VPN route")
        }
        val uid = instrumentation.uiAutomation.executeShellCommand("id -u").use { pipe ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { it.readText().trim() }
        }
        check(uid == "2000" && uid.toInt() != instrumentation.targetContext.applicationInfo.uid)
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        fun counters(): Pair<Long,Long> = client.newCall(Request.Builder()
            .url("http://127.0.0.1:${endpoint.port}/connections")
            .header("Authorization", "Bearer ${endpoint.secret}").build()).execute().use { response ->
            check(response.isSuccessful)
            val body = checkNotNull(response.body).byteStream().use { input ->
                val bytes = input.readNBytes(1024 * 1024 + 1); check(bytes.size <= 1024 * 1024); bytes
            }
            val row = JSONObject(body.toString(Charsets.UTF_8))
            row.getLong("uploadTotal") to row.getLong("downloadTotal")
        }
        try {
        val before = withContext(Dispatchers.IO) { counters() }
        val token = UUID.randomUUID().toString()
        val reply = probeFixture(18080, token)
        check(reply.startsWith("HTTP/1.") && reply.contains(" 200 ") && reply.endsWith("AEROVE_LITE_FIXTURE:$token"))
        var after = before
        withTimeout(5_000) {
            while (after.first <= before.first || after.second <= before.second) {
                after = withContext(Dispatchers.IO) { counters() }
                if (after.first <= before.first || after.second <= before.second) delay(100)
            }
        }
        return result.put("accepted", true).put("probeUid", 2000).put("targetUid", instrumentation.targetContext.applicationInfo.uid)
            .put("token", token).put("uploadBefore", before.first).put("downloadBefore", before.second)
            .put("uploadAfter", after.first).put("downloadAfter", after.second)
            .put("responseSha256", MessageDigest.getInstance("SHA-256").digest(reply.toByteArray()).joinToString("") { "%02x".format(it) })
        } finally {
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    private suspend fun probeFixture(port: Int, token: String): String = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val pipes = instrumentation.uiAutomation.executeShellCommandRw(
            "toybox timeout -s KILL 10 toybox nc -w 10 10.0.2.2 $port")
        var failure: Throwable? = null
        try {
            for (pipe in pipes) {
                val fd = pipe.fileDescriptor
                Os.fcntlInt(fd, OsConstants.F_SETFL, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK)
            }
            val request = "GET /$token HTTP/1.0\r\nHost: fixture\r\n\r\n".toByteArray(Charsets.US_ASCII)
            var offset = 0
            while (offset < request.size) {
                awaitPipe(pipes[1].fileDescriptor, OsConstants.POLLOUT, deadline)
                try { offset += Os.write(pipes[1].fileDescriptor, request, offset, request.size - offset) }
                catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                }
            }
            pipes[1].close()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                awaitPipe(pipes[0].fileDescriptor, OsConstants.POLLIN, deadline)
                val count = try { Os.read(pipes[0].fileDescriptor, buffer, 0, buffer.size) }
                catch (error: ErrnoException) {
                    if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw error
                    continue
                }
                if (count == 0) break
                check(output.size() + count <= 64 * 1024) { "Controlled TUN fixture response exceeds 64 KiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            var closeFailure: Throwable? = null
            for (pipe in pipes) {
                try { pipe.close() } catch (error: Throwable) {
                    val original = failure ?: closeFailure
                    if (original == null) closeFailure = error else original.addSuppressed(error)
                }
            }
            closeFailure?.let { throw it }
        }
    }

    private suspend fun awaitPipe(fd: FileDescriptor, events: Int, deadline: Long) {
        val poll = StructPollfd().apply { this.fd = fd; this.events = events.toShort() }
        while (true) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Controlled TUN fixture request/response exceeded 10000ms" }
            val ready = try { Os.poll(arrayOf(poll), minOf(100L, remaining).toInt()) }
            catch (error: ErrnoException) {
                if (error.errno != OsConstants.EINTR) throw error
                continue
            }
            currentCoroutineContext().ensureActive()
            check(SystemClock.elapsedRealtime() < deadline) { "Controlled TUN fixture request/response exceeded 10000ms" }
            if (ready == 0) continue
            check(poll.revents.toInt() and OsConstants.POLLNVAL == 0) { "Controlled TUN fixture pipe is invalid" }
            if (poll.revents.toInt() and (events or OsConstants.POLLHUP or OsConstants.POLLERR) != 0) return
        }
    }

}
