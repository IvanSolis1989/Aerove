package dev.aerove.lite.controllerprefs

interface ExternalControllerRuntime {
    /** Existing runtime owner only: verify bind/auth/routes, preserve private readiness on failure, exclude aerove routes; never expose secrets/private endpoints. */
    suspend fun configure(runtimeIdentity: Long, plan: UserControllerPlan): UserControllerReadback
}

data class UserControllerReadback(
    val runtimeIdentity: Long,
    val enabled: Boolean,
    val authenticated: Boolean,
    val privateRoutesExcluded: Boolean,
) {
    fun requireMatches(expectedIdentity: Long, plan: UserControllerPlan): UserControllerReadback = apply {
        check(runtimeIdentity == expectedIdentity && enabled == plan.enabled) { "User controller runtime changed" }
        check(!enabled || (authenticated && privateRoutesExcluded)) { "User controller verification failed" }
    }
}
