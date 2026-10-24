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
            rejects { MeowNativeCore.inspectRuleSet(file.path) }
        }
        withFile("example.com\n192.0.2.0/24\n".toByteArray()) { file ->
            rejects { MeowNativeCore.inspectRuleSet(file.path) }
        }
        withFile("example.com\n".toByteArray()) { file ->
            rejects { MeowNativeCore.dumpRuleSet(file.path, "ipcidr", "text") }
            rejects { MeowNativeCore.dumpRuleSet(file.path, "domain", "mrs") }
        }
    }

    private fun assertInfo(json: String, format: String, behavior: String, count: Int) {
        val info = JSONObject(json)
        assertEquals(format, info.getString("format"))
        assertEquals(behavior, info.getString("behavior"))
        assertEquals(count, info.getInt("count"))
    }

    private fun rejects(action: () -> String) {
        try {
            action()
            fail("Invalid native rule file was accepted")
        } catch (error: RuntimeException) {
            assertTrue(error.message.orEmpty().startsWith("rule_set_invalid:"))
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
