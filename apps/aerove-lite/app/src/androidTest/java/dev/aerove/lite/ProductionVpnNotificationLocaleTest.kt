package dev.aerove.lite

import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationManager
import android.net.VpnService
import android.os.LocaleList
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.LiteVpnService
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.io.IOException
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionVpnNotificationLocaleTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as LiteApplication
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val channelId = "aerove-lite-vpn"

    @Test fun nativeLocaleChangeRefreshesTheAlreadyPostedVpnNotificationAndChannel() = runBlocking<Unit> {
        assertNull("CI must grant ACTIVATE_VPN", VpnService.prepare(context))
        assertTrue("CI must grant POST_NOTIFICATIONS", manager.areNotificationsEnabled())
        assertEquals("Do not replace an existing VPN", VpnPhase.Stopped, graph.vpn.value.phase)
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val previousLocales = localeManager.applicationLocales
        val originalSettings = graph.settings.read()
        val originalProfiles = graph.profiles.snapshot()
        var ownedProfile: ProfileSummary? = null
        var activity: ActivityScenario<MainActivity>? = null
        var started = false
        var primaryFailure: Throwable? = null
        try {
            val profile = graph.profiles.importText("Notification locale ${UUID.randomUUID()}",
                "mode: direct\nrules:\n  - MATCH,DIRECT\n")
            ownedProfile = profile
            graph.profiles.select(profile.id)
            val port = freeUserPort()
            val settings = graph.settings.save(LiteSettings(mode = ProxyMode.Direct, aurora = false,
                material = MaterialTier.Solid, locale = "en", autoCheckUpdate = false,
                network = NetworkSettings(systemProxy = false, ports = UserProxyPorts(port))), vpnAuthorized = true)
            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            lateinit var controller: LiteController
            launched.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
            withTimeout(15_000) { controller.state.first { it.settings == settings && it.selectedProfile == profile.id } }
            started = true
            launched.onActivity { controller.connect() }
            val running = withTimeout(60_000) { graph.vpn.first { it.phase in setOf(VpnPhase.Running, VpnPhase.Failed) } }
            assertEquals(running.error ?: "The actual service must become ready", VpnPhase.Running, running.phase)
            await { posted()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "VPN is running" }
            val oldImportance = checkNotNull(manager.getNotificationChannel(channelId)).importance
            val oldBadge = checkNotNull(manager.getNotificationChannel(channelId)).canShowBadge()

            instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.forLanguageTags("ja") }
            await {
                val notification = posted()
                graph.resources.configuration.locales[0].language == "ja" &&
                    notification != null && notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "VPN は実行中です" &&
                    notification.actions?.singleOrNull()?.title?.toString() == "切断" &&
                    manager.getNotificationChannel(channelId)?.name?.toString() == "VPN 接続"
            }
            val channel = checkNotNull(manager.getNotificationChannel(channelId))
            assertEquals(oldImportance, channel.importance)
            assertEquals(oldBadge, channel.canShowBadge())
            assertSame("Locale changes cannot restart the VPN", running.endpoint, graph.vpn.value.endpoint)
            assertEquals(running.runningSinceNanos, graph.vpn.value.runningSinceNanos)
            launched.onActivity { controller.disconnect() }
            withTimeout(20_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            await { posted() == null }
            started = false
            instrumentation.runOnMainSync { localeManager.applicationLocales = LocaleList.forLanguageTags("fr") }
            await { graph.resources.configuration.locales[0].language == "fr" }
            instrumentation.waitForIdleSync()
            assertNull("A locale event after stop cannot repost the VPN notification", posted())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
            }
            if (started) cleanup {
                context.startService(android.content.Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
                withTimeout(20_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            }
            cleanup { activity?.close() }
            cleanup { graph.settings.save(originalSettings, vpnAuthorized = true) }
            cleanup { originalProfiles.selectedId?.let { graph.profiles.select(it) } }
            cleanup { ownedProfile?.let { graph.profiles.delete(it.id) } }
            cleanup { instrumentation.runOnMainSync { localeManager.applicationLocales = previousLocales } }
            cleanup {
                assertEquals(originalSettings, graph.settings.read())
                assertEquals(originalProfiles, graph.profiles.snapshot())
            }
            cleanupFailure?.let {
                val original = primaryFailure
                if (original == null) throw it else original.addSuppressed(it)
            }
        }
    }

    private fun posted(): Notification? = manager.activeNotifications.singleOrNull { it.notification.channelId == channelId }?.notification

    private fun freeUserPort(): Int {
        repeat(100) {
            val port = Random.nextInt(20_000, 45_000)
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress("127.0.0.1", port))
                    return port
                }
            } catch (_: IOException) { }
        }
        error("No isolated legal user proxy port is available")
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(50)
    }
}
