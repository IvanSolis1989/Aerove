package dev.aerove.lite.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
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
                if (application.settings.read().autoStart && VpnService.prepare(context) == null &&
                    application.profiles.snapshot().selectedId != null) {
                    ContextCompat.startForegroundService(context, Intent(context, LiteVpnService::class.java)
                        .setAction(LiteVpnService.ACTION_START))
                }
            } finally {
                result.finish()
            }
        }
    }
}
