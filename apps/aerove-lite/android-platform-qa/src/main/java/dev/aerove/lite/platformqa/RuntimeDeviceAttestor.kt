package dev.aerove.lite.platformqa

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.core.ConnectionOwnerResolver
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

/** Test dependency sockets are owned here; the production graph is observed, never mutated. */
internal class RuntimeDeviceAttestor(private val app: LiteApplication) {
    suspend fun capture(request: JSONObject, evidence: JSONObject) = withContext(Dispatchers.IO) {
        val stage = request.getString("stage")
        check(stage in stages)
        val expected = request.getJSONObject("runtimeDevice")
        val stopped = stage in stoppedStages
        val state = withTimeout(25_000) { app.vpn.first {
            if (stopped) it.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed) && it.endpoint == null
            else it.phase == VpnPhase.Running && it.endpoint != null && it.listening && !it.suspended
        } }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val networks = cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.let { caps ->
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == app.applicationInfo.uid
        } == true }
        check((networks.isNotEmpty()) == request.getBoolean("expectedVpn"))
        val row = JSONObject().put("stage", stage).put("phase", state.phase.name)
            .put("serviceOwner", state.serviceOwner).put("sessionGeneration", state.sessionGeneration)
            .put("networkRequestId", state.networkRequest?.id ?: JSONObject.NULL)
            .put("listening", state.listening).put("suspended", state.suspended)
            .put("appUid", app.applicationInfo.uid).put("pid", Process.myPid())
            .put("vpnHandles", JSONArray(networks.map { it.networkHandle.toString() }.sorted()))
            .put("listenerError", state.listenerError ?: JSONObject.NULL)
            .put("error", state.error ?: JSONObject.NULL)
        if (!stopped) {
            val endpoint = checkNotNull(state.endpoint)
            val identity = checkNotNull(app.flutterRuntimeIdentity.first { value -> value != null &&
                app.flutterRuntimeIdentityIsCurrent(value) && value.proof.serviceOwner == state.serviceOwner &&
                value.proof.sessionGeneration == state.sessionGeneration })
            check(app.runtimeHandoffs.readAppliedIdentity() == identity)
            val profileName = JSONObject(checkNotNull(app.runtimeHandoffs.readLastApplied()).handoff.notificationJson).getString("currentProfileName")
            check(profileName == androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("profileLabel"))
            row.put("coreEpoch", identity.proof.coreEpoch).put("runtimeRevision", identity.revision)
                .put("intentEpoch", identity.intentEpoch).put("profileName", profileName)
            val raw = withTimeout(20_000) {
                var value: String
                do {
                    value = networkReadback(endpoint)
                    val parsed = JSONObject(value)
                    if (parsed.getJSONObject("network").getInt("mixed-port") == expected.getInt("port")) break
                    delay(100)
                } while (true)
                value
            }
            val network = JSONObject(raw)
            check(network.getJSONArray("activePorts").let { ports ->
                (0 until ports.length()).any { ports.getInt(it) == expected.getInt("port") }
            })
            check(network.getString("coreEpoch") == identity.proof.coreEpoch)
            row.put("networkReadbackJson", raw).put("proxyEcho", proxyEcho(expected.getInt("port")))
            if (stage == "owner-tcp-udp") row.put("owner", owner(endpoint))
        }
        if (expected.has("releasedPort")) {
            val port = expected.getInt("releasedPort")
            check(port in 1024..49151)
            row.put("releasedPort", port).put("releasedPortBindSucceeded", ServerSocket().use { probe ->
                probe.reuseAddress = false
                probe.bind(InetSocketAddress("127.0.0.1", port))
                true
            })
        }
        if (stage == "listener-bind-failed") {
            // The UI test still owns this blocker. Do not treat unrelated failure as a bind rejection.
            val occupied = expected.getInt("port")
            check(occupied in 1024..49151)
            val collision = try {
                ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress("127.0.0.1", occupied)) }
                false
            } catch (_: java.net.BindException) { true }
            check(collision && (state.listenerError ?: state.error).orEmpty().isNotBlank())
            row.put("occupiedPort", occupied).put("occupiedPortRejected", collision)
            val failure = checkNotNull(state.networkApplyFailure) { "Original network apply diagnostic is absent" }
            check(failure.mixedPort == occupied)
            row.put("networkApplyFailure", JSONObject().put("operation", failure.operation).put("method", failure.method)
                .put("statusCode", failure.statusCode).put("requestId", failure.requestId)
                .put("networkRequestId", checkNotNull(failure.networkRequestId))
                .put("mixedPort", failure.mixedPort).put("expectedCoreEpoch", failure.expectedCoreEpoch)
                .put("serviceOwner", checkNotNull(failure.serviceOwner))
                .put("sessionGeneration", checkNotNull(failure.sessionGeneration))
                .put("responseBody", failure.responseBody))
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val notifications = withTimeout(10_000) {
            var actual: List<android.service.notification.StatusBarNotification>
            do {
                // activeNotifications is already scoped to this App; retain every channel for replay.
                actual = manager.activeNotifications.toList()
                val matches = if (request.getBoolean("expectedVpn") || stopped) actual.isEmpty()
                    else actual.size == 1 && (actual.single().notification.actions?.map { it.title.toString() } ?: emptyList()) ==
                        if (expected.getBoolean("stopAction")) listOf("Stop") else emptyList<String>()
                if (matches) break
                delay(100)
            } while (true)
            actual
        }
        row.put("notifications", JSONArray(notifications.map { posted ->
            val notification = posted.notification
            JSONObject().put("id", posted.id).put("channel", notification.channelId)
                .put("flags", notification.flags).put("title", notification.extras.getCharSequence("android.title")?.toString())
                .put("text", notification.extras.getCharSequence("android.text")?.toString())
                .put("channelImportance", checkNotNull(manager.getNotificationChannel(notification.channelId)).importance)
                .put("actions", JSONArray(notification.actions?.map { it.title.toString() } ?: emptyList<String>()))
        }))
        if (request.getBoolean("expectedVpn") || stopped) check(notifications.isEmpty())
        else {
            check(notifications.size == 1)
            val actions = notifications.single().notification.actions?.map { it.title.toString() } ?: emptyList()
            check(actions == if (expected.getBoolean("stopAction")) listOf("Stop") else emptyList<String>())
            val notification = notifications.single().notification
            check(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0 &&
                notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0 &&
                checkNotNull(manager.getNotificationChannel(notification.channelId)).importance in 1..NotificationManager.IMPORTANCE_LOW &&
                notification.extras.getCharSequence("android.title")?.toString() == row.getString("profileName"))
        }
        // Capture again after all potentially blocking reads; no reused endpoint/session may pass.
        check(app.vpn.value.let { it.phase == state.phase && it.endpoint === state.endpoint &&
            it.serviceOwner == state.serviceOwner && it.sessionGeneration == state.sessionGeneration &&
            it.listening == state.listening && it.suspended == state.suspended })
        evidence.put("runtimeDevice", row).put("attestedElapsedRealtimeMs", SystemClock.elapsedRealtime())
    }

    private suspend fun networkReadback(endpoint: dev.aerove.lite.vpn.CoreEndpoint): String {
        val url = java.net.URL("http://127.0.0.1:${endpoint.port}/aerove/network")
        val connection = url.openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
        connection.connectTimeout = 5_000; connection.readTimeout = 5_000
        connection.setRequestProperty("Authorization", "Bearer ${endpoint.secret}")
        try {
            check(connection.responseCode == 200)
            return connection.inputStream.use { stream ->
                val raw = stream.readNBytes(262_145); check(raw.size <= 262_144)
                raw.toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun proxyEcho(port: Int): JSONObject {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val fixture = java.net.URI(requireNotNull(arguments.getString("fixtureUrl")))
        check(fixture.scheme == "http" && fixture.host == "10.0.2.2" && fixture.port in 1..65535 && fixture.userInfo == null)
        val token = UUID.randomUUID().toString()
        val target = "http://10.0.2.2:${fixture.port}/$token"
        val raw = java.net.Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 3_000); socket.soTimeout = 5_000
            socket.getOutputStream().write(("GET $target HTTP/1.0\r\nHost: 10.0.2.2:${fixture.port}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readNBytes(65_537)
            check(response.size <= 65_536)
            response.toString(Charsets.UTF_8)
        }
        check(raw.lineSequence().first().split(' ').getOrNull(1) == "200" && raw.substringAfter("\r\n\r\n").contains("AEROVE_LITE_FIXTURE:$token"))
        return JSONObject().put("port", port).put("token", token).put("response", raw)
    }

    private suspend fun owner(endpoint: dev.aerove.lite.vpn.CoreEndpoint): JSONObject {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        val fixturePackage = "dev.aerove.fixture.socketowner"
        val fixture = app.packageManager.getApplicationInfo(fixturePackage, 0)
        val uid = fixture.uid
        check(uid != app.applicationInfo.uid && uid != 2000 && uid > 0)
        check(fixture.flags and android.content.pm.ApplicationInfo.FLAG_TEST_ONLY != 0)
        check(app.packageManager.getPackagesForUid(uid)?.toList() == listOf(fixturePackage))
        val lease = SocketLease(app, fixturePackage)
        val result = JSONObject()
        try {
            lease.bind()
            val ready = lease.request(1, Bundle().apply {
                putInt("tcpPort", requireNotNull(arguments.getString("ownerTcpPort")).toInt())
                putInt("udpPort", requireNotNull(arguments.getString("ownerUdpPort")).toInt())
            })
            check(ready.getBoolean("live") && ready.getInt("uid") == uid && ready.getString("package") == fixturePackage &&
                ready.getInt("pid") != Process.myPid())
            result.put("fixtureUid", uid).put("fixturePackage", fixturePackage).put("fixturePid", ready.getInt("pid"))
                .put("token", ready.getString("token"))
                .put("fixtureTestOnly", true).put("fixtureApkSha256", java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.io.File(fixture.sourceDir).readBytes()).joinToString("") { "%02x".format(it) })
            val raw = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { client ->
                withTimeout(10_000) {
                    var snapshot: String
                    do {
                        snapshot = client.protocolSnapshot(listOf("connections"))
                        val rows = JSONObject(snapshot).getJSONArray("connections")
                        val found = listOf("tcp", "udp").all { protocol ->
                            val source = checkNotNull(ConnectionOwnerResolver.numericEndpoint(checkNotNull(ready.getString(protocol + "Source"))))
                            val target = checkNotNull(ConnectionOwnerResolver.numericEndpoint(checkNotNull(ready.getString(protocol + "Target"))))
                            (0 until rows.length()).count { index ->
                                val metadata = rows.getJSONObject(index).getJSONObject("metadata")
                                metadata.optString("network").lowercase() == protocol && metadata.optString("type").equals("Tun", ignoreCase = true) &&
                                    metadata.optInt("sourcePort") == source.port &&
                                    metadata.optString("sourceIP") == source.address.hostAddress && metadata.optInt("destinationPort") == target.port &&
                                    metadata.optString("destinationIP") == target.address.hostAddress && metadata.optInt("uid", -1) == uid &&
                                    metadata.optString("process") == fixturePackage
                            } == 1
                        }
                        if (found) break
                        delay(100)
                    } while (true)
                    snapshot
                }
            }
            val retained = lease.request(2)
            check(retained.getBoolean("live"))
            for (key in listOf("uid", "pid", "package", "token", "tcpSource", "tcpTarget", "udpSource", "udpTarget"))
                check(ready.get(key) == retained.get(key))
            for (protocol in listOf("tcp", "udp")) result.put(protocol, JSONObject()
                .put("source", ready.getString(protocol + "Source")).put("target", ready.getString(protocol + "Target")))
            result.put("connectionsJson", raw).put("retained", true)
        } finally {
            result.put("fixtureClosed", lease.close())
        }
        return result
    }

    /** Same lease protocol as the retained old owner test; no product data or start commands. */
    private class SocketLease(private val context: Context, private val packageName: String) {
        private val token = UUID.randomUUID().toString()
        private val connected = Channel<Messenger>(1)
        private val responses = Channel<Bundle>(1)
        private val replies = Messenger(Handler(Looper.getMainLooper()) { responses.trySend(it.data); true })
        private var bound = false
        private var remote: Messenger? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.trySend(Messenger(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        suspend fun bind() {
            bound = context.bindService(Intent().setComponent(ComponentName(packageName, "$packageName.SocketOwnerService")), connection, Context.BIND_AUTO_CREATE)
            check(bound)
            remote = withTimeout(5_000) { connected.receive() }
        }
        suspend fun request(operation: Int, input: Bundle = Bundle()): Bundle {
            input.putString("token", token)
            checkNotNull(remote).send(Message.obtain(null, operation).apply { data = input; replyTo = replies })
            val result = withTimeout(15_000) { responses.receive() }
            check(!result.containsKey("error")) { result.getString("error").orEmpty() }
            check(result.getString("token") == token)
            return result
        }
        suspend fun close(): Boolean {
            try { return if (remote != null) request(3).getBoolean("closed").also { check(it) } else !bound }
            finally { if (bound) context.unbindService(connection); bound = false; remote = null }
        }
    }

    companion object {
        val stoppedStages = setOf("listener-bind-failed", "listener-stopped", "proxy-notification-stopped", "proxy-notification-still-stopped", "owner-stopped")
        val stages = stoppedStages + setOf("listener-baseline", "listener-rebound", "listener-retry", "proxy-notification", "proxy-stop-hidden", "proxy-stop-restored", "owner-tcp-udp")
    }
}
