package dev.aerove.lite.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.*
import dev.aerove.lite.data.ProfileDocumentSnapshot
import dev.aerove.lite.data.ProfileSnapshot
import dev.aerove.lite.data.privateDomainTransaction
import dev.aerove.lite.vpn.OwnedStartRequest
import dev.aerove.lite.vpn.OwnedStartCancellation
import dev.aerove.lite.vpn.VpnSnapshot
import dev.aerove.lite.networksettings.NetworkSettings
import dev.aerove.lite.networksettings.UserProxyPorts
import dev.aerove.lite.vpn.LiteVpnService
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionOwnerResolverDeviceTest {
    @Test fun api33AttributesAnotherApplicationsActualSockets() = runBlocking<Unit> {
        val hardDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(115)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("processOwnerFixture") == "true") {
            "Requires a real independent package retaining TCP/UDP sockets and active Lite Tun; use the discovered 8/16-shard fixture invocation for acceptance"
        }
        assertEquals(33, Build.VERSION.SDK_INT)
        val context = instrumentation.targetContext
        val graph = context.applicationContext as LiteApplication
        assertNull("CI must grant VPN authorization", VpnService.prepare(context))
        assertEquals("Never replace an existing runtime", VpnPhase.Stopped, graph.vpn.value.phase)
        assertFalse(LiteVpnService.wasRequested(context))
        val fixturePackage = requireNotNull(arguments.getString("ownerFixturePackage"))
        assertEquals("dev.aerove.fixture.socketowner", fixturePackage)
        val fixtureUid = context.packageManager.getApplicationInfo(fixturePackage, 0).uid
        assertNotEquals("A same-UID socket cannot prove attribution", context.applicationInfo.uid, fixtureUid)
        val packages = requireNotNull(context.packageManager.getPackagesForUid(fixtureUid))
        assertArrayEquals(arrayOf(fixturePackage), packages)
        val originalSettings = graph.settings.read()
        val originalProfiles = graph.profiles.snapshot()
        val receipt = JSONObject().put("fixturePackage", fixturePackage).put("fixtureUid", fixtureUid)
            .put("appUid", context.applicationInfo.uid).put("sourceRevision", arguments.getString("sourceRevision"))
        var ownedDocument: ProfileDocumentSnapshot? = null
        var fixtureWrittenSettings: LiteSettings? = null
        var ownedStart: OwnedStartRequest? = null
        var ownedSession: VpnSnapshot? = null
        var activity: ActivityScenario<MainActivity>? = null
        var sockets: SocketFixture? = null
        var primaryFailure: Throwable? = null
        try {
            val workBudget = minOf(75_000L, TimeUnit.NANOSECONDS.toMillis(hardDeadline - System.nanoTime()) - 40_000L)
            check(workBudget > 0) { "No original test budget remains for work and cleanup" }
            withTimeout(workBudget) {
                exerciseCancelledStorageRestore(context, graph)
                receipt.put("storageCancellationProbePassed", true)
                exerciseSupersededStorageRestore(context, graph)
                receipt.put("storageSupersessionProbePassed", true)
                withContext(NonCancellable) {
                    privateDomainTransaction(context.filesDir) {
                        check(graph.settings.read() == originalSettings && graph.profiles.snapshot() == originalProfiles) {
                            "Fixture inputs changed before its write"
                        }
                        val imported = graph.profiles.importText("Socket owner ${UUID.randomUUID()}", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
                        ownedDocument = graph.profiles.documentSnapshot(imported.id)
                        graph.profiles.select(imported.id)
                        fixtureWrittenSettings = graph.settings.save(LiteSettings(mode = ProxyMode.Direct, aurora = false,
                            material = MaterialTier.Solid, autoCheckUpdate = false,
                            network = NetworkSettings(vpnEnabled = true, systemProxy = false, bypassPrivateRoute = false,
                                ports = UserProxyPorts(freeUserPort()))), vpnAuthorized = true)
                    }
                }
                val owned = checkNotNull(ownedDocument).profile
                val settings = checkNotNull(fixtureWrittenSettings)
                val launched = ActivityScenario.launch(MainActivity::class.java)
                activity = launched
                lateinit var controller: LiteController
                launched.onActivity { controller = ViewModelProvider(it)[LiteController::class.java] }
                withTimeout(15_000) { controller.state.first { it.settings == settings && it.selectedProfile == owned.id } }
                ownedStart = checkNotNull(controller.prepareOwnedConnect()) { "The actual controller rejected fixture start admission" }
                val preparedStart = checkNotNull(ownedStart)
                launched.onActivity { check(controller.connectOwned(preparedStart)) { "The owned fixture start was not sent" } }
                val running = withTimeout(60_000) { graph.vpn.first { it.phase in setOf(VpnPhase.Running, VpnPhase.Failed) } }
                assertEquals(running.error ?: "Actual Tun must become ready", VpnPhase.Running, running.phase)
                assertTrue(running.listening && !running.suspended && running.initialNetwork?.vpnEnabled == true)
                assertEquals(owned.id, running.endpoint?.configuration?.profileId)
                assertEquals(settings, running.initialSettings)
                assertTrue(running.sessionGeneration > 0)
                ownedSession = running
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                val vpn = withTimeout(15_000) {
                    var network = connectivity.allNetworks.firstOrNull {
                        connectivity.getNetworkCapabilities(it)?.let { caps ->
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == context.applicationInfo.uid
                        } == true
                    }
                    while (network == null) {
                        delay(50)
                        network = connectivity.allNetworks.firstOrNull {
                            connectivity.getNetworkCapabilities(it)?.let { caps ->
                                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && caps.ownerUid == context.applicationInfo.uid
                            } == true
                        }
                    }
                    network
                }
                val link = requireNotNull(connectivity.getLinkProperties(vpn))
                val tunInterface = requireNotNull(link.interfaceName)
                assertTrue(link.linkAddresses.isNotEmpty())
                assertTrue(requireNotNull(NetworkInterface.getByName(tunInterface)).isUp)
                fun admitted(): Boolean = graph.vpn.value.let {
                    it.phase == VpnPhase.Running && it.endpoint === running.endpoint &&
                        it.sessionGeneration == running.sessionGeneration && it.listening && !it.suspended &&
                        it.initialNetwork?.vpnEnabled == true &&
                        connectivity.getNetworkCapabilities(vpn)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                assertTrue(admitted())
                receipt.put("tunInterface", tunInterface).put("vpnNetwork", vpn.toString())
                    .put("sessionGeneration", running.sessionGeneration)
                val fixture = SocketFixture(context, fixturePackage)
                sockets = fixture
                fixture.bind()
                val ready = fixture.request(1, Bundle().apply {
                    putInt("tcpPort", requireNotNull(arguments.getString("ownerTcpPort")).toInt())
                    putInt("udpPort", requireNotNull(arguments.getString("ownerUdpPort")).toInt())
                })
                assertTrue(ready.getBoolean("live"))
                assertEquals(fixtureUid, ready.getInt("uid"))
                assertEquals(fixturePackage, ready.getString("package"))
                assertNotEquals(Process.myPid(), ready.getInt("pid"))
                val resolver = ConnectionOwnerResolver.forTun(context, ::admitted)
                for ((prefix, protocol) in listOf("tcp" to 6, "udp" to 17)) {
                    val source = requireNotNull(ConnectionOwnerResolver.numericEndpoint(requireNotNull(ready.getString(prefix + "Source"))))
                    val target = requireNotNull(ConnectionOwnerResolver.numericEndpoint(requireNotNull(ready.getString(prefix + "Target"))))
                    val actual = resolver.resolve(protocol, source, target)
                    assertNotNull("The other APK must retain its actual $prefix socket until lookup completes", actual)
                    assertEquals(fixtureUid, actual!!.uid)
                    assertEquals(fixturePackage, actual.process)
                    assertEquals("", actual.processPath)
                    receipt.put(prefix, JSONObject().put("source", ready.getString(prefix + "Source"))
                        .put("target", ready.getString(prefix + "Target")).put("resolvedUid", actual.uid).put("package", actual.process))
                }
                val retained = fixture.request(2)
                assertTrue(retained.getBoolean("live"))
                for (key in listOf("uid", "pid", "package", "token", "tcpSource", "tcpTarget", "udpSource", "udpTarget")) {
                    assertEquals("The live fixture lease changed during lookup: $key", ready.get(key), retained.get(key))
                }
                assertTrue(admitted())
                receipt.put("pid", ready.getInt("pid")).put("token", ready.getString("token")).put("lookupPassed", true)
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            receipt.put("failure", failure.toString())
            throw failure
        } finally {
            withContext(NonCancellable) {
                val cleanupDeadline = minOf(hardDeadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(35))
                var cleanupFailure: Throwable? = null
                fun remember(failure: Throwable) {
                    val previous = cleanupFailure
                    if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                }
                suspend fun cleanup(limitMillis: Long, action: suspend () -> Unit) {
                    try {
                        val remaining = TimeUnit.NANOSECONDS.toMillis(cleanupDeadline - System.nanoTime())
                        check(remaining > 0) { "Original test cleanup budget expired" }
                        withTimeout(minOf(limitMillis, remaining)) { action() }
                    } catch (failure: Throwable) { remember(failure) }
                }
                cleanup(3_000) { sockets?.close() }
                var runtimeReleased = ownedStart == null
                cleanup(16_000) {
                    val request = ownedStart
                    if (request != null) {
                        val result = request.cancelAndJoin(awaitMillis = 15_000)
                        receipt.put("ownedStartCancellation", result.name)
                        when (result) {
                            OwnedStartCancellation.Prevented, OwnedStartCancellation.CancelledAndJoined -> {
                                val observed = ownedSession
                                val stopped = withTimeout(750) {
                                    graph.vpn.first {
                                        it.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed) ||
                                            (it.phase == VpnPhase.Running && (observed == null ||
                                                it.endpoint !== observed.endpoint || it.sessionGeneration != observed.sessionGeneration))
                                    }
                                }
                                check(stopped.phase == VpnPhase.Stopped && stopped.endpoint == null && !LiteVpnService.wasRequested(context)) {
                                    "The owned request ended but a newer runtime owns the service; preserve its data"
                                }
                                runtimeReleased = true
                            }
                            OwnedStartCancellation.Superseded -> error("The owned fixture start was superseded; do not stop or restore a newer intent")
                            OwnedStartCancellation.TerminationUnconfirmed -> error("Owned fixture termination is unconfirmed; do not restore runtime data")
                        }
                    }
                }
                cleanup(3_000) { runInterruptible(Dispatchers.IO) { activity?.close() } }
                cleanup(10_000) {
                    check(runtimeReleased) { "Unproved runtime ownership prevents persistent rollback" }
                    privateDomainTransaction(context.filesDir) {
                        check(graph.vpn.value.phase == VpnPhase.Stopped && graph.vpn.value.endpoint == null && !LiteVpnService.wasRequested(context)) {
                            "A newer runtime arrived before rollback; preserve its data"
                        }
                        val conflicts = restoreOwnedWrites(context, graph, originalSettings, originalProfiles,
                            ownedDocument, fixtureWrittenSettings)
                        check(conflicts.isEmpty()) { conflicts.joinToString("; ") }
                        assertEquals(originalSettings, graph.settings.read())
                        assertEquals(originalProfiles, graph.profiles.snapshot())
                    }
                }
                receipt.put("cleanupPassed", cleanupFailure == null).put("cleanupBudgetMillis", 35_000)
                    .put("workBudgetMillis", 75_000).put("runtimeReleased", runtimeReleased)
                cleanupFailure?.let { receipt.put("cleanupFailure", it.toString()) }
                cleanup(1_000) {
                    val output = java.io.File(context.getExternalFilesDir(null), "captures/process-owner/receipt.json")
                    withContext(Dispatchers.IO) {
                        output.parentFile!!.mkdirs()
                        output.writeText(receipt.toString(2))
                    }
                }
                cleanupFailure?.let { if (primaryFailure == null) throw it else primaryFailure!!.addSuppressed(it) }
            }
        }
    }

    private suspend fun restoreOwnedWrites(context: Context, graph: LiteApplication, previousSettings: LiteSettings,
        previousProfiles: ProfileSnapshot, owned: ProfileDocumentSnapshot?, writtenSettings: LiteSettings?): List<String> =
        privateDomainTransaction(context.filesDir) {
            val conflicts = mutableListOf<String>()
            if (writtenSettings != null) {
                if (graph.settings.read() == writtenSettings) {
                    graph.settings.save(previousSettings, vpnAuthorized = true)
                    assertEquals(previousSettings, graph.settings.read())
                } else conflicts += "Newer settings preserved"
            }
            if (owned != null) {
                val profiles = graph.profiles.snapshot()
                val current = if (profiles.profiles.any { it.id == owned.profile.id }) graph.profiles.documentSnapshot(owned.profile.id) else null
                if (current == owned) {
                    if (profiles.selectedId == owned.profile.id) {
                        val oldSelection = previousProfiles.selectedId
                        if (oldSelection == null || graph.profiles.profileSummary(oldSelection) == previousProfiles.profiles.firstOrNull { it.id == oldSelection }) {
                            oldSelection?.let { graph.profiles.select(it) }
                        } else conflicts += "Previous selection changed; not restored"
                    } else conflicts += "Newer profile selection preserved"
                    if (graph.vpn.value.endpoint?.configuration?.profileId != owned.profile.id) {
                        graph.profiles.delete(owned.profile.id)
                        assertNull(graph.profiles.profileSummary(owned.profile.id))
                    } else conflicts += "Newer runtime still references the fixture profile"
                } else conflicts += "Changed or missing fixture document preserved"
            }
            if (conflicts.isEmpty()) {
                writtenSettings?.let { assertEquals(previousSettings, graph.settings.read()) }
            }
            conflicts
        }

    private suspend fun exerciseCancelledStorageRestore(context: Context, graph: LiteApplication) {
        val previous = privateDomainTransaction(context.filesDir) { graph.settings.read() to graph.profiles.snapshot() }
        var document: ProfileDocumentSnapshot? = null
        var written: LiteSettings? = null
        var recoveryFailure: Throwable? = null
        coroutineScope {
            val committed = CompletableDeferred<Unit>()
            val worker = launch {
                try {
                    withContext(NonCancellable) {
                        privateDomainTransaction(context.filesDir) {
                            val profile = graph.profiles.importText("Cancelled owner ${UUID.randomUUID()}", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
                            document = graph.profiles.documentSnapshot(profile.id)
                            graph.profiles.select(profile.id)
                            written = graph.settings.save(previous.first.copy(autoCheckUpdate = !previous.first.autoCheckUpdate), vpnAuthorized = true)
                            committed.complete(Unit)
                        }
                    }
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        try {
                            withTimeout(5_000) {
                                check(restoreOwnedWrites(context, graph, previous.first, previous.second, document, written).isEmpty())
                            }
                        } catch (failure: Throwable) { recoveryFailure = failure }
                    }
                }
            }
            committed.await()
            worker.cancelAndJoin()
        }
        recoveryFailure?.let { throw it }
        privateDomainTransaction(context.filesDir) {
            assertEquals(previous.first, graph.settings.read())
            assertEquals(previous.second, graph.profiles.snapshot())
        }
    }

    private suspend fun exerciseSupersededStorageRestore(context: Context, graph: LiteApplication) {
        val previous = privateDomainTransaction(context.filesDir) { graph.settings.read() to graph.profiles.snapshot() }
        var oldDocument: ProfileDocumentSnapshot? = null
        var newerDocument: ProfileDocumentSnapshot? = null
        var written: LiteSettings? = null
        var newerSettings: LiteSettings? = null
        try {
            withContext(NonCancellable) {
                privateDomainTransaction(context.filesDir) {
                    val old = graph.profiles.importText("Old owner ${UUID.randomUUID()}", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
                    oldDocument = graph.profiles.documentSnapshot(old.id)
                    graph.profiles.select(old.id)
                    written = graph.settings.save(previous.first.copy(autoCheckUpdate = !previous.first.autoCheckUpdate), vpnAuthorized = true)
                    val newer = graph.profiles.importText("New owner ${UUID.randomUUID()}", "mode: direct\nrules:\n  - MATCH,DIRECT\n")
                    newerDocument = graph.profiles.documentSnapshot(newer.id)
                    graph.profiles.select(newer.id)
                    newerSettings = graph.settings.save(checkNotNull(written).copy(locale = if (written!!.locale == "ja") "en" else "ja"), vpnAuthorized = true)
                    val conflicts = restoreOwnedWrites(context, graph, previous.first, previous.second, oldDocument, written)
                    assertTrue(conflicts.contains("Newer settings preserved"))
                    assertTrue(conflicts.contains("Newer profile selection preserved"))
                    assertEquals(newerSettings, graph.settings.read())
                    assertEquals(newer.id, graph.profiles.snapshot().selectedId)
                    assertEquals(newerDocument, graph.profiles.documentSnapshot(newer.id))
                    assertNull(graph.profiles.profileSummary(old.id))
                    oldDocument = null
                }
            }
        } finally {
            withContext(NonCancellable) {
                withTimeout(5_000) {
                    val latestWritten = newerSettings ?: written
                    if (newerDocument != null) {
                        restoreOwnedWrites(context, graph, previous.first, previous.second, newerDocument, latestWritten)
                        if (oldDocument != null && graph.profiles.profileSummary(oldDocument!!.profile.id) != null) {
                            restoreOwnedWrites(context, graph, previous.first, previous.second, oldDocument, null)
                        }
                    } else if (oldDocument != null) {
                        restoreOwnedWrites(context, graph, previous.first, previous.second, oldDocument, latestWritten)
                    }
                    privateDomainTransaction(context.filesDir) {
                        assertEquals(previous.first, graph.settings.read())
                        assertEquals(previous.second, graph.profiles.snapshot())
                    }
                }
            }
        }
    }

    private fun freeUserPort(): Int {
        repeat(100) {
            val port = kotlin.random.Random.nextInt(20_000, 45_000)
            try {
                ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)); return port }
            } catch (_: java.io.IOException) { }
        }
        error("No isolated legal mixed port is available")
    }

    private class SocketFixture(private val context: Context, private val packageName: String) {
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
            bound = context.bindService(Intent().setComponent(ComponentName(packageName, "$packageName.SocketOwnerService")),
                connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Actual separate-package service binding failed" }
            remote = withTimeout(5_000) { connected.receive() }
        }
        suspend fun request(operation: Int, input: Bundle = Bundle()): Bundle {
            input.putString("token", token)
            checkNotNull(remote).send(Message.obtain(null, operation).apply { data = input; replyTo = replies })
            val result = withTimeout(15_000) { responses.receive() }
            check(!result.containsKey("error")) { result.getString("error").orEmpty() }
            assertEquals(token, result.getString("token"))
            return result
        }
        suspend fun close() {
            try { if (bound) assertTrue(request(3).getBoolean("closed")) }
            finally { if (bound) context.unbindService(connection); bound = false; remote = null }
        }
    }
}
