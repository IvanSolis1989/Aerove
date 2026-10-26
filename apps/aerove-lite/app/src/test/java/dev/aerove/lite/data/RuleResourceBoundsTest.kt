package dev.aerove.lite.data

import dev.aerove.lite.integration.GlobalResourceWorkspace
import dev.aerove.lite.profilebuilder.BuilderResourceLibrary
import dev.aerove.lite.profilebuilder.GlobalLibraryFile
import dev.aerove.lite.resources.CachedLibraryRule
import dev.aerove.lite.resources.ManagedRuleResource
import dev.aerove.lite.resources.ResourceManagementRepository
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.source
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Both sides of every Kotlin rule-resource read bound, through the production call sites; fixtures stream from disk. */
class RuleResourceBoundsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bom = 0xFEFF.toChar().toString()
    private val mib32 = 32L * 1024 * 1024
    private val editor = "Configuration exceeds $MAX_PROFILE_BYTES UTF-8 bytes"

    private fun fixture(size: Long, head: String, unit: String = "x", tail: String = "\n"): File = fixture(size, head.toByteArray(), unit, tail)
    private fun fixture(size: Long, start: ByteArray, unit: String = "x", tail: String = "\n"): File = temporary.newFile().apply {
        outputStream().buffered(1 shl 16).use { out ->
            val end = tail.toByteArray(); val step = unit.toByteArray()
            out.write(start)
            var left = size - start.size - end.size
            while (left >= step.size) { out.write(step); left -= step.size }
            repeat(left.toInt()) { out.write('x'.code) }
            out.write(end)
        }
        check(length() == size)
    }
    private fun yamlEntries(count: Int, extra: String = ""): File = temporary.newFile().apply {
        bufferedWriter().use { writer -> writer.write("payload:\n"); repeat(count) { writer.write("  - r$it.example\n") }; writer.write(extra) }
    }
    private fun yamlOfSize(size: Long) = fixture(size, "payload:\n  - example.com\n#", "x".repeat(998) + "\n#", "\n")
    private fun body(file: File) = Buffer().apply { file.source().use { writeAll(it) } }
    private fun jdkStrict(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun failure(action: () -> Any?): Throwable = runCatching(action).exceptionOrNull() ?: throw AssertionError("Expected a failure")
    private fun issue(error: Throwable) = (error as ConfigValidationException).issues.single().let { "${it.path}: ${it.reason}" }
    private fun File.lines(): List<String> = mutableListOf<String>().also { lines -> forEachRuleLine(MAX_RULE_INPUT_BYTES) { _, line -> lines += line }.requireEntryBound("$.p") }

    private class SizedParser : NativeRulePayloadInspector {
        var dumpText: String? = null
        override suspend fun inspect(path: String): RulePayloadInspection =
            if (File(path).inputStream().use { it.read() } == 0xff) RulePayloadInspection("mrs", "domain", 1)
            else RulePayloadInspection("text", "domain", File(path).countRuleLines(MAX_RULE_INPUT_BYTES))
        override suspend fun dump(path: String, behavior: String, format: String): String = dumpText
            ?: StringBuilder().also { text -> File(path).forEachRuleLine(MAX_RULE_INPUT_BYTES) { _, line -> text.append(line).append('\n') } }.toString()
    }

    private class Library(root: File) {
        val parser = SizedParser()
        val validator = CompatibilityValidator(NativeConfigValidator { null }, File(root, "runtime"))
        val manager = ResourceBundleManager(File(root, "runtime"), validator, NativeResourceValidator { _, _, _, _ -> null }, offlineRules = parser)
        val library = BuilderResourceLibrary(root)
        val profiles = ProfileRepository(root, ProfileValidator {}, resources = manager, globalResources = library)
        val payloads = GlobalRulePayloadRepository(root, library, manager, parser, profiles)
        val workspace = GlobalResourceWorkspace(library, profiles, payloads, { null }, {})
    }

    @Test fun boundsAreTheNativeAdmissionConstants() {
        assertEquals(64L * 1024 * 1024, MAX_RULE_INPUT_BYTES)
        assertEquals(64L * 1024 * 1024, MAX_RULE_TEXT_BYTES)
        assertEquals(250_000, MAX_RULE_PAYLOAD_ENTRIES)
        assertEquals(2048, MAX_RULE_ENTRY_BYTES)
        assertEquals(250_002, MAX_RULE_YAML_VALUES)
        assertEquals(1024 * 1024, MAX_RULE_YAML_TOKEN_CHARS)
    }

    @Test fun streamedLinesMatchTheLineSequenceTheyReplace() {
        val wide = 0x3000.toChar()
        val samples = mutableListOf("", "a", "a\n", "a\r\nb\rc\n\n  d  \n", "$bom# c\n// x\n e \r\n", "a\n${bom}b\n", "\t x$wide\n", "/\n/ a\n//b\n /",
            "x".repeat(2047) + "\r\n" + "y", "中".repeat(682) + "ab\r" + "\n" + " z ", "\u0085a\u0085\n\u001Fb\u001F\n# \n  #x\n", "a\r\r\nb\n\r")
        val random = Random(20261026)
        repeat(300) { samples += String(CharArray(random.nextInt(0, 120)) { "ab #/ \t\r\n　中\u0085".random(random) }) }
        samples.forEachIndexed { index, sample ->
            val file = temporary.newFile("lines-$index").apply { writeBytes(sample.toByteArray()) }
            val expected = jdkStrict(sample.toByteArray()).removePrefix(bom).lineSequence().map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith("//") }.toList()
            assertEquals(sample, expected, file.lines())
            assertEquals(sample, expected.size, file.countRuleLines(MAX_RULE_INPUT_BYTES))
            assertEquals(jdkStrict(sample.toByteArray()).removePrefix(bom), file.readEditorRuleText(MAX_RULE_TEXT_BYTES))
        }
    }

    @Test fun ruleEntriesAreBoundedInTrimmedUtf8BytesAndCountsNeverAre() {
        val exact = listOf("a".repeat(2048), "中".repeat(682) + "ab", " \t" + "b".repeat(2048) + " ".repeat(5000), "c".repeat(1000) + " " + "d".repeat(1047))
        exact.forEach { line -> assertEquals(listOf(line.trim()), temporary.newFile().apply { writeText("# skipped\n$line\n") }.lines()) }
        for (over in listOf("a".repeat(2049), "中".repeat(682) + "abc", "c".repeat(1000) + "  " + "d".repeat(1047))) {
            val file = temporary.newFile().apply { writeText("first.example\n$over\nlast.example\n") }
            val seen = mutableListOf<String>()
            val scan = file.forEachRuleLine(MAX_RULE_INPUT_BYTES) { index, line -> seen += "$index:$line" }
            assertEquals(listOf("0:first.example", "2:last.example"), seen)
            assertEquals("$.p[1]: Rule entry exceeds 2048 UTF-8 bytes", issue(failure { scan.requireEntryBound("$.p") }))
            assertEquals(3, file.countRuleLines(MAX_RULE_INPUT_BYTES))
        }
        val validator = CompatibilityValidator(NativeConfigValidator { null })
        val check = validator.ruleLineCheck("$.p")
        val scan = temporary.newFile().apply { writeText("UID,1000,DIRECT\n" + "x".repeat(3000) + "\n") }.forEachRuleLine(MAX_RULE_INPUT_BYTES, check::line)
        assertEquals("$.p[0]", (failure { check.finish() } as ConfigValidationException).issues.single().path)
        assertEquals("$.p[1]: Rule entry exceeds 2048 UTF-8 bytes", issue(failure { scan.requireEntryBound("$.p") }))
    }

    @Test fun boundedReadsKeepTheReadBoundedTextOnEitherSideOfTheLimit() {
        val file = temporary.newFile("bounded").apply { writeText("payload:\n  - example.com\n") }
        val size = file.length()
        assertEquals(listOf("payload:", "- example.com"), file.lines())
        assertEquals(listOf("example.com"), file.readRuleYaml(size)["payload"])
        val reference = failure { file.inputStream().use { it.readBounded((size - 1).toInt()) } }
        for (read in listOf<(Long) -> Any?>({ file.readEditorRuleText(it) }, { file.forEachRuleLine(it) { _, _ -> } }, { file.countRuleLines(it) }, { file.readRuleYaml(it) })) {
            val error = failure { read(size - 1) }
            assertTrue(error is IOException && error !is ConfigValidationException)
            assertEquals(reference.message, error.message)
        }
    }

    @Test fun encodingAndSizeErrorsStillComeBeforeYamlErrorsAndBounds() {
        val bad = byteArrayOf(0xE4.toByte(), 0x41)
        val inputs = listOf("payload: [a\n".toByteArray() + "#".repeat(9000).toByteArray() + bad,
            yamlEntries(MAX_RULE_PAYLOAD_ENTRIES + 1).readBytes() + bad, yamlEntries(MAX_RULE_PAYLOAD_ENTRIES + 1, "#${"x".repeat(998)}\n".repeat(256)).readBytes() + bad,
            "payload:\n  - a\n#".toByteArray() + ByteArray(MAX_RULE_YAML_TOKEN_CHARS + 10) { 'x'.code.toByte() } + bad)
        for (bytes in inputs) {
            val file = temporary.newFile().apply { writeBytes(bytes) }
            assertTrue(failure { file.readRuleYaml(MAX_RULE_INPUT_BYTES) } is MalformedInputException)
            assertEquals("Input exceeds ${bytes.size - 1} bytes", failure { file.readRuleYaml(bytes.size - 1L) }.message)
        }
        val text = temporary.newFile().apply { writeBytes("first\n".toByteArray() + "x".repeat(5000).toByteArray() + "\n".toByteArray() + bad) }
        assertTrue(failure { text.forEachRuleLine(MAX_RULE_INPUT_BYTES) { _, _ -> } } is MalformedInputException)
        if (MAX_RULE_PREVIEW_BYTES < MAX_RULE_TEXT_BYTES) {
            val preview = temporary.newFile().apply { writeBytes(ByteArray(MAX_RULE_PREVIEW_BYTES + 1) { 'x'.code.toByte() } + bad) }
            assertTrue(failure { preview.readEditorRuleText(MAX_RULE_TEXT_BYTES) } is MalformedInputException)
        }
    }

    @Test fun yamlReaderAgreesWithTheWholeDocumentLoaderOnEveryDocumentWithinTheBound() {
        val random = Random(4026)
        fun scalar() = listOf("a", "'q x'", "\"d\\tq\"", "1", "true", "~", "", "-x", "[]", "{}", "*z", "&k v", "! t").random(random)
        fun node(depth: Int): String = when (if (depth > 3) 0 else random.nextInt(4)) {
            0 -> scalar()
            1 -> "[" + List(random.nextInt(4)) { node(depth + 1) }.joinToString(", ") + "]"
            2 -> "{" + List(random.nextInt(4)) { "k$it: ${node(depth + 1)}" }.joinToString(", ") + "}"
            else -> listOf("&z [x, y]", "&m {a: 1}", "*z", "*m", "&s txt").random(random)
        }
        var compared = 0
        repeat(1500) { round ->
            val document = when (round % 4) {
                0 -> "payload: ${node(0)}\n"
                1 -> "payload:\n" + List(random.nextInt(6)) { "  - ${node(1)}\n" }.joinToString("") + listOf("", "extra: ${node(1)}\n", "  bad\n").random(random)
                2 -> "a: &z [x, y]\nm: &m {a: 1}\npayload: [${List(random.nextInt(5)) { node(1) }.joinToString(", ")}]\n"
                else -> node(0) + "\n" + listOf("", "---\nb: 1\n", "...\n", "# c\n").random(random)
            }
            val file = temporary.newFile().apply { writeText(document) }
            val expected = runCatching { ConfigYaml.parse(document.reader(), MAX_RULE_INPUT_BYTES.toInt(), MAX_RULE_YAML_VALUES) }
            val actual = runCatching { file.readRuleYaml(MAX_RULE_INPUT_BYTES) }
            assertEquals(document, expected.getOrNull(), actual.getOrNull())
            assertEquals(document, expected.exceptionOrNull()?.let { (it as ConfigValidationException).issues }, actual.exceptionOrNull()?.let { (it as ConfigValidationException).issues })
            compared++
        }
        assertEquals(1500, compared)
    }

    @Test fun yamlValueBoundStopsOneValuePastItWithTheLoaderMessage() {
        assertEquals(MAX_RULE_PAYLOAD_ENTRIES, (yamlEntries(MAX_RULE_PAYLOAD_ENTRIES).readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"] as List<*>).size)
        assertEquals("$.payload[250000]: Expanded configuration exceeds 250002 values",
            issue(failure { yamlEntries(MAX_RULE_PAYLOAD_ENTRIES + 1).readRuleYaml(MAX_RULE_INPUT_BYTES) }))
        val aliases = temporary.newFile().apply { writeText("payload:\n  - &a [${List(6000) { "x" }.joinToString(",")}]\n  - [${List(50) { "*a" }.joinToString(",")}]\n") }
        assertEquals("$.payload[1][40]: Expanded configuration exceeds 250002 values", issue(failure { aliases.readRuleYaml(MAX_RULE_INPUT_BYTES) }))
        val nested = temporary.newFile().apply { writeText("payload: " + "[".repeat(300_000) + "]".repeat(300_000) + "\n") }
        assertEquals("$: Invalid YAML", issue(failure { nested.readRuleYaml(MAX_RULE_INPUT_BYTES) }))
        val many = (0..MAX_RULE_PAYLOAD_ENTRIES).joinToString("") { "  - r$it.example\n" }
        for (document in listOf("payload:\n  - *missing\n$many", "a: &a [x]\npayload:\n${"  - *a\n".repeat(51)}$many", "payload: [a]\n---\npayload:\n$many")) {
            val expected = issue(failure { ConfigYaml.parse(document.reader(), MAX_RULE_INPUT_BYTES.toInt(), MAX_RULE_YAML_VALUES) })
            assertTrue(expected, expected.startsWith("$: Invalid YAML"))
            assertEquals(expected, issue(failure { temporary.newFile().apply { writeText(document) }.readRuleYaml(MAX_RULE_INPUT_BYTES) }))
        }
        val config = "rules:\n" + (0..100_000).joinToString("") { "  - r$it\n" }
        assertEquals("Expanded configuration exceeds 100000 values", (failure { ConfigYaml.parse(config) } as ConfigValidationException).issues.single().reason)
    }

    @Test fun yamlTokensAndTheTextBetweenValuesAreBoundedButCommentsResetTheCount() {
        val cap = MAX_RULE_YAML_TOKEN_CHARS
        val bound = "YAML scalar, comment or gap exceeds $cap characters at line"
        assertEquals(listOf("a"), temporary.newFile().apply { writeText("payload:\n  - a\n#" + "x".repeat(cap - 1) + "\n") }.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"])
        assertTrue(issue(failure { temporary.newFile().apply { writeText("payload:\n  - a\n#" + "x".repeat(cap) + "\n") }.readRuleYaml(MAX_RULE_INPUT_BYTES) }).startsWith("$: $bound 3"))
        assertEquals(listOf("x".repeat(cap - 64)), temporary.newFile().apply { writeText("payload:\n  - " + "x".repeat(cap - 64) + "\n") }.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"])
        assertTrue(issue(failure { temporary.newFile().apply { writeText("payload:\n  - " + "x".repeat(cap + 1) + "\n") }.readRuleYaml(MAX_RULE_INPUT_BYTES) }).startsWith("$: $bound 2"))
        val block = "payload:\n  - |\n" + "    " + "y".repeat(63) + "\n".repeat(1) + ("    " + "y".repeat(63) + "\n").repeat(cap / 64)
        assertTrue(issue(failure { temporary.newFile().apply { writeText(block) }.readRuleYaml(MAX_RULE_INPUT_BYTES) }).startsWith("$: $bound"))
        val comments = "payload:\n  - first.example\n" + "  # - commented.example\n".repeat(3 * cap / 24) + "  - last.example\n"
        assertEquals(listOf("first.example", "last.example"), temporary.newFile().apply { writeText(comments) }.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"])
        val error = failure { ConfigYaml.parseRule(BoundedYamlStream("payload: [a\n".reader()), 1024, MAX_RULE_YAML_VALUES) } as ConfigValidationException
        assertEquals("Invalid YAML at line 2", error.issues.single().reason)
        val marked = runCatching { ParserImplProbe.parse(BoundedYamlStream("a: [b\n".reader())) }.exceptionOrNull()!!
        assertTrue(marked.toString().contains("line 2"))
    }

    private object ParserImplProbe {
        fun parse(stream: BoundedYamlStream) { val parser = org.yaml.snakeyaml.parser.ParserImpl(stream, ConfigYaml.options(1024)); while (parser.event != null) Unit }
    }

    @Test fun profileRuleProvidersAdmitExactlyTheBounds() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val native = mutableListOf<String>()
            val profiles = ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(runtime, validator,
                NativeResourceValidator { _, path, _, format -> native += "$format:${File(path).length()}"; null }))
            fun source(format: String) = """
                rule-providers:
                  big: {type: http, url: '${server.url("/big-$format")}', path: big.$format, behavior: domain, format: $format}
                rules: ['RULE-SET,big,DIRECT', 'MATCH,DIRECT']
            """.trimIndent()
            val key = "$.rule-providers.big"
            val resources = ResourceManagementRepository(profiles)
            val text = fixture(MAX_RULE_INPUT_BYTES, "example.com\n#")
            server.enqueue(MockResponse().setBody(body(text)))
            val textId = profiles.importText("Text", source("text")).id
            assertEquals(1, resources.snapshot(textId).entries.single { it.key == key }.count)
            assertEquals(MAX_RULE_INPUT_BYTES.toInt(), resources.preview(textId, key).length)
            server.enqueue(MockResponse().setBody(body(text)))
            profiles.importText("Binary", source("mrs"))
            assertTrue("mrs:$MAX_RULE_INPUT_BYTES" in native)
            for (format in listOf("text", "yaml", "mrs")) {
                server.enqueue(MockResponse().setBody(body(text).writeByte('x'.code)))
                val over = failure { runBlocking { profiles.importText("Over $format", source(format)) } } as ConfigValidationException
                assertEquals("Resource download/copy failed: Resource exceeds $MAX_RULE_INPUT_BYTES bytes", over.issues.single().reason)
            }
            server.enqueue(MockResponse().setBody("first.example\n" + "e".repeat(2048) + "\n"))
            assertEquals(2, resources.snapshot(profiles.importText("Widest", source("text")).id).entries.single { it.key == key }.count)
            server.enqueue(MockResponse().setBody("first.example\n" + "e".repeat(2049) + "\n"))
            assertEquals("$key[1]: Rule entry exceeds 2048 UTF-8 bytes", issue(failure { runBlocking { profiles.importText("Wide", source("text")) } }))
            val yaml = yamlOfSize(MAX_RULE_INPUT_BYTES)
            server.enqueue(MockResponse().setBody(body(yaml)))
            assertEquals(1, resources.snapshot(profiles.importText("Large yaml", source("yaml")).id).entries.single { it.key == key }.count)
            server.enqueue(MockResponse().setBody(body(yamlEntries(MAX_RULE_PAYLOAD_ENTRIES))))
            val yamlId = profiles.importText("Yaml", source("yaml")).id
            assertEquals(MAX_RULE_PAYLOAD_ENTRIES, resources.snapshot(yamlId).entries.single { it.key == key }.count)
            server.enqueue(MockResponse().setBody(body(yamlEntries(MAX_RULE_PAYLOAD_ENTRIES + 1))))
            assertEquals("$.payload[250000]: Expanded configuration exceeds 250002 values", issue(failure { runBlocking { profiles.importText("Many", source("yaml")) } }))
        }
    }

    @Test fun resourcePagePreviewOpensUpToTheNativeTextBudget() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val runtime = File(temporary.root, "runtime")
            val validator = CompatibilityValidator(NativeConfigValidator { null }, runtime)
            val profiles = ProfileRepository(temporary.root, validator, resources = ResourceBundleManager(runtime, validator, NativeResourceValidator { _, _, _, _ -> null }))
            val resources = ResourceManagementRepository(profiles)
            fun source(path: String) = "rule-providers:\n  big: {type: http, url: '${server.url(path)}', path: big.txt, behavior: domain, format: text}\nrules: ['RULE-SET,big,DIRECT', 'MATCH,DIRECT']\n"
            for ((name, size, head) in listOf(Triple("exact", MAX_PROFILE_BYTES.toLong(), "example.com\n#"), Triple("bom", MAX_PROFILE_BYTES + 3L, "${bom}example.com\n#"))) {
                server.enqueue(MockResponse().setBody(body(fixture(size, head))))
                val shown = resources.preview(profiles.importText(name, source("/$name")).id, "$.rule-providers.big")
                assertEquals(MAX_PROFILE_BYTES, shown.toByteArray().size)
            }
            for (size in listOf(MAX_PROFILE_BYTES + 1L, MAX_RULE_TEXT_BYTES)) {
                server.enqueue(MockResponse().setBody(body(fixture(size, "example.com\n#"))))
                val id = profiles.importText("over-$size", source("/over-$size")).id
                assertEquals(size.toInt(), resources.preview(id, "$.rule-providers.big").length)
            }
        }
    }

    @Test fun libraryCacheSyncUsesTheNativeBoundsForEveryRuleFormat() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val l = Library(temporary.newFolder("cache"))
            val exact = mapOf("text" to fixture(MAX_RULE_INPUT_BYTES, "example.com\n#"), "yaml" to yamlOfSize(MAX_RULE_INPUT_BYTES), "mrs" to fixture(MAX_RULE_INPUT_BYTES, "example.com\n#"))
            for ((format, file) in exact) {
                server.enqueue(MockResponse().setBody(body(file)))
                val saved = l.manager.syncLibraryRule("rules-$format", server.url("/$format").toString(), "domain", format)
                assertEquals(MAX_RULE_INPUT_BYTES, saved.bytes)
                assertEquals(if (format == "mrs") null else 1, saved.count)
                server.enqueue(MockResponse().setBody(body(file).writeByte('x'.code)))
                val over = failure { runBlocking { l.manager.syncLibraryRule("over-$format", server.url("/over-$format").toString(), "domain", format) } }
                assertTrue(over is IOException)
                assertEquals("Resource exceeds $MAX_RULE_INPUT_BYTES bytes", over.message)
            }
            server.enqueue(MockResponse().setBody(body(yamlEntries(MAX_RULE_PAYLOAD_ENTRIES))))
            assertEquals(MAX_RULE_PAYLOAD_ENTRIES, l.manager.syncLibraryRule("many", server.url("/many").toString(), "domain", "yaml").count)
            server.enqueue(MockResponse().setBody(body(yamlEntries(MAX_RULE_PAYLOAD_ENTRIES + 1))))
            assertEquals("$.payload[250000]: Expanded configuration exceeds 250002 values",
                issue(failure { runBlocking { l.manager.syncLibraryRule("more", server.url("/more").toString(), "domain", "yaml") } }))
            server.enqueue(MockResponse().setBody("a.example\n" + "w".repeat(2049) + "\n"))
            assertEquals("$.rule-providers.library.payload[1]: Rule entry exceeds 2048 UTF-8 bytes",
                issue(failure { runBlocking { l.manager.syncLibraryRule("wide", server.url("/wide").toString(), "domain", "text") } }))
            server.enqueue(MockResponse().setBody("a.example\n" + "w".repeat(2048) + "\n"))
            assertEquals(2, l.manager.syncLibraryRule("widest", server.url("/widest").toString(), "domain", "text").count)
        }
    }

    @Test fun globalLibraryImportPreviewAndMaterializationStopAtTheirBounds() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val l = Library(temporary.newFolder("library"))
            val exact = fixture(MAX_RULE_INPUT_BYTES, "example.com\n#")
            var saved = l.workspace.rules.importFile(l.library.snapshot(), "text", GlobalLibraryFile("text.txt") { exact.inputStream() })
            val text = saved.resources.ruleProviders.single { it.name == "text" }.id!!
            assertEquals(MAX_RULE_INPUT_BYTES, l.payloads.info(saved, text).bytes)
            val large = l.workspace.rules.preview(saved, text)
            assertFalse(large.oversize)
            assertEquals(MAX_RULE_INPUT_BYTES.toInt(), large.editorText().length)
            val picked = failure { runBlocking { l.workspace.rules.importFile(saved, "over", GlobalLibraryFile("over.txt") { java.io.SequenceInputStream(exact.inputStream(), "x".byteInputStream()) }) } }
            assertTrue(picked is IllegalArgumentException)
            assertEquals("Rule resource exceeds 64 MiB", picked.message)
            val fits = fixture(MAX_PROFILE_BYTES.toLong(), "example.com\n#")
            saved = l.workspace.rules.importFile(saved, "fits", GlobalLibraryFile("fits.txt") { fits.inputStream() })
            val opened = l.workspace.rules.preview(saved, saved.resources.ruleProviders.single { it.name == "fits" }.id!!)
            assertEquals(MAX_PROFILE_BYTES, opened.editorText().length)

            server.enqueue(MockResponse().setBody(body(exact)))
            saved = l.payloads.importUrl(saved, "remote", server.url("/remote").toString())
            server.enqueue(MockResponse().setBody(body(exact).writeByte('x'.code)))
            assertEquals("Resource exceeds $MAX_RULE_INPUT_BYTES bytes", failure { runBlocking { l.payloads.importUrl(saved, "remote-over", server.url("/over").toString()) } }.message)

            val binary = fixture(MAX_RULE_INPUT_BYTES, byteArrayOf(0xff.toByte()))
            saved = l.workspace.rules.importFile(saved, "binary", GlobalLibraryFile("binary.mrs") { binary.inputStream() })
            val mrs = saved.resources.ruleProviders.single { it.name == "binary" }.id!!
            l.parser.dumpText = "a".repeat(MAX_PROFILE_BYTES)
            assertEquals(MAX_PROFILE_BYTES, l.payloads.preview(saved, mrs).text.length)
            l.parser.dumpText = "a".repeat(MAX_PROFILE_BYTES + 1)
            assertFalse(l.payloads.preview(saved, mrs).oversize)
            l.parser.dumpText = "a".repeat(MAX_RULE_TEXT_BYTES.toInt())
            assertEquals(MAX_RULE_TEXT_BYTES.toInt(), l.payloads.preview(saved, mrs).text.length)
            l.parser.dumpText = l.parser.dumpText + "a"
            assertEquals("Rule preview exceeds 67108864 UTF-8 bytes", failure { runBlocking { l.payloads.preview(saved, mrs) } }.message)

            l.parser.dumpText = String(fixture(MAX_RULE_TEXT_BYTES, "materialized.example\n#").readBytes(), Charsets.UTF_8)
            saved = l.payloads.saveText(saved, null, "materialized", "materialized.example\n")
            assertNotNull(saved.resources.ruleProviders.singleOrNull { it.name == "materialized" })
            l.parser.dumpText = l.parser.dumpText + "x"
            assertEquals("Materialized rule payload exceeds 67108864 UTF-8 bytes", failure { runBlocking { l.payloads.saveText(saved, null, "materialized-over", "materialized.example\n") } }.message)
            l.parser.dumpText = null
        }
    }

    @Test fun profilesOnlyRestoreBindsGeoFromEveryRuleLineUpToTheInputBound() {
        val runtime = temporary.newFolder("restore")
        val manager = ResourceBundleManager(runtime, CompatibilityValidator(NativeConfigValidator { null }, runtime), NativeResourceValidator { _, _, _, _ -> null })
        val geoUrls = mapOf("mmdb" to "https://geo.example/country.mmdb", "geosite" to "https://geo.example/site.dat")
        val mmdb = temporary.newFile("country.mmdb"); val site = temporary.newFile("site.dat")
        fun rule(format: String, file: File) = CachedLibraryRule(ManagedRuleResource("r", "https://rules.example/$format", "classical", format, file.length(), "0", 0, 1), file)
        val geo = listOf(CachedLibraryRule(ManagedRuleResource("mmdb", geoUrls.getValue("mmdb"), "", "", 1, "0", 0, null, "mmdb"), mmdb),
            CachedLibraryRule(ManagedRuleResource("geosite", geoUrls.getValue("geosite"), "", "", 1, "0", 0, null, "geosite"), site))
        fun bound(format: String, file: File): Set<File> {
            val source = "rule-providers:\n  r: {type: http, url: 'https://rules.example/$format', behavior: classical, format: $format}\nrules: ['RULE-SET,r,DIRECT', 'MATCH,DIRECT']\n"
            return manager.localizeLibraryPayloads(source, listOf(rule(format, file)) + geo, geoUrls).values.toSet()
        }
        val late = fixture(MAX_RULE_INPUT_BYTES, "DOMAIN,a.example,DIRECT\n#", tail = "\nGEOIP,CN,DIRECT\n")
        assertEquals(setOf(late, mmdb), bound("text", late))
        val commented = temporary.newFile().apply { writeText("DOMAIN,a.example,DIRECT\n# GEOIP,CN,DIRECT\n") }
        assertEquals(setOf(commented), bound("text", commented))
        val yaml = temporary.newFile().apply { writeText("payload:\n  - DOMAIN,a.example,DIRECT\n  - 'GEOSITE,cn,DIRECT'\n") }
        assertEquals(setOf(yaml, site), bound("yaml", yaml))
        val largeYaml = fixture(MAX_RULE_INPUT_BYTES, "payload:\n  - DOMAIN,a.example,DIRECT\n  - GEOSITE,cn,DIRECT\n#", "x".repeat(998) + "\n#")
        assertEquals(setOf(largeYaml, site), bound("yaml", largeYaml))
        for (format in listOf("text", "yaml")) {
            val over = fixture(MAX_RULE_INPUT_BYTES + 1, "payload:\n  - GEOIP,CN,DIRECT\n#", "x".repeat(998) + "\n#")
            assertEquals("Input exceeds $MAX_RULE_INPUT_BYTES bytes", failure { bound(format, over) }.message)
        }
    }

    @Test fun preparationBindsGeoFromStreamedRuleLinesAndValidatesBeforeTheEntryBound() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse().setBody("geo-fixture:${request.path}")
            }
            server.start()
            val runtime = temporary.newFolder("geo")
            val manager = ResourceBundleManager(runtime, CompatibilityValidator(NativeConfigValidator { null }, runtime), NativeResourceValidator { _, _, _, _ -> null })
            val urls = mapOf("mmdb" to server.url("/mmdb").toString(), "asn-mmdb" to server.url("/asn").toString(), "geosite" to server.url("/geosite").toString())
            suspend fun keys(format: String, payload: String): Set<String> {
                val profile = java.util.UUID.randomUUID().toString()
                manager.importLocal(profile, "rules.$format", payload.byteInputStream())
                val source = "rule-providers:\n  r: {type: file, path: rules.$format, behavior: classical, format: $format}\nrules: ['RULE-SET,r,DIRECT', 'MATCH,DIRECT']\n"
                val records = org.json.JSONObject(File(manager.prepare(profile, source, geoUrls = urls).directory, "manifest.json").readText()).getJSONArray("records")
                return (0 until records.length()).map { records.getJSONObject(it).getString("key") }.filterNot { it.startsWith("$.rule-providers") }.toSet()
            }
            assertEquals(setOf("$.mmdb"), keys("text", "DOMAIN,a.example,DIRECT\n# GEOSITE,cn,DIRECT\nGEOIP,CN,DIRECT\n"))
            assertEquals(setOf("$.geosite", "$.asn-mmdb"), keys("yaml", "payload:\n  - DOMAIN,a.example,DIRECT\n  - 'GEOSITE,cn,DIRECT'\n  - IP-ASN,13335,DIRECT\n"))
            assertEquals(emptySet<String>(), keys("text", "DOMAIN,a.example,DIRECT\n"))
            val both = failure { runBlocking { keys("text", "UID,1000,DIRECT\n" + "e".repeat(2049) + "\n") } } as ConfigValidationException
            assertEquals("$.rule-providers.r[0]", both.issues.single().path)
        }
    }

    @Test fun pickedIconSetsKeepTheirOwnUnchangedBound() = runBlocking<Unit> {
        val l = Library(temporary.newFolder("icons"))
        assertTrue(l.workspace.prepare())
        val head = """{"icons":[{"name":"one","url":"https://icons.example/one.png"}],"pad":"""".toByteArray()
        val exact = ByteArray(mib32.toInt()).also { bytes ->
            head.copyInto(bytes); bytes.fill('x'.code.toByte(), head.size, bytes.size - 2)
            bytes[bytes.size - 2] = '"'.code.toByte(); bytes[bytes.size - 1] = '}'.code.toByte()
        }
        assertTrue(l.workspace.session.importIcons(GlobalLibraryFile("icons.json", exact)))
        assertEquals(1, l.library.snapshot().resources.iconSets.size)
        assertFalse(l.workspace.session.importIcons(GlobalLibraryFile("over.json", exact + ' '.code.toByte())))
        assertEquals("Icon set exceeds 32 MiB", l.workspace.session.state.value.error)
        l.workspace.closeAndJoin()
    }
}
