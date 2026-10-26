package dev.aerove.lite.data

import dev.aerove.lite.LiteSettings
import dev.aerove.lite.overrides.*
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AddedRulesPropagationTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun globalAddedRulesChangeTheRealStandardPolicyFingerprintAndCompiledInputWhileCustomBodyStaysExact() = runBlocking<Unit> {
        val root=temporary.root;val repository=OverrideRepository(root)
        val source="rules: ['MATCH,DIRECT']\n"
        var custom=false
        val pipeline=ProfileSourcePipeline(BuilderResourceLibrary(root),OverridePipeline(repository){_,yaml->yaml},{LiteSettings()},
            {ProfileDnsSelection(customProfile=custom)})
        val initialSnapshot=repository.snapshot()
        val initial=pipeline.forStart("profile",source)
        custom=true;val customBefore=pipeline.forStart("custom",source)
        repository.setGlobalRules(listOf(OverrideRule("global","DOMAIN,added.example,DIRECT")))
        val saved=repository.snapshot()
        assertNotEquals(overridePolicyFingerprint(initialSnapshot,"profile"),overridePolicyFingerprint(saved,"profile"))
        custom=false;val changed=pipeline.forStart("profile",source)
        assertNotEquals(initial.policyFingerprint,changed.policyFingerprint)
        assertEquals(listOf("DOMAIN,added.example,DIRECT","MATCH,DIRECT"),ConfigYaml.parse(changed.compiledYaml)["rules"])
        assertFalse(initial.isCurrent())
        custom=true;val customAfter=pipeline.forStart("custom",source)
        assertEquals(ConfigYaml.parse(customBefore.compiledYaml),ConfigYaml.parse(customAfter.compiledYaml))
        assertEquals(listOf("MATCH,DIRECT"),ConfigYaml.parse(customAfter.compiledYaml)["rules"])
    }
}
