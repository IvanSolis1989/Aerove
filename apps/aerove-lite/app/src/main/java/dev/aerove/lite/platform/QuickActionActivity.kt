package dev.aerove.lite.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.maintenance.CURRENT_DISCLAIMER_VERSION
import dev.aerove.lite.vpn.LiteVpnService
import kotlinx.coroutines.*
import dev.aerove.lite.R
import android.widget.Toast

class QuickActionActivity : Activity() {
    private var forwarded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (LauncherActions.shouldForwardOnCreate(savedInstanceState?.getBoolean(STATE_FORWARDED) == true)) {
            forward(intent)
        }
        if (savedInstanceState?.getBoolean(STATE_FORWARDED) == true) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_FORWARDED, forwarded)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forward(intent)
    }

    private fun forward(source: Intent?) {
        val action = source?.let {
            LauncherActions.parseIntentAction(packageName, it.action, it.getStringExtra(LauncherActions.EXTRA_ACTION))
        } ?: run { finish(); return }
        val requestId = LauncherActions.requestIdForLaunch(source.getStringExtra(LauncherActions.EXTRA_REQUEST_ID)) ?: run { finish(); return }
        scope.launch {
            val graph = application as LiteApplication
            val epoch = graph.startIntentEpoch()
            try {
                val stopping = action == LauncherAction.STOP || action == LauncherAction.TOGGLE &&
                    graph.vpn.value.phase in setOf(VpnPhase.Running, VpnPhase.Starting)
                if (stopping) {
                    graph.invalidatePendingStarts()
                    startService(Intent(this@QuickActionActivity, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
                } else {
                    val missing = withContext(Dispatchers.IO) {
                        val settings = graph.settings.read()
                        when {
                            settings.acceptedDisclaimerVersion < CURRENT_DISCLAIMER_VERSION -> R.string.platform_disclaimer_required
                            graph.profiles.snapshot().selectedId == null -> R.string.error_profile_required
                            settings.network.vpnEnabled && VpnService.prepare(this@QuickActionActivity) != null -> R.string.error_vpn_permission
                            !settings.network.vpnEnabled && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> R.string.platform_notification_required
                            else -> null
                        }
                    }
                    if (graph.maintenanceActive || graph.startIntentEpoch() != epoch) return@launch
                    if (missing == null) startForegroundService(Intent(this@QuickActionActivity, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_START))
                    else Toast.makeText(this@QuickActionActivity, missing, Toast.LENGTH_LONG).show()
                }
                forwarded = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(this@QuickActionActivity, R.string.vpn_failed, Toast.LENGTH_LONG).show(); forwarded = true }
            finally { finish() }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val ACTION_QUICK_ACTION = "dev.aerove.lite.platform.action.QUICK_ACTION"
        private const val STATE_FORWARDED = "dev.aerove.lite.platform.state.FORWARDED"
    }
}
