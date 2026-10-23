package dev.aerove.lite.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.aerove.lite.LiteApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val application = context.applicationContext as LiteApplication
                val settings = application.settings.read()
                if (!application.maintenanceActive && settings.autoStart && canStartRuntime(settings.network.vpnEnabled) { VpnService.prepare(context) == null } &&
                    application.profiles.snapshot().selectedId != null) {
                    check(settings.network.vpnEnabled || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        "Notification permission is required to start the proxy in the background"
                    }
                    ContextCompat.startForegroundService(context, Intent(context, LiteVpnService::class.java)
                        .setAction(LiteVpnService.ACTION_START))
                }
            } catch (failure: Exception) {
                LiteVpnService.recordStartupFailure(context, failure)
            } finally {
                result.finish()
            }
        }
    }
}
