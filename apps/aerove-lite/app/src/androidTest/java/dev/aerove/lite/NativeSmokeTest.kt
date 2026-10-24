package dev.aerove.lite

import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.vpn.MeowTunnelBackend
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val graph get() = context.applicationContext as LiteApplication

    @Test
    fun nativeParserRejectsUnknownConfiguration() {
        assertNotNull(MeowNativeCore.validate("unknown-aerove-field: true\n"))
        assertNull(MeowNativeCore.validate("mode: direct\nrules: ['MATCH,DIRECT']\n"))
    }

    @Test
    fun vpnForwardsOtherUidTrafficAndStopsWithoutLeavingAnInstance() {
        runBlocking<Unit> {
        val fixture = InstrumentationRegistry.getArguments().getString("fixtureUrl")
            ?: error("A controlled fixtureUrl is required for the TUN acceptance test")
        require(fixture.matches(Regex("http://10\\.0\\.2\\.2:[0-9]+")))
        val port = fixture.substringAfterLast(':').toInt()
        assertNull("Grant ACTIVATE_VPN to the development package before this test", VpnService.prepare(context))
        assertEquals("Do not take over an existing VPN session", VpnPhase.Stopped, graph.vpn.value.phase)
        check(!LiteVpnService.wasRequested(context)) { "Do not replace an existing VPN request" }
        val before = graph.profiles.snapshot()
        check(before.selectedId != null || before.profiles.isEmpty()) { "Cannot restore an unselected nonempty profile library" }
        val settings = graph.settings.read()
        var profileId: String? = null
        var activity: ActivityScenario<MainActivity>? = null
        var startRequested = false
        val failures = NativeSmokeFailures()
        val stage = AtomicReference("setup")
        fun event(name: String, failure: Throwable? = null) {
            stage.set(name)
            runCatching {
                val actual = graph.vpn.value
                val message = "elapsedNanos=${android.os.SystemClock.elapsedRealtimeNanos()} stage=$name phase=${actual.phase} error=${actual.error} endpointPort=${actual.endpoint?.port}"
                if (failure == null) android.util.Log.i("NativeSmoke", message)
                else android.util.Log.e("NativeSmoke", message, failure)
            }
        }
        val observation = launch(Dispatchers.IO) {
            graph.vpn.distinctUntilChangedBy { Triple(it.phase, it.error, it.endpoint?.port) }.collect { actual ->
                runCatching { android.util.Log.i("NativeSmoke",
                    "elapsedNanos=${android.os.SystemClock.elapsedRealtimeNanos()} stage=${stage.get()} phase=${actual.phase} error=${actual.error} endpointPort=${actual.endpoint?.port}") }
            }
        }
        val diagnosticSaveFailure = AtomicReference<Throwable?>(null)
        val diagnosticHits = java.util.concurrent.atomic.AtomicInteger()
        val diagnosticGeneration = AtomicReference<String?>(null)
        val testPid = android.os.Process.myPid()
        var startupFailed = false
        var startupDiagnostics: AutoCloseable? = null
        try {
            check(android.app.Application.getProcessName() == context.packageName) { "Startup diagnostics requires the target main process" }
            startupDiagnostics = MeowTunnelBackend.observeStartupFailures { diagnostic ->
                diagnosticHits.incrementAndGet()
                try {
                    val capturePid = android.os.Process.myPid()
                    check(capturePid == testPid && diagnostic.nativeGeneration > 0 && diagnostic.intentGeneration?.let { it > 0 } == true) { "Startup diagnostic owner identity is unavailable" }
                    diagnosticGeneration.set("${diagnostic.nativeGeneration}/${diagnostic.intentGeneration}/${diagnostic.coreEpoch}")
                    val directory = File(checkNotNull(context.getExternalFilesDir(null)), "captures")
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create startup diagnostics directory" }
                    val target = File(directory, "native-smoke-startup-${android.os.SystemClock.elapsedRealtimeNanos()}.txt")
                    val content = buildString {
                        appendLine("testPid=$testPid capturePid=$capturePid process=${android.app.Application.getProcessName()}")
                        appendLine("nativeGeneration=${diagnostic.nativeGeneration} intentGeneration=${diagnostic.intentGeneration} coreEpoch=${diagnostic.coreEpoch}")
                        appendLine(diagnostic.failure.stackTraceToString())
                        appendLine("nativeBeforeStop=" + diagnostic.nativeBeforeStop.fold({ it.toString() }, { it.stackTraceToString() }))
                        appendLine("nativeAfterStop=" + diagnostic.nativeAfterStop.fold({ it.toString() }, { it.stackTraceToString() }))
                    }
                    check(content.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Startup diagnostic exceeds 512 KiB" }
                    target.writeText(content)
                    android.util.Log.i("NativeSmoke", "startupDiagnostic=${target.name}")
                } catch (failure: Throwable) { diagnosticSaveFailure.compareAndSet(null, failure) }
            }
            event("import-profile")
            val profile = graph.profiles.importText("TUN test", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
            profileId = profile.id
            graph.profiles.select(profile.id)
            graph.settings.save(LiteSettings(mode = ProxyMode.Direct), vpnAuthorized = true)
            event("launch-activity")
            activity = ActivityScenario.launch(MainActivity::class.java)
            event("start-first")
            startRequested = true
            start()
            withTimeout(60_000) { graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed } }
                .also { check(it.phase == VpnPhase.Running) { it.error ?: "VPN did not become ready" } }
            event("running-first")
            val token = UUID.randomUUID().toString()
            event("cross-uid-traffic")
            val command = "printf 'GET /$token HTTP/1.0\\r\\nHost: fixture\\r\\n\\r\\n' | toybox nc -w 10 10.0.2.2 $port"
            val response = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            assertTrue("An independent shell UID must receive its unique fixture response through the TUN", response.contains("AEROVE_LITE_FIXTURE:$token"))
            event("private-controller")
            val endpoint = checkNotNull(graph.vpn.value.endpoint)
            val client = OkHttpClient()
            try {
                val controller = "http://127.0.0.1:${endpoint.port}"
                client.newCall(Request.Builder().url("$controller/proxies").build()).execute().use {
                    assertEquals("Controller data must require the per-session secret", 401, it.code)
                }
                client.newCall(Request.Builder().url("$controller/proxies")
                    .header("Authorization", "Bearer incorrect-session-secret").build()).execute().use {
                    assertEquals("Another session cannot control this instance", 401, it.code)
                }
                for (path in listOf("/restart", "/configs")) {
                    client.newCall(Request.Builder().url("$controller$path")
                        .header("Authorization", "Bearer ${endpoint.secret}")
                        .post("{}".toRequestBody()).build()).execute().use {
                        assertEquals("Native lifecycle changes belong to the VPN service", 409, it.code)
                    }
                }
                withTimeout(5000) {
                    while (true) {
                        val counters = client.newCall(Request.Builder().url("http://127.0.0.1:${endpoint.port}/connections")
                            .header("Authorization", "Bearer ${endpoint.secret}").build()).execute().use {
                            check(it.isSuccessful)
                            JSONObject(checkNotNull(it.body).string())
                        }
                        if (counters.getLong("uploadTotal") > 0 && counters.getLong("downloadTotal") > 0) break
                        delay(100)
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
            event("stop-first")
            stop()
            withTimeout(15_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            event("restart")
            start()
            withTimeout(60_000) { graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed } }
                .also { check(it.phase == VpnPhase.Running) { it.error ?: "VPN could not restart" } }
            event("running-second")
        } catch (failure: Throwable) {
            startupFailed = stage.get() in setOf("start-first", "restart")
            failures.record(failure)
            event("first-failure:${stage.get()}", failure)
        } finally {
            withContext(NonCancellable) {
                suspend fun cleanup(name: String, action: suspend () -> Unit) {
                    failures.attempt {
                        event("cleanup:$name")
                        try { withTimeout(15_000) { action() } }
                        catch (failure: Throwable) { event("cleanup-failed:$name", failure); throw failure }
                    }
                }
                if (startRequested) cleanup("stop") {
                    stop()
                    graph.vpn.first { it.phase == VpnPhase.Stopped }
                }
                cleanup("activity") { activity?.close() }
                cleanup("settings") { graph.settings.save(settings, vpnAuthorized = true) }
                cleanup("selection") { before.selectedId?.let { graph.profiles.select(it) } }
                cleanup("owned-profile") { profileId?.let { graph.profiles.delete(it) } }
                cleanup("readback") {
                    assertEquals("Original settings must be restored", settings, graph.settings.read())
                    assertEquals("Only the test profile may be removed", before, graph.profiles.snapshot())
                }
                cleanup("startup-diagnostics") {
                    startupDiagnostics?.close()
                    val directory = File(checkNotNull(context.getExternalFilesDir(null)), "captures")
                    check(directory.isDirectory || directory.mkdirs()) { "Cannot create startup diagnostics directory" }
                    File(directory, "native-smoke-observer-${android.os.SystemClock.elapsedRealtimeNanos()}.txt").writeText(
                        "testPid=$testPid process=${android.app.Application.getProcessName()} startupFailed=$startupFailed hits=${diagnosticHits.get()} generation=${diagnosticGeneration.get()}")
                    diagnosticSaveFailure.get()?.let { throw it }
                    check(!startupFailed || diagnosticHits.get() > 0) { "Startup diagnostic observer did not capture the actual failure" }
                }
                cleanup("observation") { observation.cancelAndJoin() }
                event("finished", failures.first)
            }
        }
        failures.rethrow()
        }
    }

    private fun start() {
        ContextCompat.startForegroundService(context, Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_START))
    }

    private fun stop() {
        context.startService(Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    }
}

internal class NativeSmokeFailures {
    var first: Throwable? = null
        private set

    fun record(failure: Throwable) {
        val original = first
        if (original == null) first = failure
        else if (original !== failure) original.addSuppressed(failure)
    }

    suspend fun attempt(action: suspend () -> Unit) {
        try { action() } catch (failure: Throwable) { record(failure) }
    }

    fun rethrow() { first?.let { throw it } }
}
