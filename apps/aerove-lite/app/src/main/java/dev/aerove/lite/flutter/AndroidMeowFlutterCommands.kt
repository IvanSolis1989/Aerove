package dev.aerove.lite.flutter

import android.content.Intent
import androidx.core.content.ContextCompat
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.vpn.LiteVpnService
import dev.aerove.lite.vpn.VpnSnapshot
import kotlinx.coroutines.flow.map

/** canConnect rechecks current data admission and actual VPN permission after the Activity result. */
fun createMeowFlutterCommandFacade(
    application: LiteApplication,
    canPrepare: () -> Boolean,
    canConnect: () -> Boolean,
    cancelPendingWork: () -> Unit = {},
): MeowFlutterCommandFacade = MeowFlutterCommandFacade(
    ownedStarts = application.ownedStarts,
    canPrepare = canPrepare,
    canConnect = canConnect,
    startIntentEpoch = application::startIntentEpoch,
    invalidatePendingStarts = application::invalidatePendingStarts,
    dispatchStart = { request, requestedAt, runtime ->
        ContextCompat.startForegroundService(application, Intent(application, LiteVpnService::class.java)
            .setAction(LiteVpnService.ACTION_START)
            .putExtra(LiteVpnService.EXTRA_REQUESTED_AT_NANOS, requestedAt)
            .putExtra(LiteVpnService.EXTRA_OWNED_START_ID, request.id).apply {
                if (runtime != null) {
                    putExtra(LiteVpnService.EXTRA_RUNTIME_REVISION, runtime.revision)
                    putExtra(LiteVpnService.EXTRA_RUNTIME_EPOCH, runtime.intentEpoch)
                }
            })
    },
    dispatchStop = {
        application.startService(Intent(application, LiteVpnService::class.java).setAction(LiteVpnService.ACTION_STOP))
    },
    cancelPendingWork = cancelPendingWork,
    requiresRuntimeBinding = { application.runtimeHandoffs.hasAuthority },
    runtimeCurrent = { application.runtimeHandoffs.isCurrent(it.revision, it.intentEpoch) },
    currentStopSnapshot = { application.vpn.value.stopSnapshot() },
    stopSnapshots = application.vpn.map { it.stopSnapshot() },
)

private fun VpnSnapshot.stopSnapshot() = MeowFlutterStopSnapshot(serviceOwner, sessionGeneration,
    terminal = phase == VpnPhase.Stopped && endpoint == null && error.isNullOrBlank(),
    failed = phase == VpnPhase.Failed || !error.isNullOrBlank())
