package dev.aerove.lite.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiteYamlRuntimeTest {
    @Test fun mapsAndMergedAliasesRoundtripInTheShrunkAndroidApplication() {
        val parsed = ConfigYaml.parse("base: &base {name: on, port: 443}\nnode: {<<: *base, name: off}")
        assertEquals(mapOf("name" to "off", "port" to 443), parsed["node"])
        assertEquals(parsed, ConfigYaml.parse(ConfigYaml.dump(parsed)))
    }

    @Test fun duplicateKeysAndRecursiveAliasesRemainRejected() {
        for (source in listOf("mode: rule\nmode: global", "root: &self {child: *self}")) {
            assertThrows(ConfigValidationException::class.java) { ConfigYaml.parse(source) }
        }
    }
}
