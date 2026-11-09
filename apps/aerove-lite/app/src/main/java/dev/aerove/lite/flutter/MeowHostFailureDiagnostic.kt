package dev.aerove.lite.flutter

internal fun meowHostFailureDiagnostic(code: String, method: String, failure: Throwable): String {
    val known = setOf("service.init", "service.shutdown", "service.syncState", "service.getRunTime", "service.setUiTelemetryActive",
        "service.start", "service.stop", "service.park", "service.observeRuntime", "core.invokeMethod", "core.cancel", "telemetry.event",
        "migration.begin", "migration.validate", "migration.commit", "migration.prepareFiles", "migration.confirm", "migration.release", "migration.providerSourceRecovery")
    val safeMethod = method.takeIf { it in known } ?: "unknown"
    val safeCode = code.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) } ?: "invalid_code"
    val causes = generateSequence(failure) { it.cause }.take(3).joinToString(" <- ") { cause ->
        val frames = cause.stackTrace.take(3).joinToString(",") { frame ->
            "${frame.className.take(96)}.${frame.methodName.take(64)}:${frame.lineNumber}"
        }
        "${cause.javaClass.name.take(128)}[$frames]"
    }
    return "transport_failure code=$safeCode method=$safeMethod causes=$causes".take(2048)
}

/** Shareable code locations only: never include exception messages, paths or request data. */
internal fun meowFailureLocation(method: String, failure: Throwable): String {
    val token = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,159}")
    fun safe(value: String) = value.takeIf { token.matches(it) } ?: "unknown"
    val causes = generateSequence(failure) { it.cause }.take(3).joinToString(" <- ") { cause ->
        val frame = cause.stackTrace.firstOrNull { it.className.startsWith("dev.aerove.lite.") }
        val location = frame?.let { "@${safe(it.className)}.${safe(it.methodName)}:${it.lineNumber}" }.orEmpty()
        val evidence = cause.suppressed.filterIsInstance<dev.aerove.lite.core.MeowStartupFailureEvidence>().firstOrNull()
        val setupEvidence = cause.suppressed.filterIsInstance<MeowSetupFailureEvidence>().firstOrNull()
        val sourceCode = when (cause.message) {
            "shared_config_unstable", "shared_config_duplicate", "shared_source_config_changed", "provider_source_recovery_required", "runtime_graph_superseded" -> cause.message
            "skip-cert-verify: Graph policy differs from compiled setup" -> "graph_certificate_policy_mismatch"
            "mode: Graph policy did not converge" -> "graph_mode_not_converged"
            "Meow graph HTTP and native epochs differ" -> "graph_epoch_mismatch"
            "Query graph user controller is not isolated" -> "graph_controller_not_isolated"
            "Query graph opened public listeners" -> "graph_public_listeners_open"
            else -> null
        }
        "${safe(cause.javaClass.simpleName)}$location" + (setupEvidence?.let { "{setupPhase=${it.phase.name}}" }.orEmpty()) + (evidence?.let { "{${it.label()}}" } ?: sourceCode?.let { "{$it}" }.orEmpty())
    }
    val knownHost = setOf("service.init", "service.shutdown", "service.syncState", "service.getRunTime",
        "service.setUiTelemetryActive", "service.start", "service.stop", "service.park", "core.invokeMethod", "core.cancel")
    val safeMethod = method.takeIf { it in MeowProtocolDispatcher.METHODS || it in knownHost } ?: "unknown"
    return "$safeMethod: $causes".take(1024)
}
