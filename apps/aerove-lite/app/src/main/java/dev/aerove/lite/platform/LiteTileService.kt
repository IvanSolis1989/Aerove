package dev.aerove.lite.platform

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class LiteTileService : TileService() {
    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        stopListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listeningScope = scope
        scope.launch {
            (application as LiteApplication).vpn.map { tileStateFor(it.phase) }.distinctUntilChanged().collect { tileState ->
                qsTile?.apply {
                    state = tileState
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        stopListening()
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = LauncherActions.intent(this, LauncherAction.TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun stopListening() {
        listeningScope?.cancel()
        listeningScope = null
    }
}

internal fun tileStateFor(phase: VpnPhase): Int = when (phase) {
    VpnPhase.Running -> Tile.STATE_ACTIVE
    VpnPhase.Starting, VpnPhase.Stopping -> Tile.STATE_UNAVAILABLE
    VpnPhase.Stopped, VpnPhase.Failed -> Tile.STATE_INACTIVE
}
