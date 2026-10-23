package dev.aerove.lite.vpn

/** One session's policy transaction; secret values never appear in diagnostic representations. */
internal data class RuntimePolicyTarget(
    val onlyProxyStatistics: Boolean,
    val userAgent: String,
    val checkCertificate: Boolean,
    val unifiedDelay: Boolean,
    val tcpConcurrent: Boolean,
    val controllerEnabled: Boolean,
    val controllerSecret: String,
    val findProcessMode: String,
) {
    override fun toString(): String = "RuntimePolicyTarget(onlyProxyStatistics=$onlyProxyStatistics, unifiedDelay=$unifiedDelay, tcpConcurrent=$tcpConcurrent, controllerEnabled=$controllerEnabled, findProcessMode=$findProcessMode, credentials=<redacted>)"
}

internal data class ProbeDialReadback(val unifiedDelay: Boolean, val tcpConcurrent: Boolean, val coreEpoch: String)
internal data class ProcessModeReadback(val mode: String, val coreEpoch: String)
internal data class UserControllerReadback(val enabled: Boolean, val address: String?, val authenticated: Boolean,
    val privateRoutesExcluded: Boolean, val coreEpoch: String)

internal interface RuntimePolicyCommands {
    fun statistics(enabled: Boolean): String
    fun http(userAgent: String, checkCertificate: Boolean)
    fun probe(unifiedDelay: Boolean, tcpConcurrent: Boolean)
    fun probeStatus(): ProbeDialReadback
    fun controller(enabled: Boolean, secret: String): UserControllerReadback
    fun controllerStatus(): UserControllerReadback
    fun processMode(mode: String)
    fun processStatus(): ProcessModeReadback
}

/** Caller serializes with the session mutex. A stale transaction cannot establish the diff baseline. */
internal class RuntimePolicyOwner(private val commands: RuntimePolicyCommands) {
    private var applied: RuntimePolicyTarget? = null
    var lastEpoch: String? = null
        private set

    fun apply(target: RuntimePolicyTarget, epoch: String, current: () -> Boolean): Boolean {
        val baseline = requireConfigEpoch(epoch, lastEpoch)
        lastEpoch = baseline
        require(target.findProcessMode in setOf("strict", "always", "off")) { "Invalid process matching mode" }
        require(!target.controllerEnabled || target.controllerSecret.isNotBlank()) { "Enabled user controller requires a secret" }
        if (!current()) return false
        val previous = applied
        if (previous?.onlyProxyStatistics != target.onlyProxyStatistics) {
            lastEpoch = requireConfigEpoch(commands.statistics(target.onlyProxyStatistics), lastEpoch ?: baseline)
            if (!current()) return false
        }
        if (previous?.userAgent != target.userAgent || previous?.checkCertificate != target.checkCertificate) {
            commands.http(target.userAgent, target.checkCertificate)
            if (!current()) return false
        }
        if (previous?.unifiedDelay != target.unifiedDelay || previous?.tcpConcurrent != target.tcpConcurrent) {
            commands.probe(target.unifiedDelay, target.tcpConcurrent)
            val actual = commands.probeStatus()
            lastEpoch = requireConfigEpoch(actual.coreEpoch, lastEpoch ?: baseline)
            if (!current()) return false
            check(actual.unifiedDelay == target.unifiedDelay && actual.tcpConcurrent == target.tcpConcurrent) {
                "Core probe dialing policy did not converge"
            }
        }
        if (previous?.controllerEnabled != target.controllerEnabled || previous?.controllerSecret != target.controllerSecret) {
            val receipt = commands.controller(target.controllerEnabled, target.controllerSecret)
            lastEpoch = requireConfigEpoch(receipt.coreEpoch, lastEpoch ?: baseline)
            val actual = commands.controllerStatus()
            lastEpoch = requireConfigEpoch(actual.coreEpoch, lastEpoch)
            if (!current()) return false
            check(receipt.enabled == target.controllerEnabled && receipt.privateRoutesExcluded &&
                receipt.authenticated == target.controllerEnabled &&
                receipt.address == if (target.controllerEnabled) "127.0.0.1:9090" else null) {
                "Core user controller receipt did not match its target"
            }
            check(actual.enabled == target.controllerEnabled && actual.privateRoutesExcluded &&
                actual.authenticated == target.controllerEnabled &&
                actual.address == if (target.controllerEnabled) "127.0.0.1:9090" else null) {
                "Core user controller policy did not converge"
            }
        }
        if (previous?.findProcessMode != target.findProcessMode) {
            commands.processMode(target.findProcessMode)
            val actual = commands.processStatus()
            lastEpoch = requireConfigEpoch(actual.coreEpoch, lastEpoch ?: baseline)
            if (!current()) return false
            check(actual.mode == target.findProcessMode) { "Core process matching mode did not converge" }
        }
        if (!current()) return false
        applied = target
        return true
    }
}

private val configEpochFormat = Regex("0|[1-9][0-9]*")

/** Native configuration revisions can advance within the same handle/session; they cannot move backwards. */
internal fun requireConfigEpoch(actual: String, minimum: String? = null): String {
    require(configEpochFormat.matches(actual) && actual.toULongOrNull() != null) { "Invalid Core configuration revision" }
    if (minimum != null) {
        require(configEpochFormat.matches(minimum) && minimum.toULongOrNull() != null) { "Invalid prior Core configuration revision" }
        check(actual.toULong() >= minimum.toULong()) { "Stale Core configuration readback" }
    }
    return actual
}
