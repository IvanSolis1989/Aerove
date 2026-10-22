package dev.aerove.lite.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompatibilityValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private var nativeCalls = 0
    private val validator = CompatibilityValidator(NativeConfigValidator { nativeCalls++; null })

    private fun rejected(yaml: String, path: String) {
        val failure = assertThrows(ConfigValidationException::class.java) { validator.validate(yaml) }
        assertTrue(failure.message, failure.issues.any { it.path == path })
    }

    @Test fun supportedProfileAlsoRunsNativeParser() {
        validator.validate("mode: rule\nproxies:\n - {name: direct, type: direct}\nrules:\n - MATCH,direct")
        assertEquals(1, nativeCalls)
    }

    @Test fun nativeErrorsAreNotConvertedToSuccess() {
        val realFailure = CompatibilityValidator(NativeConfigValidator { "$.proxies[0].uuid: invalid UUID" })
        val failure = assertThrows(ConfigValidationException::class.java) {
            realFailure.validate("proxies:\n - {name: node, type: vless, server: example.com, port: 443, uuid: broken}")
        }
        assertTrue(failure.message!!.contains("uuid"))
    }

    @Test fun nestedUnknownTransportFieldHasExactPath() {
        rejected("proxies:\n - type: vless\n   name: Node\n   ws-opts: {path: /, unsupported: true}", "$.proxies[0].ws-opts.unsupported")
        assertEquals(0, nativeCalls)
    }

    @Test fun parsedButIgnoredFingerprintAndIdleFieldsAreRejected() {
        rejected("proxies:\n - {type: vless, name: N, client-fingerprint: chrome}", "$.proxies[0].client-fingerprint")
        rejected("proxies:\n - {type: anytls, name: N, min-idle-session: 1}", "$.proxies[0].min-idle-session")
    }

    @Test fun unknownTopLevelAndGroupFilterAreRejected() {
        rejected("sniffer: {enable: true}", "$.sniffer")
        rejected("proxy-groups:\n - {name: N, type: select, filter: test}", "$.proxy-groups[0].filter")
    }

    @Test fun unsupportedDnsPoliciesAreNotSilentlyAccepted() {
        rejected("dns: {enhanced-mode: redir-host}", "$.dns.enhanced-mode")
        rejected("dns: {nameserver: [system]}", "$.dns.nameserver[0]")
        rejected("dns: {nameserver-policy: {example.com: [1.1.1.1]}}", "$.dns.nameserver-policy.example.com")
        rejected("dns: {fake-ip-filter: ['geosite:cn']}", "$.dns.fake-ip-filter[0]")
    }

    @Test fun androidProcessRuleIsRejectedInsideLogicalRules() {
        rejected("rules:\n - AND,((PROCESS-NAME,app),(NETWORK,TCP)),DIRECT", "$.rules[0]")
    }

    @Test fun geoRequirementAndUnmanagedProvidersAreExplicitErrors() {
        rejected("rules:\n - GEOIP,CN,DIRECT", "$.rules[0]")
        rejected("proxy-providers: {remote: {type: http, url: 'https://example.com/list'}}", "$.proxy-providers")
    }

    @Test fun additionalInboundsAndExternalUiAreRejected() {
        rejected("listeners: []", "$.listeners")
        rejected("external-ui: public", "$.external-ui")
    }

    @Test fun duplicateKeysAndRecursiveAliasesAreRejected() {
        rejected("mode: rule\nmode: global", "$")
        assertThrows(ConfigValidationException::class.java) { validator.validate("hosts: &self {localhost: *self}") }
    }

    @Test fun privateGeoFileMustExistAndCannotEscape() {
        val check = CompatibilityValidator(NativeConfigValidator { null }, temporary.root)
        assertThrows(ConfigValidationException::class.java) { check.validate("geosite: ../outside.dat") }
        assertThrows(ConfigValidationException::class.java) { check.validate("geosite: missing.dat") }
        File(temporary.root, "resources").mkdirs()
        File(temporary.root, "resources/geo.dat").writeBytes(byteArrayOf(1))
        check.validate("geosite: geo.dat\nrules:\n - GEOSITE,cn,DIRECT")
    }
}
