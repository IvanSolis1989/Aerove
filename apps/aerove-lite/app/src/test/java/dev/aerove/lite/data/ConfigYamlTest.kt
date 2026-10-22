package dev.aerove.lite.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.representer.Representer

class ConfigYamlTest {
    @Test fun mapSerializationRetainsScalarQuotingAndAliases() {
        val shared = listOf("on", "off", "true", "0012", "2026-10-22", null, true, 42)
        val value = linkedMapOf("first" to shared, "second" to shared, "name" to "代理")
        assertEquals(Yaml().dump(value), ConfigYaml.dump(value))
        val roundtrip = ConfigYaml.parse(ConfigYaml.dump(value))
        assertEquals(shared, roundtrip["first"])
        assertEquals(shared, roundtrip["second"])
    }

    @Test fun lowLevelLoadingKeepsExistingDepthBoundary() {
        for (depth in listOf(49, 50, 51)) {
            val yaml = "{key: ".repeat(depth) + "value" + "}".repeat(depth)
            val options = LoaderOptions().apply { nestingDepthLimit = 50 }
            val dumper = DumperOptions()
            val previous = runCatching {
                Yaml(SafeConstructor(options), Representer(dumper), dumper, options).load<Any>(yaml)
            }
            val current = runCatching { ConfigYaml.parse(yaml) }
            assertEquals("depth=$depth", previous.isSuccess, current.isSuccess)
            if (previous.isSuccess) assertEquals(previous.getOrThrow(), current.getOrThrow())
        }
    }
}
