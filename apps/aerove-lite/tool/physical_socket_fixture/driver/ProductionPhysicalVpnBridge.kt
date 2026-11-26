package dev.aerove.lite.platformqa

import dev.aerove.lite.FindProcessMode
import dev.aerove.lite.LiteApplication
import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.ControlEndpoint
import dev.aerove.lite.data.CoreApiClient
import dev.aerove.lite.flutter.MeowRuntimeSnapshotSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Hooks the isolated test onto read-only production state and the actual Core API.
 * UI actions are supplied by a separate instrumentation entry point.
 */
internal class ProductionPhysicalVpnBridge(private val app: LiteApplication) {
    private val vpn = AndroidPhysicalVpnReader(app)
    private val sockets = PhysicalSocketDriver(app)

    suspend fun run(pin: PhysicalSocketDriver.Pin, challenges: List<PhysicalSocketDriver.Challenge>,
        profileLabel: String, profileDigest: String,
        startViaProductionUi: suspend () -> Unit, stopViaProductionUi: suspend () -> Unit
    ): PhysicalVpnScenario.Result = withContext(Dispatchers.IO) {
        PhysicalVpnScenario(app.applicationInfo.uid, vpn::visibleVpn,
            startFromUi = startViaProductionUi,
            stopFromUiAndJoin = {
                stopViaProductionUi()
                // A parked graph or historical apply receipt may survive.
                // Coordinator Stopped is published after the service join.
                val stopped = app.vpn.first {
                    it.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed) &&
                        it.endpoint == null && !it.listening
                }
                StoppedReadback(stopped.phase.name, stopped.endpoint == null, stopped.listening)
            },
            awaitRunningCore = {
                app.vpn.first { it.phase == VpnPhase.Running && it.endpoint != null && it.listening && !it.suspended }
                app.flutterRuntimeIdentity.first { it != null && app.flutterRuntimeIdentityIsCurrent(it) }
                readActive(profileLabel, profileDigest)
            },
            currentCore = { readActive(profileLabel, profileDigest) },
            snapshotForCore = { stamp ->
                check(readActive(profileLabel, profileDigest).stamp == stamp)
                val endpoint = checkNotNull(app.vpn.value.endpoint)
                val raw = CoreApiClient(ControlEndpoint(endpoint.port, endpoint.secret)).use {
                    it.protocolSnapshot(listOf("connections"))
                }
                check(app.vpn.value.endpoint === endpoint && readActive(profileLabel, profileDigest).stamp == stamp)
                raw
            },
            sample = { target, findProcess, native -> sockets.collect(pin, target, findProcess, native) }
        ).run(challenges)
    }

    private suspend fun readActive(profileLabel: String, profileDigest: String): CoreReadback {
        val state = app.vpn.value
        check(state.phase == VpnPhase.Running && state.endpoint != null && state.listening && !state.suspended)
        val identity = checkNotNull(app.flutterRuntimeIdentity.value)
        check(app.flutterRuntimeIdentityIsCurrent(identity) && app.runtimeHandoffs.readAppliedIdentity() == identity &&
            identity.proof.serviceOwner == state.serviceOwner && identity.proof.sessionGeneration == state.sessionGeneration)
        val applied = checkNotNull(app.runtimeHandoffs.readLastApplied())
        check(applied.revision == identity.revision && applied.intentEpoch == identity.intentEpoch &&
            applied.handoff.compiledSha256 == identity.compiledSha256)
        check(applied.handoff.sourceSha256 == profileDigest &&
            org.json.JSONObject(applied.handoff.notificationJson).getString("currentProfileName") == profileLabel)
        val settings = MeowRuntimeSnapshotSettings.decode(applied.handoff.runtimeSettingsJson)
        check(settings.network.vpnEnabled && app.vpn.value === state && app.flutterRuntimeIdentityIsCurrent(identity))
        return CoreReadback(CoreStamp(state.serviceOwner, state.sessionGeneration, identity.proof.coreEpoch.toULong(), identity.revision),
            settings.findProcessMode == FindProcessMode.Always, settings.network.bypassPrivateRoute, settings.appFilterMode.name)
    }
}
