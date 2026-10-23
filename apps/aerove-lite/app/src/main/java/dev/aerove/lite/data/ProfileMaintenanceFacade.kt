package dev.aerove.lite.data

import dev.aerove.lite.ProfileSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import dev.aerove.lite.profilesource.AgeSecret

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

    /** Borrows the caller's handle, copies it before suspension, and joins this exact flight before releasing the copy. */
    suspend fun editCandidate(expected: ProfileDocumentSnapshot, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, secret: AgeSecret? = null, replaceSecret: Boolean = false): ProfileSummary {
        val owned = if (replaceSecret && secret != null) secret.copyBytes().let { bytes ->
            try { AgeSecret(bytes) } finally { bytes.fill(0) }
        } else null
        try {
            reload()
            var saved: ProfileSummary? = null
            val result = maintenance.executeCandidate(expected.profile.id) { request ->
                val summary = repository.editCandidate(expected, name, url, autoUpdate, intervalMillis, owned, replaceSecret, request)
                val callbackRequest = if (summary.sourceIdentity != request.sourceIdentity) request.committedSource(summary.sourceIdentity) else request
                reload()
                if (summary.sourceIdentity != expected.profile.sourceIdentity && callbackRequest.isCurrent() &&
                    repository.isCurrentSource(summary.id, callbackRequest.sourceIdentity)) onUpdated(summary, callbackRequest)
                saved = summary
                summary.lastSuccessfulUpdateAt ?: summary.updatedAt
            }
            return when (result) {
                is ProfileMaintenanceResult.Updated -> checkNotNull(saved)
                is ProfileMaintenanceResult.Failed -> throw result.error
                ProfileMaintenanceResult.Cancelled -> throw CancellationException("Candidate profile update cancelled")
                ProfileMaintenanceResult.Unavailable -> error("Profile maintenance is unavailable")
            }
        } finally { owned?.close() }
    }

    suspend fun quiesce() = maintenance.quiesce()
    fun resumeAfterMaintenance(foreground: Boolean) = maintenance.resumeAfterMaintenance(foreground)

    fun setForeground(value: Boolean) = maintenance.setForeground(value)
    fun checkDue() = maintenance.checkDue()
    fun cancel(id: String) = maintenance.cancel(id)
    fun snapshot(): ProfileMaintenanceSnapshot = maintenance.snapshot()
    override fun close() = maintenance.close()
}
