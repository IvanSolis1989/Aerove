package dev.aerove.lite.integration

import dev.aerove.lite.ProfileSummary
import dev.aerove.lite.data.ProfileDocumentSnapshot
import dev.aerove.lite.data.ProfileMaintenanceFacade
import dev.aerove.lite.data.ProfileRepository
import dev.aerove.lite.profilesource.AgeSecret

/** Private form data is owned by the caller, never published in application state or saved instance state. */
class PrivateProfileProperties(val document: ProfileDocumentSnapshot, val secret: AgeSecret?) : AutoCloseable {
    override fun close() { secret?.close() }
    override fun toString(): String = "PrivateProfileProperties([redacted])"
}

interface ProfilePropertiesAccess {
    suspend fun load(id: String): PrivateProfileProperties
    suspend fun save(expected: ProfileDocumentSnapshot, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, secret: AgeSecret?, replaceSecret: Boolean): ProfileSummary
}

class RepositoryProfilePropertiesAccess(private val profiles: ProfileRepository,
    private val maintenance: ProfileMaintenanceFacade, private val available: () -> Boolean,
    private val saved: suspend () -> Unit) : ProfilePropertiesAccess {
    override suspend fun load(id: String): PrivateProfileProperties {
        check(available()) { "profile_maintenance" }
        repeat(2) {
            val before = profiles.documentSnapshot(id)
            val key = profiles.readAgeSecret(id)
            try {
                val after = profiles.documentSnapshot(id)
                if (available() && before.revision == after.revision &&
                    before.profile.sourceIdentity == after.profile.sourceIdentity) return PrivateProfileProperties(after, key)
            } catch (failure: Throwable) { key?.close(); throw failure }
            key?.close()
        }
        error("profile_changed")
    }

    override suspend fun save(expected: ProfileDocumentSnapshot, name: String, url: String?, autoUpdate: Boolean,
        intervalMillis: Long, secret: AgeSecret?, replaceSecret: Boolean): ProfileSummary {
        check(available()) { "profile_maintenance" }
        val result = maintenance.editCandidate(expected, name, url, autoUpdate, intervalMillis, secret, replaceSecret)
        if (available()) saved()
        return result
    }
}
