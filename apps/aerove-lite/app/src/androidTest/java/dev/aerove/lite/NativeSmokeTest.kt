package dev.aerove.lite

import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.vpn.LiteVpnService
import java.util.UUID
import kotlinx.coroutines.flow.first
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
        assertNotNull(NativeCore.validate("unknown-aerove-field: true\n"))
        assertNull(NativeCore.validate("mode: direct\nrules: ['MATCH,DIRECT']\n"))
    }

    @Test
    fun vpnForwardsOtherUidTrafficAndStopsWithoutLeavingAnInstance() {
        runBlocking<Unit> {
        val fixture = InstrumentationRegistry.getArguments().getString("fixtureUrl")
            ?: error("A controlled fixtureUrl is required for the TUN acceptance test")
        require(fixture.matches(Regex("http://10\\.0\\.2\\.2:[0-9]+")))
        val port = fixture.substringAfterLast(':').toInt()
        assertNull("Grant ACTIVATE_VPN to the development package before this test", VpnService.prepare(context))
        val profile = graph.profiles.importText("TUN test", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
        graph.profiles.select(profile.id)
        graph.settings.save(LiteSettings(mode = ProxyMode.Direct), vpnAuthorized = true)
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            start()
            withTimeout(60_000) { graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed } }
                .also { check(it.phase == VpnPhase.Running) { it.error ?: "VPN did not become ready" } }
            val token = UUID.randomUUID().toString()
            val command = "printf 'GET /$token HTTP/1.0\\r\\nHost: fixture\\r\\n\\r\\n' | toybox nc -w 10 10.0.2.2 $port"
            val response = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            assertTrue("An independent shell UID must receive its unique fixture response through the TUN", response.contains("AEROVE_LITE_FIXTURE:$token"))
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
            stop()
            withTimeout(15_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            start()
            withTimeout(60_000) { graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed } }
                .also { check(it.phase == VpnPhase.Running) { it.error ?: "VPN could not restart" } }
        } finally {
            try {
                stop()
                withTimeout(15_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
                graph.profiles.delete(profile.id)
            } finally { activity.close() }
        }
        }
    }

    private fun start() {
        ContextCompat.startForegroundService(context, Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_START))
    }

    private fun stop() {
        context.startService(Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    }
}
