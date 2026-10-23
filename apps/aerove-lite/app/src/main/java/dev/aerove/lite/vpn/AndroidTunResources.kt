package dev.aerove.lite.vpn

import android.app.PendingIntent
import android.content.Intent
import android.net.Network
import android.os.ParcelFileDescriptor
import dev.aerove.lite.MainActivity
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.data.ProfileLease
import dev.aerove.lite.data.SystemDnsSnapshot
import dev.aerove.lite.networksettings.applyVpnNetworkPolicy
import dev.aerove.lite.networksettings.applyVpnTunPolicy
import java.io.File

/** Shared Builder policy; both backends use the same Android address/routes/app-filter/underlying rules. */
internal fun establishAndroidTun(service: LiteVpnService, plan: VpnPlan, port: Int, dnsEnabled: Boolean,
    dnsSnapshot: SystemDnsSnapshot, reservedRanges: List<String>, underlying: () -> Network?): ParcelFileDescriptor {
        check(android.net.VpnService.prepare(service) == null) { "VPN authorization is required" }
        val builder = service.Builder()
            .setSession("AEROVE Lite")
            .addAddress("198.18.0.1", 30)
            .setBlocking(false)
            .setMetered(false)
            .setConfigureIntent(PendingIntent.getActivity(
                service, 0, Intent(service, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ))
        val networkPlan = vpnBuilderNetworkPlan(plan, port, reservedRanges)
        applyVpnNetworkPolicy(builder, networkPlan)
        applyVpnTunPolicy(builder, networkPlan)
        if (vpnBuilderIpv6(plan)) {
            builder.addAddress("fdfe:dcba:9876::1", 126)
        }
        val builderSettings = plan.builderSettings()
        val applications = vpnApplicationPolicy(builderSettings.appFilterMode, builderSettings.packages, service.packageName)
        fun addApplication(name: String, include: Boolean) {
            try {
                if (include) builder.addAllowedApplication(name) else builder.addDisallowedApplication(name)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                android.util.Log.w("AEROVE Lite", "VPN policy skipped an uninstalled application: $name")
            }
        }
        applications.allowed?.forEach { addApplication(it, true) }
        applications.disallowed.forEach { addApplication(it, false) }
        underlying()?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        val dns = if (dnsEnabled) listOf("198.18.0.2") + if (vpnBuilderIpv6(plan)) listOf("fdfe:dcba:9876::2") else emptyList()
            else dnsSnapshot.interfaces[dnsSnapshot.defaultInterface].orEmpty().filter { vpnBuilderIpv6(plan) || ':' !in it }
        check(dns.isNotEmpty()) { "The underlying network has no DNS server while custom DNS is disabled" }
        dns.forEach(builder::addDnsServer)
        return checkNotNull(builder.establish()) { "VPN authorization was revoked" }

}

internal suspend fun releaseAndroidTunResources(application: LiteApplication, tun: ParcelFileDescriptor?, config: File?,
    profileLease: ProfileLease?, warn: (Exception) -> Unit, unlock: () -> Unit) {
    try {
        try { tun?.close() } finally {
            if (config != null && !config.delete() && config.exists()) warn(java.io.IOException("Runtime configuration cleanup failed"))
            profileLease?.let { application.profiles.release(it) }
        }
    } finally { unlock() }
}
