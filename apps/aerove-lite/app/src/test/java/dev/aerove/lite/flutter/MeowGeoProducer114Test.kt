package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigYaml
import org.junit.Assert.*
import org.junit.Test

class MeowGeoProducer114Test {
    private fun body(url: String, origin: Map<String, Any?> = emptyMap(), rule: String = "MATCH,DIRECT"): String = ConfigYaml.dump(mapOf(
        "aerove-geo-origin" to mapOf("schema" to 1, "source" to origin),
        "geodata" to mapOf("auto-update" to false, "auto-update-interval" to 24, "url" to mapOf("mmdb" to url)),
        "rules" to listOf(rule)))

    @Test fun completeProducerComparisonAcceptsOnlyGlobalFallbackGeoLeaves() {
        val before = body("https://before.test/mmdb")
        val after = body("https://after.test/mmdb")
        val actual = MeowGeoProducer.verifyDifference(before, after)
        assertTrue(actual.urls.values.contains("https://after.test/mmdb"))
        try { MeowGeoProducer.verifyDifference(before, body("https://after.test/mmdb", rule = "MATCH,REJECT")); fail("Non-Geo change accepted") }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun explicitProfileOrScriptGeoOriginCannotBeReplacedByGlobalFallback() {
        val explicit = mapOf("url" to mapOf("mmdb" to "https://before.test/mmdb"))
        val before = body("https://before.test/mmdb", explicit)
        try { MeowGeoProducer.verifyDifference(before, body("https://after.test/mmdb")); fail("Source origin changed") }
        catch (_: IllegalArgumentException) {}
        assertEquals("https://before.test/mmdb", MeowGeoProducer.verifyDifference(before, before).urls.values.first { it == "https://before.test/mmdb" })
    }
}
