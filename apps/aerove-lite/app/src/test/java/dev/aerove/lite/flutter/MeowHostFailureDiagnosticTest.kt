package dev.aerove.lite.flutter

import org.junit.Assert.*
import org.junit.Test

class MeowHostFailureDiagnosticTest {
    @Test fun hostDiagnosticKeepsTransportIdentityAndSafeFramesWithoutExceptionMessagesOrUnknownMethodText() {
        val cause = IllegalArgumentException("password=never-log-secret https://private.invalid/feed")
        val failure = IllegalStateException("proxies: [private subscription contents]", cause)
        failure.stackTrace = arrayOf(StackTraceElement("dev.aerove.Loader", "load", "private-config.yaml", 37))
        val log = meowHostFailureDiagnostic("method_failed", "service.init", failure)
        assertTrue(log.contains("code=method_failed method=service.init"))
        assertTrue(log.contains("java.lang.IllegalStateException")); assertTrue(log.contains("java.lang.IllegalArgumentException"))
        assertTrue(log.contains("dev.aerove.Loader.load:37"))
        for (secret in listOf("never-log-secret", "private.invalid", "private subscription", "private-config.yaml")) assertFalse(log.contains(secret))
        val unknown = meowHostFailureDiagnostic("https://private.invalid", "password=never-log-secret", failure)
        assertTrue(unknown.contains("code=invalid_code method=unknown")); assertFalse(unknown.contains("never-log-secret"))
        assertTrue(unknown.length <= 2048)
    }
}
