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
    @Test fun parkedGraphChecksUseFixedCodesAndNeverEchoNearMatchingSecrets() {
        val codes = mapOf(
            "skip-cert-verify: Graph policy differs from compiled setup" to "graph_certificate_policy_mismatch",
            "mode: Graph policy did not converge" to "graph_mode_not_converged",
            "Meow graph HTTP and native epochs differ" to "graph_epoch_mismatch",
            "Query graph user controller is not isolated" to "graph_controller_not_isolated",
            "Query graph opened public listeners" to "graph_public_listeners_open",
        )
        for ((message, code) in codes) {
            val failure = IllegalStateException(message).also { it.stackTrace = emptyArray() }
            assertTrue(meowFailureLocation("setupConfig", failure).contains("{$code}"))
            assertFalse(meowFailureLocation("setupConfig", IllegalStateException("$message password=secret")).contains("secret"))
        }
    }

    @Test fun stopOpenChecksPrefixTheObfuscatedStackWithFixedCodesOnly() {
        val codes = mapOf(
            "Meow returned an invalid handle" to "open_invalid_handle",
            "Invalid Meow FD/protect plan" to "open_fd_protect_mismatch",
            "Meow runtime mode differs from Builder" to "open_builder_mode_mismatch",
            "Meow applied configuration differs from submitted engine YAML" to "open_engine_yaml_mismatch",
            "Meow applied configuration profile mismatch" to "open_applied_profile_mismatch",
            "Meow prepared lineage mismatch" to "open_prepared_lineage_mismatch",
            "Meow startup intent was superseded" to "open_owner_superseded",
        )
        val privateValue = "https://private.invalid/token"
        for ((message, code) in codes) {
            val original = IllegalStateException(message).also {
                it.stackTrace = arrayOf(StackTraceElement("dev.aerove.lite.vpn.c0", "invokeSuspend", privateValue, 527))
            }
            val failure = IllegalStateException(privateValue, original).also { it.stackTrace = emptyArray() }
            val diagnostic = meowFailureLocation("service.stop", failure)
            assertTrue(diagnostic, diagnostic.startsWith("service.stop: {$code} IllegalStateException"))
            assertTrue(diagnostic, diagnostic.contains("@dev.aerove.lite.vpn.c0.invokeSuspend:527"))
            assertFalse(diagnostic.contains(privateValue))
            assertFalse(meowFailureLocation("service.start", original).contains(code))
            assertFalse(meowFailureLocation("service.stop", IllegalArgumentException(message)).contains(code))
            val nearMatch = meowFailureLocation("service.stop", IllegalStateException("$message $privateValue"))
            assertFalse(nearMatch.contains(code))
            assertFalse(nearMatch.contains(privateValue))
            assertTrue(diagnostic.length <= 1024)
        }
    }
}
