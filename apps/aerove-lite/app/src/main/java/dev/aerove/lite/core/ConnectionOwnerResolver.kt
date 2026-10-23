package dev.aerove.lite.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Looper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.LinkedHashMap
import org.json.JSONObject

/** One instance belongs to one admitted Tun session. Proxy runtimes receive no resolver. */
class ConnectionOwnerResolver internal constructor(
    private val lookupUid: (Int, InetSocketAddress, InetSocketAddress) -> Int,
    private val packages: (Int) -> Array<String>?,
    private val admitted: () -> Boolean,
    private val isMainThread: () -> Boolean,
) : ConnectionOwnerLookup {
    data class Identity(val uid: Int, val process: String, val processPath: String = "")
    private val packageCache = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > 256
    }

    fun resolve(protocol: Int, source: InetSocketAddress, target: InetSocketAddress): Identity? {
        if (protocol !in listOf(6, 17) || source.isUnresolved || target.isUnresolved ||
            source.port == 0 || target.port == 0 || isMainThread() || !admitted()) return null
        return try {
            val uid = lookupUid(protocol, source, target)
            if (uid < 0 || !admitted()) return null
            val name = synchronized(packageCache) {
                packageCache[uid] ?: packages(uid)?.firstOrNull()?.takeIf(String::isNotEmpty)
                    .orEmpty().also { packageCache[uid] = it }
            }
            if (admitted()) Identity(uid, name) else null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** JNI callback wire: null denotes unknown, never UID zero. No hostname DNS lookup. */
    override fun resolveConnectionOwner(protocol: Int, source: String, target: String): String? {
        val local = numericEndpoint(source) ?: return null
        val remote = numericEndpoint(target) ?: return null
        val identity = resolve(protocol, local, remote) ?: return null
        return JSONObject().put("uid", identity.uid).put("process", identity.process)
            .put("processPath", identity.processPath).toString()
    }

    fun clearPackageCache() = synchronized(packageCache) { packageCache.clear() }

    companion object {
        fun forTun(context: Context, admitted: () -> Boolean): ConnectionOwnerResolver {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val manager = context.packageManager
            return ConnectionOwnerResolver(
                lookupUid = { protocol, source, target -> connectivity?.getConnectionOwnerUid(protocol, source, target) ?: -1 },
                packages = manager::getPackagesForUid,
                admitted = admitted,
                isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            )
        }

        internal fun numericEndpoint(value: String): InetSocketAddress? { return try {
            val separator = value.lastIndexOf(':')
            if (separator < 1) return null
            val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
            if (host.isEmpty() || host.any { it !in "0123456789abcdefABCDEF:." }) return null
            if (':' !in host && (host.count { it == '.' } != 3 || host.any { it !in "0123456789." })) return null
            val port = value.substring(separator + 1).toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            InetSocketAddress(InetAddress.getByName(host), port)
        } catch (_: IllegalArgumentException) { null } catch (_: java.net.UnknownHostException) { null } }
    }
}
