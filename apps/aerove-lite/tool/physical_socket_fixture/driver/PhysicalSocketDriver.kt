package dev.aerove.lite.platformqa

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Standalone diagnostic adapter, not linked into the stock release gate. */
internal class PhysicalSocketDriver(private val context: Context) {
    data class Pin(val appSha: String, val appSigner: String, val fixtureSha: String, val fixtureSigner: String)
    data class Challenge(val stage: String, val host: String, val port: Int, val nonce: String)
    data class Capture(val row: JSONObject, val nativeJson: String?)

    /**
     * Supply the actual on-device CoreApiClient protocolSnapshot("connections") for the on leg only.
     * The outer runner still owns actual VPN routes/owner, Find process readback, and stop join.
     */
    suspend fun collect(pin: Pin, target: Challenge, findProcessReadback: Boolean,
        nativeConnections: (suspend () -> String)? = null): Capture = withContext(Dispatchers.IO) {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(target.stage in setOf("off-before", "on", "off-after") &&
            UUID.fromString(target.nonce).toString() == target.nonce && target.port in 1..65535)
        val active = target.stage == "on"
        check(active == (nativeConnections != null) && (!active || findProcessReadback))
        check(context.packageName == AEROVE && Process.myUid() == context.applicationInfo.uid)
        val app = installed(AEROVE, pin.appSha, pin.appSigner)
        val fixture = installed(FIXTURE, pin.fixtureSha, pin.fixtureSigner)
        check(app.uid == Process.myUid() && fixture.uid != app.uid &&
            fixture.uid / 100000 == app.uid / 100000 && fixture.uid % 100000 in 10000..19999 &&
            (fixture.flags and ApplicationInfo.FLAG_TEST_ONLY) != 0 &&
            context.packageManager.getPackagesForUid(fixture.uid)?.toList() == listOf(FIXTURE))
        val start = SystemClock.elapsedRealtimeNanos()
        val session = Lease(fixture.uid, target.nonce)
        try {
            val remote = session.bind()
            val first = session.request(remote, 1, Bundle().apply {
                putString("stage", target.stage); putString("host", target.host); putInt("port", target.port)
            }, 5000)
            check(first.getInt("httpStatus") == 200 &&
                first.getString("body") == "AEROVE_PHYSICAL_HTTP:" + target.nonce &&
                first.containsKey("explicitProxy") && !first.getBoolean("explicitProxy") &&
                first.containsKey("redirected") && !first.getBoolean("redirected") &&
                first.getString("destinationIp") == target.host && first.getInt("destinationPort") == target.port)
            val opened = first.getLong("openedNs")
            check(opened > start)
            val row = JSONObject().put("stage", target.stage).put("nonce", target.nonce)
                .put("startNs", start).put("openedNs", opened).put("aerovePid", Process.myPid())
                .put("fixtureUid", fixture.uid).put("fixturePid", first.getInt("fixturePid"))
                .put("sourceIp", first.getString("sourceIp")).put("sourcePort", first.getInt("sourcePort"))
                .put("destinationIp", target.host).put("destinationPort", target.port)
                .put("httpStatus", 200).put("body", first.getString("body"))
                .put("explicitProxy", false).put("redirected", false)
            val flow = PhysicalFlow(checkNotNull(first.getString("sourceIp")), first.getInt("sourcePort"),
                target.host, target.port, fixture.uid)
            check(flow.sourcePort in 1..65535)
            var raw: String? = null
            var observed = 0L
            if (nativeConnections != null) {
                val sample = withTimeout(4000) {
                    var selected: Pair<String, Long>? = null
                    while (selected == null) {
                        val data = nativeConnections()
                        val whenNs = SystemClock.elapsedRealtimeNanos()
                        check(data.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
                        if (exactPhysicalTun(data, flow)) selected = data to whenNs else delay(60)
                    }
                    selected
                }
                raw = sample.first
                observed = sample.second
                val retained = session.request(remote, 2, Bundle(), 700)
                for (key in listOf("sourceIp", "destinationIp"))
                    check(retained.getString(key) == first.getString(key))
                for (key in listOf("fixturePid", "sourcePort", "destinationPort"))
                    check(retained.getInt(key) == first.getInt(key))
                check(observed > opened && retained.getLong("wireObservedNs") > observed)
                row.put("findProcessEnabled", findProcessReadback).put("nativeObservedNs", observed)
                    .put("nativeSha256", sha(raw.toByteArray(Charsets.UTF_8)))
            }
            val close = session.request(remote, 3, Bundle(), 700)
            val end = SystemClock.elapsedRealtimeNanos()
            val closed = close.getLong("closedNs")
            check(close.getBoolean("socketClosed") && close.getInt("fixturePid") == first.getInt("fixturePid") &&
                closed > maxOf(opened, observed) && end > closed &&
                (!active || close.getLong("wireObservedNs") in observed + 1 until closed))
            Capture(row.put("closedNs", closed).put("endNs", end).put("socketClosed", true), raw)
        } finally {
            session.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun installed(name: String, apk: String, cert: String): ApplicationInfo {
        val pi = context.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
        val app = checkNotNull(pi.applicationInfo)
        val signatures = checkNotNull(pi.signingInfo).apkContentsSigners
        check(signatures.size == 1 && sha(signatures.single().toByteArray()) == cert)
        check(app.splitSourceDirs.isNullOrEmpty() && File(app.sourceDir).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n != 0) digest.update(buffer, 0, n)
            }
            digest.digest().hex() == apk
        }) { "Installed APK differs from its pinned bytes" }
        return app
    }

    private inner class Lease(private val uid: Int, private val nonce: String) : AutoCloseable {
        private val events = Channel<Messenger>(1)
        private val thread = HandlerThread("aerove-physical-replies").apply { start() }
        private var bound = false
        private val component = ComponentName(FIXTURE, "$FIXTURE.PhysicalSocketService")
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (name == component) events.trySend(Messenger(binder)) else events.close()
            }
            override fun onServiceDisconnected(name: ComponentName) { events.close() }
            override fun onBindingDied(name: ComponentName) { events.close() }
            override fun onNullBinding(name: ComponentName) { events.close() }
        }

        suspend fun bind(): Messenger {
            bound = context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
            check(bound)
            return withTimeout(2500) { events.receive() }
        }

        suspend fun request(remote: Messenger, op: Int, args: Bundle, timeout: Long): Bundle {
            val result = Channel<Bundle>(1)
            val reply = Messenger(Handler(thread.looper) {
                if (it.sendingUid == uid && it.what == op) result.trySend(it.data)
                true
            })
            try {
                args.putString("nonce", nonce)
                remote.send(Message.obtain(null, op).apply { data = args; replyTo = reply })
                return withTimeout(timeout) { result.receive() }.also {
                    check(!it.containsKey("error") && it.getString("nonce") == nonce &&
                        it.getInt("fixtureUid") == uid && it.getString("fixturePackage") == FIXTURE &&
                        it.getInt("fixturePid") > 0 && it.getInt("fixturePid") != Process.myPid())
                }
            } finally {
                result.close()
            }
        }

        override fun close() {
            try { if (bound) context.unbindService(connection) }
            finally { events.close(); thread.quitSafely() }
        }
    }

    private companion object {
        const val AEROVE = "dev.aerove.lite"
        const val FIXTURE = "dev.aerove.fixture.socketowner"
        fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        fun sha(raw: ByteArray) = MessageDigest.getInstance("SHA-256").digest(raw).hex()
    }
}

internal data class PhysicalFlow(val origin: String, val sourcePort: Int, val destination: String,
    val destinationPort: Int, val uid: Int)

internal fun exactPhysicalTun(raw: String, flow: PhysicalFlow): Boolean {
    val rows = JSONObject(raw).getJSONArray("connections")
    val matches = (0 until rows.length()).map { rows.getJSONObject(it).getJSONObject("metadata") }
        .filter { it.optString("network").equals("tcp", true) &&
            it.optString("sourceIP") == flow.origin && it.optInt("sourcePort") == flow.sourcePort &&
            it.optString("destinationIP") == flow.destination && it.optInt("destinationPort") == flow.destinationPort }
    return matches.singleOrNull()?.let {
        it.optString("type").equals("tun", true) && it.optInt("uid", -1) == flow.uid &&
            it.optString("process") == "dev.aerove.fixture.socketowner"
    } == true
}
