package dev.aerove.lite.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.Network
import android.os.ParcelFileDescriptor
import dev.aerove.lite.AppFilterMode
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.MainActivity
import dev.aerove.lite.core.NativeCore
import dev.aerove.lite.core.SocketProtector
import dev.aerove.lite.data.AppliedConfigSnapshot
import dev.aerove.lite.data.ProfileConflictException
import dev.aerove.lite.data.PreparedProfile
import dev.aerove.lite.data.ProfileLease
import dev.aerove.lite.data.RuntimeConfigComposer
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AndroidTunnelBackend(
    private val service: LiteVpnService,
    private val application: LiteApplication,
    private val underlying: () -> Network?,
) : TunnelBackend {
    override suspend fun open(plan: VpnPlan): TunnelSession = withContext(Dispatchers.IO) {
        val lease = Any()
        var acquired = false
        var config: File? = null
        var tun: ParcelFileDescriptor? = null
        var handle: Long? = null
        var profileLease: ProfileLease? = null
        try {
            withTimeout(45_000) {
                application.nativeLifecycle.lock(lease)
                acquired = true
            }
            val prepared = application.profiles.prepareForStart(plan.profileId)
            profileLease = application.profiles.retain(prepared)
            val runtime = File(application.filesDir, "runtime").apply { mkdirs() }
            val directory = File(runtime, "profiles/${prepared.profileId}").apply { check(isDirectory || mkdirs()) }
            val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val secret = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            config = File(directory, "${UUID.randomUUID()}.yaml")
            val builder = service.Builder()
                .setSession("AEROVE Lite")
                .setMtu(1500)
                .addAddress("198.18.0.1", 30)
                .addDnsServer("198.18.0.2")
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)
                .setMetered(false)
                .setConfigureIntent(PendingIntent.getActivity(
                    service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ))
            if (plan.settings.ipv6) {
                builder.addAddress("fdfe:dcba:9876::1", 126)
                    .addDnsServer("fdfe:dcba:9876::2").addRoute("::", 0)
            }
            when (plan.settings.appFilterMode) {
                AppFilterMode.All -> builder.addDisallowedApplication(service.packageName)
                AppFilterMode.Exclude -> (plan.settings.packages + service.packageName).forEach(builder::addDisallowedApplication)
                AppFilterMode.Include -> plan.settings.packages.filterNot { it == service.packageName }.also {
                    require(it.isNotEmpty()) { "Select at least one application" }
                }.forEach(builder::addAllowedApplication)
            }
            underlying()?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
            tun = checkNotNull(builder.establish()) { "VPN authorization was revoked" }
            config.writeText(RuntimeConfigComposer(runtime).compose(prepared, plan.settings, tun.fd, port, secret))
            config.setReadable(false, false)
            config.setReadable(true, true)
            val endpoint = CoreEndpoint(port, secret, AppliedConfigSnapshot(prepared.profileId, config))
            handle = NativeCore.start(config.path, directory.path, tun.fd, SocketProtector(service::protect))
            Session(checkNotNull(handle), tun, config, endpoint, prepared, checkNotNull(profileLease), lease)
        } catch (failure: Throwable) {
            stopNativeSession(
                join = { handle?.let(NativeCore::stop) },
                cleanup = { cleanupStoppedResources(tun, config, profileLease, lease, acquired) },
                warn = ::warnStoppedCleanup,
            )
            throw failure
        }
    }

    private inner class Session(
        private val handle: Long,
        private val tun: ParcelFileDescriptor,
        private val config: File,
        override val endpoint: CoreEndpoint,
        private val prepared: PreparedProfile,
        private val profileLease: ProfileLease,
        private val lease: Any,
    ) : TunnelSession {
        override suspend fun awaitReady() = withTimeout(45_000) {
            while (true) {
                val snapshot = NativeCore.status(handle)
                when (snapshot.state) {
                    NativeCore.State.Running -> break
                    NativeCore.State.Starting -> delay(50)
                    else -> error(snapshot.error ?: "Core stopped before becoming ready")
                }
            }
            verifyApi(endpoint)
            try {
                application.profiles.markUsable(prepared)
            } catch (_: ProfileConflictException) {
                // A concurrent subscription update must not mark its newer revision as tested.
            }
        }

        override suspend fun awaitExit() {
            while (true) {
                delay(500)
                val snapshot = NativeCore.status(handle)
                check(snapshot.state == NativeCore.State.Running) { snapshot.error ?: "Core exited" }
            }
        }

        override suspend fun stop() = withContext(Dispatchers.IO) {
            stopNativeSession(
                join = { NativeCore.stop(handle) },
                cleanup = { cleanupStoppedResources(tun, config, profileLease, lease) },
                warn = ::warnStoppedCleanup,
            )
        }
    }

    private suspend fun cleanupStoppedResources(
        tun: ParcelFileDescriptor?, config: File?, profileLease: ProfileLease?, lease: Any, acquired: Boolean = true,
    ) {
        try {
            try {
                tun?.close()
            } finally {
                if (config != null && !config.delete() && config.exists()) warnStoppedCleanup(java.io.IOException("Runtime configuration cleanup failed"))
                profileLease?.let { application.profiles.release(it) }
            }
        } finally {
            if (acquired) application.nativeLifecycle.unlock(lease)
        }
    }

    private fun warnStoppedCleanup(failure: Exception) {
        android.util.Log.w("AEROVE Lite", "Core joined; private-file cleanup was incomplete", failure)
    }

    private fun verifyApi(endpoint: CoreEndpoint) {
        val client = OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder().url("http://127.0.0.1:${endpoint.port}/version")
                .header("Authorization", "Bearer ${endpoint.secret}").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Authenticated core controller is not ready" }
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
