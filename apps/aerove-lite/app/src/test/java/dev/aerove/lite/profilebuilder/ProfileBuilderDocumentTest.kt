package dev.aerove.lite.profilebuilder

import dev.aerove.lite.data.ConfigYaml
import org.junit.Assert.*
import org.junit.Test

class ProfileBuilderDocumentTest {
    private val source = """
        mode: rule
        dns:
          enable: true
          nameserver: [1.1.1.1]
        proxies:
          - {name: on, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: off}
          - {name: backup, type: socks5, server: example.net, port: 1080, dialer-proxy: on}
        proxy-groups:
          - {name: select, type: select, proxies: [on, backup, DIRECT]}
        rules:
          - DOMAIN-SUFFIX,example.org,select
          - MATCH,DIRECT
    """.trimIndent()

    @Test fun renamePropagatesMembersDialersAndRulesWithoutChangingOtherFields() {
        val doc = ProfileBuilderDocument.parse(source)
        val changed = doc.put(BuilderSection.Proxies, 0, "renamed", doc.entries(BuilderSection.Proxies)[0].fields)
        assertEquals(listOf("renamed", "backup", "DIRECT"), changed.entries(BuilderSection.Groups)[0].fields["proxies"])
        assertEquals("renamed", changed.entries(BuilderSection.Proxies)[1].fields["dialer-proxy"])
        assertEquals(ConfigYaml.parse(source)["dns"], ConfigYaml.parse(changed.yaml())["dns"])
        assertEquals("off", changed.entries(BuilderSection.Proxies)[0].fields["password"])
        assertTrue(changed.issues().isEmpty())
        assertEquals("on", doc.entries(BuilderSection.Proxies)[0].name)
    }

    @Test fun referencedDeletionRequiresExplicitValidReplacement() {
        val doc = ProfileBuilderDocument.parse(source)
        assertThrows(IllegalArgumentException::class.java) { doc.delete(BuilderSection.Proxies, setOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { doc.delete(BuilderSection.Proxies, setOf(0), "missing") }
        val changed = doc.delete(BuilderSection.Proxies, setOf(0), "DIRECT")
        assertEquals("DIRECT", changed.entries(BuilderSection.Proxies)[0].fields["dialer-proxy"])
        assertTrue(changed.issues().isEmpty())
    }

    @Test fun groupRenameChangesNestedLogicalRuleTargetOnly() {
        val doc = ProfileBuilderDocument.parse(source).put(BuilderSection.Rules, 0, "", mapOf("rule" to "AND,((DOMAIN,a),(DOMAIN,b)),select,no-resolve"))
        val changed = doc.put(BuilderSection.Groups, 0, "new", doc.entries(BuilderSection.Groups)[0].fields)
        assertEquals("AND,((DOMAIN,a),(DOMAIN,b)),new,no-resolve", changed.entries(BuilderSection.Rules)[0].fields["rule"])
    }

    @Test fun providerRenameAndDeleteTrackUseAndRuleSetReferences() {
        var doc = ProfileBuilderDocument.empty()
            .put(BuilderSection.ProxyProviders, null, "nodes", mapOf("type" to "file", "path" to "nodes.yaml"))
            .put(BuilderSection.RuleProviders, null, "domains", mapOf("type" to "file", "path" to "rules.yaml", "behavior" to "domain"))
            .put(BuilderSection.Groups, null, "auto", mapOf("type" to "select", "use" to listOf("nodes")))
            .put(BuilderSection.Rules, 0, "", mapOf("rule" to "RULE-SET,domains,auto"))
        doc = doc.put(BuilderSection.ProxyProviders, 0, "remote", doc.entries(BuilderSection.ProxyProviders)[0].fields)
        assertEquals(listOf("remote"), doc.entries(BuilderSection.Groups)[0].fields["use"])
        assertThrows(IllegalArgumentException::class.java) { doc.delete(BuilderSection.RuleProviders, setOf(0)) }
        doc = doc.put(BuilderSection.RuleProviders, 0, "new", doc.entries(BuilderSection.RuleProviders)[0].fields)
        assertEquals("RULE-SET,new,auto", doc.entries(BuilderSection.Rules)[0].fields["rule"])
        assertTrue(doc.issues().isEmpty())
    }

    @Test fun graphFindsMixedDialerAndGroupCyclesAndMissingTargets() {
        var doc = ProfileBuilderDocument.parse(source)
        doc = doc.put(BuilderSection.Proxies, 0, "on", doc.entries(BuilderSection.Proxies)[0].fields + ("dialer-proxy" to "select"))
        assertTrue(doc.issues().any { "cycle" in it.reason })
        doc = doc.put(BuilderSection.Groups, 0, "select", mapOf("type" to "select", "proxies" to listOf("missing")))
        assertTrue(doc.issues().any { it.path == "$.proxy-groups[0].proxies" && "missing" in it.reason })
    }

    @Test fun sortDuplicateAndBatchDeletionPreserveOrderAcrossYamlRoundTrip() {
        var doc = ProfileBuilderDocument.parse(source).duplicate(BuilderSection.Proxies, 0, "copy")
        doc = doc.move(BuilderSection.Proxies, 2, 0)
        assertEquals(listOf("backup", "on", "copy"), doc.entries(BuilderSection.Proxies).map { it.name })
        doc = doc.delete(BuilderSection.Proxies, setOf(1, 2), "DIRECT")
        val reopened = ProfileBuilderDocument.parse(doc.yaml())
        assertEquals(listOf("backup"), reopened.entries(BuilderSection.Proxies).map { it.name })
        assertTrue(reopened.issues().isEmpty())
    }

    @Test fun terminalAndNestedRuleParsingRejectsBrokenStructure() {
        assertEquals("MATCH,DIRECT", ruleParts("MATCH,DIRECT").encode())
        assertEquals("IP-CIDR,10.0.0.0/8,REJECT,no-resolve", ruleParts("IP-CIDR,10.0.0.0/8,REJECT,no-resolve").encode())
        assertThrows(IllegalArgumentException::class.java) { ruleParts("AND,((DOMAIN,a),DIRECT") }
        assertThrows(IllegalArgumentException::class.java) { ruleParts("DOMAIN,,DIRECT") }
    }

    @Test fun namespaceCollisionAndReservedNamesAreRefused() {
        val doc = ProfileBuilderDocument.parse(source)
        assertThrows(IllegalArgumentException::class.java) { doc.put(BuilderSection.Groups, null, "on", mapOf("type" to "select")) }
        assertThrows(IllegalArgumentException::class.java) { doc.put(BuilderSection.Proxies, 0, "DIRECT", doc.entries(BuilderSection.Proxies)[0].fields) }
    }
}
