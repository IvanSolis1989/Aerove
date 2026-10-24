package dev.aerove.lite

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.core.MeowNativeCore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuleFileNativeTest {
    @Test
    fun textIsInspectedAndDumpedThroughRealJniWithoutChangingTheSource() {
        withFile("# source\nDOMAIN-SUFFIX,example.com\n".toByteArray()) { file ->
            assertInfo(MeowNativeCore.inspectRuleSet(file.path), "text", "domain", 1)
            assertEquals("+.example.com\n", MeowNativeCore.dumpRuleSet(file.path, "domain", "text"))
        }
    }

    @Test
    fun yamlRulesAliasAndBareAddressesHaveCanonicalPayloads() {
        withFile("rules: ['192.0.2.1', '2001:db8::1']\n".toByteArray()) { file ->
            assertInfo(MeowNativeCore.inspectRuleSet(file.path), "yaml", "ipcidr", 2)
            val lines = MeowNativeCore.dumpRuleSet(file.path, "ipcidr", "yaml").lineSequence().filter(String::isNotEmpty).toSet()
            assertEquals(setOf("192.0.2.1/32", "2001:db8::1/128"), lines)
        }
    }

    @Test
    fun emptyLocalFilesRemainEmptyClassicalSets() {
        for ((source, format) in listOf("" to "text", "payload: []\n" to "yaml")) {
            withFile(source.toByteArray()) { file ->
                assertInfo(MeowNativeCore.inspectRuleSet(file.path), format, "classical", 0)
                assertEquals("", MeowNativeCore.dumpRuleSet(file.path, "classical", format))
            }
        }
    }

    @Test
    fun corruptedAndMismatchedFilesThrowInsteadOfReportingAnEmptySuccess() {
        withFile(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0)) { file ->
            rejects(file, "domain", "mrs", "$.payload.mrs") { MeowNativeCore.inspectRuleSet(file.path) }
        }
        withFile("example.com\n192.0.2.0/24\n".toByteArray()) { file ->
            rejects(file, "domain", "text", "$.payload", "rule_set_mixed: domains and IP ranges") { MeowNativeCore.inspectRuleSet(file.path) }
        }
        withFile("example.com\n".toByteArray()) { file ->
            rejects(file, "ipcidr", "text", "$.behavior", "rule_set_behavior_mismatch") { MeowNativeCore.dumpRuleSet(file.path, "ipcidr", "text") }
            rejects(file, "domain", "mrs", "$.format", "rule_set_format_mismatch") { MeowNativeCore.dumpRuleSet(file.path, "domain", "mrs") }
        }
    }

    private fun assertInfo(json: String, format: String, behavior: String, count: Int) {
        val info = JSONObject(json)
        assertEquals(format, info.getString("format"))
        assertEquals(behavior, info.getString("behavior"))
        assertEquals(count, info.getInt("count"))
    }

    private fun rejects(file: File, behavior: String, format: String, field: String, reason: String? = null, action: () -> String) {
        val diagnostic = checkNotNull(MeowNativeCore.validateResource("ruleset", file.path, behavior, format)) {
            "Invalid native rule file passed resource validation"
        }
        val prefix = "rule_set_invalid: $field: "
        assertTrue("Native diagnostic lost its typed field: $diagnostic", diagnostic.startsWith(prefix))
        assertTrue("Native diagnostic lost its parser reason", diagnostic.removePrefix(prefix).isNotBlank())
        if (reason != null) assertEquals(prefix + reason, diagnostic)
        try {
            action()
            fail("Invalid native rule file was accepted")
        } catch (error: RuntimeException) {
            assertEquals(RuntimeException::class.java, error.javaClass)
            assertEquals("JNI must preserve the native diagnostic through its fixed error policy", "Rust error: $diagnostic", error.message)
        }
    }

    private fun withFile(source: ByteArray, action: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("rule-native-", ".data", context.cacheDir)
        try {
            file.writeBytes(source)
            action(file)
            assertTrue("Native inspection must preserve the original bytes", source.contentEquals(file.readBytes()))
        } finally {
            check(file.delete()) { "Rule fixture cleanup failed" }
        }
    }
}
