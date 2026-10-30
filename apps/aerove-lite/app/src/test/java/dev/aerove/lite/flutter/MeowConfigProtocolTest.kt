package dev.aerove.lite.flutter

import dev.aerove.lite.data.ConfigValidationException
import dev.aerove.lite.data.ConfigYaml
import dev.aerove.lite.data.NativeConfigValidator
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MeowConfigProtocolTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun home() = temporary.newFolder().canonicalFile
    private fun source(home: File, text: String) = File(home, "profile.yaml").apply { writeText(text) }
    private fun protocol(home: File, validator: NativeConfigValidator = NativeConfigValidator { null },
        budget: MeowSpillBudget = MeowSpillBudget(), maxInputBytes: Int = 4 * 1024 * 1024) =
        MeowConfigProtocol(home, validator, { emptySet() }, budget, maxInputBytes)

    @Test fun getConfigReturnsReadableJsonFileWithScriptKeysAndNoMihomoDefaults() = runBlocking {
        val home = home()
        val input = source(home, "x-extension: {unicode: '代理', null-value: null}\nproxies: []\n")
        val original = input.readBytes()
        protocol(home).use { api ->
            val output = File(api.getConfig(input.path))
            assertEquals(File(home, "responses"), output.parentFile)
            assertEquals("json", output.extension)
            val row = JSONObject(output.readText())
            assertEquals(0, row.getJSONArray("rules").length())
            assertEquals(0, row.getJSONObject("proxy-providers").length())
            assertEquals("代理", row.getJSONObject("x-extension").getString("unicode"))
            assertTrue(row.getJSONObject("x-extension").isNull("null-value"))
            assertFalse(row.has("mixed-port"))
            assertFalse(row.has("dns"))
            assertFalse(row.has("rule"))
            assertArrayEquals(original, input.readBytes())
            api.discard(output.path)
            assertFalse(output.exists())
        }
    }

    @Test fun profileBaseReturnsRawShaCountsMatchRulesAndRemainingConfig() = runBlocking {
        val home = home()
        val input = source(home, """
            # digest includes this comment and CRLF
            mode: rule
            x-extension: {keep: true}
            proxies: [{name: on, type: direct}]
            proxy-groups: [{name: main, type: select, proxies: [on]}]
            sub-rules: {inner: ['MATCH,DIRECT']}
            rules: ['DOMAIN,example.com,main', ' match ,main', 'MATCH,DIRECT']
        """.trimIndent().replace("\n", "\r\n"))
        val original = input.readBytes()
        protocol(home).use { api ->
            val base = api.getProfileBase(input.path)
            assertEquals(setOf("config", "digest", "match-rules", "proxies", "proxy-groups", "rules"), base.keys)
            assertEquals(sha(original), base["digest"])
            assertEquals(1, base["proxies"])
            assertEquals(1, base["proxy-groups"])
            assertEquals(3, base["rules"])
            assertEquals(listOf(" match ,main", "MATCH,DIRECT"), base["match-rules"])
            val config = base["config"] as Map<*, *>
            assertEquals(setOf("mode", "x-extension"), config.keys)
            assertEquals(mapOf("keep" to true), config["x-extension"])
            assertArrayEquals(original, input.readBytes())
        }
    }

    @Test fun outboundsProjectRealNameAndTypeWithOriginalOrder() = runBlocking {
        val home = home()
        val input = source(home, "proxies: [{name: on, type: direct}, {name: '0012', type: socks5}]\nproxy-groups: [{name: main, type: select}]\n")
        protocol(home).use { api ->
            assertEquals(mapOf("proxies" to listOf(mapOf("name" to "on", "type" to "direct"), mapOf("name" to "0012", "type" to "socks5")),
                "proxy-groups" to listOf(mapOf("name" to "main", "type" to "select"))), api.getProfileOutbounds(input.path))
        }
    }

    @Test fun buildUsesStandardBaseSectionReplacementAndPrependsOverlayRules() = runBlocking {
        val home = home()
        val input = source(home, """
            mode: direct
            dns: {nameserver: [base], fallback: [discard]}
            x-base: discard
            proxies: [{name: base, type: direct}]
            proxy-groups: [{name: main, type: select, proxies: [base]}]
            sub-rules: {inner: ['MATCH,base']}
            rules: ['DOMAIN,base.example,main', 'MATCH,base']
        """.trimIndent())
        val original = input.readBytes()
        protocol(home).use { api ->
            val path = api.buildProfile(input.path, """
                mode: rule
                dns: {nameserver: [overlay]}
                x-overlay: preserve
                proxies: [{name: wrong, type: direct}]
                proxy-groups: []
                sub-rules: {wrong: []}
                rules: ['DOMAIN,first.example,DIRECT']
            """.trimIndent())
            val output = File(path)
            assertEquals("yaml", output.extension)
            assertEquals(File(home, "responses"), output.parentFile)
            val merged = ConfigYaml.parse(output.readText())
            val base = ConfigYaml.parse(input.readText())
            listOf("proxies", "proxy-groups", "sub-rules").forEach { assertEquals(base[it], merged[it]) }
            assertEquals(listOf("DOMAIN,first.example,DIRECT", "DOMAIN,base.example,main", "MATCH,base"), merged["rules"])
            assertEquals("rule", merged["mode"])
            assertEquals(mapOf("nameserver" to listOf("overlay")), merged["dns"])
            assertFalse(merged.containsKey("x-base"))
            assertEquals("preserve", merged["x-overlay"])
            assertArrayEquals(original, input.readBytes())
        }
    }

    @Test fun mergeRetainsOverlaySectionsAbsentFromBaseAndHandlesNullRulesLikeStandard() {
        val overlay = "proxies: [{name: overlay, type: direct}]\nrules: ['MATCH,overlay']\n"
        val expected = ConfigYaml.parse(overlay)
        assertEquals(expected, ConfigYaml.parse(MeowProfileCodec.merge("{}", overlay)))
        assertEquals(expected, ConfigYaml.parse(MeowProfileCodec.merge("rules: null", overlay)))
        assertEquals(listOf("MATCH,DIRECT"), ConfigYaml.parse(MeowProfileCodec.merge("rules: ['MATCH,DIRECT']", "rules: []"))["rules"])
        assertNull(ConfigYaml.parse(MeowProfileCodec.merge("rules: null", "rules: []"))["rules"])
    }

    @Test fun parserPreservesAliasMergedSectionsAndShortIdStrings() = runBlocking {
        val home = home()
        val input = source(home, """
            x-base: &base
              proxies: [{name: off, type: direct, short-id: '0012'}]
              rules: ['MATCH,off']
            <<: *base
        """.trimIndent())
        protocol(home).use { api ->
            val result = ConfigYaml.parse(File(api.buildProfile(input.path, "{}")).readText())
            val proxy = (result["proxies"] as List<*>).single() as Map<*, *>
            assertEquals("off", proxy["name"])
            assertEquals("0012", proxy["short-id"])
            assertEquals(listOf("MATCH,off"), result["rules"])
        }
    }

    @Test fun malformedYamlAndWrongRulesCannotProduceSuccessSpills() = runBlocking {
        val home = home()
        protocol(home).use { api ->
            for (text in listOf("mode: rule\nmode: direct", "rules: scalar", "proxies: [{name: wrong, type: direct}\n")) {
                val input = source(home, text)
                try { api.getConfig(input.path); fail("Accepted $text") } catch (_: ConfigValidationException) {}
            }
            val input = source(home, "proxies: [{type: direct}]")
            try { api.getProfileOutbounds(input.path); fail() } catch (error: ConfigValidationException) {
                assertEquals("$.proxies[0].name", error.issues.single().path)
            }
            assertEquals(0, File(home, "responses").listFiles()?.size ?: 0)
        }
    }

    @Test fun validatorRunsExistingSourceAndCompatibilityGatesBeforeNative() = runBlocking {
        val home = home()
        var calls = 0
        protocol(home, NativeConfigValidator { calls++; null }).use { api ->
            for ((text, field) in listOf("tun: {stack: system}" to "$.tun.stack", "dns: {respect-rules: true}" to "$.dns.respect-rules")) {
                assertTrue(api.validateConfig(source(home, text).path).contains(field))
            }
            assertEquals(0, calls)
        }
    }

    @Test fun injectedNativeDiagnosticAndNormalizedInputArePreserved() = runBlocking {
        val home = home()
        val input = source(home, "proxies: [{name: local, type: direct}]\nrules: ['MATCH,local']\n")
        var received: String? = null
        protocol(home, NativeConfigValidator { yaml -> received = yaml; "$.rules[0]: actual native rejection" }).use { api ->
            val diagnostic = api.validateConfig(input.path)
            assertTrue(diagnostic.contains("$.rules[0]"))
            assertTrue(diagnostic.contains("actual native rejection"))
            val normalized = ConfigYaml.parse(received!!)
            assertEquals(true, normalized["strict"])
            assertEquals(mapOf("enable" to false, "auto-route" to false), normalized["tun"])
        }
        protocol(home).use { assertEquals("", it.validateConfig(input.path)) }
    }

    @Test fun nativeTransportFailureCannotBecomeEmptySuccess() = runBlocking {
        val home = home()
        protocol(home, NativeConfigValidator { throw IllegalStateException("native unavailable") }).use { api ->
            try { api.validateConfig(source(home, "{}").path); fail() }
            catch (failure: IllegalStateException) { assertEquals("native unavailable", failure.message) }
        }
    }

    @Test fun fileAndDirectorySpillBudgetsFailWithoutPartialFiles() = runBlocking {
        val home = home()
        val input = source(home, "x: '${"a".repeat(150)}'")
        protocol(home, budget = MeowSpillBudget(80, 160, 2)).use { api ->
            expectCode("spill_error") { api.getConfig(input.path) }
            assertEquals(0, File(home, "responses").listFiles()?.size ?: 0)
        }
        source(home, "{}")
        protocol(home, budget = MeowSpillBudget(100, 200, 1)).use { api ->
            val first = File(api.getConfig(input.path))
            expectCode("spill_error") { api.getConfig(input.path) }
            assertEquals(1, first.parentFile.listFiles()!!.size)
            api.discard(first.path)
            assertTrue(File(api.getConfig(input.path)).isFile)
        }
        assertEquals(0, File(home, "responses").listFiles()!!.size)
    }

    @Test fun byteBudgetIncludesExistingUnownedResponses() = runBlocking {
        val home = home()
        val directory = File(home, "responses").apply { mkdir() }
        val external = File(directory, "existing.json").apply { writeText("x".repeat(195)) }
        val input = source(home, "{}")
        protocol(home, budget = MeowSpillBudget(100, 200, 5)).use { api ->
            expectCode("spill_error") { api.getConfig(input.path) }
        }
        assertTrue(external.isFile)
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun uniqueSpillsAndCloseDeleteOnlyOwnedFiles() = runBlocking {
        val home = home()
        val input = source(home, "{}")
        val api = protocol(home)
        val first = File(api.getConfig(input.path))
        val second = File(api.getConfig(input.path))
        assertNotEquals(first, second)
        val other = File(first.parentFile, "other.json").apply { writeText("other") }
        api.discard(input.path)
        assertTrue(input.exists())
        api.close()
        api.close()
        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals("other", other.readText())
        expectCode("closed") { api.getConfig(input.path) }
    }

    @Test fun inputBudgetAndUtf8FailureAreExplicit() = runBlocking {
        val home = home()
        protocol(home, maxInputBytes = 8).use { api ->
            expectCode("input_too_large") { api.getConfig(source(home, "key: ${"x".repeat(10)}").path) }
            val input = source(home, "{}")
            expectCode("bad_argument") { api.buildProfile(input.path, "key: ${"x".repeat(10)}") }
            input.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            expectCode("invalid_encoding") { api.getConfig(input.path) }
        }
    }

    @Test fun pathAndArgumentFailuresDoNotReadOutsideHome() = runBlocking {
        val home = home()
        val outside = temporary.newFile().apply { writeText("{}") }
        val input = source(home, "{}")
        protocol(home).use { api ->
            expectCode("path_error") { api.getConfig(outside.path) }
            expectCode("path_error") { api.getConfig("profile.yaml") }
            expectCode("path_error") { api.getConfig(File(home, "sub/../profile.yaml").path) }
            expectCode("bad_argument") { api.invoke("getConfig", 1) }
            expectCode("bad_argument") { api.invoke("buildProfile", mapOf("path" to input.path)) }
            expectCode("unsupported_method") { api.invoke("other", null) }
        }
    }

    @Test fun symlinkedInputAndResponseDirectoryAreRejected() = runBlocking {
        val home = home()
        val input = source(home, "{}")
        val alias = File(home, "alias.yaml")
        try { Files.createSymbolicLink(alias.toPath(), input.toPath()) }
        catch (failure: Exception) { assumeNoException("Host cannot create test symlinks", failure) }
        protocol(home).use { api -> expectCode("path_error") { api.getConfig(alias.path) } }
        val elsewhere = temporary.newFolder().canonicalFile
        Files.createSymbolicLink(File(home, "responses").toPath(), elsewhere.toPath())
        protocol(home).use { api -> expectCode("spill_error") { api.getConfig(input.path) } }
        assertEquals(0, elsewhere.listFiles()!!.size)
    }

    @Test fun windowsJunctionsCannotRedirectHomeInputOrResponses() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val home = home()
        val elsewhere = home()
        val input = source(elsewhere, "{}")
        val link = File(home, "redirect")
        junction(link, elsewhere)
        try {
            protocol(home).use { api -> expectCode("path_error") { api.getConfig(File(link, input.name).path) } }
            expectCode("path_error") { protocol(link) }
        } finally { Files.deleteIfExists(link.toPath()) }
        val responses = File(home, "responses")
        junction(responses, elsewhere)
        try {
            protocol(home).use { api -> expectCode("spill_error") { api.getConfig(source(home, "{}").path) } }
            assertEquals(setOf(input.name), elsewhere.listFiles()!!.map { it.name }.toSet())
        } finally { Files.deleteIfExists(responses.toPath()) }
    }

    @Test fun cancellationNeverAcknowledgesNativeOrPublishesResponse() = runBlocking {
        val home = home()
        val input = source(home, "{}")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        protocol(home, NativeConfigValidator { entered.complete(Unit); release.await(); null }).use { api ->
            val request = async { api.validateConfig(input.path) }
            entered.await()
            request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(0, File(home, "responses").listFiles()?.size ?: 0)
        }
    }

    @Test fun cancellationDuringActualSpillWriteRemovesTemporaryAndFinalFiles() = runBlocking {
        val home = home()
        val directory = File(home, "responses").apply { mkdir() }
        val budget = MeowSpillBudget(32 * 1024 * 1024, 64L * 1024 * 1024, 2)
        MeowFlutterSpill(home, budget).use { spill ->
            directory.toPath().fileSystem.newWatchService().use { watcher ->
                directory.toPath().register(watcher, ENTRY_CREATE)
                val text = "x".repeat(budget.maxFileBytes)
                val request = async(Dispatchers.IO) { spill.write(text, "yaml") }
                val key = withContext(Dispatchers.IO) { watcher.poll(5, TimeUnit.SECONDS) }
                assertNotNull("No actual temporary write was observed", key)
                assertTrue(key.pollEvents().any { it.context().toString().endsWith(".tmp") })
                request.cancelAndJoin()
                assertTrue("The write completed before cancellation could be exercised", request.isCancelled)
                assertEquals(0, directory.listFiles()!!.size)
            }
        }
    }

    @Test fun dispatcherUsesExactSharedMethodShapes() = runBlocking {
        val home = home()
        val input = source(home, "{}")
        protocol(home).use { api ->
            assertTrue(api.invoke("getProfileBase", input.path) is Map<*, *>)
            assertEquals(mapOf("proxies" to emptyList<Any>(), "proxy-groups" to emptyList<Any>()), api.invoke("getProfileOutbounds", input.path))
            val output = api.invoke("buildProfile", mapOf("path" to input.path, "overlay" to "mode: rule")) as String
            assertEquals("rule", ConfigYaml.parse(File(output).readText())["mode"])
        }
    }

    private suspend fun expectCode(code: String, work: suspend () -> Any?) {
        try { work(); fail("Expected $code") }
        catch (failure: MeowConfigProtocolException) { assertEquals(code, failure.code) }
    }

    private fun junction(link: File, target: File) {
        assertTrue(link.absoluteFile.toPath().startsWith(temporary.root.canonicalFile.toPath()))
        assertTrue(target.toPath().startsWith(temporary.root.canonicalFile.toPath()))
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.path, target.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
