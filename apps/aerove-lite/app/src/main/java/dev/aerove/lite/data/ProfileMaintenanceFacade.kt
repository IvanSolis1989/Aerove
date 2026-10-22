package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Retained by the controller; UI visibility changes never recreate the scheduler. */
class ProfileMaintenanceFacade(
    private val repository: ProfileRepository,
    ownerScope: CoroutineScope,
    private val onUpdated: suspend (ProfileSummary, ProfileMaintenanceRequest) -> Unit = { _, _ -> },
    onAutomaticError: (String, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val reloadGate = Mutex()
    private val maintenance = ProfileMaintenance(ownerScope, update = { request ->
        val summary = repository.update(request.profileId, request)
        reload()
        if (request.isCurrent() && repository.isCurrentSource(summary.id, request.sourceIdentity)) {
            onUpdated(summary, request)
        }
        checkNotNull(summary.lastSuccessfulUpdateAt)
    }, onAutomaticError = onAutomaticError)

    val runningProfileIds = maintenance.runningProfileIds

    suspend fun reload(): ProfileSnapshot = reloadGate.withLock {
        repository.snapshot().also { snapshot ->
            maintenance.replacePolicies(snapshot.profiles.map { profile ->
                ProfileMaintenancePolicy(profile.id, profile.sourceIdentity, profile.url != null,
                    profile.autoUpdate, profile.updateIntervalMillis, profile.lastSuccessfulUpdateAt)
            })
        }
    }

    suspend fun refresh(id: String): ProfileMaintenanceResult {
        reload()
        return maintenance.refresh(id)
    }

    suspend fun editMetadata(id: String, name: String, url: String?, autoUpdate: Boolean, intervalMillis: Long): ProfileSummary {
        val summary = repository.editMetadata(id, name, url, autoUpdate, intervalMillis)
        reload()
        return summary
    }

    fun setForeground(value: Boolean) = maintenance.setForeground(value)
    fun checkDue() = maintenance.checkDue()
    fun cancel(id: String) = maintenance.cancel(id)
    fun snapshot(): ProfileMaintenanceSnapshot = maintenance.snapshot()
    override fun close() = maintenance.close()
}
