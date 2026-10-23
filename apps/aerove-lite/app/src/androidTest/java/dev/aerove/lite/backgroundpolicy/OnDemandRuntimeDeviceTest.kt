package dev.aerove.lite.backgroundpolicy

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.net.wifi.WifiInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.LiteVpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Requires an explicitly isolated device, real Wi-Fi/location grants, VPN consent and the controlled CI HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class OnDemandRuntimeDeviceTest {
    @Test fun excludedRealWifiParksAndResumesProxyAndTunWithoutReplacingCore() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val graph = context.applicationContext as LiteApplication
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val args = InstrumentationRegistry.getArguments()
        check(args.getString("isolatedBackgroundPolicy") == "true") { "Explicit isolatedBackgroundPolicy=true is required" }
        val fixture = checkNotNull(args.getString("fixtureUrl")) { "Controlled fixtureUrl is required" }
        check(fixture.matches(Regex("http://10\\.0\\.2\\.2:[0-9]+"))) { "Only the isolated emulator fixture is accepted" }
        check(graph.profiles.snapshot().profiles.isEmpty()) { "Never replace existing profiles for this acceptance" }
        check(graph.vpn.value.phase == VpnPhase.Stopped && !LiteVpnService.wasRequested(context)) { "Never take over an existing run" }
        check(connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }) {
            "Never take over another application's VPN"
        }
        check(VpnService.prepare(context) == null) { "Grant system VPN consent before acceptance" }
        check(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Grant notification permission before the controlled proxy acceptance"
        }
        check(AndroidSsidPolicy.access(context).granted) { "Grant precise/background location and enable location before acceptance" }
        val ssid = CompletableDeferred<String>()
        val wifi = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                BackgroundPolicyPreferences.ssidFromPlatform((capabilities.transportInfo as? WifiInfo)?.ssid)?.let { ssid.complete(it) }
            }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), wifi)
        val actualSsid = try { withTimeout(15_000) { ssid.await() } } finally { connectivity.unregisterNetworkCallback(wifi) }
        val vpnNetworks = java.util.concurrent.ConcurrentHashMap.newKeySet<Network>()
        val vpnAvailableCount = java.util.concurrent.atomic.AtomicInteger()
        val vpnEvents = Channel<Boolean>(Channel.CONFLATED)
        val vpn = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { vpnAvailableCount.incrementAndGet(); vpnNetworks += network; vpnEvents.trySend(vpnNetworks.isNotEmpty()) }
            override fun onLost(network: Network) { vpnNetworks -= network; vpnEvents.trySend(vpnNetworks.isNotEmpty()) }
        }
        connectivity.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpn)
        suspend fun vpnPresent(expected: Boolean) = withTimeout(15_000) {
            if (expected || vpnNetworks.isNotEmpty()) while (vpnEvents.receive() != expected) Unit
        }
        val saved = graph.settings.read()
        var profileId: String? = null
        var activity: ActivityScenario<MainActivity>? = null
        var primaryFailure: Throwable? = null
        try {
            graph.settings.save(saved.copy(autoStart = false,
                backgroundPolicy = saved.backgroundPolicy.copy(autoRun = false, excludeSSIDs = emptyList()),
                geoResources = saved.geoResources.copy(autoUpdate = false)), vpnAuthorized = true)
            val profile = graph.profiles.importText("background-acceptance-${UUID.randomUUID()}",
                "dns:\n  enable: false\nproxies: []\nproxy-groups: []\nrules: ['MATCH,DIRECT']\n", null, true)
            profileId = profile.id
            graph.profiles.select(profile.id)
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var controller: LiteController
            activity.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
            for (tun in listOf(false, true)) {
                val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val settings = saved.copy(autoStart = false, mode = ProxyMode.Direct, network = saved.network.copy(vpnEnabled = tun,
                    systemProxy = false, bypassPrivateRoute = false, ports = UserProxyPorts(port)),
                    backgroundPolicy = saved.backgroundPolicy.copy(autoRun = false, excludeSSIDs = emptyList()),
                    geoResources = saved.geoResources.copy(autoUpdate = false))
                val initiallyExcluded = settings.copy(backgroundPolicy = settings.backgroundPolicy.copy(excludeSSIDs = listOf(actualSsid)))
                graph.settings.save(initiallyExcluded, vpnAuthorized = true)
                withTimeout(15_000) { controller.state.first { it.settings.backgroundPolicy == initiallyExcluded.backgroundPolicy } }
                val vpnCountBefore = vpnAvailableCount.get()
                val parkedAtStart = ServerSocket().use { occupied ->
                    // A real bind attempt during startup would fail while this socket owns the configured user port.
                    occupied.bind(InetSocketAddress("127.0.0.1", port))
                    activity.onActivity { controller.connect() }
                    val actual = withTimeout(60_000) { graph.vpn.first { it.phase == VpnPhase.Running || it.phase == VpnPhase.Failed } }
                    assertEquals(actual.error, VpnPhase.Running, actual.phase)
                    assertTrue(actual.suspended)
                    assertFalse(actual.listening)
                    assertNull(actual.initialSettings)
                    assertNull(actual.initialNetwork)
                    assertTrue(LiteVpnService.wasRequested(context))
                    assertEquals(vpnCountBefore, vpnAvailableCount.get())
                    assertTrue(connectivity.allNetworks.none { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true })
                    actual
                }
                val parkedEndpoint = checkNotNull(parkedAtStart.endpoint)
                val parkedEpoch = CoreApiClient(ControlEndpoint(parkedEndpoint.port, parkedEndpoint.secret)).use { it.routeSnapshot().coreEpoch }
                graph.settings.save(settings, vpnAuthorized = true)
                val original = withTimeout(60_000) { graph.vpn.first { it.listening || it.phase == VpnPhase.Failed } }
                assertEquals(original.error ?: original.listenerError, VpnPhase.Running, original.phase)
                assertSame(parkedEndpoint, original.endpoint)
                assertNotNull(original.initialSettings)
                if (tun) vpnPresent(true)
                val endpoint = checkNotNull(original.endpoint)
                val epoch = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot().coreEpoch }
                assertTrue(epoch.toULong() >= parkedEpoch.toULong())
                fixtureRequest(port, fixture)
                if (tun) directFixtureRequest(fixture)
                graph.settings.save(settings.copy(backgroundPolicy = settings.backgroundPolicy.copy(excludeSSIDs = listOf(actualSsid))), vpnAuthorized = true)
                val parked = withTimeout(30_000) { graph.vpn.first { it.suspended || it.phase == VpnPhase.Failed } }
                assertEquals(parked.error ?: parked.listenerError, VpnPhase.Running, parked.phase)
                assertFalse(parked.listening)
                assertSame(endpoint, parked.endpoint)
                assertTrue(LiteVpnService.wasRequested(context))
                ServerSocket().use { released ->
                    released.reuseAddress = true
                    released.bind(InetSocketAddress("127.0.0.1", port))
                    assertTrue(released.isBound)
                }
                if (tun) vpnPresent(false)
                if (tun) directFixtureRequest(fixture)
                assertTrue(CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { it.routeSnapshot().coreEpoch }.toULong() >= epoch.toULong())
                graph.settings.save(settings, vpnAuthorized = true)
                val resumed = withTimeout(30_000) { graph.vpn.first { (it.listening && !it.suspended) || it.phase == VpnPhase.Failed } }
                assertEquals(resumed.error ?: resumed.listenerError, VpnPhase.Running, resumed.phase)
                assertSame(endpoint, resumed.endpoint)
                assertEquals(original.runningSinceNanos, resumed.runningSinceNanos)
                if (tun) vpnPresent(true)
                fixtureRequest(port, fixture)
                if (tun) directFixtureRequest(fixture)
                activity.onActivity { controller.connect() }
                withTimeout(30_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
                if (tun) vpnPresent(false)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(action: suspend () -> Unit) {
                try { action() } catch (failure: Throwable) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(failure)
                    else if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure!!.addSuppressed(failure)
                }
            }
            var joined = graph.vpn.value.phase == VpnPhase.Stopped
            cleanup {
                if (LiteVpnService.wasRequested(context) || !joined) {
                    context.startService(android.content.Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
                    withTimeout(30_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
                }
                joined = true
            }
            cleanup { activity?.close() }
            cleanup { connectivity.unregisterNetworkCallback(vpn); vpnEvents.close() }
            if (joined) {
                cleanup { profileId?.let { graph.profiles.delete(it) } }
                cleanup { graph.settings.save(saved, vpnAuthorized = true) }
            }
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }

    private fun fixtureRequest(port: Int, fixture: String) {
        val token = UUID.randomUUID().toString()
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(("GET $fixture/$token HTTP/1.0\r\nHost: ${fixture.removePrefix("http://")}\r\n\r\n").toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("AEROVE_LITE_FIXTURE:$token"))
        }
    }

    private fun directFixtureRequest(fixture: String) {
        val uri = java.net.URI(fixture)
        val token = UUID.randomUUID().toString()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 5000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(("GET /$token HTTP/1.0\r\nHost: ${uri.rawAuthority}\r\n\r\n").toByteArray())
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response.startsWith("HTTP/1.0 200") || response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("AEROVE_LITE_FIXTURE:$token"))
        }
    }
}
