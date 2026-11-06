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
