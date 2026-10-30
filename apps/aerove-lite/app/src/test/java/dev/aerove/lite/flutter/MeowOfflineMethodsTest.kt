package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.NativeConfigValidator
import dev.aerove.lite.profilesource.AgeDecryptTransport
import dev.aerove.lite.regexp2.ExactFilterExecutor
import dev.aerove.lite.regexp2.FilterProtocol
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowOfflineMethodsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder().canonicalFile
    private fun methods(home: File, validator: NativeConfigValidator = NativeConfigValidator { null },
        age: AgeDecryptTransport = AgeDecryptTransport { _, _ -> error("Test did not authorize helper use") },
        filters: ExactFilterExecutor = ExactFilterExecutor { _, _, _, _ -> error("Test did not authorize helper use") }) =
        MeowOfflineMethods(home, validator, { setOf("ss", "vmess", "vless", "trojan", "hysteria2", "anytls") }, age, filters)
    private fun ss(name: String = "Node", udp: Boolean? = null): Map<String, Any?> = linkedMapOf<String, Any?>("name" to name,
        "type" to "ss", "server" to "node.example", "port" to 443, "cipher" to "aes-128-gcm", "password" to "p@ss:word").apply { if (udp != null) put("udp", udp) }
    private fun ssLink(name: String = "Node") = "ss://${Base64.getEncoder().withoutPadding().encodeToString("aes-128-gcm:p@ss:word".toByteArray())}@node.example:443#$name"

    @Test fun registersExactlyTheSixSharedMethods() {
        assertEquals(setOf("decryptConfig", "validateProxies", "validateFilters", "convertProxies", "encodeShareLinks", "decodeShareLinks"), methods(home()).methods)
    }

    @Test fun decryptReturnsOriginalPlaintextWithoutSpillOrDatabase() = runBlocking {
        val home = home(); val source = File(home, "plain.yaml").apply { writeText("# original\r\nproxies: []\r\n") }
        val before = source.readBytes()
        assertEquals(source.readText(), methods(home).invoke("decryptConfig", mapOf("path" to source.path, "age-secret-key" to "")))
        assertArrayEquals(before, source.readBytes())
        assertEquals(setOf(source.name), home.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun encryptedSourceUsesOwnedIdentityAndSanitizesHelperErrors() = runBlocking {
        val home = home(); val source = File(home, "cipher.age").apply { writeText("age-encryption.org/v1\nsynthetic-ciphertext") }
        var identities: ByteArray? = null
        val api = methods(home, age = AgeDecryptTransport { _, key -> identities = key; "mode: rule\n".toByteArray() })
        assertEquals("mode: rule\n", api.invoke("decryptConfig", mapOf("path" to source.path, "age-secret-key" to "secret-only-for-test")))
        assertTrue(identities!!.all { it == 0.toByte() })
        val failure = failure("age_decrypt_failed") {
            methods(home, age = AgeDecryptTransport { _, _ -> error("secret-only-for-test") }).invoke("decryptConfig", mapOf("path" to source.path, "age-secret-key" to "secret-only-for-test"))
        }
        assertFalse(failure.toString().contains("secret-only-for-test"))
        assertFalse(failure.details.toString().contains("secret-only-for-test"))
    }

    @Test fun ageCancellationPropagatesAndWipesTheTransferredIdentity() = runBlocking {
        val home = home(); val source = File(home, "cipher.age").apply { writeText("age-encryption.org/v1\nsynthetic") }
        var identity: ByteArray? = null
        try {
            methods(home, age = AgeDecryptTransport { _, key -> identity = key; throw CancellationException("cancelled") })
                .invoke("decryptConfig", mapOf("path" to source.path, "age-secret-key" to "key"))
            fail()
        } catch (_: CancellationException) {}
        assertTrue(identity!!.all { it == 0.toByte() })
    }

    @Test fun nativeProxyValidationKeepsPositionsAndActualDiagnostics() = runBlocking {
        val calls = mutableListOf<Map<String, Any?>>()
        val api = methods(home(), NativeConfigValidator { yaml ->
            val root = ConfigYaml.parse(yaml); calls += root
            val proxy = (root["proxies"] as List<*>).single() as Map<*, *>
            if (proxy["name"] == "native-bad") "$.proxies[0].server: actual native failure" else null
        })
        val values = listOf(mapOf("name" to "direct", "type" to "direct"), ss() + ("port" to "wrong"), ss("native-bad"))
        val results = api.invoke("validateProxies", values) as List<*>
        assertEquals(3, results.size)
        assertEquals("", results[0])
        assertTrue(results[1].toString().contains("$.proxies[1].port"))
        assertEquals("$.proxies[2].server: actual native failure", results[2])
        assertEquals(2, calls.size)
        calls.forEach { assertEquals(true, it["strict"]); assertEquals(listOf("MATCH,DIRECT"), it["rules"]) }
    }

    @Test fun nativeInfrastructureFailureIsAnEnvelopeErrorNotAnInvalidNodeOrEmptySuccess() = runBlocking {
        val api = methods(home(), NativeConfigValidator { error("native worker unavailable") })
        val result = failure("native_validation_failed") { api.invoke("validateProxies", listOf(ss())) }
        assertEquals("$.proxies[0]", result.details["field"])
    }

    @Test fun exactFilterValidationUsesTheRealWireParserAndMaintainsSlots() = runBlocking {
        val seen = mutableListOf<String>()
        val api = methods(home(), filters = ExactFilterExecutor { include, exclude, names, validate ->
            assertTrue(validate); assertTrue(exclude.isEmpty()); assertTrue(names.isEmpty()); assertEquals(1, include.size)
            val expression = include.single(); seen += expression
            val request = JSONObject(FilterProtocol.request(1, include, exclude, names, validate).toString(Charsets.UTF_8))
            assertTrue(request.getBoolean("validateOnly"))
            if (expression == "bad") FilterProtocol.indices(1, """{"id":1,"engine":"regexp2-v1.12.0+aerove-cancel1/None","status":"invalid","error":{"path":"$.include[0].parts[0]","message":"actual compile diagnostic"}}""".toByteArray(), 0)
            else emptyList()
        })
        assertEquals(listOf("", "$.filters[1].parts[0]: actual compile diagnostic", ""), api.invoke("validateFilters", listOf("(?<=a)b`c", "bad", "")))
        assertEquals(listOf("(?<=a)b`c", "bad", ""), seen)
    }

    @Test fun filterTransportAndCancellationCannotBecomeEmptyRegexDiagnostics() = runBlocking {
        failure("filter_engine_failed") { methods(home(), filters = ExactFilterExecutor { _, _, _, _ -> error("transport disconnected") }).invoke("validateFilters", listOf("a")) }
        try { methods(home(), filters = ExactFilterExecutor { _, _, _, _ -> throw CancellationException() }).invoke("validateFilters", listOf("a")); fail() }
        catch (_: CancellationException) {}
    }

    @Test fun actualShareCodecsRoundTripAndRetainEveryProvidedField() = runBlocking {
        val api = methods(home())
        val originals = listOf(ss("Node"), mapOf("name" to "Socks", "type" to "socks5", "server" to "node.example", "port" to 1080))
        val links = api.invoke("encodeShareLinks", originals) as List<*>
        assertEquals(2, links.size)
        assertTrue(links.all { it is String && it.isNotEmpty() })
        val decoded = api.invoke("decodeShareLinks", links) as List<*>
        originals.forEachIndexed { index, original ->
            val node = (decoded[index] as List<*>).single() as Map<*, *>
            original.forEach { (key, value) -> assertEquals("$index.$key", value, node[key]) }
        }
    }

    @Test fun emptyEncoderSlotsAndUnrepresentableFieldsAreExplicitFailures() = runBlocking {
        val api = methods(home())
        assertEquals("$.proxies[0].type", failure("implementation_blocked") { api.invoke("encodeShareLinks", listOf(mapOf("name" to "Direct", "type" to "direct"))) }.details["field"])
        assertEquals("$.proxies[0].udp", failure("implementation_blocked") { api.invoke("encodeShareLinks", listOf(ss(udp = false))) }.details["field"])
        assertEquals("$.proxies[0].type", failure("implementation_blocked") { api.invoke("encodeShareLinks", listOf(mapOf("name" to "HTTP", "type" to "http", "server" to "node.example", "port" to 8080))) }.details["field"])
    }

    @Test fun perInputDecodePreservesZeroOneAndMultipleNodeSlots() = runBlocking {
        val api = methods(home())
        val nested = api.invoke("decodeShareLinks", listOf("", ssLink("One"), ssLink("Two") + "\n" + ssLink("Three"))) as List<*>
        assertEquals(listOf(0, 1, 2), nested.map { (it as List<*>).size })
        val invalid = failure("share_decode_error") { api.invoke("decodeShareLinks", listOf(ssLink(), "ss://not-base64")) }
        assertTrue(invalid.details["field"].toString().startsWith("$.lines[1]"))
        failure("implementation_blocked") { api.invoke("decodeShareLinks", listOf("https://subscription.example/a")) }
        Unit
    }

    @Test fun convertUsesTheActualPrivateFileAndBase64DecoderWithoutModifyingIt() = runBlocking {
        val home = home(); val file = File(home, "subscription.txt").apply { writeText(Base64.getEncoder().encodeToString((ssLink("A") + "\n" + ssLink("B")).toByteArray())) }
        val original = file.readBytes()
        val nodes = methods(home).invoke("convertProxies", file.path) as List<*>
        assertEquals(listOf("A", "B"), nodes.map { (it as Map<*, *>)["name"] })
        assertArrayEquals(original, file.readBytes())
        file.writeText("proxies: []")
        failure("implementation_blocked") { methods(home).invoke("convertProxies", file.path) }
        Unit
    }

    @Test fun privatePathTypesUnicodeAndRecursiveInputsFailExplicitly() = runBlocking {
        val home = home(); val outside = temporary.newFile().apply { writeText(ssLink()) }; val api = methods(home)
        failure("bad_argument") { api.invoke("convertProxies", outside.path) }
        failure("bad_argument") { api.invoke("convertProxies", "relative.txt") }
        failure("bad_argument") { api.invoke("validateFilters", listOf("\uD800")) }
        failure("bad_argument") { api.invoke("decryptConfig", mapOf("path" to outside.path)) }
        val cycle = mutableMapOf<String, Any?>(); cycle["cycle"] = cycle
        failure("bad_argument") { api.invoke("validateProxies", listOf(cycle)) }
        failure("bad_method") { api.invoke("other", null) }
        Unit
    }

    @Test fun budgetsFailBeforeAnyNativeOrHelperWork() = runBlocking {
        val home = home(); val file = File(home, "plain.yaml").apply { writeText("{}") }
        var calls = 0
        val api = methods(home, NativeConfigValidator { calls++; null }, AgeDecryptTransport { _, _ -> calls++; byteArrayOf() },
            ExactFilterExecutor { _, _, _, _ -> calls++; emptyList() })
        failure("bad_argument") { api.invoke("validateProxies", List(2049) { mapOf("name" to "d", "type" to "direct") }) }
        failure("bad_argument") { api.invoke("validateFilters", List(4097) { "a" }) }
        failure("bad_argument") { api.invoke("decryptConfig", mapOf("path" to file.path, "age-secret-key" to "x".repeat(64 * 1024 + 1))) }
        assertEquals(0, calls)
    }

    @Test fun dispatcherReceivesExactResultsAndStructuredMethodFailures() = runBlocking {
        val home = home(); val api = methods(home, NativeConfigValidator { error("native unavailable") })
        val dispatcher = MeowProtocolDispatcher(home, api.methods.associateWith { method -> MeowProtocolMethod { api.invoke(method, it) } })
        val error = JSONObject(dispatcher.invoke("""{"id":"v","method":"validateProxies","arguments":[{"name":"d","type":"direct"}]}""").toString(Charsets.UTF_8))
        assertEquals("v", error.getString("id"))
        assertEquals("native_validation_failed", error.getJSONObject("error").getString("code"))
        assertEquals("$.proxies[0]", error.getJSONObject("error").getJSONObject("details").getString("field"))
        val success = JSONObject(dispatcher.invoke("""{"id":"d","method":"decodeShareLinks","arguments":[""]}""").toString(Charsets.UTF_8))
        assertFalse(success.has("error"))
        assertEquals(1, success.getJSONArray("result").length())
        assertEquals(0, success.getJSONArray("result").getJSONArray(0).length())
        assertTrue(dispatcher.closeAndJoin())
    }

    private suspend fun failure(code: String, operation: suspend () -> Any?): MeowProtocolFailure {
        try { operation(); fail("Expected $code") } catch (failure: MeowProtocolFailure) { assertEquals(code, failure.code); return failure }
        error("No failure")
    }
}
