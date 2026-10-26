package dev.aerove.lite.integration

import dev.aerove.lite.VpnPhase
import dev.aerove.lite.data.PreparedProfile
import dev.aerove.lite.resources.ResourceApplyReceipt
import dev.aerove.lite.vpn.CoreEndpoint
import dev.aerove.lite.vpn.VpnSnapshot
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** A service dispatch is not an applied receipt; both saved lineage and the ready runtime must agree. */
class ResourceRuntimeApply(private val snapshots: StateFlow<VpnSnapshot>, private val selected: () -> String?,
    private val readPrepared: suspend (String) -> PreparedProfile,
    private val requestReconfigure: suspend () -> Unit,
    private val verifyRoute: suspend (CoreEndpoint) -> Unit,
    private val isCurrent: () -> Boolean,
    private val reloadRules: suspend (CoreEndpoint, PreparedProfile, () -> Boolean) -> Boolean = { _, _, _ -> false }) {
    suspend fun verifyReceipt(requested: PreparedProfile, receipt: ResourceApplyReceipt): ResourceApplyReceipt {
        currentCoroutineContext().ensureActive()
        check(isCurrent() && selected() == requested.profileId) { "Resource application was superseded" }
        if (receipt.appliedGeneration == null) return receipt
        val actual = readPrepared(requested.profileId)
        currentCoroutineContext().ensureActive()
        val ready = snapshots.value
        val endpoint = ready.endpoint
        check(isCurrent() && selected() == requested.profileId && ready.phase == VpnPhase.Running &&
            endpoint?.configuration?.profileId == requested.profileId && actual.revision == requested.revision &&
            endpoint.profileRevision == actual.revision && endpoint.resourceGeneration == actual.generation &&
            actual.generation == receipt.appliedGeneration) { "Applied resource receipt no longer matches the saved configuration" }
        return receipt
    }

    suspend fun apply(requested: PreparedProfile): ResourceApplyReceipt = withTimeout(45_000) {
        // Match the existing AndroidTunnelBackend readiness deadline, without polling.
        suspend fun current() {
            currentCoroutineContext().ensureActive()
            check(isCurrent() && selected() == requested.profileId) { "Resource application was superseded" }
        }
        current()
        val before = snapshots.first { it.phase != VpnPhase.Starting && it.phase != VpnPhase.Stopping }
        current()
        if (before.endpoint == null && before.phase in setOf(VpnPhase.Stopped, VpnPhase.Failed))
            return@withTimeout ResourceApplyReceipt(null)
        check(before.phase == VpnPhase.Running && before.endpoint?.configuration?.profileId == requested.profileId) {
            "The selected configuration has no current runtime"
        }
        val reloaded = try { reloadRules(checkNotNull(before.endpoint), requested) { isCurrent() && selected() == requested.profileId } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { false } // The session retains failed cleanup; full apply must stop/join it.
        if (reloaded) {
            current()
            verifyRoute(before.endpoint)
            current()
            check(snapshots.value.endpoint === before.endpoint && before.endpoint.profileRevision == requested.revision &&
                before.endpoint.resourceGeneration == requested.generation) { "Rule reload receipt changed before readback" }
            return@withTimeout ResourceApplyReceipt(requested.generation)
        }
        current()
        requestReconfigure()
        val ready = snapshots.first { it.phase == VpnPhase.Failed || it.phase == VpnPhase.Stopped ||
            (it.phase == VpnPhase.Running && it.endpoint !== before.endpoint) }
        current()
        check(ready.phase == VpnPhase.Running) { ready.error ?: "The runtime stopped during resource application" }
        val endpoint = checkNotNull(ready.endpoint)
        val actual = readPrepared(requested.profileId)
        current()
        check(endpoint.configuration?.profileId == requested.profileId && actual.revision == requested.revision &&
            endpoint.profileRevision == actual.revision && endpoint.resourceGeneration == actual.generation) {
            "Applied resource lineage does not match the saved configuration"
        }
        verifyRoute(endpoint)
        current()
        check(snapshots.value.endpoint === endpoint && snapshots.value.phase == VpnPhase.Running) {
            "The runtime changed before application readback completed"
        }
        ResourceApplyReceipt(actual.generation)
    }
}
