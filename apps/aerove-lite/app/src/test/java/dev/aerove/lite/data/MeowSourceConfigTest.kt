package dev.aerove.lite.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowSourceConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun sourceWithoutGeneratedTunKeepsExtensionsAndOriginalFile() {
        val file = File(temporary.newFolder(), "config.yaml").apply {
            writeText("x-script-origin: {revision: actual}\ntun: {enable: false}\nrules: ['MATCH,DIRECT']\n")
        }
        val original = file.readBytes()
        val parsed = MeowSourceConfig.input(file.readText()).first
        assertEquals(mapOf("revision" to "actual"), parsed["x-script-origin"])
        assertEquals(mapOf("enable" to false), parsed["tun"])
        assertEquals(listOf("MATCH,DIRECT"), parsed["rules"])
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun sourceTunPolicyCannotBeSilentlyOverriddenByAndroidHostDefaults() {
        val policies = mapOf("enable" to "true", "mtu" to "1280", "dns-hijack" to "['any:53']",
            "device" to "custom", "auto-route" to "false", "inet4-address" to "198.18.0.1/30", "inet6-address" to "fdfe:dcba:9876::1/126",
            "stack" to "lwip", "congestion-controller" to "builtin", "unknown-future-field" to "true")
        policies.forEach { (field, value) ->
            val file = File(temporary.newFolder(), "script.yaml").apply { writeText("tun: {$field: $value}\n") }
            val original = file.readBytes()
            val failure = assertThrows(ConfigValidationException::class.java) { MeowSourceConfig.input(file.readText()) }
            assertEquals("$.tun.$field", failure.issues.single().path)
            assertArrayEquals(original, file.readBytes())
        }
    }
}
