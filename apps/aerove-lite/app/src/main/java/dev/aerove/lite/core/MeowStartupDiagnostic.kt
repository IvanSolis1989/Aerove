package dev.aerove.lite.core

import org.json.JSONObject

enum class MeowStartupStage { ownerCreate, planDecode, hostPolicy, physicalDns, managedConfig, configLoad, dnsSetup, controlSetup, graphActivation, userActivation, readyWait, running }
enum class MeowStartupCode { ownerCreateFailed, planDecodeFailed, hostPolicyFailed, physicalDnsFailed, managedConfigFailed, configLoadFailed, dnsSetupFailed, controlSetupFailed, graphActivationFailed, userActivationFailed, readyWaitFailed, candidateFieldNotIntegrated, managedDialerProxyGraphRejected, managedConfigRejected, managedGroupConfigRejected, providerConfigRejected, managedDialerProxyGraphNotIntegrated, cancelled, ownerExited, ownedRuntimePanicked, unclassified }
enum class MeowStartupIoKind { permissionDenied, addressInUse, addressNotAvailable, invalidData, invalidInput, notConnected, interrupted, timedOut, other }
enum class MeowStartupCauseType { serdeYaml, io, join, unknown }
enum class MeowStartupCleanup { pending, complete, runtimeCleanupFailed, loadJoinFailed, runtimeAndLoadJoinFailed }
enum class MeowStartupPhase { loading, running, stopped, failed }
data class MeowStartupPrimary(val stage: MeowStartupStage, val code: MeowStartupCode, val ioKind: MeowStartupIoKind?, val causeType: MeowStartupCauseType? = null)
data class MeowStartupSnapshot(val generation: String, val phase: MeowStartupPhase,
    val coreReady: Boolean, val tunReady: Boolean, val userListenersReady: Boolean,
    val stage: MeowStartupStage, val completed: Boolean, val primary: MeowStartupPrimary?, val cleanup: MeowStartupCleanup)

internal object MeowStartupCodec {
    private fun JSONObject.exact(vararg fields: String) {
        require(keys().asSequence().toSet() == fields.toSet()) { "Invalid startup diagnostic fields" }
    }
    private fun JSONObject.text(key: String) = (get(key) as? String) ?: error("Invalid startup string")
    private fun JSONObject.bool(key: String) = (get(key) as? Boolean) ?: error("Invalid startup boolean")
    private inline fun <reified T : Enum<T>> enum(value: String): T =
        enumValues<T>().singleOrNull { it.name == value } ?: error("Invalid startup enum")
    fun decode(raw: String): MeowStartupSnapshot {
        val row = JSONObject(raw)
        row.exact("schema", "generation", "phase", "coreReady", "tunReady", "userListenersReady", "startup")
        require(row.get("schema") is Int && row.getInt("schema") == 1) { "Invalid startup schema" }
        val generation = row.text("generation")
        require(generation.toULongOrNull()?.let { it > 0uL && it.toString() == generation } == true) { "Invalid startup generation" }
        val state = row.getJSONObject("startup")
        state.exact("stage", "completed", "primary", "cleanup")
        val primary = if (state.get("primary") === JSONObject.NULL) null else state.getJSONObject("primary").let {
            if (it.has("causeType")) it.exact("stage", "code", "ioKind", "causeType") else it.exact("stage", "code", "ioKind")
            val cause = if (!it.has("causeType")) null else {
                val name = it.text("causeType")
                MeowStartupCauseType.values().singleOrNull { value -> value.name == name } ?: MeowStartupCauseType.unknown
            }
            MeowStartupPrimary(enum(it.text("stage")), enum(it.text("code")),
                if (it.get("ioKind") === JSONObject.NULL) null else enum<MeowStartupIoKind>(it.text("ioKind")), cause)
        }
        return MeowStartupSnapshot(generation, enum(row.text("phase")), row.bool("coreReady"), row.bool("tunReady"),
            row.bool("userListenersReady"), enum(state.text("stage")), state.bool("completed"), primary, enum(state.text("cleanup")))
    }
}

internal fun meowStartupFailureDiagnostic(handle: Long, before: Result<MeowStartupSnapshot?>, after: Result<MeowStartupSnapshot?>): String {
    fun project(value: Result<MeowStartupSnapshot?>): String {
        val snapshot = value.getOrNull()?.takeIf { it.generation == handle.toString() } ?: return "unavailable"
        val primary = snapshot.primary?.let { "${it.stage}/${it.code}/${it.ioKind ?: "none"}" + (it.causeType?.let { cause -> "/cause=$cause" } ?: "") } ?: "none"
        return "phase=${snapshot.phase},ready=${snapshot.coreReady}/${snapshot.tunReady}/${snapshot.userListenersReady}," +
            "stage=${snapshot.stage},completed=${snapshot.completed},primary=$primary,cleanup=${snapshot.cleanup}"
    }
    return "startup_failure generation=$handle before=[${project(before)}] after=[${project(after)}]"
}
