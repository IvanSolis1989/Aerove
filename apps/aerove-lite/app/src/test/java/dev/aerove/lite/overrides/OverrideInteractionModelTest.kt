package dev.aerove.lite.overrides

import dev.aerove.lite.data.ConfigYaml
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OverrideInteractionModelTest {
    @Test fun structured_form_preserves_regex_logical_payload_and_parameter_order() {
        val regex = "DOMAIN-REGEX,^(a,b|c)\\.example$,MATCH"
        assertEquals(regex, OverrideRuleDraft.parse(regex).rawValue())
        val nested = "AND,((NETWORK,UDP),(DST-PORT,443)),REJECT-DROP"
        assertEquals(nested, OverrideRuleDraft.parse(nested).rawValue())
        val draft = OverrideRuleDraft.parse("IP-CIDR,10.0.0.0/8,MATCH,no-resolve,src")
        assertTrue(draft.src && draft.noResolve)
        assertEquals("IP-CIDR,10.0.0.0/8,MATCH,src,no-resolve", draft.rawValue())
        assertFalse(draft.copy(action = "DOMAIN").rawValue().contains("no-resolve"))
    }

    @Test fun form_rejects_standard_invalid_payloads_before_submission() {
        assertEquals(RuleDraftIssue.ContentRequired, OverrideRuleDraft().issue)
        assertEquals(RuleDraftIssue.TargetRequired, OverrideRuleDraft(content = "example.com", target = "").issue)
        assertEquals(RuleDraftIssue.Network, OverrideRuleDraft("NETWORK", "icmp").issue)
        assertNull(OverrideRuleDraft("NETWORK", "UDP").issue)
        assertEquals(RuleDraftIssue.NumberRange, OverrideRuleDraft("DST-PORT", "*").issue)
        assertEquals(RuleDraftIssue.NumberRange, OverrideRuleDraft("UID", "-1").issue)
        assertNull(OverrideRuleDraft("DST-PORT", "[80]/443/1000-2000").issue)
        assertEquals(RuleDraftIssue.DscpRange, OverrideRuleDraft("DSCP", "64").issue)
        assertNull(OverrideRuleDraft("DSCP", "0-63").issue)
    }

    @Test fun pure_fingerprint_never_executes_script_and_parameterized_match_uses_profile_target() = runBlocking {
        val repo = OverrideRepository(Files.createTempDirectory("policy").toFile())
        repo.putScript(ScriptResource("s", "Script", content = "function main(c){return c}"))
        repo.setProfile(ProfileOverride("p", OverrideMode.Script, "s"))
        var invocations = 0
        val pipeline = OverridePipeline(repo) { _, config -> invocations++; config }
        val fingerprint = pipeline.fingerprint("p", "not even parsed")
        assertEquals(0, invocations)
        assertEquals(fingerprint, pipeline.resolve("p", "rules: ['MATCH,DIRECT']").fingerprint)
        assertEquals(1, invocations)
        repo.setProfile(ProfileOverride("p", addedRules = listOf("IP-CIDR,10.0.0.0/8,MATCH,src,no-resolve", "DOMAIN,MATCH,src")))
        val transformed = pipeline.resolve("p", "rules: ['MATCH,PROXY']")
        assertEquals("IP-CIDR,10.0.0.0/8,PROXY,src,no-resolve", (ConfigYaml.parse(transformed.yaml)["rules"] as List<*>).first())
        assertEquals("DOMAIN,MATCH,src", (ConfigYaml.parse(transformed.yaml)["rules"] as List<*>)[1])
    }
}
