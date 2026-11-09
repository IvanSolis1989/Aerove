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
    @Test fun shareableFailureContainsOnlyBoundedCodeLocations() {
        val secret = "https://private.invalid/password"
        val failure = IllegalStateException(secret, IllegalArgumentException(secret))
        failure.stackTrace = arrayOf(StackTraceElement("dev.aerove.lite.Loader", "load", secret, 37))
        val diagnostic = meowFailureLocation("setupConfig", failure)
        assertTrue(diagnostic.startsWith("setupConfig: IllegalStateException@dev.aerove.lite.Loader.load:37"))
        assertTrue(diagnostic.contains("IllegalArgumentException"))
        assertFalse(diagnostic.contains(secret))
        assertTrue(meowFailureLocation(secret, failure).startsWith("unknown:"))
        assertTrue(meowFailureLocation("privateToken", failure).startsWith("unknown:"))
        failure.stackTrace = arrayOf(StackTraceElement("dev.aerove.lite.$secret", secret, secret, 9))
        assertFalse(meowFailureLocation("setupConfig", failure).contains(secret))
        assertTrue(diagnostic.length <= 1024)
    }
    @Test fun uiDiagnosisSurvivesStrippedFramesAndNeverEmitsUntrustedMessages() {
        val native = dev.aerove.lite.core.MeowStartupSnapshot("7", dev.aerove.lite.core.MeowStartupPhase.failed,
            false, false, false, dev.aerove.lite.core.MeowStartupStage.configLoad, false,
            dev.aerove.lite.core.MeowStartupPrimary(dev.aerove.lite.core.MeowStartupStage.configLoad,
                dev.aerove.lite.core.MeowStartupCode.configLoadFailed, dev.aerove.lite.core.MeowStartupIoKind.other,
                dev.aerove.lite.core.MeowStartupCauseType.dnsBootstrapResolve), dev.aerove.lite.core.MeowStartupCleanup.complete)
        val original = IllegalStateException("password=https://secret.invalid")
        original.stackTrace = emptyArray()
        dev.aerove.lite.core.MeowStartupFailureEvidence.attach(original, 7, Result.success(native), Result.success(native))
        val text = meowFailureLocation("setupConfig", original)
        assertTrue(text.contains("native=configLoad/configLoadFailed/other/dnsBootstrapResolve/cleanup=complete"))
        assertFalse(text.contains("secret.invalid")); assertFalse(text.contains("password"))
        val missing = IllegalStateException("shared_config_unstable").also { it.stackTrace = emptyArray() }
        assertTrue(meowFailureLocation("setupConfig", missing).contains("{shared_config_unstable}"))
        assertFalse(meowFailureLocation("setupConfig", IllegalStateException("shared_config_unstable password=secret")).contains("password"))
    }
}
