package dev.aerove.lite.flutter

internal fun meowSameRuntimeIdentity(left: RuntimeAppliedIdentity, right: RuntimeAppliedIdentity): Boolean =
    left.revision == right.revision && left.intentEpoch == right.intentEpoch &&
        left.compiledSha256 == right.compiledSha256 && left.proof == right.proof

/** The application forwards to the mutually exclusive parked graph or VPN lease owner. */
interface MeowFlutterRuntime {
    fun identityIsCurrent(identity: RuntimeAppliedIdentity): Boolean
    fun hasRequestedIntent(): Boolean = false
    fun queryGraph(): MeowQueryGraph? = null
    suspend fun applyStaged(snapshot: MeowRuntimeSnapshot): RuntimeAppliedIdentity =
        throw MeowProtocolFailure("implementation_blocked", "The runtime owner has no graph loader")
    suspend fun setListening(expected: MeowSetupRuntime, enabled: Boolean): RuntimeAppliedIdentity
    suspend fun applyUpdate(expected: MeowSetupRuntime, projection: MeowSetupProjection): RuntimeAppliedIdentity
    suspend fun refreshSetup(expected: MeowSetupRuntime, setupParamsJson: String): RuntimeAppliedIdentity
    suspend fun defaultTestUrl(expected: MeowSetupRuntime): String
}

data class MeowQueryGraph(val identity: RuntimeAppliedIdentity, val endpoint: dev.aerove.lite.vpn.CoreEndpoint,
    val listening: Boolean)

internal suspend fun meowApplyStagedGraph(snapshot: MeowRuntimeSnapshot, store: RuntimeHandoffStore,
    replaceRequested: suspend (MeowRuntimeSnapshot) -> RuntimeAppliedIdentity,
    parked: suspend (MeowRuntimeSnapshot) -> RuntimeAppliedIdentity): RuntimeAppliedIdentity {
    val candidate = store.readCurrent()
    if (candidate?.revision != snapshot.revision || candidate.intentEpoch != snapshot.intentEpoch)
        throw MeowProtocolFailure("superseded", "Staged graph was superseded")
    return if (candidate.userRequested) replaceRequested(candidate) else parked(candidate)
}

internal fun meowAdmitGraphReplacement(expectedOwner: Long, expectedIntent: Long, owner: Long, intent: Long,
    requested: Boolean, candidateCurrent: Boolean): Boolean = expectedOwner == owner && expectedIntent == intent &&
    requested && candidateCurrent
