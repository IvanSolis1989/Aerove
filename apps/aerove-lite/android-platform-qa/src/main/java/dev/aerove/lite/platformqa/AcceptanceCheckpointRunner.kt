package dev.aerove.lite.platformqa

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.FileObserver
import android.os.Process
import android.os.SystemClock
import androidx.test.runner.AndroidJUnitRunner
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.core.MeowNativeCore
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.flutter.verifyNativeLiteBusinessSourceReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Test-only read-only observer in the UI instrumentation; never starts/stops a production owner. */
class AcceptanceCheckpointRunner : AndroidJUnitRunner() {
    private var observer: Checkpoints? = null
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
    }
    override fun onStart() {
        val arguments = androidx.test.platform.app.InstrumentationRegistry.getArguments()
        if (arguments.getString("class")?.startsWith("dev.aerove.lite.FlutterProductionUiTest#") == true) {
            observer = Checkpoints(this, arguments).also { it.start() }
        }
        try { super.onStart() } finally { observer?.close() }
    }
}

private class Checkpoints(private val runner: AndroidJUnitRunner, private val args: Bundle) : AutoCloseable {
    private val app get() = runner.targetContext.applicationContext as LiteApplication
    private val token = requireNotNull(args.getString("qaToken")).also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) }
    private val parent get() = File(requireNotNull(app.getExternalFilesDir(null)), "flutter-production-qa")
    private val root get() = File(parent, token)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "acceptance-native-checkpoint") }
    private val watches = linkedMapOf<String, FileObserver>()
    private val submitted = mutableSetOf<String>()
    @Volatile private var closed = false
    private val installedApkSha256 by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        File(app.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private val profileCancel by lazy { ProfileCancelAttestor(app, root, args) }
    private val stages = (setOf("imported-selected", "vpn-active", "background-active", "resumed-active",
        "vpn-stopped", "vpn-permission-cancelled", "functional-running-for-host-restart", "restart-observed") + ProfileCancelAttestor.stages).toSet()

    fun start() {
        check(parent.isDirectory || parent.mkdirs())
        // Never precreate root: the black-box scenario must own its fresh qaToken directory.
        watch(parent) { attach() }
        attach()
    }
    @Synchronized private fun watch(directory: File, action: () -> Unit) {
        if (closed || !directory.isDirectory || watches.containsKey(directory.path)) return
        val observer = object : FileObserver(directory, FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) { if (!closed) action() }
        }
        watches[directory.path] = observer
        observer.startWatching()
    }
    @Synchronized private fun attach() {
        if (closed || !root.isDirectory) return
        check(root.canonicalFile == root.absoluteFile)
        watch(root) { attach() }
        val directory = File(root, "checkpoints")
        if (!directory.isDirectory) return
        watch(directory) { scan(directory) }
        scan(directory) // One catch-up after registration closes the creation/registration race.
    }
    @Synchronized private fun scan(directory: File) {
        if (closed) return
        directory.listFiles()?.filter { it.extension == "json" && it.nameWithoutExtension in stages }?.forEach { file ->
            if (submitted.add(file.name)) worker.execute { attest(file) }
        }
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun atomic(file: File, value: JSONObject): String {
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        check(!file.exists()) { "Refuse checkpoint evidence overwrite" }
        val bytes = (value.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        val pending = File(file.parentFile, file.name + ".partial")
        check(pending.createNewFile())
        java.io.FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        check(pending.renameTo(file))
        return digest(bytes)
    }
    private fun attest(file: File) {
        var request: JSONObject? = null
        var requestHash = ""
        val evidence = JSONObject().put("schema", 1).put("producer", "AcceptanceCheckpointRunner")
            .put("readOnly", true).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        var failure: Throwable? = null
        try {
            check(file.isFile && file.length() in 1..65536 && file.canonicalFile == file.absoluteFile)
            val bytes = file.readBytes(); requestHash = digest(bytes)
            val row = JSONObject(bytes.toString(Charsets.UTF_8)); request = row
            check(row.getInt("schema") == 1 && row.getString("qaToken") == token && row.getString("stage") == file.nameWithoutExtension)
            check(UUID.fromString(row.getString("nonce")).toString() == row.getString("nonce"))
            check(row.getInt("pid") == Process.myPid()) { "Checkpoint belongs to another process" }
            listOf("source", "tree", "apkSha256").forEach { check(row.getString(it) == args.getString(it)) }
            check(installedApkSha256 == row.getString("apkSha256"))
            evidence.put("qaToken", token).put("stage", row.getString("stage")).put("nonce", row.getString("nonce"))
                .put("requestSha256", requestHash).put("pid", Process.myPid())
            listOf("source", "tree", "apkSha256").forEach { evidence.put(it, row.getString(it)) }
            runBlocking { withTimeout(30_000) {
                if (row.optString("checkpointKind") == "profile-update-cancel") profileCancel.capture(row,evidence)
                else snapshot(row, evidence)
            } }
            check(digest(file.readBytes()) == requestHash) { "Checkpoint changed during attestation" }
            evidence.put("status", "attested").put("attestedElapsedRealtimeMs", SystemClock.elapsedRealtime())
        } catch (error: Throwable) {
            failure = error
            evidence.put("status", "failed").put("error", error.javaClass.name + ": " + error.message)
        }
        val row = request ?: return // Invalid JSON cannot receive a valid matching ACK; the UI times out red.
        try {
            val nonce = UUID.fromString(row.getString("nonce")).toString()
            val relative = "native-state/$nonce.json"
            val evidenceHash = atomic(File(root, relative), evidence)
            val ack = JSONObject().put("schema", 1).put("qaToken", token).put("stage", file.nameWithoutExtension)
                .put("nonce", nonce).put("requestSha256", requestHash).put("pid", Process.myPid())
                .put("status", if (failure == null) "attested" else "failed")
                .put("evidencePath", relative).put("evidenceSha256", evidenceHash)
            listOf("source", "tree", "apkSha256").forEach { ack.put(it, args.getString(it)) }
            if (failure != null) ack.put("error", failure.message)
            atomic(File(root, "native-acks/${file.name}"), ack)
        } catch (error: Throwable) { android.util.Log.e("AcceptanceNative", "Cannot publish checkpoint ACK", error) }
    }
    private suspend fun snapshot(request: JSONObject, out: JSONObject) {
        val expectedVpn = request.getBoolean("expectedVpn")
        val before = app.vpn.first { if (expectedVpn) it.phase == VpnPhase.Running && it.endpoint != null
            else it.phase == VpnPhase.Stopped && it.endpoint == null }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val networks = cm.allNetworks.filter { network -> cm.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.ownerUid == app.applicationInfo.uid
        } == true }
        val handles = networks.map { it.networkHandle.toString() }.sorted()
        val uiHandles = request.getJSONArray("networkHandles").let { a -> List(a.length()) { a.getString(it) }.sorted() }
        check(handles == uiHandles) { "UI and OS-owned VPN handles differ" }
        out.put("phase", before.phase.name).put("expectedVpn", expectedVpn).put("networkHandles", JSONArray(handles))
            .put("targetUid", app.applicationInfo.uid).put("serviceOwner", before.serviceOwner)
            .put("sessionGeneration", before.sessionGeneration).put("listening", before.listening).put("suspended", before.suspended)
        if (expectedVpn) check(before.phase == VpnPhase.Running && before.endpoint != null && before.listening &&
            !before.suspended && networks.isNotEmpty() && before.serviceOwner > 0 && before.sessionGeneration > 0)
        else check(before.phase == VpnPhase.Stopped && before.endpoint == null && networks.isEmpty())
        val identity = checkNotNull(app.flutterRuntimeIdentity.first { value -> value != null &&
            app.flutterRuntimeIdentityIsCurrent(value) && (if (expectedVpn)
                value.proof.serviceOwner == before.serviceOwner && value.proof.sessionGeneration == before.sessionGeneration
            else app.flutterQueryGraph()?.let { !it.listening && it.identity == value } == true) })
        check(app.flutterRuntimeIdentityIsCurrent(identity)) { "Historical runtime receipt is not a current owner" }
        val persisted = checkNotNull(app.runtimeHandoffs.readAppliedIdentity())
        check(persisted == identity) { "Persistent applied identity differs from the live owner" }
        val applied = checkNotNull(app.runtimeHandoffs.readLastApplied())
        check(applied.revision == identity.revision && applied.intentEpoch == identity.intentEpoch &&
            applied.handoff.compiledSha256 == identity.compiledSha256)
        val graph = app.flutterQueryGraph()
        val endpoint = if (expectedVpn) checkNotNull(before.endpoint) else checkNotNull(graph).also {
            check(!it.listening && it.identity == identity)
        }.endpoint
        if (expectedVpn) check(identity.proof.serviceOwner == before.serviceOwner &&
            identity.proof.sessionGeneration == before.sessionGeneration)
        val (route, witness) = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use { api ->
            api.routeSnapshot() to api.selectionWitness()
        }
        val sourceProof = JSONObject(applied.handoff.runtimeSettingsJson).getJSONObject("flutterSetup").getJSONObject("data-source-proof")
        val source = verifyNativeLiteBusinessSourceReceipt(app, sourceProof)
        check(witness.complete && witness.matchesRaw(source.selectedMap) &&
            witness.stamp.coreEpoch == identity.proof.coreEpoch && route.coreEpoch.toString() == identity.proof.coreEpoch &&
            route.routeVersion.toString() == witness.stamp.routeVersion) { "Live Native route/selection/source witness mismatch" }
        val capabilityBytes = MeowNativeCore.capabilities()
        val caps = dev.aerove.lite.core.MeowCapabilitiesCodec.decode(capabilityBytes)
        check(caps.source.overlaySha256 == args.getString("meowOverlaySha256") &&
            caps.source.adaptedFileManifestSha256 == args.getString("meowFileManifestSha256"))
        out.put("runtimeRevision", identity.revision).put("intentEpoch", identity.intentEpoch)
            .put("runtimeServiceOwner", identity.proof.serviceOwner).put("runtimeSessionGeneration", identity.proof.sessionGeneration)
            .put("compiledSha256", identity.compiledSha256).put("coreEpoch", route.coreEpoch.toString())
            .put("routeVersion", route.routeVersion.toString()).put("selectionWitnessId", witness.witnessId)
            .put("selectionComplete", witness.complete).put("rawSelectionMatchesSharedSql", true)
            .put("sourceDefinitionSha256", source.definitionSha256).put("coreCommit", caps.source.commit)
            .put("overlaySha256", caps.source.overlaySha256).put("adaptedFileManifestSha256", caps.source.adaptedFileManifestSha256)
            .put("capabilitiesSha256", digest(capabilityBytes.toByteArray())).put("wireVerified", caps.wireVerified)
            .put("queryGraphParked", !expectedVpn).put("controlPort", endpoint.port)
        if (expectedVpn) {
            val expectedPort = request.getInt("expectedMixedPort")
            check(expectedPort == checkNotNull(args.getString("mixedProxyPort")).toInt() && expectedPort in 1024..49151)
            check(request.getBoolean("expectedBypassPrivateRoute") == false && request.getString("fixtureRouteTarget") == "10.0.2.2")
            val uiEvidence = request.getJSONArray("networkSettingsUiEvidence")
            check(uiEvidence.length() == 2)
            val builder = checkNotNull(before.plannedBuilderSettings)
            check(!builder.network.bypassPrivateRoute)
            val networkJson = networkReadback(endpoint)
            val network = JSONObject(networkJson)
            check(network.getString("coreEpoch") == route.coreEpoch.toString())
            val settings = before.appliedNetwork ?: builder.network
            dev.aerove.lite.networksettings.verifyNetworkReadback(networkJson, route.coreEpoch,
                dev.aerove.lite.networksettings.planNetwork(settings, endpoint.port,
                    before.appliedIpv6 ?: before.initialIpv6 ?: false, before.appliedReservedFakeIpRanges))
            val actualPort = network.getJSONObject("network").getInt("mixed-port")
            check(actualPort == expectedPort)
            out.put("actualMixedPort", actualPort).put("actualBypassPrivateRoute", builder.network.bypassPrivateRoute)
                .put("networkReadbackJson", networkJson).put("networkReadbackSha256", digest(networkJson.toByteArray()))
            val tun = IndependentTunProbe(runner).capture(cm, networks.single(),
                checkNotNull(before.plannedBuilderSettings), endpoint)
            out.put("tun", tun).put("tunSeparateUidAccepted", tun.getBoolean("accepted"))
        } else {
            val expectedPort = request.getInt("expectedMixedPort")
            check(expectedPort == checkNotNull(args.getString("mixedProxyPort")).toInt() && expectedPort in 1024..49151)
            requireUserListenerClosed(expectedPort)
            out.put("tunSeparateUidAccepted", false).put("userListenerClosed", true).put("closedMixedPort", expectedPort)
        }
        check(app.vpn.value == before && app.flutterRuntimeIdentity.value == identity &&
            app.flutterRuntimeIdentityIsCurrent(identity)) { "Owner changed during checkpoint attestation" }
    }
    private suspend fun requireUserListenerClosed(port: Int) = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { socket -> socket.connect(java.net.InetSocketAddress("127.0.0.1", port), 500) }
            error("Stopped user mixed listener still accepts connections")
        } catch (failure: java.net.ConnectException) {
            var cause: Throwable? = failure
            var refused = false
            while (cause != null) {
                if (cause is android.system.ErrnoException && cause.errno == android.system.OsConstants.ECONNREFUSED) refused = true
                cause = cause.cause
            }
            check(refused || failure.message?.contains("ECONNREFUSED") == true) { "Listener closure was not proved by connection refusal" }
        }
    }
    private suspend fun networkReadback(endpoint: dev.aerove.lite.vpn.CoreEndpoint): String = withContext(Dispatchers.IO) {
        val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build()
        try {
            client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${endpoint.port}/aerove/network")
                .header("Authorization", "Bearer ${endpoint.secret}").build()).execute().use { response ->
                check(response.isSuccessful)
                val bytes = checkNotNull(response.body).byteStream().use { it.readNBytes(4097) }
                check(bytes.size <= 4096)
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            client.dispatcher.cancelAll(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    override fun close() {
        synchronized(this) { closed = true; watches.values.forEach { it.stopWatching() }; watches.clear() }
        worker.shutdownNow()
        check(worker.awaitTermination(35, TimeUnit.SECONDS)) { "Native checkpoint observer did not join" }
    }
}
