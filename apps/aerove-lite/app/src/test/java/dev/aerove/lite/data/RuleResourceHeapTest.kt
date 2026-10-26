package dev.aerove.lite.data

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Worst-case rule inputs at and past the bounds, each read by production code in a child JVM limited to a 256 MiB heap (or less). */
class RuleResourceHeapTest {
    @get:Rule val temporary = TemporaryFolder()
    private val size = MAX_RULE_INPUT_BYTES
    private val wide = "Ā"

    private fun probe(mode: String, file: File, heap: String = "256m"): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(java, "-Xmx$heap", "-cp", System.getProperty("java.class.path"), RuleResourceHeapProbe::class.java.name, mode, file.path)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(10, TimeUnit.MINUTES))
        return output.trim().lines().last()
    }

    private fun write(head: String, line: (Int) -> String, tail: String = ""): File = temporary.newFile().apply {
        outputStream().buffered(1 shl 16).use { out ->
            out.write(head.toByteArray())
            var written = head.toByteArray().size.toLong() + tail.toByteArray().size
            var index = 0
            while (true) {
                val next = line(index).toByteArray()
                if (written + next.size > size) break
                out.write(next); written += next.size; index++
            }
            val pad = size - written
            if (pad > 0) out.write(("#" + "x".repeat((pad - 2).toInt().coerceAtLeast(0)) + "\n").toByteArray().copyOf(pad.toInt()))
            out.write(tail.toByteArray())
        }
    }

    @Test fun manyShortTextLinesAreStreamedNotKept() {
        val lines = write("", { "x.y\n" })
        assertEquals("ok 16777216", probe("lines", lines))
        assertEquals("ok 16777216", probe("count", lines))
        val entry = temporary.newFile().apply { outputStream().buffered().use { out -> repeat((size - 1).toInt()) { out.write('x'.code) }; out.write('\n'.code) } }
        assertEquals("dev.aerove.lite.data.ConfigValidationException: $.probe[0]: Rule entry exceeds 2048 UTF-8 bytes", probe("lines", entry))
        assertEquals("ok 1", probe("count", entry))
        val at = write("", { "DOMAIN-KEYWORD,k${"%07d".format(it)}${"x".repeat(240)}\n" })
        assertTrue(probe("prepare-text", at).startsWith("ok "))
        val blank = temporary.newFile().apply { outputStream().buffered().use { out -> out.write("example.com".toByteArray()); repeat((size - 12).toInt()) { out.write(' '.code) }; out.write('\n'.code) } }
        assertEquals("ok 1", probe("lines", blank))
        // Streaming keeps one line within the entry bound, so trailing blanks of any length fit a heap far below the input.
        assertEquals("ok 1", probe("lines", blank, "48m"))
    }

    @Test fun yamlAtTheValueBoundFitsAndOneValueMoreStopsBeforeAnyNodeGraph() {
        val bound = write("payload:\n", { if (it < MAX_RULE_PAYLOAD_ENTRIES) "  - 'DOMAIN-KEYWORD,k${"%07d".format(it)}${"x".repeat(230)}'\n" else "#${"x".repeat(1000)}\n" })
        assertEquals("ok 250000", probe("yaml", bound))
        assertTrue(probe("prepare-yaml", bound).startsWith("ok "))
        val utf16 = write("payload:\n", { if (it < MAX_RULE_PAYLOAD_ENTRIES) "  - '$wide${"%07d".format(it)}${"x".repeat(230)}'\n" else "#${"x".repeat(1000)}\n" })
        assertEquals("ok 250000", probe("yaml", utf16))
        val short = write("payload:\n", { "- a\n" })
        for (mode in listOf("yaml", "loader")) assertEquals("dev.aerove.lite.data.ConfigValidationException: $.payload[250000]: Expanded configuration exceeds 250002 values", probe(mode, short))
        val comment = temporary.newFile().apply { outputStream().buffered().use { out -> out.write("payload:\n  - a\n#".toByteArray()); repeat((size - 17).toInt()) { out.write('x'.code) }; out.write('\n'.code) } }
        assertEquals("dev.aerove.lite.data.ConfigValidationException: $: YAML scalar, comment or gap exceeds $MAX_RULE_YAML_TOKEN_CHARS characters at line 3", probe("yaml", comment))
    }

    @Test fun previewAtTheNativeTextBudgetOpensWithOneCopy() {
        val text = write("example.com\n", { "#${"x".repeat(1000)}\n" })
        assertEquals("ok $size", probe("preview", text))
    }
}
