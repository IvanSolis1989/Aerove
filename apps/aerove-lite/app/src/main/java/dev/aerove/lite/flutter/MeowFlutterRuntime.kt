package dev.aerove.lite.flutter

internal fun meowSameRuntimeIdentity(left: RuntimeAppliedIdentity, right: RuntimeAppliedIdentity): Boolean =
    left.revision == right.revision && left.intentEpoch == right.intentEpoch &&
        left.compiledSha256 == right.compiledSha256 && left.proof == right.proof

/** The existing VPN Service registers the owner; an engine may hold a forwarding facade. */
interface MeowFlutterRuntime {
    fun identityIsCurrent(identity: RuntimeAppliedIdentity): Boolean
    suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity
    suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity
    suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String): RuntimeAppliedIdentity
    suspend fun defaultTestUrl(expected: MeowSetupRuntime): String
}
