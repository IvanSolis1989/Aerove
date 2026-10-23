package dev.aerove.lite.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeowCapabilitiesTest {
    private fun fixture(features: List<String> = listOf("ss", "mux", "kcptun")) = JSONObject()
        .put("schemaVersion",1)
        .put("source",JSONObject().put("repository","https://github.com/meow-rs/meow-rs")
            .put("commit","727ddcd06f592e2f12c5222c4de6cce328df808c").put("tag","v0.26.1")
            .put("rawFileManifestSha256","a1948157edb77700f6f9152f3467907fb6a050c66cd153edfe98295bbf56e550")
            .put("adaptedFileManifestSha256","b".repeat(64)).put("overlaySha256","c".repeat(64)))
        .put("compiledFeatures",JSONArray(features)).put("verifiedFeatures",JSONArray(features))
        .put("builtInProtocols",JSONArray(listOf("direct","http","socks5")))
        .put("verification","fixedOfflineFactory").put("wireVerified",false)

    @Test fun exactOfflineReceiptProvidesOnlyImmutableVerifiedFeatureSetWithoutLoadingJni() {
        val value=MeowCapabilitiesCodec.decode(fixture().toString())
        assertEquals(setOf("ss","mux","kcptun"),value.verifiedFeatures)
        assertEquals(value.compiledFeatures,value.verifiedFeatures)
        assertEquals("fixedOfflineFactory",value.verification);assertFalse(value.wireVerified)
        assertThrows(UnsupportedOperationException::class.java){(value.verifiedFeatures as MutableSet).add("vless")}
        assertTrue(MeowCapabilitiesCodec.decode(fixture(emptyList()).toString()).verifiedFeatures.isEmpty())
    }
    @Test fun compiledDoesNotSubstituteForFactoryVerificationOrDependencyClosure() {
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("verifiedFeatures",JSONArray(listOf("ss"))).toString())}
        listOf(listOf("kcptun"),listOf("trusttunnel-h3"),listOf("vless-vision"),listOf("ss","ss"),listOf("ssh"),listOf("full")).forEach {
            assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture(it).toString())}
        }
        val reversed=fixture().put("verifiedFeatures",JSONArray(listOf("kcptun","mux","ss")))
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(reversed.toString())}
    }
    @Test fun fixedSourceAndManifestFormatsAreStrict() {
        mapOf("repository" to "https://example.com/meow", "commit" to "0".repeat(40), "tag" to "v0.26.0",
            "rawFileManifestSha256" to "0".repeat(64), "adaptedFileManifestSha256" to "B".repeat(64), "overlaySha256" to "missing").forEach { (key,value) ->
            val row=fixture();row.getJSONObject("source").put(key,value)
            assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(row.toString())}
        }
        val row=fixture();row.getJSONObject("source").put("unexpected",true)
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(row.toString())}
    }
    @Test fun schemaScopeTypesUnknownsAndBuiltinProofCannotBeCoerced() {
        listOf("1",2,JSONObject.NULL).forEach { assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("schemaVersion",it).toString())} }
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().toString().replace("\"schemaVersion\":1","\"schemaVersion\":1.0"))}
        listOf(true,"false",JSONObject.NULL).forEach { assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("wireVerified",it).toString())} }
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("verification","wireVerified").toString())}
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("builtInProtocols",JSONArray(listOf("direct","http"))).toString())}
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("extra",true).toString())}
        assertThrows(Exception::class.java){MeowCapabilitiesCodec.decode(fixture().put("compiledFeatures",JSONArray(listOf(1))).toString())}
    }
}
