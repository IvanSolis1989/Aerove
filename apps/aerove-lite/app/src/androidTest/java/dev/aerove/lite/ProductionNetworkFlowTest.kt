package dev.aerove.lite

import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.util.Base64
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.ProxyAuthentication
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.vpn.VpnSnapshot
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production graph and sockets. Only the isolated CI fixture is used as a traffic target. */
@RunWith(AndroidJUnit4::class)
class ProductionNetworkFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = context.applicationContext as LiteApplication
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    @Test
    fun listenerEditsApplyWithoutTunRestartAndBindFailureReleasesTheSession() = runBlocking<Unit> {
        isolatedSession { session ->
            val original = session.running()
            val oldMixed = checkNotNull(original.appliedNetwork).ports.mixed
            openNetwork()
            val mixed = freeUserPort(setOf(oldMixed, checkNotNull(original.endpoint).port))
            val http = freeUserPort(setOf(oldMixed, mixed, checkNotNull(original.endpoint).port))
            editPorts(mixed, http)
            var desired = session.saved { it.ports == UserProxyPorts(mixed, http, 0) }
            session.applied(desired)
            assertReadback(original.endpoint!!, desired, ipv6 = false)
            assertSameSession(original)
            assertProxyFixture("127.0.0.1", mixed, session.fixture)
            assertProxyFixture("127.0.0.1", http, session.fixture)
            awaitPortsReleased("hot-apply-old-mixed", listOf(oldMixed))

            toggle(R.string.network_allow_lan)
            desired = session.saved { it.allowLan && it.ports.mixed == mixed }
            session.applied(desired)
            assertReadback(original.endpoint!!, desired, ipv6 = false)
            assertProxyFixture(physicalIpv4(), mixed, session.fixture)
            assertSameSession(original)

            toggle(R.string.network_authentication)
            desired = session.saved { it.authentication.enabled }
            session.applied(desired)
            assertReadback(original.endpoint!!, desired, ipv6 = false)
            assertEquals(407, proxyRequest("127.0.0.1", mixed, session.fixture).first)
            assertEquals(407, proxyRequest("127.0.0.1", http, session.fixture,
                ProxyAuthentication(true, "wrong-user", "wrong-password")).first)
            assertProxyFixture("127.0.0.1", mixed, session.fixture, desired.authentication)
            assertProxyFixture(physicalIpv4(), http, session.fixture, desired.authentication)
            assertSameSession(original)

            val blocker = reserveUserPort(setOf(oldMixed, mixed, http, original.endpoint!!.port))
            val occupied = blocker.localPort
            try {
                editPorts(occupied, http)
                session.saved { it.ports.mixed == occupied }
                val failed = withTimeout(30_000) { graph.vpn.first { it.phase == VpnPhase.Failed } }
                assertTrue("A bind conflict must produce a real failure", !failed.error.isNullOrBlank() ||
                    !session.controller.state.value.networkError.isNullOrBlank())
                assertNull("Failed listeners cannot leave a usable controller", failed.endpoint)
                awaitPortsReleased("bind-failure-session", listOf(oldMixed, mixed, http, original.endpoint!!.port))
            } finally { blocker.close() }
            // The saved intent remains authoritative: recovery starts that exact now-available port.
            session.activity.onActivity { session.controller.dismissMessage(); session.controller.connect() }
            val recovered = session.running(after = checkNotNull(original.endpoint))
            assertNotSame(original.endpoint, recovered.endpoint)
            assertEquals(occupied, recovered.initialNetwork?.ports?.mixed)
            desired = graph.settings.read().network
            session.applied(desired)
            assertReadback(checkNotNull(recovered.endpoint), desired, ipv6 = false)
            assertProxyFixture("127.0.0.1", occupied, session.fixture, desired.authentication)
        }
    }

    @Test
    fun vpnOnlyChangesNeedOneNoticeAndAnExplicitRestartBeforeBuilderPolicyChanges() = runBlocking<Unit> {
        isolatedSession { session ->
            val original = session.running()
            openNetwork()
            toggle(R.string.network_system_proxy)
            session.saved { it.systemProxy }
            toggle(R.string.network_allow_bypass)
            val desired = session.saved { it.systemProxy && !it.allowBypass }
            withTimeout(15_000) { session.controller.state.first { it.networkRestartRequired } }
            assertSingleRestartNotice()
            delay(750)
            assertSameSession(original)
            assertFalse("The active Builder policy is still the original saved startup policy",
                checkNotNull(graph.vpn.value.appliedNetwork).systemProxy)
            awaitVpnProxy(null, null)
            val beforeTapCount = session.runningIdentities().size
            assertEquals("Saving VPN-only settings must not reconstruct TUN", 1, beforeTapCount)

            clickRestart()
            val withProxy = session.running(after = checkNotNull(original.endpoint))
            assertNotSame(original.endpoint, withProxy.endpoint)
            assertEquals(desired, withProxy.initialNetwork)
            val installed = awaitVpnProxy("127.0.0.1", desired.ports.mixed)
            assertEquals(desired.bypassDomains, checkNotNull(installed.httpProxy).exclusionList.toList())
            withTimeout(15_000) { session.controller.state.first { !it.networkRestartRequired } }
            assertReadback(checkNotNull(withProxy.endpoint), desired, ipv6 = false)

            // Authentication is hot-applied, but its effective Android HTTP proxy change waits for the user.
            toggle(R.string.network_authentication)
            val authenticated = session.saved { it.authentication.enabled }
            session.applied(authenticated)
            withTimeout(15_000) { session.controller.state.first { it.networkRestartRequired } }
            assertSingleRestartNotice()
            delay(750)
            assertSameSession(withProxy)
            assertEquals("A single user tap must produce exactly one new running session", 2,
                session.runningIdentities().size)
            assertProxyFixture("127.0.0.1", authenticated.ports.mixed, session.fixture, authenticated.authentication)
            clickRestart()
            val withoutProxy = session.running(after = checkNotNull(withProxy.endpoint))
            assertNotSame(withProxy.endpoint, withoutProxy.endpoint)
            assertTrue("Do not erase the dormant systemProxy preference", graph.settings.read().network.systemProxy)
            assertTrue(checkNotNull(withoutProxy.initialNetwork).authentication.enabled)
            awaitVpnProxy(null, null)
            withTimeout(15_000) { session.controller.state.first { !it.networkRestartRequired } }
            assertReadback(checkNotNull(withoutProxy.endpoint), authenticated, ipv6 = false)
            assertProxyFixture("127.0.0.1", authenticated.ports.mixed, session.fixture, authenticated.authentication)
            assertEquals("Only the two explicit restart taps may replace the initial TUN session", 3,
                session.runningIdentities().size)
        }
    }

    private inner class Session(val fixture: String, val activity: ActivityScenario<MainActivity>,
        val controller: LiteController, val snapshots: CopyOnWriteArrayList<VpnSnapshot>) {
        suspend fun running(after: CoreEndpoint? = null): VpnSnapshot {
            val snapshot = withTimeout(60_000) { graph.vpn.first {
                it.phase == VpnPhase.Failed || (it.phase == VpnPhase.Running &&
                    (it.endpoint != null && (after == null || it.endpoint !== after)))
            } }
            assertEquals(snapshot.error ?: "The actual service must become ready", VpnPhase.Running, snapshot.phase)
            return snapshot
        }
        suspend fun saved(matches: (NetworkSettings) -> Boolean): NetworkSettings {
            val current = withTimeout(15_000) { controller.state.first {
                matches(it.settings.network) && "settings" !in it.busy
            } }.settings.network
            assertEquals("Controller save must reach the real settings store", current, graph.settings.read().network)
            return current
        }
        suspend fun applied(expected: NetworkSettings) {
            val snapshot = withTimeout(30_000) { graph.vpn.first { current ->
                val actual = current.appliedNetwork
                current.phase == VpnPhase.Failed || (!current.networkApplying && actual != null &&
                    actual.ports == expected.ports && actual.allowLan == expected.allowLan &&
                    actual.authentication == expected.authentication)
            } }
            assertEquals(snapshot.error ?: snapshot.networkError ?: "Network apply must complete",
                VpnPhase.Running, snapshot.phase)
            assertNull("Native POST and GET readback must succeed", snapshot.networkError)
        }
        fun runningIdentities(): Set<CoreEndpoint> = snapshots.filter { it.phase == VpnPhase.Running }
            .map { checkNotNull(it.endpoint) }.toSet() + graph.vpn.value.let {
                if (it.phase == VpnPhase.Running) setOf(checkNotNull(it.endpoint)) else emptySet()
            }
    }

    private suspend fun isolatedSession(body: suspend (Session) -> Unit) {
        val fixture = InstrumentationRegistry.getArguments().getString("fixtureUrl")
            ?: error("The isolated CI fixtureUrl is required")
        require(fixture.matches(Regex("http://10\\.0\\.2\\.2:[0-9]+")))
        assertEquals("This production acceptance is defined for the isolated API33 emulator", 33, Build.VERSION.SDK_INT)
        assertNull("CI must grant ACTIVATE_VPN before this test", VpnService.prepare(context))
        assertEquals("Do not take over an existing application VPN", VpnPhase.Stopped, graph.vpn.value.phase)
        // A prior test's Stopped notification can precede Android's network-removal callback.
        withTimeout(5000) { while (vpnLinks().isNotEmpty()) delay(100) }
        val originalProfiles = graph.profiles.snapshot()
        val originalSettings = graph.settings.read()
        var profile: ProfileSummary? = null
        var activity: ActivityScenario<MainActivity>? = null
        var started = false
        var primaryFailure: Throwable? = null
        val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val snapshots = CopyOnWriteArrayList<VpnSnapshot>()
        val monitor = monitorScope.launch(start = CoroutineStart.UNDISPATCHED) { graph.vpn.collect { snapshots.add(it) } }
        try {
            val owned = graph.profiles.importText("Production network ${UUID.randomUUID()}",
                "mode: direct\nrules:\n  - MATCH,DIRECT\n")
            profile = owned
            graph.profiles.select(owned.id)
            val seededSettings = graph.settings.save(LiteSettings(mode = ProxyMode.Direct, ipv6 = false, aurora = false,
                material = MaterialTier.Solid, autoCheckUpdate = false,
                network = NetworkSettings(systemProxy = false, ports = UserProxyPorts(freeUserPort()),
                    bypassDomains = listOf("localhost", "*.example.invalid"))), vpnAuthorized = true)
            val launched = ActivityScenario.launch(MainActivity::class.java)
            activity = launched
            lateinit var controller: LiteController
            launched.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
            compose.waitUntil(15_000) { controller.state.value.selectedProfile == owned.id &&
                controller.state.value.settings == seededSettings }
            compose.onNodeWithText(context.getString(R.string.maintenance_agree)).assertIsDisplayed().performClick()
            compose.onNodeWithText(context.getString(R.string.maintenance_confirm)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(context.getString(R.string.maintenance_disclaimer_restate_hint))
                .performTextInput(context.getString(R.string.maintenance_disclaimer_no_service_statement))
            compose.onNodeWithText(context.getString(R.string.maintenance_confirm)).performClick()
            withTimeout(15_000) {
                controller.state.first { it.settings.acceptedDisclaimerVersion >= dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION && it.busy.isEmpty() }
            }
            assertEquals("Consent must persist through the production settings owner",
                dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION, graph.settings.read().acceptedDisclaimerVersion)
            // The existing Controller starts the single service owner; no direct native or fake host start.
            started = true
            launched.onActivity { controller.connect() }
            val session = Session(fixture, launched, controller, snapshots)
            session.running()
            body(session)
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
            cleanup { activity?.close() }
            if (started) cleanup {
                context.startService(Intent(context, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
                withTimeout(20_000) { graph.vpn.first { it.phase == VpnPhase.Stopped } }
            }
            cleanup { monitor.cancelAndJoin(); monitorScope.cancel() }
            cleanup { graph.settings.save(originalSettings, vpnAuthorized = true) }
            cleanup { originalProfiles.selectedId?.let { graph.profiles.select(it) } }
            cleanup { profile?.let { graph.profiles.delete(it.id) } }
            cleanup {
                assertEquals(originalSettings, graph.settings.read())
                assertEquals("Only this test's profile may be removed", originalProfiles, graph.profiles.snapshot())
            }
            cleanupFailure?.let { failure ->
                val original = primaryFailure
                if (original == null) throw failure else original.addSuppressed(failure)
            }
        }
    }

    private fun openNetwork() {
        compose.onNode(hasText(context.getString(R.string.lite_tab_tools)) and hasClickAction())
            .assertIsDisplayed().performClick()
        compose.onNode(hasText(context.getString(R.string.integration_advanced_configuration)) and hasClickAction())
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText(context.getString(R.string.network_title)) and hasClickAction())
            .performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("network-settings").assertExists()
    }
    private fun editPorts(mixed: Int, http: Int) {
        compose.onNodeWithTag("network-settings").performScrollToNode(hasText(context.getString(R.string.network_ports)))
        compose.onNodeWithText(context.getString(R.string.network_ports)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.network_mixed_port)).performTextReplacement(mixed.toString())
        compose.onNodeWithContentDescription(context.getString(R.string.network_http_port)).performTextReplacement(http.toString())
        compose.onNodeWithTag("network-save").performScrollTo().performClick()
    }
    private fun toggle(label: Int) {
        val description = context.getString(label)
        compose.onNodeWithTag("network-settings").performScrollToNode(hasContentDescription(description))
        compose.onNodeWithContentDescription(description).performClick()
    }
    private fun assertSingleRestartNotice() {
        compose.onAllNodesWithTag("global-network-restart").assertCountEquals(1)
        compose.onAllNodesWithTag("global-network-restart-action").assertCountEquals(1)
    }
    private fun clickRestart() {
        assertSingleRestartNotice()
        compose.onNodeWithTag("global-network-restart-action").performClick()
    }
    private fun assertSameSession(expected: VpnSnapshot) {
        val actual = graph.vpn.value
        assertEquals(VpnPhase.Running, actual.phase)
        assertSame("Hot/VPN-only saves cannot replace the private endpoint", expected.endpoint, actual.endpoint)
        assertEquals("Only explicit user restart may reconstruct TUN", expected.runningSinceNanos, actual.runningSinceNanos)
    }

    private fun assertReadback(endpoint: CoreEndpoint, desired: NetworkSettings, ipv6: Boolean) {
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(10, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url("http://127.0.0.1:${endpoint.port}/aerove/network")
                .header("Authorization", "Bearer ${endpoint.secret}").build()).execute().use { response ->
                assertEquals("Read real native listener state", 200, response.code)
                val readback = JSONObject(checkNotNull(response.body).string())
                val epoch = readback.get("coreEpoch")
                assertTrue("Route epoch is an exact canonical uint64 string", epoch is String &&
                    epoch.toULongOrNull()?.let { it > 0uL && it.toString() == epoch } == true)
                val actual = readback.getJSONObject("network")
                assertEquals(desired.ports.mixed, actual.getInt("mixed-port"))
                assertEquals(desired.ports.http, actual.getInt("port"))
                assertEquals(desired.ports.socks, actual.getInt("socks-port"))
                assertEquals(desired.allowLan, actual.getBoolean("allow-lan"))
                assertEquals(if (!desired.allowLan) "127.0.0.1" else if (ipv6) "::" else "0.0.0.0", actual.getString("bind-address"))
                assertEquals(desired.authentication.enabled, actual.getBoolean("authEnabled"))
                val active = readback.getJSONArray("activePorts")
                assertEquals(listOf(desired.ports.mixed, desired.ports.http, desired.ports.socks).filter { it != 0 }.sorted(),
                    (0 until active.length()).map { active.getInt(it) })
                assertFalse("Native readback cannot disclose credentials", actual.has("authentication"))
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    private fun assertProxyFixture(host: String, port: Int, fixture: String, auth: ProxyAuthentication? = null) {
        val (code, body) = proxyRequest(host, port, fixture, auth)
        assertEquals("The actual user listener must forward the controlled fixture", 200, code)
        assertTrue("Fixture response must echo this request's unique token", body.contains("AEROVE_LITE_FIXTURE:"))
    }
    private fun proxyRequest(host: String, port: Int, fixture: String, auth: ProxyAuthentication? = null): Pair<Int, String> {
        val token = UUID.randomUUID().toString()
        val target = "$fixture/$token"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.soTimeout = 10_000
            val authorization = auth?.let { "Proxy-Authorization: Basic " +
                Base64.encodeToString("${it.username}:${it.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "\r\n" }.orEmpty()
            socket.getOutputStream().write(("GET $target HTTP/1.0\r\nHost: ${fixture.removePrefix("http://")}\r\n" +
                authorization + "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val status = checkNotNull(reader.readLine()) { "Proxy did not respond" }
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP status: $status")
            if (code != 200) return code to ""
            val body = reader.readText()
            assertTrue("Do not accept a cached or unrelated fixture response", body.contains("AEROVE_LITE_FIXTURE:$token"))
            return code to body
        }
    }
    private fun vpnLinks(): List<LinkProperties> = connectivity.allNetworks.mapNotNull { network ->
        val capabilities = connectivity.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) connectivity.getLinkProperties(network) else null
    }
    private suspend fun awaitVpnProxy(host: String?, port: Int?): LinkProperties = withTimeout(15_000) {
        while (true) {
            val links = vpnLinks()
            if (links.size == 1) {
                val proxy = links.single().httpProxy
                if (if (host == null) proxy == null else proxy?.host == host && proxy.port == port) return@withTimeout links.single()
            }
            delay(100)
        }
        error("unreachable")
    }
    private fun physicalIpv4(): String = connectivity.allNetworks.asSequence().filter { network ->
        connectivity.getNetworkCapabilities(network)?.let { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) } == true
    }.mapNotNull { connectivity.getLinkProperties(it) }.flatMap { it.linkAddresses.asSequence() }
        .map { it.address }.filterIsInstance<Inet4Address>().first { !it.isLoopbackAddress }.hostAddress!!

    private fun reserveUserPort(excluded: Set<Int> = emptySet()): ServerSocket {
        repeat(100) {
            val port = Random.nextInt(20_000, 45_000)
            if (port !in excluded) {
                val socket = ServerSocket()
                try {
                    socket.reuseAddress = false
                    socket.bind(InetSocketAddress("127.0.0.1", port))
                    return socket
                } catch (_: java.io.IOException) { socket.close() }
            }
        }
        error("No isolated legal user proxy port is available")
    }
    private fun freeUserPort(excluded: Set<Int> = emptySet()): Int = reserveUserPort(excluded).use { it.localPort }
    private suspend fun awaitPortsReleased(stage: String, ports: List<Int>) {
        var lastPort: Int? = null
        var lastFailure: java.io.IOException? = null
        try {
            withTimeout(20_000) {
                while (true) {
                    val reservations = mutableListOf<ServerSocket>()
                    try {
                        ports.distinct().forEach { port ->
                            val socket = ServerSocket()
                            try {
                                socket.reuseAddress = false
                                socket.bind(InetSocketAddress("127.0.0.1", port))
                                reservations += socket
                            } catch (failure: java.io.IOException) {
                                lastPort = port; lastFailure = failure
                                socket.close(); throw failure
                            }
                        }
                        return@withTimeout
                    } catch (_: java.io.IOException) { delay(100) }
                    finally { reservations.forEach { it.close() } }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            try {
                android.util.Log.e("NetworkPortRelease",
                    "stage=$stage expectedPorts=${ports.distinct()} lastPort=$lastPort " +
                        "lastType=${lastFailure?.javaClass?.name} lastMessage=${lastFailure?.message}", lastFailure)
            } finally { throw timeout }
        }
    }
}
