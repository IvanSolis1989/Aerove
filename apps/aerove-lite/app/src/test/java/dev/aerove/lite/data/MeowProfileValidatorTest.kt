package dev.aerove.lite.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowProfileValidatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun schema(native: NativeConfigValidator = NativeConfigValidator { null },
        prepared: NativePreparedConfigValidator = NativePreparedConfigValidator { _, _ -> null }) =
        CompatibilityValidator.forMeow(native, File(temporary.root, "runtime"), prepared) { emptySet() }
    private fun CompatibilityValidator.managedBlocking(yaml: String) = runBlocking { validateManagedSource(yaml) }
    private fun CompatibilityValidator.preparedBlocking(yaml: String, bundle: File) = runBlocking { validatePrepared(yaml, bundle) }

    @Test fun fixedSmartMainOutputUsesMeowSourceSchemaWithoutPrematureOfflineConversion() {
        val source = checkNotNull(javaClass.getResourceAsStream("/smart-config/meow-89abb221.json")).bufferedReader().use { it.readText() }
        var premature = 0
        val validator = schema(NativeConfigValidator { premature++; "offline cache miss must not be called before preparation" })
        val original = ConfigYaml.parse(source)
        validator.managedBlocking(source)
        assertEquals(0, premature)
        val actual = validator.resourceInput(source)
        assertEquals(149, (actual["rule-providers"] as Map<*, *>).size)
        assertEquals(168, (actual["rules"] as List<*>).size)
        for (field in listOf("dns", "hosts", "tun", "mode", "port", "external-controller", "secret", "sniffer")) assertEquals(original[field], actual[field])
        assertTrue((actual["rules"] as List<*>).any { (it as String).startsWith("AND,") })
        assertTrue((actual["rule-providers"] as Map<*, *>).values.all { (it as Map<*, *>)["proxy"] == "🚫 受限网站" })
        assertTrue(validator.coreVersion.startsWith("meow-rs/0.26.1/727ddcd"))
    }

    @Test fun finalPreparedConversionReceivesActualRootAndExactNativeFieldFailure() {
        val bundle = temporary.newFolder("bundle")
        val file = File(bundle, "rules.txt").apply { writeText("example.com\n") }
        var calls = 0
        val validator = schema(prepared = NativePreparedConfigValidator { yaml, root ->
            calls++; assertEquals(bundle.canonicalFile, root)
            val engine = ConfigYaml.parse(yaml)
            assertEquals(listOf("AND,((DST-PORT,443),(NETWORK,UDP)),DIRECT"), engine["rules"])
            assertEquals(false, (engine["tun"] as Map<*, *>)["enable"])
            "$.rules[0]: fixed native conversion rejected fixture"
        })
        val source = ConfigYaml.dump(mapOf("rule-providers" to mapOf("r" to mapOf("type" to "file", "path" to file.path, "behavior" to "domain", "format" to "text")),
            "rules" to listOf("AND,((DST-PORT,443),(NETWORK,UDP)),DIRECT")))
        val error = assertThrows(ConfigValidationException::class.java) { validator.preparedBlocking(source, bundle) }
        assertEquals("$.rules[0]", error.issues.single().path)
        assertTrue(error.issues.single().reason.contains("fixed native conversion rejected fixture"))
        assertEquals(1, calls)
        val outside = source.replace(file.path, File(temporary.root, "outside.txt").apply { writeText("example.com") }.path)
        val escaped = assertThrows(ConfigValidationException::class.java) { validator.preparedBlocking(outside, bundle) }
        assertEquals("$.rule-providers.r.path", escaped.issues.single().path)
        assertEquals(1, calls)
    }

    @Test fun sourceTunEnableRequiresHostReconciliation() {
        val failure = assertThrows(ConfigValidationException::class.java) { schema().managedBlocking("tun: {enable: true}") }
        assertEquals("$.tun.enable", failure.issues.single().path)
    }

    @Test fun sourceHostOwnershipAndLegacyConstructorRemainSeparate() {
        val source = "tun: {enable: false, auto-route: true}"
        val failure = assertThrows(ConfigValidationException::class.java) { schema().managedBlocking(source) }
        assertEquals("$.tun.auto-route", failure.issues.single().path)
        val legacy = CompatibilityValidator(NativeConfigValidator { null })
        assertEquals(CompatibilityValidator.CORE_VERSION, legacy.coreVersion)
        assertThrows(ConfigValidationException::class.java) { runBlocking { legacy.validate("sniffer: {enable: true}") } }
        schema().validateRulePayload(listOf("AND,((UID,1000),(NOT,((NETWORK,UDP)))),DIRECT"), "$.payload")
    }
}
