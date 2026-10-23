package dev.aerove.lite.data

import org.junit.Assert.*
import org.junit.Test

class MeowEngineSchemaTest {
    private fun normalize(yaml: String) = MeowCompatibilityValidator.normalize(yaml)
    private fun reject(path: String, yaml: String) {
        try { normalize(yaml); fail("Expected $path") }
        catch (e: ConfigValidationException) { assertEquals(path, e.issues.single().path) }
    }
    @Test fun projectedEngineRetainsDisabledTunHostsSnifferGeodataAndFileResources() {
        val source = """
            tun: {enable: false, auto-route: false}
            hosts: {'Case.Example': ['192.0.2.1', '2001:db8::1'], 'alias.example': 'Case.Example'}
            sniffer: {enable: true, timeout: 500, sniff: {TLS: {ports: [443, '8443-8444']}, HTTP: {ports: [80]}}, force-domain: ['+.example.com']}
            geodata: {mmdb-path: '/bundle/country.mmdb', asn-path: '/bundle/asn.mmdb', geosite-path: '/bundle/geosite.dat', auto-update: false}
            rule-providers: {local: {type: file, path: '/bundle/rules.yaml', behavior: domain, format: yaml}}
            proxy-providers: {nodes: {type: file, path: '/bundle/nodes.yaml', filter: '^node', exclude-type: [ss], override: {dialer-proxy: ''}}}
            proxy-groups: [{name: select, type: select, use: [nodes]}]
            rules: ['RULE-SET,local,select', 'MATCH,DIRECT']
        """.trimIndent()
        val output = ConfigYaml.parse(normalize(source))
        assertEquals(mapOf("enable" to false, "auto-route" to false), output["tun"])
        assertEquals(ConfigYaml.parse(source)["hosts"], output["hosts"])
        assertEquals(ConfigYaml.parse(source)["geodata"], output["geodata"])
        assertEquals(ConfigYaml.parse(source)["rule-providers"], output["rule-providers"])
        assertEquals(ConfigYaml.parse(source)["proxy-providers"], output["proxy-providers"])
    }
    @Test fun disabledTunRejectsEveryActivationAndUnknownField() {
        reject("$.tun.enable", "tun: {enable: true, auto-route: false}")
        reject("$.tun.auto-route", "tun: {enable: false, auto-route: true}")
        reject("$.tun.device", "tun: {enable: false, auto-route: false, device: 'fd://4'}")
        reject("$.tun.stack", "tun: {enable: false, auto-route: false, stack: system}")
    }
    @Test fun hostsAreStringOrNonemptyStringListWithoutResolvingNetwork() {
        normalize("hosts: {test.example: '192.0.2.1', alias.example: test.example}")
        reject("$.hosts.test.example[0]", "hosts: {test.example: [123]}")
        reject("$.hosts.test.example", "hosts: {test.example: []}")
    }
    @Test fun snifferRequiresActualSupportedPortsAndRejectsIgnoredOptions() {
        reject("$.sniffer.sniff", "sniffer: {enable: true}")
        reject("$.sniffer.sniff.QUIC", "sniffer: {enable: true, sniff: {QUIC: {ports: [443]}}}")
        reject("$.sniffer.force-dns-mapping", "sniffer: {force-dns-mapping: true}")
        reject("$.sniffer.sniff.TLS.override-destination", "sniffer: {sniff: {TLS: {ports: [443], override-destination: true}}}")
        reject("$.sniffer.timeout", "sniffer: {timeout: 0}")
        reject("$.sniffer.sniff.tls", "sniffer: {sniff: {TLS: {ports: [443]}, tls: {ports: [8443]}}}")
    }
    @Test fun ruleProviderVehicleSpecificConsumers() {
        normalize("rule-providers: {r: {type: inline, behavior: domain, payload: [example.com]}}")
        normalize("rule-providers: {r: {type: http, behavior: ipcidr, url: 'https://example.com/rules', interval: 3600, format: mrs, proxy: DIRECT, header: {X-Test: [a,b]}}}")
        reject("$.rule-providers.r.interval", "rule-providers: {r: {type: file, behavior: domain, path: /rules, interval: 60}}")
        reject("$.rule-providers.r.header", "rule-providers: {r: {type: file, behavior: domain, path: /rules, header: {X-Test: a}}}")
        reject("$.rule-providers.r.url", "rule-providers: {r: {type: http, behavior: domain, url: bad}}")
        reject("$.rule-providers.r.payload", "rule-providers: {r: {type: inline, behavior: domain}}")
        reject("$.rule-providers.r.mystery", "rule-providers: {r: {type: file, behavior: domain, path: /rules, mystery: true}}")
    }
    @Test fun proxyProviderOverrideAndHealthSchedulingAreNeverSilentlyIgnored() {
        normalize("proxy-providers: {p: {type: http, url: 'https://example.com/nodes', interval: 3600, dialer-proxy: DIRECT, header: {X-Test: a}, allow-external-plugin: false, health-check: {enable: false}}}")
        reject("$.proxy-providers.p.override.udp", "proxy-providers: {p: {type: file, path: /nodes, override: {udp: true}}}")
        reject("$.proxy-providers.p.health-check.enable", "proxy-providers: {p: {type: file, path: /nodes, health-check: {enable: true}}}")
        reject("$.proxy-providers.p.health-check.enable", "proxy-providers: {p: {type: file, path: /nodes, health-check: {}}}")
        reject("$.proxy-providers.p.header.X[0]", "proxy-providers: {p: {type: http, url: 'https://example.com/n', header: {X: [1]}}}")
    }
    @Test fun geodataRejectsWarnOnlyAndClampedValues() {
        normalize("geodata: {auto-update: false, url: {mmdb: 'https://example.com/country'}}")
        reject("$.geodata.geoip-matcher", "geodata: {geoip-matcher: succinct}")
        reject("$.geodata.auto-update-interval", "geodata: {auto-update-interval: 87601}")
        reject("$.geodata.url.unknown", "geodata: {url: {unknown: 'https://example.com/x'}}")
    }
}
